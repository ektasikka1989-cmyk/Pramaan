package com.example.arfas

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

private const val TAG = "ModelFileManager"

enum class ModelSource(
    val label: String,
    val detailText: String,
    val pillColorHex: Long
) {
    GEMMA_3N_E2B("On-device Gemma 3N E2B - vision", "Gemma 3N E2B (2.1GB litertlm)", 0xFF00E676L),           // Green
    LLAMA_3_2_3B("On-device Llama 3.2 3B - text reasoning + DINOv2", "Llama 3.2 3B GGUF (1.9GB)", 0xFF29B6F6L), // Blue
    LLAMA_11B_VISION("On-device Llama 11B Vision", "Llama 11B Vision", 0xFF00B0FFL),                            // Light Blue
    DINOV2_SAM("On-device DINOv2 + MobileSAM", "DINOv2 + MobileSAM (40MB)", 0xFFFF9100L),                       // Orange
    ENSEMBLE_LIGHT("Meta Ensemble (DINOv2-B+DETR+SAM2+CLIP+OCR -> Llama 3B)", "Meta Ensemble 2.06GB", 0xFF00E676L), // Green
    META_CLOUD_SCOUT("Cloud Meta Llama-4-Scout", "Meta Llama-4-Scout-17B", 0xFFAB47BCL),                        // Purple
    META_CLOUD_MAVERICK("Cloud Meta Llama-4-Maverick", "Meta Llama-4-Maverick-17B", 0xFF8E24AAL),               // Dark Purple
    MLKIT_FALLBACK("On-device ML Kit OCR/Barcode", "ML Kit Deterministic", 0xFFFFD54FL)                         // Amber
}

enum class ForceModelMode { AUTO, GEMMA_ONLY, LLAMA_ONLY, ENSEMBLE_ONLY, CLOUD_ONLY }

data class VisionResult(
    val answer: String,
    val source: ModelSource,
    val raw: String = "",
    val timestampMs: Long = System.currentTimeMillis()
)

data class ZipExtractionReport(
    val successCount: Int = 0,
    val totalCount: Int = 5,
    val logs: List<String> = emptyList(),
    val installedStatus: Map<ModelFileManager.ModelType, Boolean> = emptyMap()
)

/**
 * ModelFileManager — owns all model file operations and source tracking prefs.
 *
 * Supports Gemma 3N E2B (.litertlm) and Meta Stack models (DINOv2-B, SAM2, DETR, CLIP ViT-B/32, Llama 3.2 3B, OCR).
 * Model files are stored in app private storage:
 *   /data/data/com.example.arfas/files/models/
 */
class ModelFileManager(private val context: Context) {

    enum class ModelType { GEMMA_E2B, DINOv2, SAM31, DETR, LLAMA3B, OCR, CLIP }

    companion object {
        private const val PREFS_NAME             = "arfas_model_prefs"
        private const val KEY_MODEL_FILE         = "selected_model_filename"
        private const val KEY_DINOV2_FILE        = "model_dinov2_path"
        private const val KEY_SAM31_FILE         = "model_sam31_path"
        private const val KEY_DETR_FILE          = "model_detr_path"
        private const val KEY_LLAMA3B_FILE       = "model_llama3b_path"
        private const val KEY_OCR_FILE           = "model_ocr_path"
        private const val KEY_CLIP_FILE          = "model_clip_path"
        private const val KEY_USE_META_STACK     = "use_meta_stack"
        private const val KEY_FORCE_MODEL_MODE   = "force_model_mode"
        private const val KEY_GEMMA_DISCONNECTED = "gemma_disconnected"
        private const val KEY_LLAMA_DISCONNECTED = "llama_disconnected"
        private const val KEY_LAST_SOURCE        = "last_answer_source"
        private const val KEY_LAST_SOURCE_TIME   = "last_answer_source_time"
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val modelsDir: File by lazy {
        File(context.filesDir, "models").also { it.mkdirs() }
    }

    // ── Meta Stack & Force Model Mode Prefs ──────────────────────────────────

    fun getUseMetaStack(): Boolean = prefs.getBoolean(KEY_USE_META_STACK, false)

    fun setUseMetaStack(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_USE_META_STACK, enabled).apply()
        Log.d(TAG, "Meta Stack flag updated: $enabled")
    }

    fun getForceModelMode(): ForceModelMode = try {
        ForceModelMode.valueOf(prefs.getString(KEY_FORCE_MODEL_MODE, ForceModelMode.AUTO.name) ?: ForceModelMode.AUTO.name)
    } catch (_: Exception) { ForceModelMode.AUTO }

    fun setForceModelMode(mode: ForceModelMode) {
        prefs.edit().putString(KEY_FORCE_MODEL_MODE, mode.name).apply()
        Log.d(TAG, "Force model mode set to: ${mode.name}")
    }

    fun isGemmaDisconnected(): Boolean = prefs.getBoolean(KEY_GEMMA_DISCONNECTED, false)

    fun setGemmaDisconnected(disconnected: Boolean) {
        prefs.edit().putBoolean(KEY_GEMMA_DISCONNECTED, disconnected).apply()
        Log.d(TAG, "Gemma disconnected set to: $disconnected")
    }

    fun isLlamaDisconnected(): Boolean = prefs.getBoolean(KEY_LLAMA_DISCONNECTED, false)

    fun setLlamaDisconnected(disconnected: Boolean) {
        prefs.edit().putBoolean(KEY_LLAMA_DISCONNECTED, disconnected).apply()
        Log.d(TAG, "Llama disconnected set to: $disconnected")
    }

    fun recordLastAnswerSource(source: ModelSource) {
        prefs.edit()
            .putString(KEY_LAST_SOURCE, source.name)
            .putLong(KEY_LAST_SOURCE_TIME, System.currentTimeMillis())
            .apply()
        Log.d(TAG, "Recorded last answer source: ${source.name}")
    }

    fun getLastAnswerSource(): Pair<ModelSource, Long> {
        val name = prefs.getString(KEY_LAST_SOURCE, ModelSource.GEMMA_3N_E2B.name) ?: ModelSource.GEMMA_3N_E2B.name
        val time = prefs.getLong(KEY_LAST_SOURCE_TIME, 0L)
        val source = try { ModelSource.valueOf(name) } catch (_: Exception) { ModelSource.GEMMA_3N_E2B }
        return source to time
    }

    // ── Preference Key Mapping ────────────────────────────────────────────────

    fun getPrefKey(type: ModelType): String = when (type) {
        ModelType.GEMMA_E2B -> KEY_MODEL_FILE
        ModelType.DINOv2    -> KEY_DINOV2_FILE
        ModelType.SAM31     -> KEY_SAM31_FILE
        ModelType.DETR      -> KEY_DETR_FILE
        ModelType.LLAMA3B   -> KEY_LLAMA3B_FILE
        ModelType.OCR       -> KEY_OCR_FILE
        ModelType.CLIP      -> KEY_CLIP_FILE
    }

    // ── Type-based Model Path & Install Check ────────────────────────────────

    fun getModelPath(type: ModelType): String? {
        val key = getPrefKey(type)
        val name = prefs.getString(key, "") ?: ""
        if (name.isBlank()) return null
        val file = File(modelsDir, name)
        return if (file.exists()) file.absolutePath else null
    }

    fun getMinSizeBytes(type: ModelType): Long = when (type) {
        ModelType.GEMMA_E2B -> 500_000_000L  // >500MB
        ModelType.DINOv2    -> 10_000_000L   // >10MB
        ModelType.SAM31     -> 10_000_000L   // >10MB (supports 38.5MB SAM2 / MobileSAM)
        ModelType.DETR      -> 10_000_000L   // >10MB (supports 159MB DETR)
        ModelType.LLAMA3B   -> 500_000_000L  // >500MB
        ModelType.OCR       -> 500_000L      // >500KB (allows 654KB inference.pdmodel if present)
        ModelType.CLIP      -> 10_000_000L   // >10MB (supports 150MB CLIP ViT-B/32)
    }

    fun isModelInstalled(type: ModelType): Boolean = when (type) {
        ModelType.GEMMA_E2B -> getModelPath(type)?.let { File(it).length() >= getMinSizeBytes(type) } == true
        ModelType.LLAMA3B -> metaFile(MetaModelStore.Component.LLAMA)?.let { it.length() >= getMinSizeBytes(type) } == true
        ModelType.DETR -> metaFile(MetaModelStore.Component.DETR) != null
        ModelType.CLIP -> metaFile(MetaModelStore.Component.CLIP_VISION) != null
        ModelType.DINOv2 -> metaFile(MetaModelStore.Component.DINOV2) != null
        ModelType.SAM31 -> metaFile(MetaModelStore.Component.SAM_ENCODER) != null &&
            metaFile(MetaModelStore.Component.SAM_DECODER) != null
        // Text reading uses the on-device ML Kit recognisers (Latin + Devanagari); nothing to install.
        ModelType.OCR -> true
    }

    fun getModelSize(type: ModelType): Long {
        val path = getModelPath(type) ?: return 0L
        val file = File(path)
        return if (file.exists()) file.length() / 1024 / 1024 else 0L
    }

    fun deleteModel(type: ModelType): Boolean {
        val key = getPrefKey(type)
        val name = prefs.getString(key, "") ?: ""
        if (name.isNotBlank()) {
            val file = File(modelsDir, name)
            if (file.exists()) file.delete()
            prefs.edit().remove(key).apply()
            Log.d(TAG, "Deleted model $type: $name")
            return true
        }
        return false
    }

    fun saveModelFileName(type: ModelType, fileName: String) {
        val key = getPrefKey(type)
        prefs.edit().putString(key, fileName).apply()
        Log.d(TAG, "Saved model filename for $type: $fileName")
    }

    // ── Meta models zip (streamed, Zip-Slip safe, no duplicate copies) ───────

    val metaStore: MetaModelStore by lazy { MetaModelStore(modelsDir) }

    private fun legacyFile(type: ModelType): File? = getModelPath(type)?.let(::File)

    /** The Meta model file of [component] (new layout first, then the old installer's file). */
    fun metaFile(component: MetaModelStore.Component): File? = when (component) {
        MetaModelStore.Component.LLAMA -> metaStore.file(component, legacyFile(ModelType.LLAMA3B))
        MetaModelStore.Component.DETR -> metaStore.file(component, legacyFile(ModelType.DETR))
        MetaModelStore.Component.CLIP_VISION -> metaStore.file(component, legacyFile(ModelType.CLIP))
        MetaModelStore.Component.DINOV2 -> metaStore.file(component, legacyFile(ModelType.DINOv2))
        MetaModelStore.Component.SAM_ENCODER -> metaStore.file(component, legacyFile(ModelType.SAM31))
        else -> metaStore.file(component)
    }

    fun metaStatus(): List<MetaModelStore.GroupStatus> = metaStore.status(
        legacyLlama = legacyFile(ModelType.LLAMA3B),
        legacyDetr = legacyFile(ModelType.DETR),
        legacyClip = legacyFile(ModelType.CLIP)
    )

    /**
     * The on-device text model to load. Meta Llama (GGUF) whenever it is installed, unless the
     * user forced Gemma or disconnected Llama; otherwise the installed .litertlm bundle.
     */
    /** The installed Gemma .litertlm file, or null (used as the phone AI by choice or as Llama's stand-by). */
    fun gemmaModelFile(): File? {
        val gemmaName = getSelectedModelFileName().trim().ifBlank { recoverSingleInstalledModel().orEmpty() }
        return gemmaName.takeIf { it.isNotBlank() }?.let(::getModelFile)
            ?.takeIf { it.exists() && it.length() > 0 && it.name.endsWith(".litertlm", true) }
    }

    fun preferredTextModelFile(): File? {
        val force = getForceModelMode()
        val llama = metaFile(MetaModelStore.Component.LLAMA)
            ?.takeIf { it.length() >= getMinSizeBytes(ModelType.LLAMA3B) }
        val gemma = gemmaModelFile()
        return when {
            force == ForceModelMode.GEMMA_ONLY -> gemma
            llama != null && !isLlamaDisconnected() -> llama
            else -> gemma
        }
    }

    suspend fun installOneZipFromUri(
        uri: Uri,
        onProgress: (String, Int) -> Unit = { _, _ -> }
    ): ZipExtractionReport = withContext(Dispatchers.IO) {
        onProgress("Opening Meta models zip…", 2)
        val zipBytes = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        }.getOrNull() ?: -1L
        val input = context.contentResolver.openInputStream(uri)
            ?: return@withContext ZipExtractionReport(0, 5, listOf("✗ Could not open the zip file"))
        val report = input.use { stream ->
            metaStore.installZip(
                input = stream,
                zipBytes = zipBytes,
                freeBytes = modelsDir.usableSpace,
                onProgress = onProgress,
                onInstalled = { component, file ->
                    val relative = "meta/${file.name}"
                    when (component) {
                        MetaModelStore.Component.LLAMA -> saveModelFileName(ModelType.LLAMA3B, relative)
                        MetaModelStore.Component.DETR -> saveModelFileName(ModelType.DETR, relative)
                        MetaModelStore.Component.CLIP_VISION -> saveModelFileName(ModelType.CLIP, relative)
                        MetaModelStore.Component.DINOV2 -> saveModelFileName(ModelType.DINOv2, relative)
                        MetaModelStore.Component.SAM_ENCODER -> saveModelFileName(ModelType.SAM31, relative)
                        else -> Unit
                    }
                }
            )
        }
        if (report.error == null) {
            removeLegacyExtractionLeftovers()
            setUseMetaStack(true)
            setLlamaDisconnected(false)
        }
        val groups = metaStatus()
        val logs = buildList {
            report.error?.let { add("✗ $it") }
            addAll(report.logs)
            add("")
            groups.forEach { add("${if (it.ready) "✅" else "❌"} ${it.name}: ${it.detail}") }
        }
        ZipExtractionReport(
            successCount = groups.count { it.ready },
            totalCount = groups.size,
            logs = logs,
            installedStatus = ModelType.values().associateWith { isModelInstalled(it) }
        )
    }

    /**
     * The old installer left a second copy of every model under its zip path. Those files are
     * removed (only model-type files that no setting points to; .litertlm bundles are kept).
     */
    private fun removeLegacyExtractionLeftovers() {
        val referenced = (ModelType.values().mapNotNull { getModelPath(it) } + getModelFile(getSelectedModelFileName()).absolutePath)
            .map { File(it).absolutePath }.toSet()
        val modelExt = Regex("(?i).*\\.(gguf|onnx|onnx\\.data|safetensors|pt|pth|bin|pdmodel|pdiparams|ckpt)$")
        var freed = 0L
        modelsDir.listFiles()?.forEach { top ->
            if (top.name == "meta" || top.name.endsWith(".litertlm", true)) return@forEach
            top.walkBottomUp().forEach { f ->
                if (f.isFile && f.absolutePath !in referenced && modelExt.matches(f.name)) {
                    freed += f.length(); f.delete()
                } else if (f.isDirectory && f != modelsDir && f.listFiles().isNullOrEmpty()) {
                    f.delete()
                }
            }
        }
        if (freed > 0) Log.i(TAG, "META_LEGACY_CLEANUP freedMb=${freed / 1_048_576}")
    }

    // ── Persistence: remember which Gemma .litertlm the user last installed ──

    /** Returns the filename saved from the last successful install, or "" if none. */
    fun getSelectedModelFileName(): String = prefs.getString(KEY_MODEL_FILE, "") ?: ""

    /** Saves the chosen filename so it survives app restart. */
    fun saveSelectedModelFileName(fileName: String) {
        prefs.edit().putString(KEY_MODEL_FILE, fileName).apply()
        Log.d(TAG, "Selected model saved: $fileName")
    }

    fun clearSelectedModel() {
        prefs.edit().remove(KEY_MODEL_FILE).apply()
    }

    fun getModelFile(fileName: String): File = File(modelsDir, fileName)
    fun getModelPath(fileName: String): String = getModelFile(fileName).absolutePath

    // ── Single-file LiteRT-LM check (legacy signature preserved) ─────────────

    fun isModelInstalled(modelFileName: String): Boolean {
        val modelFile = getModelFile(modelFileName)
        val installed = modelFile.exists() && modelFile.length() > 500_000_000L // >500MB
        Log.d(TAG, "Model installed: $installed (${modelFile.length() / 1024 / 1024}MB)")
        return installed
    }

    fun isModelInstalled(modelFileName: String, projectorFileName: String): Boolean =
        isModelInstalled(modelFileName)

    fun listInstalledModels(): List<String> =
        modelsDir.listFiles()?.map { it.name } ?: emptyList()

    fun recoverSingleInstalledModel(): String? {
        val candidates = modelsDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".litertlm", ignoreCase = true) && it.length() > 200_000_000L }
            .orEmpty()
        if (candidates.size != 1) return null
        val recovered = candidates.single().name
        saveSelectedModelFileName(recovered)
        Log.i(TAG, "Recovered installed model selection: $recovered")
        return recovered
    }

    fun deleteModel(fileName: String): Boolean = getModelFile(fileName).delete()

    fun getModelSizeMB(fileName: String): Long =
        getModelFile(fileName).length() / 1024 / 1024

    // ── Copy from file picker URI ─────────────────────────────────────────────

    /** Human-readable reason of the last failed installFromUri, or "". */
    @Volatile var lastInstallError: String = ""

    suspend fun installFromUri(
        uri: Uri,
        type: ModelType,
        originalFilename: String = "model_file",
        onProgress: (Int) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanName = if (originalFilename.startsWith("${type.name}_", ignoreCase = true)) {
            originalFilename
        } else {
            "${type.name}_$originalFilename"
        }
        val needsOnnx = type == ModelType.DINOv2 || type == ModelType.SAM31 || type == ModelType.DETR || type == ModelType.CLIP
        if (needsOnnx && !originalFilename.endsWith(".onnx", ignoreCase = true)) {
            Log.e(TAG, "✗ $type must be an ONNX file (convert with tools/export_meta_models.py): $originalFilename")
            return@withContext false
        }
        if (type == ModelType.LLAMA3B && !originalFilename.endsWith(".gguf", ignoreCase = true)) {
            Log.e(TAG, "✗ Llama must be a .gguf file: $originalFilename")
            return@withContext false
        }
        val minSize = getMinSizeBytes(type)
        val partFile = File(modelsDir, "$cleanName.part")
        try {
            // Llama goes where the app loads it from (models/meta/<original name>.gguf), so a newly
            // attached file always replaces the one in use.
            val llamaName = originalFilename.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val targetFile = if (type == ModelType.LLAMA3B) File(metaStore.metaDir, llamaName) else getModelFile(cleanName)
            val totalBytes = context.contentResolver.openFileDescriptor(uri, "r")
                ?.use { it.statSize } ?: -1L

            if (totalBytes > 0 && modelsDir.freeSpace < totalBytes + 50_000_000L) {
                Log.e(TAG, "✗ NOT ENOUGH SPACE: need ${(totalBytes + 50_000_000L) / 1024 / 1024}MB")
                return@withContext false
            }

            if (targetFile.exists()) targetFile.delete()
            if (partFile.exists()) partFile.delete()

            val input = context.contentResolver.openInputStream(uri)
                ?: run { Log.e(TAG, "✗ Cannot open source URI"); return@withContext false }

            var totalRead = 0L
            FileOutputStream(partFile).use { out ->
                val buffer = ByteArray(8 * 1024 * 1024)
                var n: Int
                input.use { inp ->
                    while (inp.read(buffer).also { n = it } != -1) {
                        out.write(buffer, 0, n)
                        totalRead += n
                        if (totalBytes > 0) onProgress((totalRead * 100 / totalBytes).toInt())
                    }
                }
                out.flush(); out.fd.sync()
            }

            val copied = partFile.length()
            if (totalBytes > 0 && copied != totalBytes) {
                Log.e(TAG, "✗ SIZE MISMATCH src=$totalBytes copied=$copied — PARTIAL")
                partFile.delete(); return@withContext false
            }
            if (copied < minSize) {
                lastInstallError = "File too small (${copied / 1024 / 1024} MB)."
                Log.e(TAG, "✗ TOO SMALL (${copied / 1024 / 1024}MB) — min $minSize for $type")
                partFile.delete(); return@withContext false
            }
            if (type == ModelType.LLAMA3B && !LlamaFileCheck.isComplete(partFile)) {
                Log.e(TAG, "✗ Llama GGUF is incomplete or damaged: ${LlamaFileCheck.lastReason}")
                lastInstallError = "This Llama file is incomplete or damaged (${LlamaFileCheck.lastReason}). Copy it again."
                partFile.delete(); return@withContext false
            }
            if (type == ModelType.LLAMA3B) {
                // Remove every other Llama file so the damaged/old one can never be picked again.
                metaStore.metaDir.listFiles()?.filter { it.name.endsWith(".gguf", true) && it.name != targetFile.name }?.forEach { it.delete() }
                modelsDir.listFiles()?.filter { it.name.startsWith("LLAMA3B_") && it.name.endsWith(".gguf", true) }?.forEach { it.delete() }
            }
            if (!partFile.renameTo(targetFile)) {
                Log.e(TAG, "✗ Rename failed"); partFile.delete(); return@withContext false
            }
            lastInstallError = ""

            onProgress(100)
            saveModelFileName(type, cleanName)
            if (type == ModelType.GEMMA_E2B) {
                saveSelectedModelFileName(cleanName)
            }
            Log.d(TAG, "✓ COPY VERIFIED for $type: ${targetFile.length() / 1024 / 1024}MB → $cleanName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "✗ Copy failed for $type: ${e.javaClass.simpleName}: ${e.message}", e)
            partFile.delete()
            false
        }
    }

    suspend fun copyFromUri(
        uri: Uri,
        targetFileName: String,
        onProgress: (Int) -> Unit
    ): Boolean = installFromUri(uri, ModelType.GEMMA_E2B, targetFileName, onProgress)

    /** Diagnostic listing of the models directory. */
    fun diagnose(expectedFileName: String): String = buildString {
        appendLine("Dir: ${modelsDir.absolutePath} exists=${modelsDir.exists()}")
        appendLine("Free: ${modelsDir.freeSpace / 1024 / 1024}MB")
        val files = modelsDir.listFiles()
        if (files.isNullOrEmpty()) appendLine("EMPTY — no model installed")
        else files.forEach { appendLine("  ${it.name}  ${it.length() / 1024 / 1024}MB") }
        val exp = getModelFile(expectedFileName)
        appendLine("Expected: $expectedFileName present=${exp.exists()} size=${exp.length() / 1024 / 1024}MB")
        appendLine("Valid: ${exp.exists() && exp.length() > 500_000_000L}")
    }

    // ── Download from Google Drive URL ────────────────────────────────────────

    suspend fun downloadFromDrive(
        driveUrl: String,
        targetFileName: String,
        onProgress: (Int) -> Unit,
        onError: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val downloadUrl = convertDriveUrl(driveUrl)
            if (downloadUrl == null) {
                onError("Invalid Google Drive URL. Use a sharing link.")
                return@withContext false
            }

            val targetFile = getModelFile(targetFileName)
            val tempFile   = File(modelsDir, "$targetFileName.tmp")

            Log.d(TAG, "Downloading: $downloadUrl")
            val connection = URL(downloadUrl).openConnection() as HttpURLConnection
            connection.connectTimeout        = 30_000
            connection.readTimeout           = 60_000
            connection.instanceFollowRedirects = true

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                responseCode == HttpURLConnection.HTTP_MOVED_PERM) {
                val redirectUrl = connection.getHeaderField("Location")
                connection.disconnect()
                return@withContext downloadFromDrive(redirectUrl, targetFileName, onProgress, onError)
            }

            val totalBytes = connection.contentLengthLong
            Log.d(TAG, "File size: ${totalBytes / 1024 / 1024}MB")

            FileOutputStream(tempFile).use { out ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(8 * 1024 * 1024)
                    var bytesRead: Int
                    var totalRead = 0L
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        out.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (totalBytes > 0)
                            onProgress((totalRead * 100 / totalBytes).toInt())
                    }
                }
            }
            connection.disconnect()

            if (tempFile.length() < 200_000_000L) {
                tempFile.delete()
                onError("Downloaded file too small. Check the Drive link is correct and publicly shared.")
                return@withContext false
            }

            if (!tempFile.renameTo(targetFile)) {
                tempFile.delete()
                onError("Download completed but the model could not be installed.")
                return@withContext false
            }
            saveSelectedModelFileName(targetFileName)
            onProgress(100)
            Log.d(TAG, "Download complete: ${targetFile.length() / 1024 / 1024}MB")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}")
            onError("Download failed: ${e.message}")
            false
        }
    }

    private fun convertDriveUrl(url: String): String? = try {
        when {
            url.contains("/file/d/") -> {
                val fileId = url.substringAfter("/file/d/").substringBefore("/")
                "https://drive.google.com/uc?export=download&id=$fileId"
            }
            url.contains("open?id=") -> {
                val fileId = url.substringAfter("open?id=").substringBefore("&")
                "https://drive.google.com/uc?export=download&id=$fileId"
            }
            url.contains("uc?id=") || url.contains("uc?export=") -> url
            url.startsWith("http") -> url
            else -> null
        }
    } catch (e: Exception) { null }

    fun getAvailableStorageMB(): Long {
        val stat = android.os.StatFs(modelsDir.absolutePath)
        return stat.availableBlocksLong * stat.blockSizeLong / 1024 / 1024
    }
}
