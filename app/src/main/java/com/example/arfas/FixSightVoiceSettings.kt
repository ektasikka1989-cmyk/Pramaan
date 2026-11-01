package com.example.arfas

import android.content.Context

/** Runtime-configurable voice settings following AndroLLM's VoiceSettings pattern. */
data class FixSightVoiceSettings(
    val silenceTimeoutMs: Int = 1_400,
    val sttLanguage: String = "auto",
    val sttAutoAllowedLanguages: Set<String> = setOf("en", "hi"),
    val sttMaxSeconds: Int = 60,   // a spoken visit note can take a minute
    val sttStreaming: Boolean = false,
    val sttTranslate: Boolean = false,
    val sttThreads: Int = -1,
    val sttBeamSize: Int = 1,
    val sttTemperature: Float = 0.0f,
    val speakingSpeed: Float = 1.0f,
    val noiseSuppression: Boolean = true,
    val echoCancellation: Boolean = true,
    val tnEnabled: Boolean = true,
    val tnNumbers: Boolean = true,
    val tnDates: Boolean = true,
    val tnCurrency: Boolean = true,
    val tnUnits: Boolean = true,
    val tnMath: Boolean = true,
    val tnEmoji: Boolean = true,
    val tnUrlsEmails: Boolean = true,
    val tnPhones: Boolean = true,
    val tnAbbreviations: Boolean = true,
    val tnDebug: Boolean = false
) {
    companion object {
        fun load(context: Context): FixSightVoiceSettings {
            val p = context.applicationContext.getSharedPreferences("fixsight_voice", Context.MODE_PRIVATE)
            val autoAllowed = p.getString("stt_auto_allowed_languages", "en,hi")
                .orEmpty()
                .split(',')
                .map { it.trim().lowercase() }
                .filter { it.matches(Regex("[a-z]{2,3}")) }
                .distinct()
                .take(3)
                .toSet()
                .ifEmpty { setOf("en", "hi") }
            return FixSightVoiceSettings(
                silenceTimeoutMs = p.getInt("silence_timeout_ms", 1_400).coerceIn(500, 5_000),
                sttLanguage = p.getString("stt_language", "auto")?.trim().orEmpty().ifBlank { "auto" },
                sttAutoAllowedLanguages = autoAllowed,
                sttMaxSeconds = p.getInt("stt_max_seconds", 60).coerceIn(5, 120),
                sttStreaming = p.getBoolean("stt_streaming", false),
                sttTranslate = p.getBoolean("stt_translate", false),
                sttThreads = p.getInt("stt_threads", -1).let { if (it < 0) -1 else it.coerceIn(1, 16) },
                sttBeamSize = p.getInt("stt_beam_size", 1).coerceIn(1, 5),
                sttTemperature = p.getFloat("stt_temperature", 0.0f).coerceIn(0.0f, 1.0f),
                speakingSpeed = p.getFloat("speaking_speed", 1.0f).coerceIn(0.5f, 2.0f),
                noiseSuppression = p.getBoolean("noise_suppression", true),
                echoCancellation = p.getBoolean("echo_cancellation", true),
                tnEnabled = p.getBoolean("tn_enabled", true),
                tnNumbers = p.getBoolean("tn_numbers", true),
                tnDates = p.getBoolean("tn_dates", true),
                tnCurrency = p.getBoolean("tn_currency", true),
                tnUnits = p.getBoolean("tn_units", true),
                tnMath = p.getBoolean("tn_math", true),
                tnEmoji = p.getBoolean("tn_emoji", true),
                tnUrlsEmails = p.getBoolean("tn_urls_emails", true),
                tnPhones = p.getBoolean("tn_phones", true),
                tnAbbreviations = p.getBoolean("tn_abbreviations", true),
                tnDebug = p.getBoolean("tn_debug", false)
            )
        }
    }
}
