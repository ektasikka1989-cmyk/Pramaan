package com.example.arfas

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * One shared table for every register and list: filter chips (village, status, month),
 * tap-a-header sorting, status chips, row tap opens the person, and Export PDF /
 * Share (PDF or text) / Print on each. The exported/shared/printed table is always
 * what is currently shown (filters + sorting applied).
 */
@Composable
fun ArogyaRegisterTable(
    hindi: Boolean,
    title: String,
    table: RegTable,
    store: ArogyaRegistryStore,
    statusCol: Int = -1,
    villageCol: Int = -1,
    statusFilter: Boolean = false,
    monthCol: Int = -1,
    onOpenPerson: ((String) -> Unit)? = null,
    /** Row tap with the row key (used by the stock register for per-item history). */
    onRowKey: ((String) -> Unit)? = null,
    colWidths: List<Dp>? = null,
    maxHeight: Dp = 360.dp,
    note: String = "",
    /** Item 7: sample people inside → SAMPLE watermark on the PDF. */
    sample: Boolean = false
) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pdfBusy by remember { mutableStateOf(false) }
    fun toast(m: String) = Toast.makeText(context, m, Toast.LENGTH_LONG).show()
    var sortCol by remember(table.headers) { mutableStateOf(-1) }
    var sortAsc by remember(table.headers) { mutableStateOf(true) }
    var village by remember(table.headers) { mutableStateOf("") }
    var status by remember(table.headers) { mutableStateOf("") }
    var month by remember(table.headers) { mutableStateOf("") }
    val villages = remember(table) { table.rows.map { it.cells.getOrElse(villageCol) { "" } }.filter { it.isNotBlank() }.distinct().sorted().take(12) }
    val months = remember(table) {
        table.rows.mapNotNull { runCatching { java.time.YearMonth.from(java.time.LocalDate.parse(it.cells.getOrElse(monthCol) { "" })) }.getOrNull() }
            .distinct().sortedDescending().take(6).map { it.toString() }
    }
    val shown = remember(table, village, status, month, sortCol, sortAsc) {
        var rows = table.rows
        if (villageCol >= 0 && village.isNotBlank()) rows = rows.filter { it.cells.getOrElse(villageCol) { "" } == village }
        if (statusFilter && status.isNotBlank()) rows = rows.filter {
            when (status) {
                "OVERDUE" -> it.status == "OVERDUE"
                "DUE" -> it.status == "DUE_NOW" || it.status == "UPCOMING"
                "DONE" -> it.status == "DONE"
                else -> true
            }
        }
        if (monthCol >= 0 && month.isNotBlank()) rows = rows.filter {
            runCatching { java.time.YearMonth.from(java.time.LocalDate.parse(it.cells.getOrElse(monthCol) { "" })).toString() == month }.getOrDefault(false)
        }
        if (sortCol >= 0) rows = ArogyaRegisterTables.sortRows(rows, sortCol, sortAsc)
        rows
    }
    val err = MaterialTheme.colorScheme.error
    val errBg = MaterialTheme.colorScheme.errorContainer
    val warn = MaterialTheme.colorScheme.tertiary
    val good = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    fun statusColor(s: String): Color? = when (s) {
        "OVERDUE", "HIGH_RISK", "OUT", "EXPIRED" -> err
        "DUE_NOW", "LOW", "EXPIRING" -> warn
        "DONE" -> good
        "UPCOMING", "OK" -> dim
        else -> null
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (villageCol >= 0 && villages.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = village.isBlank(), onClick = { village = "" }, label = { Text(tr("All villages", "सभी गाँव")) })
                villages.forEach { v -> FilterChip(selected = village == v, onClick = { village = if (village == v) "" else v }, label = { Text(v) }) }
            }
        }
        if (statusFilter) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = status.isBlank(), onClick = { status = "" }, label = { Text(tr("All", "सभी")) })
                listOf("OVERDUE" to tr("Overdue", "छूटे"), "DUE" to tr("Due", "बाकी"), "DONE" to tr("Done", "हो गए")).forEach { (k, l) ->
                    FilterChip(selected = status == k, onClick = { status = if (status == k) "" else k }, label = { Text(l) })
                }
            }
        }
        if (monthCol >= 0 && months.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = month.isBlank(), onClick = { month = "" }, label = { Text(tr("All months", "सभी महीने")) })
                months.forEach { m -> FilterChip(selected = month == m, onClick = { month = if (month == m) "" else m }, label = { Text(m) }) }
            }
        }
        Text("$title (${shown.size})", fontWeight = FontWeight.Bold)
        if (shown.isEmpty()) {
            Text(tr("No rows.", "कोई पंक्ति नहीं।"), style = MaterialTheme.typography.bodySmall)
        } else {
            ArogyaGrid(
                headers = table.headers, rows = shown.map { it.cells }, maxLines = 3,
                colWidths = colWidths ?: table.headers.mapIndexed { i, _ -> if (i == 0) 130.dp else 104.dp },
                maxHeight = maxHeight,
                sortCol = sortCol, sortAsc = sortAsc,
                onHeaderTap = { c -> if (sortCol == c) sortAsc = !sortAsc else { sortCol = c; sortAsc = true } },
                onRowTap = if (onOpenPerson == null && onRowKey == null) null else { i ->
                    shown.getOrNull(i)?.let { r ->
                        val ben = r.beneficiaryId.takeIf { it.isNotBlank() }
                        if (ben != null && onOpenPerson != null) onOpenPerson(ben) else onRowKey?.invoke(r.key)
                    }
                },
                rowBg = { i ->
                    val s = shown.getOrNull(i)?.status.orEmpty()
                    if (s == "OVERDUE" || s == "HIGH_RISK" || s == "OUT" || s == "EXPIRED") errBg.copy(alpha = 0.45f) else null
                },
                chipColumns = if (statusCol >= 0) setOf(statusCol) else emptySet(),
                chipColor = { row, _, _ -> statusColor(shown.getOrNull(row)?.status.orEmpty()) }
            )
        }
        if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodySmall)
        if (shown.isNotEmpty()) {
            val form = remember(title, table, shown, sample) {
                ArogyaRegisterTables.toFormTable(title, "Pramaan register · ${java.time.LocalDate.now()}",
                    RegTable(table.headers, shown), note, sample)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    pdfBusy = true
                    scope.launch {
                        try {
                            val bytes = store.renderFormPdf(form)
                            val uri = bytes?.let { store.saveFile(form.title.replace(Regex("[^A-Za-z0-9]+"), "-") + ".pdf", "application/pdf", it) }
                            toast(if (uri != null) tr("Saved in Downloads/Pramaan", "Downloads/Pramaan में सहेजा") else tr("Could not create the PDF", "PDF नहीं बना"))
                        } finally {
                            pdfBusy = false
                        }
                    }
                }, enabled = !pdfBusy, modifier = Modifier.weight(1f)) { Text(if (pdfBusy) tr("Making PDF…", "PDF बन रही है…") else tr("Export PDF", "PDF निकालें"), maxLines = 1) }
                OutlinedButton(onClick = {
                    pdfBusy = true
                    scope.launch {
                        try {
                            if (!store.shareFormPdf(form)) toast(tr("Could not create the PDF", "PDF नहीं बना"))
                        } finally {
                            pdfBusy = false
                        }
                    }
                }, enabled = !pdfBusy, modifier = Modifier.weight(1f)) { Text(tr("Share PDF", "PDF भेजें"), maxLines = 1) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val tsv = (listOf(table.headers.joinToString("\t")) + shown.map { it.cells.joinToString("\t") }).joinToString("\n")
                    ArogyaShare.shareText(context, title, "$title\n$tsv")
                }, modifier = Modifier.weight(1f)) { Text(tr("Share text", "टेक्स्ट भेजें"), maxLines = 1) }
                OutlinedButton(onClick = {
                    pdfBusy = true
                    scope.launch {
                        try {
                            val bytes = store.renderFormPdf(form)
                            if (bytes == null || !ArogyaPrint.printPdf(context, title, bytes)) toast(tr("Print failed", "छपाई नहीं हुई"))
                        } finally {
                            pdfBusy = false
                        }
                    }
                }, enabled = !pdfBusy, modifier = Modifier.weight(1f)) { Text(tr("Print", "छापें"), maxLines = 1) }
            }
        }
    }
}

/**
 * A5: every AI result carries its engine chip — the real model that ran, offline/online, time.
 * Rule-based results use [FixedRuleChip]; AI proposals add [ConfirmChip].
 */
@Composable
fun AiResultChip(provider: String, online: Boolean, ms: Long, hindi: Boolean) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    val secs = if (ms < 1000) tr("<1 s", "<1 से.") else tr("${ms / 1000} s", "${ms / 1000} से.")
    AssistChip(onClick = {}, label = { Text("✨ ${provider.take(34)} · " + (if (online) tr("☁ online", "☁ ऑनलाइन") else tr("📴 offline", "📴 ऑफ़लाइन")) + " · $secs", style = MaterialTheme.typography.labelSmall) })
}

@Composable
fun FixedRuleChip(hindi: Boolean) {
    AssistChip(onClick = {}, label = { Text(if (hindi) "⚙ तय नियम" else "⚙ Fixed rule", style = MaterialTheme.typography.labelSmall) })
}

@Composable
fun ConfirmChip(hindi: Boolean) {
    AssistChip(onClick = {}, label = { Text(if (hindi) "✋ सहेजने से पहले आप पक्की करें" else "✋ You confirm before saving", style = MaterialTheme.typography.labelSmall) })
}

/** System print (sharesheet Print / printers) for a PDF already rendered in memory. */
object ArogyaPrint {
    fun printPdf(context: Context, jobName: String, bytes: ByteArray): Boolean = runCatching {
        val mgr = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        mgr.print(jobName.take(60), PdfAdapter(bytes), null)
        true
    }.getOrDefault(false)

    private class PdfAdapter(val bytes: ByteArray) : PrintDocumentAdapter() {
        override fun onLayout(
            old: PrintAttributes?, new: PrintAttributes?, cancel: CancellationSignal?,
            cb: LayoutResultCallback?, extras: Bundle?
        ) {
            if (cancel?.isCanceled == true) { cb?.onLayoutCancelled(); return }
            cb?.onLayoutFinished(
                PrintDocumentInfo.Builder("document.pdf")
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN).build(), true
            )
        }

        override fun onWrite(
            pages: Array<out PageRange>?, dest: ParcelFileDescriptor?,
            cancel: CancellationSignal?, cb: WriteResultCallback?
        ) {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(dest).use { it.write(bytes) }
                cb?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (t: Throwable) {
                cb?.onWriteFailed(t.message)
            }
        }
    }
}

/**
 * One shared grid for every register and official form: sticky header row, sticky first
 * column, horizontal + vertical scroll. The header sits above the scrolling rows; every row
 * shares one horizontal scroll state, so columns stay aligned.
 */
@Composable
fun ArogyaGrid(
    headers: List<String>,
    rows: List<List<String>>,
    colWidths: List<Dp> = headers.mapIndexed { i, _ -> if (i == 0) 150.dp else 96.dp },
    maxLines: Int = 2,
    firstColBold: Boolean = true,
    highlightCol: Int? = null,
    sortCol: Int = -1,
    sortAsc: Boolean = true,
    onHeaderTap: ((Int) -> Unit)? = null,
    onRowTap: ((Int) -> Unit)? = null,
    rowBg: ((Int) -> Color?)? = null,
    chipColumns: Set<Int> = emptySet(),
    chipColor: (row: Int, col: Int, value: String) -> Color? = { _, _, _ -> null },
    maxHeight: Dp = 560.dp
) {
    fun width(i: Int) = colWidths.getOrElse(i) { 96.dp }
    val line = MaterialTheme.colorScheme.outlineVariant
    val hState = rememberScrollState()
    val today = MaterialTheme.colorScheme.tertiaryContainer

    @Composable
    fun cell(text: String, row: Int, i: Int, bold: Boolean, bg: Color?, chip: Boolean) {
        val box = Modifier.width(width(i)).background(bg ?: Color.Transparent).padding(6.dp)
        if (chip) {
            val c = chipColor(row, i, text)
            if (c != null) {
                Box(box, contentAlignment = Alignment.CenterStart) {
                    Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = c,
                        modifier = Modifier.background(c.copy(alpha = 0.14f), MaterialTheme.shapes.small).padding(horizontal = 8.dp, vertical = 2.dp))
                }
                return
            }
        }
        Text(text, box, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.bodySmall)
    }

    Column(Modifier.fillMaxWidth()) {
        // Sticky header: first cell fixed, the rest scroll with the rows.
        Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
            val h0 = headers.getOrElse(0) { "" } + if (sortCol == 0) (if (sortAsc) " ▲" else " ▼") else ""
            Box(Modifier.width(width(0)).clickable(enabled = onHeaderTap != null) { onHeaderTap?.invoke(0) }.padding(6.dp)) {
                Text(h0, maxLines = maxLines, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.horizontalScroll(hState)) {
                headers.drop(1).forEachIndexed { k, h ->
                    val i = k + 1
                    val label = h + if (sortCol == i) (if (sortAsc) " ▲" else " ▼") else ""
                    Box(Modifier.width(width(i)).clickable(enabled = onHeaderTap != null) { onHeaderTap?.invoke(i) }.padding(6.dp)) {
                        Text(label, maxLines = maxLines, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        HorizontalDivider(color = line)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
            itemsIndexed(rows, key = { i, r -> "$i:${r.firstOrNull()}" }) { i, r ->
                val bg = rowBg?.invoke(i)
                Row(Modifier.fillMaxWidth().background(bg ?: Color.Transparent)
                    .clickable(enabled = onRowTap != null) { onRowTap?.invoke(i) }) {
                    cell(r.getOrElse(0) { "" }, i, 0, firstColBold, null, 0 in chipColumns)
                    Row(Modifier.horizontalScroll(hState)) {
                        r.drop(1).forEachIndexed { k, c ->
                            val col = k + 1
                            cell(c, i, col, false, if (highlightCol == col) today else null, col in chipColumns)
                        }
                    }
                }
                HorizontalDivider(color = line)
            }
        }
    }
}

/**
 * Full-screen official-form viewer: the same HTML document as the PDF (preview = PDF),
 * pinch-zoom, with Share PDF, Print and Back. Opened by tapping any form (RI 3/4, stock
 * card, home visit forms, filled visit forms).
 */
@Composable
fun ArogyaFormViewer(
    form: FormTable,
    hindi: Boolean,
    store: ArogyaRegistryStore,
    highlightCol: Int? = null,
    onBack: () -> Unit
) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    fun toast(m: String) = Toast.makeText(context, m, Toast.LENGTH_LONG).show()
    val doc = remember(form, highlightCol) { store.tableDoc(form, highlightCol) }
    ArogyaDocPdf.Viewer(doc, form.title, busy, hindi, onBack,
        onSharePdf = {
            busy = true
            scope.launch {
                try {
                    if (!store.shareFormPdf(form)) toast(tr("Could not create the PDF", "PDF नहीं बना"))
                } finally {
                    busy = false
                }
            }
        },
        onPrint = {
            busy = true
            scope.launch {
                try {
                    val bytes = store.renderFormPdf(form)
                    if (bytes == null || !ArogyaPrint.printPdf(context, form.title, bytes)) toast(tr("Print failed", "छपाई नहीं हुई"))
                } finally {
                    busy = false
                }
            }
        })
}
