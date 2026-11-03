package com.example.arfas

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One Ask SOP exchange in the chat thread. The full result (sources, quote) is kept for re-open. */
data class SopChatMsg(
    val id: Long,
    val question: String,
    val result: ArogyaAiResult? = null,
    /** The exact query the runtime answered (thread [question] may carry a 📎 suffix). */
    val asked: String = ""
)

/**
 * An answer rendered in sections: each Answer:/What to do:/When to tell the ANM: label
 * (English or Hindi) becomes a small heading; unlabelled text renders as one block.
 * Raw Markdown is stripped: the card never shows ## or **.
 * Each sentence carries inline superscript citations (tappable, opens the source).
 * [news] sentences are highlighted (the Online
 * tab marks what the offline answer lacked).
 */
@Composable
fun SopAnswerText(
    text: String,
    segments: List<ArogyaOfficialAnswer.CitedSegment>,
    news: Set<String>,
    onCite: (String) -> Unit
) {
    val sections = remember(text) { ArogyaAnswerFormat.sections(text) }
    val segByText = remember(segments) { segments.associate { it.text to it.sourceIds } }
    fun norm(t: String) = t.lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ").trim()
    fun idsFor(sentence: String): List<String> {
        segByText[sentence]?.let { return it }
        val n = norm(sentence)
        return segByText.entries.firstOrNull { norm(it.key) == n }?.value.orEmpty()
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        sections.forEach { s ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (s.heading.isNotEmpty()) Text(s.heading, style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                if (s.body.isNotEmpty()) SopSectionBody(s.body, news, ::idsFor, onCite)
            }
        }
    }
}

/**
 * Part B7: one section as a flowing paragraph (17 sp, 1.45 line height): plain
 * sentences run together, numbered steps and bullets keep their own lines, and
 * each sentence carries inline superscript citations (tappable, opens the source).
 */
@Composable
private fun SopSectionBody(
    body: String,
    news: Set<String>,
    idsFor: (String) -> List<String>,
    onCite: (String) -> Unit
) {
    val sentences = remember(body) { ArogyaOfficialAnswer.splitSentences(body) }
    if (sentences.isEmpty()) {
        Text(body, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 25.sp))
        return
    }
    val primary = MaterialTheme.colorScheme.primary
    val newsBg = MaterialTheme.colorScheme.secondaryContainer
    val ann = remember(body, news) {
        buildAnnotatedString {
            sentences.forEachIndexed { i, sentence ->
                if (i > 0) append(if (ArogyaAnswerFormat.needsNewLine(sentence)) "\n" else " ")
                val start = length
                append(sentence)
                if (sentence in news) addStyle(SpanStyle(background = newsBg), start, length)
                idsFor(sentence).forEach { id ->
                    append(" ")
                    val c0 = length
                    append(ArogyaAnswerFormat.supDigits(id))
                    addStyle(SpanStyle(color = primary, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                        baselineShift = BaselineShift.Superscript), c0, length)
                    addStringAnnotation("cite", id, c0, length)
                }
            }
        }
    }
    ClickableText(ann, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 25.sp)) { offset ->
        ann.getStringAnnotations("cite", offset, offset).firstOrNull()?.let { onCite(it.item) }
    }
}

/**
 * An earlier answer in the thread: question + answer + sources, collapsible.
 * Tap expands the same full card the latest answer shows (buttons included).
 */
@Composable
fun SopChatHistoryCard(
    msg: SopChatMsg,
    hindi: Boolean,
    warning: String?,
    inVisit: Boolean,
    ai: ArogyaVisitAi?,
    busy: Boolean,
    pending: Int,
    onOpenUrl: (String) -> Unit,
    onSpeak: (String) -> Unit,
    onStopSpeak: () -> Unit,
    onFamily: (Boolean) -> Unit,
    onCheckOnline: () -> Unit,
    onMoreDetail: () -> Unit,
    onSaveForLater: () -> Unit,
    onEscalate: (String) -> Unit,
    context: Context
) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    var expanded by remember(msg.id) { mutableStateOf(false) }
    val result = msg.result
    Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row {
                Text(msg.question, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(if (expanded) "▴" else "▾", color = MaterialTheme.colorScheme.primary)
            }
            if (result == null) {
                Text(tr("No answer recorded.", "कोई उत्तर दर्ज नहीं।"), style = MaterialTheme.typography.bodySmall)
            } else if (!expanded) {
                Text(ArogyaMarkdown.strip(result.text), maxLines = 6, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 17.sp, lineHeight = 25.sp))
                if (result.sources.isNotEmpty()) {
                    val s0 = result.sources.first()
                    Text(tr("📚 From the guide: ", "📚 गाइड से: ") + s0.title +
                        (if (s0.locator.isNotBlank()) ", ${s0.locator}" else ""),
                        style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
                TextButton(onClick = { expanded = true }) { Text(tr("Show more", "और देखें")) }
            } else {
                // Expanded history shows the full card; the family explanation it triggers
                // reads back in the latest card (one family block per window).
                SopAnswerCard(result, msg.question, msg.asked.ifBlank { msg.question }, warning, inVisit,
                    ai, busy, pending, false, onOpenUrl, onSpeak, onStopSpeak, "", false, "", 0L,
                    onFamily, onCheckOnline, onMoreDetail, onSaveForLater, onEscalate, context, hindi)
                TextButton(onClick = { expanded = false }) { Text(tr("Show less", "कम देखें")) }
            }
        }
    }
}

/** Progress card while the answer is being written (shared by the chat thread and the draft flow). */
@Composable
fun SopBusyCard(
    hindi: Boolean,
    online: Boolean,
    checking: Boolean,
    taskExplain: Boolean,
    draft: String,
    secs: Long,
    stalled: Boolean,
    onCancel: () -> Unit
) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        val stage = when {
            online -> tr("Checking online…", "ऑनलाइन जाँच हो रही है…")
            checking -> tr("Checking against the guide…", "गाइड से मिलाया जा रहा है…")
            !taskExplain -> tr("Organising your notes on this phone…", "फ़ोन पर नोट्स व्यवस्थित हो रहे हैं…")
            stalled -> tr("Step 3 of 3 · Checking every statement against the guide…", "चरण 3/3 · हर बात गाइड से मिलाई जा रही है…")
            draft.isNotBlank() || secs >= 20 -> tr("Step 2 of 3 · Writing the answer…", "चरण 2/3 · उत्तर लिखा जा रहा है…")
            else -> tr("Step 1 of 3 · Searching the ASHA guide…", "चरण 1/3 · ASHA गाइड में खोज…")
        }
        Text("$stage  ${secs}s", fontWeight = FontWeight.Medium)
        if (draft.isNotBlank()) Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp)) {
                Text(tr("Draft — checked against the guide before it is shown as the answer", "ड्राफ़्ट — उत्तर बनने से पहले गाइड से जाँचा जाएगा"), style = MaterialTheme.typography.labelSmall)
                Text(draft, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = onCancel) { Text(tr("Cancel", "रद्द करें")) }
    }
}

/**
 * Item 4 + 5 answer layout (latest card and expanded history alike): the model chip, the
 * "guide says" quote only when it adds to the answer (>= 70% overlap hides it), the
 * answer first in cited sections, one compact source line that expands to the highlighted
 * passages with "Open official document", then one buttons row: read aloud, explain,
 * check online, more detail, share. A checked answer shows Offline | Online tabs (both
 * kept); the Online tab highlights sentences the offline answer lacked.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SopAnswerCard(
    result: ArogyaAiResult,
    question: String,
    /** The exact query the runtime answered (for Check online / More detail). */
    asked: String,
    warning: String?,
    inVisit: Boolean,
    ai: ArogyaVisitAi?,
    busy: Boolean,
    pending: Int,
    /** History cards trigger the family explanation; its text reads in the latest card. */
    showFamily: Boolean,
    onOpenUrl: (String) -> Unit,
    onSpeak: (String) -> Unit,
    onStopSpeak: () -> Unit,
    familyText: String,
    familyBusy: Boolean,
    familyModel: String,
    familyMs: Long,
    onFamily: (Boolean) -> Unit,
    onCheckOnline: () -> Unit,
    onMoreDetail: () -> Unit,
    onSaveForLater: () -> Unit,
    onEscalate: (String) -> Unit,
    context: Context,
    hindi: Boolean
) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    val hasOnline = result.onlineText.isNotBlank()
    var tab by remember(result) { mutableStateOf(0) }
    val news = remember(result) { if (hasOnline) ArogyaOfficialAnswer.newSentences(result.text, result.onlineText) else emptySet() }
    val tabText = if (tab == 1) result.onlineText else result.text
    val tabSources = if (tab == 1) result.onlineSources else result.sources
    val tabSegments = if (tab == 1) result.onlineSegments else result.segments
    val tabTier = if (tab == 1) result.onlineTier else result.tier
    val grounded = tabSources.isNotEmpty() && !result.sourceOnly && !result.r8Blocked
    val showQuote = tab == 0 && result.quote.isNotBlank() && !ArogyaAnswerFormat.isDuplicate(result.text, result.quote)
    var srcOpen by remember(result) { mutableStateOf(false) }
    var hl by remember(result) { mutableStateOf("") }
    fun cite(id: String) { srcOpen = true; hl = id }
    var shareOpen by remember(result) { mutableStateOf(false) }
    var ansDoc by remember(result) { mutableStateOf<ArogyaDocRender.FilledDoc?>(null) }
    var ansBusy by remember(result) { mutableStateOf(false) }
    val ansScope = rememberCoroutineScope()
    // The share sheet covers the visible tab (offline or online).
    val shareText = if (tab == 1) result.onlineText else result.text
    val shareSegments = if (tab == 1) result.onlineSegments else result.segments
    val shareSources = if (tab == 1) result.onlineSources else result.sources
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (tab == 1) AiResultChip(result.onlineProvider.ifBlank { "ArogyaDoot • online" }, true, result.onlineMs, hindi)
        else AiResultChip(result.provider, result.online, result.elapsedMs, hindi)
        if (hasOnline) TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0; hl = "" }, text = { Text(tr("📱 Offline", "📱 ऑफ़लाइन")) })
            Tab(selected = tab == 1, onClick = { tab = 1; hl = "" }, text = { Text(tr("☁ Online", "☁ ऑनलाइन")) })
        }
        if (tabTier == 2) Text(tr("🌐 From an official website (not in your guides)", "🌐 आधिकारिक वेबसाइट से (आपकी गाइड में नहीं है)"),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        else if (grounded) Text(tr("📚 From official guides", "📚 आधिकारिक गाइड से"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        if (showQuote) Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(12.dp)) {
                Text(tr("The guide says", "गाइड में लिखा है"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text("“${result.quote}”")
                if (result.quoteSource.isNotBlank()) Text("— " + result.quoteSource, style = MaterialTheme.typography.bodySmall)
            }
        }
        SopAnswerText(tabText, tabSegments, if (tab == 1) news else emptySet(), ::cite)
        if (ArogyaFlags.r7SourceLine && tabSources.isNotEmpty()) {
            val s0 = tabSources.first()
            val anyWeb = tabSources.any { it.kind == "web" }
            TextButton(onClick = { srcOpen = !srcOpen }) {
                Text((if (anyWeb) tr("🌐 Sources: ", "🌐 स्रोत: ") else tr("📚 From the guide: ", "📚 गाइड से: ")) + s0.title +
                    (if (s0.locator.isNotBlank()) ", ${s0.locator}" else "") +
                    (if (s0.kind == "web" && s0.date.isNotBlank()) " (${s0.date})" else "") +
                    (if (tabSources.size > 1) tr(" +${tabSources.size - 1} more", " +${tabSources.size - 1} और") else "") +
                    (if (srcOpen) " ▴" else " ▾"),
                    fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
            }
            if (srcOpen) tabSources.forEach { s ->
                key(s.id, tab) {
                    val bring = remember { BringIntoViewRequester() }
                    LaunchedEffect(hl) { if (hl == s.id) runCatching { bring.bringIntoView() } }
                    Card(Modifier.fillMaxWidth().bringIntoViewRequester(bring),
                        colors = if (hl == s.id) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        else CardDefaults.cardColors()) {
                        Column(Modifier.padding(12.dp)) {
                            if (s.kind == "web") {
                                Text("🌐 [${s.id}] ${s.title}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                                Text(listOf(s.domain, s.locator, s.date).filter { it.isNotBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                Text(tr("From an official website (not in your guides). Open the link to read the original.",
                                    "आधिकारिक वेबसाइट से (आपकी गाइड में नहीं है)। मूल पढ़ने के लिए लिंक खोलें।"),
                                    style = MaterialTheme.typography.bodySmall)
                            } else {
                                Text("[${s.id}] ${s.title}" + (if (s.locator.isNotBlank()) " · ${s.locator}" else ""),
                                    fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                            }
                            ArogyaPassageText(s)
                            if (s.url.startsWith("http")) TextButton(onClick = { onOpenUrl(ArogyaGuideLibrary.pageUrl(s.url, s.locator)) }) {
                                Text(if (s.kind == "web") tr("Open link", "लिंक खोलें") else tr("Open official document", "आधिकारिक दस्तावेज़ खोलें"))
                            }
                        }
                    }
                }
            }
        }
        if (tab == 0 && result.r8Blocked) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    // In a visit the ANM dialog carries the pre-filled message; standalone SOP shares it.
                    if (inVisit) onEscalate(question)
                    else {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, tr("Please advise: ", "कृपया बताएँ: ") + question.trim())
                        }
                        runCatching { context.startActivity(Intent.createChooser(send, tr("Ask ANM", "ANM से पूछें"))) }
                    }
                }, modifier = Modifier.weight(1f)) { Text(tr("Ask ANM", "ANM से पूछें")) }
                OutlinedButton(onClick = onSaveForLater, modifier = Modifier.weight(1f)) { Text(tr("💾 Save to check later", "💾 बाद में जाँचें")) }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { onSpeak(tabText) }) { Text(tr("🔊 Read aloud", "🔊 सुनें")) }
            TextButton(onClick = onStopSpeak) { Text(tr("Stop", "रोकें")) }
            if (ai != null && !result.sourceOnly) {
                Button(onClick = { onFamily(true) }, enabled = !familyBusy && !busy) { Text(if (familyBusy) tr("Writing…", "लिखा जा रहा है…") else tr("👪 Explain to family (हिंदी)", "👪 परिवार को समझाएँ")) }
                TextButton(onClick = { onFamily(false) }, enabled = !familyBusy && !busy) { Text(tr("English", "English")) }
                AiBadge("Explain to family")
            }
            if (!hasOnline && !result.online && !ArogyaOnlinePolicy.localOnly(question)) Button(onClick = onCheckOnline, enabled = !busy && pending == 0 && asked.isNotBlank()) { Text(tr("☁ Check online", "☁ ऑनलाइन जाँचें")) }
            if (!result.sourceOnly) OutlinedButton(onClick = onMoreDetail, enabled = !busy && pending == 0 && asked.isNotBlank()) { Text(tr("More detail", "और विस्तार")) }
            OutlinedButton(onClick = { shareOpen = true }) { Text(tr("Share answer", "उत्तर भेजें")) }
        }
        if (warning != null && inVisit) Button(onClick = { onEscalate(question) }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), modifier = Modifier.fillMaxWidth()) { Text(tr("Escalate to ANM", "ANM को सूचित करें")) }
        if (showFamily && ai != null && !result.sourceOnly && familyText.isNotBlank()) Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("For the family", "परिवार के लिए"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                if (familyModel.isNotBlank()) AiResultChip(familyModel, !familyModel.startsWith("On-phone"), familyMs, hindi)
                Text(familyText, style = MaterialTheme.typography.bodyLarge, fontSize = 20.sp, lineHeight = 29.sp)
                Button(onClick = { onSpeak(familyText) }, modifier = Modifier.fillMaxWidth()) { Text(tr("🔊 Read to the family", "🔊 परिवार को सुनाएँ")) }
                OutlinedButton(onClick = { ArogyaShare.shareText(context, tr("For the family", "परिवार के लिए"), familyText) }, modifier = Modifier.fillMaxWidth()) { Text(tr("Share with family (WhatsApp)", "परिवार को भेजें (WhatsApp)")) }
                Text(tr("AI rewrite of the guide answer in simple words. No new advice or numbers are added — check before reading out.", "गाइड के उत्तर का सरल रूप। कोई नई सलाह या संख्या नहीं जोड़ी गई — सुनाने से पहले देख लें।"), style = MaterialTheme.typography.bodySmall)
            }
        }
        Text((if (tab == 1 || result.online) tr("Online", "ऑनलाइन") else tr("Offline on this phone", "इस फ़ोन पर ऑफ़लाइन")) +
            " · ${(if (tab == 1) result.onlineMs else result.elapsedMs) / 1000}s · " +
            tr("Not a diagnosis — ask your ANM if unsure.", "यह निदान नहीं है — शंका हो तो ANM से पूछें।"), style = MaterialTheme.typography.bodySmall)
        if (shareOpen) {
            val store = remember { ArogyaRegistryStore.get(context) }
            fun toast(m: String) = Toast.makeText(context, m, Toast.LENGTH_LONG).show()
            AlertDialog(onDismissRequest = { if (!ansBusy) shareOpen = false },
                title = { Text(tr("Share answer", "उत्तर भेजें")) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val entry = ArogyaGuideEntry(System.currentTimeMillis(), question, shareText, result.quote,
                                result.quoteSource, shareSources, result.provider, result.elapsedMs, "", result.tier)
                            ArogyaShare.shareText(context, "Guide answer", ArogyaShare.guideEntryText(entry))
                            shareOpen = false
                        }, modifier = Modifier.fillMaxWidth()) { Text(tr("Share as text", "टेक्स्ट भेजें")) }
                        OutlinedButton(onClick = {
                            ansDoc = store.answerDoc(question, shareSegments, shareText, shareSources)
                            shareOpen = false
                        }, modifier = Modifier.fillMaxWidth()) { Text(tr("View printable", "छपने वाला देखें")) }
                        Button(onClick = {
                            ansBusy = true
                            ansScope.launch {
                                try {
                                    if (!store.shareAnswerPdf(question, shareSegments, shareText, shareSources)) toast(tr("Could not create the PDF", "PDF नहीं बना"))
                                    else shareOpen = false
                                } finally {
                                    ansBusy = false
                                }
                            }
                        }, enabled = !ansBusy, modifier = Modifier.fillMaxWidth()) { Text(if (ansBusy) tr("Making PDF…", "PDF बन रही है…") else tr("Share PDF", "PDF भेजें")) }
                        OutlinedButton(onClick = {
                            ansBusy = true
                            ansScope.launch {
                                try {
                                    val bytes = store.renderDoc(store.answerDoc(question, shareSegments, shareText, shareSources))
                                    if (bytes == null || !ArogyaPrint.printPdf(context, "SOP answer", bytes)) toast(tr("Print failed", "छपाई नहीं हुई"))
                                    else shareOpen = false
                                } finally {
                                    ansBusy = false
                                }
                            }
                        }, enabled = !ansBusy, modifier = Modifier.fillMaxWidth()) { Text(tr("Print", "छापें")) }
                    }
                },
                confirmButton = { TextButton(onClick = { if (!ansBusy) shareOpen = false }) { Text(tr("Close", "बंद")) } })
        }
        ansDoc?.let { d ->
            var viewBusy by remember { mutableStateOf(false) }
            val store = remember { ArogyaRegistryStore.get(context) }
            fun toast(m: String) = Toast.makeText(context, m, Toast.LENGTH_LONG).show()
            ArogyaDocPdf.Viewer(d, tr("Answer — printable", "उत्तर — छपने वाला"), viewBusy, hindi, { ansDoc = null },
                onSharePdf = {
                    viewBusy = true
                    ansScope.launch {
                        try {
                            if (!store.shareAnswerPdf(question, shareSegments, shareText, shareSources)) toast(tr("Could not create the PDF", "PDF नहीं बना"))
                        } finally {
                            viewBusy = false
                        }
                    }
                },
                onPrint = {
                    viewBusy = true
                    ansScope.launch {
                        try {
                            val bytes = store.renderDoc(d)
                            if (bytes == null || !ArogyaPrint.printPdf(context, "SOP answer", bytes)) toast(tr("Print failed", "छपाई नहीं हुई"))
                        } finally {
                            viewBusy = false
                        }
                    }
                })
        }
    }
}
