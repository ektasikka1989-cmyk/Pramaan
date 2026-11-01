package com.example.arfas

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * Fully local streaming Zipformer/Transducer recognizer using sherpa-onnx 1.13.4.
 */
class AndroStreamingRecognizer(private val context: Context) {

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null

    val initialized: Boolean
        get() = recognizer != null

    private val inferenceProvider: String
        get() = runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            val get = clazz.getMethod("get", String::class.java)
            if (get.invoke(null, "debug.androllm.provider") == "nnapi") {
                "nnapi"
            } else {
                "cpu"
            }
        }.getOrDefault("cpu")

    @Synchronized
    fun ensureInitialized(): Boolean {
        if (recognizer != null) return true

        val required = listOf(
            AndroVoiceModels.ASR_ENCODER,
            AndroVoiceModels.ASR_DECODER,
            AndroVoiceModels.ASR_JOINER,
            AndroVoiceModels.ASR_TOKENS
        )

        if (required.any { !assetExists(it) }) {
            Log.e(TAG, "ANDRO_ASR_ASSETS_MISSING")
            return false
        }

        return runCatching {
            val featureConfig = FeatureConfig(
                sampleRate = AndroVoiceModels.SAMPLE_RATE,
                featureDim = AndroVoiceModels.FEATURE_DIM
            )

            val transducerConfig = OnlineTransducerModelConfig(
                encoder = AndroVoiceModels.ASR_ENCODER,
                decoder = AndroVoiceModels.ASR_DECODER,
                joiner = AndroVoiceModels.ASR_JOINER
            )

            val modelConfig = OnlineModelConfig(
                transducer = transducerConfig,
                tokens = AndroVoiceModels.ASR_TOKENS,
                numThreads = 2,
                debug = false,
                provider = inferenceProvider,
                modelType = "zipformer2"
            )

            val endpointConfig = EndpointConfig(
                rule1 = EndpointRule(
                    mustContainNonSilence = true,
                    minTrailingSilence = 2.0f,
                    minUtteranceLength = 0.3f
                ),
                rule2 = EndpointRule(
                    mustContainNonSilence = true,
                    minTrailingSilence = 1.0f,
                    minUtteranceLength = 3.0f
                ),
                rule3 = EndpointRule(
                    mustContainNonSilence = false,
                    minTrailingSilence = 2.0f,
                    minUtteranceLength = 5.0f
                )
            )

            val config = OnlineRecognizerConfig(
                featConfig = featureConfig,
                modelConfig = modelConfig,
                endpointConfig = endpointConfig,
                enableEndpoint = true,
                decodingMethod = "greedy_search",
                maxActivePaths = 4
            )

            recognizer = OnlineRecognizer(
                assetManager = context.assets,
                config = config
            )

            Log.i(
                TAG,
                "ANDRO_ASR_READY sampleRate=${AndroVoiceModels.SAMPLE_RATE} endpoint=true"
            )
            true
        }.onFailure {
            Log.e(TAG, "ANDRO_ASR_INIT_FAILED", it)
        }.getOrDefault(false)
    }

    @Synchronized
    fun updateSilenceTimeout(seconds: Float) {
        recognizer?.config?.endpointConfig?.rule1?.minTrailingSilence = seconds
        recognizer?.config?.endpointConfig?.rule2?.minTrailingSilence = maxOf(seconds, 0.8f)
        recognizer?.config?.endpointConfig?.rule3?.minTrailingSilence = maxOf(seconds, 1.2f)
    }

    @Synchronized
    fun startSession() {
        val r = recognizer ?: return
        runCatching { stream?.release() }
        stream = r.createStream()
    }

    @Synchronized
    fun feed(samples: FloatArray): String {
        val r = recognizer ?: return ""
        val s = stream ?: return ""
        if (samples.isEmpty()) return r.getResult(s).text

        return runCatching {
            s.acceptWaveform(
                samples = samples,
                sampleRate = AndroVoiceModels.SAMPLE_RATE
            )

            while (r.isReady(s)) {
                r.decode(s)
            }

            r.getResult(s).text
        }.onFailure {
            Log.e(TAG, "ANDRO_ASR_FEED_FAILED", it)
        }.getOrDefault("")
    }

    @Synchronized
    fun endpoint(): Boolean {
        val r = recognizer ?: return false
        val s = stream ?: return false
        return runCatching { r.isEndpoint(s) }.getOrDefault(false)
    }

    @Synchronized
    fun finalText(): String {
        val r = recognizer ?: return ""
        val s = stream ?: return ""
        return runCatching { r.getResult(s).text }.getOrDefault("")
    }

    @Synchronized
    fun reset() {
        runCatching { stream?.release() }
        stream = null
    }

    @Synchronized
    fun release() {
        reset()
        runCatching { recognizer?.release() }
        recognizer = null
    }

    private fun assetExists(path: String): Boolean {
        if (path.isBlank()) return false
        return runCatching {
            context.assets.open(path).use { }
            true
        }.getOrDefault(false)
    }

    companion object {
        private const val TAG = "AndroStreamingASR"
    }
}
