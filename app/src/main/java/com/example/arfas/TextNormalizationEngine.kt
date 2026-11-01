package com.example.arfas

import android.util.Log

/** Package-adapted AndroLLM text-normalization facade for offline TTS. */
class TextNormalizationEngine {
    @Volatile private var settingsSnapshot: NormalizationSettings = NormalizationSettings.ALL_ENABLED
    private val pipeline = TextNormalizationPipeline(
        stages = TextNormalizationPipeline.defaultStages(),
        settingsProvider = { settingsSnapshot }
    )

    fun onSettingsChanged(s: FixSightVoiceSettings) {
        settingsSnapshot = NormalizationSettings.from(s)
    }

    fun normalize(input: String): NormalizationResult {
        val snapshot = settingsSnapshot
        if (input.isBlank() || !snapshot.enabled) return NormalizationResult(input)
        val start = System.nanoTime()
        val result = pipeline.process(input)
        if (snapshot.debug) {
            result.traces.forEach { trace ->
                Log.i(TAG, "TN [${trace.stageId}]: '${trace.input}' -> '${trace.output}'")
            }
            Log.i(TAG, "TN done in ${(System.nanoTime() - start) / 1_000_000L}ms: '${result.text}'")
        }
        return result
    }

    companion object { private const val TAG = "TextNormalization" }
}
