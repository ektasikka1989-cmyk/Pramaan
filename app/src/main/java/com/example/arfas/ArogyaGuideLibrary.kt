package com.example.arfas

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/**
 * A1: the Official guides library — every Government of India document the answers come
 * from, read at runtime from the installed guide database (nothing hard-coded).
 * Fully offline except opening links.
 */
@Composable
fun ArogyaGuideLibrary(
    hindi: Boolean,
    ai: ArogyaVisitAi?,
    store: ArogyaRegistryStore,
    onAskGuide: (String, List<String>) -> Unit,
    onClose: () -> Unit
) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var docs by remember { mutableStateOf<List<GuideDoc>?>(null) }
    var query by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var lang by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<GuideDoc?>(null) }
    LaunchedEffect(Unit) { docs = ai?.guideLibrary() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClose) { Text(tr("‹ Back", "‹ पीछे")) }
                    Text(tr("Official guides", "आधिकारिक गाइड"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                }
                val list = docs
                if (list == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(tr("Reading the guide database on this phone…", "फ़ोन की गाइड पढ़ी जा रही है…"))
                } else if (list.isEmpty()) {
                    Text(tr("No guide is installed yet. Install it from Setup → Knowledge Base.", "अभी कोई गाइड नहीं है। Setup → Knowledge Base से लगाएँ।"))
                } else {
                    val (nDocs, nPages, nPassages) = ArogyaGuideLibrary.totals(list)
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(tr("Answers come only from these official Government of India documents",
                                "उत्तर सिर्फ़ इन्हीं आधिकारिक भारत सरकार दस्तावेज़ों से आते हैं"), fontWeight = FontWeight.Bold)
                            Text(tr("$nDocs documents · $nPages pages · $nPassages passages", "$nDocs दस्तावेज़ · $nPages पेज · $nPassages अंश"))
                            Text(tr("Stored on this phone · works offline", "इसी फ़ोन में हैं · बिना इंटरनेट चलते हैं"), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    OutlinedTextField(query, { query = it }, label = { Text(tr("Search title", "शीर्षक खोजें")) },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    val groups = remember(list) { ArogyaGuideLibrary.grouped(list).map { it.first } }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = body.isBlank(), onClick = { body = "" }, label = { Text(tr("All bodies", "सभी विभाग")) })
                        groups.forEach { g -> FilterChip(selected = body == g, onClick = { body = if (body == g) "" else g }, label = { Text("$g (${list.count { ArogyaGuideLibrary.group(it.authority) == g }})") }) }
                    }
                    val langs = remember(list) { list.map { it.language }.filter { it.isNotBlank() }.distinct().sorted() }
                    if (langs.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = lang.isBlank(), onClick = { lang = "" }, label = { Text(tr("All languages", "सभी भाषाएँ")) })
                        langs.forEach { l -> FilterChip(selected = lang == l, onClick = { lang = if (lang == l) "" else l }, label = { Text(l) }) }
                    }
                    val shown = remember(list, query, body, lang) {
                        list.filter { (body.isBlank() || ArogyaGuideLibrary.group(it.authority) == body) &&
                            (lang.isBlank() || it.language == lang) &&
                            (query.isBlank() || it.title.contains(query, ignoreCase = true)) }
                    }
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ArogyaGuideLibrary.grouped(shown).forEach { (g, ds) ->
                            item(key = "h$g$body$lang$query") { Text("$g (${ds.size})", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) }
                            items(ds, key = { it.sourceName }) { d ->
                                val dom = ArogyaGuideLibrary.domain(d)
                                Card(Modifier.fillMaxWidth().clickable { detail = d }) {
                                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(d.title.ifBlank { d.sourceName }, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                        Text(d.authority + (if (d.language.isNotBlank()) " · ${d.language}" else ""),
                                            style = MaterialTheme.typography.bodySmall)
                                        Text(tr("${d.pages} pages · ${d.passages} passages on this phone", "इस फ़ोन पर ${d.pages} पेज · ${d.passages} अंश"),
                                            style = MaterialTheme.typography.bodySmall)
                                        Text(if (dom.isNotBlank()) dom else tr("Link not recorded", "लिंक दर्ज नहीं"),
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        var listBusy by remember { mutableStateOf(false) }
                        OutlinedButton(onClick = {
                            listBusy = true
                            scope.launch {
                                try {
                                    val form = FormTable(tr("Official guides used by ArogyaDoot", "ArogyaDoot की आधिकारिक गाइड"),
                                        tr("Installed guide pack", "इंस्टॉल गाइड"),
                                        listOf(tr("Title", "शीर्षक"), tr("Issuing body", "विभाग"), tr("Link", "लिंक")),
                                        list.map { listOf(it.title.ifBlank { it.sourceName }, it.authority, ArogyaGuideLibrary.link(it)) })
                                    if (!store.shareFormPdf(form)) Toast.makeText(context, tr("Could not create the PDF", "PDF नहीं बना"), Toast.LENGTH_LONG).show()
                                } finally {
                                    listBusy = false
                                }
                            }
                        }, enabled = !listBusy, modifier = Modifier.weight(1f)) { Text(if (listBusy) tr("Making PDF…", "PDF बन रही है…") else tr("Share list (PDF)", "सूची भेजें (PDF)"), maxLines = 1) }
                        OutlinedButton(onClick = {
                            ArogyaShare.shareText(context, tr("Official guides", "आधिकारिक गाइड"),
                                list.joinToString("\n\n") { "• ${it.title.ifBlank { it.sourceName }}\n  ${it.authority}" +
                                    ArogyaGuideLibrary.link(it).takeIf { l -> l.isNotBlank() }?.let { "\n  $it" }.orEmpty() })
                        }, modifier = Modifier.weight(1f)) { Text(tr("Share list (text)", "सूची भेजें"), maxLines = 1) }
                    }
                }
            }
        }
    }
    detail?.let { d ->
        GuideDetailDialog(hindi, d, ai, onAskGuide = { topics -> onAskGuide(d.title.ifBlank { d.sourceName }, topics) }, onClose = { detail = null })
    }
}

@Composable
private fun GuideDetailDialog(hindi: Boolean, doc: GuideDoc, ai: ArogyaVisitAi?, onAskGuide: (List<String>) -> Unit, onClose: () -> Unit) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var topics by remember(doc) { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(doc) { topics = ai?.guideTopics(doc.sourceName) }
    val link = ArogyaGuideLibrary.link(doc)
    Dialog(onDismissRequest = onClose) {
        Surface(shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(doc.title.ifBlank { doc.sourceName }, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(doc.authority + (if (doc.language.isNotBlank()) " · ${doc.language}" else ""), style = MaterialTheme.typography.bodySmall)
                if (doc.category.isNotBlank()) Text(doc.category, style = MaterialTheme.typography.bodySmall)
                if (link.isNotBlank()) {
                    Text(ArogyaGuideLibrary.domain(doc), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { runCatching { uriHandler.openUri(link) }.onFailure {
                        Toast.makeText(context, tr("Opening needs internet.", "खोलने के लिए इंटरनेट चाहिए।"), Toast.LENGTH_LONG).show() } },
                        modifier = Modifier.fillMaxWidth()) { Text(tr("Open official page", "आधिकारिक पेज खोलें")) }
                } else Text(tr("Link not recorded", "लिंक दर्ज नहीं"), style = MaterialTheme.typography.bodySmall)
                if (doc.retrievedUtc.isNotBlank()) Text(tr("Retrieved: ", "प्राप्त: ") + doc.retrievedUtc.take(10), style = MaterialTheme.typography.bodySmall)
                if (doc.publicationDate.isNotBlank()) Text(tr("Published: ", "प्रकाशित: ") + doc.publicationDate, style = MaterialTheme.typography.bodySmall)
                Text(tr("Pages covered on this phone: ", "इस फ़ोन पर पेज: ") + tr("${doc.pages} pages · ${doc.passages} passages", "${doc.pages} पेज · ${doc.passages} अंश"),
                    style = MaterialTheme.typography.bodySmall)
                val ts = topics
                Button(onClick = { onAskGuide(ts.orEmpty()) }, enabled = ts != null, modifier = Modifier.fillMaxWidth()) {
                    Text(if (ts == null) tr("Loading topics…", "विषय लोड हो रहे हैं…") else tr("Ask about this guide", "इस गाइड के बारे में पूछें"))
                }
                TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(tr("Close", "बंद करें")) }
            }
        }
    }
}
