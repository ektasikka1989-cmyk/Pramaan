package com.example.arfas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.nio.charset.Charset
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.math.max

private const val ATTACH_TAG = "Phase1Attachment"

enum class Phase1AttachmentKind {
    IMAGE,
    TEXT_DOCUMENT,
    DOCX_DOCUMENT,
    LEGACY_WORD_DOCUMENT,
    PPTX_DOCUMENT,
    LEGACY_PPT_DOCUMENT,
    PDF_DOCUMENT
}

/**
 * One user-selected source bound to exactly one accepted user turn.
 *
 * Offline-first attachment contract:
 * - raw pixels/files never enter the text model directly;
 * - deterministic local extraction (OCR/object/barcode/document text) becomes
 *   bounded evidence for Gemma;
 * - cloud vision is required only when local extraction found no useful visual
 *   evidence, or when the turn independently requires a live/external capability.
 */
data class Phase1UserAttachment(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val kind: Phase1AttachmentKind,
    val extractedText: String = "",
    val localVisualContext: String = "",
    val localEvidenceSubstantial: Boolean = false,
    val localAnalysisWarnings: List<String> = emptyList(),
    val visionBitmaps: List<Bitmap> = emptyList(),
    /** Compact JPEG samples retained only for cloud escalation when local visual coverage was bounded. */
    val cloudRecoveryJpegs: List<ByteArray> = emptyList(),
    val pageCount: Int = 0,
    /** App-private source copy retained for conversation-scoped follow-ups and on-demand page rendering. */
    val sourceCachePath: String = "",
    val truncated: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis()
) {
    val hasLocalEvidence: Boolean
        get() = when (kind) {
            Phase1AttachmentKind.IMAGE -> localEvidenceSubstantial
            else -> extractedText.isNotBlank() || localEvidenceSubstantial
        }

    val isVisualSource: Boolean
        get() = kind == Phase1AttachmentKind.IMAGE ||
            (kind == Phase1AttachmentKind.PDF_DOCUMENT && visionBitmaps.isNotEmpty())

    /** True when this source contains retained pixels that a capable local VLM can inspect.
     * This is intentionally independent of ML Kit success: ML Kit evidence is a text-model
     * fallback, not the capability switch for a vision-language model.
     */
    val hasLocalVlmPixels: Boolean
        get() = visionBitmaps.any { !it.isRecycled && it.width > 0 && it.height > 0 }

    /**
     * Local-first attachment contract:
     * - a user-selected IMAGE is handled locally whenever ML Kit produced useful
     *   OCR/object/barcode evidence; its pixels are still retained so Cloud vision can
     *   be used later only if Local genuinely fails and Cloud is configured;
     * - PDF/Office pages need Cloud vision only when local extraction produced no
     *   usable evidence.
     *
     * The current Gemma model is text-only, so offline photo understanding is limited
     * to the visual facts ML Kit can extract locally. Raw-pixel reasoning requires a
     * local VLM and is intentionally not faked here.
     */
    val needsVision: Boolean
        get() = when (kind) {
            Phase1AttachmentKind.IMAGE -> visionBitmaps.isNotEmpty() && !hasLocalEvidence
            Phase1AttachmentKind.PDF_DOCUMENT,
            Phase1AttachmentKind.DOCX_DOCUMENT,
            Phase1AttachmentKind.PPTX_DOCUMENT,
            Phase1AttachmentKind.LEGACY_PPT_DOCUMENT -> visionBitmaps.isNotEmpty() && !hasLocalEvidence
            else -> false
        }

    fun sourceId(): String = "user_attachment:$id"

    fun relevantText(query: String, maxChars: Int = 5_000): String {
        if (extractedText.isBlank()) return ""
        return selectRelevantAttachmentText(query, extractedText, maxChars)
    }

    fun toContextAttachment(query: String, selectedTextOverride: String? = null): BaselineContextAttachment? {
        val text = selectedTextOverride?.trim().orEmpty().ifBlank { relevantText(query, 12_000) }
        val visual = localVisualContext.trim().take(2_400)
        if (!hasLocalEvidence || (text.isBlank() && visual.isBlank())) return null

        return BaselineContextAttachment(
            sourceId = sourceId(),
            text = buildString {
                append("User attachment: ")
                append(displayName)
                append("\n")
                // Short and positive: the longer "cannot be determined from the available offline
                // extraction" wording made the 3B model hedge even when the answer was in the text.
                append(
                    "The text and observations below were read from this file on the phone. Answer from them; " +
                        "if the asked detail is not in them, say briefly that the file text does not show it.\n"
                )
                if (visual.isNotBlank()) {
                    append("\n[On-device visual analysis]\n")
                    append(visual)
                }
                if (text.isNotBlank()) {
                    append("\n\n[Locally extracted document/OCR text]\n")
                    append(text)
                }
                if (truncated) {
                    append("\n[Attachment extraction was bounded; the source may contain additional material.]" )
                }
                if (localAnalysisWarnings.isNotEmpty()) {
                    append("\n[Local extraction status: ")
                    append(localAnalysisWarnings.distinct().joinToString(", "))
                    append("]")
                }
            },
            priority = 100,
            attributes = mapOf(
                "turn_scoped" to "true",
                "user_attachment" to "true",
                "attachment_id" to id,
                "attachment_name" to displayName,
                "attachment_kind" to kind.name,
                "offline_attachment_evidence" to "true",
                "local_visual_evidence" to localVisualContext.isNotBlank().toString(),
                "grounding_required" to "true"
            )
        )
    }

    fun decodeCloudRecoveryBitmaps(maxImages: Int = 4): List<Bitmap> =
        cloudRecoveryJpegs.asSequence()
            .take(maxImages.coerceAtLeast(0))
            .mapNotNull { bytes -> runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() }
            .filter { !it.isRecycled && it.width > 0 && it.height > 0 }
            .toList()

    fun recycle() {
        visionBitmaps.forEach { bmp ->
            runCatching { if (!bmp.isRecycled) bmp.recycle() }
        }
    }
}

/**
 * Offline-first attachment preparation boundary.
 *
 * Supported:
 * - Images: bounded decode + ML Kit OCR/object/barcode evidence.
 * - Text/log/source/CSV/JSON/XML: BOM-aware bounded text extraction.
 * - DOCX/DOCM: body + headers/footers/notes + OCR of embedded images.
 * - PPTX/PPTM/PPSX: slide text + speaker notes + chart/diagram XML + OCR of embedded images.
 * - Legacy DOC/PPT: optional Apache POI through reflection when bundled by the host;
 *   otherwise a bounded Unicode/Windows text recovery fallback.
 * - PDF: PdfRenderer + on-device OCR; first pages also receive object/barcode
 *   analysis. A few page bitmaps are retained only as cloud-vision recovery.
 */
class Phase1AttachmentManager(private val context: Context) {
    companion object {
        private const val MAX_TEXT_BYTES = 2 * 1024 * 1024
        private const val MAX_DOCX_ENTRY_BYTES = 4 * 1024 * 1024
        private const val MAX_DOCX_MEDIA_BYTES = 8 * 1024 * 1024
        private const val MAX_DOCX_MEDIA_IMAGES = 3
        private const val MAX_PPTX_ENTRY_BYTES = 4 * 1024 * 1024
        private const val MAX_PPTX_MEDIA_BYTES = 8 * 1024 * 1024
        private const val MAX_PPTX_MEDIA_IMAGES = 4
        private const val MAX_PPTX_SLIDES = 160
        private const val MAX_OFFICE_VISION_IMAGES = 1
        private const val MAX_PPTX_AUX_PARTS = 80
        private const val MAX_LEGACY_DOC_BYTES = 8 * 1024 * 1024
        private const val MAX_LEGACY_PPT_BYTES = 12 * 1024 * 1024
        private const val MAX_EXTRACTED_CHARS = 3_000_000
        // Product contract: PDFs up to 100 pages are fully ingested offline.
        // OCR/native text can cover all 100 pages; VLM rendering remains query-driven.
        private const val MAX_PDF_OCR_PAGES = 100
        private const val MAX_PDF_NATIVE_TEXT_PAGES = 100
        private const val MAX_PDF_LOCAL_ANALYSIS_PAGES = 6
        private const val MAX_PDF_VISION_PAGES = 1
        private const val PDF_RENDER_LONG_SIDE = 1_400
        private const val MAX_IMAGE_LONG_SIDE = 1_280
        private const val MAX_RETAINED_VISION_LONG_SIDE = 960
        private const val MAX_CLOUD_RECOVERY_IMAGES = 4
        private const val MAX_CACHED_SOURCE_BYTES = 256 * 1024 * 1024
        private const val CLOUD_RECOVERY_LONG_SIDE = 960
        private const val CLOUD_RECOVERY_JPEG_QUALITY = 76
    }

    private val appContext = context.applicationContext
    private val mlKit = MLKitProcessor()

    private val ownershipLock = Any()
    private val pending = mutableListOf<Phase1UserAttachment>()
    private val activeTurn = mutableListOf<Phase1UserAttachment>()
    private val maxPendingAttachments = 5

    suspend fun stageUri(uri: Uri): Phase1UserAttachment? = withContext(Dispatchers.IO) {
        val resolver = appContext.contentResolver
        val mime = resolver.getType(uri).orEmpty().lowercase()
        val name = queryDisplayName(uri).ifBlank { "attachment" }

        val prepared = try {
            VisionWorkCoordinator.withVisionWork("attachment_stage") {
                when {
                    mime.startsWith("image/") || hasImageExtension(name) -> prepareImage(uri, name, mime)
                    mime == "application/pdf" || name.endsWith(".pdf", true) -> preparePdf(uri, name, mime)
                    isModernWord(mime, name) -> prepareDocx(uri, name, mime)
                    isLegacyWord(mime, name) -> prepareLegacyWord(uri, name, mime)
                    isModernPresentation(mime, name) -> preparePptx(uri, name, mime)
                    isLegacyPresentation(mime, name) -> prepareLegacyPpt(uri, name, mime)
                    mime.startsWith("text/") || isTextLikeName(name) -> prepareText(uri, name, mime)
                    else -> {
                        Log.w(ATTACH_TAG, "ATTACHMENT_UNSUPPORTED name=$name mime=$mime")
                        null
                    }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.e(
                ATTACH_TAG,
                "ATTACHMENT_PREPARE_FAILED name=$name mime=$mime ${t.javaClass.simpleName}: ${t.message}",
                t
            )
            null
        }

        val retained = if (prepared != null) {
            val cached = cacheSelectedSource(uri, prepared.id, name)
            if (cached != null) prepared.copy(sourceCachePath = cached.absolutePath) else prepared
        } else null

        if (retained != null) appendPending(retained)
        retained
    }

    suspend fun stageCapturedBitmap(bitmap: Bitmap): Phase1UserAttachment = withContext(Dispatchers.Default) {
        VisionWorkCoordinator.withVisionWork("attachment_camera_stage") {
        val safe = scaleBitmapBounded(bitmap, MAX_IMAGE_LONG_SIDE)
        val local = cancellableResult { mlKit.process(safe) }.getOrElse {
            Log.w(ATTACH_TAG, "CAPTURE_LOCAL_ANALYSIS_FAILED ${it.message}")
            MLKitResult(sensorWarnings = listOf("LOCAL_IMAGE_ANALYSIS_FAILED"))
        }
        val attachmentId = UUID.randomUUID().toString()
        val cachedSource = cacheBitmapSource(safe, attachmentId)
        val prepared = Phase1UserAttachment(
            id = attachmentId,
            displayName = "camera-photo.jpg",
            mimeType = "image/jpeg",
            kind = Phase1AttachmentKind.IMAGE,
            extractedText = local.ocrText.take(MAX_EXTRACTED_CHARS),
            localVisualContext = local.toPromptContext(),
            localEvidenceSubstantial = local.isSubstantial,
            localAnalysisWarnings = local.sensorWarnings,
            visionBitmaps = listOf(safe),
            sourceCachePath = cachedSource?.absolutePath.orEmpty()
        )
        appendPending(prepared)
        prepared
        }
    }

    fun peekPending(): Phase1UserAttachment? = synchronized(ownershipLock) { pending.lastOrNull() }

    fun peekPendingAll(): List<Phase1UserAttachment> = synchronized(ownershipLock) { pending.toList() }

    fun pendingDisplayLabel(): String = synchronized(ownershipLock) {
        when (pending.size) {
            0 -> ""
            1 -> pending.first().displayName
            2 -> pending.joinToString(" + ") { it.displayName }
            else -> "${pending.first().displayName} + ${pending.size - 1} more"
        }
    }

    /** Atomically binds every staged source to one submitted user turn. */
    fun claimPendingAttachmentsForTurn(): List<Phase1UserAttachment> = synchronized(ownershipLock) {
        if (activeTurn.isNotEmpty() || pending.isEmpty()) return@synchronized emptyList()
        val claimed = pending.toList()
        pending.clear()
        activeTurn.addAll(claimed)
        CrashBreadcrumbs.add("ATTACHMENT_CLAIM count=${claimed.size} names=${claimed.joinToString { it.displayName }}")
        Log.i(ATTACH_TAG, "ATTACHMENT_CLAIMED count=${claimed.size} names=${claimed.joinToString { it.displayName }}")
        claimed
    }

    /** Backward-compatible single-source API. */
    fun claimPendingForTurn(): Phase1UserAttachment? = claimPendingAttachmentsForTurn().firstOrNull()

    fun releaseTurnAttachments(attachmentIds: Collection<String>, consumed: Boolean) {
        if (attachmentIds.isEmpty()) return
        val ids = attachmentIds.toSet()
        val recycle = mutableListOf<Phase1UserAttachment>()
        synchronized(ownershipLock) {
            val matched = activeTurn.filter { it.id in ids }
            activeTurn.removeAll { it.id in ids }
            if (consumed) {
                recycle += matched
            } else {
                val room = (maxPendingAttachments - pending.size).coerceAtLeast(0)
                pending.addAll(0, matched.take(room))
                recycle += matched.drop(room)
            }
        }
        recycle.forEach { it.recycle() }
        Log.i(ATTACH_TAG, "ATTACHMENT_LEASE_RELEASE count=${ids.size} consumed=$consumed")
    }

    fun releaseTurnAttachment(attachmentId: String, consumed: Boolean) =
        releaseTurnAttachments(listOf(attachmentId), consumed)

    fun markConsumed(attachmentId: String) = releaseTurnAttachment(attachmentId, consumed = true)

    fun clearPending() {
        val old = synchronized(ownershipLock) {
            val values = pending.toList()
            pending.clear()
            values
        }
        old.forEach { attachment ->
            attachment.recycle()
            if (attachment.sourceCachePath.isNotBlank()) runCatching { File(attachment.sourceCachePath).delete() }
        }
    }

    fun close() {
        val toRecycle = synchronized(ownershipLock) {
            val values = (pending + activeTurn).distinctBy { it.id }
            pending.clear()
            activeTurn.clear()
            values
        }
        toRecycle.forEach { attachment ->
            attachment.recycle()
            if (attachment.sourceCachePath.isNotBlank()) runCatching { File(attachment.sourceCachePath).delete() }
        }
        runCatching { mlKit.close() }
    }

    private fun appendPending(next: Phase1UserAttachment) {
        val recycle = mutableListOf<Phase1UserAttachment>()
        synchronized(ownershipLock) {
            pending += next
            while (pending.size > maxPendingAttachments) recycle += pending.removeAt(0)
        }
        recycle.forEach { attachment ->
            attachment.recycle()
            if (attachment.sourceCachePath.isNotBlank()) runCatching { File(attachment.sourceCachePath).delete() }
        }
        Log.i(
            ATTACH_TAG,
            "ATTACHMENT_STAGED id=${next.id} kind=${next.kind} name=${next.displayName} " +
                "pendingCount=${peekPendingAll().size} textChars=${next.extractedText.length} " +
                "images=${next.visionBitmaps.size} pages=${next.pageCount} truncated=${next.truncated}"
        )
    }

    private fun cacheSelectedSource(uri: Uri, attachmentId: String, displayName: String): File? {
        return runCatching {
            val dir = File(appContext.cacheDir, "fixsight_attachments").apply { mkdirs() }
            val suffix = displayName.substringAfterLast('.', "bin")
                .lowercase().replace(Regex("[^a-z0-9]"), "").take(10).ifBlank { "bin" }
            val out = File(dir, "${attachmentId}.$suffix")
            val input = appContext.contentResolver.openInputStream(uri) ?: return@runCatching null
            input.use { source ->
                FileOutputStream(out).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > MAX_CACHED_SOURCE_BYTES) {
                            throw IllegalStateException("Attachment exceeds ${MAX_CACHED_SOURCE_BYTES / (1024 * 1024)} MB cache limit")
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            out
        }.onFailure {
            // Never leave a partially copied large source behind.  Source caching is
            // disk-streamed and optional for the initial extraction, but a clean cache
            // is required for later on-demand page rendering / Cloud recovery.
            runCatching {
                val dir = File(appContext.cacheDir, "fixsight_attachments")
                val suffix = displayName.substringAfterLast('.', "bin")
                    .lowercase().replace(Regex("[^a-z0-9]"), "").take(10).ifBlank { "bin" }
                File(dir, "${attachmentId}.$suffix").delete()
            }
            Log.w(ATTACH_TAG, "ATTACHMENT_SOURCE_CACHE_FAILED name=$displayName ${it.message}")
        }.getOrNull()
    }

    private fun cacheBitmapSource(bitmap: Bitmap, attachmentId: String): File? {
        return runCatching {
            val dir = File(appContext.cacheDir, "fixsight_attachments").apply { mkdirs() }
            val out = File(dir, "${attachmentId}.jpg")
            FileOutputStream(out).use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)) { "JPEG cache write failed" }
            }
            out
        }.onFailure { Log.w(ATTACH_TAG, "ATTACHMENT_BITMAP_CACHE_FAILED ${it.message}") }.getOrNull()
    }

    private suspend fun <T> cancellableResult(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        Result.failure(t)
    }

    private suspend fun prepareImage(uri: Uri, name: String, mime: String): Phase1UserAttachment? {
        val safe = decodeBitmapBounded(uri, MAX_IMAGE_LONG_SIDE) ?: return null
        val local = cancellableResult { mlKit.process(safe) }.getOrElse {
            Log.w(ATTACH_TAG, "IMAGE_LOCAL_ANALYSIS_FAILED name=$name ${it.message}")
            MLKitResult(sensorWarnings = listOf("LOCAL_IMAGE_ANALYSIS_FAILED"))
        }
        return Phase1UserAttachment(
            id = UUID.randomUUID().toString(),
            displayName = name,
            mimeType = mime.ifBlank { "image/*" },
            kind = Phase1AttachmentKind.IMAGE,
            extractedText = local.ocrText.take(MAX_EXTRACTED_CHARS),
            localVisualContext = local.toPromptContext(),
            localEvidenceSubstantial = local.isSubstantial,
            localAnalysisWarnings = local.sensorWarnings,
            visionBitmaps = listOf(safe)
        )
    }

    private fun prepareText(uri: Uri, name: String, mime: String): Phase1UserAttachment? {
        val bytes = readBounded(uri, MAX_TEXT_BYTES) ?: return null
        val text = decodeTextBytes(bytes)
            .replace('\u0000', ' ')
            .trim()
            .take(MAX_EXTRACTED_CHARS)
        if (text.isBlank()) return null
        return Phase1UserAttachment(
            id = UUID.randomUUID().toString(),
            displayName = name,
            mimeType = mime.ifBlank { "text/plain" },
            kind = Phase1AttachmentKind.TEXT_DOCUMENT,
            extractedText = text,
            truncated = bytes.size >= MAX_TEXT_BYTES
        )
    }

    private suspend fun prepareDocx(uri: Uri, name: String, mime: String): Phase1UserAttachment? {
        val xmlParts = mutableListOf<Pair<String, String>>()
        val embeddedEvidence = mutableListOf<String>()
        val embeddedOcr = StringBuilder()
        val retainedVisionImages = mutableListOf<Bitmap>()
        val cloudRecovery = mutableListOf<ByteArray>()
        val warnings = mutableListOf<String>()
        var mediaSeen = 0
        var bounded = false

        appContext.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val entryName = entry.name
                    try {
                        when {
                            isWordTextXml(entryName) -> {
                                val (bytes, clipped) = readZipEntryBounded(zip, MAX_DOCX_ENTRY_BYTES)
                                if (clipped) bounded = true
                                val xml = decodeTextBytes(bytes)
                                if (xml.isNotBlank()) xmlParts += entryName to xml
                            }
                            entryName.startsWith("word/media/") -> {
                                mediaSeen++
                                val shouldLocalAnalyze = mediaSeen <= MAX_DOCX_MEDIA_IMAGES
                                val shouldKeepCloudRecovery = !shouldLocalAnalyze && cloudRecovery.size < MAX_CLOUD_RECOVERY_IMAGES
                                if (shouldLocalAnalyze || shouldKeepCloudRecovery) {
                                    val (bytes, clipped) = readZipEntryBounded(zip, MAX_DOCX_MEDIA_BYTES)
                                    if (clipped) bounded = true
                                    val maxSide = if (shouldLocalAnalyze) MAX_IMAGE_LONG_SIDE else CLOUD_RECOVERY_LONG_SIDE
                                    val bmp = decodeByteArrayBounded(bytes, maxSide)
                                    if (bmp != null) {
                                        var retained = false
                                        try {
                                            if (shouldLocalAnalyze) {
                                                val local = cancellableResult { mlKit.process(bmp) }.getOrElse {
                                                    warnings += "DOCX_EMBEDDED_IMAGE_ANALYSIS_FAILED"
                                                    MLKitResult()
                                                }
                                                if (local.ocrText.isNotBlank()) {
                                                    embeddedOcr.append("\n[Embedded image ")
                                                        .append(mediaSeen)
                                                        .append("]\n")
                                                        .append(local.ocrText.trim())
                                                }
                                                val compact = local.toPromptContext()
                                                if (compact.isNotBlank() && local.isSubstantial) {
                                                    embeddedEvidence += "Embedded image $mediaSeen:\n$compact"
                                                }
                                                warnings += local.sensorWarnings
                                                if (retainedVisionImages.size < MAX_OFFICE_VISION_IMAGES) {
                                                    retainedVisionImages += bmp
                                                    retained = true
                                                }
                                            } else {
                                                compactCloudRecoveryJpeg(bmp)?.let { cloudRecovery += it }
                                                bounded = true
                                            }
                                        } finally {
                                            if (!retained && !bmp.isRecycled) bmp.recycle()
                                        }
                                    }
                                } else {
                                    bounded = true
                                }
                            }
                        }
                    } finally {
                        runCatching { zip.closeEntry() }
                    }
                }
            }
        } ?: return null

        val text = buildString {
            xmlParts.forEach { (entryName, xml) ->
                val extracted = extractOfficeXmlText(xml)
                if (extracted.isNotBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    when {
                        entryName == "word/document.xml" -> Unit
                        entryName.contains("header", true) -> append("[Header]\n")
                        entryName.contains("footer", true) -> append("[Footer]\n")
                        entryName.contains("footnotes", true) -> append("[Footnotes]\n")
                        entryName.contains("endnotes", true) -> append("[Endnotes]\n")
                    }
                    append(extracted)
                }
            }
            if (embeddedOcr.isNotBlank()) append(embeddedOcr)
        }.trim()

        val clipped = text.take(MAX_EXTRACTED_CHARS)
        // Keep visual-only Office files when pixels were decoded successfully. A selected
        // local VLM can reason over those pixels even when ML Kit OCR/labels are empty.
        if (clipped.isBlank() && embeddedEvidence.isEmpty() && retainedVisionImages.isEmpty()) return null

        return Phase1UserAttachment(
            id = UUID.randomUUID().toString(),
            displayName = name,
            mimeType = mime.ifBlank { "application/vnd.openxmlformats-officedocument.wordprocessingml.document" },
            kind = Phase1AttachmentKind.DOCX_DOCUMENT,
            extractedText = clipped,
            localVisualContext = embeddedEvidence.joinToString("\n\n").take(2_400),
            localEvidenceSubstantial = clipped.isNotBlank() || embeddedEvidence.isNotEmpty(),
            localAnalysisWarnings = warnings.distinct(),
            visionBitmaps = retainedVisionImages,
            cloudRecoveryJpegs = cloudRecovery,
            truncated = bounded || text.length > MAX_EXTRACTED_CHARS || mediaSeen > MAX_DOCX_MEDIA_IMAGES
        )
    }

    private fun prepareLegacyWord(uri: Uri, name: String, mime: String): Phase1UserAttachment? {
        val bytes = readBounded(uri, MAX_LEGACY_DOC_BYTES) ?: return null
        val poiText = extractLegacyWordWithPoi(bytes)
        val fallbackText = if (poiText.isNullOrBlank()) extractLegacyOfficeTextBestEffort(bytes) else ""
        val text = (poiText ?: fallbackText).trim().take(MAX_EXTRACTED_CHARS)
        if (text.isBlank()) return null
        val warning = if (poiText.isNullOrBlank()) listOf("LEGACY_DOC_BEST_EFFORT_EXTRACTION") else emptyList()
        return Phase1UserAttachment(
            id = UUID.randomUUID().toString(),
            displayName = name,
            mimeType = mime.ifBlank { "application/msword" },
            kind = Phase1AttachmentKind.LEGACY_WORD_DOCUMENT,
            extractedText = text,
            localAnalysisWarnings = warning,
            truncated = bytes.size >= MAX_LEGACY_DOC_BYTES || text.length >= MAX_EXTRACTED_CHARS
        )
    }

    private suspend fun preparePptx(uri: Uri, name: String, mime: String): Phase1UserAttachment? {
        val slides = mutableListOf<Pair<Int, String>>()
        val notes = mutableListOf<Pair<Int, String>>()
        val auxiliaryXml = mutableListOf<Pair<String, String>>()
        val embeddedEvidence = mutableListOf<String>()
        val embeddedOcr = StringBuilder()
        val retainedVisionImages = mutableListOf<Bitmap>()
        val cloudRecovery = mutableListOf<ByteArray>()
        val warnings = mutableListOf<String>()
        var mediaSeen = 0
        var slideEntriesSeen = 0
        var bounded = false

        appContext.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val entryName = entry.name
                    try {
                        when {
                            isPresentationSlideXml(entryName) -> {
                                slideEntriesSeen++
                                if (slides.size < MAX_PPTX_SLIDES) {
                                    val (bytes, clipped) = readZipEntryBounded(zip, MAX_PPTX_ENTRY_BYTES)
                                    if (clipped) bounded = true
                                    val xml = decodeTextBytes(bytes)
                                    if (xml.isNotBlank()) {
                                        slides += presentationPartNumber(entryName) to xml
                                    }
                                } else {
                                    bounded = true
                                }
                            }
                            isPresentationNotesXml(entryName) -> {
                                if (notes.size < MAX_PPTX_SLIDES) {
                                    val (bytes, clipped) = readZipEntryBounded(zip, MAX_PPTX_ENTRY_BYTES)
                                    if (clipped) bounded = true
                                    val xml = decodeTextBytes(bytes)
                                    if (xml.isNotBlank()) {
                                        notes += presentationPartNumber(entryName) to xml
                                    }
                                } else {
                                    bounded = true
                                }
                            }
                            isPresentationAuxiliaryXml(entryName) -> {
                                if (auxiliaryXml.size < MAX_PPTX_AUX_PARTS) {
                                    val (bytes, clipped) = readZipEntryBounded(zip, MAX_PPTX_ENTRY_BYTES)
                                    if (clipped) bounded = true
                                    val xml = decodeTextBytes(bytes)
                                    if (xml.isNotBlank()) auxiliaryXml += entryName to xml
                                } else {
                                    bounded = true
                                }
                            }
                            entryName.startsWith("ppt/media/") -> {
                                mediaSeen++
                                val shouldLocalAnalyze = mediaSeen <= MAX_PPTX_MEDIA_IMAGES
                                val shouldKeepCloudRecovery = !shouldLocalAnalyze && cloudRecovery.size < MAX_CLOUD_RECOVERY_IMAGES
                                if (shouldLocalAnalyze || shouldKeepCloudRecovery) {
                                    val (bytes, clipped) = readZipEntryBounded(zip, MAX_PPTX_MEDIA_BYTES)
                                    if (clipped) bounded = true
                                    val maxSide = if (shouldLocalAnalyze) MAX_IMAGE_LONG_SIDE else CLOUD_RECOVERY_LONG_SIDE
                                    val bmp = decodeByteArrayBounded(bytes, maxSide)
                                    if (bmp != null) {
                                        var retained = false
                                        try {
                                            if (shouldLocalAnalyze) {
                                                val local = cancellableResult { mlKit.process(bmp) }.getOrElse {
                                                    warnings += "PPTX_EMBEDDED_IMAGE_ANALYSIS_FAILED"
                                                    MLKitResult()
                                                }
                                                if (local.ocrText.isNotBlank()) {
                                                    embeddedOcr.append("\n[Embedded slide image ")
                                                        .append(mediaSeen)
                                                        .append("]\n")
                                                        .append(local.ocrText.trim())
                                                }
                                                val compact = local.toPromptContext()
                                                if (compact.isNotBlank() && local.isSubstantial) {
                                                    embeddedEvidence += "Embedded slide image $mediaSeen:\n$compact"
                                                }
                                                warnings += local.sensorWarnings
                                                if (retainedVisionImages.size < MAX_OFFICE_VISION_IMAGES) {
                                                    retainedVisionImages += bmp
                                                    retained = true
                                                }
                                            } else {
                                                compactCloudRecoveryJpeg(bmp)?.let { cloudRecovery += it }
                                                bounded = true
                                            }
                                        } finally {
                                            if (!retained && !bmp.isRecycled) bmp.recycle()
                                        }
                                    }
                                } else {
                                    bounded = true
                                }
                            }
                        }
                    } finally {
                        runCatching { zip.closeEntry() }
                    }
                }
            }
        } ?: return null

        val text = buildString {
            slides.sortedBy { it.first }.forEach { (slideNumber, xml) ->
                val extracted = extractOfficeXmlText(xml)
                if (extracted.isNotBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    append("[Slide ").append(slideNumber.coerceAtLeast(1)).append("]\n")
                    append(extracted)
                }
            }
            notes.sortedBy { it.first }.forEach { (slideNumber, xml) ->
                val extracted = extractOfficeXmlText(xml)
                if (extracted.isNotBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    append("[Speaker notes ")
                        .append(slideNumber.coerceAtLeast(1))
                        .append("]\n")
                    append(extracted)
                }
            }
            auxiliaryXml.sortedBy { it.first }.forEach { (entryName, xml) ->
                val extracted = extractOfficeXmlText(xml)
                if (extracted.isNotBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    append("[").append(presentationAuxiliaryLabel(entryName)).append("]\n")
                    append(extracted)
                }
            }
            if (embeddedOcr.isNotBlank()) append(embeddedOcr)
        }.trim()

        val clipped = text.take(MAX_EXTRACTED_CHARS)
        // A slide deck may be entirely graphical. Retain it for local VLM reasoning even
        // when deterministic text/ML Kit extraction produced no usable evidence.
        if (clipped.isBlank() && embeddedEvidence.isEmpty() && retainedVisionImages.isEmpty()) return null

        val truncated = bounded ||
            slideEntriesSeen > MAX_PPTX_SLIDES ||
            text.length > MAX_EXTRACTED_CHARS ||
            mediaSeen >= MAX_PPTX_MEDIA_IMAGES

        Log.i(
            ATTACH_TAG,
            "PPTX_LOCAL_EXTRACT slides=$slideEntriesSeen textChars=${clipped.length} media=$mediaSeen truncated=$truncated"
        )

        return Phase1UserAttachment(
            id = UUID.randomUUID().toString(),
            displayName = name,
            mimeType = mime.ifBlank { "application/vnd.openxmlformats-officedocument.presentationml.presentation" },
            kind = Phase1AttachmentKind.PPTX_DOCUMENT,
            extractedText = clipped,
            localVisualContext = embeddedEvidence.joinToString("\n\n").take(2_400),
            localEvidenceSubstantial = clipped.isNotBlank() || embeddedEvidence.isNotEmpty(),
            localAnalysisWarnings = warnings.distinct(),
            visionBitmaps = retainedVisionImages,
            cloudRecoveryJpegs = cloudRecovery,
            pageCount = slideEntriesSeen,
            truncated = truncated
        )
    }

    private suspend fun prepareLegacyPpt(uri: Uri, name: String, mime: String): Phase1UserAttachment? {
        val bytes = readBounded(uri, MAX_LEGACY_PPT_BYTES) ?: return null
        val poiText = extractLegacyPresentationTextWithPoi(bytes)
        val fallbackText = if (poiText.isNullOrBlank()) extractLegacyOfficeTextBestEffort(bytes) else ""
        val embeddedEvidence = mutableListOf<String>()
        val embeddedOcr = StringBuilder()
        val retainedVisionImages = mutableListOf<Bitmap>()
        val warnings = mutableListOf<String>()

        val pictureBytes = extractLegacyPresentationPicturesWithPoi(bytes).take(MAX_PPTX_MEDIA_IMAGES)
        pictureBytes.forEachIndexed { index, imageBytes ->
            val bmp = decodeByteArrayBounded(imageBytes, MAX_IMAGE_LONG_SIDE) ?: return@forEachIndexed
            var retained = false
            try {
                val local = cancellableResult { mlKit.process(bmp) }.getOrElse {
                    warnings += "LEGACY_PPT_IMAGE_ANALYSIS_FAILED"
                    MLKitResult()
                }
                if (local.ocrText.isNotBlank()) {
                    embeddedOcr.append("\n[Embedded slide image ")
                        .append(index + 1)
                        .append("]\n")
                        .append(local.ocrText.trim())
                }
                val compact = local.toPromptContext()
                if (compact.isNotBlank() && local.isSubstantial) {
                    embeddedEvidence += "Embedded slide image ${index + 1}:\n$compact"
                }
                warnings += local.sensorWarnings
                if (retainedVisionImages.size < MAX_OFFICE_VISION_IMAGES) {
                    retainedVisionImages += bmp
                    retained = true
                }
            } finally {
                if (!retained && !bmp.isRecycled) bmp.recycle()
            }
        }

        val text = buildString {
            append((poiText ?: fallbackText).trim())
            if (embeddedOcr.isNotBlank()) append(embeddedOcr)
        }.trim()
        val clipped = text.take(MAX_EXTRACTED_CHARS)
        if (clipped.isBlank() && embeddedEvidence.isEmpty() && retainedVisionImages.isEmpty()) return null

        if (poiText.isNullOrBlank()) warnings += "LEGACY_PPT_BEST_EFFORT_EXTRACTION"
        if (pictureBytes.size >= MAX_PPTX_MEDIA_IMAGES) warnings += "LEGACY_PPT_IMAGE_EXTRACTION_BOUNDED"

        return Phase1UserAttachment(
            id = UUID.randomUUID().toString(),
            displayName = name,
            mimeType = mime.ifBlank { "application/vnd.ms-powerpoint" },
            kind = Phase1AttachmentKind.LEGACY_PPT_DOCUMENT,
            extractedText = clipped,
            localVisualContext = embeddedEvidence.joinToString("\n\n").take(2_400),
            localEvidenceSubstantial = clipped.isNotBlank() || embeddedEvidence.isNotEmpty(),
            localAnalysisWarnings = warnings.distinct(),
            visionBitmaps = retainedVisionImages,
            truncated = bytes.size >= MAX_LEGACY_PPT_BYTES || text.length > MAX_EXTRACTED_CHARS
        )
    }

    private suspend fun preparePdf(uri: Uri, name: String, mime: String): Phase1UserAttachment? {
        val pfd = appContext.contentResolver.openFileDescriptor(uri, "r") ?: return null
        pfd.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                val totalPages = renderer.pageCount
                val nativeTextAvailable = Build.VERSION.SDK_INT >= 35
                val pagesToInspect = if (nativeTextAvailable) {
                    minOf(totalPages, MAX_PDF_NATIVE_TEXT_PAGES)
                } else {
                    minOf(totalPages, MAX_PDF_OCR_PAGES)
                }
                val text = StringBuilder()
                val localVisual = mutableListOf<String>()
                val warnings = mutableListOf<String>()
                val keptImages = mutableListOf<Bitmap>()
                val cloudRecovery = mutableListOf<ByteArray>()
                var localVisualEvidenceFound = false
                var skippedImageOnlyPages = 0

                val cloudRecoveryIndices: Set<Int> = if (totalPages > MAX_PDF_LOCAL_ANALYSIS_PAGES) {
                    val start = MAX_PDF_LOCAL_ANALYSIS_PAGES.coerceAtMost((totalPages - 1).coerceAtLeast(0))
                    val last = (totalPages - 1).coerceAtLeast(start)
                    (0 until MAX_CLOUD_RECOVERY_IMAGES).map { slot ->
                        if (MAX_CLOUD_RECOVERY_IMAGES <= 1) start
                        else start + (((last - start).toDouble() * slot) / (MAX_CLOUD_RECOVERY_IMAGES - 1).toDouble()).toInt()
                    }.toSet()
                } else emptySet()
                val pageIndices = ((0 until pagesToInspect).toSet() + cloudRecoveryIndices).toList().sorted()

                for (index in pageIndices) {
                    renderer.openPage(index).use pageUse@ { page ->
                        val nativeText = if (nativeTextAvailable && index < pagesToInspect) {
                            runCatching {
                                page.textContents.joinToString("\n") { content -> content.text }.trim()
                            }.getOrElse {
                                Log.w(ATTACH_TAG, "PDF_NATIVE_TEXT_FAILED page=${index + 1} ${it.message}")
                                ""
                            }
                        } else ""

                        if (nativeText.isNotBlank()) {
                            text.append("[Page ").append(index + 1).append("]\n")
                                .append(nativeText).append("\n\n")
                        }

                        // Regression restore: before native PdfRenderer text extraction was
                        // introduced, the first PDF pages were always OCR-rendered. Some PDFs
                        // expose a partial/garbled native text layer; treating any non-blank
                        // native text as authoritative made those files substantially worse.
                        // Keep native text for speed/coverage, but also OCR the bounded first
                        // pages and append OCR only when it contributes material new evidence.
                        val shouldOcr = index < MAX_PDF_OCR_PAGES
                        val shouldAnalyzeVisual = index < MAX_PDF_LOCAL_ANALYSIS_PAGES
                        val shouldKeepVision = index < MAX_PDF_VISION_PAGES
                        val shouldKeepCloudRecovery = index in cloudRecoveryIndices
                        val shouldRender = shouldOcr || shouldAnalyzeVisual || shouldKeepVision || shouldKeepCloudRecovery

                        if (!shouldRender) {
                            if (nativeText.isBlank()) skippedImageOnlyPages++
                            return@pageUse
                        }

                        val longSide = maxOf(page.width, page.height).coerceAtLeast(1)
                        val renderLongSide = if (shouldOcr || shouldAnalyzeVisual) PDF_RENDER_LONG_SIDE else CLOUD_RECOVERY_LONG_SIDE
                        val scale = minOf(1.6f, renderLongSide.toFloat() / longSide.toFloat())
                            .coerceAtLeast(0.6f)
                        val w = (page.width * scale).toInt().coerceAtLeast(1)
                        val h = (page.height * scale).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        Canvas(bmp).drawColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                        if (shouldAnalyzeVisual) {
                            val local = cancellableResult { mlKit.process(bmp) }.getOrElse {
                                warnings += "PDF_PAGE_LOCAL_ANALYSIS_FAILED"
                                MLKitResult()
                            }
                            if (local.isSubstantial) localVisualEvidenceFound = true
                            val pageOcr = local.ocrText.trim()
                            if (pageOcr.isNotBlank() && shouldAppendPdfOcrEvidence(nativeText, pageOcr)) {
                                text.append(
                                    if (nativeText.isBlank()) "[Page ${index + 1}]\n"
                                    else "[Page ${index + 1} OCR supplement]\n"
                                ).append(pageOcr).append("\n\n")
                            }
                            val compact = local.toPromptContext()
                            if (compact.isNotBlank() && local.isSubstantial) {
                                localVisual += "PDF page ${index + 1}:\n$compact"
                            }
                            warnings += local.sensorWarnings
                        } else if (shouldOcr) {
                            val ocr = cancellableResult { mlKit.recognizeText(bmp).trim() }.getOrDefault("")
                            if (ocr.isNotBlank() && shouldAppendPdfOcrEvidence(nativeText, ocr)) {
                                text.append(
                                    if (nativeText.isBlank()) "[Page ${index + 1}]\n"
                                    else "[Page ${index + 1} OCR supplement]\n"
                                ).append(ocr).append("\n\n")
                            } else if (nativeText.isBlank() && ocr.isBlank()) {
                                skippedImageOnlyPages++
                            }
                        }

                        if (shouldKeepVision) {
                            // Retain only a compact VLM recovery copy. OCR/ML Kit already
                            // consumed the higher-resolution render above, so keeping the
                            // full page bitmap only increases heap pressure.
                            keptImages += scaleBitmapBounded(bmp, MAX_RETAINED_VISION_LONG_SIDE)
                        }
                        if (shouldKeepCloudRecovery && cloudRecovery.size < MAX_CLOUD_RECOVERY_IMAGES) {
                            compactCloudRecoveryJpeg(bmp)?.let { cloudRecovery += it }
                        }
                        if (!shouldKeepVision) bmp.recycle()
                    }
                }

                val raw = text.toString().trim()
                val clipped = raw.take(MAX_EXTRACTED_CHARS)
                val compactVisual = localVisual.joinToString("\n\n").take(2_400)
                if (clipped.isBlank() && compactVisual.isBlank() && keptImages.isEmpty()) return null

                if (skippedImageOnlyPages > 0) warnings += "PDF_IMAGE_ONLY_PAGES_BEYOND_OCR_LIMIT=$skippedImageOnlyPages"
                val truncated = totalPages > pagesToInspect || raw.length > MAX_EXTRACTED_CHARS || skippedImageOnlyPages > 0

                Log.i(
                    ATTACH_TAG,
                    "PDF_LOCAL_EXTRACT pages=$totalPages inspected=$pagesToInspect nativeText=$nativeTextAvailable " +
                        "textChars=${clipped.length} skippedImageOnly=$skippedImageOnlyPages truncated=$truncated"
                )

                return Phase1UserAttachment(
                    id = UUID.randomUUID().toString(),
                    displayName = name,
                    mimeType = mime.ifBlank { "application/pdf" },
                    kind = Phase1AttachmentKind.PDF_DOCUMENT,
                    extractedText = clipped,
                    localVisualContext = compactVisual,
                    localEvidenceSubstantial = clipped.isNotBlank() || localVisualEvidenceFound,
                    localAnalysisWarnings = warnings.distinct(),
                    visionBitmaps = keptImages,
                    cloudRecoveryJpegs = cloudRecovery,
                    pageCount = totalPages,
                    truncated = truncated
                )
            }
        }
    }


    private fun compactCloudRecoveryJpeg(bitmap: Bitmap): ByteArray? {
        val scaled = scaleBitmapBounded(bitmap, CLOUD_RECOVERY_LONG_SIDE)
        return try {
            ByteArrayOutputStream().use { out ->
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, CLOUD_RECOVERY_JPEG_QUALITY, out)) null
                else out.toByteArray()
            }
        } finally {
            if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        val resolver = appContext.contentResolver
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
            }.orEmpty()
        } catch (_: Throwable) {
            uri.lastPathSegment.orEmpty()
        }
    }

    /**
     * Adds OCR beside native PDF text only when OCR contributes meaningful new
     * information. This keeps hybrid/poor-text-layer PDFs robust without doubling
     * clean native text in the Local prompt.
     */
    private fun shouldAppendPdfOcrEvidence(nativeText: String, ocrText: String): Boolean {
        val ocr = normalizeExtractedText(ocrText)
        if (ocr.isBlank()) return false
        val native = normalizeExtractedText(nativeText)
        if (native.isBlank()) return true

        fun tokens(value: String): Set<String> = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}._/-]{1,}")
            .findAll(value.lowercase())
            .map { it.value }
            .take(2_000)
            .toSet()

        val ocrTokens = tokens(ocr)
        if (ocrTokens.isEmpty()) return false
        val nativeTokens = tokens(native)
        if (ocrTokens.all { it in nativeTokens }) return false

        val novel = ocrTokens.count { it !in nativeTokens }
        return novel >= 3 && novel * 4 >= ocrTokens.size
    }

    private fun readBounded(uri: Uri, maxBytes: Int): ByteArray? =
        appContext.contentResolver.openInputStream(uri)?.use { readBounded(it, maxBytes).first }

    private fun readBounded(input: InputStream, maxBytes: Int): Pair<ByteArray, Boolean> {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var remaining = maxBytes
        var clipped = false
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (n <= 0) break
            out.write(buffer, 0, n)
            remaining -= n
        }
        if (remaining == 0) clipped = input.read() >= 0
        return out.toByteArray() to clipped
    }

    private fun readZipEntryBounded(zip: ZipInputStream, maxBytes: Int): Pair<ByteArray, Boolean> {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var remaining = maxBytes
        var clipped = false
        while (remaining > 0) {
            val n = zip.read(buffer, 0, minOf(buffer.size, remaining))
            if (n <= 0) break
            out.write(buffer, 0, n)
            remaining -= n
        }
        if (remaining == 0) clipped = zip.read() >= 0
        return out.toByteArray() to clipped
    }

    private fun decodeTextBytes(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        return when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                bytes.copyOfRange(3, bytes.size).toString(Charsets.UTF_8)
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16BE)
            else -> bytes.toString(Charsets.UTF_8)
        }
    }

    private fun decodeBitmapBounded(uri: Uri, maxLongSide: Int): Bitmap? {
        val resolver = appContext.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        var longSide = max(bounds.outWidth, bounds.outHeight)
        while (longSide / sample > maxLongSide * 2) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        // Pramaan fix: camera photos carry an EXIF rotation that BitmapFactory ignores; OCR needs them upright.
        return scaleBitmapBounded(ArogyaPhotoGuard.upright(appContext, uri, decoded), maxLongSide)
    }

    private fun decodeByteArrayBounded(bytes: ByteArray, maxLongSide: Int): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val longSide = max(bounds.outWidth, bounds.outHeight)
        while (longSide / sample > maxLongSide * 2) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return scaleBitmapBounded(decoded, maxLongSide)
    }

    private fun scaleBitmapBounded(source: Bitmap, maxLongSide: Int): Bitmap {
        val longSide = max(source.width, source.height).coerceAtLeast(1)
        if (longSide <= maxLongSide && source.config == Bitmap.Config.ARGB_8888) {
            return if (source.isMutable) source.copy(Bitmap.Config.ARGB_8888, false).also {
                if (it !== source && !source.isRecycled) source.recycle()
            } else source
        }
        val scale = maxLongSide.toFloat() / longSide.toFloat()
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, w, h, true)
        val safe = if (scaled.config == Bitmap.Config.ARGB_8888 && !scaled.isMutable) scaled
        else scaled.copy(Bitmap.Config.ARGB_8888, false)
        if (safe !== scaled && !scaled.isRecycled) scaled.recycle()
        if (safe !== source && !source.isRecycled) source.recycle()
        return safe
    }

    private fun extractOfficeXmlText(xml: String): String {
        if (xml.isBlank()) return ""
        return try {
            val parser = XmlPullParserFactory.newInstance().apply { setNamespaceAware(true) }.newPullParser()
            parser.setInput(StringReader(xml))
            val out = StringBuilder()
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.TEXT -> out.append(parser.text.orEmpty())
                    XmlPullParser.START_TAG -> if (parser.name.equals("tab", true)) out.append('\t')
                    XmlPullParser.END_TAG -> when (parser.name.lowercase()) {
                        "p", "tr" -> out.append('\n')
                        "br" -> out.append('\n')
                    }
                }
                event = parser.next()
            }
            normalizeExtractedText(out.toString())
        } catch (t: Throwable) {
            Log.w(ATTACH_TAG, "DOCX_XML_PARSE_FALLBACK ${t.message}")
            normalizeExtractedText(
                xml.replace(Regex("</(?:w:)?p>"), "\n")
                    .replace(Regex("</(?:w:)?tr>"), "\n")
                    .replace(Regex("<(?:w:)?tab[^>]*/>"), "\t")
                    .replace(Regex("<[^>]+>"), " ")
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&apos;", "'")
            )
        }
    }

    private fun isPresentationSlideXml(entryName: String): Boolean =
        Regex("ppt/slides/slide\\d+\\.xml", RegexOption.IGNORE_CASE).matches(entryName)

    private fun isPresentationNotesXml(entryName: String): Boolean =
        Regex("ppt/notesSlides/notesSlide\\d+\\.xml", RegexOption.IGNORE_CASE).matches(entryName)

    private fun isPresentationAuxiliaryXml(entryName: String): Boolean =
        Regex("ppt/charts/chart\\d+\\.xml", RegexOption.IGNORE_CASE).matches(entryName) ||
            Regex("ppt/diagrams/data\\d+\\.xml", RegexOption.IGNORE_CASE).matches(entryName) ||
            Regex("ppt/comments/comment\\d+\\.xml", RegexOption.IGNORE_CASE).matches(entryName)

    private fun presentationPartNumber(entryName: String): Int =
        Regex("(\\d+)\\.xml$", RegexOption.IGNORE_CASE)
            .find(entryName)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 1

    private fun presentationAuxiliaryLabel(entryName: String): String = when {
        entryName.contains("/charts/", true) -> "Chart data ${presentationPartNumber(entryName)}"
        entryName.contains("/diagrams/", true) -> "Diagram data ${presentationPartNumber(entryName)}"
        entryName.contains("/comments/", true) -> "Presentation comments ${presentationPartNumber(entryName)}"
        else -> "Presentation content"
    }

    private fun normalizeExtractedText(text: String): String =
        text.replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\n[ \\t]+"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    /**
     * If the host app already bundles Apache POI, use its mature legacy-DOC parser
     * without adding a compile-time dependency. This keeps the source drop-in safe.
     */
    private fun extractLegacyWordWithPoi(bytes: ByteArray): String? = try {
        val docClass = Class.forName("org.apache.poi.hwpf.HWPFDocument")
        val ctor = docClass.getConstructor(InputStream::class.java)
        val doc = ctor.newInstance(ByteArrayInputStream(bytes))
        try {
            val range = docClass.getMethod("getRange").invoke(doc)
            val text = range.javaClass.getMethod("text").invoke(range) as? String
            text?.let(::normalizeExtractedText)?.takeIf { it.isNotBlank() }
        } finally {
            runCatching { (doc as? AutoCloseable)?.close() }
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * If Apache POI is already bundled by the host, use HSLF's mature legacy-PPT
     * parser without adding a compile-time dependency. Reflection keeps this source
     * safe for the existing Gradle configuration.
     */
    private fun extractLegacyPresentationTextWithPoi(bytes: ByteArray): String? = try {
        val showClass = Class.forName("org.apache.poi.hslf.usermodel.HSLFSlideShow")
        val show = showClass.getConstructor(InputStream::class.java)
            .newInstance(ByteArrayInputStream(bytes))
        try {
            // Prefer POI's text extractor when available.
            val extracted = runCatching {
                val extractorClass = Class.forName("org.apache.poi.hslf.extractor.PowerPointExtractor")
                val ctor = extractorClass.constructors.firstOrNull { ctor ->
                    ctor.parameterTypes.size == 1 && ctor.parameterTypes[0].isAssignableFrom(showClass)
                } ?: extractorClass.constructors.firstOrNull { ctor ->
                    ctor.parameterTypes.size == 1 && ctor.parameterTypes[0] == showClass
                }
                val extractor = ctor?.newInstance(show)
                try {
                    extractor?.javaClass?.getMethod("getText")?.invoke(extractor) as? String
                } finally {
                    runCatching { (extractor as? AutoCloseable)?.close() }
                }
            }.getOrNull()

            if (!extracted.isNullOrBlank()) {
                normalizeExtractedText(extracted).takeIf { it.isNotBlank() }
            } else {
                val slides = runCatching { showClass.getMethod("getSlides").invoke(show) as? Iterable<*> }
                    .getOrNull()
                    ?: emptyList<Any?>()
                buildString {
                    slides.forEachIndexed { index, slide ->
                        val slideText = extractPoiShapeText(slide)
                        if (slideText.isNotBlank()) {
                            if (isNotEmpty()) append("\n\n")
                            append("[Slide ").append(index + 1).append("]\n")
                            append(slideText)
                        }
                    }
                }.let(::normalizeExtractedText).takeIf { it.isNotBlank() }
            }
        } finally {
            runCatching { (show as? AutoCloseable)?.close() }
        }
    } catch (_: Throwable) {
        null
    }

    private fun extractLegacyPresentationPicturesWithPoi(bytes: ByteArray): List<ByteArray> {
        return try {
            val showClass = Class.forName("org.apache.poi.hslf.usermodel.HSLFSlideShow")
            val show = showClass.getConstructor(InputStream::class.java)
                .newInstance(ByteArrayInputStream(bytes))
            try {
                val pictures = showClass.getMethod("getPictureData").invoke(show) as? Iterable<*>
                if (pictures == null) {
                    emptyList()
                } else {
                    pictures.mapNotNull { picture ->
                        runCatching {
                            picture?.javaClass?.getMethod("getData")?.invoke(picture) as? ByteArray
                        }.getOrNull()
                    }.take(MAX_PPTX_MEDIA_IMAGES)
                }
            } finally {
                runCatching { (show as? AutoCloseable)?.close() }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun extractPoiShapeText(container: Any?): String {
        if (container == null) return ""
        val fragments = mutableListOf<String>()
        val directText = runCatching {
            container.javaClass.methods.firstOrNull { it.name == "getText" && it.parameterCount == 0 }
                ?.invoke(container) as? String
        }.getOrNull()
        if (!directText.isNullOrBlank()) fragments += directText

        val children = runCatching {
            container.javaClass.methods.firstOrNull { it.name == "getShapes" && it.parameterCount == 0 }
                ?.invoke(container) as? Iterable<*>
        }.getOrNull()
        children?.forEach { child ->
            val childText = extractPoiShapeText(child)
            if (childText.isNotBlank()) fragments += childText
        }
        return normalizeExtractedText(fragments.joinToString("\n"))
    }

    /**
     * Conservative fallback for Office 97-2003 DOC/PPT when POI is absent. It recovers
     * readable ANSI and UTF-16LE runs, deduplicates them, and rejects binary-looking
     * fragments. It is intentionally labelled best-effort in attachment metadata.
     */
    private fun extractLegacyOfficeTextBestEffort(bytes: ByteArray): String {
        val candidates = mutableListOf<String>()
        val encodings = listOf(
            Charsets.UTF_16LE,
            Charset.forName("windows-1252")
        )
        for (charset in encodings) {
            val decoded = runCatching { bytes.toString(charset) }.getOrDefault("")
            Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}\\p{P}\\p{Zs}\\t]{7,}")
                .findAll(decoded)
                .map { normalizeExtractedText(it.value) }
                .filter { it.length >= 8 && readableRatio(it) >= 0.88 }
                .take(1_500)
                .forEach(candidates::add)
        }
        return candidates
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")
            .take(MAX_EXTRACTED_CHARS)
    }

    private fun readableRatio(text: String): Double {
        if (text.isEmpty()) return 0.0
        val good = text.count { ch -> ch.isLetterOrDigit() || ch.isWhitespace() || ch in ".,;:!?()[]{}'\"/-_@#%&+=" }
        return good.toDouble() / text.length.toDouble()
    }

    private fun isWordTextXml(entryName: String): Boolean {
        if (!entryName.startsWith("word/")) return false
        return entryName == "word/document.xml" ||
            Regex("word/header\\d*\\.xml", RegexOption.IGNORE_CASE).matches(entryName) ||
            Regex("word/footer\\d*\\.xml", RegexOption.IGNORE_CASE).matches(entryName) ||
            entryName.equals("word/footnotes.xml", true) ||
            entryName.equals("word/endnotes.xml", true)
    }

    private fun isModernWord(mime: String, name: String): Boolean =
        mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
            mime == "application/vnd.ms-word.document.macroenabled.12" ||
            name.endsWith(".docx", true) || name.endsWith(".docm", true)

    private fun isLegacyWord(mime: String, name: String): Boolean =
        mime == "application/msword" ||
            mime == "application/vnd.ms-word" ||
            mime == "application/vnd.msword" ||
            name.endsWith(".doc", true)

    private fun isModernPresentation(mime: String, name: String): Boolean =
        mime == "application/vnd.openxmlformats-officedocument.presentationml.presentation" ||
            mime == "application/vnd.ms-powerpoint.presentation.macroenabled.12" ||
            mime == "application/vnd.openxmlformats-officedocument.presentationml.slideshow" ||
            name.endsWith(".pptx", true) || name.endsWith(".pptm", true) || name.endsWith(".ppsx", true)

    private fun isLegacyPresentation(mime: String, name: String): Boolean =
        mime == "application/vnd.ms-powerpoint" ||
            mime == "application/mspowerpoint" ||
            mime == "application/powerpoint" ||
            name.endsWith(".ppt", true)

    private fun hasImageExtension(name: String): Boolean =
        listOf(".jpg", ".jpeg", ".png", ".webp", ".bmp", ".heic", ".heif")
            .any { name.endsWith(it, true) }

    private fun isTextLikeName(name: String): Boolean =
        listOf(".txt", ".md", ".csv", ".json", ".xml", ".log", ".kt", ".java", ".kts", ".gradle", ".yaml", ".yml")
            .any { name.endsWith(it, true) }
}

/**
 * Phase-2 bounded whole-source plan. The runtime may compress these sections through
 * AndroLLM ephemeral generations when the user explicitly asks for a broad document
 * summary/explanation while offline.
 */
enum class AttachmentEscalationClass { NONE, CAPACITY, QUALITY, CAPABILITY }

data class AttachmentEscalationDecision(
    val shouldEscalate: Boolean,
    val category: AttachmentEscalationClass = AttachmentEscalationClass.NONE,
    val reason: String = "",
    val coverageComplete: Boolean = true
)

data class AttachmentSummaryPlan(
    val sourceId: String,
    val sourceLabel: String,
    val sections: List<String>,
    val coverageComplete: Boolean,
    val sourceChars: Int
)

/**
 * Conversation-scoped semantic attachment workspace.
 *
 * Contract:
 * - extraction/chunking happens once per accepted source;
 * - accepted source files + extracted text are persisted under the owning conversation;
 * - E5 embeddings remain lazy and are recreated/cached in memory as needed;
 * - every accepted source remains available for later turns and saved-chat reopen;
 * - service/session teardown unloads RAM only; conversation deletion/pruning removes disk state;
 * - the AndroLLM resident public chat/KV is never used as attachment storage.
 *
 * The same object also owns the attachment-specific Local-answer grounding gate.
 * It deliberately ignores selected-KB/SOP evidence, which remains owned by
 * SelectedKnowledgeContextProvider.
 */
class AttachmentSessionContextProvider(
    context: Context,
    private val semanticEngine: SemanticSearchEngine
) : BaselineContextProvider, BaselineResponseValidator {
    private val appContext = context.applicationContext
    private val persistentRoot = File(appContext.filesDir, "fixsight_conversation_attachments").apply { mkdirs() }
    @Volatile private var boundConversationId: String = ""
    companion object {
        private const val MAX_TRANSIENT_WORKSPACE_ATTACHMENTS = 8
        private const val MAX_RECALL_SOURCES_PER_TURN = 5
        private const val MAX_RECALL_TEXT_CHARS = 3_600
        private const val MAX_RECALL_VISUAL_CHARS = 1_000
        private const val MAX_CLOUD_TEXT_CHARS = 14_000
        private const val MAX_SELECTION_CACHE = 16
        private const val MAX_QUERY_EMBEDDING_CACHE = 8
        private const val MAX_VALIDATION_EMBEDDING_CACHE = 96
        private const val MIN_SEMANTIC_FOLLOWUP = 0.74f
        private const val MIN_SEMANTIC_CLAIM = 0.84f
        private const val MIN_LEXICAL_CLAIM = 0.34f
        private const val MIN_LEXICAL_WITH_SEMANTIC = 0.14f
        private const val SUMMARY_SECTION_CHARS = 3_200
        private const val MAX_SUMMARY_SECTIONS = 12
        // Mirrors Phase1AttachmentManager V1 local-PDF ownership contract.
        private const val MAX_PDF_NATIVE_TEXT_PAGES = 100

        // Dynamic attachment intent descriptors. These replace phrase/topic lists.
        private const val WHOLE_SOURCE_ANALYSIS_DESCRIPTOR =
            "the user wants broad coverage of the whole attached source, such as an overall summary, review, analysis, or explanation spanning the document rather than one narrow fact"
        private const val FOCUSED_SOURCE_QUESTION_DESCRIPTOR =
            "the user asks a focused question about one specific fact, section, detail, or issue from an attached source rather than requesting broad whole-document coverage"
        private const val MULTI_SOURCE_DESCRIPTOR =
            "the user wants information from two or more attached sources together, including comparison, reconciliation, synthesis, differences, or combined evidence"
        private const val SINGLE_SOURCE_DESCRIPTOR =
            "the user asks about one attached source or one answer that does not require combining multiple attached sources"
        private const val VISUAL_SOURCE_DESCRIPTOR =
            "the requested answer depends on inspecting visual content in the attached source, such as a figure, diagram, page image, layout, chart, screenshot, drawing, or other visual detail"
        private const val TEXT_SOURCE_DESCRIPTOR =
            "the requested answer can be resolved from extracted text and does not require inspecting visual content from the attached source"
        private const val ATTACHMENT_INTENT_MIN_SCORE = 0.68f
        private const val ATTACHMENT_INTENT_MARGIN = 0.015f
    }

    private data class WorkspaceEntry(
        val id: String,
        val label: String,
        val kind: String,
        val extractedText: String,
        val localVisualContext: String,
        var sourceCachePath: String,
        val pageCount: Int,
        val chunks: List<String>,
        val embeddings: MutableList<FloatArray?>,
        var transientVisionBitmap: Bitmap? = null,
        var accepted: Boolean,
        var lastUsedAtMs: Long
    )

    private val lock = Any()
    private val workspace = linkedMapOf<String, WorkspaceEntry>()
    private val selectionCache = linkedMapOf<String, String>()
    private val queryEmbeddingCache = linkedMapOf<String, FloatArray>()
    private val validationEmbeddingCache = linkedMapOf<String, FloatArray>()
    @Volatile private var wholeSourceEmbedding: FloatArray? = null
    @Volatile private var focusedSourceEmbedding: FloatArray? = null
    @Volatile private var multiSourceEmbedding: FloatArray? = null
    @Volatile private var singleSourceEmbedding: FloatArray? = null
    @Volatile private var visualSourceEmbedding: FloatArray? = null
    @Volatile private var textSourceEmbedding: FloatArray? = null

    private fun conversationDir(conversationId: String): File =
        File(persistentRoot, conversationId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }).apply { mkdirs() }

    private fun clearMemoryLocked() {
        workspace.values.forEach { entry ->
            entry.transientVisionBitmap?.let { bmp ->
                runCatching { if (!bmp.isRecycled) bmp.recycle() }
            }
        }
        workspace.clear()
        selectionCache.clear()
        queryEmbeddingCache.clear()
        validationEmbeddingCache.clear()
    }

    /**
     * Conversation ownership follows AndroLLM's attachment-cache contract:
     * switching/opening a chat unloads only RAM state, then restores that chat's
     * accepted attachment index from app-private storage. Persistent sources are
     * never deleted merely because the foreground service/session stops.
     */
    suspend fun bindConversation(conversationId: String) = withContext(Dispatchers.IO) {
        val clean = conversationId.trim()
        synchronized(lock) {
            clearMemoryLocked()
            boundConversationId = clean
        }
        if (clean.isBlank()) return@withContext

        val dir = conversationDir(clean)
        val restored = mutableListOf<WorkspaceEntry>()
        dir.listFiles()?.filter { it.isDirectory }?.forEach { sourceDir ->
            val metaFile = File(sourceDir, "meta.json")
            if (!metaFile.exists()) return@forEach
            runCatching {
                val meta = JSONObject(metaFile.readText())
                val id = meta.optString("id", sourceDir.name).ifBlank { sourceDir.name }
                val label = meta.optString("label", "attachment")
                val kind = meta.optString("kind", Phase1AttachmentKind.TEXT_DOCUMENT.name)
                val text = File(sourceDir, "extracted.txt").takeIf { it.exists() }?.readText().orEmpty()
                val visual = File(sourceDir, "visual.txt").takeIf { it.exists() }?.readText().orEmpty()
                val sourceName = meta.optString("source_file", "")
                val sourcePath = if (sourceName.isNotBlank()) {
                    File(sourceDir, sourceName).takeIf { it.exists() }?.absolutePath.orEmpty()
                } else ""
                val clippedText = text.take(900_000)
                val restoredChunks = attachmentChunks(clippedText)
                WorkspaceEntry(
                    id = id,
                    label = label.take(120),
                    kind = kind,
                    extractedText = clippedText,
                    localVisualContext = visual.take(2_400),
                    sourceCachePath = sourcePath,
                    pageCount = meta.optInt("page_count", 0),
                    chunks = restoredChunks,
                    embeddings = MutableList(restoredChunks.size) { null },
                    accepted = true,
                    lastUsedAtMs = meta.optLong("last_used_at_ms", meta.optLong("created_at_ms", 0L))
                )
            }.onSuccess { restored += it }
                .onFailure { Log.w(ATTACH_TAG, "ATTACHMENT_RESTORE_SKIPPED dir=${sourceDir.name} ${it.message}") }
        }
        val stillBound = synchronized(lock) {
            if (boundConversationId != clean) {
                false
            } else {
                restored.sortedBy { it.lastUsedAtMs }.forEach { entry -> workspace[entry.id] = entry }
                true
            }
        }
        if (!stillBound) return@withContext
        Log.i(ATTACH_TAG, "ATTACHMENT_CONVERSATION_BOUND id=${clean.take(12)} restored=${restored.size}")
    }

    /** Delete only attachment stores whose owning conversations no longer exist. */
    suspend fun pruneConversationScopes(validConversationIds: Set<String>) = withContext(Dispatchers.IO) {
        val valid = validConversationIds.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        persistentRoot.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            if (dir.name !in valid) {
                runCatching { dir.deleteRecursively() }
                    .onSuccess { Log.i(ATTACH_TAG, "ATTACHMENT_CONVERSATION_PRUNED id=${dir.name.take(12)}") }
            }
        }
    }

    suspend fun deleteConversationAttachments(conversationId: String) = withContext(Dispatchers.IO) {
        val clean = conversationId.trim()
        if (clean.isBlank()) return@withContext
        if (boundConversationId == clean) synchronized(lock) {
            clearMemoryLocked()
            boundConversationId = ""
        }
        runCatching { File(persistentRoot, clean).deleteRecursively() }
    }

    private suspend fun persistAcceptedEntry(entry: WorkspaceEntry) = withContext(Dispatchers.IO) {
        val conversationId = boundConversationId
        if (conversationId.isBlank()) {
            Log.w(ATTACH_TAG, "ATTACHMENT_PERSIST_SKIPPED reason=no_bound_conversation id=${entry.id}")
            return@withContext
        }
        val sourceDir = File(conversationDir(conversationId), entry.id).apply { mkdirs() }
        val oldPath = entry.sourceCachePath
        var persistentSourceName = ""
        if (oldPath.isNotBlank()) {
            val old = File(oldPath)
            if (old.exists()) {
                val rawExt = File(entry.label).extension.lowercase().filter { it.isLetterOrDigit() }.take(8)
                val ext = if (rawExt.isBlank()) "bin" else rawExt
                val target = File(sourceDir, "source.$ext")
                if (old.absolutePath != target.absolutePath) old.copyTo(target, overwrite = true)
                persistentSourceName = target.name
                synchronized(lock) { workspace[entry.id]?.sourceCachePath = target.absolutePath }
                if (old.absolutePath != target.absolutePath && old.absolutePath.startsWith(appContext.cacheDir.absolutePath)) {
                    runCatching { old.delete() }
                }
            }
        }
        File(sourceDir, "extracted.txt").writeText(entry.extractedText)
        File(sourceDir, "visual.txt").writeText(entry.localVisualContext)
        File(sourceDir, "meta.json").writeText(
            JSONObject().apply {
                put("id", entry.id)
                put("label", entry.label)
                put("kind", entry.kind)
                put("page_count", entry.pageCount)
                put("source_file", persistentSourceName)
                put("created_at_ms", System.currentTimeMillis())
                put("last_used_at_ms", entry.lastUsedAtMs)
            }.toString()
        )
        Log.i(
            ATTACH_TAG,
            "ATTACHMENT_PERSISTED conversation=${conversationId.take(12)} source=${entry.id} " +
                "chars=${entry.extractedText.length} sourceFile=${persistentSourceName.isNotBlank()}"
        )
    }

    private fun ensureWorkspaceEntry(attachment: Phase1UserAttachment): WorkspaceEntry {
        val source = attachment.extractedText.take(900_000)
        synchronized(lock) {
            val existing = workspace[attachment.id]
            if (existing != null &&
                existing.extractedText == source &&
                existing.kind == attachment.kind.name
            ) {
                existing.lastUsedAtMs = System.currentTimeMillis()
                return existing
            }

            val chunks = attachmentChunks(source)
            val entry = WorkspaceEntry(
                id = attachment.id,
                label = attachment.displayName.take(120),
                kind = attachment.kind.name,
                extractedText = source,
                localVisualContext = attachment.localVisualContext.take(2_400),
                sourceCachePath = attachment.sourceCachePath,
                pageCount = attachment.pageCount,
                chunks = chunks,
                embeddings = MutableList(chunks.size) { null },
                accepted = false,
                lastUsedAtMs = System.currentTimeMillis()
            )
            workspace.remove(attachment.id)
            workspace[attachment.id] = entry
            selectionCache.keys.removeAll { it.startsWith("${attachment.id}|") }
            trimWorkspaceLocked()
            Log.i(
                ATTACH_TAG,
                "ATTACHMENT_WORKSPACE_INDEXED id=${attachment.id} chunks=${chunks.size} chars=${source.length}"
            )
            return entry
        }
    }

    private fun trimWorkspaceLocked() {
        // Accepted attachments belong to the conversation and are never evicted merely
        // because more documents are attached later. Bound only transient/unaccepted
        // staging entries in RAM.
        while (workspace.values.count { !it.accepted } > MAX_TRANSIENT_WORKSPACE_ATTACHMENTS) {
            val removable = workspace.entries.firstOrNull { !it.value.accepted } ?: break
            val removedId = removable.key
            removable.value.transientVisionBitmap?.let { bmp -> runCatching { if (!bmp.isRecycled) bmp.recycle() } }
            workspace.remove(removedId)
            selectionCache.keys.removeAll { it.startsWith("$removedId|") }
        }
        while (selectionCache.size > MAX_SELECTION_CACHE) {
            selectionCache.remove(selectionCache.keys.first())
        }
        while (queryEmbeddingCache.size > MAX_QUERY_EMBEDDING_CACHE) {
            queryEmbeddingCache.remove(queryEmbeddingCache.keys.first())
        }
        while (validationEmbeddingCache.size > MAX_VALIDATION_EMBEDDING_CACHE) {
            validationEmbeddingCache.remove(validationEmbeddingCache.keys.first())
        }
    }

    suspend fun rememberAccepted(attachment: Phase1UserAttachment) {
        // Successful VLM/Cloud visual turns must remain recallable even when OCR/ML Kit
        // found little text. The source cache is therefore sufficient to retain the
        // attachment in the owning conversation workspace.
        if (!attachment.hasLocalEvidence && attachment.sourceCachePath.isBlank() && !attachment.hasLocalVlmPixels) return
        val entry = ensureWorkspaceEntry(attachment)
        synchronized(lock) {
            entry.accepted = true
            entry.lastUsedAtMs = System.currentTimeMillis()
            workspace.remove(entry.id)
            workspace[entry.id] = entry
            trimWorkspaceLocked()
        }
        persistAcceptedEntry(entry)
        Log.i(
            ATTACH_TAG,
            "ATTACHMENT_RECALL_STORED name=${attachment.displayName} chars=${attachment.extractedText.length} " +
                "chunks=${entry.chunks.size} persistent=true"
        )
    }

    /**
     * Session/service teardown unloads only RAM. Persistent conversation-owned
     * attachments stay on disk until their conversation is pruned/deleted.
     */
    fun clear() = synchronized(lock) {
        clearMemoryLocked()
        Log.i(ATTACH_TAG, "ATTACHMENT_WORKSPACE_UNLOADED persistent=true")
    }

    suspend fun contextAttachmentForTurn(
        attachment: Phase1UserAttachment,
        query: String,
        maxTextChars: Int = 5_000,
        broadSourceCoverage: Boolean = false
    ): BaselineContextAttachment? {
        val entry = ensureWorkspaceEntry(attachment)
        val selected = selectForEntry(
            entry = entry,
            query = query,
            maxChars = maxOf(maxTextChars, MAX_CLOUD_TEXT_CHARS),
            broadSourceCoverage = broadSourceCoverage
        ).take(maxTextChars)
        return attachment.toContextAttachment(query, selected)
    }

    private suspend fun semanticAttachmentDecision(
        query: String,
        positiveDescriptor: String,
        negativeDescriptor: String,
        positiveEmbedding: () -> FloatArray?,
        negativeEmbedding: () -> FloatArray?,
        setPositive: (FloatArray) -> Unit,
        setNegative: (FloatArray) -> Unit
    ): Boolean {
        if (query.isBlank() || !ensureSemanticReady()) return false
        val q = queryEmbeddingFor(query) ?: return false
        var pos = positiveEmbedding()
        var neg = negativeEmbedding()
        if (pos == null || neg == null) {
            synchronized(lock) {
                pos = positiveEmbedding()
                neg = negativeEmbedding()
            }
            if (pos == null) {
                val created = semanticEngine.embed("passage: $positiveDescriptor")
                if (created != null) {
                    setPositive(created)
                    pos = created
                }
            }
            if (neg == null) {
                val created = semanticEngine.embed("passage: $negativeDescriptor")
                if (created != null) {
                    setNegative(created)
                    neg = created
                }
            }
        }
        val p = pos ?: return false
        val n = neg ?: return false
        val positiveScore = semanticEngine.cosineSimilarity(q, p)
        val negativeScore = semanticEngine.cosineSimilarity(q, n)
        return positiveScore >= ATTACHMENT_INTENT_MIN_SCORE &&
            (positiveScore - negativeScore) >= ATTACHMENT_INTENT_MARGIN
    }

    private suspend fun isWholeSourceAnalysisRequest(query: String): Boolean =
        semanticAttachmentDecision(
            query = query,
            positiveDescriptor = WHOLE_SOURCE_ANALYSIS_DESCRIPTOR,
            negativeDescriptor = FOCUSED_SOURCE_QUESTION_DESCRIPTOR,
            positiveEmbedding = { wholeSourceEmbedding },
            negativeEmbedding = { focusedSourceEmbedding },
            setPositive = { wholeSourceEmbedding = it },
            setNegative = { focusedSourceEmbedding = it }
        )

    suspend fun isMultiAttachmentComparisonRequest(query: String): Boolean =
        semanticAttachmentDecision(
            query = query,
            positiveDescriptor = MULTI_SOURCE_DESCRIPTOR,
            negativeDescriptor = SINGLE_SOURCE_DESCRIPTOR,
            positiveEmbedding = { multiSourceEmbedding },
            negativeEmbedding = { singleSourceEmbedding },
            setPositive = { multiSourceEmbedding = it },
            setNegative = { singleSourceEmbedding = it }
        )

    private suspend fun isVisualAttachmentQuestion(query: String): Boolean {
        // Explicit page numbers are syntax, not semantic/topic routing.
        if (Regex("""(?i)\bpage\s*\d{1,3}\b""").containsMatchIn(query)) return true
        return semanticAttachmentDecision(
            query = query,
            positiveDescriptor = VISUAL_SOURCE_DESCRIPTOR,
            negativeDescriptor = TEXT_SOURCE_DESCRIPTOR,
            positiveEmbedding = { visualSourceEmbedding },
            negativeEmbedding = { textSourceEmbedding },
            setPositive = { visualSourceEmbedding = it },
            setNegative = { textSourceEmbedding = it }
        )
    }

    suspend fun queryNeedsVisualInspection(attachment: Phase1UserAttachment, query: String): Boolean {
        if (attachment.kind == Phase1AttachmentKind.IMAGE) return true
        return isVisualAttachmentQuestion(query)
    }

    /**
     * Resolve one query-relevant bitmap on demand. This keeps all 100 PDF pages
     * addressable offline without retaining 100 bitmaps in heap. Explicit page requests
     * are honored first; otherwise the page number is inferred from retrieved text.
     */
    suspend fun visionBitmapForTurn(attachment: Phase1UserAttachment, query: String): Bitmap? = withContext(Dispatchers.IO) {
        attachment.visionBitmaps.firstOrNull { !it.isRecycled && it.width > 0 && it.height > 0 }
            ?.takeIf { attachment.kind == Phase1AttachmentKind.IMAGE }
            ?.let { return@withContext it }

        val path = attachment.sourceCachePath.ifBlank {
            synchronized(lock) { workspace[attachment.id]?.sourceCachePath.orEmpty() }
        }
        if (path.isBlank()) return@withContext attachment.visionBitmaps.firstOrNull { !it.isRecycled }

        if (attachment.kind == Phase1AttachmentKind.IMAGE) {
            return@withContext decodeBitmapFileBounded(path, 960)
        }
        if (attachment.kind != Phase1AttachmentKind.PDF_DOCUMENT) {
            return@withContext attachment.visionBitmaps.firstOrNull { !it.isRecycled }
        }

        val explicit = Regex("""(?i)\bpage\s*(\d{1,3})\b""").find(query)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()?.minus(1)
        val selected = if (explicit != null) explicit else {
            val entry = ensureWorkspaceEntry(attachment)
            val relevant = selectForEntry(entry, query, 5_000)
            Regex("""\[Page\s+(\d{1,3})""").find(relevant)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()?.minus(1)
        }
        if (selected == null && queryNeedsVisualInspection(attachment, query)) {
            Log.w(
                ATTACH_TAG,
                "ATTACHMENT_VISUAL_PAGE_UNRESOLVED name=${attachment.displayName} pages=${attachment.pageCount}"
            )
            return@withContext null
        }
        val pageIndex = (selected ?: 0).coerceIn(0, (attachment.pageCount - 1).coerceAtLeast(0))
        val rendered = renderCachedPdfPage(path, pageIndex, 960) ?: return@withContext null
        val entry = ensureWorkspaceEntry(attachment)
        synchronized(lock) {
            entry.transientVisionBitmap?.let { old ->
                if (old !== rendered) runCatching { if (!old.isRecycled) old.recycle() }
            }
            entry.transientVisionBitmap = rendered
            entry.lastUsedAtMs = System.currentTimeMillis()
        }
        rendered
    }

    suspend fun recalledVisionBitmapForTurn(query: String): Bitmap? = withContext(Dispatchers.IO) {
        val entries = synchronized(lock) {
            workspace.values.filter { entry ->
                entry.accepted &&
                    entry.sourceCachePath.isNotBlank() &&
                    (entry.kind == Phase1AttachmentKind.IMAGE.name || entry.kind == Phase1AttachmentKind.PDF_DOCUMENT.name)
            }
        }
        if (entries.isEmpty()) return@withContext null
        val queryEmbedding = if (ensureSemanticReady()) queryEmbeddingFor(query) else null
        val entry = if (queryEmbedding != null) {
            entries.map { candidate -> candidate to semanticRecallScore(candidate, query, queryEmbedding) }
                .maxByOrNull { it.second }
                ?.first
        } else {
            entries.maxByOrNull { it.lastUsedAtMs }
        } ?: return@withContext null
        val path = entry.sourceCachePath
        val bitmap = when (entry.kind) {
            Phase1AttachmentKind.IMAGE.name -> decodeBitmapFileBounded(path, 960)
            Phase1AttachmentKind.PDF_DOCUMENT.name -> {
                val explicit = Regex("""(?i)\bpage\s*(\d{1,3})\b""").find(query)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()?.minus(1)
                val selected = if (explicit != null) explicit else {
                    val relevant = selectForEntry(entry, query, 5_000)
                    Regex("""\[Page\s+(\d{1,3})""").find(relevant)
                        ?.groupValues?.getOrNull(1)?.toIntOrNull()?.minus(1)
                }
                if (selected == null && isVisualAttachmentQuestion(query)) {
                    Log.w(
                        ATTACH_TAG,
                        "ATTACHMENT_RECALL_VISUAL_PAGE_UNRESOLVED name=${entry.label} pages=${entry.pageCount}"
                    )
                    return@withContext null
                }
                val index = (selected ?: 0).coerceIn(0, (entry.pageCount - 1).coerceAtLeast(0))
                renderCachedPdfPage(path, index, 960)
            }
            else -> null
        } ?: return@withContext null
        synchronized(lock) {
            entry.transientVisionBitmap?.let { old ->
                if (old !== bitmap) runCatching { if (!old.isRecycled) old.recycle() }
            }
            entry.transientVisionBitmap = bitmap
            entry.lastUsedAtMs = System.currentTimeMillis()
        }
        bitmap
    }

    private fun decodeBitmapFileBounded(path: String, longSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > longSide * 2) sample *= 2
        val decoded = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        if (maxOf(decoded.width, decoded.height) <= longSide) return decoded
        val scale = longSide.toFloat() / maxOf(decoded.width, decoded.height).toFloat()
        val resized = Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
        if (resized !== decoded) decoded.recycle()
        return resized
    }

    private fun renderCachedPdfPage(path: String, pageIndex: Int, longSide: Int): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null
        return runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (renderer.pageCount <= 0) return@use null
                    val safeIndex = pageIndex.coerceIn(0, renderer.pageCount - 1)
                    renderer.openPage(safeIndex).use { page ->
                        val scale = longSide.toFloat() / maxOf(page.width, page.height).toFloat()
                        val w = (page.width * scale).toInt().coerceAtLeast(1)
                        val h = (page.height * scale).toInt().coerceAtLeast(1)
                        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            }
        }.onFailure { Log.w(ATTACH_TAG, "PDF_ON_DEMAND_RENDER_FAILED page=${pageIndex + 1} ${it.message}") }.getOrNull()
    }

    suspend fun cloudEvidenceForTurn(
        attachment: Phase1UserAttachment,
        query: String,
        maxChars: Int = MAX_CLOUD_TEXT_CHARS,
        broadSourceCoverage: Boolean = false
    ): String {
        val entry = ensureWorkspaceEntry(attachment)
        val text = selectForEntry(entry, query, maxChars, broadSourceCoverage)
        val visual = attachment.localVisualContext.trim().take(2_400)
        if (text.isBlank() && visual.isBlank()) return ""
        return buildString {
            append("User attachment: ").append(attachment.displayName.take(160)).append('\n')
            append("Use this extracted source context for attachment-specific claims.\n")
            if (visual.isNotBlank()) {
                append("[On-device visual extraction]\n").append(visual).append('\n')
            }
            if (text.isNotBlank()) {
                append("[Relevant extracted source text]\n").append(text)
            }
            if (attachment.truncated) {
                append("\n[Source extraction was bounded; additional source material may exist.]")
            }
        }.take(maxChars)
    }

    /**
     * Phase-2 plan for explicit whole-document explanation/summary requests.
     *
     * Medium documents are covered contiguously. Very large documents use evenly
     * distributed windows so the app never pretends a small phone reviewed text that
     * was never presented to Gemma. coverageComplete is propagated into the synthesis
     * prompt and user-facing answer contract.
     */
    suspend fun summaryPlanForTurn(
        attachment: Phase1UserAttachment,
        query: String,
        maxSections: Int = MAX_SUMMARY_SECTIONS,
        sectionChars: Int = SUMMARY_SECTION_CHARS
    ): AttachmentSummaryPlan? {
        if (attachment.kind == Phase1AttachmentKind.IMAGE) return null
        if (!isWholeSourceAnalysisRequest(query)) return null
        val entry = ensureWorkspaceEntry(attachment)
        val source = entry.extractedText.replace("\r\n", "\n").trim()
        if (source.length < 4_500) return null

        val safeSections = maxSections.coerceIn(2, MAX_SUMMARY_SECTIONS)
        val safeChars = sectionChars.coerceIn(2_400, SUMMARY_SECTION_CHARS)
        val completeLimit = safeSections * safeChars
        val coverageComplete = source.length <= completeLimit && !attachment.truncated

        val sections = if (coverageComplete) {
            val out = mutableListOf<String>()
            var start = 0
            while (start < source.length && out.size < safeSections) {
                var end = minOf(source.length, start + safeChars)
                if (end < source.length) {
                    val paragraph = source.lastIndexOf("\n\n", end)
                    if (paragraph > start + safeChars / 2) end = paragraph
                }
                val piece = source.substring(start, end).trim()
                if (piece.isNotBlank()) out += piece
                start = end.coerceAtLeast(start + 1)
            }
            out
        } else {
            val lastStart = (source.length - safeChars).coerceAtLeast(0)
            (0 until safeSections).map { slot ->
                val start = if (safeSections <= 1) 0
                else ((lastStart.toDouble() * slot) / (safeSections - 1).toDouble()).toInt()
                val end = minOf(source.length, start + safeChars)
                source.substring(start, end).trim()
            }.filter { it.isNotBlank() }.distinct()
        }

        if (sections.size < 2) return null
        Log.i(
            ATTACH_TAG,
            "ATTACHMENT_HIERARCHICAL_PLAN name=${attachment.displayName} sections=${sections.size} " +
                "sourceChars=${source.length} coverageComplete=$coverageComplete"
        )
        return AttachmentSummaryPlan(
            sourceId = attachment.sourceId(),
            sourceLabel = attachment.displayName.take(120),
            sections = sections,
            coverageComplete = coverageComplete,
            sourceChars = source.length
        )
    }

    suspend fun isWholeAttachmentAnalysisRequest(query: String): Boolean =
        isWholeSourceAnalysisRequest(query)

    suspend fun cloudEscalationDecision(
        attachment: Phase1UserAttachment,
        query: String
    ): AttachmentEscalationDecision {
        val whole = isWholeSourceAnalysisRequest(query)
        val visualSpecific = isVisualAttachmentQuestion(query)
        val warnings = attachment.localAnalysisWarnings.joinToString("|").uppercase()
        val extractionFailure = warnings.contains("FAILED") || warnings.contains("IMAGE_ONLY_PAGES_BEYOND_OCR_LIMIT")
        val visualHeavy = attachment.kind == Phase1AttachmentKind.IMAGE ||
            attachment.kind == Phase1AttachmentKind.PDF_DOCUMENT ||
            attachment.kind == Phase1AttachmentKind.DOCX_DOCUMENT ||
            attachment.kind == Phase1AttachmentKind.PPTX_DOCUMENT ||
            attachment.kind == Phase1AttachmentKind.LEGACY_PPT_DOCUMENT
        // Product ownership contract: a PDF of up to 100 pages remains Local-first.
        // Initial visual sampling is intentionally bounded for memory, but arbitrary
        // pages can be rendered on demand from the cached source; cloud-recovery images
        // must therefore not steal ownership merely because the PDF has >6 pages.
        val localPdfWithinContract = attachment.kind == Phase1AttachmentKind.PDF_DOCUMENT &&
            attachment.pageCount in 1..MAX_PDF_NATIVE_TEXT_PAGES

        if (whole && attachment.truncated && !localPdfWithinContract) {
            return AttachmentEscalationDecision(
                shouldEscalate = true,
                category = AttachmentEscalationClass.CAPACITY,
                reason = "local_extraction_or_visual_coverage_bounded",
                coverageComplete = false
            )
        }
        if (visualSpecific && attachment.truncated && attachment.cloudRecoveryJpegs.isNotEmpty() && !localPdfWithinContract) {
            return AttachmentEscalationDecision(
                shouldEscalate = true,
                category = AttachmentEscalationClass.CAPABILITY,
                reason = "requested_visual_detail_may_be_beyond_local_analysis_cap",
                coverageComplete = false
            )
        }
        if (whole && visualHeavy && attachment.cloudRecoveryJpegs.isNotEmpty() && !localPdfWithinContract) {
            return AttachmentEscalationDecision(
                shouldEscalate = true,
                category = AttachmentEscalationClass.CAPABILITY,
                reason = "whole_document_requires_visual_content_beyond_local_analysis_cap",
                coverageComplete = !attachment.truncated
            )
        }
        if (extractionFailure && !attachment.hasLocalEvidence) {
            return AttachmentEscalationDecision(
                shouldEscalate = true,
                category = AttachmentEscalationClass.QUALITY,
                reason = "local_extraction_quality_insufficient",
                coverageComplete = false
            )
        }
        return AttachmentEscalationDecision(false)
    }

    /**
     * Zero-cost guard for semantic continuation routing. We only need the semantic
     * follow-up classifier when an accepted attachment actually participated in the
     * immediately preceding user turn. Strong lexical recall of older sources remains
     * available independently.
     */
    fun hasImmediateRecallCandidate(
        acceptedHistory: List<AndroChatMessage>,
        excludedAttachmentIds: Set<String> = emptySet()
    ): Boolean {
        val previousUserAt = acceptedHistory.asReversed()
            .firstOrNull { it.role == "user" }
            ?.timestampMs
            ?: return false
        return synchronized(lock) {
            workspace.values.any { entry ->
                entry.accepted &&
                    entry.id !in excludedAttachmentIds &&
                    entry.lastUsedAtMs >= previousUserAt
            }
        }
    }

    override suspend fun shouldAttach(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): Boolean = shouldAttachForTurn(query, acceptedHistory, emptySet())

    /**
     * Dynamic attachment-recall arbitration.  There are deliberately no query
     * keyword/deictic/length shortcuts here: retained sources participate only when
     * the existing lexical/semantic retrieval signals say they are relevant.
     *
     * [excludedAttachmentIds] are attachments explicitly supplied on the current
     * turn.  Excluding them prevents the same source from entering the prompt once
     * as turn-scoped evidence and again through session recall.
     */
    suspend fun shouldAttachForTurn(
        query: String,
        acceptedHistory: List<AndroChatMessage>,
        excludedAttachmentIds: Set<String>,
        contextDependentContinuation: Boolean = false
    ): Boolean {
        if (query.isBlank()) return false
        return relevantRecallEntries(query, excludedAttachmentIds, contextDependentContinuation, acceptedHistory).isNotEmpty()
    }

    override suspend fun contextFor(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): BaselineContextAttachment? =
        contextAttachmentsForTurnRecall(query, acceptedHistory, emptySet(), false).firstOrNull()

    suspend fun contextForTurnRecall(
        query: String,
        acceptedHistory: List<AndroChatMessage>,
        excludedAttachmentIds: Set<String>,
        contextDependentContinuation: Boolean = false
    ): BaselineContextAttachment? =
        contextAttachmentsForTurnRecall(query, acceptedHistory, excludedAttachmentIds, contextDependentContinuation).firstOrNull()

    /**
     * Preserve recalled files as independent grounding sources end-to-end. Recall is
     * deliberately lexical/cached-only: it never creates a query or passage embedding
     * on the live turn, so this path cannot add E5 inference latency.
     */
    suspend fun contextAttachmentsForTurnRecall(
        query: String,
        acceptedHistory: List<AndroChatMessage>,
        excludedAttachmentIds: Set<String>,
        contextDependentContinuation: Boolean = false
    ): List<BaselineContextAttachment> {
        val entries = relevantRecallEntries(query, excludedAttachmentIds, contextDependentContinuation, acceptedHistory)
        if (entries.isEmpty()) return emptyList()

        val rendered = entries.mapNotNull { entry ->
            // Every historical document follow-up gets semantic retrieval. This avoids
            // losing a source simply because the user paraphrased the document wording.
            val relevant = selectForEntry(
                entry = entry,
                query = query,
                maxChars = MAX_RECALL_TEXT_CHARS,
                forceSemantic = true
            )
            val text = buildString {
                append("Recent accepted attachment: ").append(entry.label)
                    .append(" (").append(entry.kind).append(")\n")
                append(
                    "Follow-up evidence boundary: use only this retained extraction for claims about this source; " +
                        "if the requested detail is absent, say so rather than guessing.\n"
                )
                if (entry.localVisualContext.isNotBlank()) {
                    append("[Retained on-device visual analysis]\n")
                    append(entry.localVisualContext.take(MAX_RECALL_VISUAL_CHARS))
                    append('\n')
                }
                if (relevant.isNotBlank()) {
                    append("[Retained extracted text]\n")
                    append(relevant)
                }
            }.trim()
            if (text.isBlank()) null else BaselineContextAttachment(
                sourceId = "user_attachment:${entry.id}",
                text = text,
                priority = 70,
                attributes = mapOf(
                    "user_attachment" to "true",
                    "attachment_recall" to "true",
                    "attachment_id" to entry.id,
                    "attachment_name" to entry.label,
                    "attachment_kind" to entry.kind,
                    "turn_scoped" to "false",
                    "offline_attachment_evidence" to "true",
                    "grounding_required" to "true"
                )
            )
        }
        Log.i(
            ATTACH_TAG,
            "ATTACHMENT_RECALL_ATTACHED count=${rendered.size} excludedCurrent=${excludedAttachmentIds.size} " +
                "chars=${rendered.sumOf { it.text.length }} perSource=true"
        )
        return rendered
    }

    /**
     * Select only recall sources actually related to the current query.  Strong
     * lexical overlap remains the zero-cost fast path.  Semantic follow-up scoring is
     * used only when the semantic engine is already resident, so recall never causes
     * a new E5/model load or increases first-turn latency.
     */
    private suspend fun relevantRecallEntries(
        query: String,
        excludedAttachmentIds: Set<String>,
        contextDependentContinuation: Boolean,
        acceptedHistory: List<AndroChatMessage>
    ): List<WorkspaceEntry> {
        val entries = synchronized(lock) {
            workspace.values.filter { it.accepted && it.id !in excludedAttachmentIds }
        }
        if (query.isBlank() || entries.isEmpty()) return emptyList()

        // Direct source identity is data-driven, not a static semantic keyword rule.
        // A filename/source label explicitly mentioned by the user must remain selectable
        // even when its content is semantically similar to another document.
        val normalizedQuery = normalizeAttachmentIdentity(query)
        val explicit = entries.filter { entry ->
            val label = normalizeAttachmentIdentity(entry.label)
            val stem = normalizeAttachmentIdentity(File(entry.label).nameWithoutExtension)
            (label.length >= 3 && normalizedQuery.contains(label)) ||
                (stem.length >= 3 && normalizedQuery.contains(stem))
        }

        val multiSource = if (entries.size > 1) isMultiAttachmentComparisonRequest(query) else false
        val maxSources = if (multiSource || explicit.size > 1) MAX_RECALL_SOURCES_PER_TURN else 2

        // An explicit source identity is already authoritative and should not wait for
        // the semantic engine to warm or create a query embedding. This keeps the common
        // "open/compare <filename>" follow-up on the zero-cost lexical path while
        // preserving semantic recall for paraphrased questions below.
        if (explicit.isNotEmpty()) {
            Log.i(
                ATTACH_TAG,
                "ATTACHMENT_RECALL_ROUTE candidates=${entries.size} explicit=${explicit.size} " +
                    "semantic=false multi=$multiSource selected=${explicit.size.coerceAtMost(maxSources)} fast=identity"
            )
            return explicit.take(maxSources)
        }

        val semanticReady = ensureSemanticReady()
        val queryEmbedding = if (semanticReady) queryEmbeddingFor(query) else null
        val scored = if (queryEmbedding != null) {
            entries.map { entry -> entry to semanticRecallScore(entry, query, queryEmbedding) }
                .sortedByDescending { it.second }
        } else {
            emptyList()
        }

        val selected = linkedSetOf<WorkspaceEntry>()
        explicit.forEach { selected += it }
        scored.asSequence()
            .filter { it.first !in selected }
            .filter { (_, score) -> score >= MIN_SEMANTIC_FOLLOWUP }
            .take((maxSources - selected.size).coerceAtLeast(0))
            .forEach { selected += it.first }

        if (selected.isEmpty() && contextDependentContinuation) {
            // If the utterance is semantically dependent on the immediately preceding
            // conversation and semantic source ranking did not cross the admission
            // threshold, preserve the most recently used source from that prior turn.
            val previousUserAt = acceptedHistory.asReversed()
                .firstOrNull { it.role == "user" }
                ?.timestampMs
                ?: Long.MAX_VALUE
            entries
                .filter { it.lastUsedAtMs >= previousUserAt }
                .maxByOrNull { it.lastUsedAtMs }
                ?.let { selected += it }
        }

        Log.i(
            ATTACH_TAG,
            "ATTACHMENT_RECALL_ROUTE candidates=${entries.size} explicit=${explicit.size} " +
                "semantic=${queryEmbedding != null} multi=$multiSource selected=${selected.size}"
        )
        return selected.take(maxSources)
    }

    private suspend fun semanticRecallScore(
        entry: WorkspaceEntry,
        query: String,
        queryEmbedding: FloatArray
    ): Float {
        if (entry.chunks.isEmpty()) return 0f

        // Evaluate lexical candidates plus distributed coverage, but every candidate is
        // semantically scored. This gives paraphrases and distant document sections a
        // chance without embedding every page/chunk on every turn.
        val lexical = entry.chunks.mapIndexed { index, chunk ->
            index to lexicalOverlapScore(query, chunk)
        }
        val candidateIndexes = linkedSetOf<Int>()
        lexical.sortedByDescending { it.second }.take(6).forEach { candidateIndexes += it.first }
        val distributed = minOf(6, entry.chunks.size)
        for (slot in 0 until distributed) {
            val index = if (distributed <= 1) 0
            else ((entry.chunks.lastIndex.toDouble() * slot) / (distributed - 1).toDouble()).toInt()
            candidateIndexes += index
        }
        candidateIndexes += 0
        candidateIndexes += entry.chunks.lastIndex

        var best = 0f
        for (index in candidateIndexes) {
            val emb = embeddingFor(entry, index) ?: continue
            best = maxOf(best, semanticEngine.cosineSimilarity(queryEmbedding, emb))
        }

        // Source name is also part of source identity and helps explicit document
        // references without any hard-coded filename/topic vocabulary.
        val labelEmbedding = validationEmbedding("passage", entry.label)
        if (labelEmbedding != null) {
            best = maxOf(best, semanticEngine.cosineSimilarity(queryEmbedding, labelEmbedding))
        }
        return best
    }

    private fun normalizeAttachmentIdentity(value: String): String =
        value.lowercase()
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    override suspend fun validate(
        query: String,
        answer: String,
        provider: AndroBaselineRuntime.Provider,
        attachments: List<BaselineContextAttachment>,
        acceptedHistory: List<AndroChatMessage>
    ): BaselineValidationResult {
        if (provider != AndroBaselineRuntime.Provider.LOCAL) return BaselineValidationResult.ACCEPT
        // This validator owns user attachments only. Selected-KB/SOP grounding is
        // validated by SelectedKnowledgeContextProvider and must remain untouched.
        val groundedSources = attachments.filter {
            it.attributes["grounding_required"] == "true" &&
                (it.attributes["user_attachment"] == "true" || it.attributes["attachment_recall"] == "true")
        }
        if (groundedSources.isEmpty()) return BaselineValidationResult.ACCEPT

        if (groundedSources.none { it.text.isNotBlank() }) {
            return BaselineValidationResult.reject("attachment_source_missing")
        }
        if (containsPrivateAttachmentPromptLeak(answer)) {
            return BaselineValidationResult.reject("attachment_private_context_leak")
        }

        val claims = splitAttachmentClaims(answer)
        if (claims.isEmpty()) return BaselineValidationResult.ACCEPT
        val semanticReady = ensureSemanticReady()
        for (claim in claims.take(10)) {
            if (claim.length < 12) continue

            // Validate against the bounded union of the already-selected grounding
            // sources. Recall arbitration decides which documents belong to the turn;
            // validation must not force a multi-document claim onto one filename or one
            // source and then reject otherwise-correct comparisons.
            val primarySources = groundedSources
            val perSourceChars = (24_000 / primarySources.size.coerceAtLeast(1)).coerceAtLeast(2_000)
            val primaryText = primarySources.joinToString("\n\n") { it.text.take(perSourceChars) }.take(24_000)
            if (primaryText.isBlank()) {
                return BaselineValidationResult.reject("attachment_source_missing")
            }
            if (containsUnsupportedNumber(claim, primaryText)) {
                return BaselineValidationResult.reject("attachment_unsupported_numeric_claim")
            }
            val bestLexical = primarySources.maxOfOrNull { lexicalOverlapScore(claim, it.text) } ?: 0f
            if (bestLexical >= MIN_LEXICAL_CLAIM) continue

            var semantic = 0f
            if (semanticReady) {
                val claimEmbedding = validationEmbedding("query", claim.take(700))
                if (claimEmbedding != null) {
                    val candidates = primarySources
                        .asSequence()
                        .flatMap { source -> attachmentChunks(source.text).asSequence() }
                        .map { it to lexicalOverlapScore(claim, it) }
                        .sortedByDescending { it.second }
                        .take(6)
                        .toList()
                    for ((chunk, _) in candidates) {
                        val emb = validationEmbedding("passage", chunk.take(1_700)) ?: continue
                        semantic = maxOf(semantic, semanticEngine.cosineSimilarity(claimEmbedding, emb))
                    }
                }
            }
            if (!(semantic >= MIN_SEMANTIC_CLAIM && bestLexical >= MIN_LEXICAL_WITH_SEMANTIC)) {
                Log.w(
                    ATTACH_TAG,
                    "ATTACHMENT_GROUNDING_REJECT sources=${primarySources.joinToString("|") { it.sourceId }} " +
                        "lexical=${"%.3f".format(bestLexical)} semantic=${"%.3f".format(semantic)} " +
                        "claim='${claim.take(180)}'"
                )
                return BaselineValidationResult.reject("attachment_claim_not_supported")
            }
        }
        Log.i(ATTACH_TAG, "ATTACHMENT_GROUNDING_ACCEPT claims=${claims.size}")
        return BaselineValidationResult.ACCEPT
    }

    private suspend fun selectForEntry(
        entry: WorkspaceEntry,
        query: String,
        maxChars: Int,
        broadSourceCoverage: Boolean = false,
        forceSemantic: Boolean = false
    ): String {
        if (entry.extractedText.isBlank()) return ""
        if (entry.extractedText.length <= maxChars) return entry.extractedText

        val key = "${entry.id}|${if (broadSourceCoverage) "BROAD" else "QUERY"}|${query.trim().lowercase().take(220)}"
        synchronized(lock) {
            selectionCache[key]?.let {
                entry.lastUsedAtMs = System.currentTimeMillis()
                return it.take(maxChars)
            }
        }

        val chunks = entry.chunks
        if (chunks.isEmpty()) return ""
        val broad = broadSourceCoverage || isWholeSourceAnalysisRequest(query)
        val selected = if (broad) {
            renderDistributedChunks(chunks, minOf(8, chunks.size), maxChars)
        } else {
            selectHybridFromWorkspace(entry, query, maxChars, forceSemantic)
        }

        synchronized(lock) {
            selectionCache[key] = selected.take(MAX_CLOUD_TEXT_CHARS)
            entry.lastUsedAtMs = System.currentTimeMillis()
            trimWorkspaceLocked()
        }
        return selected.take(maxChars)
    }

    private suspend fun selectHybridFromWorkspace(
        entry: WorkspaceEntry,
        query: String,
        maxChars: Int,
        forceSemantic: Boolean = false
    ): String {
        val chunks = entry.chunks
        val queryTerms = attachmentTerms(query)
        val lexical = chunks.mapIndexed { index, chunk ->
            Triple(index, lexicalOverlapScore(query, chunk), chunk)
        }
        val lexicalTop = lexical.sortedByDescending { it.second }.take(20)
        val distributedCount = minOf(10, chunks.size)
        val distributed = (0 until distributedCount).map { slot ->
            val index = if (distributedCount <= 1) 0
            else ((chunks.lastIndex.toDouble() * slot) / (distributedCount - 1).toDouble()).toInt()
            lexical[index]
        }
        // A file of up to ATTACHMENT_SEMANTIC_ALL_CHUNKS excerpts (~30 pages) is read by meaning in
        // full: a question worded differently from the page ("is the bid valid?" vs "validity of
        // offer") has no word overlap, so the right page was not even a candidate. Embeddings are
        // computed once per file and kept.
        val candidates = (if (chunks.size <= ATTACHMENT_SEMANTIC_ALL_CHUNKS) lexical
            else lexicalTop + distributed + lexical.take(2) + lexical.takeLast(2))
            .distinctBy { it.first }

        // Large-document fast path: when the user's meaningful terms already have a
        // strong direct match, semantic rescoring adds substantial latency while rarely
        // changing the selected passage. Keep semantic retrieval for ambiguous/deictic
        // follow-ups and weak lexical matches. This is evidence-driven, not domain routing.
        val maxLexical = lexicalTop.maxOfOrNull { it.second } ?: 0f
        if (!forceSemantic && queryTerms.isNotEmpty() && maxLexical >= 0.72f) {
            Log.i(
                ATTACH_TAG,
                "ATTACHMENT_RETRIEVAL_FAST_PATH mode=LEXICAL_STRONG " +
                    "score=${"%.3f".format(maxLexical)} chunks=${chunks.size}"
            )
            return renderRankedChunks(fitByRelevance(lexicalTop, lexical.firstOrNull(), maxChars), maxChars)
        }

        if (queryTerms.isEmpty() || !ensureSemanticReady()) {
            return renderRankedChunks(fitByRelevance(lexicalTop, lexical.firstOrNull(), maxChars), maxChars)
        }

        val queryEmbedding = queryEmbeddingFor(query)
            ?: return selectRelevantAttachmentText(query, entry.extractedText, maxChars)
        val lexicalScale = candidates.maxOfOrNull { it.second }?.coerceAtLeast(0.0001f) ?: 1f
        val rescored = mutableListOf<Triple<Int, Float, String>>()
        for ((index, lex, chunk) in candidates) {
            val emb = embeddingFor(entry, index)
            val semantic = if (emb == null) 0f else semanticEngine.cosineSimilarity(queryEmbedding, emb)
            val combined = (semantic * 0.72f) + ((lex / lexicalScale).coerceIn(0f, 1f) * 0.28f)
            rescored += Triple(index, combined, chunk)
        }

        val selected = fitByRelevance(rescored.sortedByDescending { it.second }, rescored.minByOrNull { it.first }, maxChars)
        Log.i(ATTACH_TAG, "ATTACHMENT_RETRIEVAL_SELECTED chunks=${selected.map { it.first + 1 }} of=${chunks.size} budget=$maxChars")
        return renderRankedChunks(selected, maxChars)
    }

    private suspend fun semanticFollowupScore(
        entry: WorkspaceEntry,
        query: String,
        queryEmbedding: FloatArray
    ): Float {
        val lexical = entry.chunks.mapIndexed { index, chunk ->
            index to lexicalOverlapScore(query, chunk)
        }.sortedByDescending { it.second }.take(12)
        var best = 0f
        for ((index, _) in lexical) {
            val emb = embeddingFor(entry, index) ?: continue
            best = maxOf(best, semanticEngine.cosineSimilarity(queryEmbedding, emb))
        }
        return best
    }

    private suspend fun queryEmbeddingFor(query: String): FloatArray? {
        val key = query.trim().lowercase().take(1_200)
        synchronized(lock) { queryEmbeddingCache[key] }?.let { return it }
        if (!ensureSemanticReady()) return null
        val embedding = semanticEngine.embed("query: ${query.trim().take(1_200)}") ?: return null
        synchronized(lock) {
            queryEmbeddingCache[key] = embedding
            trimWorkspaceLocked()
        }
        return embedding
    }

    private suspend fun embeddingFor(entry: WorkspaceEntry, index: Int): FloatArray? {
        if (index !in entry.chunks.indices) return null
        synchronized(lock) { entry.embeddings[index] }?.let { return it }
        if (!ensureSemanticReady()) return null
        val embedding = semanticEngine.embed("passage: ${entry.chunks[index].take(1_700)}") ?: return null
        synchronized(lock) {
            if (index in entry.embeddings.indices && entry.embeddings[index] == null) {
                entry.embeddings[index] = embedding
            }
            entry.lastUsedAtMs = System.currentTimeMillis()
        }
        return embedding
    }

    private suspend fun validationEmbedding(kind: String, text: String): FloatArray? {
        val clean = text.trim()
        if (clean.isBlank()) return null
        val key = "$kind:${clean.take(1_700)}"
        synchronized(lock) { validationEmbeddingCache[key] }?.let { return it }
        if (!semanticEngine.isReady) return null
        val prefix = if (kind == "passage") "passage: " else "query: "
        val embedding = semanticEngine.embed(prefix + clean) ?: return null
        synchronized(lock) {
            validationEmbeddingCache[key] = embedding
            trimWorkspaceLocked()
        }
        return embedding
    }

    private suspend fun ensureSemanticReady(): Boolean {
        if (semanticEngine.isReady) return true
        return try {
            semanticEngine.initialise()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            false
        }
    }
}

/**
 * Lightweight local document retrieval. This is deliberately deterministic: it
 * selects query-relevant excerpts without an extra LLM/embedding call. When no
 * lexical evidence is strong (for example "summarize this document"), it samples
 * across the whole document rather than returning only the beginning.
 */
private fun selectRelevantAttachmentText(query: String, source: String, maxChars: Int): String {
    val clean = source.replace("\r\n", "\n").trim()
    if (clean.length <= maxChars) return clean

    val chunks = attachmentChunks(clean)

    val terms = query.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .split(Regex("\\s+"))
        .filter { it.length >= 3 }
        .distinct()

    val ranked = chunks.mapIndexed { index, chunk ->
        val lower = chunk.lowercase()
        var score = 0.0
        for (term in terms) {
            var from = 0
            var hits = 0
            while (hits < 6) {
                val at = lower.indexOf(term, from)
                if (at < 0) break
                hits++
                from = at + term.length
            }
            score += hits * (1.0 + minOf(term.length, 10) / 10.0)
        }
        Triple(index, score, chunk)
    }

    val strongest = ranked.maxOfOrNull { it.second } ?: 0.0
    val selected = if (terms.isNotEmpty() && strongest > 0.0) {
        // Preserve the document opening for title/context, then add the strongest
        // query matches in source order. No semantic model call is required.
        val top = ranked.sortedWith(
            compareByDescending<Triple<Int, Double, String>> { it.second }.thenBy { it.first }
        ).take(5)
        (listOfNotNull(ranked.firstOrNull()) + top)
            .distinctBy { it.first }
            .take(5)
            .sortedBy { it.first }
    } else {
        // Whole-document/low-match request: distribute excerpts from beginning to end.
        val count = minOf(5, chunks.size)
        if (count <= 1) ranked.take(1) else {
            (0 until count).map { slot ->
                val index = ((chunks.lastIndex.toDouble() * slot) / (count - 1).toDouble()).toInt()
                ranked[index]
            }.distinctBy { it.first }.sortedBy { it.first }
        }
    }

    return buildString {
        for ((index, _, chunk) in selected) {
            if (isNotEmpty()) append("\n\n")
            append("[Excerpt ").append(index + 1).append("]\n")
            append(chunk.trim())
            if (length >= maxChars) break
        }
    }.take(maxChars)
}

private const val ATTACHMENT_SEMANTIC_ALL_CHUNKS = 60

private fun attachmentChunks(source: String): List<String> {
    val clean = source.replace("\r\n", "\n").trim()
    if (clean.isBlank()) return emptyList()
    val chunks = mutableListOf<String>()
    var start = 0
    val chunkChars = 1_400
    val overlap = 180
    while (start < clean.length && chunks.size < 768) {
        val end = minOf(clean.length, start + chunkChars)
        chunks += clean.substring(start, end)
        if (end >= clean.length) break
        start = (end - overlap).coerceAtLeast(start + 1)
    }
    return chunks
}

private fun attachmentTerms(value: String): List<String> =
    value.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}._/%+-]+"), " ")
        .split(Regex("\\s+"))
        .map { it.trim() }
        .filter { it.length >= 3 && it !in ATTACHMENT_STOP_WORDS }
        .distinct()

private val ATTACHMENT_STOP_WORDS = setOf(
    "the", "and", "for", "this", "that", "with", "from", "what", "when", "where",
    "which", "would", "could", "should", "please", "help", "tell", "about", "into",
    "document", "file", "attached", "attachment", "image", "pdf", "word", "ppt", "pptx"
)

private fun lexicalOverlapScore(queryOrClaim: String, source: String): Float {
    val terms = attachmentTerms(queryOrClaim)
    if (terms.isEmpty()) return 0f
    val lower = source.lowercase()
    var matchedWeight = 0.0
    var totalWeight = 0.0
    for (term in terms) {
        val weight = 1.0 + minOf(term.length, 12) / 12.0
        totalWeight += weight
        if (lower.contains(term)) matchedWeight += weight
    }
    return if (totalWeight <= 0.0) 0f else (matchedWeight / totalWeight).toFloat()
}

private fun renderDistributedChunks(
    chunks: List<String>,
    count: Int,
    maxChars: Int
): String {
    if (chunks.isEmpty() || maxChars <= 0) return ""
    val safeCount = count.coerceIn(1, chunks.size)
    val indices = if (safeCount <= 1) listOf(0) else (0 until safeCount).map { slot ->
        ((chunks.lastIndex.toDouble() * slot) / (safeCount - 1).toDouble()).toInt()
    }.distinct()
    return buildString {
        for (index in indices) {
            if (isNotEmpty()) append("\n\n")
            append("[Excerpt ").append(index + 1).append("]\n")
            append(chunks[index].trim())
            if (length >= maxChars) break
        }
    }.take(maxChars)
}

/**
 * The excerpts that reach the model: the most relevant first, as many as fit in [maxChars], then
 * shown in document order. The document's opening excerpt (title, scope) is added only when room
 * is left. Before, up to nine excerpts were put in document order and then cut at [maxChars], so
 * the opening pages always survived and the best-matching page deep in the file was cut off.
 */
private fun fitByRelevance(
    ranked: List<Triple<Int, Float, String>>,
    opening: Triple<Int, Float, String>?,
    maxChars: Int
): List<Triple<Int, Float, String>> {
    val out = ArrayList<Triple<Int, Float, String>>()
    var used = 0
    fun cost(c: Triple<Int, Float, String>) = c.third.trim().length + 16
    for (c in ranked.distinctBy { it.first }) {
        if (out.size >= 8) break
        if (out.isNotEmpty() && used + cost(c) > maxChars) continue
        out += c; used += cost(c)
    }
    if (opening != null && out.none { it.first == opening.first } && used + cost(opening) <= maxChars) out += opening
    return out.sortedBy { it.first }
}

private fun <T> renderRankedChunks(
    selected: List<Triple<Int, T, String>>,
    maxChars: Int
): String {
    if (selected.isEmpty() || maxChars <= 0) return ""
    return buildString {
        for ((index, _, chunk) in selected) {
            if (isNotEmpty()) append("\n\n")
            append("[Excerpt ").append(index + 1).append("]\n")
            append(chunk.trim())
            if (length >= maxChars) break
        }
    }.take(maxChars)
}

private fun splitAttachmentClaims(answer: String): List<String> =
    answer.replace("\r\n", "\n")
        .split(Regex("(?<=[.!?])\\s+|\\n+"))
        .map { it.trim().replace(Regex("^[\\-*•\\d.)\\s]+"), "").trim() }
        .filter { it.length >= 12 }
        .filterNot {
            val l = it.lowercase()
            l.startsWith("i can't") || l.startsWith("i cannot") || l.startsWith("i don’t") ||
                l.startsWith("i don't") || l.startsWith("please upload") ||
                l.startsWith("offline summary note:") || l.endsWith("?")
        }

private fun containsUnsupportedNumber(claim: String, source: String): Boolean {
    val claimNumbers = Regex("(?<![\\p{L}\\p{N}])\\d+(?:[.,]\\d+)?%?(?![\\p{L}\\p{N}])")
        .findAll(claim).map { it.value.replace(",", "") }.toSet()
    if (claimNumbers.isEmpty()) return false
    val normalizedSource = source.replace(",", "")
    return claimNumbers.any { !normalizedSource.contains(it) }
}

private fun containsPrivateAttachmentPromptLeak(answer: String): Boolean {
    val a = answer.lowercase()
    return listOf(
        "offline evidence boundary", "locally extracted document", "relevant context",
        "current user question", "internal_context", "verified reference evidence",
        "private case memory", "instructions for the assistant"
    ).any { a.contains(it) }
}
