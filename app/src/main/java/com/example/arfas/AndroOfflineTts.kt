package com.example.arfas

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * FixSight package adapter of AndroLLM's SherpaOnnxOfflineTtsEngine.
 * Includes the upstream text-normalization pipeline and optional lexicon/OOV
 * spelling. The espeak data directory is copied to a real filesystem path before
 * native initialization because sherpa-onnx cannot consume it directly from APK assets.
 */
class AndroOfflineTts(private val context: Context) {
    private var tts: OfflineTts? = null
    private val textNormalization = TextNormalizationEngine()
    private var lexicon: Set<String>? = null
    private val providerPriority = listOf("nnapi", "xnnpack", "cpu")

    val initialized: Boolean get() = tts != null
    val sampleRate: Int get() = tts?.sampleRate() ?: 22050

    @Synchronized
    fun ensureInitialized(): Boolean {
        if (tts != null) return true
        if (!hasAsset(AndroVoiceModels.TTS_MODEL) || !hasAsset(AndroVoiceModels.TTS_TOKENS)) {
            Log.w(TAG, "TTS assets missing")
            return false
        }
        val dataDir = copyDataDirToFilesystem() ?: return false
        textNormalization.onSettingsChanged(FixSightVoiceSettings.load(context))
        for (provider in providerPriority) {
            if (createTts(provider, dataDir)) {
                lexicon = loadLexiconIfPresent()
                Log.i(TAG, "initialized provider=$provider lexicon=${lexicon?.size ?: 0}")
                return true
            }
        }
        return false
    }

    private fun createTts(provider: String, dataDir: String): Boolean = runCatching {
        val vits = OfflineTtsVitsModelConfig(
            model = AndroVoiceModels.TTS_MODEL,
            lexicon = if (hasAsset(AndroVoiceModels.TTS_LEXICON)) AndroVoiceModels.TTS_LEXICON else "",
            tokens = AndroVoiceModels.TTS_TOKENS,
            dataDir = dataDir,
            noiseScale = 0.667f,
            noiseScaleW = 0.8f,
            lengthScale = 1.0f
        )
        val ttsModel = OfflineTtsModelConfig(
            vits = vits,
            numThreads = if (provider == "nnapi") 2 else 4,
            debug = false,
            provider = provider
        )
        val config = OfflineTtsConfig(model = ttsModel).apply { silenceScale = 0.8f }
        val candidate = OfflineTts(assetManager = context.assets, config = config)
        val probe = candidate.generate("test", 0, 1.0f)
        val probePeak = peakAbs(probe.samples)
        if (probePeak < 0.01f) {
            Log.w(TAG, "provider $provider produced silent probe — rejecting")
            runCatching { candidate.release() }
            return@runCatching false
        }
        tts = candidate
        true
    }.onFailure { Log.e(TAG, "TTS init failed provider=$provider", it) }.getOrDefault(false)

    @Synchronized
    fun synthesize(text: String, speed: Float): FloatArray? {
        if (text.isBlank()) return null
        if (!ensureInitialized()) return null
        val t = tts ?: return null
        return runCatching {
            textNormalization.onSettingsChanged(FixSightVoiceSettings.load(context))
            val normalized = textNormalization.normalize(text).text.trim()
            if (normalized.isBlank()) return null
            val covered = lexicon?.let { EnglishTtsNormalizer.spellOutOfLexicon(normalized, it) } ?: normalized
            Log.d(TAG, "speaking '${covered.take(80)}'")
            val audio = t.generate(covered, 0, speed.coerceIn(0.5f, 2.0f))
            audio.samples
        }.onFailure { Log.e(TAG, "TTS synth failed", it) }.getOrNull()
    }

    @Synchronized
    fun release() {
        runCatching { tts?.release() }
        tts = null
        lexicon = null
    }

    private fun peakAbs(samples: FloatArray): Float {
        var peak = 0f
        for (s in samples) {
            val a = if (s < 0f) -s else s
            if (a > peak) peak = a
        }
        return peak
    }

    private fun hasAsset(path: String): Boolean =
        runCatching { context.assets.open(path).close(); true }.getOrDefault(false)

    private fun loadLexiconIfPresent(): Set<String>? {
        if (!hasAsset(AndroVoiceModels.TTS_LEXICON)) {
            Log.i(TAG, "TTS_LEXICON_NOT_BUNDLED oovSpelling=false")
            return null
        }
        return runCatching {
            context.assets.open(AndroVoiceModels.TTS_LEXICON)
                .bufferedReader()
                .useLines { lines ->
                    lines.map { it.substringBefore(' ').lowercase() }
                        .filter { it.isNotBlank() }
                        .toHashSet()
                }
        }.onFailure { Log.w(TAG, "TTS lexicon read failed", it) }.getOrNull()
    }

    private fun copyDataDirToFilesystem(): String? {
        val root = context.getExternalFilesDir(null) ?: return null
        val destination = File(root, AndroVoiceModels.TTS_DATA_DIR)
        return runCatching {
            copyAssetTree(AndroVoiceModels.TTS_DATA_DIR, destination)
            val required = listOf("phontab", "phonindex", "phondata", "intonations")
            val missing = required.filterNot { File(destination, it).isFile }
            if (missing.isNotEmpty()) throw IOException("Incomplete espeak data: ${missing.joinToString()}")
            destination.absolutePath
        }.onFailure { Log.e(TAG, "espeak data copy failed", it) }.getOrNull()
    }

    private fun copyAssetTree(assetPath: String, destination: File) {
        val children = context.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                FileOutputStream(destination, false).use { output -> input.copyTo(output, 64 * 1024) }
            }
            return
        }
        if (!destination.exists() && !destination.mkdirs()) throw IOException("Cannot create ${destination.absolutePath}")
        children.forEach { child -> copyAssetTree("$assetPath/$child", File(destination, child)) }
    }

    companion object { private const val TAG = "AndroOfflineTts" }
}
