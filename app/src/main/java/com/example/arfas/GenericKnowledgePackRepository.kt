package com.example.arfas

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.PriorityQueue
import java.util.Locale

/**
 * Domain-neutral FixSight knowledge-pack repository.
 *
 * The repository never classifies a user's topic from domain keywords. A pack is selected explicitly for the
 * session; semantic similarity decides whether that selected pack has useful
 * evidence for an individual turn.
 *
 * Supported v1 schemas:
 *  - generic table: knowledge_chunks
 *  - Benchmark-1 compatibility table: procurement_chunks
 *
 * Future pack builders should emit knowledge_chunks with the same column contract.
 */
class GenericKnowledgePackRepository(
    private val context: Context,
    private val semanticEngine: SemanticSearchEngine
) {
    companion object {
        private const val RUNNING_KEY_SEPARATOR = "\u001f"
        /**
         * A heading on at least this many distinct pages of one document is a running header. The
         * ASHA pack's OCR headings ran over up to 46 pages ("Determining LMP and EDD" over the IFA
         * doses). Measured on 129 ASHA questions (answer in the evidence / in the first passage):
         * off 111/86, 5 pages 109/85, 8 pages 110/86 with "how to identify a malnourished child"
         * moving from the third passage to the first. 0 turns the rule off (measurement harness).
         */
        @Volatile var runningHeadingPages = 8
        /** Optional side table written by tools/kb_enrich.py. */
        const val ENRICHMENT_TABLE = "kb_chunk_enrichment"
        private const val TAG = "GenericKnowledgePack"
        private const val GENERIC_TABLE = "knowledge_chunks"
        /** Saved inspection of the installed pack file (counts and hash), keyed by path, size and date. */
        private const val INSPECT_CACHE_KEY = "inspect_cache_v1"
        /** Every file of the active pack starts with this name (database and prepared search files). */
        private const val ACTIVE_FILE_PREFIX = "active_generic_knowledge."
        /** Top passages that get their cut-off sentence start back ([withSentenceStart]). */
        private const val SENTENCE_START_HITS = 6
        private const val SENTENCE_START_MAX_CHARS = 300
        private const val COMPAT_TABLE = "procurement_chunks"
        private const val EMBEDDING_BYTES = 1536
        private const val DEFAULT_MIN_SCORE = 0.78f
        private const val DEFAULT_CANDIDATE_POOL = 48
        private const val DEFAULT_FINAL_LIMIT = 10
        private const val SMALL_FRAGMENT_TIE_WINDOW = 0.018f
        private const val DIVERSITY_TIE_WINDOW = 0.035f
        private const val IDENTIFIER_MAX_BOOST = 0.055f
        private const val IDENTIFIER_CONFIDENCE_WINDOW = 0.060f
        private const val LIFECYCLE_ACTIVE_BOOST = 0.012f
        private const val LIFECYCLE_SUPERSEDED_PENALTY = 0.045f
        private const val LIFECYCLE_DRAFT_PENALTY = 0.060f
        private const val CURRENT_VERSION_DATE_MAX_BOOST = 0.010f
        private const val DIRECT_QUERY_CONFIDENCE_FLOOR = 0.700f
        // Open WebUI-style two-stage hybrid retrieval: BM25 + vector candidates are
        // fused first, then the combined set is reranked by the existing E5 query
        // similarity. No additional model is introduced.
        private const val HYBRID_BM25_WEIGHT = 0.50f
        private const val HYBRID_VECTOR_WEIGHT = 0.50f
        private const val HYBRID_RRF_TIE_BONUS = 0.020f
        // Explicit source codes in pack metadata are a small tie-break only; semantic
        // relevance and lexical retrieval always remain the primary ranking signals.
        private const val SOURCE_IDENTITY_CODE_BOOST = 0.020f
    }

    enum class InstallResult { INSTALLED, UNSUPPORTED_SCHEMA, FAILED }

    /** One installed knowledge pack: the active one, or one kept in the library for quick switching. */
    data class InstalledPack(val packId: String, val displayName: String, val rowCount: Int, val active: Boolean)

    data class Status(
        val fileExists: Boolean = false,
        val schemaCompatible: Boolean = false,
        val valid: Boolean = false,
        val rowCount: Int = 0,
        val embeddingCount: Int = 0,
        val packId: String = "",
        val displayName: String = "",
        val tableName: String = ""
    )

    data class Hit(
        val chunkId: String,
        val originalChunkId: String?,
        val docId: String,
        val sourceName: String?,
        val pageNumber: Int?,
        val locator: String?,
        val headingContext: String?,
        val unitKind: String?,
        val content: String,
        val similarity: Float,
        val rankScore: Float,
        val specificityScore: Float = similarity,
        val smallFragment: Boolean,
        val sourceTitle: String? = null,
        val sourceAuthority: String? = null,
        val sourceUrl: String? = null,
        val registerUrl: String? = null,
        val identifierMatched: Boolean = false,
        val sourceCategory: String? = null,
        val sourceDocumentStatus: String? = null,
        val sourceEffectiveDate: String? = null,
        val sourcePublicationDate: String? = null,
        /** Last page included when the passage was extended with its following pages (null = own page only). */
        val pageEnd: Int? = null,
        /** The heading is a running header of its document, not this passage's section (see runningHeadings). */
        val runningHeading: Boolean = false
    )

    data class SourceMeta(
        val sourceName: String,
        val title: String? = null,
        val authority: String? = null,
        val sourceUrl: String? = null,
        val registerUrl: String? = null,
        val category: String? = null,
        val documentStatus: String? = null,
        val effectiveDate: String? = null,
        val publicationDate: String? = null,
        /** Pack-declared source precedence (knowledge_sources.precedence; 1 = primary); null = not declared. */
        val precedence: Int? = null
    )

    data class SearchResult(
        val hits: List<Hit> = emptyList(),
        val topSimilarity: Float = 0f,
        val confident: Boolean = false,
        val packId: String = "",
        val displayName: String = ""
    )

    data class ClauseSearchResult(
        val hits: List<Hit> = emptyList(),
        val clauseIdsByChunk: Map<String, Set<String>> = emptyMap(),
        val topSimilarity: Float = 0f,
        val packId: String = "",
        val displayName: String = ""
    )

    private val prefs = context.getSharedPreferences("fixsight_generic_kb", Context.MODE_PRIVATE)
    private val packDir = File(context.filesDir, "knowledge_packs").also { it.mkdirs() }
    private val dbFile = File(packDir, "active_generic_knowledge.db")
    private val candidateFile = File(packDir, "active_generic_knowledge.db.candidate")
    private val lexicalRetriever = HybridKnowledgeRetriever(
        File(packDir, "active_generic_knowledge.fts.db"),
        TAG
    )
    private var db: SQLiteDatabase? = null
    @Volatile private var status = Status()
    private val corpusStatistics = KbCorpusStatistics()
    private val corpusStatisticsFile = File(packDir, "active_generic_knowledge.corpusstats")

    fun isReady(): Boolean = db?.isOpen == true && status.valid
    fun currentStatus(): Status = status
    fun meaningfulLiteralSlots(query: String): Set<String> = corpusStatistics.meaningfulLiteralSlots(query)
    fun currentPackId(): String = status.packId
    fun currentDisplayName(): String = status.displayName

    /** Part C2: one query embedding for gold matching (the same call the KB uses). */
    suspend fun embedForGold(text: String): FloatArray? =
        runCatching { semanticEngine.embed(text) }.getOrNull()

    /**
     * Part C2: resolve verified-answer sources to passages from the installed pack.
     * chunk_id first; on an older DB, source_name + page_number + a quote substring.
     * Missing ids are skipped (logged) so pinning never invents evidence.
     */
    fun goldPassages(sources: List<ArogyaVerifiedAnswers.VerifiedSource>): List<ArogyaGoldPin.GoldPassage> {
        val database = db ?: return emptyList()
        val table = status.tableName
        if (table.isBlank() || !table.all { it.isLetterOrDigit() || it == '_' }) return emptyList()
        val out = ArrayList<ArogyaGoldPin.GoldPassage>()
        runCatching {
            val columns = columnsFor(database, table)
            if ("content" !in columns) return@runCatching
            val catalog = runCatching { loadSourceCatalog(database) }.getOrDefault(emptyMap())
            for (s in sources.take(4)) {
                val row = goldRowById(database, table, columns, s.chunkId)
                    ?: goldRowBySourcePage(database, table, columns, s)
                if (row == null) {
                    Log.i(TAG, "AROGYA_GOLD skip chunk=${s.chunkId} src=${s.sourceName} p=${s.page}")
                    continue
                }
                val title = catalog[row.sourceName]?.title?.takeIf { it.isNotBlank() }
                    ?: s.title.takeIf { it.isNotBlank() }
                    ?: row.sourceName ?: row.docId
                val locator = row.locator?.takeIf { it.isNotBlank() }
                    ?: row.page?.let { "p.$it" } ?: "p.${s.page}"
                // UI5: the official link, from the pack's own catalog first, else the verified answer's source.
                val url = catalog[row.sourceName]?.sourceUrl?.takeIf { it.startsWith("http") }
                    ?: s.sourceUrl.takeIf { it.startsWith("http") }.orEmpty()
                out += ArogyaGoldPin.GoldPassage(
                    chunkId = row.chunkId.ifBlank { "gold-${s.chunkId}" },
                    docId = row.docId.ifBlank { row.sourceName.orEmpty() },
                    title = title, locator = locator, text = row.content.trim(), url = url
                )
            }
        }.onFailure { Log.w(TAG, "AROGYA_GOLD resolve failed ${it.message}") }
        return out.filter { it.text.isNotBlank() }
    }

    private data class GoldRow(
        val chunkId: String, val docId: String, val sourceName: String?,
        val page: Int?, val locator: String?, val content: String
    )

    private fun goldRowCols(columns: Set<String>): String {
        fun sel(c: String, fb: String): String = if (c in columns) c else "$fb AS $c"
        return listOf(
            sel("chunk_id", "''"), sel("doc_id", "''"), sel("source_name", "NULL"),
            sel("page_number", "NULL"), sel("locator", "NULL"), sel("content", "''")
        ).joinToString(",")
    }

    private fun goldRowFromCursor(c: Cursor): GoldRow = GoldRow(
        chunkId = c.getString(0).orEmpty(), docId = c.getString(1).orEmpty(),
        sourceName = if (c.isNull(2)) null else c.getString(2),
        page = if (c.isNull(3)) null else c.getInt(3),
        locator = if (c.isNull(4)) null else c.getString(4),
        content = c.getString(5).orEmpty()
    )

    private fun goldRowById(database: SQLiteDatabase, table: String, columns: Set<String>, chunkId: Int): GoldRow? {
        if ("chunk_id" in columns) database.rawQuery(
            "SELECT ${goldRowCols(columns)} FROM $table WHERE chunk_id = ? LIMIT 1",
            arrayOf(chunkId.toString())
        ).use { c -> if (c.moveToNext()) return goldRowFromCursor(c) }
        // Older packs key rows by id (verified chunk_id = procurement_chunks.id).
        if ("id" in columns) database.rawQuery(
            "SELECT ${goldRowCols(columns)} FROM $table WHERE id = ? LIMIT 1",
            arrayOf(chunkId.toString())
        ).use { c -> if (c.moveToNext()) return goldRowFromCursor(c) }
        return null
    }

    private fun goldRowBySourcePage(
        database: SQLiteDatabase, table: String, columns: Set<String>,
        s: ArogyaVerifiedAnswers.VerifiedSource
    ): GoldRow? {
        if ("source_name" !in columns || "page_number" !in columns) return null
        if (s.sourceName.isBlank()) return null
        val needle = s.quote.replace(Regex("\\s+"), " ").trim().lowercase(Locale.ROOT).take(80)
        if (needle.length < 24) return null
        database.rawQuery(
            "SELECT ${goldRowCols(columns)} FROM $table WHERE source_name = ? AND page_number = ? LIMIT 12",
            arrayOf(s.sourceName, s.page.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val row = goldRowFromCursor(c)
                if (row.content.replace(Regex("\\s+"), " ").lowercase(Locale.ROOT).contains(needle)) return row
            }
        }
        return null
    }

    suspend fun inspectInstalled(): Status = withContext(Dispatchers.IO) {
        inspectFile(dbFile).also { inspected ->
            status = inspected.copy(
                packId = prefs.getString("pack_id", inspected.packId).orEmpty().ifBlank { inspected.packId },
                displayName = prefs.getString("display_name", inspected.displayName).orEmpty().ifBlank { inspected.displayName }
            )
        }
    }

    suspend fun initialise(): Boolean = withContext(Dispatchers.IO) {
        close()
        val started = System.currentTimeMillis()
        val inspected = inspectInstalled()
        val inspectedAt = System.currentTimeMillis()
        if (!inspected.valid) return@withContext false
        return@withContext try {
            db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            status = inspected
            ensureLexicalIndex(db!!, status)
            val lexicalAt = System.currentTimeMillis()
            // Phase 1.4 profile is optional: a failure leaves the previous retrieval path in use.
            runCatching { ensurePhase14Profile(db!!, status) }
                .onFailure { Log.w(TAG, "KB_PHASE14_PROFILE_FAILED ${it.javaClass.simpleName}:${it.message}"); phase14Profile = null }
            val done = System.currentTimeMillis()
            Log.i(
                TAG,
                "KB_STARTUP_TIMING inspect=${inspectedAt - started}ms lexical=${lexicalAt - inspectedAt}ms " +
                    "profile=${done - lexicalAt}ms total=${done - started}ms"
            )
            Log.i(TAG, "GENERIC_KB_READY pack=${status.packId} rows=${status.rowCount} table=${status.tableName}")
            // Start the re-ranker's on-device check now (background, never blocks), so it is verified
            // (or its saved verdict loaded) before the first question instead of during it.
            if (semanticEngine.isReady) runCatching { reranker.ready() }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "GENERIC_KB_OPEN_FAILED ${t.message}", t)
            close()
            false
        }
    }

    /**
     * Installs a pre-embedded generic knowledge database. The copy is validated
     * before replacing the live file. Unsupported schemas are reported distinctly
     * so Phase1FeatureBundle can fall back to the legacy ANM installer.
     */
    suspend fun installFromUri(uri: Uri, displayNameHint: String = ""): InstallResult =
        withContext(Dispatchers.IO) {
            runCatching { candidateFile.delete() }
            val copied = try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(candidateFile).use { output -> input.copyTo(output, 1024 * 1024) }
                } ?: return@withContext InstallResult.FAILED
                candidateFile.exists() && candidateFile.length() > 0L
            } catch (t: Throwable) {
                Log.e(TAG, "GENERIC_KB_COPY_FAILED ${t.message}", t)
                false
            }
            if (!copied) return@withContext InstallResult.FAILED

            val candidateStatus = inspectFile(candidateFile)
            if (!candidateStatus.schemaCompatible) {
                candidateFile.delete()
                return@withContext InstallResult.UNSUPPORTED_SCHEMA
            }
            if (!candidateStatus.valid) {
                Log.w(TAG, "GENERIC_KB_REJECT rows=${candidateStatus.rowCount} embeddings=${candidateStatus.embeddingCount}")
                candidateFile.delete()
                return@withContext InstallResult.FAILED
            }

            close()
            // The previous pack is kept in the library (with its prepared search files) so the user
            // can switch back in seconds. Installing a pack already in the library replaces that copy.
            val stashed = if (dbFile.exists()) stashActivePack() else null
            if (dbFile.exists()) {
                // Could not move the current pack aside: keep it, drop the new one.
                candidateFile.delete()
                initialise()
                return@withContext InstallResult.FAILED
            }
            libraryEntry(candidateStatus.packId)?.takeIf { it != stashed }?.deleteRecursively()
            if (!candidateFile.renameTo(dbFile)) {
                candidateFile.delete()
                stashed?.let { restoreFromLibrary(it) }
                initialise()
                return@withContext InstallResult.FAILED
            }

            val resolvedName = displayNameHint.ifBlank { resolveDisplayName(uri) }
                .ifBlank { "Custom Knowledge Pack" }
            val packId = candidateStatus.packId.ifBlank { sha256Prefix(dbFile) }
            prefs.edit()
                .putString("pack_id", packId)
                .putString("display_name", resolvedName.substringBeforeLast('.').take(120))
                .apply()

            val reopened = initialise()
            if (!reopened) {
                close()
                activeFiles().forEach { it.delete() }
                stashed?.let { restoreFromLibrary(it) }
                initialise()
                return@withContext InstallResult.FAILED
            }
            Log.i(TAG, "GENERIC_KB_INSTALL_SUCCESS pack=$packId rows=${status.rowCount} kept=${stashed?.name ?: "-"}")
            InstallResult.INSTALLED
        }

    /**
     * Generic-pack retrieval is semantic-only. No finite query vocabulary, domain
     * keyword list, stop-word list, or keyword bonus participates in ranking.
     * Exact identifiers still influence the E5 query embedding naturally; source
     * diversity is applied after semantic ranking so the final evidence packet can
     * integrate corroborating material from multiple documents.
     */

    private fun loadSourceCatalog(database: SQLiteDatabase): Map<String, SourceMeta> {
        val exists = database.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='knowledge_sources' LIMIT 1", null
        ).use { it.moveToFirst() }
        if (!exists) return emptyMap()
        val cols = columnsFor(database, "knowledge_sources")
        if ("source_name" !in cols) return emptyMap()
        fun col(name: String): String = if (name in cols) name else "NULL AS $name"
        fun firstCol(vararg names: String, alias: String): String {
            val found = names.firstOrNull { it in cols }
            return if (found != null) "$found AS $alias" else "NULL AS $alias"
        }
        val sql = "SELECT " + listOf(
            "source_name", col("title"), col("authority"), col("source_url"), col("register_url"),
            col("category"),
            firstCol("document_status", "authority_status", "lifecycle_status", alias = "document_status"),
            firstCol("effective_date", "effective_from", alias = "effective_date"),
            firstCol("publication_date", "issue_date", "issued_date", alias = "publication_date"),
            firstCol("precedence", "source_precedence", alias = "precedence")
        ).joinToString(",") + " FROM knowledge_sources"
        val out = linkedMapOf<String, SourceMeta>()
        database.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0).orEmpty().trim()
                if (name.isBlank()) continue
                out[name] = SourceMeta(
                    sourceName = name,
                    title = if (c.isNull(1)) null else c.getString(1),
                    authority = if (c.isNull(2)) null else c.getString(2),
                    sourceUrl = if (c.isNull(3)) null else c.getString(3)?.takeIf { it.startsWith("http") },
                    registerUrl = if (c.isNull(4)) null else c.getString(4)?.takeIf { it.startsWith("http") },
                    category = if (c.isNull(5)) null else c.getString(5),
                    documentStatus = if (c.isNull(6)) null else c.getString(6),
                    effectiveDate = if (c.isNull(7)) null else c.getString(7),
                    publicationDate = if (c.isNull(8)) null else c.getString(8),
                    precedence = if (c.isNull(9)) null else runCatching { c.getInt(9) }.getOrNull()?.takeIf { it > 0 }
                )
            }
        }
        return out
    }


    /**
     * Domain-neutral exact-reference signals. These are not topic keywords: they
     * simply recognize identifiers containing digits (for example RFC9110, 7.2.1,
     * Rule 151, SOP-17, DAP 2020). They can only boost a candidate that literally
     * contains the same identifier and therefore cannot create evidence ex nihilo.
     */
    private data class SourceIdentityHint(
        val terms: Set<String> = emptySet(),
        val preferredTerms: Set<String> = emptySet()
    ) {
        val active: Boolean get() = terms.isNotEmpty()
    }

    /**
     * Resolve an explicitly named source/document family from the installed pack's
     * own metadata. This deliberately does NOT contain a procurement vocabulary.
     * Only an explicit acronym/code can become a source hint. It is a small ranking
     * boost, never a candidate filter or a title-word heuristic.
     */
    private fun sourceIdentityHint(query: String, catalog: Map<String, SourceMeta>): SourceIdentityHint {
        if (query.isBlank() || catalog.isEmpty()) return SourceIdentityHint()
        fun explicitCodes(text: String): Set<String> = Regex("""\b[A-Z]{2,8}(?:[-_./]\d+[A-Z0-9._/-]*)?\b""")
            .findAll(text).map { it.value.lowercase(Locale.US) }.toSet()

        fun sourceCodes(meta: SourceMeta): Set<String> {
            val raw = listOf(meta.sourceName, meta.title.orEmpty(), meta.category.orEmpty()).joinToString(" ")
            return explicitCodes(raw)
        }
        val queryCodes = explicitCodes(query)
        if (queryCodes.isEmpty()) return SourceIdentityHint()
        val catalogCodes = catalog.values.flatMapTo(linkedSetOf()) { meta -> sourceCodes(meta) }
        val matched = queryCodes.intersect(catalogCodes)
        if (matched.isEmpty()) return SourceIdentityHint()
        return SourceIdentityHint(matched.toSet())
    }

    private fun sourceIdentityText(hit: Hit): String = listOf(
        hit.sourceName.orEmpty(), hit.sourceTitle.orEmpty(), hit.sourceCategory.orEmpty()
    ).joinToString(" ")

    private fun sourceIdentityMatch(hit: Hit, hint: SourceIdentityHint): Pair<Int, Int> {
        if (!hint.active) return 0 to 0
        val source = sourceIdentityText(hit)
        val acronyms = Regex("""\b[A-Z]{2,8}(?:[-_./]\d+[A-Z0-9._/-]*)?\b""")
            .findAll(source).map { it.value.lowercase(Locale.US) }.toSet()
        fun containsTerm(term: String): Boolean {
            return term.lowercase(Locale.US) in acronyms
        }
        val preferredSet = hint.preferredTerms.ifEmpty { hint.terms }
        val preferredMatches = preferredSet.count(::containsTerm)
        val allMatches = hint.terms.count(::containsTerm)
        return preferredMatches to allMatches
    }

    private fun sourceIdentityAdjustment(hit: Hit, hint: SourceIdentityHint): Float {
        if (!hint.active) return 0f
        val (preferredMatches, allMatches) = sourceIdentityMatch(hit, hint)
        return if (preferredMatches > 0 || allMatches > 0) SOURCE_IDENTITY_CODE_BOOST else 0f
    }

    private fun identifierSignals(query: String): Set<String> {
        val raw = query.trim()
        if (raw.isBlank()) return emptySet()
        val out = linkedSetOf<String>()
        val alphaNumeric = Regex("(?i)\\b(?=[A-Z0-9./()_-]{3,}\\b)(?=[A-Z0-9./()_-]*[A-Z])(?=[A-Z0-9./()_-]*\\d)[A-Z0-9][A-Z0-9./()_-]{2,31}\\b")
        val hierarchy = Regex("\\b\\d+(?:\\.\\d+){1,5}(?:\\([A-Za-z0-9]+\\))*\\b")
        val labelNumber = Regex("(?i)\\b[A-Z][A-Z]{1,24}\\s+(?:No\\.?\\s*)?[A-Z]?\\d[A-Z0-9./()_-]*\\b")
        // Generic numbered references must outrank incidental numeric matches
        // (for example “GFR 16” forms when the user asked for Rule 161).
        val labelledNumber = Regex("(?i)\\b(?:rule|section|clause|article|chapter|part)\\s+\\d+[A-Z0-9./()_-]*\\b")
        for (m in alphaNumeric.findAll(raw)) out += normalizeIdentifier(m.value)
        for (m in hierarchy.findAll(raw)) out += normalizeIdentifier(m.value)
        for (m in labelNumber.findAll(raw)) out += normalizeIdentifier(m.value)
        for (m in labelledNumber.findAll(raw)) out += normalizeIdentifier(m.value)
        return out.filter { it.length >= 3 }.toSet()
    }

    private fun normalizeIdentifier(value: String): String = value
        .lowercase(Locale.US)
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun identifierBoost(signals: Set<String>, haystack: String): Pair<Float, Boolean> {
        if (signals.isEmpty()) return 0f to false
        val normalized = normalizeIdentifier(haystack)
        val matched = signals.count { it.isNotBlank() && normalized.contains(it) }
        if (matched <= 0) return 0f to false
        val ratio = matched.toFloat() / signals.size.coerceAtLeast(1)
        val boost = (0.025f + (IDENTIFIER_MAX_BOOST - 0.025f) * ratio).coerceAtMost(IDENTIFIER_MAX_BOOST)
        return boost to true
    }

    private fun lifecycleAdjustment(status: String?, query: String, @Suppress("UNUSED_PARAMETER") identifierMatched: Boolean): Float {
        val clean = status?.trim()?.lowercase(Locale.US).orEmpty()
        if (clean.isBlank()) return 0f
        val q = query.lowercase(Locale.US)
        val explicitlyHistorical = listOf("historical", "superseded", "old version", "previous version", "draft").any { q.contains(it) }
        // An identifier match means "this clause/rule is relevant", not "the user
        // requested an obsolete version". Lifecycle protection is bypassed only when
        // the query explicitly asks for historical/superseded material.
        if (explicitlyHistorical) return 0f
        return when {
            listOf("draft", "proposed", "consultation").any { clean.contains(it) } -> -LIFECYCLE_DRAFT_PENALTY
            listOf("superseded", "repealed", "obsolete", "historical", "withdrawn").any { clean.contains(it) } -> -LIFECYCLE_SUPERSEDED_PENALTY
            listOf("active", "current", "in force", "effective").any { clean.contains(it) } -> LIFECYCLE_ACTIVE_BOOST
            else -> 0f
        }
    }

    /** Generic linguistic history intent; never uses domain vocabulary. */
    private fun explicitlyHistoricalQuery(query: String): Boolean {
        val q = query.lowercase(Locale.US)
        return listOf("historical", "superseded", "old version", "previous version", "earlier version", "draft").any { q.contains(it) }
    }

    private fun lifecycleClass(hit: Hit): Int {
        val status = hit.sourceDocumentStatus?.trim()?.lowercase(Locale.US).orEmpty()
        return when {
            listOf("active", "current", "in force", "effective").any { status.contains(it) } -> 2
            listOf("superseded", "repealed", "obsolete", "historical", "withdrawn", "draft", "proposed", "consultation").any { status.contains(it) } -> 0
            else -> 1
        }
    }

    /**
     * Lifecycle is an authority constraint, not merely a tiny score hint. When an
     * active/current source is semantically and directly near the best candidate,
     * obsolete/draft variants must not outrank it for a current request.
     */
    private fun authorityFiltered(candidates: List<Hit>, query: String): List<Hit> {
        if (candidates.isEmpty() || explicitlyHistoricalQuery(query)) return candidates
        val bestDirect = candidates.maxOf { it.specificityScore }
        val bestRank = candidates.maxOf { it.rankScore }
        val activeNear = candidates.filter { hit ->
            lifecycleClass(hit) == 2 &&
                hit.specificityScore >= bestDirect - 0.035f &&
                hit.rankScore >= bestRank - 0.080f
        }
        if (activeNear.isEmpty()) return candidates
        return candidates.filter { hit ->
            lifecycleClass(hit) != 0 ||
                hit.specificityScore > activeNear.maxOf { it.specificityScore } + 0.045f
        }
    }

    /**
     * Tiny deterministic tie-breaker for current/effective versions. It is deliberately
     * bounded so semantic relevance remains dominant. Dates are compared only when
     * the query is not explicitly asking for an older/historical version.
     */
    private fun dateAdjustment(meta: SourceMeta?, query: String): Float {
        if (meta == null || explicitlyHistoricalQuery(query)) return 0f
        val status = meta.documentStatus?.lowercase(Locale.US).orEmpty()
        if (listOf("superseded", "repealed", "obsolete", "historical", "withdrawn", "draft", "proposed").any { status.contains(it) }) return 0f
        val raw = meta.effectiveDate?.takeIf { it.isNotBlank() } ?: meta.publicationDate.orEmpty()
        val m = Regex("\\b(20\\d{2})[-/.](0?[1-9]|1[0-2])[-/.](0?[1-9]|[12]\\d|3[01])\\b").find(raw) ?: return 0f
        val y = m.groupValues[1].toIntOrNull() ?: return 0f
        val mo = m.groupValues[2].toIntOrNull() ?: 1
        val d = m.groupValues[3].toIntOrNull() ?: 1
        val ordinal = (y - 2000).coerceIn(0, 50) * 372 + mo.coerceIn(1, 12) * 31 + d.coerceIn(1, 31)
        return (ordinal / (50f * 372f)) * CURRENT_VERSION_DATE_MAX_BOOST
    }

    private fun sourceOrderKey(hit: Hit): Triple<Int, Int, String> {
        val page = hit.pageNumber ?: Int.MAX_VALUE / 2
        val numeric = Regex("\\d+").findAll(hit.originalChunkId ?: hit.chunkId).lastOrNull()?.value?.toIntOrNull() ?: Int.MAX_VALUE / 2
        return Triple(page, numeric, hit.locator.orEmpty())
    }

    /**
     * Procedure retrieval must favor one coherent document and source order instead
     * of maximizing cross-document diversity. This is task-shape routing, not domain routing.
     */
    private fun selectForIntent(
        candidates: List<Hit>,
        limit: Int,
        intent: SemanticQueryIntent?,
        procedureTargetTerms: Set<String> = emptySet(),
        procedureActionToken: String? = null,
        userQuery: String = ""
    ): List<Hit> {
        if (candidates.isEmpty()) return emptyList()
        val ranked = authorityFiltered(candidates, userQuery).sortedByDescending { it.rankScore }
        if (intent == null) return ranked.take(limit.coerceAtLeast(1))
        if (intent != SemanticQueryIntent.PROCEDURE) {
            // Task-shaped retrieval improves recall, but source ownership must remain
            // specific to the user's exact request. Among semantically near-tied
            // candidates, prefer the passage with the strongest direct-query score.
            // This is domain-neutral and prevents generic task words such as
            // "comparison" or "categories" from choosing the wrong source section.
            val nearBest = ranked.filter { it.rankScore >= ranked.first().rankScore - 0.055f }
            val specificityOrdered = nearBest.sortedWith(
                compareByDescending<Hit> { it.specificityScore }
                    .thenByDescending { it.rankScore }
            ) + ranked.filter { candidate -> nearBest.none { it.chunkId == candidate.chunkId } }

            // Comparisons genuinely benefit from independent documents, but diversity
            // starts from the most user-specific near-tied evidence rather than the
            // task-shaped embedding winner alone.
            if (intent == SemanticQueryIntent.COMPARISON) return diversify(specificityOrdered, limit)

            val anchor = specificityOrdered.first()
            val anchorDoc = anchor.docId.ifBlank { anchor.sourceName.orEmpty() }
            val maxCoherent = when (intent) {
                SemanticQueryIntent.VALUE_LOOKUP -> 3
                SemanticQueryIntent.ENUMERATION -> 8
                SemanticQueryIntent.PROCEDURE -> 12
                else -> 5
            }.coerceAtMost(limit.coerceAtLeast(1))
            val result = mutableListOf(anchor)
            if (anchorDoc.isNotBlank() && result.size < maxCoherent) {
                ranked.asSequence()
                    .filter { it.chunkId != anchor.chunkId }
                    .filter { (it.docId.ifBlank { it.sourceName.orEmpty() }) == anchorDoc }
                    .filter { it.rankScore >= anchor.rankScore - 0.060f }
                    .take(maxCoherent - result.size)
                    .forEach { result += it }
            }
            // One genuinely near-tied corroborating document is allowed, but only
            // when there is still room.  This preserves useful corroboration without
            // turning a broad question into a five-document source collage.
            if (result.size < maxCoherent) {
                ranked.firstOrNull { hit ->
                    hit.chunkId !in result.map { it.chunkId }.toSet() &&
                        (hit.docId.ifBlank { hit.sourceName.orEmpty() }) != anchorDoc &&
                        hit.rankScore >= anchor.rankScore - (if (intent == SemanticQueryIntent.VALUE_LOOKUP || intent == SemanticQueryIntent.EXPLANATION || intent == SemanticQueryIntent.GENERAL) 0.030f else 0.015f)
                }?.let { result += it }
            }
            return result.take(maxCoherent)
        }


        // Semantic similarity still defines the candidate pool. Only procedure anchor
        // choice gets this extra target check, preventing a broad parent procedure from
        // beating a slightly lower-ranked chunk that actually names the requested action/object.
        fun searchableText(hit: Hit): String = buildString {
            append(hit.sourceTitle.orEmpty()).append(' ')
            append(hit.sourceName.orEmpty()).append(' ')
            append(hit.headingContext.orEmpty()).append(' ')
            append(hit.locator.orEmpty()).append(' ')
            append(hit.content)
        }.lowercase(Locale.US)

        fun tokenSeen(text: String, token: String): Boolean =
            Regex("(?i)\\b${Regex.escape(token)}(?:s|d|ed|ing)?\\b").containsMatchIn(text)

        val anchor = if (procedureTargetTerms.isNotEmpty() || !procedureActionToken.isNullOrBlank()) {
            // Source selection is semantic-first. Restrict to candidates near the best
            // retrieval score, then prefer the chunk that is most specific to the user's
            // exact request. Dynamic action/target coverage only breaks near ties. This
            // avoids selecting a broad procurement manual merely because it contains the
            // same nouns somewhere in the passage.
            val nearBest = ranked.filter { it.rankScore >= ranked.first().rankScore - 0.070f }
            fun metadataText(hit: Hit): String = buildString {
                append(hit.sourceTitle.orEmpty()).append(' ')
                append(hit.sourceName.orEmpty()).append(' ')
                append(hit.headingContext.orEmpty()).append(' ')
                append(hit.locator.orEmpty())
            }.lowercase(Locale.US)
            nearBest.maxWithOrNull(
                compareBy<Hit> { hit ->
                    val text = searchableText(hit)
                    if (!procedureActionToken.isNullOrBlank() && tokenSeen(text, procedureActionToken)) 1 else 0
                }.thenBy { hit ->
                    val meta = metadataText(hit)
                    procedureTargetTerms.count { tokenSeen(meta, it) }
                }.thenBy { hit ->
                    val text = searchableText(hit)
                    procedureTargetTerms.count { tokenSeen(text, it) }
                }.thenBy { it.specificityScore }
                    .thenBy { it.rankScore }
            ) ?: ranked.first()
        } else ranked.maxByOrNull { it.specificityScore } ?: ranked.first()

        val anchorDoc = anchor.docId.ifBlank { anchor.sourceName.orEmpty() }
        val sameDoc = ranked.filter { (it.docId.ifBlank { it.sourceName.orEmpty() }) == anchorDoc }
        if (sameDoc.isEmpty()) return listOf(anchor)

        // Keep only reasonably relevant material from the anchor document, then order
        // it as the source presents it. This prevents step 7 appearing before step 3.
        val floor = anchor.rankScore - 0.11f
        val ordered = sameDoc
            .filter { it.rankScore >= floor }
            .sortedWith(compareBy<Hit>({ sourceOrderKey(it).first }, { sourceOrderKey(it).second }, { sourceOrderKey(it).third }))
        if (ordered.isEmpty()) return listOf(anchor)
        val anchorIndex = ordered.indexOfFirst { it.chunkId == anchor.chunkId }.let { if (it >= 0) it else 0 }
        val window = limit.coerceAtLeast(1)
        val start = (anchorIndex - window / 2).coerceAtLeast(0).coerceAtMost((ordered.size - window).coerceAtLeast(0))
        return ordered.drop(start).take(window)
    }

    /** Build a writable FTS5 sidecar from the selected pack without mutating it. */
    private fun ensureLexicalIndex(database: SQLiteDatabase, current: Status): Boolean {
        val table = current.tableName
        if (table.isBlank()) return false
        val columns = columnsFor(database, table)
        if ("content" !in columns || "chunk_id" !in columns) return false
        val enrichment = hasChunkEnrichment(database)
        val signature = lexicalRetriever.stableSignature(
            if (enrichment) "generic_heading_body_questions_v3" else "generic_heading_body_v2",
            current.packId,
            current.rowCount.toString(),
            dbFile.length().toString(),
            dbFile.lastModified().toString(),
            table
        )
        val lexicalCurrent = lexicalRetriever.isCurrent(signature)
        if (lexicalCurrent && corpusStatistics.isCurrent(signature)) return true
        // Saved statistics for this exact pack: no need to re-read and re-tokenise every passage.
        if (lexicalCurrent && corpusStatistics.load(corpusStatisticsFile, signature)) {
            Log.i(TAG, "KB_CORPUS_STATS_CACHED")
            return true
        }
        val documents = ArrayList<HybridKnowledgeRetriever.Document>(current.rowCount.coerceAtLeast(16))
        val heading = if ("heading_context" in columns) "heading_context" else "NULL"
        // Build-time likely questions (optional side table) are searchable text only; the JSON
        // punctuation is ignored by the tokenizer, so the stored array is appended as is.
        val lexicalSql = if (enrichment) {
            "SELECT t.chunk_id,${if (heading == "NULL") "NULL" else "t.$heading"},t.content,e.questions_json FROM $table t " +
                "LEFT JOIN $ENRICHMENT_TABLE e ON e.chunk_id = t.chunk_id WHERE t.content IS NOT NULL"
        } else {
            "SELECT chunk_id,$heading,content,NULL FROM $table WHERE content IS NOT NULL"
        }
        database.rawQuery(lexicalSql, null).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0).orEmpty()
                if (id.isBlank()) continue
                val text = buildString {
                    if (!c.isNull(1)) append(c.getString(1)).append(' ')
                    append(c.getString(2).orEmpty())
                    if (!c.isNull(3)) append(' ').append(c.getString(3).orEmpty())
                }
                if (text.isNotBlank()) documents += HybridKnowledgeRetriever.Document(id, text)
            }
        }
        corpusStatistics.rebuild(signature, documents)
        corpusStatistics.save(corpusStatisticsFile)
        return if (lexicalCurrent) true else lexicalRetriever.ensureIndex(signature, documents.asSequence())
    }

    suspend fun search(
        query: String,
        candidatePool: Int = DEFAULT_CANDIDATE_POOL,
        finalLimit: Int = DEFAULT_FINAL_LIMIT,
        minScore: Float = DEFAULT_MIN_SCORE,
        intent: SemanticQueryIntent? = null,
        procedureTargetTerms: Set<String> = emptySet(),
        procedureActionToken: String? = null,
        specificityQuery: String? = null,
        queryViews: List<String> = emptyList()
    ): SearchResult = withContext(Dispatchers.IO) {
        val database = db ?: return@withContext SearchResult()
        if (!isReady() || query.isBlank() || !semanticEngine.isReady) return@withContext SearchResult()
        // Multi-view retrieval stays entirely local. The task-shaped query remains the
        // primary view, while exact/current-turn clause views improve recall for broad
        // questions without lowering the grounding confidence threshold.
        val semanticQueries = (listOf(query.trim()) + queryViews.map { it.trim() })
            .filter { it.isNotBlank() }
            .distinct()
            .distinct()
        val queryEmbeddings = semanticQueries.mapNotNull { q ->
            semanticEngine.embed("query: $q")?.takeIf { it.size == 384 }?.let { q to it }
        }
        if (queryEmbeddings.isEmpty()) return@withContext SearchResult()
        // For procedure turns, keep a second embedding of the user's exact request.
        // The task-shaped retrieval query improves recall, while this direct embedding
        // prevents a broad manual passage from outranking a chunk that more directly
        // answers the user's requested operation. No domain/topic keyword table is used.
        val directEmbedding = specificityQuery?.trim()?.takeIf { it.isNotBlank() }
            ?.let { semanticEngine.embed("query: $it") }
        val identifiers = identifierSignals(query)

        // Independent lexical arm: FTS5/BM25 searches the exact user wording. The
        // source pack remains read-only; a sidecar index is rebuilt only when the
        // selected pack signature changes.
        ensureLexicalIndex(database, status)
        // The portable lexical arm must see the same task-shaped evidence request as the
        // semantic arm. This is still current-turn-only and domain-neutral: callers may
        // provide generic views such as definition/enumeration/procedure, but no history
        // or topic-specific keyword table is injected here.
        val lexicalQuery = (listOf(specificityQuery ?: query) + queryViews)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
        val lexicalHits = lexicalRetriever.search(lexicalQuery, candidatePool.coerceAtLeast(16))
        val lexicalRankById = lexicalHits.associate { it.id to it.rank }
        val lexicalMode = lexicalRetriever.searchMode()

        val poolSize = candidatePool.coerceAtLeast(finalLimit.coerceAtLeast(1))
        val heap = PriorityQueue<Hit>(compareBy { it.rankScore })
        val lexicalCandidates = LinkedHashMap<String, Hit>()
        val table = status.tableName
        if (table.isBlank()) return@withContext SearchResult()

        val columns = columnsFor(database, table)
        val catalog = loadSourceCatalog(database)
        val sourceHint = sourceIdentityHint(specificityQuery ?: query, catalog)
        if (sourceHint.active) {
            Log.i(TAG, "GENERIC_KB_SOURCE_HINT terms=${sourceHint.terms.joinToString(",")} preferred=${sourceHint.preferredTerms.joinToString(",")}")
        }
        fun select(column: String, fallback: String = "NULL"): String =
            if (column in columns) column else "$fallback AS $column"
        val directUrlColumn = listOf("source_url", "document_url", "final_url", "url").firstOrNull { it in columns }

        val sql = "SELECT " + listOf(
            select("chunk_id", "''"),
            select("original_chunk_id"),
            select("doc_id", "''"),
            select("source_name"),
            select("page_number"),
            select("locator"),
            select("heading_context"),
            select("unit_kind"),
            select("content", "''"),
            select("embedding"),
            select("small_fragment", "0"),
            if (directUrlColumn != null) directUrlColumn else "NULL AS source_url"
        ).joinToString(",") + " FROM $table WHERE embedding IS NOT NULL"

        database.rawQuery(sql, null).use { cursor ->
            while (cursor.moveToNext()) {
                val blob = cursor.getBlob(9) ?: continue
                val vector = semanticEngine.blobToFloatArray(blob) ?: continue
                val semantic = queryEmbeddings.maxOf { (_, embedding) ->
                    semanticEngine.cosineSimilarity(embedding, vector)
                }
                val specificity = directEmbedding?.let { semanticEngine.cosineSimilarity(it, vector) } ?: semantic
                val sourceName = if (cursor.isNull(3)) null else cursor.getString(3)
                val heading = if (cursor.isNull(6)) null else cursor.getString(6)
                val content = cursor.getString(8).orEmpty()
                if (content.isBlank()) continue
                val meta = sourceName?.let { catalog[it] }
                val identifierHaystack = buildString {
                    append(sourceName.orEmpty()).append(' ')
                    append(meta?.title.orEmpty()).append(' ')
                    append(heading.orEmpty()).append(' ')
                    append(if (cursor.isNull(5)) "" else cursor.getString(5).orEmpty()).append(' ')
                    append(content)
                }
                val (idBoost, idMatched) = identifierBoost(identifiers, identifierHaystack)
                // The best semantic view may come from a contextual/planner expansion,
                // but the literal current user query must remain visible in ranking.
                // Blend a modest direct-query component instead of letting an expansion
                // completely own the candidate order.
                val anchoredSemantic = (semantic * 0.82f) + (specificity * 0.18f)
                val directUrl = if (cursor.isNull(11)) null else cursor.getString(11)
                val provisional = Hit(
                    chunkId = cursor.getString(0).orEmpty(),
                    originalChunkId = if (cursor.isNull(1)) null else cursor.getString(1),
                    docId = cursor.getString(2).orEmpty(),
                    sourceName = sourceName,
                    pageNumber = if (cursor.isNull(4)) null else cursor.getInt(4),
                    locator = if (cursor.isNull(5)) null else cursor.getString(5),
                    headingContext = heading,
                    unitKind = if (cursor.isNull(7)) null else cursor.getString(7),
                    content = content,
                    similarity = semantic,
                    rankScore = anchoredSemantic,
                    specificityScore = specificity,
                    smallFragment = !cursor.isNull(10) && cursor.getInt(10) != 0,
                    sourceTitle = meta?.title,
                    sourceAuthority = meta?.authority,
                    sourceUrl = directUrl?.takeIf { it.startsWith("http") } ?: meta?.sourceUrl,
                    registerUrl = meta?.registerUrl,
                    identifierMatched = idMatched,
                    sourceCategory = meta?.category,
                    sourceDocumentStatus = meta?.documentStatus,
                    sourceEffectiveDate = meta?.effectiveDate,
                    sourcePublicationDate = meta?.publicationDate
                )
                val rank = anchoredSemantic + idBoost + sourceIdentityAdjustment(provisional, sourceHint) +
                    lifecycleAdjustment(meta?.documentStatus, query, idMatched) + dateAdjustment(meta, query)
                val hit = provisional.copy(
                    rankScore = rank
                )
                // Open WebUI ensemble behavior: lexical candidates enter the union
                // independently of the vector threshold. Relevance is judged in the
                // second-stage E5 rerank below, not before fusion.
                if (hit.chunkId in lexicalRankById) lexicalCandidates[hit.chunkId] = hit
                if (heap.size < poolSize) heap += hit
                else {
                    val weakest = heap.peek()
                    if (weakest != null && rank > weakest.rankScore) {
                        heap.poll()
                        heap += hit
                    }
                }
            }
        }

        val semanticRanked = heap.toList().sortedByDescending { it.rankScore }
        val semanticRankById = semanticRanked.mapIndexed { index, hit -> hit.chunkId to (index + 1) }.toMap()
        val combined = LinkedHashMap<String, Hit>()
        semanticRanked.forEach { combined[it.chunkId] = it }
        lexicalCandidates.values.forEach { combined.putIfAbsent(it.chunkId, it) }

        // Port of Open WebUI's retrieval order: first build an ensemble from BM25 and
        // vector ranks, then rerank the UNION using query/document relevance. Their
        // no-cross-encoder fallback reranks by embedding cosine; FixSight already has
        // E5 in-process, so specificityScore is that direct current-query cosine.
        val rrfDenominator = 61f // rank_constant(60) + rank(1)
        val maxWeightedRrf = ((HYBRID_VECTOR_WEIGHT + HYBRID_BM25_WEIGHT) / rrfDenominator)
            .coerceAtLeast(0.0001f)
        val ensembleScoreById = combined.values.associate { hit ->
            val semanticRank = semanticRankById[hit.chunkId]
            val lexicalRank = lexicalRankById[hit.chunkId]
            val vectorPart = semanticRank?.let { HYBRID_VECTOR_WEIGHT / (60f + it) } ?: 0f
            val lexicalPart = lexicalRank?.let { HYBRID_BM25_WEIGHT / (60f + it) } ?: 0f
            hit.chunkId to ((vectorPart + lexicalPart) / maxWeightedRrf).coerceIn(0f, 1f)
        }
        val candidates = combined.values.map { hit ->
            // Preserve existing authority/version/identifier adjustments, but replace
            // the multi-view semantic component with the direct current-query cosine
            // for the rerank stage. RRF is only a small stable tie-break after rerank.
            val priorSemanticBase = (hit.similarity * 0.82f) + (hit.specificityScore * 0.18f)
            val metadataAdjustment = hit.rankScore - priorSemanticBase
            val ensemble = ensembleScoreById[hit.chunkId] ?: 0f
            hit.copy(
                rankScore = hit.specificityScore + metadataAdjustment +
                    (ensemble * HYBRID_RRF_TIE_BONUS)
            )
        }.sortedWith(
            compareByDescending<Hit> { it.rankScore }
                .thenByDescending { ensembleScoreById[it.chunkId] ?: 0f }
        )
        if (candidates.isEmpty()) return@withContext SearchResult(
            packId = status.packId,
            displayName = status.displayName
        )

        // Semantic similarity remains the primary confidence gate. An exact-reference
        // match may recover a narrowly missed rule/clause lookup, but only when the
        // underlying semantic score is already close to the normal threshold.
        val semanticTop = candidates.maxOf { it.similarity }
        val directTop = candidates.maxOf { it.specificityScore }
        val bestRanked = candidates.first()
        val semanticConfident = semanticTop >= minScore ||
            (bestRanked.identifierMatched && bestRanked.similarity >= minScore - IDENTIFIER_CONFIDENCE_WINDOW)
        val directConfident = directTop >= DIRECT_QUERY_CONFIDENCE_FLOOR ||
            (bestRanked.identifierMatched && directTop >= DIRECT_QUERY_CONFIDENCE_FLOOR - 0.035f)
        val confident = semanticConfident && directConfident
        if (!confident) {
            Log.i(TAG, "GENERIC_KB_NO_STRONG_MATCH top=${"%.4f".format(Locale.US, semanticTop)} direct=${"%.4f".format(Locale.US, directTop)} min=$minScore directFloor=$DIRECT_QUERY_CONFIDENCE_FLOOR")
            return@withContext SearchResult(
                topSimilarity = semanticTop,
                confident = false,
                packId = status.packId,
                displayName = status.displayName
            )
        }

        val selected = selectForIntent(
            candidates,
            // The packet builder applies the final character budget. Keep the
            // retrieval limit dynamic for broad list/procedure questions instead of
            // silently collapsing every answer to a fixed top-10 window.
            finalLimit.coerceAtLeast(1),
            intent,
            procedureTargetTerms,
            procedureActionToken,
            specificityQuery ?: query
        )
        Log.i(
            TAG,
            "GENERIC_KB_RETRIEVED top=${"%.4f".format(Locale.US, semanticTop)} " +
                "rank=${"%.4f".format(Locale.US, candidates.first().rankScore)} direct=${"%.4f".format(Locale.US, selected.firstOrNull()?.specificityScore ?: candidates.first().specificityScore)} idMatch=${candidates.first().identifierMatched} sourceHint=${sourceHint.active} intent=${intent ?: "NONE"} " +
                "procAction=${procedureActionToken ?: "-"} procTargets=${procedureTargetTerms.joinToString(",")} views=${queryEmbeddings.size} " +
                "lexical=${lexicalHits.size} lexicalSource=$lexicalMode fusion=${if (lexicalHits.isNotEmpty()) "ENSEMBLE_RRF" else "SEMANTIC_ONLY"} rerank=E5_COSINE candidates=${candidates.size} selected=${selected.size}"
        )
        SearchResult(
            hits = selected,
            topSimilarity = semanticTop,
            confident = true,
            packId = status.packId,
            displayName = status.displayName
        )
    }

    /** Retrieve every structural clause independently, then cover clauses before score. */
    suspend fun searchForClauses(clauses: List<GenericKbClause>, candidatePool: Int): ClauseSearchResult {
        if (clauses.isEmpty()) return ClauseSearchResult(packId = currentPackId(), displayName = currentDisplayName())
        val budget = candidatePool.coerceAtLeast(1)
        val byClause = LinkedHashMap<String, List<Hit>>()
        var top = 0f
        var pack = ""
        var name = ""
        for (clause in clauses) {
            val result = search(
                query = clause.literal,
                candidatePool = budget,
                finalLimit = budget,
                specificityQuery = clause.literal
            )
            byClause[clause.id] = result.hits
            top = maxOf(top, result.topSimilarity)
            if (pack.isBlank()) pack = result.packId
            if (name.isBlank()) name = result.displayName
        }
        val selected = LinkedHashMap<String, Hit>()
        byClause.entries.sortedBy { it.value.firstOrNull()?.rankScore ?: Float.NEGATIVE_INFINITY }
            .forEach { (_, hits) -> hits.firstOrNull()?.let { selected.putIfAbsent(it.chunkId, it) } }
        byClause.values.flatten().groupBy { it.chunkId }
            .map { (_, hits) -> hits.maxByOrNull { it.rankScore }!! }
            .sortedByDescending { it.rankScore }
            .forEach { if (selected.size < budget) selected.putIfAbsent(it.chunkId, it) }
        val coverage = selected.values.associate { hit ->
            hit.chunkId to byClause.filterValues { hits -> hits.any { it.chunkId == hit.chunkId } }.keys
        }
        return ClauseSearchResult(selected.values.toList(), coverage, top, pack, name)
    }


    /** Exact-E5 hybrid retrieval verified by the standalone 75-question gate. */
    suspend fun searchExactE5Hybrid(
        query: String,
        denseRecall: Int = 40,
        lexicalRecall: Int = 40,
        evidenceCharBudget: Int = 6_000,
        contextPageRadius: Int = 3
    ): SearchResult = withContext(Dispatchers.IO) {
        val database = db ?: return@withContext SearchResult()
        if (!isReady() || query.isBlank() || !semanticEngine.isReady) return@withContext SearchResult()

        val started = System.currentTimeMillis()
        val queryEmbedding = semanticEngine.embed("query: ${query.trim()}")
            ?.takeIf { it.size == 384 }
            ?: return@withContext SearchResult(packId = status.packId, displayName = status.displayName)
        val table = status.tableName
        if (table.isBlank()) return@withContext SearchResult(packId = status.packId, displayName = status.displayName)

        val columns = columnsFor(database, table)
        val catalog = loadSourceCatalog(database)
        fun select(column: String, fallback: String = "NULL"): String =
            if (column in columns) column else "$fallback AS $column"
        val directUrlColumn = listOf("source_url", "document_url", "final_url", "url").firstOrNull { it in columns }
        val sql = "SELECT " + listOf(
            select("chunk_id", "''"), select("original_chunk_id"), select("doc_id", "''"),
            select("source_name"), select("page_number"), select("locator"), select("heading_context"),
            select("unit_kind"), select("content", "''"), select("embedding"), select("small_fragment", "0"),
            if (directUrlColumn != null) directUrlColumn else "NULL AS source_url"
        ).joinToString(",") + " FROM $table WHERE embedding IS NOT NULL"

        ensureLexicalIndex(database, status)
        val lexicalHits = lexicalRetriever.search(query, lexicalRecall.coerceAtLeast(1))
        val lexicalRankById = lexicalHits.associate { it.id to it.rank }
        val lexicalScoreById = lexicalHits.associate { it.id to (-it.bm25).toFloat() }

        val window = denseRecall.coerceAtLeast(1)
        val denseHeap = PriorityQueue<Hit>(compareBy { it.similarity })
        val lexicalCandidates = LinkedHashMap<String, Hit>()
        var scanned = 0

        fun readHit(cursor: android.database.Cursor, similarity: Float): Hit {
            val sourceName = if (cursor.isNull(3)) null else cursor.getString(3)
            val meta = sourceName?.let { catalog[it] }
            val directUrl = if (cursor.isNull(11)) null else cursor.getString(11)
            return Hit(
                chunkId = cursor.getString(0).orEmpty(), originalChunkId = if (cursor.isNull(1)) null else cursor.getString(1),
                docId = cursor.getString(2).orEmpty(), sourceName = sourceName,
                pageNumber = if (cursor.isNull(4)) null else cursor.getInt(4), locator = if (cursor.isNull(5)) null else cursor.getString(5),
                headingContext = if (cursor.isNull(6)) null else cursor.getString(6), unitKind = if (cursor.isNull(7)) null else cursor.getString(7),
                content = cursor.getString(8).orEmpty(), similarity = similarity, rankScore = similarity, specificityScore = similarity,
                smallFragment = !cursor.isNull(10) && cursor.getInt(10) != 0,
                sourceTitle = meta?.title, sourceAuthority = meta?.authority,
                sourceUrl = directUrl?.takeIf { it.startsWith("http") } ?: meta?.sourceUrl,
                registerUrl = meta?.registerUrl, sourceCategory = meta?.category,
                sourceDocumentStatus = meta?.documentStatus, sourceEffectiveDate = meta?.effectiveDate,
                sourcePublicationDate = meta?.publicationDate
            )
        }

        database.rawQuery(sql, null).use { cursor ->
            while (cursor.moveToNext()) {
                val vector = semanticEngine.blobToFloatArray(cursor.getBlob(9) ?: continue) ?: continue
                val cosine = semanticEngine.cosineSimilarity(queryEmbedding, vector)
                val hit = readHit(cursor, cosine)
                scanned++
                if (denseHeap.size < window) denseHeap += hit
                else if (cosine > (denseHeap.peek()?.similarity ?: Float.NEGATIVE_INFINITY)) {
                    denseHeap.poll(); denseHeap += hit
                }
                if (hit.chunkId in lexicalRankById) lexicalCandidates[hit.chunkId] = hit
            }
        }

        val denseOrdered = denseHeap.toList().sortedByDescending { it.similarity }
        val denseRankById = denseOrdered.mapIndexed { index, hit -> hit.chunkId to index + 1 }.toMap()
        val candidateById = LinkedHashMap<String, Hit>()
        denseOrdered.forEach { candidateById[it.chunkId] = it }
        lexicalCandidates.forEach { (id, hit) -> candidateById.putIfAbsent(id, hit) }
        val ranked = RagFlowMobileRanker.rank(query, candidateById.values.map { hit ->
            RagFlowMobileRanker.Candidate(
                id = hit.chunkId, vectorSimilarity = hit.similarity, content = hit.content,
                title = hit.sourceTitle ?: hit.sourceName.orEmpty(), heading = hit.headingContext.orEmpty(),
                denseRank = denseRankById[hit.chunkId], lexicalRank = lexicalRankById[hit.chunkId],
                lexicalScore = lexicalScoreById[hit.chunkId]
            )
        })
        val rankById = ranked.associateBy { it.id }
        val reranked = ranked.mapNotNull { r -> candidateById[r.id]?.copy(rankScore = r.score, specificityScore = r.termSimilarity) }
        val top = denseOrdered.firstOrNull()?.similarity ?: 0f
        if (reranked.isEmpty()) {
            Log.i(TAG, "EXACT_E5_HYBRID_NO_MATCH scanned=$scanned dense=${denseOrdered.size} lexical=${lexicalHits.size} topCos=${"%.4f".format(Locale.US, top)}")
            return@withContext SearchResult(
                topSimilarity = top, confident = false,
                packId = status.packId, displayName = status.displayName
            )
        }

        // Use the budgeted primary packet as context anchors. Neighbor expansion is
        // source/page structural only, so it remains portable across knowledge domains.
        val anchors = RagFlowMobileRanker.packEvidence(
            reranked.map { hit -> hit.toRankerEvidence() },
            evidenceCharBudget
        ).mapNotNull { evidence -> reranked.firstOrNull { it.chunkId == evidence.id } }
        val contextById = LinkedHashMap<String, Hit>()
        if ("source_name" in columns && "page_number" in columns && contextPageRadius > 0) {
            anchors.forEach { anchor ->
                val source = anchor.sourceName ?: return@forEach
                val page = anchor.pageNumber ?: return@forEach
                val neighborSql = sql + " AND source_name=? AND page_number BETWEEN ? AND ?"
                database.rawQuery(
                    neighborSql,
                    arrayOf(source, (page - contextPageRadius).toString(), (page + contextPageRadius).toString())
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0).orEmpty()
                        if (id.isBlank() || id == anchor.chunkId) continue
                        val vector = semanticEngine.blobToFloatArray(cursor.getBlob(9) ?: continue) ?: continue
                        val cosine = semanticEngine.cosineSimilarity(queryEmbedding, vector)
                        val raw = readHit(cursor, cosine)
                        val candidate = RagFlowMobileRanker.Candidate(
                            id = raw.chunkId, vectorSimilarity = cosine, content = raw.content,
                            title = raw.sourceTitle ?: raw.sourceName.orEmpty(), heading = raw.headingContext.orEmpty(),
                            denseRank = null, lexicalRank = lexicalRankById[id], lexicalScore = lexicalScoreById[id]
                        )
                        val score = RagFlowMobileRanker.contextScore(query, candidate, anchor.rankScore)
                        if (!score.isFinite()) continue
                        val contextual = raw.copy(
                            rankScore = score,
                            specificityScore = RagFlowMobileRanker.termSimilarity(query, candidate)
                        )
                        val current = contextById[id]
                        if (current == null || contextual.rankScore > current.rankScore) contextById[id] = contextual
                    }
                }
            }
        }

        val merged = LinkedHashMap<String, Hit>()
        reranked.forEach { merged[it.chunkId] = it }
        contextById.forEach { (id, contextual) ->
            val current = merged[id]
            if (current == null || contextual.rankScore > current.rankScore) merged[id] = contextual
        }
        val mergedById = merged.toMap()
        val packedEvidence = RagFlowMobileRanker.packEvidence(
            merged.values.map { it.toRankerEvidence() },
            evidenceCharBudget
        )
        val selected = packedEvidence.mapNotNull { evidence ->
            mergedById[evidence.id]?.copy(content = evidence.text, rankScore = evidence.score)
        }

        Log.i(TAG, "EXACT_E5_HYBRID_RETRIEVAL scanned=$scanned denseWindow=${denseOrdered.size} lexicalWindow=${lexicalHits.size} " +
            "union=${candidateById.size} relevant=${ranked.size} context=${contextById.size} selected=${selected.size} chars=${selected.sumOf { it.content.length }} " +
            "topCos=${"%.4f".format(Locale.US, top)} elapsed=${System.currentTimeMillis() - started}ms " +
            "mode=EXACT_E5_DENSE_FTS_RERANK_CONTEXT_BUDGET")
        selected.forEachIndexed { index, hit ->
            val r = rankById[hit.chunkId]
            Log.i(TAG, "EXACT_E5_HYBRID_SOURCE rank=${index + 1} hybrid=${"%.4f".format(Locale.US, hit.rankScore)} " +
                "term=${"%.4f".format(Locale.US, r?.termSimilarity ?: 0f)} cos=${"%.4f".format(Locale.US, hit.similarity)} " +
                "chunk=${hit.chunkId.take(80)}")
        }
        SearchResult(hits = selected, topSimilarity = top, confident = selected.isNotEmpty(), packId = status.packId, displayName = status.displayName)
    }

    private fun Hit.toRankerEvidence(): RagFlowMobileRanker.Evidence = RagFlowMobileRanker.Evidence(
        id = chunkId,
        score = rankScore,
        docId = docId.ifBlank { sourceName.orEmpty() },
        pageNumber = pageNumber,
        text = content,
        heading = headingContext.orEmpty()
    )

    private fun diversify(candidates: List<Hit>, limit: Int): List<Hit> {
        if (candidates.isEmpty()) return emptyList()
        val ranked = candidates.sortedByDescending { it.rankScore }
        val result = mutableListOf<Hit>()
        val usedDocs = linkedSetOf<String>()
        val usedPages = linkedSetOf<String>()

        fun pageKey(hit: Hit): String = "${hit.docId}::${hit.pageNumber ?: -1}::${hit.locator.orEmpty()}"

        // Pass 1: cover as many distinct relevant documents as possible while staying
        // close to the best semantic match. This is provenance diversity, not keyword
        // routing. It helps the answer synthesize corroborating or conflicting sources.
        val top = ranked.first().rankScore
        ranked.forEach { hit ->
            if (result.size >= limit) return@forEach
            if (hit.rankScore < top - DIVERSITY_TIE_WINDOW) return@forEach
            val doc = hit.docId.ifBlank { hit.sourceName.orEmpty() }
            if (doc.isNotBlank() && doc !in usedDocs) {
                result += hit
                usedDocs += doc
                usedPages += pageKey(hit)
            }
        }

        // Pass 2: fill remaining slots by pure semantic score, avoiding duplicate
        // page/locator material and gently preferring substantive chunks over tiny
        // fragments only when scores are near-tied.
        for (hit in ranked) {
            if (result.size >= limit) break
            if (result.any { it.chunkId == hit.chunkId }) continue
            val key = pageKey(hit)
            if (key in usedPages) continue

            if (hit.smallFragment || hit.content.length < 80) {
                val better = ranked.firstOrNull { alt ->
                    alt.chunkId != hit.chunkId &&
                        !alt.smallFragment && alt.content.length >= 80 &&
                        alt.rankScore >= hit.rankScore - SMALL_FRAGMENT_TIE_WINDOW &&
                        pageKey(alt) !in usedPages &&
                        result.none { it.chunkId == alt.chunkId }
                }
                if (better != null) {
                    result += better
                    usedPages += pageKey(better)
                    val doc = better.docId.ifBlank { better.sourceName.orEmpty() }
                    if (doc.isNotBlank()) usedDocs += doc
                    continue
                }
            }

            result += hit
            usedPages += key
            val doc = hit.docId.ifBlank { hit.sourceName.orEmpty() }
            if (doc.isNotBlank()) usedDocs += doc
        }
        return result
    }

    private fun inspectFile(file: File): Status {
        if (!file.exists() || file.length() <= 0L) return Status(fileExists = false)
        var database: SQLiteDatabase? = null
        return try {
            database = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val tables = mutableSetOf<String>()
            database.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
                while (c.moveToNext()) tables += c.getString(0)
            }
            val table = when {
                GENERIC_TABLE in tables -> GENERIC_TABLE
                COMPAT_TABLE in tables -> COMPAT_TABLE
                else -> ""
            }
            if (table.isBlank()) return Status(fileExists = true)
            val columns = columnsFor(database, table)
            val required = setOf("chunk_id", "doc_id", "source_name", "content", "embedding")
            val schemaCompatible = columns.containsAll(required)
            if (!schemaCompatible) return Status(fileExists = true, tableName = table)

            // The counts and the full-file hash (a 67 MB read) are kept per file path, size and
            // date: the pack file is only replaced by a new install, which gives it a new date.
            val cacheKey = "${file.absolutePath}|${file.length()}|${file.lastModified()}|$table"
            val cacheSlot = "$INSPECT_CACHE_KEY:${file.length()}:${file.lastModified()}"
            val cached = prefs.getString(cacheSlot, null)
                ?.takeIf { it.startsWith("$cacheKey#") }
                ?.substringAfter('#')?.split('|')
                ?.takeIf { it.size == 3 && it[2].isNotBlank() }
            val rowCount: Int
            val embeddingCount: Int
            val id: String
            if (cached != null && cached[0].toIntOrNull() != null && cached[1].toIntOrNull() != null) {
                rowCount = cached[0].toInt()
                embeddingCount = cached[1].toInt()
                id = cached[2]
            } else {
                rowCount = database.rawQuery("SELECT COUNT(*) FROM $table", null).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else 0
                }
                // Do not bind EMBEDDING_BYTES through selectionArgs here. Android's
                // rawQuery binds selectionArgs as TEXT; LENGTH(blob) has no column
                // affinity, so an INTEGER result compared to a TEXT "1536" can
                // evaluate false for every valid row. Validate the storage class and
                // byte length with an integer SQL literal instead.
                embeddingCount = database.rawQuery(
                    "SELECT COUNT(*) FROM $table " +
                        "WHERE embedding IS NOT NULL " +
                        "AND typeof(embedding)='blob' " +
                        "AND length(embedding)=$EMBEDDING_BYTES",
                    null
                ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
                id = sha256Prefix(file)
                if (file == dbFile && id.isNotBlank()) {
                    prefs.edit().putString(cacheSlot, "$cacheKey#$rowCount|$embeddingCount|$id").apply()
                }
            }
            // A pack is valid only when every row contains one 384-d float32
            // embedding (384 * 4 = 1536 bytes).
            val valid = rowCount > 0 && embeddingCount == rowCount
            Status(
                fileExists = true,
                schemaCompatible = true,
                valid = valid,
                rowCount = rowCount,
                embeddingCount = embeddingCount,
                packId = id,
                displayName = prefs.getString("display_name", "").orEmpty().ifBlank {
                    // Never show the internal file name ("active_generic_knowledge") to the user.
                    file.nameWithoutExtension.takeUnless { it.startsWith("active_generic_knowledge") } ?: "Knowledge Base"
                },
                tableName = table
            )
        } catch (t: Throwable) {
            Log.w(TAG, "GENERIC_KB_INSPECT_FAILED ${t.message}")
            Status(fileExists = true)
        } finally {
            runCatching { database?.close() }
        }
    }

    private fun columnsFor(database: SQLiteDatabase, table: String): Set<String> {
        val columns = linkedSetOf<String>()
        database.rawQuery("PRAGMA table_info($table)", null).use { c ->
            while (c.moveToNext()) columns += c.getString(1)
        }
        return columns
    }

    private fun resolveDisplayName(uri: Uri): String = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) c.getString(i).orEmpty() else ""
            } else ""
        }.orEmpty()
    } catch (_: Throwable) { "" }

    private fun sha256Prefix(file: File): String {
        if (!file.exists()) return ""
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    digest.update(buffer, 0, n)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }.take(24)
        } catch (_: Throwable) { "" }
    }


    // ------------------------------------------------------------------------------------
    // Phase 1.4 retrieval: literal-first, mean-centred, pack-calibrated, multi-lane.
    // No conversation history, no vocabulary lists, no fixed similarity constant. Quality
    // signals re-rank only; the calibrated gate (or an identifier admission) admits evidence.
    // ------------------------------------------------------------------------------------

    data class Phase14ClauseState(
        val clauseId: String,
        val state: GenericKbCoverageState,
        val note: String = ""
    )

    data class Phase14Result(
        val hits: List<Hit>,
        val reservedCount: Int,
        val clauseIdsByChunk: Map<String, Set<String>>,
        val clauseStates: List<Phase14ClauseState>,
        val notesByChunk: Map<String, String>,
        val topSimilarity: Float,
        val packId: String,
        val displayName: String,
        val trace: String,
        /** Best cross-encoder relevance of the turn (null without re-ranker); drives follow-up resolution. */
        val bestRerank: Float? = null,
        /** Question as the re-ranker read it (rewrite + literal + abbreviation long forms). */
        val rerankQuestion: String = ""
    )

    /** Cross-encoder scorer for key-fact selection; null when the re-ranker is not ready (lexical scoring then). */
    fun keyFactScorer(): (suspend (String, List<String>) -> FloatArray?)? =
        if (semanticEngine.isReady && reranker.ready()) { q, units -> reranker.score(q, units) } else null

    /** UI2: mixed-question pair scorer (one batched run); null when the re-ranker is not ready. */
    fun pairScorer(): (suspend (List<Pair<String, String>>) -> FloatArray?)? =
        if (semanticEngine.isReady && reranker.ready()) { pairs -> reranker.scorePairs(pairs) } else null

    /** Long forms of the abbreviations in [question], from the active pack's own text. */
    fun aliasExpansions(question: String): List<String> = phase14Profile?.expansions(question).orEmpty()
    fun unknownWords(question: String): List<String> = phase14Profile?.unknownWords(question).orEmpty()
    fun abbreviationTerms(question: String): List<Pair<String, String>> = phase14Profile?.terms(question).orEmpty()

    @Volatile private var phase14Profile: GenericKbCorpusProfile? = null
    @Volatile private var runningHeadingCache: Pair<String, Set<String>>? = null

    /**
     * "doc␟heading" of headings that a document repeats over at least [runningHeadingPages] pages:
     * running headers, not the heading of the passage (see GenericKbPhase14Engine.Row.runningHeading).
     * One grouped query per pack, cached by the profile signature.
     */
    private fun runningHeadings(database: SQLiteDatabase, table: String, columns: Set<String>, signature: String): Set<String> {
        val minPages = runningHeadingPages
        if (minPages <= 0 || "heading_context" !in columns || "page_number" !in columns) return emptySet()
        runningHeadingCache?.let { (sig, set) -> if (sig == "$signature:$minPages") return set }
        val doc = if ("doc_id" in columns) "COALESCE(NULLIF(doc_id, ''), source_name, '')" else "COALESCE(source_name, '')"
        val out = HashSet<String>()
        runCatching {
            database.rawQuery(
                "SELECT $doc, heading_context FROM $table WHERE heading_context IS NOT NULL AND heading_context <> '' " +
                    "GROUP BY 1, 2 HAVING COUNT(DISTINCT page_number) >= $minPages", null
            ).use { c -> while (c.moveToNext()) out += c.getString(0).orEmpty() + RUNNING_KEY_SEPARATOR + c.getString(1).orEmpty() }
        }.onFailure { Log.w(TAG, "KB_RUNNING_HEADINGS_FAILED ${it.message}") }
        Log.i(TAG, "KB_RUNNING_HEADINGS count=${out.size} minPages=$minPages")
        runningHeadingCache = "$signature:$minPages" to out
        return out
    }
    @Volatile private var phase14Calibration: GenericKbScoreCalibration? = null
    private val phase14Mutex = Mutex()
    private val phase14CalibrationFile = File(packDir, "active_generic_knowledge.p14cal")
    private val phase14ProfileFile = File(packDir, "active_generic_knowledge.p14profile")

    fun phase14Available(): Boolean = phase14Profile != null

    /**
     * Evidence for a passage much shorter than this pack's normal passage (a slide caption such as
     * "Click on Bid to create bid") is completed with the other passages of the same document page,
     * in reading order, up to the pack's median passage length. Same page = same source, so
     * provenance is unchanged. Only the text handed to the answering model changes.
     */
    /**
     * A passage that starts mid-sentence (cut at a page or size limit: "...the dose is 20 mg") gets
     * the end of the previous passage of the same document, from its last sentence end, so the
     * answering model and the shown passage read a whole sentence. Unchanged when the passage starts
     * cleanly, the previous passage is another document, or the missing part is long.
     */
    private fun withSentenceStart(database: SQLiteDatabase, table: String, columns: Set<String>, hit: Hit, content: String): String {
        if (!KbHealthCheck.midSentenceStart(content) || hit.chunkId.isBlank() || "doc_id" !in columns || hit.docId.isBlank()) return content
        val order = if ("id" in columns) "id" else "rowid"
        val previous = runCatching {
            database.rawQuery(
                "SELECT content FROM $table WHERE doc_id = ? AND $order < (SELECT $order FROM $table WHERE chunk_id = ? LIMIT 1) ORDER BY $order DESC LIMIT 1",
                arrayOf(hit.docId, hit.chunkId)
            ).use { c -> if (c.moveToFirst()) c.getString(0).orEmpty() else "" }
        }.getOrDefault("").trimEnd()
        if (previous.isBlank()) return content
        val cut = previous.indexOfLast { it == '.' || it == '?' || it == '!' || it == '।' || it == '\n' }
        val tail = previous.substring(cut + 1).trim()
        if (tail.isBlank() || tail.length > SENTENCE_START_MAX_CHARS || content.startsWith(tail)) return content
        val body = content.trimStart().removePrefix("…").removePrefix("...").trimStart()
        return "$tail $body"
    }

    private fun withPageContext(
        database: SQLiteDatabase,
        table: String,
        columns: Set<String>,
        hit: Hit,
        medianChars: Int
    ): String {
        val own = hit.content.trim()
        val page = hit.pageNumber ?: return own
        if (medianChars <= 0 || own.length >= medianChars / 2) return own
        if ("doc_id" !in columns || "page_number" !in columns || hit.docId.isBlank()) return own
        val order = if ("id" in columns) "id" else "rowid"
        val siblings = runCatching {
            database.rawQuery(
                "SELECT chunk_id, content FROM $table WHERE doc_id = ? AND page_number = ? ORDER BY $order LIMIT 24",
                arrayOf(hit.docId, page.toString())
            ).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0).orEmpty() to c.getString(1).orEmpty().trim()) }
            }
        }.getOrNull() ?: return own
        val seen = HashSet<String>().apply { add(own.lowercase()) }
        val out = StringBuilder(own)
        for ((id, text) in siblings) {
            if (id == hit.chunkId || text.isBlank() || !seen.add(text.lowercase())) continue
            if (out.contains(text)) continue
            if (out.length + text.length + 1 > medianChars) break
            out.append('\n').append(text)
        }
        return out.toString()
    }

    /**
     * A top passage from a fragmentary document (slide deck, screenshot manual): its page and the
     * following pages of the same document in reading order, adding up to half the pack's median
     * passage length. A procedure in such documents is one step per caption across consecutive
     * pages, so the matched caption alone cannot answer "how do I ...". Same document, so the
     * source shown is unchanged; only the text handed to the answering model grows.
     */
    private fun withFollowingPages(
        database: SQLiteDatabase,
        table: String,
        columns: Set<String>,
        hit: Hit,
        medianChars: Int
    ): Pair<String, Int?> {
        val own = hit.content.trim()
        val page = hit.pageNumber ?: return withPageContext(database, table, columns, hit, medianChars) to null
        if (medianChars <= 0 || "doc_id" !in columns || "page_number" !in columns || hit.docId.isBlank()) return own to null
        // Own text plus up to half a normal passage of what follows (~one to five slide steps).
        val budget = own.length + medianChars / 2
        val order = if ("id" in columns) "id" else "rowid"
        val texts = runCatching {
            database.rawQuery(
                "SELECT chunk_id, content, page_number FROM $table WHERE doc_id = ? AND page_number >= ? AND page_number <= ? " +
                    "ORDER BY page_number, $order LIMIT 60",
                arrayOf(hit.docId, page.toString(), (page + 8).toString())
            ).use { c -> buildList { while (c.moveToNext()) add(Triple(c.getString(0).orEmpty(), c.getString(1).orEmpty().trim(), c.getInt(2))) } }
        }.getOrNull() ?: return own to null
        val out = StringBuilder(own)
        val seen = HashSet<String>().apply { add(own.lowercase()) }
        var lastPage = page
        for ((id, text, textPage) in texts) {
            if (id == hit.chunkId || text.isBlank() || !seen.add(text.lowercase()) || out.contains(text)) continue
            // Text from a later page is marked, so the answer model and the source line agree on pages.
            val marker = if (textPage != lastPage) "\n[page $textPage]" else ""
            if (out.length + marker.length + text.length + 1 > budget) break
            out.append(marker).append('\n').append(text)
            lastPage = textPage
        }
        return out.toString() to lastPage.takeIf { it > page }
    }

    @Volatile private var vectorSpace: Pair<String, KbVectorSpaceCheck.Result>? = null
    private val vectorSpaceFile by lazy { File(packDir, "active_generic_knowledge.vspace") }

    /**
     * Checks that the pack's stored passage vectors were made by the same embedding model and
     * recipe as the phone's query embedder: a few evenly spaced rows are re-embedded ("passage: "
     * with and without the heading line; the better layout counts) and compared with the stored
     * vectors. Cached per pack signature. Logged as KB_VECTOR_SPACE.
     */
    suspend fun vectorSpaceCheck(): KbVectorSpaceCheck.Result? = withContext(Dispatchers.IO) {
        val database = db ?: return@withContext null
        if (!isReady() || !semanticEngine.isReady) return@withContext null
        val signature = phase14Signature(status)
        vectorSpace?.takeIf { it.first == signature }?.let { return@withContext it.second }
        // Cached on disk per pack signature: the check embeds a few passages once, not every launch.
        runCatching {
            val lines = vectorSpaceFile.readLines()
            if (lines.size >= 2 && lines[0] == signature) {
                val p = lines[1].split(',')
                KbVectorSpaceCheck.Result(p[0].toBoolean(), p[1].toInt(), p[2].toFloat(), p[3])
            } else null
        }.getOrNull()?.let { vectorSpace = signature to it; return@withContext it }
        val table = status.tableName
        val columns = columnsFor(database, table)
        if ("embedding" !in columns || "content" !in columns) return@withContext null
        val heading = if ("heading_context" in columns) "heading_context" else "NULL"
        // Eight evenly spaced passages of normal length (deterministic for a given pack).
        val candidates = ArrayList<Long>()
        database.rawQuery(
            "SELECT rowid FROM $table WHERE embedding IS NOT NULL AND length(content) >= 200 ORDER BY rowid", null
        ).use { c -> while (c.moveToNext()) candidates += c.getLong(0) }
        val picked = if (candidates.isEmpty()) emptyList() else
            (0 until minOf(8, candidates.size)).map { candidates[it * candidates.size / minOf(8, candidates.size)] }
        val samples = ArrayList<Triple<String, String, FloatArray>>()
        for (rowid in picked) {
            database.rawQuery("SELECT $heading, content, embedding FROM $table WHERE rowid = ?", arrayOf(rowid.toString())).use { c ->
                if (c.moveToFirst()) {
                    val v = c.getBlob(2)?.let { semanticEngine.blobToFloatArray(it) }
                    if (v != null) samples += Triple(if (c.isNull(0)) "" else c.getString(0).orEmpty(), c.getString(1).orEmpty(), v)
                }
            }
        }
        val cosines = samples.mapNotNull { (h, text, stored) ->
            val plain = semanticEngine.embed("passage: $text")
            val withHeading = if (h.isNotBlank()) semanticEngine.embed("passage: $h\n$text") else null
            listOfNotNull(plain, withHeading).maxOfOrNull { semanticEngine.cosineSimilarity(it, stored) }
        }
        val result = KbVectorSpaceCheck.evaluate(cosines)
        Log.i(
            TAG,
            "KB_VECTOR_SPACE passed=${result.passed} checked=${result.checked} min=${"%.4f".format(Locale.US, result.minimumCosine)} " +
                "tolerance=${KbVectorSpaceCheck.MINIMUM_COSINE} reason=${result.reason} values=${cosines.joinToString(",") { "%.4f".format(Locale.US, it) }}"
        )
        vectorSpace = signature to result
        if (result.checked > 0) runCatching {
            vectorSpaceFile.writeText("$signature\n${result.passed},${result.checked},${result.minimumCosine},${result.reason}\n")
        }
        result
    }

    /**
     * Full-text answer path that needs nothing but the pack table and its portable lexical index:
     * no corpus profile, no calibration, no embeddings. Hits come from the lexical retriever (FTS5 or
     * the portable BM25 index), keep their source/page metadata, and are re-ordered by the verified
     * re-ranker when it is available. Returns null only when the lexical index itself is unavailable.
     */
    private suspend fun lexicalWithoutProfile(
        database: SQLiteDatabase,
        literal: String,
        clauses: List<GenericKbClause>,
        candidatePool: Int
    ): Phase14Result? {
        val started = System.currentTimeMillis()
        if (!ensureLexicalIndex(database, status)) return null
        val table = status.tableName
        val columns = columnsFor(database, table)
        if ("chunk_id" !in columns || "content" !in columns) return null
        val limit = candidatePool.coerceAtLeast(16)
        val ranks = lexicalRetriever.search(literal.trim(), limit).associate { it.id to it.rank }
        val catalog = loadSourceCatalog(database)
        fun col(name: String, fallback: String = "NULL") = if (name in columns) name else "$fallback AS $name"
        val hits = ArrayList<Hit>()
        ranks.keys.chunked(200).forEach { ids ->
            val sql = "SELECT " + listOf(
                "chunk_id", col("original_chunk_id"), col("doc_id", "''"), col("source_name"), col("page_number"),
                col("locator"), col("heading_context"), col("unit_kind"), "content", col("small_fragment", "0")
            ).joinToString(",") + " FROM $table WHERE chunk_id IN (" + ids.joinToString(",") { "?" } + ")"
            database.rawQuery(sql, ids.toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    val content = c.getString(8).orEmpty()
                    if (content.isBlank()) continue
                    val sourceName = if (c.isNull(3)) null else c.getString(3)
                    val meta = sourceName?.let { catalog[it] }
                    hits += Hit(
                        chunkId = c.getString(0).orEmpty(),
                        originalChunkId = if (c.isNull(1)) null else c.getString(1),
                        docId = c.getString(2).orEmpty(),
                        sourceName = sourceName,
                        pageNumber = if (c.isNull(4)) null else c.getInt(4),
                        locator = if (c.isNull(5)) null else c.getString(5),
                        headingContext = if (c.isNull(6)) null else c.getString(6),
                        unitKind = if (c.isNull(7)) null else c.getString(7),
                        content = content,
                        similarity = 0f, rankScore = 0f, specificityScore = 0f,
                        smallFragment = !c.isNull(9) && c.getInt(9) != 0,
                        sourceTitle = meta?.title, sourceAuthority = meta?.authority, sourceUrl = meta?.sourceUrl,
                        registerUrl = meta?.registerUrl, sourceCategory = meta?.category,
                        sourceDocumentStatus = meta?.documentStatus, sourceEffectiveDate = meta?.effectiveDate,
                        sourcePublicationDate = meta?.publicationDate
                    )
                }
            }
        }
        val texts = hits.map { (it.headingContext?.let { h -> "$h\n" } ?: "") + it.content }
        val scores = if (hits.isNotEmpty() && semanticEngine.isReady && reranker.ready()) {
            runCatching { reranker.score(literal.trim(), texts) }.getOrNull()?.takeIf { it.size == hits.size }
        } else null
        val order = GenericKbRetrievalPlan.lexicalFallbackOrder(hits.map { ranks[it.chunkId] ?: Int.MAX_VALUE }, scores)
        val ordered = order.map { i -> hits[i].copy(rankScore = scores?.get(i) ?: (1f / (60 + (ranks[hits[i].chunkId] ?: 60)))) }
            .distinctBy { it.content.lowercase().filter { ch -> ch.isLetterOrDigit() }.take(400) }
            .take(limit)
        val clauseHits = if (clauses.size > 1) clauses.associate { it.id to lexicalRetriever.search(it.literal, limit).map { h -> h.id }.toSet() } else emptyMap()
        val covers = ordered.associate { h ->
            h.chunkId to (if (clauses.size <= 1) clauses.map { it.id }.toSet()
            else clauses.filter { h.chunkId in clauseHits[it.id].orEmpty() }.map { it.id }.toSet())
        }
        val states = clauses.map { cl ->
            Phase14ClauseState(cl.id,
                if (ordered.any { cl.id in covers[it.chunkId].orEmpty() }) GenericKbCoverageState.SUPPORTED else GenericKbCoverageState.UNSUPPORTED, "")
        }
        val trace = "literal=${literal.take(80).replace('\n', ' ')} mode=lexical_only reason=no_profile lexical=${ranks.size} " +
            "rr=${if (scores != null) "on:best=${"%.2f".format(Locale.US, scores.maxOrNull() ?: Float.NaN)}" else "off"} admitted=${ordered.size} " +
            "top=" + ordered.take(5).joinToString(";") { "${it.chunkId.take(10)}:lx=${ranks[it.chunkId] ?: "-"}:src=${(it.sourceTitle ?: it.sourceName ?: it.docId).replace(' ', '_').take(40)}@p${it.pageNumber ?: "-"}" } +
            " elapsed=${System.currentTimeMillis() - started}ms"
        Log.i(TAG, "KB_PHASE14_TRACE $trace")
        return Phase14Result(
            hits = ordered, reservedCount = 0, clauseIdsByChunk = covers, clauseStates = states,
            notesByChunk = emptyMap(), topSimilarity = 0f, packId = status.packId, displayName = status.displayName,
            trace = trace, bestRerank = scores?.maxOrNull()
        )
    }

    /** Optional cross-encoder (assets/reranker/): used only after its on-device parity passes. */
    private val reranker by lazy { GenericKbReranker(context, semanticEngine) }

    /**
     * Starts the re-ranker's background verification early (warming): the first question
     * otherwise pays for it. Never waits; safe to call before the pack is selected.
     */
    fun warmSearchSessions() {
        runCatching { reranker.ready() }
    }

    /**
     * R9 marker of the installed pack: 2 = v2 guide file, 0 = v1, -1 = none installed.
     * Read live (no caching) so a replaced guide is reported correctly.
     */
    fun guideVersion(): Int {
        val database = db ?: return -1
        return runCatching {
            database.rawQuery("PRAGMA user_version", null).use { c -> if (c.moveToFirst()) c.getInt(0) else -1 }
        }.getOrDefault(-1)
    }

    /**
     * Official guides library (read-only; retrieval untouched): every installed source with its
     * on-phone passage/page counts. Empty when no pack is installed.
     */
    fun listGuideSources(): List<GuideDoc> {
        val database = db ?: return emptyList()
        return runCatching {
            database.rawQuery(
                """SELECT s.source_name, s.title, s.authority, s.category, s.language, s.source_url, s.register_url,
                   s.retrieved_at_utc, s.publication_date, COUNT(p.id), COUNT(DISTINCT p.page_number)
                   FROM knowledge_sources s LEFT JOIN procurement_chunks p ON p.source_name = s.source_name
                   GROUP BY s.source_name ORDER BY s.authority, s.title""", null
            ).use { c ->
                ArrayList<GuideDoc>().also { out ->
                    while (c.moveToNext()) out += GuideDoc(
                        c.getString(0).orEmpty(), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getString(3).orEmpty(),
                        c.getString(4).orEmpty(), c.getString(5).orEmpty(), c.getString(6).orEmpty(),
                        c.getString(7).orEmpty(), c.getString(8).orEmpty(), c.getInt(9), c.getInt(10))
                }
            }
        }.getOrDefault(emptyList())
    }

    /** Topic headings inside one guide, for "Ask about this guide" suggestions. */
    fun guideTopics(sourceName: String, limit: Int = 8): List<String> {
        val database = db ?: return emptyList()
        return runCatching {
            database.rawQuery(
                "SELECT DISTINCT heading_context FROM procurement_chunks WHERE source_name = ? AND heading_context IS NOT NULL AND heading_context <> '' LIMIT $limit",
                arrayOf(sourceName)).use { c ->
                ArrayList<String>().also { out -> while (c.moveToNext()) out += c.getString(0).orEmpty().trim() }
            }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    /**
     * Optional build-time enrichment (tools/kb_enrich.py): likely questions per passage and their
     * centroid vector. Searchable only; answers and sources always come from the passage row.
     */
    private fun hasChunkEnrichment(database: SQLiteDatabase): Boolean = runCatching {
        val present = database.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='$ENRICHMENT_TABLE' LIMIT 1", null
        ).use { it.moveToFirst() }
        present && columnsFor(database, ENRICHMENT_TABLE).containsAll(listOf("chunk_id", "questions_json", "question_embedding"))
    }.getOrDefault(false)

    private fun phase14Signature(current: Status): String = lexicalRetriever.stableSignature(
        "phase14_profile_v1",
        current.packId,
        current.rowCount.toString(),
        dbFile.length().toString(),
        dbFile.lastModified().toString(),
        current.tableName
    )

    /** One read of the pack at open: mean vector, vocabulary, quality penalties, structure. */
    private fun ensurePhase14Profile(database: SQLiteDatabase, current: Status) {
        val signature = phase14Signature(current)
        if (phase14Profile?.signature == signature) return
        val started = System.currentTimeMillis()
        // The profile is a pure function of the pack file: load the saved one when it matches.
        GenericKbCorpusProfile.readFrom(phase14ProfileFile, signature)?.let { saved ->
            phase14Profile = saved
            phase14Calibration = loadPhase14Calibration(signature)
            Log.i(
                TAG,
                "KB_PHASE14_PROFILE_READY rows=${saved.chunkCount} structuralLabels=${saved.structure.structuralLabels.size} " +
                    "calibrated=${phase14Calibration != null} cached=true elapsed=${System.currentTimeMillis() - started}ms"
            )
            return
        }
        val table = current.tableName
        val columns = columnsFor(database, table)
        if (table.isBlank() || "content" !in columns || "chunk_id" !in columns) {
            phase14Profile = null
            return
        }
        fun col(name: String): String = if (name in columns) name else "NULL"
        val origins = listOf("text_origin", "recovery_method").filter { it in columns }
        val originExpr = if (origins.isEmpty()) "NULL" else origins.joinToString(" || '/' || ") { "COALESCE($it,'')" }
        val docExpr = when {
            // Same document key as the retrieval rows: doc id, else source name.
            "doc_id" in columns && "source_name" in columns -> "COALESCE(NULLIF(doc_id,''),source_name)"
            "doc_id" in columns -> "doc_id"
            "source_name" in columns -> "source_name"
            else -> "NULL"
        }
        val builder = GenericKbCorpusProfile.Builder(signature)
        database.rawQuery(
            "SELECT chunk_id,$docExpr,${col("heading_context")},${col("locator")},content,$originExpr,${col("embedding")},${col("source_name")} " +
                "FROM $table WHERE content IS NOT NULL",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val vector = if (c.isNull(6)) null else c.getBlob(6)?.let { semanticEngine.blobToFloatArray(it) }
                builder.addRow(
                    chunkId = c.getString(0).orEmpty(),
                    documentKey = if (c.isNull(1)) "" else c.getString(1).orEmpty(),
                    heading = if (c.isNull(2)) "" else c.getString(2).orEmpty(),
                    locator = if (c.isNull(3)) "" else c.getString(3).orEmpty(),
                    content = c.getString(4).orEmpty(),
                    extractionOrigin = if (c.isNull(5)) "" else c.getString(5).orEmpty(),
                    vector = vector,
                    documentName = if (c.isNull(7)) "" else c.getString(7).orEmpty()
                )
            }
        }
        val profile = builder.build()
        phase14Profile = profile
        Log.i(TAG, "KB_EDITIONS ${GenericKbCorpusProfile.editionNote.ifBlank { "none" }}")
        profile.writeTo(phase14ProfileFile)
        phase14Calibration = loadPhase14Calibration(signature)
        Log.i(
            TAG,
            "KB_PHASE14_PROFILE_READY rows=${profile.chunkCount} structuralLabels=${profile.structure.structuralLabels.size} " +
                "calibrated=${phase14Calibration != null} elapsed=${System.currentTimeMillis() - started}ms"
        )
    }

    private fun loadPhase14Calibration(signature: String): GenericKbScoreCalibration? = runCatching {
        if (!phase14CalibrationFile.exists()) return@runCatching null
        val lines = phase14CalibrationFile.readLines()
        if (lines.size < 2 || lines[0] != signature) null else GenericKbScoreCalibration.decode(lines[1])
    }.getOrNull()

    private fun savePhase14Calibration(signature: String, calibration: GenericKbScoreCalibration) {
        runCatching { phase14CalibrationFile.writeText(signature + "\n" + calibration.encode() + "\n") }
    }

    private suspend fun ensurePhase14Calibration(
        database: SQLiteDatabase,
        profile: GenericKbCorpusProfile
    ): GenericKbScoreCalibration? {
        phase14Calibration?.let { return it }
        return phase14Mutex.withLock {
            phase14Calibration ?: calibratePhase14(database, profile)?.also {
                phase14Calibration = it
                savePhase14Calibration(profile.signature, it)
            }
        }
    }

    /**
     * One-time calibration per installed pack (cached on disk by pack signature), using the
     * shared [GenericKbCalibrationPlan]: incoherent pseudo-questions of random pack words
     * (noise) and the pack's own headings asked as questions (positives).
     */
    private suspend fun calibratePhase14(
        database: SQLiteDatabase,
        profile: GenericKbCorpusProfile
    ): GenericKbScoreCalibration? {
        val started = System.currentTimeMillis()
        val table = status.tableName
        val columns = columnsFor(database, table)
        if ("embedding" !in columns) return null
        val headingRows = ArrayList<Pair<String, String>>()
        if ("heading_context" in columns) {
            database.rawQuery(
                "SELECT heading_context, chunk_id FROM $table WHERE heading_context IS NOT NULL AND content IS NOT NULL",
                null
            ).use { c -> while (c.moveToNext()) headingRows += c.getString(0).orEmpty() to c.getString(1).orEmpty() }
        }
        val plan = GenericKbCalibrationPlan.plan(profile, headingRows.asSequence()) ?: return null
        val vectors = plan.texts.map { q -> semanticEngine.embed("query: $q")?.takeIf { it.size == 384 } ?: return null }
        val rows = sequence {
            database.rawQuery("SELECT chunk_id, embedding FROM $table WHERE embedding IS NOT NULL", null).use { c ->
                while (c.moveToNext()) {
                    val v = c.getBlob(1)?.let { semanticEngine.blobToFloatArray(it) } ?: continue
                    yield(c.getString(0).orEmpty() to v)
                }
            }
        }
        val calibration = GenericKbCalibrationPlan.calibrate(profile, plan, vectors, rows)
        Log.i(
            TAG,
            "KB_PHASE14_CALIBRATED gateZ=${calibration?.gateZ} noiseP90=${calibration?.noiseP90} " +
                "positiveP10=${calibration?.positiveP10} positiveMedian=${calibration?.positiveMedian} " +
                "separates=${calibration?.separates} samples=${plan.texts.size} elapsed=${System.currentTimeMillis() - started}ms"
        )
        return calibration
    }

    /**
     * Phase 1.4 search. Returns null when the pack profile or calibration is unavailable so
     * the caller can fall back to the previous retrieval path unchanged. The ranking logic
     * lives in [GenericKbPhase14Engine]; this method only adapts the SQLite pack to it.
     */
    suspend fun searchPhase14(
        literal: String,
        clauses: List<GenericKbClause>,
        candidatePool: Int,
        /** False after a failed vector-space check: dense lanes off, full-text retrieval continues. */
        denseEnabled: Boolean = true,
        /** Extra search views (the question rewritten by the answer model); the literal is always searched. */
        extraViews: List<String> = emptyList()
    ): Phase14Result? = withContext(Dispatchers.IO) {
        val database = db ?: return@withContext null
        if (!isReady() || literal.isBlank()) return@withContext null
        // The corpus profile only enhances retrieval. A valid pack whose profile is missing, stale or
        // failed to build still answers from the portable full-text index plus chunk/source metadata.
        val profile = phase14Profile?.takeIf { it.signature == phase14Signature(status) }
            ?: return@withContext lexicalWithoutProfile(database, literal, clauses, candidatePool)
        // Dense retrieval needs a ready, matching embedder and the calibrated gate; without them the
        // engine runs full-text only (never "no answer because of a diagnostic").
        val dense = denseEnabled && semanticEngine.isReady
        if (dense) runCatching { KbParityCheck.runOnceIfFixturePresent(context, semanticEngine) }
        val calibration = if (dense) ensurePhase14Calibration(database, profile) else null

        val table = status.tableName
        val columns = columnsFor(database, table)
        fun select(column: String, fallback: String = "NULL"): String =
            if (column in columns) column else "$fallback AS $column"
        val directUrlColumn = listOf("source_url", "document_url", "final_url", "url").firstOrNull { it in columns }
        val enrichment = hasChunkEnrichment(database)
        fun col(column: String, fallback: String = "NULL"): String =
            if (column in columns) "t.$column" else "$fallback AS $column"
        val sql = "SELECT " + listOf(
            col("chunk_id", "''"), col("original_chunk_id"), col("doc_id", "''"),
            col("source_name"), col("page_number"), col("locator"), col("heading_context"),
            col("unit_kind"), col("content", "''"), col("embedding"), col("small_fragment", "0"),
            directUrlColumn?.let { "t.$it" } ?: "NULL AS source_url",
            if (enrichment) "e.question_embedding" else "NULL AS question_embedding"
        ).joinToString(",") + " FROM $table t" +
            (if (enrichment) " LEFT JOIN $ENRICHMENT_TABLE e ON e.chunk_id = t.chunk_id" else "") +
            " WHERE t.embedding IS NOT NULL"
        val catalog = loadSourceCatalog(database)
        val sourceHint = sourceIdentityHint(literal, catalog)
        ensureLexicalIndex(database, status)

        val running = runningHeadings(database, table, columns, profile.signature)
        fun rows(): Sequence<GenericKbPhase14Engine.Row<Hit>> = sequence {
            database.rawQuery(sql, null).use { cursor ->
                while (cursor.moveToNext()) {
                    val vector = cursor.getBlob(9)?.let { semanticEngine.blobToFloatArray(it) } ?: continue
                    val chunkId = cursor.getString(0).orEmpty()
                    if (chunkId.isBlank()) continue
                    val content = cursor.getString(8).orEmpty()
                    val heading = if (cursor.isNull(6)) null else cursor.getString(6)
                    val locator = if (cursor.isNull(5)) null else cursor.getString(5)
                    val sourceName = if (cursor.isNull(3)) null else cursor.getString(3)
                    val meta = sourceName?.let { catalog[it] }
                    val originalChunkId = if (cursor.isNull(1)) null else cursor.getString(1)
                    val docId = cursor.getString(2).orEmpty()
                    val page = if (cursor.isNull(4)) null else cursor.getInt(4)
                    val unitKind = if (cursor.isNull(7)) null else cursor.getString(7)
                    val small = !cursor.isNull(10) && cursor.getInt(10) != 0
                    val directUrl = if (cursor.isNull(11)) null else cursor.getString(11)
                    val questionVector = if (cursor.isNull(12)) null else cursor.getBlob(12)?.let { semanticEngine.blobToFloatArray(it) }
                    val runningRow = heading != null && (docId.ifBlank { sourceName.orEmpty() } + RUNNING_KEY_SEPARATOR + heading) in running
                    yield(
                        GenericKbPhase14Engine.Row(
                            chunkId = chunkId,
                            heading = heading.orEmpty(),
                            locator = locator.orEmpty(),
                            content = content,
                            sourceText = listOf(sourceName.orEmpty(), meta?.title.orEmpty(), meta?.category.orEmpty()).joinToString(" "),
                            documentKey = docId.ifBlank { sourceName.orEmpty() },
                            runningHeading = runningRow,
                            vector = vector,
                            questionVector = questionVector?.takeIf { it.size == vector.size }
                        ) {
                            Hit(
                                chunkId = chunkId, originalChunkId = originalChunkId, docId = docId,
                                sourceName = sourceName, pageNumber = page, locator = locator,
                                // A running header at the start of the text is dropped: it is not this passage's topic.
                                headingContext = heading, unitKind = unitKind,
                                content = if (runningRow && heading != null && content.startsWith(heading)) content.substring(heading.length).trimStart().ifBlank { content } else content,
                                similarity = 0f, rankScore = 0f, specificityScore = 0f, smallFragment = small,
                                sourceTitle = meta?.title, sourceAuthority = meta?.authority,
                                sourceUrl = directUrl?.takeIf { it.startsWith("http") } ?: meta?.sourceUrl,
                                registerUrl = meta?.registerUrl, sourceCategory = meta?.category,
                                sourceDocumentStatus = meta?.documentStatus, sourceEffectiveDate = meta?.effectiveDate,
                                sourcePublicationDate = meta?.publicationDate,
                                runningHeading = runningRow
                            )
                        }
                    )
                }
            }
        }

        val engine = GenericKbPhase14Engine<Hit>(profile, calibration?.gateZ ?: Float.MAX_VALUE)
        var ftsMs = 0L
        var embedMs = 0L
        var rerankMs = 0L
        var rerankPairs = 0
        var rerankCalls = 0
        val tEngine = System.currentTimeMillis()
        val result = engine.search(
            literal = literal,
            clauses = clauses,
            pool = candidatePool,
            scanRows = ::rows,
            embed = { text ->
                val t = System.currentTimeMillis()
                semanticEngine.embed(text)?.takeIf { it.size == 384 }.also { embedMs += System.currentTimeMillis() - t }
            },
            lexicalRanks = { text ->
                val t = System.currentTimeMillis()
                lexicalRetriever.search(text, candidatePool.coerceAtLeast(16)).associate { it.id to it.rank }.also { ftsMs += System.currentTimeMillis() - t }
            },
            metadataCos = { hit, admission ->
                val meta = hit.sourceName?.let { catalog[it] }
                sourceIdentityAdjustment(hit, sourceHint) +
                    lifecycleAdjustment(meta?.documentStatus, literal, admission != null) + dateAdjustment(meta, literal)
            },
            describe = { hit -> (hit.sourceTitle ?: hit.sourceName ?: hit.docId) + (hit.pageNumber?.let { "@p$it" } ?: "") },
            rerank = if (semanticEngine.isReady && reranker.ready()) { question, passages ->
                val t = System.currentTimeMillis()
                rerankCalls++
                rerankPairs += passages.size
                reranker.score(question, passages).also { rerankMs += System.currentTimeMillis() - t }
            } else null,
            denseEnabled = dense && calibration != null,
            precedenceOf = { hit -> hit.sourceName?.let { catalog[it]?.precedence } },
            extraViews = extraViews
        ) ?: return@withContext null
        Log.i(TAG, "KB_PHASE14_TRACE ${result.trace} cal=${phase14Calibration?.encode() ?: "-"}")
        Log.i(TAG, "POCKET_RAG_SEARCH_MS fts=$ftsMs embed=$embedMs rerank=$rerankMs pairs=$rerankPairs calls=$rerankCalls engine=${System.currentTimeMillis() - tEngine}")

        // Each of the first three passages that comes from a fragmentary document carries the start
        // of its following pages; every other short passage gets its own page's context.
        val expandIndexes = result.ordered.take(3).mapIndexedNotNull { i, (c, _) -> i.takeIf { c.payload.docId in profile.fragmentaryDocuments } }.toSet()
        Phase14Result(
            hits = result.ordered.mapIndexed { index, (c, fused) ->
                val (expanded, pageEnd) = if (index in expandIndexes)
                    withFollowingPages(database, table, columns, c.payload, profile.medianChars)
                else withPageContext(database, table, columns, c.payload, profile.medianChars) to null
                // A passage cut mid-sentence gets the start of its sentence back from the passage before it.
                val content = if (index < SENTENCE_START_HITS) withSentenceStart(database, table, columns, c.payload, expanded) else expanded
                c.payload.copy(
                    content = content, pageEnd = pageEnd,
                    similarity = c.rawCos, specificityScore = c.rawCos, rankScore = fused, identifierMatched = c.admission != null
                )
            },
            reservedCount = result.reservedCount,
            clauseIdsByChunk = result.clauseIdsByChunk,
            clauseStates = result.clauseStates.map { Phase14ClauseState(it.clauseId, it.state, it.note) },
            notesByChunk = result.notesByChunk,
            topSimilarity = result.topSimilarity,
            packId = status.packId,
            displayName = status.displayName,
            trace = result.trace,
            bestRerank = result.bestRerank,
            rerankQuestion = engine.rerankQuestion
        )
    }

    fun close() {
        runCatching { db?.close() }
        db = null
        phase14Profile = null
    }

    // ------------------------------------------------------------------------------------
    // Pack library: installed packs are kept side by side with their prepared search files
    // (full-text index, corpus profile, calibration, statistics), so switching is a few file
    // renames plus a start that loads saved files instead of rebuilding them.
    // ------------------------------------------------------------------------------------
    private val library by lazy { GenericKbPackLibrary(packDir, dbFile.name, ACTIVE_FILE_PREFIX) }

    private fun activeFiles(): List<File> = library.activeFiles()

    private fun libraryEntry(packId: String): File? = library.entry(packId)

    /** Moves the active pack into the library. Caller must have closed the database. Returns its folder. */
    private fun stashActivePack(): File? {
        if (!dbFile.exists()) return null
        val id = prefs.getString("pack_id", null).orEmpty().ifBlank { status.packId }.ifBlank { sha256Prefix(dbFile) }
        val name = prefs.getString("display_name", null).orEmpty().ifBlank { status.displayName }.ifBlank { "Knowledge Base" }
        return library.stashActive(GenericKbPackLibrary.Meta(id, name, status.rowCount))
            ?.also { Log.i(TAG, "GENERIC_KB_LIBRARY_STASH pack=$id rows=${status.rowCount}") }
    }

    /** Moves a library pack into the active slot. The active slot must be empty. */
    private fun restoreFromLibrary(dir: File): Boolean {
        val meta = library.restore(dir) ?: return false
        prefs.edit().putString("pack_id", meta.packId).putString("display_name", meta.displayName).apply()
        return true
    }

    /** Installed packs: the active one first, then the library. */
    suspend fun installedPacks(): List<InstalledPack> = withContext(Dispatchers.IO) {
        val out = ArrayList<InstalledPack>()
        if (dbFile.exists()) {
            val current = if (status.valid) status else inspectInstalled()
            out += InstalledPack(
                prefs.getString("pack_id", null).orEmpty().ifBlank { current.packId },
                prefs.getString("display_name", null).orEmpty().ifBlank { current.displayName },
                current.rowCount, active = true
            )
        }
        library.entries().forEach { meta ->
            out += InstalledPack(meta.packId, meta.displayName.ifBlank { "Knowledge Base" }, meta.rowCount, active = false)
        }
        out
    }

    /**
     * Makes a library pack the active one (the current one goes to the library). Returns true when
     * the chosen pack is open and ready. On failure the previous pack is restored.
     */
    suspend fun activatePack(packId: String): Boolean = withContext(Dispatchers.IO) {
        val currentId = prefs.getString("pack_id", null).orEmpty().ifBlank { status.packId }
        if (currentId == packId && dbFile.exists()) return@withContext isReady() || initialise()
        val target = libraryEntry(packId) ?: return@withContext false
        val started = System.currentTimeMillis()
        close()
        val stashed = if (dbFile.exists()) stashActivePack() ?: return@withContext initialise().let { false } else null
        if (!restoreFromLibrary(target) || !initialise()) {
            close()
            // Put the chosen pack back into the library and reopen the previous one.
            if (dbFile.exists()) stashActivePack()
            stashed?.let { restoreFromLibrary(it) }
            initialise()
            return@withContext false
        }
        Log.i(TAG, "GENERIC_KB_SWITCHED pack=$packId previous=${currentId.ifBlank { "-" }} elapsed=${System.currentTimeMillis() - started}ms")
        true
    }

    /** Deletes a pack from the library (the active pack cannot be removed this way). */
    // ── Health check (KB validation) ──────────────────────────────────────────────────────

    data class HealthSample(val chunkId: String, val docId: String, val page: Int?, val content: String, val title: String)

    /** Data statistics of the active pack for [KbHealthCheck]; null when no pack is open. */
    /**
     * Text around a passage: its document's same and two previous pages (section headings live
     * there). Used by the topic check: a foot-care passage under "Diabetes" does not repeat the word.
     */
    suspend fun nearbyText(docId: String, page: Int?, maxChars: Int = 12_000): String = withContext(Dispatchers.IO) {
        val database = db ?: return@withContext ""
        val table = status.tableName.ifBlank { return@withContext "" }
        if (docId.isBlank() || page == null) return@withContext ""
        runCatching {
            val cols = columnsFor(database, table)
            if ("doc_id" !in cols || "page_number" !in cols) return@runCatching ""
            val heading = if ("heading_context" in cols) "coalesce(heading_context,'') || ' ' || " else ""
            database.rawQuery(
                "SELECT ${heading}content FROM $table WHERE doc_id = ? AND page_number BETWEEN ? AND ? LIMIT 40",
                arrayOf(docId, (page - 2).toString(), page.toString())
            ).use { c -> buildString { while (c.moveToNext() && length < maxChars) append(c.getString(0).orEmpty()).append('\n') } }
        }.getOrDefault("")
    }

    suspend fun healthStats(sampleSize: Int = 2_000): KbHealthCheck.Stats? = withContext(Dispatchers.IO) {
        val database = db ?: return@withContext null
        val table = status.tableName.ifBlank { return@withContext null }
        runCatching {
            val cols = columnsFor(database, table)
            fun long(sql: String): Long = database.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
            val rows = long("SELECT COUNT(*) FROM $table")
            val docs = if ("doc_id" in cols) long("SELECT COUNT(DISTINCT doc_id) FROM $table") else 0L
            val noEmbedding = if ("embedding" in cols) long("SELECT COUNT(*) FROM $table WHERE embedding IS NULL OR length(embedding) < 16") else rows
            val short = long("SELECT COUNT(*) FROM $table WHERE length(trim(content)) < 80")
            val dup = rows - long("SELECT COUNT(DISTINCT content) FROM $table")
            val noPage = if ("page_number" in cols) long("SELECT COUNT(*) FROM $table WHERE page_number IS NULL") else rows
            var sampled = 0; var mid = 0; var dev = 0
            database.rawQuery("SELECT content FROM $table ORDER BY random() LIMIT $sampleSize", null).use { c ->
                while (c.moveToNext()) {
                    val text = c.getString(0).orEmpty()
                    sampled++
                    if (KbHealthCheck.midSentenceStart(text)) mid++
                    if (KbHealthCheck.mostlyDevanagari(text)) dev++
                }
            }
            var titles = 0; var fileNames = 0
            val hasSources = database.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='knowledge_sources' LIMIT 1", null).use { it.moveToFirst() }
            if (hasSources && "title" in columnsFor(database, "knowledge_sources")) {
                database.rawQuery("SELECT title, source_name FROM knowledge_sources", null).use { c ->
                    while (c.moveToNext()) {
                        titles++
                        val title = c.getString(0)?.takeIf { it.isNotBlank() } ?: c.getString(1)
                        if (KbHealthCheck.fileNameLike(title)) fileNames++
                    }
                }
            }
            KbHealthCheck.Stats(rows, docs, noEmbedding, short, dup, noPage, sampled, mid, dev, titles, fileNames)
        }.onFailure { Log.w(TAG, "KB_HEALTH_STATS_FAILED ${it.javaClass.simpleName}:${it.message}") }.getOrNull()
    }

    /** [n] content-rich passages spread across documents (round robin), for the search check. */
    suspend fun healthSamples(n: Int): List<HealthSample> = withContext(Dispatchers.IO) {
        val database = db ?: return@withContext emptyList()
        val table = status.tableName.ifBlank { return@withContext emptyList() }
        runCatching {
            val cols = columnsFor(database, table)
            val doc = if ("doc_id" in cols) "doc_id" else "''"
            val page = if ("page_number" in cols) "page_number" else "NULL"
            val source = if ("source_name" in cols) "source_name" else "''"
            val small = if ("small_fragment" in cols) "AND COALESCE(small_fragment, 0) = 0" else ""
            val byDoc = LinkedHashMap<String, ArrayDeque<HealthSample>>()
            database.rawQuery(
                "SELECT chunk_id, $doc, $page, content, $source FROM $table WHERE length(content) BETWEEN 300 AND 6000 $small ORDER BY random() LIMIT ${n * 6}",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    val s = HealthSample(c.getString(0).orEmpty(), c.getString(1).orEmpty(), if (c.isNull(2)) null else c.getInt(2), c.getString(3).orEmpty(), c.getString(4).orEmpty())
                    byDoc.getOrPut(s.docId) { ArrayDeque() }.addLast(s)
                }
            }
            val out = ArrayList<HealthSample>()
            while (out.size < n && byDoc.values.any { it.isNotEmpty() }) {
                for (q in byDoc.values) { if (out.size >= n) break; q.removeFirstOrNull()?.let(out::add) }
            }
            out
        }.onFailure { Log.w(TAG, "KB_HEALTH_SAMPLES_FAILED ${it.javaClass.simpleName}:${it.message}") }.getOrDefault(emptyList())
    }

    suspend fun removeLibraryPack(packId: String): Boolean = withContext(Dispatchers.IO) {
        library.remove(packId)
    }
}
