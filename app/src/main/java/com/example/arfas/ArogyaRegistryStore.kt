package com.example.arfas

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.AtomicFile
import android.util.Base64
import android.util.Log
import java.io.File
import java.time.LocalDate
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Offline registers (patients/MCP cards, diary, drug kit, vital events), encrypted on the phone with
 * the same Android-Keystore key as the visits and kept out of Android's own cloud backup. Writes are
 * serialised on one background thread; the screen reads the in-memory copy.
 */
class ArogyaRegistryStore private constructor(private val context: Context, private val visitsRepo: ArogyaVisitRepository) {
    companion object {
        private const val TAG = "ArogyaRegistry"
        @Volatile private var instance: ArogyaRegistryStore? = null
        fun get(context: Context): ArogyaRegistryStore = instance ?: synchronized(this) {
            instance ?: ArogyaRegistryStore(context.applicationContext, ArogyaVisitStore.get(context).first).also { instance = it }
        }
    }
    private val file = AtomicFile(File(File(context.noBackupFilesDir, "arogyadoot_visits").apply { mkdirs() }, "registry.enc"))
    private val writer = Executors.newSingleThreadExecutor()
    private val prefs = context.getSharedPreferences("arogya_backup", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(RegistryData())
    val state: StateFlow<RegistryData> = mutable
    @Volatile var loaded = false; private set
    @Volatile var error = ""; private set

    suspend fun load() = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        val t = System.currentTimeMillis()
        try {
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
                mutable.value = ArogyaRegistryCodec.decode(visitsRepo.decrypt(file.readFully()).toString(Charsets.UTF_8))
            }
            loaded = true
            Log.i(TAG, "AROGYA_REGISTRY_LOAD patients=${mutable.value.beneficiaries.size} ms=${System.currentTimeMillis() - t}")
        } catch (e: Exception) { error = "Registers could not be opened: ${e.message}"; Log.e(TAG, "AROGYA_REGISTRY_LOAD_FAILED ${e.message}") }
    }

    /** Applies [change] to the in-memory registers now and writes them, encrypted, on the writer thread. */
    @Synchronized fun update(change: (RegistryData) -> RegistryData) {
        check(loaded) { "Registers are still loading." }
        val next = change(mutable.value)
        mutable.value = next
        writer.execute {
            val t = System.currentTimeMillis()
            val stream = file.startWrite()
            try { stream.write(visitsRepo.encrypt(ArogyaRegistryCodec.encode(next).toByteArray())); file.finishWrite(stream)
                Log.i(TAG, "AROGYA_REGISTRY_SAVE patients=${next.beneficiaries.size} ms=${System.currentTimeMillis() - t}") }
            catch (e: Throwable) { file.failWrite(stream); Log.e(TAG, "AROGYA_REGISTRY_SAVE_FAILED ${e.message}") }
        }
    }

    // ── Backup ("like WhatsApp"): one password-protected file at a place the ASHA chooses (e.g. Google Drive) ──

    var backupUri: Uri?
        get() = prefs.getString("uri", null)?.let(Uri::parse)
        set(v) { prefs.edit().putString("uri", v?.toString()).apply() }
    var autoDaily: Boolean
        get() = prefs.getBoolean("auto", false)
        set(v) { prefs.edit().putBoolean("auto", v).apply() }
    val lastBackup: Long get() = prefs.getLong("last", 0L)
    val lastStatus: String get() = prefs.getString("status", "").orEmpty()

    /** The backup password, kept encrypted with the phone's key (needed for the daily automatic backup). */
    fun savePassword(p: String) { prefs.edit().putString("pw", Base64.encodeToString(visitsRepo.encrypt(p.toByteArray()), Base64.NO_WRAP)).apply() }
    fun password(): String = prefs.getString("pw", null)?.let { runCatching { visitsRepo.decrypt(Base64.decode(it, Base64.NO_WRAP)).toString(Charsets.UTF_8) }.getOrNull() }.orEmpty()

    fun backupBytes(password: String): ByteArray {
        val visits = visitsRepo.all()
        val payload = JSONObject().put("app", "Pramaan").put("version", 1).put("createdAt", System.currentTimeMillis())
            .put("registry", JSONObject(ArogyaRegistryCodec.encode(mutable.value)))
            .put("visits", JSONArray().also { a -> visits.forEach { a.put(ArogyaVisitRepository.encode(it)) } })
        return ArogyaBackupCrypto.encrypt(payload.toString().toByteArray(), password)
    }

    /** Writes the backup to [uri] (a Google Drive file chosen in the Android file picker works; Drive uploads it when online). */
    suspend fun backupTo(uri: Uri, password: String): String = withContext(Dispatchers.IO) {
        val t = System.currentTimeMillis()
        try {
            val bytes = backupBytes(password)
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("Cannot write to the chosen backup location.")
            val msg = "Backed up ${mutable.value.beneficiaries.size} patients, ${visitsRepo.all().size} visits (${bytes.size / 1024} KB) on ${LocalDate.now()}"
            prefs.edit().putLong("last", System.currentTimeMillis()).putString("status", msg).apply()
            Log.i(TAG, "AROGYA_BACKUP_OK bytes=${bytes.size} ms=${System.currentTimeMillis() - t}")
            msg
        } catch (e: Exception) {
            val msg = "Backup failed: ${e.message?.take(120)}"
            prefs.edit().putString("status", msg).apply(); Log.e(TAG, "AROGYA_BACKUP_FAILED ${e.message}")
            throw IllegalStateException(msg)
        }
    }

    /** Daily automatic backup when a location and password are saved. Silent; result shown on the Backup screen. */
    suspend fun autoBackupIfDue() {
        val uri = backupUri ?: return
        val pw = password()
        if (!autoDaily || pw.isBlank() || System.currentTimeMillis() - lastBackup < 24 * 3600_000L || !loaded) return
        runCatching { backupTo(uri, pw) }
    }

    /** Restores registers (replaces them) and adds visits that are not already on this phone. Returns a summary. */
    suspend fun restore(uri: Uri, password: String, queue: ArogyaVisitQueue): String = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot read the backup file.")
        val json = JSONObject(ArogyaBackupCrypto.decrypt(bytes, password).toString(Charsets.UTF_8))
        require(json.optString("app") in setOf("Pramaan", "ArogyaDoot")) { "Not a Pramaan backup." }   // "ArogyaDoot" = backups made before the rename
        val registry = ArogyaRegistryCodec.decode(json.getJSONObject("registry").toString())
        val visits = json.optJSONArray("visits") ?: JSONArray()
        val existing = visitsRepo.all().map { it.id }.toSet()
        var added = 0; var skipped = 0
        for (i in 0 until visits.length()) {
            val v = runCatching { ArogyaVisitRepository.decode(visits.getJSONObject(i)) }.getOrNull()
            if (v == null || v.id in existing) { skipped++; continue }
            if (runCatching { withContext(Dispatchers.Main) { queue.save(v) } }.isSuccess) added++ else skipped++
        }
        withContext(Dispatchers.Main) { update { registry } }
        Log.i(TAG, "AROGYA_RESTORE patients=${registry.beneficiaries.size} visitsAdded=$added skipped=$skipped")
        "Restored ${registry.beneficiaries.size} patients and registers; added $added visits ($skipped already here or unreadable)."
    }

    /** Saves a file in Downloads/Pramaan. Returns its Uri, or null. */
    fun saveFile(name: String, mime: String, bytes: ByteArray): Uri? = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Pramaan")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("no destination")
        context.contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
        uri
    }.getOrNull()

    /** Saves a file in Downloads/Pramaan and opens the share sheet (CSV registers, reports). */
    fun shareFile(name: String, mime: String, bytes: ByteArray): Boolean {
        val uri = saveFile(name, mime, bytes) ?: return false
        return runCatching {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        }.getOrDefault(false)
    }

    // Item 7: one document renderer — the same filled HTML shows in the viewer and goes
    // to PDF/print. Templates: assets/doc_templates; filling: ArogyaDocRender (pure).
    /** Any ruled table (official forms, RI forms, stock card, registers, lists) as a document. */
    fun tableDoc(form: FormTable, highlightCol: Int? = null): ArogyaDocRender.FilledDoc {
        val t = ArogyaDocPdf.templates(context)
        return ArogyaDocRender.tableDoc(form, ArogyaDocPdf.header(context), "Date", ArogyaDocPdf.todayStr(),
            t["table"].orEmpty(), t["css"].orEmpty(), highlightCol)
    }

    /** Renders a filled document to PDF bytes (WebView pipeline, works offline). Null = failed. */
    suspend fun renderDoc(doc: ArogyaDocRender.FilledDoc): ByteArray? = ArogyaDocPdf.render(context, doc)

    /** Renders an official form as a ruled document and opens the share sheet. */
    suspend fun shareFormPdf(form: FormTable): Boolean {
        val bytes = renderDoc(tableDoc(form)) ?: return false
        return shareFile(ArogyaDocPdf.fileName(form.title.substringBefore(" ·") + "-" + LocalDate.now(), ".pdf"), "application/pdf", bytes)
    }

    /** Headless render for the device tests: the same pipeline, no share sheet. Null = failed. */
    suspend fun renderFormPdf(form: FormTable): ByteArray? = renderDoc(tableDoc(form))

    /** MCP card in the printed card's shape (viewer + PDF share the same document). */
    fun mcpDoc(b: Beneficiary, all: List<Beneficiary>, today: LocalDate): ArogyaDocRender.FilledDoc {
        val t = ArogyaDocPdf.templates(context)
        // Gap round item 4: growth chart (child) + ANC trend (pregnant) from the bundled WHO table.
        val table = runCatching {
            ArogyaGrowth.parseTable(context.assets.open(ArogyaGrowth.ASSET).bufferedReader().readText())
        }.getOrDefault(ArogyaGrowth.Table(emptyList(), emptyList()))
        val growthSvg = if (b.category == BenCategory.CHILD) ArogyaGrowth.svg(b.sex, b.dob, b.mcp.weights, table) else ""
        val growthNote = if (growthSvg.isNotBlank()) ArogyaGrowth.weightNote(b.mcp.weights).orEmpty() else ""
        val ancTrend = if (b.category == BenCategory.PREGNANT) ArogyaGrowth.ancTrend(b.mcp.ancDetails) else ""
        return ArogyaDocRender.mcpDoc(b, all, today, ArogyaDocPdf.header(context), ArogyaDocPdf.todayStr(),
            t["mcp"].orEmpty(), t["css"].orEmpty(), growthSvg, growthNote, ancTrend, state.value.forms)
    }

    /** MCP card shared as a PDF. */
    suspend fun shareMcpPdf(b: Beneficiary, all: List<Beneficiary>, today: LocalDate): Boolean {
        val bytes = renderDoc(mcpDoc(b, all, today)) ?: return false
        return shareFile(ArogyaDocPdf.fileName("MCP-card-${b.name}", ".pdf"), "application/pdf", bytes)
    }

    /** An Ask SOP answer with its numbered sources. */
    fun answerDoc(question: String, segments: List<ArogyaOfficialAnswer.CitedSegment>, answerFallback: String,
                  sources: List<ArogyaSource>): ArogyaDocRender.FilledDoc {
        val t = ArogyaDocPdf.templates(context)
        return ArogyaDocRender.answerDoc(question, segments, answerFallback, sources, ArogyaDocPdf.header(context),
            ArogyaDocPdf.todayStr(), t["answer"].orEmpty(), t["css"].orEmpty())
    }

    /** An Ask SOP answer shared as a PDF. */
    suspend fun shareAnswerPdf(question: String, segments: List<ArogyaOfficialAnswer.CitedSegment>, answerFallback: String,
                               sources: List<ArogyaSource>): Boolean {
        val bytes = renderDoc(answerDoc(question, segments, answerFallback, sources)) ?: return false
        return shareFile(ArogyaDocPdf.fileName("SOP-answer", ".pdf"), "application/pdf", bytes)
    }

    /** 1000 synthetic patients for scale testing / demo (marked demo, removable). */
    fun loadSyntheticPatients(n: Int = 1000) {
        val today = LocalDate.now()
        val villages = listOf("Rampur", "Sundarpur", "Khedi", "Nayagaon", "Bhimpur", "Kalyanpur", "Devnagar")
        val made = (1..n).map { i ->
            val v = villages[i % villages.size]
            when (i % 4) {
                0 -> Beneficiary(name = "Synthetic Mother $i", relativeName = "Husband $i", village = v, category = BenCategory.PREGNANT, sex = "F",
                    lmp = today.minusDays((20 + i % 250).toLong()).toString(), demo = true)
                1 -> Beneficiary(name = "Synthetic Couple $i", village = v, category = BenCategory.ELIGIBLE_COUPLE, sex = "F", familyPlanning = listOf("None", "Condom", "OCP", "IUCD")[i % 4], demo = true)
                else -> Beneficiary(name = "Synthetic Child $i", relativeName = "Mother $i", village = v, category = BenCategory.CHILD, sex = if (i % 2 == 0) "F" else "M",
                    dob = today.minusDays((i % 900).toLong()).toString(), deliveryPlace = if (i % 9 == 0) "HOME" else "INSTITUTION",
                    birthWeightKg = if (i % 11 == 0) 2.2 else 2.9, demo = true)
            }
        }
        update { it.copy(beneficiaries = it.beneficiaries + made) }
    }
    fun removeSynthetic() = update { r -> val ids = r.beneficiaries.filter { it.demo }.map { it.id }.toSet()
        r.copy(beneficiaries = r.beneficiaries.filterNot { it.demo }, diary = r.diary.filterNot { it.beneficiaryId in ids },
            stock = r.stock.filterNot { it.beneficiaryId in ids }, vitals = r.vitals.filterNot { it.beneficiaryId in ids }, forms = r.forms.filterNot { it.beneficiaryId in ids }) }
}
