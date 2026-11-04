package com.example.arfas

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * "+ Add person": a bottom sheet with four options (type it in, photo, Excel/CSV,
 * PDF), all ending on one shared review screen. Category, due dates and form
 * columns come from the existing fixed rules; AI values are grounded and the
 * ASHA confirms every person before saving.
 */

data class ReviewPerson(val b: Beneficiary, val source: String, val weight: String, val skip: Boolean = false, val mergeId: String? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPersonSheet(hindi: Boolean, ai: ArogyaVisitAi?, data: RegistryData, visits: List<ArogyaVisit>,
    save: ((RegistryData) -> RegistryData) -> Boolean, onClose: () -> Unit) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    var step by remember { mutableStateOf("menu") }
    var review by remember { mutableStateOf<List<ReviewPerson>>(emptyList()) }
    var reviewSource by remember { mutableStateOf("manual") }
    var reviewNote by remember { mutableStateOf("") }
    if (step == "menu") ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("+ Add person", "+ व्यक्ति जोड़ें"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            listOf("manual" to tr("⌨ Type it in", "⌨ खुद लिखें"), "photo" to tr("📷 Photo of MCP card or register page", "📷 MCP कार्ड या रजिस्टर की फ़ोटो"),
                "csv" to tr("📄 Excel / CSV file", "📄 Excel / CSV फ़ाइल"), "pdf" to tr("📕 PDF list", "📕 PDF सूची")).forEach { (id, label) ->
                Button(onClick = { step = id }, modifier = Modifier.fillMaxWidth()) { Text(label) }
            }
            TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(tr("Cancel", "रद्द करें")) }
            Spacer(Modifier.height(16.dp))
        }
    } else Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            val villages = remember(data) { data.beneficiaries.map { it.village }.filter { it.isNotBlank() }.distinct() }
            var dictateTarget by remember { mutableStateOf<((String) -> Unit)?>(null) }
            val context = LocalContext.current
            if (dictateTarget != null) ArogyaDictationDialog(onDismiss = { dictateTarget = null },
                cloudVoice = { s, l -> ArogyaCloud.transcribe(context, s, l) },
                cloudFirst = ArogyaCloud.cloudFirst(context), hindi = hindi,
                onAccept = { dictateTarget?.invoke(it); dictateTarget = null })
            fun toReview(people: List<Beneficiary>, source: String, srcOf: (Int) -> String, note: String = "") {
                review = people.mapIndexed { i, b -> ReviewPerson(b, srcOf(i), b.birthWeightKg?.toString().orEmpty()) }
                reviewSource = source; reviewNote = note; step = "review"
            }
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { step = "menu" }) { Text(tr("‹ Back", "‹ पीछे")) }
                    Text(when (step) {
                        "manual" -> tr("Type it in", "खुद लिखें"); "photo" -> tr("Photo", "फ़ोटो")
                        "csv" -> tr("Excel / CSV", "Excel / CSV"); "pdf" -> tr("PDF list", "PDF सूची")
                        else -> tr("Review", "जाँचें")
                    }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text(tr("Close", "बंद करें")) }
                }
                when (step) {
                    "manual" -> ManualPersonStep(hindi, villages, { dictateTarget = it }) { b -> toReview(listOf(b), "manual", { tr("typed in", "लिखा हुआ") }) }
                    "photo" -> PhotoPersonStep(hindi, ai)
                        { toReview(it, "photo", { i -> tr("person ${i + 1} from photo", "फ़ोटो से व्यक्ति ${i + 1}") }) }
                    "csv" -> CsvPersonStep(hindi)
                        { list, note -> toReview(list, "csv", { i -> tr("row ${i + 1} of file", "फ़ाइल की पंक्ति ${i + 1}") }, note) }
                    "pdf" -> PdfPersonStep(hindi, ai,
                        { list -> toReview(list, "pdf", { i -> tr("PDF block ${i + 1}", "PDF हिस्सा ${i + 1}") }) })
                    else -> AddPersonReview(hindi, ai != null, review, { review = it }, reviewNote, reviewSource, data, visits, villages,
                        { dictateTarget = it }, save, onClose)
                }
            }
        }
    }
}

/** Manual path: category first, then the shared fields. */
@Composable
private fun ManualPersonStep(hindi: Boolean, villages: List<String>, dictate: (((String) -> Unit)) -> Unit, onDone: (Beneficiary) -> Unit) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    var b by remember { mutableStateOf(Beneficiary(category = BenCategory.PREGNANT)) }
    var weight by remember { mutableStateOf("") }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PatientFormFields(hindi, b, weight, { weight = it }, { b = it }, villages, dictate)
        val ok = b.name.isNotBlank() && (b.category != BenCategory.CHILD || b.dob.isNotBlank()) && (b.category != BenCategory.PREGNANT || b.lmp.isNotBlank())
        Button(onClick = { onDone(b.copy(birthWeightKg = weight.toDoubleOrNull()?.takeIf { it in 0.3..8.0 })) }, enabled = ok, modifier = Modifier.fillMaxWidth()) {
            Text(tr("Continue to review", "जाँच के लिए आगे"))
        }
    }
}

/** Photo path: camera/gallery → ML Kit OCR → grounded extraction → review. */
@Composable
private fun PhotoPersonStep(hindi: Boolean, ai: ArogyaVisitAi?, onDone: (List<Beneficiary>) -> Unit) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var shotUri by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Uri?>(null) }
    fun run(uri: Uri, temp: Boolean) {
        if (ai == null) { msg = tr("AI is not ready.", "AI तैयार नहीं।"); return }
        busy = true; msg = tr("Reading the photo…", "फ़ोटो पढ़ रहे हैं…")
        scope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                    var sample = 1; while (maxOf(opts.outWidth, opts.outHeight) / sample > 2000) sample *= 2
                    (context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                        ?: throw IllegalStateException("Could not open the photo")).let { ArogyaPhotoGuard.upright(context, uri, it) }
                }
                val ocrProc = MLKitProcessor()
                val (ocr, lines) = try { withContext(Dispatchers.IO) { ocrProc.recognizeText(bmp) to runCatching { ocrProc.recognizeLines(bmp) }.getOrDefault(emptyList()) } } finally { runCatching { ocrProc.close() } }
                // A ruled register page: rebuild its rows from the line positions, one person per row (rules, no AI).
                val tableRows = ArogyaTableOcr.people(lines).map { r -> r.mapValues { ArogyaPersonExtract.maskAadhaar(it.value) } }
                Log.i("ArogyaDemo", "AROGYA_REGISTER_ROWS lines=${lines.size} people=${tableRows.size}")
                if (ocr.isBlank()) throw IllegalStateException(tr("No readable text on this photo", "फ़ोटो में पढ़ने लायक अक्षर नहीं मिले"))
                if (ArogyaPersonExtract.hasAadhaar(ocr)) msg = tr("Aadhaar number found and removed — it is never stored.", "आधार नंबर मिला और हटाया — वो कभी सेव नहीं होता।") + " "
                msg += if (ai.isBusy) tr("Waiting for the AI to finish the current answer…", "AI पिछला उत्तर पूरा कर रहा है, रुकिए…")
                    else tr("Copying the details…", "जानकारी उतार रहे हैं…")
                val blocks = ArogyaExtractSplit.splitBlocks(ArogyaPersonExtract.maskAadhaar(ocr))
                val found = if (tableRows.isNotEmpty()) {
                    msg = tr("Register page: ${tableRows.size} people read row by row on the phone.", "रजिस्टर पेज: ${tableRows.size} लोग पंक्ति-दर-पंक्ति फ़ोन पर पढ़े।")
                    tableRows.map { ArogyaPersonCsv.toBeneficiary(it) }
                } else if (blocks.size > 1) {
                    msg = tr("Reading ${blocks.size} people…", "${blocks.size} लोगों को पढ़ रहे हैं…")
                    ai.extractPersonList(ocr).first
                        .filter { !it["name"].isNullOrBlank() }
                        .map { ArogyaPersonCsv.toBeneficiary(it) }
                } else {
                    val (fields, _, _) = ai.extractPersonFields(ocr)
                    if (fields.isEmpty()) throw IllegalStateException(tr("No person details found on this photo", "फ़ोटो में व्यक्ति की जानकारी नहीं मिली"))
                    listOf(ArogyaPersonCsv.toBeneficiary(fields))
                }
                if (found.isEmpty()) throw IllegalStateException(tr("No person details found on this photo", "फ़ोटो में व्यक्ति की जानकारी नहीं मिली"))
                onDone(found)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                busy = false; msg = (e.message ?: "").take(200)
            } finally { if (temp) runCatching { context.contentResolver.delete(uri, null, null) } }
        }
    }
    val shoot = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> val u = shotUri; shotUri = null; if (u != null) { if (ok) run(u, true) else runCatching { context.contentResolver.delete(u, null, null) } } }
    val camPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { g ->
        if (g) { val u = runCatching { context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "person_${System.currentTimeMillis()}.jpg"); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg") }) }.getOrNull()
            if (u != null) { shotUri = u; ArogyaPhotoGuard.hold(context); runCatching { shoot.launch(u) } } }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) run(u, false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Photograph the MCP card or register page. Only the picked photo is read; Aadhaar numbers are removed.", "MCP कार्ड या रजिस्टर पेज की फ़ोटो लें। सिर्फ़ चुनी फ़ोटो पढ़ी जाती है; आधार नंबर हटाए जाते हैं।"), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { camPerm.launch(android.Manifest.permission.CAMERA) }, enabled = !busy) { Text(tr("📷 Take photo", "📷 फ़ोटो लें")) }
            OutlinedButton(onClick = { ArogyaPhotoGuard.hold(context); pick.launch(arrayOf("image/*")) }, enabled = !busy) { Text(tr("Choose photo", "फ़ोटो चुनें")) }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (msg.isNotBlank()) Text(msg, style = MaterialTheme.typography.bodySmall)
    }
}

/** CSV path: template download + picker + column mapping + preview. */
@Composable
private fun CsvPersonStep(hindi: Boolean, onDone: (List<Beneficiary>, String) -> Unit) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var headers by remember { mutableStateOf<List<String>?>(null) }
    var rows by remember { mutableStateOf<List<List<String>>>(emptyList()) }
    var mapping by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u == null) return@rememberLauncherForActivityResult
        busy = true; msg = tr("Reading the file…", "फ़ाइल पढ़ रहे हैं…")
        scope.launch {
            try {
                val name = withContext(Dispatchers.IO) {
                    context.contentResolver.query(u, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)) else ""
                    }.orEmpty()
                }
                if (name.endsWith(".xlsx", true) || name.endsWith(".xls", true))
                    throw IllegalStateException(tr("Excel files: please Save As CSV in Excel first, then pick the .csv file.", "Excel फ़ाइल: पहले Excel में CSV में सेव करें, फिर .csv चुनें।"))
                val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(u)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty() }
                val parsed = ArogyaPersonCsv.parse(text)
                if (parsed.isEmpty()) throw IllegalStateException(tr("No rows found in this file.", "फ़ाइल में पंक्तियाँ नहीं मिलीं।"))
                headers = parsed.first(); rows = parsed.drop(1).take(500)
                mapping = ArogyaPersonCsv.matchHeaders(parsed.first())
                msg = ""
            } catch (e: CancellationException) { throw e } catch (e: Exception) { msg = (e.message ?: "").take(200) }
            finally { busy = false }
        }
    }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Download the template, fill one row per person, then pick the file. Excel opens CSV.", "टेम्पलेट उतारें, हर व्यक्ति एक पंक्ति में भरें, फिर फ़ाइल चुनें।"), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { runCatching {
                        val values = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, "Pramaan_people_template.csv")
                            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        }
                        val dest = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("no destination")
                        context.contentResolver.openOutputStream(dest, "w")?.use { it.write(ArogyaPersonCsv.template().toByteArray()) } ?: error("cannot write")
                    }.isSuccess }
                    msg = if (ok) tr("Template saved in Downloads.", "टेम्पलेट Downloads में सेव।") else tr("Could not write the template.", "टेम्पलेट नहीं लिख पाए।")
                }
            }) { Text(tr("Download template", "टेम्पलेट उतारें")) }
            Button(onClick = { ArogyaPhotoGuard.hold(context); pick.launch(arrayOf("text/*", "application/vnd.ms-excel")) }, enabled = !busy) { Text(tr("Pick CSV file", "CSV फ़ाइल चुनें")) }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (msg.isNotBlank()) Text(msg, style = MaterialTheme.typography.bodySmall)
        val hs = headers
        if (hs != null) {
            Text(tr("Match the columns (fix any the app missed):", "कॉलम मिलाएँ (छूटा हो तो सुधारें):"), fontWeight = FontWeight.Bold)
            hs.forEachIndexed { i, h ->
                var open by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(h.ifBlank { "(${tr("blank", "खाली")})" }, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Box {
                        OutlinedButton(onClick = { open = true }) { Text(mapping[i].ifNullOrBlank(tr("Ignore", "छोड़ें"))) }
                        DropdownMenu(open, { open = false }) {
                            (listOf("" to tr("Ignore", "छोड़ें")) + ArogyaPersonCsv.KEYS.map { it to it }).forEach { (k, label) ->
                                DropdownMenuItem({ Text(label) }, { mapping = mapping + (i to k); open = false })
                            }
                        }
                    }
                }
            }
            Text(tr("Preview (first 20 rows):", "झलक (पहली 20 पंक्तियाँ):"), fontWeight = FontWeight.Bold)
            rows.take(20).forEachIndexed { ri, r ->
                val map = mapping.entries.filter { it.value.isNotBlank() }.associate { it.value to r.getOrElse(it.key) { "" } }
                val errs = ArogyaPersonCsv.rowErrors(map)
                Text("${ri + 1}. ${map["name"].orEmpty()}${if (errs.isEmpty()) "" else " — ⚠ " + errs.joinToString("; ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (errs.isEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
            }
            Button(onClick = {
                val list = rows.mapNotNull { r ->
                    val map = mapping.entries.filter { it.value.isNotBlank() }.associate { it.value to r.getOrElse(it.key) { "" } }
                    if (map["name"].isNullOrBlank()) null else ArogyaPersonCsv.toBeneficiary(map)
                }
                val skipped = rows.size - list.size
                onDone(list, if (skipped > 0) tr("$skipped rows skipped (no name).", "$skipped पंक्तियाँ छोड़ीं (नाम नहीं)।") else "")
            }, enabled = rows.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text(tr("Continue to review", "जाँच के लिए आगे")) }
        }
    }
}

private fun String?.ifNullOrBlank(other: String) = if (this.isNullOrBlank()) other else this

/** PDF path: render pages + OCR, tabular split or AI mapping per block, all grounded. */
@Composable
private fun PdfPersonStep(hindi: Boolean, ai: ArogyaVisitAi?, onDone: (List<Beneficiary>) -> Unit) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u == null || ai == null) { if (ai == null) msg = tr("AI is not ready.", "AI तैयार नहीं।"); return@rememberLauncherForActivityResult }
        busy = true; msg = tr("Reading the PDF…", "PDF पढ़ रहे हैं…")
        scope.launch {
            try {
                val pageTexts = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(u)?.use { it.readBytes() } ?: error("Cannot read file")
                    val temp = File(context.noBackupFilesDir, "arogya-addpdf-${System.currentTimeMillis()}.pdf")
                    try {
                        temp.writeBytes(bytes)
                        val ocr = MLKitProcessor()
                        try {
                            ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                                PdfRenderer(fd).use { pdf ->
                                    (0 until minOf(6, pdf.pageCount)).map { i ->
                                        pdf.openPage(i).use { page ->
                                            val scale = minOf(1400f / page.width, 1400f / page.height)
                                            val bmp = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                                            try { bmp.eraseColor(android.graphics.Color.WHITE); page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); ocr.recognizeText(bmp) }
                                            finally { bmp.recycle() }
                                        }
                                    }
                                }
                            }
                        } finally { runCatching { ocr.close() } }
                    } finally { temp.delete() }
                }
                val full = ArogyaPersonExtract.maskAadhaar(pageTexts.joinToString("\n"))
                if (full.trim().length < 4) throw IllegalStateException(tr("No readable text in this PDF.", "PDF में पढ़ने लायक अक्षर नहीं मिले।"))
                // Tabular first: a header line matching 2+ known columns, then data lines.
                val lines = full.lines().map { it.trim() }.filter { it.isNotBlank() }
                val tabled = splitTableLines(lines)
                val out = ArrayList<Beneficiary>()
                if (tabled != null) {
                    val (map, dataLines) = tabled
                    dataLines.take(100).forEach { dl ->
                        val cells = dl.split(Regex(" {2,}|\t")).map { it.trim() }
                        val row = map.entries.associate { it.value to cells.getOrElse(it.key) { "" } }
                        if (!row["name"].isNullOrBlank()) out += ArogyaPersonCsv.toBeneficiary(row)
                    }
                } else {
                    val blocks = full.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.length > 20 }.take(10)
                    if (blocks.isEmpty()) throw IllegalStateException(tr("No person blocks found in this PDF.", "PDF में व्यक्ति के हिस्से नहीं मिले।"))
                    blocks.forEachIndexed { i, block ->
                        msg = tr("Reading person ${i + 1} of ${blocks.size}…", "व्यक्ति ${i + 1} / ${blocks.size} पढ़ रहे हैं…")
                        val (fields, _, _) = ai.extractPersonFields(block)
                        if (!fields["name"].isNullOrBlank()) out += ArogyaPersonCsv.toBeneficiary(fields)
                    }
                }
                if (out.isEmpty()) throw IllegalStateException(tr("No people found in this PDF.", "PDF में कोई व्यक्ति नहीं मिला।"))
                busy = false
                onDone(out)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { busy = false; msg = (e.message ?: "").take(200) }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Pick a due list or line list PDF (up to 6 pages). Only the picked file is read; Aadhaar numbers are removed.", "बाकी सूची या लाइन सूची की PDF चुनें (6 पेज तक)। सिर्फ़ चुनी फ़ाइल पढ़ी जाती है; आधार नंबर हटाए जाते हैं।"), style = MaterialTheme.typography.bodySmall)
        Button(onClick = { ArogyaPhotoGuard.hold(context); pick.launch(arrayOf("application/pdf")) }, enabled = !busy) { Text(tr("Pick PDF file", "PDF फ़ाइल चुनें")) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (msg.isNotBlank()) Text(msg, style = MaterialTheme.typography.bodySmall)
    }
}

/** A header line whose 2+-space columns match 2+ known headers, plus the data lines after it. */
private fun splitTableLines(lines: List<String>): Pair<Map<Int, String>, List<String>>? {
    lines.forEachIndexed { li, line ->
        val cells = line.split(Regex(" {2,}|\t")).map { it.trim() }
        if (cells.size >= 3) {
            val map = ArogyaPersonCsv.matchHeaders(cells)
            if (map.values.count { it.isNotBlank() } >= 2) {
                val data = lines.drop(li + 1).filter { it.split(Regex(" {2,}|\t")).size >= 2 }.take(100)
                if (data.isNotEmpty()) return map to data
            }
        }
    }
    return null
}

/** Shared review screen: one editable card per person, duplicates, consent, save. */
@Composable
private fun AddPersonReview(hindi: Boolean, aiReady: Boolean, people: List<ReviewPerson>, onPeople: (List<ReviewPerson>) -> Unit,
    note: String, source: String, data: RegistryData, visits: List<ArogyaVisit>, villages: List<String>,
    dictate: (((String) -> Unit)) -> Unit, save: ((RegistryData) -> RegistryData) -> Boolean, onClose: () -> Unit) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    val context = LocalContext.current
    var consent by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf("") }
    var dupCount by remember { mutableStateOf(0) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodySmall)
        Text(tr("${people.size} to review — check every card, then save.", "${people.size} जाँचने हैं — हर कार्ड देखें, फिर सेव करें।"), fontWeight = FontWeight.Bold)
        people.forEachIndexed { i, rp ->
            val dup = remember(rp.b, data) { ArogyaPersonDup.find(rp.b, data.beneficiaries) }
            Card(Modifier.fillMaxWidth(), colors = if (rp.skip) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant) else CardDefaults.cardColors()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Person ${i + 1}", "व्यक्ति ${i + 1}") + " · " + rp.source, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        FilterChip(selected = rp.skip, onClick = { onPeople(people.mapIndexed { j, p -> if (j == i) p.copy(skip = !p.skip) else p }) }, label = { Text(tr("Skip", "छोड़ें")) })
                    }
                    if (!rp.skip) {
                        PatientFormFields(hindi, rp.b, rp.weight, { w -> onPeople(people.mapIndexed { j, p -> if (j == i) p.copy(weight = w) else p }) },
                            { b -> onPeople(people.mapIndexed { j, p -> if (j == i) p.copy(b = b) else p }) }, villages, dictate)
                        val dueLine = remember(rp.b) { runCatching {
                            val (sp, col) = ArogyaOfficialForms.formFor(rp.b, data.forms)
                            val due = ArogyaOfficialForms.nextDue(sp, rp.b, col, LocalDate.now())
                            tr("First form: ", "पहला फ़ॉर्म: ") + sp.title.substringBefore(" (") + " · " + tr("next due ", "अगली तारीख़ ") + (due?.toString() ?: "—")
                        }.getOrDefault("") }
                        if (dueLine.isNotBlank()) Text(dueLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        if (dup != null && rp.mergeId == null) {
                            Text(tr("Possible duplicate of ${dup.name}", "संभव दोहराव: ${dup.name}"), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(onClick = { dupCount++; onPeople(people.mapIndexed { j, p -> if (j == i) p.copy(mergeId = dup.id) else p }) }) { Text(tr("Merge", "मिलाएँ")) }
                                OutlinedButton(onClick = { dupCount++; onPeople(people.mapIndexed { j, p -> if (j == i) p.copy(mergeId = "") else p }) }) { Text(tr("Keep both", "दोनों रखें")) }
                                OutlinedButton(onClick = { onPeople(people.mapIndexed { j, p -> if (j == i) p.copy(skip = true) else p }) }) { Text(tr("Skip", "छोड़ें")) }
                            }
                        }
                        if (rp.mergeId != null && rp.mergeId.isNotBlank()) Text(tr("Will merge into the existing record.", "मौजूदा रिकॉर्ड में मिलेगा।"), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(consent, { consent = it })
            Text(tr("Family agreed to be registered", "परिवार रजिस्टर होने को तैयार"), modifier = Modifier.weight(1f))
        }
        val toSave = people.filter { !it.skip && it.b.name.isNotBlank() }
        Button(onClick = {
            val summary = ArogyaChangeSummary.forNewPeople(toSave.map { it.b.name }, hindi)
            val ok = save { r ->
                var bens = r.beneficiaries
                var diary = r.diary
                toSave.forEach { rp ->
                    val b = rp.b.copy(birthWeightKg = rp.weight.toDoubleOrNull()?.takeIf { it in 0.3..8.0 })
                    val target = rp.mergeId
                    bens = if (!target.isNullOrBlank()) bens.map { if (it.id == target) ArogyaPersonDup.merge(it, b) else it }
                    else bens + b
                    diary = diary + DiaryEntry(beneficiaryId = target?.ifBlank { b.id } ?: b.id,
                        note = tr("Registered: ", "पंजीकृत: ") + b.name + " · " + rp.source + "\n" + summary)
                }
                r.copy(beneficiaries = bens, diary = diary)
            }
            if (ok) {
                Log.i("ArogyaDemo", "AROGYA_ADD_PERSON source=$source rows=${people.size} saved=${toSave.size} duplicates=$dupCount")
                done = summary
            } else done = tr("Could not save.", "सेव नहीं हुआ।")
        }, enabled = consent && toSave.isNotEmpty() && done.isEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(tr("Save ${toSave.size} people", "${toSave.size} लोग सेव करें"))
        }
        if (done.isNotBlank()) {
            Text(done, fontWeight = FontWeight.Bold)
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(tr("Done", "हो गया")) }
        }
    }
}
