package com.example.arfas

/**
 * KB-only conversational continuity used for follow-up questions. Kept in memory; after an app
 * restart or when an earlier conversation is reopened it is re-seeded from that conversation's
 * saved turns (see AndroBaselineRuntime), so follow-ups keep working.
 */
import java.util.ArrayDeque

class PocketRagSessionStore(
    private val maxTurnsPerKb: Int = 40
) {
    private val sessions = LinkedHashMap<String, ArrayDeque<PocketRagTurnMemory>>()

    @Synchronized
    fun record(kbId: String, user: String, assistant: String, sourceIds: List<String>) {
        val deque = sessions.getOrPut(kbId) { ArrayDeque() }
        deque.addLast(
            PocketRagTurnMemory(
                user = user.trim().take(2_400),
                // Keep the start AND the end of long answers: the end carries the app's "type continue"
                // note (KbReplyText.MORE_EN) that the next turn's continuation check looks for; the
                // old take(4000) cut it off long answers, so "continue" was searched as a word.
                assistant = assistant.trim().let { a -> if (a.length <= 4_000) a else a.take(2_800) + " … " + a.takeLast(1_150) },
                sourceIds = sourceIds
            )
        )
        while (deque.size > maxTurnsPerKb.coerceAtLeast(1)) deque.removeFirst()
    }

    @Synchronized
    fun context(kbId: String): List<PocketRagTurnMemory> = sessions[kbId]?.toList().orEmpty()

    /** Seeds [kbId] with saved (user, assistant) turns when it has none yet. Returns true when seeded. */
    @Synchronized
    fun seedIfEmpty(kbId: String, turns: List<Pair<String, String>>): Boolean {
        if (sessions[kbId]?.isNotEmpty() == true || turns.isEmpty()) return false
        turns.takeLast(maxTurnsPerKb.coerceAtLeast(1)).forEach { (u, a) -> record(kbId, u, a, emptyList()) }
        return true
    }

    @Synchronized
    fun clearAll() {
        sessions.clear()
    }

    @Synchronized
    fun clear(kbId: String) {
        sessions.remove(kbId)
    }

    @Synchronized
    fun clearAllExcept(kbId: String) {
        val keep = sessions[kbId]
        sessions.clear()
        if (keep != null) sessions[kbId] = keep
    }

    fun asAndroHistory(kbId: String): List<AndroChatMessage> {
        var id = -10_000L
        return context(kbId).flatMap { turn ->
            listOf(
                AndroChatMessage(id++, "user", turn.user, 0L),
                AndroChatMessage(id++, "assistant", turn.assistant, 0L)
            )
        }
    }
}
