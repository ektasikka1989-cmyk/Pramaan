package com.example.arfas

import android.content.Context
import android.util.Log

/**
 * Immutable-per-session audit trail used only for customer-facing reporting.
 * It is deliberately separate from PassiveNotebookObserver: Notebook may merge or
 * supersede reasoning state, while this trail preserves what actually happened.
 */
class SessionAuditTrail(context: Context) {
    companion object {
        private const val TAG = "SessionAuditTrail"
        private const val PREFS = "fixsight_session_audit_v1"
        private const val KEY_STARTED = "started_at"
        private const val KEY_TURNS = "turns"
        private const val SEP = "\u001F"
        private const val ROW_SEP = "\u001E"
        private const val MAX_TURNS = 80
    }

    data class Turn(
        val number: Int,
        val timestampMs: Long,
        val userText: String,
        val fullAnswer: String,
        val answerSummary: String,
        val provider: String,
        val sourceIds: List<String>,
        val visualUsed: Boolean,
        val attachmentName: String,
        val elapsedMs: Long
    )

    data class Snapshot(
        val startedAtMs: Long,
        val endedAtMs: Long,
        val turns: List<Turn>
    )

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    fun startSession(nowMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        prefs.edit().putLong(KEY_STARTED, nowMs).remove(KEY_TURNS).apply()
        Log.i(TAG, "SESSION_AUDIT_STARTED at=$nowMs")
    }

    fun recordAcceptedTurn(
        userText: String,
        result: AndroBaselineRuntime.TurnResult,
        attachmentName: String = ""
    ) = synchronized(lock) {
        if (result.provider == AndroBaselineRuntime.Provider.NONE || result.text.isBlank()) return@synchronized
        val turns = readTurnsLocked().toMutableList()
        val turn = Turn(
            number = (turns.lastOrNull()?.number ?: 0) + 1,
            timestampMs = System.currentTimeMillis(),
            userText = clean(userText, 1200),
            fullAnswer = clean(result.text, 12_000),
            answerSummary = summarizeAnswer(result.text),
            provider = result.provider.name,
            sourceIds = result.sourceIds.map { clean(it, 160) }.filter { it.isNotBlank() }.distinct().take(12),
            visualUsed = result.visualObservation.isNotBlank(),
            attachmentName = clean(attachmentName, 180),
            elapsedMs = result.elapsedMs
        )
        turns += turn
        prefs.edit().putString(KEY_TURNS, encodeTurns(turns.takeLast(MAX_TURNS))).apply()
        Log.i(
            TAG,
            "SESSION_AUDIT_TURN number=${turn.number} provider=${turn.provider} visual=${turn.visualUsed} " +
                "sources=${turn.sourceIds.size} summaryChars=${turn.answerSummary.length}"
        )
    }


    fun recordInterruptedTurn(
        userText: String,
        partialAnswer: String,
        reason: String
    ) = synchronized(lock) {
        if (userText.isBlank()) return@synchronized
        val turns = readTurnsLocked().toMutableList()
        val answer = partialAnswer.trim().ifBlank { "Response interrupted before completion." }
        val turn = Turn(
            number = (turns.lastOrNull()?.number ?: 0) + 1,
            timestampMs = System.currentTimeMillis(),
            userText = clean(userText, 1200),
            fullAnswer = clean(answer + " [Interrupted]", 12_000),
            answerSummary = summarizeAnswer(answer),
            provider = "INTERRUPTED",
            sourceIds = listOf("interrupt:${clean(reason, 80)}"),
            visualUsed = false,
            attachmentName = "",
            elapsedMs = 0L
        )
        turns += turn
        prefs.edit().putString(KEY_TURNS, encodeTurns(turns.takeLast(MAX_TURNS))).apply()
        Log.i(TAG, "SESSION_AUDIT_INTERRUPTED number=${turn.number} reason=${clean(reason, 80)} partialChars=${partialAnswer.length}")
    }

    fun snapshot(endedAtMs: Long = System.currentTimeMillis()): Snapshot = synchronized(lock) {
        Snapshot(
            startedAtMs = prefs.getLong(KEY_STARTED, 0L),
            endedAtMs = endedAtMs,
            turns = readTurnsLocked()
        )
    }

    fun clear() = synchronized(lock) {
        prefs.edit().clear().apply()
    }

    private fun summarizeAnswer(raw: String): String {
        val text = clean(raw, 4000)
        if (text.isBlank()) return ""
        val sentences = Regex("[^.!?।！？]+(?:[.!?।！？]+|$)")
            .findAll(text)
            .map { it.value.trim() }
            .filter { it.isNotBlank() }
            .toList()
        val candidate = when {
            sentences.isEmpty() -> text
            sentences.size == 1 -> sentences.first()
            else -> sentences.take(2).joinToString(" ")
        }
        return if (candidate.length <= 280) candidate else candidate.take(277).trimEnd() + "…"
    }

    private fun clean(value: String, max: Int): String =
        FixSightOutputSanitizer.sanitize(value)
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(max)

    private fun encodeTurns(turns: List<Turn>): String = turns.joinToString(ROW_SEP) { t ->
        listOf(
            t.number.toString(),
            t.timestampMs.toString(),
            enc(t.userText),
            enc(t.fullAnswer),
            enc(t.answerSummary),
            t.provider,
            enc(t.sourceIds.joinToString("\n")),
            if (t.visualUsed) "1" else "0",
            enc(t.attachmentName),
            t.elapsedMs.toString()
        ).joinToString(SEP)
    }

    private fun readTurnsLocked(): List<Turn> {
        val raw = prefs.getString(KEY_TURNS, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split(ROW_SEP).mapNotNull { row ->
            val p = row.split(SEP)
            if (p.size < 10) return@mapNotNull null
            runCatching {
                Turn(
                    number = p[0].toInt(),
                    timestampMs = p[1].toLong(),
                    userText = dec(p[2]),
                    fullAnswer = dec(p[3]),
                    answerSummary = dec(p[4]),
                    provider = p[5],
                    sourceIds = dec(p[6]).lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList(),
                    visualUsed = p[7] == "1",
                    attachmentName = dec(p[8]),
                    elapsedMs = p[9].toLong()
                )
            }.getOrNull()
        }
    }

    private fun enc(value: String): String = android.util.Base64.encodeToString(
        value.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE
    )

    private fun dec(value: String): String = String(
        android.util.Base64.decode(value, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE), Charsets.UTF_8
    )
}
