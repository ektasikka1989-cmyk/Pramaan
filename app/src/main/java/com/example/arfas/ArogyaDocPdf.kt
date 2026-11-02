package com.example.arfas

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.print.PrintManager
import android.util.Log
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume

/**
 * Item 7: the phone side of the document renderer. The SAME filled HTML shows in the
 * viewer and goes to PDF/print, so preview = PDF. WebView renders offline (system Noto
 * fonts); no network, no binaries.
 */
object ArogyaDocPdf {
    private const val TAG = "AROGYA_DOC"
    private var cached: Map<String, String>? = null

    /** Templates + CSS from assets (cached; test builds inject by path instead). */
    fun templates(context: Context): Map<String, String> {
        cached?.let { return it }
        fun read(name: String) = runCatching {
            context.assets.open("doc_templates/$name").use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrDefault("")
        return mapOf("css" to read("doc.css"), "table" to read("table.html"),
            "mcp" to read("mcp.html"), "answer" to read("answer.html"))
            .also { if (it.values.all(String::isNotBlank)) cached = it }
    }

    /** Page header from the ASHA profile (blanks print as ruled lines). */
    fun header(context: Context): ArogyaDocRender.DocHeader {
        val p = context.getSharedPreferences("arogya_profile", Context.MODE_PRIVATE)
        return ArogyaDocRender.DocHeader(
            p.getString("state", "").orEmpty(), p.getString("district", "").orEmpty(),
            p.getString("block", "").orEmpty(), p.getString("subcentre", "").orEmpty(),
            p.getString("name", "").orEmpty()
        )
    }

    fun todayStr(): String = LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))

    fun fileName(title: String, ext: String): String =
        "ArogyaDoot-" + title.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').take(60) + ext

    private fun printAttrs(landscape: Boolean): PrintAttributes = PrintAttributes.Builder()
        .setMediaSize(if (landscape) PrintAttributes.MediaSize.ISO_A4.asLandscape() else PrintAttributes.MediaSize.ISO_A4.asPortrait())
        .setResolution(PrintAttributes.Resolution("pdf", "pdf", 300, 300))
        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
        .build()

    /**
     * Renders a filled document to PDF bytes: a headless software-layer WebView draws the
     * same HTML as the viewer into PdfDocument pages (A4, ~200 dpi raster text). Null on
     * failure. The print-dialog path ([printDoc]) keeps vector text via the print adapter.
     */
    suspend fun render(context: Context, doc: ArogyaDocRender.FilledDoc): ByteArray? {
        val t0 = System.currentTimeMillis()
        val pageW = if (doc.landscape) 2339 else 1654
        val pageH = if (doc.landscape) 1654 else 2339
        val ptsW = if (doc.landscape) 842 else 595
        val ptsH = if (doc.landscape) 595 else 842
        return try {
            withContext(Dispatchers.Main) {
                val web = WebView(context)
                try {
                    web.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    web.settings.javaScriptEnabled = false
                    web.settings.loadsImagesAutomatically = false
                    web.settings.blockNetworkLoads = true
                    web.settings.useWideViewPort = false
                    web.settings.loadWithOverviewMode = false
                    var finished = false
                    suspendCancellableCoroutine { cont ->
                        val main = Handler(Looper.getMainLooper())
                        val timeout = Runnable { if (cont.isActive) cont.resume(Unit) }
                        main.postDelayed(timeout, 15_000)
                        cont.invokeOnCancellation { main.removeCallbacks(timeout) }
                        web.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                finished = true
                                main.removeCallbacks(timeout)
                                if (cont.isActive) cont.resume(Unit)
                            }
                        }
                        web.loadDataWithBaseURL(null, doc.html, "text/html", "utf-8", null)
                    }
                    if (!finished) {
                        Log.w(TAG, "AROGYA_DOC_TIMEOUT title=${doc.title.take(40)}")
                        return@withContext null
                    }
                    web.measure(
                        View.MeasureSpec.makeMeasureSpec(pageW, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                    )
                    web.layout(0, 0, pageW, web.measuredHeight)
                    val contentH = web.measuredHeight.coerceAtLeast(1)
                    val pages = ((contentH + pageH - 1) / pageH).coerceAtLeast(1).coerceAtMost(100)
                    val pdf = android.graphics.pdf.PdfDocument()
                    val scale = ptsW.toFloat() / pageW
                    for (p in 0 until pages) {
                        val page = pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(ptsW, ptsH, p + 1).create())
                        val c = page.canvas
                        c.save()
                        c.scale(scale, scale)
                        c.clipRect(0, p * pageH, pageW, (p + 1) * pageH)
                        c.translate(0f, -(p * pageH).toFloat())
                        web.draw(c)
                        c.restore()
                        pdf.finishPage(page)
                    }
                    val bytes = withContext(Dispatchers.IO) {
                        java.io.ByteArrayOutputStream().also { pdf.writeTo(it) }.toByteArray()
                    }
                    pdf.close()
                    Log.i(TAG, "AROGYA_DOC_RENDER ms=${System.currentTimeMillis() - t0} bytes=${bytes.size} pages=$pages landscape=${doc.landscape}")
                    bytes
                } finally {
                    runCatching { web.destroy() }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "AROGYA_DOC_RENDER_EX ${e.message}")
            null
        }
    }

    /** System print sheet for a filled document (the user picks printer or Save as PDF). */
    fun printDoc(activity: Activity, doc: ArogyaDocRender.FilledDoc, jobName: String): Boolean = runCatching {
        val web = WebView(activity)
        web.settings.javaScriptEnabled = false
        web.settings.blockNetworkLoads = true
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                val pm = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
                pm.print(jobName, view.createPrintDocumentAdapter(jobName), printAttrs(doc.landscape))
            }
        }
        web.loadDataWithBaseURL(null, doc.html, "text/html", "utf-8", null)
        true
    }.getOrDefault(false)

    /**
     * Full-screen document viewer: the same HTML as the PDF, pinch-zoom, Share PDF,
     * Print and Back. Used for forms, the MCP card, the monthly summary and answers.
     */
    @Composable
    fun Viewer(
        doc: ArogyaDocRender.FilledDoc,
        title: String,
        busy: Boolean,
        hindi: Boolean,
        onBack: () -> Unit,
        onSharePdf: () -> Unit,
        onPrint: () -> Unit
    ) {
        fun tr(en: String, hi: String) = if (hindi) hi else en
        Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onBack) { Text(tr("‹ Back", "‹ पीछे")) }
                        Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 2)
                    }
                    Surface(Modifier.weight(1f).fillMaxWidth(), tonalElevation = 1.dp) {
                        AndroidView(factory = { ctx ->
                            WebView(ctx).apply {
                                settings.builtInZoomControls = true
                                settings.displayZoomControls = false
                                settings.setSupportZoom(true)
                                settings.javaScriptEnabled = false
                                settings.blockNetworkLoads = true
                                loadDataWithBaseURL(null, doc.html, "text/html", "utf-8", null)
                            }
                        })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onSharePdf, enabled = !busy, modifier = Modifier.weight(1f)) {
                            Text(if (busy) tr("Making PDF…", "PDF बन रही है…") else tr("Share PDF", "PDF भेजें"))
                        }
                        OutlinedButton(onClick = onPrint, enabled = !busy, modifier = Modifier.weight(1f)) { Text(tr("Print", "छापें")) }
                        OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text(tr("Back", "पीछे")) }
                    }
                    Text(tr("Preview = the PDF. Works offline.", "यही PDF बनेगी। ऑफ़लाइन चलता है।"),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
