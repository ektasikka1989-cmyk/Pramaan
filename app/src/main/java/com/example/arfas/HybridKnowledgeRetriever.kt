package com.example.arfas

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Small offline lexical companion for the existing E5 retrievers.
 *
 * - Uses a writable sidecar SQLite FTS5 index; the selected/source KB is never mutated.
 * - No domain vocabulary, allowlist, or routing keywords are stored here.
 * - Query terms are derived only from the current user text.
 * - Callers fuse FTS/BM25 rank with semantic rank using reciprocal-rank fusion (RRF).
 *
 * If a device SQLite build does not expose FTS5/bm25(), the same sidecar uses a
 * portable term/postings index with equivalent BM25 signals; semantic retrieval
 * remains authoritative for final grounding/admission.
 */
class HybridKnowledgeRetriever(
    private val indexFile: File,
    private val tag: String
) {
    data class Document(val id: String, val text: String)
    data class LexicalHit(val id: String, val rank: Int, val bm25: Double)

    companion object {
        private const val META_TABLE = "hybrid_meta"
        private const val FTS_TABLE = "hybrid_fts"
        private const val DOCS_TABLE = "hybrid_docs"
        private const val PORTABLE_TERMS_TABLE = "hybrid_portable_terms"
        private const val PORTABLE_DOC_STATS_TABLE = "hybrid_portable_doc_stats"
        const val DEFAULT_RRF_K = 60
        private const val INDEX_SCHEMA_VERSION = "5"

        /** Standard rank-only RRF. Missing arms contribute zero. */
        fun rrfScore(semanticRank: Int?, lexicalRank: Int?, k: Int = DEFAULT_RRF_K): Float {
            var score = 0.0
            if (semanticRank != null && semanticRank > 0) score += 1.0 / (k + semanticRank)
            if (lexicalRank != null && lexicalRank > 0) score += 1.0 / (k + lexicalRank)
            return score.toFloat()
        }
    }

    @Volatile private var ftsAvailable: Boolean? = null
    @Volatile private var lastSearchMode: String = "NONE"

    fun searchMode(): String = lastSearchMode

    fun isCurrent(signature: String): Boolean {
        if (signature.isBlank() || !indexFile.exists()) return false
        var database: SQLiteDatabase? = null
        return try {
            database = SQLiteDatabase.openDatabase(indexFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            if (!tableExists(database, DOCS_TABLE) ||
                !tableExists(database, PORTABLE_TERMS_TABLE) ||
                !tableExists(database, PORTABLE_DOC_STATS_TABLE)
            ) return false
            val installedSchema = database.rawQuery(
                "SELECT v FROM $META_TABLE WHERE k='schema_version' LIMIT 1", null
            ).use { c -> if (c.moveToFirst()) c.getString(0).orEmpty() else "" }
            if (installedSchema != INDEX_SCHEMA_VERSION) return false
            database.rawQuery(
                "SELECT v FROM $META_TABLE WHERE k='signature' LIMIT 1", null
            ).use { c -> c.moveToFirst() && c.getString(0).orEmpty() == signature }
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { database?.close() }
        }
    }

    fun ensureIndex(signature: String, documents: Sequence<Document>): Boolean {
        if (signature.isBlank()) return false
        indexFile.parentFile?.mkdirs()
        var database: SQLiteDatabase? = null
        return try {
            database = SQLiteDatabase.openOrCreateDatabase(indexFile, null)
            database.execSQL("CREATE TABLE IF NOT EXISTS $META_TABLE (k TEXT PRIMARY KEY, v TEXT NOT NULL)")
            database.execSQL("CREATE TABLE IF NOT EXISTS $DOCS_TABLE (doc_id TEXT PRIMARY KEY, body TEXT NOT NULL)")
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS $PORTABLE_DOC_STATS_TABLE (" +
                    "doc_id TEXT PRIMARY KEY, doc_len INTEGER NOT NULL, doc_order INTEGER NOT NULL)"
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS $PORTABLE_TERMS_TABLE (" +
                    "term TEXT NOT NULL, doc_id TEXT NOT NULL, tf INTEGER NOT NULL, " +
                    "positions TEXT NOT NULL, PRIMARY KEY(term,doc_id)) WITHOUT ROWID"
            )
            val ftsReady = ensureFtsTable(database)

            val installedSignature = database.rawQuery(
                "SELECT v FROM $META_TABLE WHERE k='signature' LIMIT 1", null
            ).use { c -> if (c.moveToFirst()) c.getString(0).orEmpty() else "" }
            val installedSchema = database.rawQuery(
                "SELECT v FROM $META_TABLE WHERE k='schema_version' LIMIT 1", null
            ).use { c -> if (c.moveToFirst()) c.getString(0).orEmpty() else "" }
            if (installedSignature == signature && installedSchema == INDEX_SCHEMA_VERSION) return true

            database.beginTransaction()
            try {
                database.execSQL("DELETE FROM $DOCS_TABLE")
                database.execSQL("DELETE FROM $PORTABLE_TERMS_TABLE")
                database.execSQL("DELETE FROM $PORTABLE_DOC_STATS_TABLE")
                if (ftsReady) database.execSQL("DELETE FROM $FTS_TABLE")
                val docsStatement = database.compileStatement(
                    "INSERT OR REPLACE INTO $DOCS_TABLE(doc_id, body) VALUES (?, ?)"
                )
                val ftsStatement = if (ftsReady) database.compileStatement(
                    "INSERT INTO $FTS_TABLE(doc_id, body) VALUES (?, ?)"
                ) else null
                val statsStatement = database.compileStatement(
                    "INSERT OR REPLACE INTO $PORTABLE_DOC_STATS_TABLE(doc_id,doc_len,doc_order) VALUES (?, ?, ?)"
                )
                val termsStatement = database.compileStatement(
                    "INSERT OR REPLACE INTO $PORTABLE_TERMS_TABLE(term,doc_id,tf,positions) VALUES (?, ?, ?, ?)"
                )
                var rows = 0
                var indexedDocuments = 0
                var indexedTerms = 0L
                var totalDocumentLength = 0L
                documents.forEach { doc ->
                    val id = doc.id.trim()
                    val text = doc.text.trim()
                    if (id.isBlank() || text.isBlank()) return@forEach
                    docsStatement.clearBindings()
                    docsStatement.bindString(1, id)
                    docsStatement.bindString(2, text)
                    docsStatement.executeInsert()
                    ftsStatement?.let { statement ->
                        statement.clearBindings()
                        statement.bindString(1, id)
                        statement.bindString(2, text)
                        statement.executeInsert()
                    }
                    val bodyTokens = lexicalTokens(text, dropStopWords = true, maxTokens = 4096)
                    if (bodyTokens.isNotEmpty()) {
                        statsStatement.clearBindings()
                        statsStatement.bindString(1, id)
                        statsStatement.bindLong(2, bodyTokens.size.toLong())
                        statsStatement.bindLong(3, indexedDocuments.toLong())
                        statsStatement.executeInsert()

                        val positionsByTerm = LinkedHashMap<String, MutableList<Int>>()
                        bodyTokens.forEachIndexed { position, term ->
                            positionsByTerm.getOrPut(term) { ArrayList() } += position
                        }
                        positionsByTerm.forEach { (term, positions) ->
                            termsStatement.clearBindings()
                            termsStatement.bindString(1, term)
                            termsStatement.bindString(2, id)
                            termsStatement.bindLong(3, positions.size.toLong())
                            termsStatement.bindString(4, positions.joinToString(","))
                            termsStatement.executeInsert()
                            indexedTerms++
                        }
                        indexedDocuments++
                        totalDocumentLength += bodyTokens.size.toLong()
                    }
                    rows++
                }
                database.execSQL(
                    "INSERT OR REPLACE INTO $META_TABLE(k,v) VALUES('signature',?)",
                    arrayOf(signature)
                )
                database.execSQL(
                    "INSERT OR REPLACE INTO $META_TABLE(k,v) VALUES('schema_version',?)",
                    arrayOf(INDEX_SCHEMA_VERSION)
                )
                database.execSQL(
                    "INSERT OR REPLACE INTO $META_TABLE(k,v) VALUES('portable_document_count',?)",
                    arrayOf(indexedDocuments.toString())
                )
                database.execSQL(
                    "INSERT OR REPLACE INTO $META_TABLE(k,v) VALUES('portable_total_document_length',?)",
                    arrayOf(totalDocumentLength.toString())
                )
                database.setTransactionSuccessful()
                Log.i(
                    tag,
                    "HYBRID_INDEX_READY schema=$INDEX_SCHEMA_VERSION rows=$rows fts5=$ftsReady " +
                        "portableDocs=$indexedDocuments portableTerms=$indexedTerms signature=${signature.take(12)}"
                )
            } finally {
                database.endTransaction()
            }
            true
        } catch (t: Throwable) {
            Log.w(tag, "HYBRID_INDEX_UNAVAILABLE ${t.message}")
            false
        } finally {
            runCatching { database?.close() }
        }
    }

    /** Fast indexed lexical arm: FTS5 first, current-schema portable postings second. */
    fun searchIndexed(query: String, limit: Int = 16): List<LexicalHit> {
        lastSearchMode = "NONE"
        val match = ftsMatchQuery(query) ?: return emptyList()
        if (!indexFile.exists()) return emptyList()
        var database: SQLiteDatabase? = null
        return try {
            database = SQLiteDatabase.openDatabase(indexFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            if (ensureFtsReadable(database)) {
                val hits = database.rawQuery(
                    "SELECT doc_id, bm25($FTS_TABLE) AS score FROM $FTS_TABLE " +
                        "WHERE $FTS_TABLE MATCH ? ORDER BY score LIMIT ?",
                    arrayOf(match, limit.coerceIn(1, 64).toString())
                ).use { c ->
                    val out = ArrayList<LexicalHit>()
                    var rank = 1
                    while (c.moveToNext()) out += LexicalHit(c.getString(0).orEmpty(), rank++, c.getDouble(1))
                    out
                }
                if (hits.isNotEmpty()) {
                    lastSearchMode = "FTS5"
                    return hits
                }
            }
            val portable = portableIndexedSearch(database, query, limit) ?: return emptyList()
            if (portable.isNotEmpty()) lastSearchMode = "PORTABLE_INDEXED_BM25"
            portable
        } catch (t: Throwable) {
            Log.w(tag, "HYBRID_FTS_INDEXED_SEARCH_FAILED ${t.message}")
            emptyList()
        } finally {
            runCatching { database?.close() }
        }
    }

    fun search(query: String, limit: Int = 32): List<LexicalHit> {
        lastSearchMode = "NONE"
        val match = ftsMatchQuery(query)
        if (!indexFile.exists()) return emptyList()
        var database: SQLiteDatabase? = null
        return try {
            database = SQLiteDatabase.openDatabase(indexFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            if (match != null && ensureFtsReadable(database)) {
                val ftsHits = try {
                    database.rawQuery(
                        "SELECT doc_id, bm25($FTS_TABLE) AS score FROM $FTS_TABLE " +
                            "WHERE $FTS_TABLE MATCH ? ORDER BY score LIMIT ?",
                        arrayOf(match, limit.coerceIn(1, 100).toString())
                    ).use { c ->
                        val out = ArrayList<LexicalHit>()
                        var rank = 1
                        while (c.moveToNext()) out += LexicalHit(c.getString(0).orEmpty(), rank++, c.getDouble(1))
                        out
                    }
                } catch (t: Throwable) {
                    Log.w(tag, "HYBRID_FTS_SEARCH_FAILED ${t.message}")
                    emptyList()
                }
                if (ftsHits.isNotEmpty()) {
                    lastSearchMode = "FTS5"
                    return ftsHits
                }
            }

            val indexedFallback = portableIndexedSearch(database, query, limit)
            if (indexedFallback != null) {
                if (indexedFallback.isNotEmpty()) {
                    lastSearchMode = "PORTABLE_INDEXED_BM25"
                    Log.i(
                        tag,
                        "HYBRID_LEXICAL_FALLBACK source=PORTABLE_INDEXED_BM25 hits=${indexedFallback.size}"
                    )
                }
                return indexedFallback
            }

            // Recovery path for a damaged/pre-v4 sidecar. Normal initialisation rebuilds
            // such a sidecar before search, so this O(corpus) scan must not run per turn.
            val fallback = portableSearch(database, query, limit)
            if (fallback.isNotEmpty()) {
                lastSearchMode = "PORTABLE_BM25"
                Log.w(tag, "HYBRID_LEXICAL_RECOVERY source=PORTABLE_BM25 hits=${fallback.size}")
            }
            fallback
        } catch (t: Throwable) {
            Log.w(tag, "HYBRID_LEXICAL_SEARCH_FAILED ${t.message}")
            emptyList()
        } finally {
            runCatching { database?.close() }
        }
    }

    /**
     * Preserve token frequency for the portable scorer. The previous implementation
     * converted every document to a Set and therefore discarded term frequency,
     * document length and phrase structure. That made all chunks containing a single
     * surviving query term look almost equally relevant.
     */
    private fun lexicalTokens(raw: String, dropStopWords: Boolean, maxTokens: Int): List<String> {
        return KnowledgeLexicalText.tokens(raw, dropStopWords, maxTokens.coerceAtLeast(1))
    }

    private data class PortableCandidate(
        val id: String,
        val documentLength: Int,
        val termFrequency: IntArray,
        val orderedCoverage: Double,
        val exactPhrase: Boolean
    )

    private data class IndexedPortableCandidate(
        val id: String,
        val documentLength: Int,
        val documentOrder: Int,
        val termFrequency: IntArray,
        val positions: Array<IntArray?>
    )

    /**
     * Device-independent indexed BM25 fallback. The sidecar stores one posting per
     * document/term during installation, including token positions, so this produces
     * the same frequency, coverage, ordered-term and exact-phrase signals as the
     * legacy full-corpus scan without tokenising every document for every question.
     *
     * null means the current portable index is unavailable and the caller may use recovery;
     * an empty list is a valid indexed search with no lexical matches.
     */
    private fun portableIndexedSearch(
        database: SQLiteDatabase,
        query: String,
        limit: Int
    ): List<LexicalHit>? {
        if (!tableExists(database, PORTABLE_TERMS_TABLE) ||
            !tableExists(database, PORTABLE_DOC_STATS_TABLE)
        ) return null

        val queryTokens = lexicalTokens(query, dropStopWords = true, maxTokens = 24)
        val queryTerms = queryTokens.distinct()
        if (queryTerms.isEmpty()) return emptyList()

        val documentCount = metaLong(database, "portable_document_count")
        val totalDocumentLength = metaLong(database, "portable_total_document_length")
        if (documentCount <= 0L || totalDocumentLength <= 0L) return null

        val termIndex = queryTerms.withIndex().associate { it.value to it.index }
        val documentFrequency = IntArray(queryTerms.size)
        val candidates = LinkedHashMap<String, IndexedPortableCandidate>()
        val placeholders = queryTerms.joinToString(",") { "?" }
        val sql = "SELECT p.term,p.doc_id,p.tf,p.positions,s.doc_len,s.doc_order " +
            "FROM $PORTABLE_TERMS_TABLE p JOIN $PORTABLE_DOC_STATS_TABLE s ON s.doc_id=p.doc_id " +
            "WHERE term IN ($placeholders)"

        database.rawQuery(sql, queryTerms.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) {
                val term = cursor.getString(0).orEmpty()
                val index = termIndex[term] ?: continue
                val id = cursor.getString(1).orEmpty()
                if (id.isBlank()) continue
                val candidate = candidates.getOrPut(id) {
                    IndexedPortableCandidate(
                        id = id,
                        documentLength = cursor.getInt(4),
                        documentOrder = cursor.getInt(5),
                        termFrequency = IntArray(queryTerms.size),
                        positions = arrayOfNulls(queryTerms.size)
                    )
                }
                candidate.termFrequency[index] = cursor.getInt(2)
                candidate.positions[index] = parsePositions(cursor.getString(3).orEmpty())
                documentFrequency[index]++
            }
        }
        if (candidates.isEmpty()) return emptyList()

        val averageDocumentLength =
            (totalDocumentLength.toDouble() / documentCount.toDouble()).coerceAtLeast(1.0)
        val k1 = 1.2
        val b = 0.75
        val scored = ArrayList<Triple<Double, Int, String>>(candidates.size)
        candidates.values.forEach { candidate ->
            var bm25 = 0.0
            queryTerms.indices.forEach { i ->
                val tf = candidate.termFrequency[i].toDouble()
                if (tf > 0.0) {
                    val df = documentFrequency[i].coerceAtLeast(1).toDouble()
                    val idf = kotlin.math.ln(
                        1.0 + ((documentCount.toDouble() - df + 0.5) / (df + 0.5))
                    )
                    val lengthNorm = 1.0 - b + b *
                        (candidate.documentLength.toDouble() / averageDocumentLength)
                    bm25 += idf * ((tf * (k1 + 1.0)) / (tf + k1 * lengthNorm))
                }
            }

            val matchedTerms = candidate.termFrequency.count { it > 0 }
            val completeCoverage = matchedTerms == queryTerms.size
            val coverageBonus = if (completeCoverage) 0.35 else
                0.15 * matchedTerms.toDouble() / queryTerms.size.toDouble()
            val orderedCoverage = orderedCoverage(candidate.positions, queryTerms.size)
            val orderedBonus = 0.20 * orderedCoverage
            val phraseBonus = if (containsContiguousTerms(candidate.positions, queryTerms.size)) 0.40 else 0.0
            scored += Triple(
                bm25 + coverageBonus + orderedBonus + phraseBonus,
                candidate.documentOrder,
                candidate.id
            )
        }

        val ranked = scored.sortedWith(
            compareByDescending<Triple<Double, Int, String>> { it.first }.thenBy { it.second }
        ).take(limit.coerceIn(1, 100))
        if (ranked.isNotEmpty()) {
            Log.i(
                tag,
                "HYBRID_PORTABLE_INDEXED_BM25 docs=$documentCount candidates=${candidates.size} " +
                    "avgdl=${"%.1f".format(Locale.US, averageDocumentLength)} " +
                    "queryTerms=${queryTerms.joinToString(",").take(180)} hits=${ranked.size}"
            )
        }
        return ranked.mapIndexed { index, pair ->
            LexicalHit(pair.third, index + 1, -pair.first)
        }
    }

    private fun parsePositions(raw: String): IntArray {
        if (raw.isBlank()) return IntArray(0)
        return raw.split(',').mapNotNull { it.toIntOrNull() }.toIntArray()
    }

    private fun orderedCoverage(positions: Array<IntArray?>, termCount: Int): Double {
        if (termCount <= 0) return 0.0
        var nextPosition = 0
        var matches = 0
        for (index in 0 until termCount) {
            // The legacy scanner advances to end-of-document when the next query
            // term is absent, so no later term can restore ordered coverage.
            val found = positions[index]?.firstOrNull { it >= nextPosition } ?: break
            matches++
            nextPosition = found + 1
        }
        return matches.toDouble() / termCount.toDouble()
    }

    private fun containsContiguousTerms(positions: Array<IntArray?>, termCount: Int): Boolean {
        if (termCount < 2) return false
        val starts = positions.firstOrNull() ?: return false
        return starts.any { start ->
            (1 until termCount).all { offset ->
                positions[offset]?.binarySearch(start + offset)?.let { it >= 0 } == true
            }
        }
    }

    /**
     * Real BM25-style fallback for devices whose SQLite build does not expose FTS5.
     *
     * This remains completely domain-neutral: only the current query and the indexed
     * document text are used. We compute corpus document frequency for the query terms,
     * preserve term frequency, normalize by document length, and add small generic
     * bonuses for complete ordered-query coverage / exact query phrase matches.
     */
    private fun portableSearch(database: SQLiteDatabase, query: String, limit: Int): List<LexicalHit> {
        val queryTokens = lexicalTokens(query, dropStopWords = true, maxTokens = 24)
        val queryTerms = queryTokens.distinct()
        if (queryTerms.isEmpty()) return emptyList()

        val termIndex = queryTerms.withIndex().associate { it.value to it.index }
        val documentFrequency = IntArray(queryTerms.size)
        val candidates = ArrayList<PortableCandidate>()
        var documentCount = 0
        var totalDocumentLength = 0L

        fun containsContiguousTerms(tokens: List<String>, phrase: List<String>): Boolean {
            if (phrase.size < 2 || phrase.size > tokens.size) return false
            val lastStart = tokens.size - phrase.size
            for (start in 0..lastStart) {
                var matches = true
                for (offset in phrase.indices) {
                    if (tokens[start + offset] != phrase[offset]) {
                        matches = false
                        break
                    }
                }
                if (matches) return true
            }
            return false
        }

        database.rawQuery("SELECT doc_id,body FROM $DOCS_TABLE", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0).orEmpty()
                val body = c.getString(1).orEmpty()
                if (id.isBlank() || body.isBlank()) continue

                val bodyTokens = lexicalTokens(body, dropStopWords = true, maxTokens = 4096)
                if (bodyTokens.isEmpty()) continue
                documentCount++
                totalDocumentLength += bodyTokens.size.toLong()

                val tf = IntArray(queryTerms.size)
                bodyTokens.forEach { token -> termIndex[token]?.let { tf[it]++ } }
                var matchedTerms = 0
                tf.forEachIndexed { index, count ->
                    if (count > 0) {
                        documentFrequency[index]++
                        matchedTerms++
                    }
                }
                if (matchedTerms == 0) continue

                val exactPhrase = containsContiguousTerms(bodyTokens, queryTerms)

                // Ordered coverage measures how much of the query can be found in order
                // inside the passage, without requiring adjacency. It is useful for generic
                // questions such as "types of tender" while remaining topic-agnostic.
                var cursor = 0
                var orderedMatches = 0
                queryTerms.forEach { term ->
                    while (cursor < bodyTokens.size && bodyTokens[cursor] != term) cursor++
                    if (cursor < bodyTokens.size) {
                        orderedMatches++
                        cursor++
                    }
                }
                val orderedCoverage = orderedMatches.toDouble() / queryTerms.size.toDouble()

                candidates += PortableCandidate(
                    id = id,
                    documentLength = bodyTokens.size,
                    termFrequency = tf,
                    orderedCoverage = orderedCoverage,
                    exactPhrase = exactPhrase
                )
            }
        }

        if (documentCount <= 0 || candidates.isEmpty()) return emptyList()
        val averageDocumentLength =
            (totalDocumentLength.toDouble() / documentCount.toDouble()).coerceAtLeast(1.0)

        // Standard BM25 constants. Values are conventional and intentionally fixed;
        // there are no domain-specific thresholds or topic rules here.
        val k1 = 1.2
        val b = 0.75
        val scored = ArrayList<Pair<Double, String>>(candidates.size)
        candidates.forEach { candidate ->
            var bm25 = 0.0
            queryTerms.indices.forEach { i ->
                val tf = candidate.termFrequency[i].toDouble()
                if (tf > 0.0) {
                    val df = documentFrequency[i].coerceAtLeast(1).toDouble()
                    val idf = kotlin.math.ln(1.0 + ((documentCount - df + 0.5) / (df + 0.5)))
                    val lengthNorm = 1.0 - b + b *
                        (candidate.documentLength.toDouble() / averageDocumentLength)
                    bm25 += idf * ((tf * (k1 + 1.0)) / (tf + k1 * lengthNorm))
                }
            }

            val completeCoverage = candidate.termFrequency.count { it > 0 } == queryTerms.size
            val coverageBonus = if (completeCoverage) 0.35 else
                0.15 * candidate.termFrequency.count { it > 0 }.toDouble() / queryTerms.size.toDouble()
            val orderedBonus = 0.20 * candidate.orderedCoverage
            val phraseBonus = if (candidate.exactPhrase) 0.40 else 0.0
            val score = bm25 + coverageBonus + orderedBonus + phraseBonus
            scored += score to candidate.id
        }

        val cap = limit.coerceIn(1, 100)
        val ranked = scored
            .sortedByDescending { it.first }
            .take(cap)

        if (ranked.isNotEmpty()) {
            Log.i(
                tag,
                "HYBRID_PORTABLE_BM25 docs=$documentCount avgdl=${"%.1f".format(Locale.US, averageDocumentLength)} " +
                    "queryTerms=${queryTerms.joinToString(",").take(180)} hits=${ranked.size}"
            )
        }

        // LexicalHit retains the historical field name `bm25`; FTS5 bm25() returns
        // lower-is-better/negative values, so preserve that sign convention here.
        return ranked.mapIndexed { index, pair ->
            LexicalHit(pair.second, index + 1, -pair.first)
        }
    }

    private fun ensureFtsTable(database: SQLiteDatabase): Boolean {
        if (ftsAvailable == false) return false
        return try {
            database.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS $FTS_TABLE USING fts5(" +
                    "doc_id UNINDEXED, body, tokenize='unicode61 remove_diacritics 2')"
            )
            ftsAvailable = true
            true
        } catch (t: Throwable) {
            ftsAvailable = false
            Log.w(tag, "HYBRID_FTS5_UNAVAILABLE ${t.message}")
            false
        }
    }

    private fun tableExists(database: SQLiteDatabase, table: String): Boolean =
        database.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
            arrayOf(table)
        ).use { cursor -> cursor.moveToFirst() }

    private fun metaLong(database: SQLiteDatabase, key: String): Long =
        database.rawQuery(
            "SELECT v FROM $META_TABLE WHERE k=? LIMIT 1",
            arrayOf(key)
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).orEmpty().toLongOrNull() ?: 0L else 0L
        }

    private fun ensureFtsReadable(database: SQLiteDatabase): Boolean {
        if (ftsAvailable == false) return false
        return try {
            database.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
                arrayOf(FTS_TABLE)
            ).use { c -> c.moveToFirst() }.also { if (it) ftsAvailable = true }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Domain-neutral lexical query. Quoted OR terms avoid FTS operator injection and
     * preserve identifiers/numbers. Terms come exclusively from the user's text.
     */
    private fun ftsMatchQuery(raw: String): String? {
        val terms = KnowledgeLexicalText.tokens(raw, dropStopWords = true, maxTokens = 32).distinct()
        if (terms.isEmpty()) return null
        return terms.joinToString(" OR ") { term ->
            "\"${term.replace("\"", "\"\"")}\""
        }
    }

    fun stableSignature(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { digest.update(it.toByteArray(Charsets.UTF_8)); digest.update(0) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
