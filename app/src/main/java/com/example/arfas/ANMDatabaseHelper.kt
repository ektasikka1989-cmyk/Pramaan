package com.example.arfas

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

private const val TAG = "ANMDatabaseHelper"

class ANMDatabaseHelper(private val context: Context) {

    @Volatile var lastTopSimilarity: Float = 0f
        private set

    companion object {
        private const val DB_NAME = "ANM_knowledge_base.db"
        private const val ASSETS_PATH = "databases/$DB_NAME"
        private const val TABLE = "sop_chunks"
        private const val SEMANTIC_THRESHOLD = 0.35f
    }

    private var db: SQLiteDatabase? = null
    private val recordLexicalRetriever = HybridKnowledgeRetriever(
        File(context.filesDir, "knowledge_packs/anm_records.fts.db"),
        TAG
    )

    var semanticEngine: SemanticSearchEngine? = null

    data class DbStats(
        val totalSops: Int = 0,
        val byTier: Map<String, Int> = emptyMap(),
        val byLanguage: Map<String, Int> = emptyMap(),
        val categories: List<String> = emptyList(),
        val hasEmbeddings: Boolean = false,
        val isHealthy: Boolean = false
    )

    data class InstalledDbStatus(
        val fileExists: Boolean = false,
        val schemaCompatible: Boolean = false,
        val valid: Boolean = false,
        val rowCount: Int = 0,
        val embeddingCount: Int = 0
    )

    private var cachedStats: DbStats? = null

    val installedDbPath: String
        get() = context.getDatabasePath(DB_NAME).absolutePath

    private val legacyPackPath: String
        get() = File(
            context.filesDir,
            "knowledge_packs/$DB_NAME"
        ).absolutePath

    fun getInstallTargetFile(): File =
        context.getDatabasePath(DB_NAME).also {
            it.parentFile?.mkdirs()
        }

    /**
     * Safely install a user-selected ANM SQLite database. The candidate is copied
     * beside the live DB, validated read-only, then atomically swapped. The previous
     * working DB is restored if the new file cannot be reopened.
     */
    suspend fun installFromUri(
        uri: Uri,
        onProgress: (String) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        val target = getInstallTargetFile()
        val parent = target.parentFile ?: return@withContext false
        parent.mkdirs()
        val candidate = File(parent, "${target.name}.candidate")
        val backup = File(parent, "${target.name}.backup")

        runCatching { candidate.delete() }
        runCatching { backup.delete() }

        onProgress("Copying ANM Knowledge Base…")
        val copied = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(candidate).use { output -> input.copyTo(output, 1024 * 1024) }
            } ?: return@withContext false
            candidate.exists() && candidate.length() > 0L
        } catch (t: Throwable) {
            Log.e(TAG, "KB_INSTALL_COPY_FAILED ${t.message}", t)
            false
        }
        if (!copied) {
            candidate.delete()
            return@withContext false
        }

        val before = inspectDatabaseFile(candidate)
        if (before.rowCount <= 0 || !before.schemaCompatible) {
            candidate.delete()
            Log.w(
                TAG,
                "KB_INSTALL_REJECTED missing_or_invalid_schema=true " +
                    "v32Compatible=${before.schemaCompatible} rows=${before.rowCount}"
            )
            return@withContext false
        }

        // Curated seed databases intentionally ship without embeddings. Build the
        // exact multilingual-e5-small passage vectors on-device with the SAME
        // SemanticSearchEngine used for runtime queries. Never accept zero/proxy vectors.
        if (!before.valid) {
            onProgress("Preparing semantic index…")
            val indexed = indexCandidateDatabase(candidate, onProgress)
            if (!indexed) {
                candidate.delete()
                Log.w(TAG, "KB_INSTALL_REJECTED indexing_failed=true")
                return@withContext false
            }
        }

        onProgress("Validating semantic index…")
        if (!validateCandidateDatabase(candidate)) {
            candidate.delete()
            Log.w(TAG, "KB_INSTALL_REJECTED candidateInvalid=true")
            return@withContext false
        }

        // Close the live handle before replacing the file beneath SQLite.
        close()
        val hadExisting = target.exists()
        if (hadExisting && !target.renameTo(backup)) {
            candidate.delete()
            Log.e(TAG, "KB_INSTALL_BACKUP_FAILED path=${target.absolutePath}")
            return@withContext false
        }

        val promoted = candidate.renameTo(target)
        if (!promoted) {
            if (hadExisting && backup.exists()) backup.renameTo(target)
            candidate.delete()
            Log.e(TAG, "KB_INSTALL_PROMOTE_FAILED path=${target.absolutePath}")
            return@withContext false
        }

        val reopened = initialise()
        if (!reopened) {
            close()
            target.delete()
            if (hadExisting && backup.exists()) backup.renameTo(target)
            val restored = if (target.exists()) initialise() else false
            Log.e(TAG, "KB_INSTALL_ROLLBACK newDbFailed=true restored=$restored")
            return@withContext false
        }

        backup.delete()
        onProgress("ANM Knowledge Base ready")
        Log.i(TAG, "KB_INSTALL_SUCCESS rows=${getRecordCount()} path=${target.absolutePath}")
        true
    }

    private fun inspectDatabaseFile(file: File): InstalledDbStatus {
        if (!file.exists() || file.length() <= 0L) {
            return InstalledDbStatus(fileExists = false)
        }

        var candidateDb: SQLiteDatabase? = null
        return try {
            candidateDb = SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )

            val tables = mutableSetOf<String>()
            candidateDb.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table'",
                null
            ).use { c ->
                while (c.moveToNext()) tables += c.getString(0)
            }

            if (TABLE !in tables) {
                return InstalledDbStatus(fileExists = true)
            }

            val columns = mutableSetOf<String>()
            candidateDb.rawQuery("PRAGMA table_info($TABLE)", null).use { c ->
                while (c.moveToNext()) columns += c.getString(1)
            }

            val required = setOf(
                "file_name", "page_number", "content", "embedding",
                "record_id", "category", "condition", "emergency",
                "section", "validation_status"
            )

            val structuralTables =
                setOf("record_applicability", "sop_records", "kb_meta")
            var schemaCompatible =
                columns.containsAll(required) &&
                    tables.containsAll(structuralTables)

            if (schemaCompatible) {
                val applicabilityColumns = mutableSetOf<String>()
                candidateDb.rawQuery(
                    "PRAGMA table_info(record_applicability)",
                    null
                ).use { c ->
                    while (c.moveToNext()) applicabilityColumns += c.getString(1)
                }

                schemaCompatible = applicabilityColumns.containsAll(
                    setOf(
                        "record_id", "category", "condition", "emergency",
                        "identify_text", "assess_text", "refer_text", "counsel_text", "source", "validation_status"
                    )
                )
            }

            if (schemaCompatible) {
                val revision = try {
                    candidateDb.rawQuery(
                        "SELECT value FROM kb_meta WHERE key='engine_revision' LIMIT 1",
                        null
                    ).use { c ->
                        if (c.moveToFirst()) c.getString(0).orEmpty() else ""
                    }
                } catch (_: Throwable) {
                    ""
                }
                schemaCompatible = revision in setOf("V3_1_FACT_APPLICABILITY", "V3_2_CONTEXT_APPLICABILITY")
            }

            val rowCount = candidateDb.rawQuery(
                "SELECT COUNT(*) FROM $TABLE " +
                    "WHERE content IS NOT NULL AND length(content) > 0",
                null
            ).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }

            if (schemaCompatible) {
                val profileCount = candidateDb.rawQuery(
                    "SELECT COUNT(*) FROM record_applicability",
                    null
                ).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else 0
                }
                if (profileCount != rowCount || rowCount <= 0) {
                    schemaCompatible = false
                }
            }

            // A correctly-sized BLOB is not automatically a real embedding.
            var embeddingCount = 0
            if (columns.contains("embedding")) {
                candidateDb.rawQuery(
                    "SELECT embedding FROM $TABLE " +
                        "WHERE embedding IS NOT NULL AND length(embedding) = 1536",
                    null
                ).use { c ->
                    while (c.moveToNext()) {
                        val blob = try { c.getBlob(0) } catch (_: Throwable) { null }
                        if (blob != null && isUsableEmbeddingBlob(blob)) {
                            embeddingCount++
                        }
                    }
                }
            }

            val minimumEmbeddingCoverage =
                if (rowCount > 0) kotlin.math.ceil(rowCount * 0.98).toInt() else 0

            val valid =
                schemaCompatible &&
                    rowCount > 0 &&
                    embeddingCount >= minimumEmbeddingCoverage

            InstalledDbStatus(
                fileExists = true,
                schemaCompatible = schemaCompatible,
                valid = valid,
                rowCount = rowCount,
                embeddingCount = embeddingCount
            )
        } catch (t: Throwable) {
            Log.e(TAG, "KB_INSTALL_VALIDATE_FAILED ${t.message}", t)
            InstalledDbStatus(fileExists = true)
        } finally {
            runCatching { candidateDb?.close() }
        }
    }

    private fun validateCandidateDatabase(file: File): Boolean {
        val status = inspectDatabaseFile(file)
        Log.i(
            TAG,
            "KB_INSTALL_VALIDATE rows=${status.rowCount} embedding1536=${status.embeddingCount} " +
                "v32Compatible=${status.schemaCompatible} valid=${status.valid}"
        )
        return status.valid
    }


    private fun isUsableEmbeddingBlob(blob: ByteArray): Boolean {
        if (blob.size != 1536) return false
        return try {
            val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
            var norm2 = 0.0
            var maxAbs = 0.0
            repeat(384) {
                val value = buffer.float
                if (!value.isFinite()) return false
                val d = value.toDouble()
                norm2 += d * d
                maxAbs = maxOf(maxAbs, kotlin.math.abs(d))
            }
            // E5 vectors produced by the packaged engine are normalized near 1.0.
            // Keep a deliberately broad lower bound so valid vectors are accepted,
            // while zero/all-near-zero placeholder vectors are rejected.
            norm2 > 0.25 && maxAbs > 1e-5
        } catch (_: Throwable) {
            false
        }
    }

    private fun floatArrayToBlob(vector: FloatArray): ByteArray? {
        if (vector.size != 384 || vector.any { !it.isFinite() }) return null
        var norm2 = 0.0
        vector.forEach { v ->
            val d = v.toDouble()
            norm2 += d * d
        }
        if (norm2 <= 0.25) return null

        val buffer = ByteBuffer.allocate(1536).order(ByteOrder.LITTLE_ENDIAN)
        vector.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    /**
     * Converts a structurally valid curated seed DB into a production semantic DB
     * using the exact on-device multilingual-e5-small engine already used for query
     * embeddings. Existing non-zero compatible vectors are preserved.
     */
    private suspend fun indexCandidateDatabase(
        file: File,
        onProgress: (String) -> Unit
    ): Boolean {
        val engine = semanticEngine
        if (engine?.isReady != true) {
            Log.e(TAG, "KB_INDEX_ABORT semantic_engine_not_ready")
            return false
        }

        var candidateDb: SQLiteDatabase? = null
        return try {
            candidateDb = SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE
            )
            val database = candidateDb ?: return false

            val rows = ArrayList<Pair<Long, String>>()
            database.rawQuery(
                "SELECT rowid,content,embedding FROM $TABLE ORDER BY rowid",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    val rowId = c.getLong(0)
                    val content = c.getString(1).orEmpty().trim()
                    if (content.isBlank()) continue
                    val existing = if (c.isNull(2)) null else runCatching { c.getBlob(2) }.getOrNull()
                    if (existing == null || !isUsableEmbeddingBlob(existing)) {
                        rows += rowId to content
                    }
                }
            }

            if (rows.isEmpty()) {
                Log.i(TAG, "KB_INDEX_SKIP all_embeddings_usable=true")
                return true
            }

            Log.i(TAG, "KB_INDEX_BEGIN missing=${rows.size}")
            val generated = ArrayList<Pair<Long, ByteArray>>(rows.size)
            var done = 0

            // ONNX inference dominates the work. Generate vectors first, then write
            // them in one SQLite transaction so per-row fsync/journal overhead does
            // not make first-time installation unnecessarily slow.
            for ((rowId, content) in rows) {
                val vector = try {
                    engine.embed("passage: $content")
                } catch (t: Throwable) {
                    Log.e(TAG, "KB_INDEX_EMBED_FAILED row=$rowId ${t.message}", t)
                    null
                } ?: return false

                val blob = floatArrayToBlob(vector) ?: run {
                    Log.e(TAG, "KB_INDEX_VECTOR_INVALID row=$rowId dims=${vector.size}")
                    return false
                }

                generated += rowId to blob
                done++
                if (done == 1 || done % 25 == 0 || done == rows.size) {
                    onProgress("Indexing ANM knowledge… $done/${rows.size}")
                    Log.i(TAG, "KB_INDEX_PROGRESS done=$done total=${rows.size}")
                }
            }

            database.beginTransaction()
            try {
                for ((rowId, blob) in generated) {
                    val values = ContentValues().apply { put("embedding", blob) }
                    val changed = database.update(
                        TABLE,
                        values,
                        "rowid=?",
                        arrayOf(rowId.toString())
                    )
                    if (changed != 1) {
                        Log.e(TAG, "KB_INDEX_UPDATE_FAILED row=$rowId changed=$changed")
                        return false
                    }
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }

            val status = inspectDatabaseFile(file)
            Log.i(
                TAG,
                "KB_INDEX_END rows=${status.rowCount} usable=${status.embeddingCount} valid=${status.valid}"
            )
            status.valid
        } catch (t: Throwable) {
            Log.e(TAG, "KB_INDEX_FAILED ${t.message}", t)
            false
        } finally {
            runCatching { candidateDb?.close() }
        }
    }

    /**
     * Reports whether a structurally valid ANM database is already installed on
     * disk. This deliberately does NOT require the E5 semantic engine to be
     * initialized, so Settings can distinguish "Installed" from "Search ready".
     */
    suspend fun inspectInstalledDatabase(): InstalledDbStatus = withContext(Dispatchers.IO) {
        inspectDatabaseFile(getInstallTargetFile())
    }

    suspend fun initialise(): Boolean =
        withContext(Dispatchers.IO) {
            try {
                withTimeout(30_000L) {
                    initialiseInternal()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "KB_INIT_FAILED ${t.message}", t)
                false
            }
        }

    private fun initialiseInternal(): Boolean {
        return try {
            val dbFile = context.getDatabasePath(DB_NAME)

            when {
                dbFile.exists() -> {
                    Log.d(
                        TAG,
                        "KB_TRACE_DB_FILE path=${dbFile.absolutePath} " +
                            "name=${dbFile.name} exists=true size=${dbFile.length()}"
                    )
                }

                File(legacyPackPath).exists() -> {
                    dbFile.parentFile?.mkdirs()
                    File(legacyPackPath).copyTo(dbFile, overwrite = true)

                    Log.d(
                        TAG,
                        "KB_TRACE_DB_COPIED_FROM_LEGACY path=${dbFile.absolutePath} " +
                            "size=${dbFile.length()}"
                    )
                }

                else -> {
                    dbFile.parentFile?.mkdirs()
                    copyFromAssets(dbFile)

                    Log.d(
                        TAG,
                        "KB_TRACE_DB_COPIED_FROM_ASSETS path=${dbFile.absolutePath} " +
                            "size=${dbFile.length()}"
                    )
                }
            }

            if (!dbFile.exists() || dbFile.length() == 0L) {
                Log.e(TAG, "KB_TRACE_DB_INVALID file missing/empty")
                return false
            }

            db?.close()
            db = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )

            Log.d(
                TAG,
                "KB_TRACE_DB_OPEN path=${dbFile.absolutePath} " +
                    "open=${db?.isOpen == true}"
            )

            val database = db ?: return false

            val tables = mutableListOf<String>()

            database.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    tables.add(c.getString(0))
                }
            }

            Log.d(
                TAG,
                "KB_TRACE_DB_TABLES ${tables.joinToString(",")}"
            )

            if (TABLE !in tables) {
                Log.e(
                    TAG,
                    "KB_TRACE_DB_SCHEMA_MISMATCH expected=$TABLE " +
                        "tables=${tables.joinToString(",")}"
                )
                database.close()
                db = null
                return false
            }

            val installedRevision = try {
                database.rawQuery(
                    "SELECT value FROM kb_meta WHERE key='engine_revision' LIMIT 1",
                    null
                ).use { c -> if (c.moveToFirst()) c.getString(0).orEmpty() else "" }
            } catch (_: Throwable) {
                ""
            }
            if (installedRevision !in setOf("V3_1_FACT_APPLICABILITY", "V3_2_CONTEXT_APPLICABILITY") || !hasV32ApplicabilitySchema(database)) {
                Log.e(
                    TAG,
                    "KB_TRACE_DB_REVISION_MISMATCH expected=V3_1_FACT_APPLICABILITY|V3_2_CONTEXT_APPLICABILITY actual=$installedRevision"
                )
                database.close()
                db = null
                cachedStats = null
                return false
            }
            Log.i(TAG, "KB_ENGINE_REV $installedRevision databaseReady=true schema=context_applicability_compatible")

            val columns = mutableListOf<String>()

            database.rawQuery(
                "PRAGMA table_info($TABLE)",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    columns.add(c.getString(1))
                }
            }

            Log.d(
                TAG,
                "KB_TRACE_DB_COLUMNS ${columns.joinToString(",")}"
            )

            val required = setOf(
                "file_name",
                "page_number",
                "content",
                "embedding"
            )

            if (!columns.containsAll(required)) {
                Log.e(
                    TAG,
                    "KB_TRACE_DB_COLUMNS_MISSING required=$required actual=$columns"
                )
                database.close()
                db = null
                return false
            }

            val count = database.rawQuery(
                "SELECT COUNT(*) FROM $TABLE",
                null
            ).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }

            val contentCount = database.rawQuery(
                "SELECT COUNT(*) FROM $TABLE WHERE content IS NOT NULL AND length(content) > 0",
                null
            ).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }

            val embeddingCount = database.rawQuery(
                "SELECT COUNT(*) FROM $TABLE WHERE embedding IS NOT NULL AND length(embedding) = 1536",
                null
            ).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }

            Log.d(
                TAG,
                "KB_TRACE_DB_COUNTS rows=$count content=$contentCount " +
                    "embedding1536=$embeddingCount"
            )

            if (count == 0) {
                Log.e(TAG, "KB_TRACE_DB_EMPTY table=$TABLE")
                cachedStats = DbStats()
                return false
            }

            if (embeddingCount == 0) {
                Log.w(
                    TAG,
                    "KB_TRACE_DB_NO_VALID_EMBEDDINGS rows=$count"
                )
            }

            database.rawQuery(
                "SELECT file_name,page_number,length(content),length(embedding) " +
                    "FROM $TABLE LIMIT 1",
                null
            ).use { c ->
                if (c.moveToFirst()) {
                    Log.d(
                        TAG,
                        "KB_TRACE_DB_SAMPLE file='${c.getString(0)}' " +
                            "page=${c.getInt(1)} contentChars=${c.getInt(2)} " +
                            "embeddingBytes=${c.getInt(3)}"
                    )
                }
            }

            cachedStats = buildDbStats()

            Log.d(
                TAG,
                "KB_TRACE_DB_READY records=$count " +
                    "hasEmbeddings=${cachedStats?.hasEmbeddings == true}"
            )

            true
        } catch (t: Throwable) {
            Log.e(TAG, "KB_TRACE_DB_INIT_EXCEPTION ${t.message}", t)
            db?.close()
            db = null
            cachedStats = null
            false
        }
    }

    private fun buildDbStats(): DbStats {
        val database = db ?: return DbStats()

        return try {
            val total = database.rawQuery(
                "SELECT COUNT(*) FROM $TABLE",
                null
            ).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }

            var embeddingCount = 0
            database.rawQuery(
                "SELECT embedding FROM $TABLE WHERE embedding IS NOT NULL AND length(embedding) = 1536",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    val blob = try { c.getBlob(0) } catch (_: Throwable) { null }
                    if (blob != null && isUsableEmbeddingBlob(blob)) embeddingCount++
                }
            }

            val categories = mutableListOf<String>()

            database.rawQuery(
                "SELECT DISTINCT file_name FROM $TABLE " +
                    "WHERE file_name IS NOT NULL LIMIT 20",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    c.getString(0)?.let { categories.add(it) }
                }
            }

            val minimumEmbeddingCoverage =
                if (total > 0) kotlin.math.ceil(total * 0.98).toInt() else 0

            DbStats(
                totalSops = total,
                categories = categories,
                hasEmbeddings = total > 0 && embeddingCount >= minimumEmbeddingCoverage,
                isHealthy = total > 0
            )
        } catch (t: Throwable) {
            Log.e(TAG, "KB_TRACE_DB_STATS_FAILED ${t.message}", t)
            DbStats()
        }
    }

    fun getDbStats(): DbStats =
        cachedStats ?: DbStats()

    fun getDbSummaryForUser(): String {
        val s = cachedStats ?: return "Knowledge base not loaded."

        if (!s.isHealthy) {
            return "Knowledge base appears empty. Please reinstall."
        }

        val searchMode =
            if (s.hasEmbeddings && semanticEngine?.isReady == true) {
                "Semantic evidence retrieval"
            } else {
                "Unavailable — no fallback retrieval"
            }

        return buildString {
            appendLine("Knowledge base: ${s.totalSops} SOP records loaded")
            appendLine("Search mode: $searchMode")
        }.trim()
    }

    private fun parseTier(content: String, source: String): EmergencyTier {
        val probe = (content.take(500) + " " + source).uppercase(Locale.US)
        return when {
            "RED_EMERGENCY" in probe || "🔴" in probe || " EMERGENCY" in probe ->
                EmergencyTier.RED_EMERGENCY
            "YELLOW_URGENT" in probe || "🟡" in probe || " URGENT" in probe ->
                EmergencyTier.YELLOW_URGENT
            "GREEN_ROUTINE" in probe || "🟢" in probe || " ROUTINE" in probe ->
                EmergencyTier.GREEN_ROUTINE
            else -> EmergencyTier.UNKNOWN
        }
    }

    private fun parseTaggedValue(content: String, label: String): String {
        val rx = Regex("(?im)^\\s*${Regex.escape(label)}\\s*:\\s*(.+?)\\s*$")
        return rx.find(content)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    suspend fun searchSops(
        query: String,
        maxResults: Int = 5,
        minSemanticScore: Float = 0.52f
    ): List<SopRecord> =
        searchSemanticMulti(
            queries = listOf(query),
            maxResults = maxResults,
            minSemanticScore = minSemanticScore
        ).map { it.record }

    data class AdaptiveSopMatch(
        val record: SopRecord,
        val semanticScore: Float,
        val lexicalExact: Boolean,
        val matchType: String,
        val lexicalSupport: Float = 0f
    )


    /**
     * Record-level candidate used by the selected-KB clinical retrieval path.
     * V3.2 stores one semantic applicability row per active source record, then
     * performs a separate fact-provenance applicability decision before any full
     * ACT/TREAT evidence is exposed to the answer model.
     */
    data class KbRecordCandidate(
        val recordId: Int,
        val category: String,
        val conditionName: String,
        val emergency: String,
        val currentSemanticScore: Float,
        val contextSemanticScore: Float,
        val rankingScore: Float,
        val lexicalSupport: Float = 0f,
        val applicabilityText: String,
        val fileName: String,
        val sourceInfo: String = "",
        val validationStatus: String = "",
        val activeAnchor: Boolean = false
    )

    private fun hasV32ApplicabilitySchema(database: SQLiteDatabase): Boolean {
        return try {
            val tables = HashSet<String>()
            database.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table'",
                null
            ).use { c ->
                while (c.moveToNext()) tables += c.getString(0)
            }
            if ("record_applicability" !in tables) return false

            val chunkCols = HashSet<String>()
            database.rawQuery("PRAGMA table_info($TABLE)", null).use { c ->
                while (c.moveToNext()) chunkCols += c.getString(1)
            }
            val appCols = HashSet<String>()
            database.rawQuery("PRAGMA table_info(record_applicability)", null).use { c ->
                while (c.moveToNext()) appCols += c.getString(1)
            }

            chunkCols.containsAll(
                setOf("record_id", "category", "condition", "emergency", "section", "validation_status")
            ) &&
                appCols.containsAll(
                    setOf(
                        "record_id", "category", "condition", "emergency",
                        "identify_text", "assess_text", "refer_text", "counsel_text", "source", "validation_status"
                    )
                )
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Compatibility schema check used only by loadRecordEvidence() if a database
     * does not provide a full sop_records row for the selected record.
     *
     * V3.2 database admission requires hasV32ApplicabilitySchema(); this
     * helper merely keeps the retained rich-sop_chunks fallback compilable.
     */
    private fun hasRichV2Schema(database: SQLiteDatabase): Boolean {
        return try {
            val cols = HashSet<String>()
            database.rawQuery("PRAGMA table_info($TABLE)", null).use { c ->
                while (c.moveToNext()) cols += c.getString(1)
            }
            cols.containsAll(
                setOf(
                    "record_id", "category", "condition", "emergency",
                    "section", "chunk_part", "validation_status"
                )
            )
        } catch (_: Throwable) {
            false
        }
    }

    private fun boundedApplicabilityText(
        condition: String,
        category: String,
        emergency: String,
        identify: String,
        refer: String,
        counsel: String,
        assess: String
    ): String = buildString {
        append("CONDITION: ").append(condition.trim().take(180)).append('\n')
        append("CATEGORY: ").append(category.trim().take(100)).append('\n')
        append("EMERGENCY: ").append(emergency.trim().take(32)).append('\n')
        if (identify.isNotBlank()) {
            append("IDENTIFY: ").append(identify.trim().replace(Regex("\\s+"), " ").take(240)).append('\n')
        }
        // Applicability selectors receive a bounded prefix of this text. Keep ASSESS
        // ahead of downstream REFER/COUNSEL guidance so a missing test or measurement
        // that the SOP itself tells the worker to assess is not mistaken for an entry
        // prerequisite. This path is used only by the user-selected KB workflow.
        if (assess.isNotBlank()) {
            append("ASSESS: ").append(assess.trim().replace(Regex("\\s+"), " ").take(220)).append('\n')
        }
        if (refer.isNotBlank()) {
            append("REFER: ").append(refer.trim().replace(Regex("\\s+"), " ").take(220)).append('\n')
        }
        if (counsel.isNotBlank()) {
            append("COUNSEL: ").append(counsel.trim().replace(Regex("\\s+"), " ").take(360))
        }
    }.trim().take(900)

    /** Build/query a source-derived FTS5 sidecar over record applicability text. */
    private fun ensureRecordLexicalIndex(database: SQLiteDatabase): Boolean {
        if (!hasV32ApplicabilitySchema(database)) return false
        val count = database.rawQuery(
            "SELECT COUNT(*) FROM $TABLE s JOIN record_applicability a ON a.record_id=s.record_id WHERE s.record_id IS NOT NULL",
            null
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        val sourceFile = getInstallTargetFile()
        val signature = recordLexicalRetriever.stableSignature(
            "record_applicability_v3_no_source",
            count.toString(),
            sourceFile.length().toString(),
            sourceFile.lastModified().toString()
        )
        if (recordLexicalRetriever.isCurrent(signature)) return true
        val documents = ArrayList<HybridKnowledgeRetriever.Document>(count.coerceAtLeast(16))
        database.rawQuery(
            "SELECT s.record_id,s.category,s.condition,s.emergency," +
                "a.identify_text,a.assess_text,a.refer_text,a.counsel_text,a.source " +
                "FROM $TABLE s JOIN record_applicability a ON a.record_id=s.record_id " +
                "WHERE s.record_id IS NOT NULL",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getInt(0).toString()
                val text = buildString {
                    for (i in 1..7) if (!c.isNull(i)) append(c.getString(i)).append(' ')
                }
                if (text.isNotBlank()) documents += HybridKnowledgeRetriever.Document(id, text)
            }
        }
        return recordLexicalRetriever.ensureIndex(signature, documents.asSequence())
    }

    /**
     * V3.2 Stage A — candidate RECORD retrieval only.
     *
     * The database contains one E5 embedding per active clinical record.  The
     * embedded text is restricted to source-derived CONDITION/CATEGORY/EMERGENCY/IDENTIFY/ASSESS/REFER/COUNSEL.
     * ACT/TREAT/KEYWORDS never participate in candidate similarity.
     *
     * No lexical keyword arm and no disease/population routing table exists here.
     * If a case already has an accepted record anchor, that record is kept in the
     * candidate set and the Notebook-aware context view becomes the semantic ranking
     * view for competing records. Before the first anchor, a distinct Notebook/history context view already owns ranking so short follow-ups cannot discard established case facts.
     *
     * There is deliberately no hard semantic cutoff at this stage.  E5 is a recall
     * mechanism; the detached applicability gate makes the clinical admission decision.
     */
    suspend fun searchRecordCandidates(
        queries: List<String>,
        activeRecordIds: List<Int> = emptyList(),
        maxRecords: Int = 16
    ): List<KbRecordCandidate> = withContext(Dispatchers.IO) {
        val database = db ?: return@withContext emptyList()
        val engine = semanticEngine ?: return@withContext emptyList()

        if (!database.isOpen || !engine.isReady || !hasV32ApplicabilitySchema(database)) {
            Log.w(
                TAG,
                "KB_RECORD_CANDIDATE_UNAVAILABLE rev=V5_FTS_RRF " +
                    "dbOpen=${database.isOpen} semantic=${engine.isReady} v32Schema=${database.isOpen && hasV32ApplicabilitySchema(database)}"
            )
            return@withContext emptyList()
        }

        val cleanQueries = queries
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(2)
        if (cleanQueries.isEmpty()) return@withContext emptyList()

        val queryEmbeddings = cleanQueries.map { q ->
            try {
                engine.embed(if (q.startsWith("query: ")) q else "query: $q")
                    ?.takeIf { it.size == 384 && it.all { v -> v.isFinite() } }
            } catch (t: Throwable) {
                Log.w(TAG, "KB_RECORD_QUERY_EMBED_FAILED ${t.message}")
                null
            }
        }
        val currentEmbedding = queryEmbeddings.getOrNull(0)
            ?: return@withContext emptyList()
        val contextEmbedding = queryEmbeddings.getOrNull(1) ?: currentEmbedding
        val hasDistinctCaseContext = cleanQueries.size > 1

        /*
         * V5 hybrid recall: semantic E5 + SQLite FTS5/BM25. The FTS sidecar is
         * built entirely from installed source fields and contains no domain routing
         * vocabulary. RRF controls recall only; applicability selection remains the
         * clinical admission authority.
         */
        ensureRecordLexicalIndex(database)
        val lexicalHits = recordLexicalRetriever.search(cleanQueries.joinToString(" "), maxRecords.coerceIn(12, 40))
        val lexicalMode = recordLexicalRetriever.searchMode()
        val lexicalRankByRecord = lexicalHits.mapNotNull { hit ->
            hit.id.toIntOrNull()?.let { it to hit.rank }
        }.toMap()
        val bestLexical = 1f / (HybridKnowledgeRetriever.DEFAULT_RRF_K + 1).toFloat()

        data class Scored(
            val recordId: Int,
            val category: String,
            val condition: String,
            val emergency: String,
            val fileName: String,
            val validationStatus: String,
            val source: String,
            val identify: String,
            val assess: String,
            val refer: String,
            val counsel: String,
            val current: Float,
            val contextual: Float,
            val ranking: Float,
            val lexical: Float,
            val active: Boolean
        ) {
            val recallScore: Float
                get() = (ranking * 0.82f + lexical * 0.18f).coerceIn(-1f, 1f)
        }

        val activeSet = activeRecordIds.filter { it > 0 }.toSet()
        val scored = ArrayList<Scored>()
        var scanned = 0
        var valid = 0

        database.rawQuery(
            "SELECT s.file_name,s.embedding,s.record_id,s.category,s.condition,s.emergency,s.validation_status," +
                "a.identify_text,a.assess_text,a.refer_text,a.counsel_text,a.source " +
                "FROM $TABLE s JOIN record_applicability a ON a.record_id=s.record_id " +
                "WHERE s.record_id IS NOT NULL ORDER BY s.record_id",
            null
        ).use { c ->
            while (c.moveToNext()) {
                scanned++
                val blob = try { c.getBlob(1) } catch (_: Throwable) { null } ?: continue
                if (!isUsableEmbeddingBlob(blob)) continue
                val vector = engine.blobToFloatArray(blob) ?: continue
                if (vector.size != 384 || vector.any { !it.isFinite() }) continue
                valid++

                val recordId = c.getInt(2)
                val category = c.getString(3).orEmpty()
                val condition = c.getString(4).orEmpty()
                val identify = c.getString(7).orEmpty()
                val assess = c.getString(8).orEmpty()
                val currentScore = engine.cosineSimilarity(currentEmbedding, vector)
                val contextScore = engine.cosineSimilarity(contextEmbedding, vector)
                val active = recordId in activeSet
                val rank = if (hasDistinctCaseContext) contextScore else currentScore
                val lexical = lexicalRankByRecord[recordId]?.let { rank ->
                    ((1f / (HybridKnowledgeRetriever.DEFAULT_RRF_K + rank).toFloat()) / bestLexical)
                        .coerceIn(0f, 1f)
                } ?: 0f

                scored += Scored(
                    recordId = recordId,
                    category = category,
                    condition = condition,
                    emergency = c.getString(5).orEmpty(),
                    fileName = c.getString(0).orEmpty(),
                    validationStatus = c.getString(6).orEmpty(),
                    source = c.getString(11).orEmpty(),
                    identify = identify,
                    assess = assess,
                    refer = c.getString(9).orEmpty(),
                    counsel = c.getString(10).orEmpty(),
                    current = currentScore,
                    contextual = contextScore,
                    ranking = rank,
                    lexical = lexical,
                    active = active
                )
            }
        }

        if (scored.isEmpty()) return@withContext emptyList()

        val limit = maxRecords.coerceIn(8, 20)
        // Phase 10: active status guarantees inclusion, never ranking privilege.
        // The selector receives activeAnchor explicitly and can reason about continuity,
        // while current evidence remains the ordering authority.
        val currentOrdered = scored.sortedWith(
            compareByDescending<Scored> { it.current }
                .thenByDescending { it.contextual }
        )
        val semanticOrdered = scored.sortedWith(
            compareByDescending<Scored> { it.ranking }
                .thenByDescending { it.current }
                .thenByDescending { it.contextual }
        )
        val semanticRankByRecord = semanticOrdered.mapIndexed { index, row -> row.recordId to (index + 1) }.toMap()
        val rrfOrdered = scored.sortedWith(
            compareByDescending<Scored> { row ->
                HybridKnowledgeRetriever.rrfScore(
                    semanticRankByRecord[row.recordId],
                    lexicalRankByRecord[row.recordId]
                )
            }.thenByDescending { it.ranking }
        )

        val chosen = ArrayList<Scored>(limit)
        fun addIfMissing(row: Scored) {
            if (chosen.size < limit && chosen.none { it.recordId == row.recordId }) chosen += row
        }

        // Active anchors are recall-visible, but receive no ranking privilege.
        semanticOrdered.filter { it.active }.forEach(::addIfMissing)
        // Preserve the current-turn semantic leaders, then let RRF fill the pool with
        // candidates supported by semantic rank, lexical/BM25 rank, or both.
        currentOrdered.take(2).forEach(::addIfMissing)
        rrfOrdered.forEach(::addIfMissing)

        // Phase 9: lexical support may recall a record into the candidate pool, but
        // final selector ordering remains semantic. Lexical overlap is not clinical
        // applicability and must not make a superficially similar SOP look stronger.
        val selectedRows = chosen
            .distinctBy { it.recordId }
            .sortedWith(
                compareByDescending<Scored> { it.ranking }
                    .thenByDescending { it.current }
                    .thenByDescending { it.contextual }
            )
            .take(limit)

        val selected = selectedRows.map { row ->
            KbRecordCandidate(
                recordId = row.recordId,
                category = row.category,
                conditionName = row.condition,
                emergency = row.emergency,
                currentSemanticScore = row.current,
                contextSemanticScore = row.contextual,
                rankingScore = row.ranking,
                lexicalSupport = row.lexical,
                applicabilityText = boundedApplicabilityText(
                    row.condition, row.category, row.emergency, row.identify,
                    row.refer, row.counsel, row.assess
                ),
                fileName = row.fileName,
                sourceInfo = row.source,
                validationStatus = row.validationStatus,
                activeAnchor = row.active
            )
        }

        lastTopSimilarity = selected.maxOfOrNull { it.rankingScore } ?: 0f
        Log.i(
            TAG,
            "KB_RECORD_CANDIDATES rev=V5_FTS_RRF scanned=$scanned valid=$valid " +
                "records=${scored.size} returned=${selected.size} active=${activeSet.size} " +
                "ranking=${if (hasDistinctCaseContext) "CASE_CONTEXT_PRIMARY" else "CURRENT_ONLY"} " +
                "lexical=${lexicalHits.size} lexicalSource=$lexicalMode fusion=${if (lexicalHits.isNotEmpty()) "RRF" else "SEMANTIC_ONLY"} top=${"%.4f".format(lastTopSimilarity)}"
        )
        selected.forEachIndexed { index, item ->
            Log.i(
                TAG,
                "KB_RECORD_CANDIDATE[${index + 1}] rev=V5_FTS_RRF " +
                    "id=${item.recordId} current=${"%.4f".format(item.currentSemanticScore)} " +
                    "context=${"%.4f".format(item.contextSemanticScore)} " +
                    "rank=${"%.4f".format(item.rankingScore)} lexical=${"%.4f".format(item.lexicalSupport)} " +
                    "active=${item.activeAnchor} condition='${item.conditionName.take(130)}'"
            )
        }
        selected
    }

    private fun compactSelectedRecordEvidence(
        rawRecord: String,
        maxChars: Int
    ): String {
        val raw = rawRecord.trim()
        if (raw.isBlank()) return ""
        val limit = maxChars.coerceIn(2_400, 4_200)

        val header = buildString {
            listOf("CATEGORY", "CONDITION", "EMERGENCY").forEach { label ->
                Regex("(?im)^\\s*$label\\s*:\\s*(.+)$")
                    .find(raw)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { append(label).append(": ").append(it).append('\n') }
            }
        }.trim()

        val sectionRx = Regex(
            "(?ms)^\\s*(IDENTIFY|ASSESS|ACT|TREAT|REFER|COUNSEL)\\s*:\\s*(.*?)" +
                "(?=^\\s*(?:IDENTIFY|ASSESS|ACT|TREAT|REFER|COUNSEL|KEYWORDS|SOURCE)\\s*:|\\z)"
        )
        val sections = LinkedHashMap<String, String>()
        sectionRx.findAll(raw).forEach { match ->
            val name = match.groupValues[1].trim().uppercase(Locale.ROOT)
            val body = match.groupValues[2].trim()
            if (body.isNotBlank() && name !in sections) sections[name] = body
        }
        if (sections.isEmpty()) return raw.take(limit)

        val weights = mapOf(
            "IDENTIFY" to 1.0,
            "ASSESS" to 1.0,
            "ACT" to 1.2,
            "TREAT" to 1.35,
            "REFER" to 1.0,
            "COUNSEL" to 0.55
        )
        val structuralOverhead = sections.keys.sumOf { it.length + 3 } + header.length + 16
        val available = (limit - structuralOverhead).coerceAtLeast(1_500)
        val presentWeight = sections.keys.sumOf { weights[it] ?: 1.0 }.coerceAtLeast(1.0)

        val out = StringBuilder()
        if (header.isNotBlank()) out.append(header).append('\n')
        for ((name, body) in sections) {
            val share = ((available * (weights[name] ?: 1.0)) / presentWeight)
                .toInt()
                .coerceAtLeast(180)
            out.append(name).append(":\n")
            out.append(body.take(share).trim())
            out.append('\n')
        }
        return out.toString().trim().take(limit)
    }

    /** Stage B: once applicability is decided, load ONLY the selected record(s). */
    suspend fun loadRecordEvidence(
        recordIds: List<Int>,
        maxCharsPerRecord: Int = 3_600
    ): List<SopRecord> = withContext(Dispatchers.IO) {
        val database = db ?: return@withContext emptyList()
        if (!database.isOpen) return@withContext emptyList()
        val ids = recordIds.distinct().filter { it > 0 }.take(1)
        if (ids.isEmpty()) return@withContext emptyList()

        val tables = HashSet<String>()
        database.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
            while (c.moveToNext()) tables += c.getString(0)
        }

        // V2/V3 curated DBs preserve the complete source record in sop_records.
        // Prefer it so answer generation sees one coherent protocol, not adjacent
        // row fragments from unrelated selection cards.
        if ("sop_records" in tables) {
            val out = ArrayList<SopRecord>()
            for (recordId in ids) {
                database.rawQuery(
                    "SELECT category,condition,emergency,source,raw_record,retrieval_status " +
                        "FROM sop_records WHERE record_id=?",
                    arrayOf(recordId.toString())
                ).use { c ->
                    if (!c.moveToFirst()) return@use
                    val status = c.getString(5).orEmpty()
                    if (status.contains("QUARANTINED", ignoreCase = true)) return@use
                    val category = c.getString(0).orEmpty()
                    val condition = c.getString(1).orEmpty()
                    val emergency = c.getString(2).orEmpty()
                    val sourceText = c.getString(3).orEmpty()
                    val raw = c.getString(4).orEmpty().trim()
                    if (raw.isBlank()) return@use
                    out += SopRecord(
                        id = recordId.toString(),
                        category = category.ifBlank { "ANM" },
                        conditionName = condition,
                        emergencyTier = parseTier("EMERGENCY: $emergency", sourceText),
                        sopText = compactSelectedRecordEvidence(
                            raw,
                            maxCharsPerRecord.coerceIn(2_400, 4_200)
                        ),
                        sourceInfo = sourceText.ifBlank { "ANM Knowledge Base" },
                        fileName = recordId.toString(),
                        pageNumber = -1,
                        sourceTitle = sourceText,
                        portal = ""
                    )
                }
            }
            if (out.isNotEmpty()) return@withContext out
        }

        // Compatibility fallback for an older rich sop_chunks-only database.
        if (!hasRichV2Schema(database)) return@withContext emptyList()
        val out = ArrayList<SopRecord>()
        for (recordId in ids) {
            database.rawQuery(
                "SELECT file_name,page_number,content,category,condition,emergency,section,chunk_part " +
                    "FROM $TABLE WHERE record_id=? ORDER BY " +
                    "CASE section WHEN 'RECOGNIZE_ASSESS' THEN 0 WHEN 'ACT_TREAT' THEN 1 " +
                    "WHEN 'REFER_COUNSEL' THEN 2 ELSE 3 END, chunk_part, rowid",
                arrayOf(recordId.toString())
            ).use { c ->
                var fileName = ""
                var page = -1
                var category = ""
                var condition = ""
                var emergency = ""
                val parts = ArrayList<String>()
                val seen = HashSet<String>()
                while (c.moveToNext()) {
                    fileName = c.getString(0).orEmpty().ifBlank { fileName }
                    page = c.getInt(1)
                    val content = c.getString(2).orEmpty().trim()
                    category = c.getString(3).orEmpty().ifBlank { category }
                    condition = c.getString(4).orEmpty().ifBlank { condition }
                    emergency = c.getString(5).orEmpty().ifBlank { emergency }
                    if (content.isNotBlank()) {
                        val key = content.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").take(500)
                        if (seen.add(key)) parts += content
                    }
                }
                if (parts.isNotEmpty()) {
                    val merged = parts.joinToString("\n\n").take(maxCharsPerRecord.coerceIn(1_500, 5_500))
                    val source = buildSourceInfo(fileName, fileName, page)
                    out += SopRecord(
                        id = recordId.toString(),
                        category = category.ifBlank { "ANM" },
                        conditionName = condition,
                        emergencyTier = parseTier("EMERGENCY: $emergency", source),
                        sopText = merged,
                        sourceInfo = source,
                        fileName = fileName,
                        pageNumber = page,
                        sourceTitle = fileName,
                        portal = ""
                    )
                }
            }
        }
        out
    }

    /**
     * Multi-view semantic retrieval for conversational KB turns.  Each E5 query view
     * is embedded once, then the database is scanned only once.  A chunk's score is
     * the maximum cosine similarity across the supplied semantic views; there is no
     * invented weighted blend.
     */
    suspend fun searchSemanticMulti(
        queries: List<String>,
        maxResults: Int = 10,
        minSemanticScore: Float = 0.835f
    ): List<AdaptiveSopMatch> = withContext(Dispatchers.IO) {
        lastTopSimilarity = 0f
        val cleanQueries = queries
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(3)
        if (cleanQueries.isEmpty()) return@withContext emptyList()

        try {
            withTimeout(15_000L) {
                val database = db ?: return@withTimeout emptyList<AdaptiveSopMatch>()
                val engine = semanticEngine
                if (!database.isOpen || engine?.isReady != true || cachedStats?.hasEmbeddings != true) {
                    Log.w(TAG, "KB_TRACE_SEMANTIC_UNAVAILABLE multiView=true")
                    return@withTimeout emptyList<AdaptiveSopMatch>()
                }

                val queryEmbeddings = cleanQueries.mapNotNull { q ->
                    try {
                        engine.embed(if (q.startsWith("query: ")) q else "query: $q")
                            ?.takeIf { vector -> vector.size == 384 && vector.all { it.isFinite() } }
                            ?.let { q to it }
                    } catch (t: Throwable) {
                        Log.w(TAG, "KB_TRACE_EMBED_VIEW_FAILED ${t.message}")
                        null
                    }
                }
                if (queryEmbeddings.isEmpty()) return@withTimeout emptyList<AdaptiveSopMatch>()

                val primaryQuery = cleanQueries.first()
                val primaryIsSpecific = cleanQueries.size == 1 || semanticTokenCount(primaryQuery) >= 4

                data class Scored(val record: SopRecord, val score: Float)
                val scored = ArrayList<Scored>()
                var scanned = 0
                var validEmbeddings = 0
                var maxRawScore = -1f

                database.rawQuery(
                    "SELECT rowid,file_name,page_number,content,embedding FROM $TABLE",
                    null
                ).use { c ->
                    while (c.moveToNext()) {
                        scanned++
                        val blob = try { c.getBlob(4) } catch (_: Throwable) { null } ?: continue
                        if (blob.size != 1536) continue
                        val vector = engine.blobToFloatArray(blob) ?: continue
                        if (vector.size != 384 || vector.any { !it.isFinite() }) continue
                        validEmbeddings++

                        var best = -1f
                        var primary = -1f
                        for ((viewText, q) in queryEmbeddings) {
                            val similarity = engine.cosineSimilarity(q, vector)
                            best = maxOf(best, similarity)
                            if (viewText == primaryQuery) primary = similarity
                        }
                        val rankingScore =
                            if (primaryIsSpecific && primary >= -0.5f) primary else best
                        if (rankingScore > maxRawScore) maxRawScore = rankingScore
                        if (rankingScore < minSemanticScore) continue

                        val fileName = c.getString(1).orEmpty()
                        val page = c.getInt(2)
                        val content = c.getString(3).orEmpty()
                        val sourceTitle = fileName
                        val portal = ""
                        val source = buildSourceInfo(sourceTitle, fileName, page)
                        scored += Scored(
                            SopRecord(
                                id = c.getString(0).orEmpty(),
                                category = parseTaggedValue(content, "CATEGORY").ifBlank { "ANM" },
                                conditionName = parseTaggedValue(content, "CONDITION"),
                                emergencyTier = parseTier(content, source),
                                sopText = content,
                                sourceInfo = source,
                                fileName = fileName,
                                pageNumber = page,
                                sourceTitle = sourceTitle,
                                portal = portal
                            ),
                            rankingScore
                        )
                    }
                }

                val ranked = scored.sortedByDescending { it.score }
                lastTopSimilarity = ranked.firstOrNull()?.score ?: maxRawScore.coerceAtLeast(0f)

                Log.d(
                    TAG,
                    "KB_TRACE_MULTI_VIEW views=${queryEmbeddings.size} scanned=$scanned validEmbeddings=$validEmbeddings " +
                        "candidates=${ranked.size} top=${"%.4f".format(lastTopSimilarity)} threshold=$minSemanticScore " +
                        "ranking=${if (primaryIsSpecific) "primary_current_turn" else "multi_view_max"}"
                )

                ranked.take(maxResults.coerceIn(1, 100)).map { item ->
                    AdaptiveSopMatch(
                        record = item.record,
                        semanticScore = item.score,
                        lexicalExact = false,
                        matchType = "semantic"
                    )
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "KB_TRACE_MULTI_VIEW_FAILED ${t.message}", t)
            emptyList()
        }
    }

    /**
     * Semantic-primary hybrid retrieval.  Lexical matching can mark an already
     * semantic-relevant record as an exact terminology hit, but it cannot admit a
     * record when E5 found no semantic evidence.
     */
    suspend fun searchSopsAdaptive(
        query: String,
        maxResults: Int = 3,
        minSemanticScore: Float = 0.835f
    ): List<AdaptiveSopMatch> = searchSopsAdaptiveMulti(
        queries = listOf(query),
        maxResults = maxResults,
        minSemanticScore = minSemanticScore
    )

    suspend fun searchSopsAdaptiveMulti(
        queries: List<String>,
        maxResults: Int = 10,
        minSemanticScore: Float = 0.835f
    ): List<AdaptiveSopMatch> = withContext(Dispatchers.IO) {
        /*
         * Selected-KB retrieval is semantic-only.
         *
         * There is no keyword search, stop-word table, condition list, symptom
         * list, domain allowlist, or lexical admission path. The E5 embeddings
         * already stored in the selected database are the only retrieval signal.
         *
         * Query content reaches this function only as E5 semantic views.
         */
        val semantic = searchSemanticMulti(
            queries = queries,
            maxResults = maxResults,
            minSemanticScore = minSemanticScore
        )

        val result = semantic
            .take(maxResults.coerceIn(1, 20))
            .map { match ->
                match.copy(
                    lexicalExact = false,
                    matchType = "semantic_e5",
                    lexicalSupport = 0f
                )
            }

        result.forEachIndexed { index, match ->
            Log.d(
                TAG,
                "KB_ADAPTIVE_SELECTED[${index + 1}] " +
                    "semantic=${"%.4f".format(match.semanticScore)} " +
                    "condition='${match.record.conditionName.take(120)}' " +
                    "source='${match.record.fileName.take(140)}'"
            )
        }
        Log.d(
            TAG,
            "KB_ADAPTIVE_MULTI semantic=${semantic.size} selected=${result.size} " +
                "topSemantic=${"%.4f".format(lastTopSimilarity)} ranking=semantic_only"
        )
        result
    }

    /**
     * Reconstruct the local source neighborhood around a semantic seed.  The real
     * ANM database stores many numbered lists/instructions across consecutive chunks,
     * so a hit is expanded to the previous/current/next row from the same file.
     */
    suspend fun expandAdjacentChunks(
        seed: SopRecord,
        radius: Int = 1,
        maxChars: Int = 1_800
    ): SopRecord = withContext(Dispatchers.IO) {
        val database = db ?: return@withContext seed
        val rowId = seed.id.toLongOrNull() ?: return@withContext seed
        val file = seed.fileName.ifBlank { return@withContext seed }
        val boundedRadius = radius.coerceIn(0, 2)
        val parts = ArrayList<SopRecord>()

        try {
            database.rawQuery(
                "SELECT rowid,file_name,page_number,content FROM $TABLE " +
                    "WHERE file_name=? AND rowid BETWEEN ? AND ? ORDER BY rowid",
                arrayOf(file, (rowId - boundedRadius).toString(), (rowId + boundedRadius).toString())
            ).use { c ->
                while (c.moveToNext()) {
                    val fileName = c.getString(1).orEmpty()
                    val page = c.getInt(2)
                    val content = c.getString(3).orEmpty()
                    val sourceTitle = fileName
                    val portal = ""
                    val source = buildSourceInfo(sourceTitle, fileName, page)
                    parts += SopRecord(
                        id = c.getString(0).orEmpty(),
                        category = parseTaggedValue(content, "CATEGORY").ifBlank { seed.category },
                        conditionName = parseTaggedValue(content, "CONDITION").ifBlank { seed.conditionName },
                        emergencyTier = parseTier(content, source),
                        sopText = content,
                        sourceInfo = source,
                        fileName = fileName,
                        pageNumber = page,
                        sourceTitle = sourceTitle,
                        portal = portal
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "KB_ADJACENT_EXPAND_FAILED id=${seed.id} ${t.message}")
            return@withContext seed
        }
        if (parts.isEmpty()) return@withContext seed

        var merged = ""
        for (part in parts) {
            merged = mergeChunkOverlap(merged, part.sopText, maxChars)
            if (merged.length >= maxChars) break
        }
        seed.copy(
            sopText = merged.take(maxChars),
            sourceInfo = buildSourceInfo(seed.sourceTitle, seed.fileName, seed.pageNumber)
        )
    }

    private fun mergeChunkOverlap(existing: String, next: String, maxChars: Int): String {
        val left = existing.trim()
        val right = next.trim()
        if (left.isBlank()) return right.take(maxChars)
        if (right.isBlank()) return left.take(maxChars)

        val maxOverlap = minOf(180, left.length, right.length)
        var overlap = 0
        for (n in maxOverlap downTo 24) {
            if (left.regionMatches(left.length - n, right, 0, n, ignoreCase = false)) {
                overlap = n
                break
            }
        }
        val combined = if (overlap > 0) left + right.substring(overlap) else "$left\n$right"
        return combined.take(maxChars)
    }

    private fun buildSourceInfo(sourceTitle: String, fileName: String, page: Int): String {
        val label = sourceTitle.trim().ifBlank { fileName.trim() }.ifBlank { "ANM Knowledge Base" }
        return if (page >= 0) "$label page $page" else label
    }

    /**
     * Structural specificity check only. It counts user-supplied tokens and does
     * not recognize any particular word, language, diagnosis, symptom, or domain.
     */
    private fun semanticTokenCount(text: String): Int =
        text.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .split(Regex("\\s+"))
            .count { it.length >= 2 }

    /**
     * Resolve the semantic score for a merged row only for diagnostics.  This
     * does not alter acceptance or ranking and therefore cannot silently loosen
     * the E5 gate.
     */
    private suspend fun semanticScoreForRecord(
        query: String,
        rowId: String
    ): Float {
        val database = db ?: return 0f
        val engine = semanticEngine ?: return 0f
        if (!engine.isReady) return 0f

        val q = try { engine.embed(query) } catch (_: Throwable) { null } ?: return 0f

        return try {
            database.rawQuery(
                "SELECT embedding FROM $TABLE WHERE rowid=?",
                arrayOf(rowId)
            ).use { c ->
                if (!c.moveToFirst()) return@use 0f
                val blob = c.getBlob(0) ?: return@use 0f
                val vector = engine.blobToFloatArray(blob) ?: return@use 0f
                engine.cosineSimilarity(q, vector)
            }
        } catch (_: Throwable) {
            0f
        }
    }

    suspend fun getSopById(
        id: String
    ): SopRecord? =
        withContext(Dispatchers.IO) {
            try {
                db?.rawQuery(
                    """
                    SELECT rowid,file_name,page_number,content
                    FROM $TABLE
                    WHERE rowid=?
                    """.trimIndent(),
                    arrayOf(id)
                )?.use { c ->

                    if (c.moveToFirst()) {
                        SopRecord(
                            id = c.getString(0) ?: id,
                            category = "ANM",
                            conditionName = "",
                            emergencyTier = EmergencyTier.GREEN_ROUTINE,
                            sopText = c.getString(3) ?: "",
                            sourceInfo =
                                "${c.getString(1) ?: "ANM SOP"} " +
                                    "page ${c.getInt(2)}"
                        )
                    } else {
                        null
                    }
                }
            } catch (t: Throwable) {
                Log.e(
                    TAG,
                    "KB_TRACE_GET_BY_ID_FAILED ${t.message}",
                    t
                )
                null
            }
        }

    fun isReady(): Boolean =
        db?.isOpen == true

    fun getRecordCount(): Int =
        try {
            db?.rawQuery(
                "SELECT COUNT(*) FROM $TABLE",
                null
            )?.use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            } ?: 0
        } catch (_: Throwable) {
            0
        }

    fun close() {
        db?.close()
        db = null
        cachedStats = null
    }

    private fun copyFromAssets(file: File) {
        file.parentFile?.mkdirs()

        context.assets
            .open(ASSETS_PATH)
            .use { input ->
                FileOutputStream(file).use { output ->
                    val buffer = ByteArray(4 * 1024 * 1024)

                    var count: Int

                    while (
                        input.read(buffer).also {
                            count = it
                        } != -1
                    ) {
                        output.write(
                            buffer,
                            0,
                            count
                        )
                    }
                }
            }
    }
}
