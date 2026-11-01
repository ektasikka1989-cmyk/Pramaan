package com.example.arfas

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

private const val MEMORY_TAG = "ConversationMemory"

data class ConversationMemoryRecord(
    val id: Long,
    val text: String,
    val timestampMs: Long,
    val semanticScore: Float,
    val lexicalScore: Float
)

class ConversationMemoryStore(
    context: Context,
    private val semanticEngine: SemanticSearchEngine
) : SQLiteOpenHelper(
    context.applicationContext,
    "androllm_conversation_memory.db",
    null,
    5
) {
    @Volatile private var conversationId: String = "default"

    fun setConversationId(id: String) {
        conversationId = id.trim().ifBlank { "default" }
    }

    @Synchronized
    fun pruneConversationIds(validIds: Set<String>) {
        if (validIds.isEmpty()) {
            writableDatabase.execSQL("DELETE FROM memories")
            return
        }
        val placeholders = validIds.joinToString(",") { "?" }
        writableDatabase.execSQL(
            "DELETE FROM memories WHERE conversation_id NOT IN ($placeholders)",
            validIds.toTypedArray()
        )
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE memories(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                conversation_id TEXT NOT NULL,
                content TEXT NOT NULL,
                embedding BLOB,
                timestamp_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_memories_time ON memories(timestamp_ms)")
        db.execSQL("CREATE INDEX idx_memories_conversation ON memories(conversation_id, timestamp_ms)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS memories")
        onCreate(db)
    }

    /**
     * FIXED per handoff §9: Extract and index only DURABLE information from
     * accepted turns — NOT the full verbatim "User: ... Assistant: ..." exchange.
     *
     * The handoff example:
     *   Input:  "The IP phone was working yesterday."
     *   Store:  "FACT: IP phone was working normally until yesterday."
     *
     * NOT:
     *   "User: The IP phone was working yesterday.
     *    Assistant: Okay, let's check several things..."
     */
    @Volatile
    var extractor: (suspend (String, String) -> List<String>)? = null

    suspend fun indexAcceptedExchange(userText: String, assistantText: String) {
        val extraction = extractor ?: return
        val memoryItems = try { extraction(userText, assistantText) } catch (t: Throwable) {
            Log.w(MEMORY_TAG, "MEMORY_EXTRACT_FAILED ${t.message}")
            emptyList()
        }
        if (memoryItems.isEmpty()) return

        memoryItems.take(3).forEach { itemRaw ->
            val item = FixSightOutputSanitizer.sanitize(itemRaw).trim()
            if (item.length !in 8..320) return@forEach
            val embedding = if (semanticEngine.isReady) semanticEngine.embed("passage: $item") else null
            val blob = embedding?.let(::floatArrayToBlob)
            synchronized(this@ConversationMemoryStore) {
                writableDatabase.execSQL(
                    "INSERT INTO memories(conversation_id, content, embedding, timestamp_ms) VALUES(?,?,?,?)",
                    arrayOf(conversationId, item, blob, System.currentTimeMillis())
                )
                writableDatabase.execSQL(
                    "DELETE FROM memories WHERE conversation_id=? AND id NOT IN (SELECT id FROM memories WHERE conversation_id=? ORDER BY id DESC LIMIT 500)",
                    arrayOf(conversationId, conversationId)
                )
            }
            Log.d(MEMORY_TAG, "MEMORY_FACT_INDEXED semantic=${embedding != null} chars=${item.length}")
        }
    }

    suspend fun retrieve(query: String, maxResults: Int = 4): List<ConversationMemoryRecord> {
        val clean = query.trim()
        if (clean.isBlank()) return emptyList()

        val queryEmbedding = if (semanticEngine.isReady) semanticEngine.embed("query: $clean") else null
        val queryTerms = tokenize(clean)

        val scored = ArrayList<ConversationMemoryRecord>()
        synchronized(this) {
            readableDatabase.rawQuery(
                "SELECT id, content, embedding, timestamp_ms FROM memories WHERE conversation_id=? ORDER BY id DESC LIMIT 500",
                arrayOf(conversationId)
            ).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val content = c.getString(1).orEmpty()
                    val blob = if (c.isNull(2)) null else c.getBlob(2)
                    val time = c.getLong(3)

                    val semantic = if (queryEmbedding != null && blob != null) {
                        semanticEngine.blobToFloatArray(blob)?.let {
                            semanticEngine.cosineSimilarity(queryEmbedding, it)
                        } ?: 0f
                    } else 0f

                    val memoryTerms = tokenize(content)
                    val matched = if (queryTerms.isEmpty()) 0 else queryTerms.count { it in memoryTerms }
                    val lexical = if (queryTerms.isEmpty()) 0f else matched.toFloat() / queryTerms.size.toFloat()

                    val admitted = if (queryEmbedding != null && blob != null) {
                        semantic >= 0.80f
                    } else {
                        matched >= 2 || (queryTerms.size == 1 && lexical >= 1f)
                    }

                    if (admitted) {
                        scored += ConversationMemoryRecord(id, content, time, semantic, lexical)
                    }
                }
            }
        }

        return scored
            .sortedWith(
                compareByDescending<ConversationMemoryRecord> { max(it.semanticScore, it.lexicalScore * 0.90f) }
                    .thenByDescending { it.timestampMs }
            )
            .take(maxResults.coerceIn(1, 8))
    }

    private fun tokenize(text: String): Set<String> =
        text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .split(' ')
            .asSequence()
            .map { it.trim() }
            .filter { it.length >= 3 }
            .toSet()

    @Synchronized
    fun hasAnyMemory(): Boolean = try {
        readableDatabase.rawQuery("SELECT 1 FROM memories WHERE conversation_id=? LIMIT 1", arrayOf(conversationId)).use { it.moveToFirst() }
    } catch (_: Throwable) { false }

    fun closeStore() {
        try { close() } catch (_: Throwable) {}
    }

    private fun floatArrayToBlob(values: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buffer.putFloat(it) }
        return buffer.array()
    }
}

class ConversationMemoryContextProvider(
    private val store: ConversationMemoryStore
) : BaselineContextProvider {

    /**
     * Skip memory retrieval (and its ONNX embedding call) when:
     *   - The store has fewer than 3 entries — not enough to be useful.
     *   - The query is under 4 words — too short to match meaningfully.
     *
     * Without this override the default returns true and memory retrieval
     * runs ONNX on every single turn, including the first turn on an empty store.
     */
    override suspend fun shouldAttach(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): Boolean {
        if (query.isBlank() || !store.hasAnyMemory()) return false

        // Do not let old durable memory complete an under-specified first turn.
        // Example observed on-device: Whisper returned only "not starting." and an
        // old memory attachment caused Gemma to invent "laptop". This is a
        // structural specificity guard, not a topic keyword router.
        val semanticWords = query.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}']+"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.length >= 2 }
        if (acceptedHistory.isEmpty() && semanticWords.size < 3) return false
        return true
    }

    override suspend fun contextFor(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): BaselineContextAttachment? {
        val recentIds = acceptedHistory.takeLast(8).map { it.content.trim() }.toHashSet()
        val matches = store.retrieve(query)
            .filterNot { record ->
                recentIds.any { recent -> recent.isNotBlank() && record.text.contains(recent) }
            }
            // Durable memory should be a strong contextual signal, not merely a
            // broad topical resemblance. Tighten semantic admission here so generic
            // phrases like "not starting" do not pull an unrelated old device fact.
            .filter { record ->
                if (record.semanticScore > 0f) record.semanticScore >= 0.84f
                else record.lexicalScore >= 0.75f
            }
            .take(3)

        if (matches.isEmpty()) return null

        val text = matches.joinToString("\n\n") { it.text.take(900) }.take(3_000)
        Log.i(
            MEMORY_TAG,
            "MEMORY_CONTEXT_ATTACHED matches=${matches.size} bestSemantic=" +
                "${"%.4f".format(matches.maxOfOrNull { it.semanticScore } ?: 0f)} chars=${text.length}"
        )

        return BaselineContextAttachment(
            sourceId = "conversation_memory",
            text = text,
            priority = 60,
            attributes = mapOf("retrieval" to "hybrid_cosine_keyword")
        )
    }
}

class ConversationMemoryObserver(
    private val store: ConversationMemoryStore
) : AcceptedTurnObserver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun onAcceptedTurn(
        userText: String,
        assistantText: String,
        provider: AndroBaselineRuntime.Provider
    ) {
        // Durable-memory extraction must never block the live response path.
        // Notebook/state observers remain synchronous; only the expensive memory
        // extraction/indexing work is dispatched in the background.
        scope.launch {
            try { store.indexAcceptedExchange(userText, assistantText) }
            catch (t: Throwable) { Log.w(MEMORY_TAG, "MEMORY_BACKGROUND_FAILED ${t.message}") }
        }
    }
}
