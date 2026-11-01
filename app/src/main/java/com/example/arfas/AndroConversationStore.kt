package com.example.arfas

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID
import kotlin.math.ceil

/** Durable accepted-chat memory, now scoped by conversation/session. */
data class AndroChatMessage(
    val id: Long,
    val role: String,
    val content: String,
    val timestampMs: Long
)

/**
 * One saved message as the chat screen shows it: who answered (local / online), the sources and
 * markers (cited passages, "not in this KB" card, Check online) and whether a knowledge base was used.
 */
data class AndroDisplayMessage(
    val role: String,
    val content: String,
    val provider: String = "",
    val sourceIds: List<String> = emptyList(),
    val knowledgeUsed: Boolean = false
)

data class AndroConversationSession(
    val id: String,
    val title: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val mode: String,
    val messageCount: Int
)

class AndroConversationStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    "androllm_baseline_chat.db",
    null,
    3
) {
    companion object {
        private const val PREFS = "androllm_conversation_state"
        private const val KEY_ACTIVE_ID = "active_conversation_id"
        private const val MAX_CONVERSATIONS = 5
        private const val DEFAULT_MODE = "NORMAL"
        private const val LEGACY_ID = "legacy"
        /** Separates source markers in one column (markers themselves may contain \u001F). */
        private const val SOURCE_SEPARATOR = "\u001E"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    @Volatile private var interruptedUser: String = ""
    @Volatile private var interruptedAssistant: String = ""
    @Volatile private var activeConversationId: String = prefs.getString(KEY_ACTIVE_ID, "").orEmpty()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE conversations (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                created_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                mode TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                conversation_id TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                timestamp_ms INTEGER NOT NULL,
                provider TEXT NOT NULL DEFAULT '',
                source_ids TEXT NOT NULL DEFAULT '',
                knowledge_used INTEGER NOT NULL DEFAULT 0,
                display_only INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_messages_conversation_time ON messages(conversation_id, timestamp_ms)")
        db.execSQL("CREATE INDEX idx_conversations_updated ON conversations(updated_at_ms)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS conversations (
                    id TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    created_at_ms INTEGER NOT NULL,
                    updated_at_ms INTEGER NOT NULL,
                    mode TEXT NOT NULL
                )
                """.trimIndent()
            )
            runCatching {
                db.execSQL("ALTER TABLE messages ADD COLUMN conversation_id TEXT NOT NULL DEFAULT '$LEGACY_ID'")
            }
            val now = System.currentTimeMillis()
            var count = 0
            db.rawQuery("SELECT COUNT(*) FROM messages", null).use { if (it.moveToFirst()) count = it.getInt(0) }
            if (count > 0) {
                var firstUser = "Previous conversation"
                db.rawQuery(
                    "SELECT content FROM messages WHERE role='user' ORDER BY id ASC LIMIT 1",
                    null
                ).use { if (it.moveToFirst()) firstUser = titleFrom(it.getString(0)) }
                db.execSQL(
                    "INSERT OR IGNORE INTO conversations(id,title,created_at_ms,updated_at_ms,mode) VALUES(?,?,?,?,?)",
                    arrayOf(LEGACY_ID, firstUser, now, now, DEFAULT_MODE)
                )
                db.execSQL("UPDATE messages SET conversation_id=? WHERE conversation_id IS NULL OR conversation_id=''", arrayOf(LEGACY_ID))
            }
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_conversation_time ON messages(conversation_id, timestamp_ms)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_conversations_updated ON conversations(updated_at_ms)")
        }
        if (oldVersion < 3) {
            // Version 3 keeps what the chat screen shows with each answer, so sources, the
            // provider badge and replies that are not part of the model's memory survive the
            // app being closed or sent to the background. Existing messages are kept.
            runCatching { db.execSQL("ALTER TABLE messages ADD COLUMN provider TEXT NOT NULL DEFAULT ''") }
            runCatching { db.execSQL("ALTER TABLE messages ADD COLUMN source_ids TEXT NOT NULL DEFAULT ''") }
            runCatching { db.execSQL("ALTER TABLE messages ADD COLUMN knowledge_used INTEGER NOT NULL DEFAULT 0") }
            runCatching { db.execSQL("ALTER TABLE messages ADD COLUMN display_only INTEGER NOT NULL DEFAULT 0") }
        }
    }

    @Synchronized
    fun currentConversationId(): String = ensureActiveConversationLocked(DEFAULT_MODE)

    @Synchronized
    fun startNewConversation(mode: String = DEFAULT_MODE): String {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        writableDatabase.execSQL(
            "INSERT INTO conversations(id,title,created_at_ms,updated_at_ms,mode) VALUES(?,?,?,?,?)",
            arrayOf(id, "New chat", now, now, mode.ifBlank { DEFAULT_MODE })
        )
        setActiveLocked(id)
        trimConversationsLocked()
        clearInterruptedTurn()
        return id
    }

    @Synchronized
    fun activateConversation(id: String): Boolean {
        val clean = id.trim()
        if (clean.isBlank()) return false
        var exists = false
        readableDatabase.rawQuery("SELECT 1 FROM conversations WHERE id=? LIMIT 1", arrayOf(clean)).use {
            exists = it.moveToFirst()
        }
        if (!exists) return false
        setActiveLocked(clean)
        writableDatabase.execSQL("UPDATE conversations SET updated_at_ms=? WHERE id=?", arrayOf(System.currentTimeMillis(), clean))
        clearInterruptedTurn()
        return true
    }

    @Synchronized
    fun listConversations(limit: Int = MAX_CONVERSATIONS): List<AndroConversationSession> {
        val out = ArrayList<AndroConversationSession>()
        readableDatabase.rawQuery(
            """
            SELECT c.id,c.title,c.created_at_ms,c.updated_at_ms,c.mode,COUNT(m.id)
            FROM conversations c
            LEFT JOIN messages m ON m.conversation_id=c.id
            GROUP BY c.id,c.title,c.created_at_ms,c.updated_at_ms,c.mode
            ORDER BY c.updated_at_ms DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.coerceIn(1, MAX_CONVERSATIONS).toString())
        ).use { c ->
            while (c.moveToNext()) {
                out += AndroConversationSession(
                    id = c.getString(0), title = c.getString(1), createdAtMs = c.getLong(2),
                    updatedAtMs = c.getLong(3), mode = c.getString(4), messageCount = c.getInt(5)
                )
            }
        }
        return out
    }

    @Synchronized
    fun deleteConversation(id: String) {
        writableDatabase.execSQL("DELETE FROM messages WHERE conversation_id=?", arrayOf(id))
        writableDatabase.execSQL("DELETE FROM conversations WHERE id=?", arrayOf(id))
        if (activeConversationId == id) {
            activeConversationId = ""
            prefs.edit().remove(KEY_ACTIVE_ID).apply()
        }
    }

    @Synchronized
    fun append(role: String, content: String) {
        val cleanRole = role.trim().lowercase()
        val cleanContent = content.trim()
        if (cleanRole !in setOf("user", "assistant") || cleanContent.isBlank()) return
        val conversationId = ensureActiveConversationLocked(DEFAULT_MODE)
        val now = System.currentTimeMillis()
        writableDatabase.execSQL(
            "INSERT INTO messages(conversation_id, role, content, timestamp_ms) VALUES(?,?,?,?)",
            arrayOf(conversationId, cleanRole, cleanContent, now)
        )
        if (cleanRole == "user") {
            writableDatabase.execSQL(
                "UPDATE conversations SET title=CASE WHEN title='New chat' THEN ? ELSE title END, updated_at_ms=? WHERE id=?",
                arrayOf(titleFrom(cleanContent), now, conversationId)
            )
        } else {
            writableDatabase.execSQL("UPDATE conversations SET updated_at_ms=? WHERE id=?", arrayOf(now, conversationId))
        }
        writableDatabase.execSQL(
            "DELETE FROM messages WHERE conversation_id=? AND id NOT IN (SELECT id FROM messages WHERE conversation_id=? ORDER BY id DESC LIMIT 300)",
            arrayOf(conversationId, conversationId)
        )
        trimConversationsLocked()
    }

    @Synchronized
    fun recent(limit: Int = 24): List<AndroChatMessage> {
        val conversationId = ensureActiveConversationLocked(DEFAULT_MODE)
        val out = ArrayList<AndroChatMessage>()
        readableDatabase.rawQuery(
            "SELECT id, role, content, timestamp_ms FROM messages WHERE conversation_id=? AND display_only=0 ORDER BY id DESC LIMIT ?",
            arrayOf(conversationId, limit.coerceIn(1, 300).toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(AndroChatMessage(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3)))
            }
        }
        out.reverse()
        return out
    }

    @Synchronized
    fun allCurrentMessages(limit: Int = 300): List<AndroChatMessage> = recent(limit)

    /**
     * Every message of the open conversation as the chat screen shows it, including replies kept
     * for display only (for example "not found in this knowledge base"), with sources and badges.
     */
    @Synchronized
    fun displayMessages(limit: Int = 300): List<AndroDisplayMessage> {
        val conversationId = ensureActiveConversationLocked(DEFAULT_MODE)
        val out = ArrayList<AndroDisplayMessage>()
        readableDatabase.rawQuery(
            "SELECT role, content, provider, source_ids, knowledge_used FROM messages WHERE conversation_id=? ORDER BY id DESC LIMIT ?",
            arrayOf(conversationId, limit.coerceIn(1, 300).toString())
        ).use { c ->
            while (c.moveToNext()) {
                out += AndroDisplayMessage(
                    role = c.getString(0),
                    content = c.getString(1),
                    provider = c.getString(2).orEmpty(),
                    sourceIds = c.getString(3).orEmpty().split(SOURCE_SEPARATOR).filter { it.isNotBlank() },
                    knowledgeUsed = c.getInt(4) != 0
                )
            }
        }
        out.reverse()
        return out
    }

    /**
     * Saves what the chat screen shows for a finished turn. When the turn's answer was already
     * saved as conversation memory (an assistant message written since [turnStartedMs]), its
     * provider and sources are added to it. Otherwise (a reply that is deliberately not part of
     * the model's memory: not found, could not answer, interrupted) the question and reply are
     * saved for display only, so they are still there after the app is closed or reopened.
     */
    @Synchronized
    fun saveTurnForDisplay(
        user: String,
        assistant: String,
        provider: String,
        sourceIds: List<String>,
        knowledgeUsed: Boolean,
        turnStartedMs: Long
    ) {
        val cleanUser = user.trim()
        val cleanAssistant = assistant.trim()
        if (cleanAssistant.isBlank()) return
        val conversationId = ensureActiveConversationLocked(DEFAULT_MODE)
        val sources = sourceIds.filter { it.isNotBlank() }.joinToString(SOURCE_SEPARATOR)
        val knowledge = if (knowledgeUsed) 1 else 0
        var savedId = -1L
        readableDatabase.rawQuery(
            "SELECT id, role, timestamp_ms FROM messages WHERE conversation_id=? ORDER BY id DESC LIMIT 1",
            arrayOf(conversationId)
        ).use { c ->
            if (c.moveToFirst() && c.getString(1) == "assistant" && c.getLong(2) >= turnStartedMs) savedId = c.getLong(0)
        }
        if (savedId >= 0L) {
            writableDatabase.execSQL(
                "UPDATE messages SET provider=?, source_ids=?, knowledge_used=? WHERE id=?",
                arrayOf(provider, sources, knowledge, savedId)
            )
            return
        }
        val now = System.currentTimeMillis()
        if (cleanUser.isNotBlank()) {
            writableDatabase.execSQL(
                "INSERT INTO messages(conversation_id, role, content, timestamp_ms, display_only) VALUES(?,?,?,?,1)",
                arrayOf(conversationId, "user", cleanUser, now)
            )
            writableDatabase.execSQL(
                "UPDATE conversations SET title=CASE WHEN title='New chat' THEN ? ELSE title END, updated_at_ms=? WHERE id=?",
                arrayOf(titleFrom(cleanUser), now, conversationId)
            )
        }
        writableDatabase.execSQL(
            "INSERT INTO messages(conversation_id, role, content, timestamp_ms, provider, source_ids, knowledge_used, display_only) VALUES(?,?,?,?,?,?,?,1)",
            arrayOf(conversationId, "assistant", cleanAssistant, now, provider, sources, knowledge)
        )
    }

    @Synchronized
    fun recordInterruptedTurn(user: String, partialAssistant: String) {
        interruptedUser = user.trim().take(1_200)
        interruptedAssistant = partialAssistant.trim().take(1_800)
    }

    @Synchronized
    fun clearInterruptedTurn() { interruptedUser = ""; interruptedAssistant = "" }

    @Synchronized
    fun interruptedContext(): String {
        if (interruptedUser.isBlank()) return ""
        return buildString {
            appendLine("[MOST RECENT INTERRUPTED EXCHANGE]")
            appendLine("User: $interruptedUser")
            if (interruptedAssistant.isNotBlank()) appendLine("Assistant partial response (interrupted; not authoritative): $interruptedAssistant")
            else appendLine("Assistant response was interrupted before any answer was completed.")
            append("[END MOST RECENT INTERRUPTED EXCHANGE]")
        }
    }

    @Synchronized
    fun recentWithinTokenBudget(maxEstimatedTokens: Int, maxMessages: Int = 120): List<AndroChatMessage> {
        val candidates = recent(maxMessages.coerceIn(2, 300))
        if (candidates.isEmpty()) return emptyList()
        data class Pair(val user: AndroChatMessage, val assistant: AndroChatMessage?)
        val pairs = ArrayList<Pair>()
        var i = 0
        while (i < candidates.size) {
            val msg = candidates[i]
            if (msg.role == "user") {
                val next = candidates.getOrNull(i + 1)
                if (next != null && next.role == "assistant") { pairs.add(Pair(msg, next)); i += 2 }
                else { pairs.add(Pair(msg, null)); i += 1 }
            } else i += 1
        }
        val selected = ArrayDeque<Pair>()
        var used = 0
        for (pair in pairs.asReversed()) {
            val pairCost = estimateTokens(pair.user.content) + 8 + (pair.assistant?.let { estimateTokens(it.content) + 8 } ?: 0)
            if (selected.isNotEmpty() && used + pairCost > maxEstimatedTokens) break
            if (pairCost > maxEstimatedTokens) continue
            selected.addFirst(pair); used += pairCost
        }
        val out = selected.flatMap { p -> if (p.assistant != null) listOf(p.user, p.assistant) else listOf(p.user) }.toMutableList()
        if (interruptedUser.isNotBlank()) {
            val now = System.currentTimeMillis()
            val marker = "[Interrupted response; incomplete, not authoritative] $interruptedAssistant"
            val cost = estimateTokens(interruptedUser) + 8 + if (interruptedAssistant.isNotBlank()) estimateTokens(marker) + 8 else 0
            if (used + cost <= maxEstimatedTokens) {
                out.add(AndroChatMessage(Long.MAX_VALUE - 1, "user", interruptedUser, now))
                if (interruptedAssistant.isNotBlank()) out.add(AndroChatMessage(Long.MAX_VALUE, "assistant", marker, now))
            }
        }
        return out
    }

    @Synchronized
    fun buildRecoveryContext(currentQuery: String, maxEstimatedTokens: Int = 2_800, recentMessageCount: Int = 14, relevantOlderCount: Int = 0): String {
        @Suppress("UNUSED_VARIABLE") val ignoredQuery = currentQuery
        @Suppress("UNUSED_VARIABLE") val ignoredOlder = relevantOlderCount
        val chosen = recent(recentMessageCount.coerceIn(2, 80))
        if (chosen.isEmpty()) return ""
        val lines = ArrayDeque<String>()
        var tokens = estimateTokens("[ACCEPTED CONVERSATION HISTORY]\n[END ACCEPTED CONVERSATION HISTORY]")
        for (m in chosen.asReversed()) {
            val line = if (m.role == "user") "User: ${m.content}" else "Assistant: ${m.content}"
            val cost = estimateTokens(line) + 1
            if (tokens + cost > maxEstimatedTokens) continue
            lines.addFirst(line); tokens += cost
        }
        if (lines.isEmpty()) return ""
        return buildString { appendLine("[ACCEPTED CONVERSATION HISTORY]"); lines.forEach { appendLine(it) }; append("[END ACCEPTED CONVERSATION HISTORY]") }
    }

    @Synchronized
    fun historyForCloud(
        currentQuery: String,
        maxEstimatedTokens: Int = 8_000,
        recentMessageCount: Int = 30,
        relevantOlderCount: Int = 0
    ): List<kotlin.Pair<String, String>> {
        @Suppress("UNUSED_VARIABLE") val ignoredQuery = currentQuery
        @Suppress("UNUSED_VARIABLE") val ignoredOlder = relevantOlderCount
        val candidates = recent(recentMessageCount.coerceIn(2, 60))
        data class CloudPair(val user: AndroChatMessage, val assistant: AndroChatMessage?)
        val pairs = ArrayList<CloudPair>()
        var i = 0
        while (i < candidates.size) {
            val message = candidates[i]
            if (message.role == "user") {
                val next = candidates.getOrNull(i + 1)
                if (next != null && next.role == "assistant") {
                    pairs += CloudPair(message, next); i += 2
                } else {
                    pairs += CloudPair(message, null); i += 1
                }
            } else i += 1
        }
        val selected = ArrayDeque<CloudPair>()
        var tokens = 0
        for (pair in pairs.asReversed()) {
            val pairCost = estimateTokens(pair.user.content) + 4 +
                if (pair.assistant != null) estimateTokens(pair.assistant.content) + 4 else 0
            if (selected.isNotEmpty() && tokens + pairCost > maxEstimatedTokens) break
            if (pairCost > maxEstimatedTokens) continue
            selected.addFirst(pair); tokens += pairCost
        }
        val out = mutableListOf<kotlin.Pair<String, String>>()
        selected.forEach { pair ->
            out += "user" to pair.user.content
            pair.assistant?.let { out += "assistant" to it.content }
        }
        if (interruptedUser.isNotBlank()) {
            val marker = "[Interrupted response; incomplete, not authoritative] $interruptedAssistant"
            val interruptionCost = estimateTokens(interruptedUser) + 4 +
                if (interruptedAssistant.isNotBlank()) estimateTokens(marker) + 4 else 0
            if (tokens + interruptionCost <= maxEstimatedTokens) {
                out += "user" to interruptedUser
                if (interruptedAssistant.isNotBlank()) out += "assistant" to marker
            }
        }
        return out
    }

    private fun estimateTokens(text: String): Int = ceil(text.length / 3.0).toInt().coerceAtLeast(if (text.isBlank()) 0 else 1)

    @Synchronized
    fun clear() {
        val id = ensureActiveConversationLocked(DEFAULT_MODE)
        writableDatabase.execSQL("DELETE FROM messages WHERE conversation_id=?", arrayOf(id))
        writableDatabase.execSQL("UPDATE conversations SET title='New chat', updated_at_ms=? WHERE id=?", arrayOf(System.currentTimeMillis(), id))
        clearInterruptedTurn()
    }

    private fun ensureActiveConversationLocked(mode: String): String {
        val candidate = activeConversationId.ifBlank { prefs.getString(KEY_ACTIVE_ID, "").orEmpty() }
        if (candidate.isNotBlank()) {
            var exists = false
            readableDatabase.rawQuery("SELECT 1 FROM conversations WHERE id=? LIMIT 1", arrayOf(candidate)).use { exists = it.moveToFirst() }
            if (exists) { activeConversationId = candidate; return candidate }
        }
        return startNewConversation(mode)
    }

    private fun setActiveLocked(id: String) {
        activeConversationId = id
        prefs.edit().putString(KEY_ACTIVE_ID, id).apply()
    }

    private fun trimConversationsLocked() {
        val keep = ArrayList<String>()
        readableDatabase.rawQuery("SELECT id FROM conversations ORDER BY updated_at_ms DESC LIMIT $MAX_CONVERSATIONS", null).use {
            while (it.moveToNext()) keep += it.getString(0)
        }
        if (keep.isEmpty()) return
        val placeholders = keep.joinToString(",") { "?" }
        writableDatabase.execSQL("DELETE FROM messages WHERE conversation_id NOT IN ($placeholders)", keep.toTypedArray())
        writableDatabase.execSQL("DELETE FROM conversations WHERE id NOT IN ($placeholders)", keep.toTypedArray())
    }

    private fun titleFrom(text: String): String {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.isBlank()) return "New chat"
        return if (clean.length <= 42) clean else clean.take(39).trimEnd() + "…"
    }
}
