package com.example.arfas

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Evaluator-proof first run: find the Llama model, Whisper model and guide DB in
 * Downloads (or the bundled asset) and install them with progress, then load the
 * sample village. Completion is one flag in its own prefs file; clearing only that
 * flag replays the wizard without touching app data.
 */
object ArogyaSetup {
    private const val PREFS = "arogya_setup"
    private const val TAG = "ArogyaDemo"

    enum class Step { LLAMA, WHISPER, GUIDE, SAMPLE }

    data class Scan(
        val llamaReady: Boolean, val whisperReady: Boolean,
        val guideReady: Boolean, val guideV2: Boolean, val sampleLoaded: Boolean,
        val found: Map<ArogyaSetupMatch.Kind, Pair<Uri, String>>
    )

    fun isDone(c: Context): Boolean = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("done", false)
    fun markDone(c: Context) { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("done", true).apply() }

    /** Setup wizard status: models (Llama installed), guide, Meta key. Call off the main thread. */
    fun status(c: Context, guideReady: Boolean): ArogyaSetupPlan.Status =
        ArogyaSetupPlan.Status(llamaInstalled(c), guideReady, runCatching { GroqKeyStore(c.applicationContext).metaKey().isNotBlank() }.getOrDefault(false))

    fun llamaInstalled(c: Context): Boolean {
        val meta = File(File(c.filesDir, "models"), "meta")
        if (meta.listFiles()?.any { it.isFile && it.name.endsWith(".gguf", true) && it.length() > 0 } == true) return true
        return File(c.filesDir, "models").listFiles()?.any { it.isFile && it.name.startsWith("LLAMA3B_", true) && it.name.endsWith(".gguf", true) } == true
    }

    /** Offline speech-to-text is usable when Whisper model files are on the phone (or bundled). */
    fun whisperInstalled(c: Context): Boolean {
        if (File(c.filesDir, "whisper").listFiles()?.any { it.isFile && it.length() > 0 } == true) return true
        return runCatching { c.assets.open("voice/stt/whisper/model.bin").close(); true }.getOrDefault(false)
    }

    suspend fun scan(c: Context, guideReady: Boolean, guideV2: Boolean, sampleLoaded: Boolean): Scan = withContext(Dispatchers.IO) {
        Scan(llamaInstalled(c), whisperInstalled(c), guideReady, guideV2, sampleLoaded, findInDownloads(c))
    }

    /** Downloads files the wizard can install, by kind (same MediaStore pattern as the guide update). */
    fun findInDownloads(c: Context): Map<ArogyaSetupMatch.Kind, Pair<Uri, String>> = runCatching {
        val out = LinkedHashMap<ArogyaSetupMatch.Kind, Pair<Uri, String>>()
        c.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { cur ->
            val idCol = cur.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameCol = cur.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            while (cur.moveToNext()) {
                val name = cur.getString(nameCol) ?: continue
                val kind = ArogyaSetupMatch.classify(name) ?: continue
                if (!out.containsKey(kind)) out[kind] = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cur.getLong(idCol)) to name
            }
        }
        out
    }.getOrDefault(emptyMap())

    suspend fun installLlama(c: Context, uri: Uri, name: String, onProgress: (Int) -> Unit): Boolean =
        ModelFileManager(c.applicationContext).installFromUri(uri, ModelFileManager.ModelType.LLAMA3B, name, onProgress)

    /** Whisper models copy straight into filesDir/whisper with progress. */
    suspend fun installWhisper(c: Context, uri: Uri, name: String, onProgress: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(c.filesDir, "whisper").also { it.mkdirs() }
            val target = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
            val total = c.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            c.contentResolver.openInputStream(uri)?.use { inp ->
                target.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20); var done = 0L; var n = inp.read(buf)
                    while (n > 0) { out.write(buf, 0, n); done += n; if (total > 0) onProgress(((done * 100) / total).toInt().coerceIn(0, 100)); n = inp.read(buf) }
                }
            } ?: error("cannot open $name")
            onProgress(100)
            Log.i(TAG, "AROGYA_SETUP installed whisper ${target.name} ${target.length()} bytes")
            true
        }.getOrElse { Log.w(TAG, "AROGYA_SETUP whisper failed ${it.message?.take(160)}"); false }
    }

    /**
     * Bundled guide v2 fallback: copy the asset into the app's own Downloads entry,
     * then install through the normal path. Returns the uri to install, or null.
     */
    suspend fun bundledGuideV2Uri(c: Context): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, ArogyaSetupMatch.GUIDE_V2_NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
            }
            val dest = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("no destination")
            c.assets.open(ArogyaSetupMatch.ASSET_GUIDE_V2).use { inp ->
                c.contentResolver.openOutputStream(dest, "w")?.use { inp.copyTo(it) } ?: error("cannot write")
            }
            Log.i(TAG, "AROGYA_SETUP bundled guide v2 copied to Downloads")
            dest
        }.getOrNull()
    }
}
