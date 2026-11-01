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
        val payload = JSONObject().put("app", "ArogyaDoot").put("version", 1).put("createdAt", System.currentTimeMillis())
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
        require(json.optString("app") == "ArogyaDoot") { "Not an ArogyaDoot backup." }
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

    /** Saves a file in Downloads/ArogyaDoot. Returns its Uri, or null. */
    fun saveFile(name: String, mime: String, bytes: ByteArray): Uri? = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ArogyaDoot")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("no destination")
        context.contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
        uri
    }.getOrNull()

    /** Saves a file in Downloads/ArogyaDoot and opens the share sheet (CSV registers, reports). */
    fun shareFile(name: String, mime: String, bytes: ByteArray): Boolean {
        val uri = saveFile(name, mime, bytes) ?: return false
        return runCatching {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        }.getOrDefault(false)
    }

    /** Renders an official form as a ruled table (A4 landscape) and opens the share sheet. */
    fun shareFormPdf(form: FormTable): Boolean {
        val bytes = renderFormPdf(form) ?: return false
        return shareFile(form.title.substringBefore(" ·").replace(Regex("[^A-Za-z0-9]+"), "-") + "-${LocalDate.now()}.pdf", "application/pdf", bytes)
    }

    /** Headless render for the device tests: the same drawing code, no share sheet. Null = failed. */
    fun renderFormPdf(form: FormTable): ByteArray? = runCatching {
        val doc = android.graphics.pdf.PdfDocument()
        try { drawFormTable(doc, form); java.io.ByteArrayOutputStream().also { doc.writeTo(it) }.toByteArray() } finally { doc.close() }
    }.getOrNull()

    private fun drawFormTable(doc: android.graphics.pdf.PdfDocument, form: FormTable) {
        val w = 842; val h = 595; val m = 24f
        val title = android.graphics.Paint().apply { textSize = 13f; isFakeBoldText = true; isAntiAlias = true }
        val small = android.graphics.Paint().apply { textSize = 7.5f; isAntiAlias = true }
        val head = android.graphics.Paint().apply { textSize = 7.5f; isFakeBoldText = true; isAntiAlias = true }
        val line = android.graphics.Paint().apply { strokeWidth = 0.6f; style = android.graphics.Paint.Style.STROKE }
        val cols = form.headers.size
        val weights = form.headers.mapIndexed { i, hd -> if (i == 0 || hd.length > 18 || hd.contains("Name") || hd.contains("Ask")) 2.2f else 1f }
        val unit = (w - 2 * m) / weights.sum()
        val widths = weights.map { it * unit }
        fun clip(t: String, cw: Float, p: android.graphics.Paint): String { var x = t; while (x.isNotEmpty() && p.measureText(x) > cw - 4) x = x.dropLast(1); return x }
        var pageNo = 0; var row = 0
        while (row < form.rows.size || pageNo == 0) {
            val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(w, h, ++pageNo).create()); val c = page.canvas
            c.drawText(form.title, m, m + 10, title); c.drawText("Format: ${form.source} · test data · page $pageNo", m, m + 22, small)
            var y = m + 32f; var x = m
            val hh = 22f
            form.headers.forEachIndexed { i, hd -> c.drawRect(x, y, x + widths[i], y + hh, line)
                val words = hd.split(" "); val half = (words.size + 1) / 2
                c.drawText(clip(words.take(half).joinToString(" "), widths[i], head), x + 2, y + 9, head); c.drawText(clip(words.drop(half).joinToString(" "), widths[i], head), x + 2, y + 18, head); x += widths[i] }
            y += hh
            while (row < form.rows.size && y + 14 < h - m - 14) {
                x = m; form.rows[row].take(cols).forEachIndexed { i, cell -> c.drawRect(x, y, x + widths[i], y + 14, line); c.drawText(clip(cell, widths[i], small), x + 2, y + 10, small); x += widths[i] }
                y += 14; row++
            }
            if (row >= form.rows.size && form.note.isNotBlank()) c.drawText(clip(form.note, (w - 2 * m), small), m, h - m, small)
            doc.finishPage(page)
            if (form.rows.isEmpty()) break
        }
    }

    /** MCP card sections as a simple PDF. */
    fun shareCardPdf(name: String, sections: List<Pair<String, List<String>>>): Boolean = sharePdf("MCP-card-" + name.replace(Regex("[^A-Za-z0-9]+"), "-") + ".pdf") { doc ->
        val text = "MOTHER AND CHILD PROTECTION CARD (format: MoHFW/MWCD revised 2018) — test data\n$name\n\n" + sections.joinToString("\n\n") { (t, l) -> t + "\n" + l.joinToString("\n") { "   $it" } }
        val paint = android.text.TextPaint().apply { textSize = 10f; isAntiAlias = true }
        val layout = android.text.StaticLayout.Builder.obtain(text, 0, text.length, paint, 515).build()
        var line = 0; var n = 0
        while (line < layout.lineCount) {
            val top = layout.getLineTop(line); var end = line + 1
            while (end < layout.lineCount && layout.getLineBottom(end) - top <= 760) end++
            val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, ++n).create())
            page.canvas.save(); page.canvas.translate(40f, 40f - top); page.canvas.clipRect(0, top, 515, layout.getLineBottom(end - 1)); layout.draw(page.canvas); page.canvas.restore()
            doc.finishPage(page); line = end
        }
    }

    private fun sharePdf(name: String, draw: (android.graphics.pdf.PdfDocument) -> Unit): Boolean = runCatching {
        val doc = android.graphics.pdf.PdfDocument()
        val bytes = try { draw(doc); java.io.ByteArrayOutputStream().also { doc.writeTo(it) }.toByteArray() } finally { doc.close() }
        shareFile(name, "application/pdf", bytes)
    }.getOrDefault(false)

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
