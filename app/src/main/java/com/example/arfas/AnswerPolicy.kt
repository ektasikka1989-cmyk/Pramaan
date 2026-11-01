package com.example.arfas

import android.content.Context

/**
 * User-selectable general-answer preference.
 *
 * This never overrides authoritative source ownership (selected KB, attachments,
 * visual/AR capability), safety routing, or hard freshness requirements.
 */
enum class AnswerPolicy {
    LOCAL_FIRST,
    CLOUD_FIRST
}

class AnswerPolicyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(): AnswerPolicy = runCatching {
        AnswerPolicy.valueOf(prefs.getString(KEY_MODE, AnswerPolicy.LOCAL_FIRST.name).orEmpty())
    }.getOrDefault(AnswerPolicy.LOCAL_FIRST)

    fun set(policy: AnswerPolicy) {
        prefs.edit().putString(KEY_MODE, policy.name).apply()
    }

    /**
     * Online backup for knowledge-base answers: when the phone's own answer fails its checks
     * twice, the online AI answers from the same knowledge-base passages (only with internet).
     * Off by default: knowledge-base data leaves the phone only when the user turns this on.
     */
    fun onlineBackup(): Boolean = prefs.getBoolean(KEY_ONLINE_BACKUP, false)

    fun setOnlineBackup(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ONLINE_BACKUP, enabled).apply()
    }

    /**
     * Meta online voice: with a Meta key and internet, spoken questions are transcribed by Meta Muse
     * Voice Transcribe (Hindi/English code-switching); Whisper on the phone otherwise. Off by
     * default: the recording leaves the phone only when the user turns this on.
     */
    fun onlineVoice(): Boolean = prefs.getBoolean(KEY_ONLINE_VOICE, false) || get() == AnswerPolicy.CLOUD_FIRST   // ArogyaDoot: one Cloud first / Local first setting for everything

    fun setOnlineVoice(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ONLINE_VOICE, enabled).apply()
    }

    companion object {
        private const val PREFS = "fixsight_answer_policy"
        private const val KEY_ONLINE_VOICE = "meta_online_voice"
        private const val KEY_MODE = "mode"
        private const val KEY_ONLINE_BACKUP = "kb_online_backup"
    }
}

/** Narrow deterministic policy guards used only for provider preference/verification. */
object AnswerPolicyHeuristics {
    private val EXACT_STABLE_FACT = Regex(
        """(?i)^\s*(?:who\s+(?:killed|assassinated|wrote|authored|invented|discovered|founded|won|defeated)|when\s+(?:did|was|were)|what\s+(?:year|date)\b|where\s+(?:was|were)\b).+"""
    )

    fun isExactStableFactRequest(query: String): Boolean = EXACT_STABLE_FACT.matches(query.trim())
}
