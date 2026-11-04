package com.example.arfas

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Where the Meta models live and how the "Meta models zip" is installed.
 *
 * Install rules (all from the audit):
 *  - the zip is streamed entry by entry straight to its final file: no temp copy of the zip, no
 *    second copy of each model (the old installer needed ~3x the zip size free)
 *  - entry names are never used as paths: only the file name is classified, so a "../" entry
 *    cannot write outside the models folder (Zip Slip)
 *  - each model goes to a fixed name in files/models/meta/; ONNX external-data files keep their
 *    original name because the .onnx file refers to them by that name
 *  - PyTorch/safetensors checkpoints cannot run on a phone: they are reported as "needs ONNX
 *    conversion" (tools/export_meta_models.py) and not copied, which also saves space
 */
class MetaModelStore(private val modelsDir: File) {

    enum class Component(val label: String) {
        LLAMA("Llama 3.2 Instruct (GGUF) — reasoning and answers"),
        DETR("DETR ResNet-50 (ONNX) — object detection"),
        CLIP_VISION("CLIP ViT-B/32 image encoder (ONNX)"),
        CLIP_TEXT("CLIP ViT-B/32 text encoder (ONNX)"),
        DINOV2("DINOv2 (ONNX) — visual memory"),
        SAM_ENCODER("SAM / MobileSAM image encoder (ONNX)"),
        SAM_DECODER("SAM / MobileSAM mask decoder (ONNX)")
    }

    /** One line per model group for the install report and Settings. */
    data class GroupStatus(val name: String, val ready: Boolean, val detail: String)

    data class InstallReport(
        val groups: List<GroupStatus>,
        val logs: List<String>,
        val error: String? = null
    ) {
        val readyCount: Int get() = groups.count { it.ready }
        val totalCount: Int get() = groups.size
    }

    val metaDir: File = File(modelsDir, "meta").also { it.mkdirs() }

    private fun canonical(component: Component): File = File(
        metaDir,
        when (component) {
            Component.LLAMA -> "llama.gguf"  // not used: see file()
            Component.DETR -> "detr.onnx"
            Component.CLIP_VISION -> "clip_vision.onnx"
            Component.CLIP_TEXT -> "clip_text.onnx"
            Component.DINOV2 -> "dinov2.onnx"
            Component.SAM_ENCODER -> "sam_encoder.onnx"
            Component.SAM_DECODER -> "sam_decoder.onnx"
        }
    )

    /** The installed file of [component], or null. Legacy locations (old installer) count too. */
    fun file(component: Component, legacy: File? = null): File? {
        if (component == Component.LLAMA) {
            // The GGUF keeps its own name (shown to judges: "Llama-3.2-3B-Instruct-Q4_K_M.gguf").
            metaDir.listFiles()?.filter { it.isFile && it.name.endsWith(".gguf", true) && it.length() > 0 }
                ?.maxByOrNull { it.length() }?.let { return it }
            return legacy?.takeIf { it.isFile && it.length() > 0 && legacyMatches(component, it) }
        }
        val f = canonical(component)
        if (f.isFile && f.length() > 0) return f
        return legacy?.takeIf { it.isFile && it.length() > 0 && legacyMatches(component, it) }
    }

    private fun legacyMatches(component: Component, f: File): Boolean = when (component) {
        Component.LLAMA -> f.name.endsWith(".gguf", true)
        else -> f.name.endsWith(".onnx", true)
    }

    data class Classification(val component: Component?, val externalData: Boolean, val note: String?)

    /**
     * Decides what a zip entry is. Keywords are looked for in the whole entry path (Hugging Face
     * layouts put "vision_model.onnx" in a "clip-vit-base-patch32/onnx/" folder), but the path is
     * never used to write files.
     */
    fun classify(fileName: String, entryPath: String = fileName): Classification {
        val n = fileName.lowercase()
        val path = entryPath.lowercase().replace('\\', '/')
        if (n.endsWith(".onnx.data") || n.endsWith(".onnx_data") || n.endsWith(".onnx.data0") || n.endsWith("_data.bin") && n.contains("onnx")) {
            return Classification(null, externalData = true, note = null)
        }
        fun has(vararg k: String) = k.any { path.contains(it) }
        val isSam = n.startsWith("sam") || has("_sam", "-sam", "/sam", "mobilesam", "mobile_sam", "sam2", "segment")
        val isDino = has("dinov2", "dino")
        val isClip = has("clip")
        val isDetr = has("detr")
        if (n.endsWith(".gguf")) return Classification(Component.LLAMA, false, null)
        if (n.endsWith(".onnx")) {
            return when {
                isDetr -> Classification(Component.DETR, false, null)
                isClip && (n.contains("text") || n.contains("txt")) -> Classification(Component.CLIP_TEXT, false, null)
                isClip -> Classification(Component.CLIP_VISION, false, null)  // vision/visual/image or unnamed; probed at runtime
                isDino -> Classification(Component.DINOV2, false, null)
                isSam && (n.contains("decoder") || n.contains("mask")) -> Classification(Component.SAM_DECODER, false, null)
                isSam -> Classification(Component.SAM_ENCODER, false, null)
                else -> Classification(null, false, "skipped (unknown ONNX model): $entryPath")
            }
        }
        val checkpoint = n.endsWith(".safetensors") || n.endsWith(".pt") || n.endsWith(".pth") || n.endsWith(".bin") || n.endsWith(".ckpt")
        if (checkpoint && (isDino || isSam || isDetr || isClip)) {
            return Classification(null, false, "needs ONNX conversion (PyTorch weights cannot run on the phone): $fileName — run tools/export_meta_models.py")
        }
        return Classification(null, false, null)
    }

    /**
     * Streams [input] (a zip) into the models folder. [freeBytes] is checked against
     * [zipBytes] (an uncompressed model zip is about its own size).
     */
    fun installZip(
        input: InputStream,
        zipBytes: Long,
        freeBytes: Long,
        onProgress: (String, Int) -> Unit,
        onInstalled: (Component, File) -> Unit
    ): InstallReport {
        val logs = ArrayList<String>()
        if (zipBytes > 0 && freeBytes in 0 until zipBytes + 200_000_000L) {
            val need = (zipBytes + 200_000_000L) / 1_048_576
            return InstallReport(status(), logs, "Not enough free space: need about $need MB, have ${freeBytes / 1_048_576} MB")
        }
        var written = 0L
        var entries = 0
        Log.i(TAG, "META_ZIP_OPENED bytes=$zipBytes freeMb=${freeBytes / 1_048_576}")
        try {
            ZipInputStream(input.buffered(1 shl 20)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        entries++
                        val entryPath = entry.name.replace('\\', '/')
                        val base = entryPath.substringAfterLast('/')
                        val c = classify(base, entryPath)
                        val target: File? = when {
                            c.component == Component.LLAMA -> File(metaDir, safeName(base))
                            c.component != null -> canonical(c.component)
                            c.externalData -> File(metaDir, safeName(base))
                            else -> null
                        }
                        c.note?.let { logs += "⚠ $it" }
                        if (target != null && entry.size > 0 && target.isFile && target.length() == entry.size &&
                            (c.component != Component.LLAMA || LlamaFileCheck.isComplete(target))) {
                            // Already installed from an earlier (interrupted) run: skip, so a retry
                            // goes straight to the file that did not finish.
                            written += entry.size
                            logs += "✓ $base already installed (${target.length() / 1_048_576} MB)"
                            c.component?.let { onInstalled(it, target) }
                        } else if (target != null) {
                            val part = File(metaDir, target.name + ".part")
                            onProgress("Installing $base", progress(written, zipBytes))
                            FileOutputStream(part).use { out ->
                                val buf = ByteArray(1 shl 20)
                                var entryBytes = 0L
                                var nextReport = 32L shl 20
                                while (true) {
                                    val n = zis.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    written += n
                                    entryBytes += n
                                    if (entryBytes >= nextReport) {
                                        // Large files (Llama is ~2 GB) take minutes: keep the progress moving.
                                        onProgress("Installing $base — ${entryBytes / 1_048_576} MB copied", progress(written, zipBytes))
                                        nextReport += 32L shl 20
                                    }
                                }
                                out.fd.sync()
                            }
                            if (c.component == Component.LLAMA && !LlamaFileCheck.isComplete(part)) {
                                logs += "✗ $base in the zip is incomplete (${LlamaFileCheck.lastReason})"
                                part.delete()
                                zis.closeEntry(); entry = zis.nextEntry
                                continue
                            }
                            if (target.exists()) target.delete()
                            if (!part.renameTo(target)) {
                                part.delete()
                                logs += "✗ could not install $base"
                            } else {
                                logs += "✓ $base → ${target.name} (${target.length() / 1_048_576} MB)"
                                if (c.component == Component.LLAMA) {
                                    // Only one Llama model is kept: a new one replaces the old one.
                                    metaDir.listFiles()?.filter { it.name.endsWith(".gguf", true) && it != target }?.forEach { it.delete() }
                                }
                                c.component?.let { onInstalled(it, target) }
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "META_ZIP_INSTALL_FAILED ${t.javaClass.simpleName}: ${t.message}", t)
            metaDir.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
            val why = "Install stopped after ${written / 1_048_576} MB: ${t.javaClass.simpleName}: ${t.message}" +
                (if (t is java.io.IOException && (t.message ?: "").contains("ENOSPC|No space".toRegex())) " — phone storage is full" else "")
            runCatching { File(metaDir, "last_install.txt").writeText((logs + "✗ $why").joinToString("\n")) }
            return InstallReport(status(), logs, why)
        }
        if (entries == 0) {
            // An HTML error page saved as ".zip" yields zero entries: never treat it as a valid archive.
            Log.e(TAG, "META_ZIP_EMPTY no entries — not a valid models zip")
            return InstallReport(status(), logs, "The file is not a valid models zip (0 entries). An error page may have been saved instead of the zip — download again or pick the correct file.")
        }
        Log.i(TAG, "META_ZIP_DONE entries=$entries writtenMb=${written / 1_048_576}")
        onProgress("Done", 100)
        runCatching { File(metaDir, "last_install.txt").writeText(logs.joinToString("\n")) }
        return InstallReport(status(), logs)
    }

    /** Ready/missing per model group (Llama, DETR, CLIP, DINOv2, SAM). */
    fun status(legacyLlama: File? = null, legacyDetr: File? = null, legacyClip: File? = null): List<GroupStatus> {
        fun mb(f: File?) = f?.let { "${it.length() / 1_048_576} MB" } ?: ""
        val llama = file(Component.LLAMA, legacyLlama)
        val detr = file(Component.DETR, legacyDetr)
        val clipV = file(Component.CLIP_VISION, legacyClip)
        val clipT = file(Component.CLIP_TEXT)
        val dino = file(Component.DINOV2)
        val samE = file(Component.SAM_ENCODER)
        val samD = file(Component.SAM_DECODER)
        return listOf(
            GroupStatus("Llama 3.2", llama != null, if (llama != null) "${llama.name} ${mb(llama)}" else "missing (required)"),
            GroupStatus("DETR", detr != null, if (detr != null) mb(detr) else "missing (object detection off)"),
            GroupStatus("CLIP", clipV != null && clipT != null, when {
                clipV != null && clipT != null -> "image ${mb(clipV)} + text ${mb(clipT)}"
                clipV != null -> "image encoder only (zero-shot labels need the text encoder too)"
                else -> "missing"
            }),
            GroupStatus("DINOv2", dino != null, if (dino != null) mb(dino) else "missing or not converted to ONNX (visual memory off)"),
            GroupStatus("SAM", samE != null && samD != null, when {
                samE != null && samD != null -> "encoder ${mb(samE)} + decoder ${mb(samD)}"
                samE != null || samD != null -> "needs both encoder and decoder"
                else -> "missing or not converted to ONNX (box refinement off)"
            })
        )
    }

    private fun safeName(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun progress(done: Long, total: Long): Int =
        if (total <= 0) 50 else (done * 100 / total).toInt().coerceIn(1, 99)

    companion object {
        private const val TAG = "MetaModelStore"
    }
}
