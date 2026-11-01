package com.example.arfas

import android.content.Context

/**
 * Batch 4: UI language and voice-input language (new prefs file "arogya_ui"; no
 * existing prefs name is touched, so nothing saved before is affected).
 *  - UI: the phone's language by default (Hindi when the phone is Hindi), with the
 *    हिन्दी/English toggle; the toggle wins once used.
 *  - Voice: Hindi / English / Auto (Hinglish) for dictation, remembered per user
 *    (one ASHA profile per phone), passed to Whisper and Muse Voice Transcribe.
 */
object ArogyaLang {
    const val VOICE_AUTO = "auto"
    const val VOICE_HINDI = "hi"
    const val VOICE_ENGLISH = "en"

    private const val PREFS = "arogya_ui"
    private const val KEY_UI_HINDI = "ui_hindi"
    private const val KEY_UI_SET = "ui_hindi_set"
    private const val KEY_VOICE = "voice_lang"
    private const val KEY_WELCOMED = "welcomed"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True when the phone itself runs in Hindi. */
    fun isHindiPhone(): Boolean = java.util.Locale.getDefault().language == "hi"

    /** UI language: the toggle when used, else the phone's language. */
    fun uiHindi(c: Context): Boolean {
        val p = prefs(c)
        return if (p.contains(KEY_UI_SET)) p.getBoolean(KEY_UI_HINDI, false) else isHindiPhone()
    }

    fun setUiHindi(c: Context, hindi: Boolean) {
        prefs(c).edit().putBoolean(KEY_UI_HINDI, hindi).putBoolean(KEY_UI_SET, true).apply()
    }

    /** Dictation language: "hi", "en" or "auto". */
    fun voiceLang(c: Context): String =
        prefs(c).getString(KEY_VOICE, VOICE_AUTO).takeIf { it == VOICE_HINDI || it == VOICE_ENGLISH } ?: VOICE_AUTO

    fun setVoiceLang(c: Context, v: String) {
        prefs(c).edit().putString(KEY_VOICE, if (v == VOICE_HINDI || v == VOICE_ENGLISH) v else VOICE_AUTO).apply()
    }

    /** Batch 6 (U7): the 3-slide welcome shows once, then never again. Same new prefs file. */
    fun welcomed(c: Context): Boolean = prefs(c).getBoolean(KEY_WELCOMED, false)

    fun setWelcomed(c: Context) {
        prefs(c).edit().putBoolean(KEY_WELCOMED, true).apply()
    }

    /** Whisper session language: "hi" fixes the Urdu-script Hindi output; "auto" lets it detect. */
    fun whisperLang(voice: String): String = when (voice) {
        VOICE_HINDI -> "hi"
        VOICE_ENGLISH -> "en"
        else -> "auto"
    }

    /** Muse Voice Transcribe language bias (MetaModelApi.languages). */
    fun metaLanguages(voice: String): List<String> = when (voice) {
        VOICE_HINDI -> listOf("Hindi")
        VOICE_ENGLISH -> listOf("English")
        else -> listOf("Hindi", "English")
    }

    /** Best Android voice for [lang] ("hi"/"en"): in-country first, then quality. Pure, host-testable. */
    fun rankVoice(
        voices: List<Triple<java.util.Locale, Int, Boolean>>,
        lang: String,
        online: Boolean
    ): Int {
        // Triple(locale, quality, networkRequired) -> index of the best, or -1.
        val use = voices.mapIndexedNotNull { i, (loc, _, _) -> if (loc.language == lang) i else null }
        if (use.isEmpty()) return -1
        // In-country (hi-IN/en-IN) outranks the bare language and other regions.
        val inCountry = use.filter { voices[it].first.country.equals("IN", ignoreCase = true) }
        val pool = if (inCountry.isNotEmpty()) inCountry else use
        // Offline-first (or online-first with Cloud first + signal), then quality.
        val offline = pool.filter { !voices[it].third }
        val onlinePool = pool.filter { voices[it].third }
        val ordered = if (online) onlinePool.ifEmpty { offline } else offline.ifEmpty { onlinePool }
        val pickFrom = if (ordered.isNotEmpty()) ordered else pool
        return pickFrom.maxByOrNull { voices[it].second } ?: -1
    }
}
