package com.example.arfas

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

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
 */
@Composable
fun SopAnswerText(text: String) {
    val sections = remember(text) { ArogyaAnswerFormat.sections(text) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        sections.forEach { s ->
            if (s.heading.isNotEmpty()) Text(s.heading, style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            if (s.body.isNotEmpty()) Text(s.body, style = MaterialTheme.typography.bodyLarge)
        }
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
    onEscalate: (String) -> Unit,
    context: Context
) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    var expanded by remember(msg.id) { mutableStateOf(false) }
    val result = msg.result
    Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row {
                Text(msg.question, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(if (expanded) "▴" else "▾", color = MaterialTheme.colorScheme.primary)
            }
            if (result == null) {
                Text(tr("No answer recorded.", "कोई उत्तर दर्ज नहीं।"), style = MaterialTheme.typography.bodySmall)
            } else if (!expanded) {
                Text(ArogyaMarkdown.strip(result.text), maxLines = 3, style = MaterialTheme.typography.bodyMedium)
                if (result.sources.isNotEmpty()) {
                    val s0 = result.sources.first()
                    Text(tr("📚 From the guide: ", "📚 गाइड से: ") + s0.title +
                        (if (s0.locator.isNotBlank()) ", ${s0.locator}" else ""),
                        style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
            } else {
                // Expanded history shows the full card; the family explanation it triggers
                // reads back in the latest card (one family block per window).
                SopAnswerCard(result, msg.question, msg.asked.ifBlank { msg.question }, warning, inVisit,
                    ai, busy, pending, false, onOpenUrl, onSpeak, onStopSpeak, "", false, "", 0L,
                    onFamily, onCheckOnline, onMoreDetail, onEscalate, context, hindi)
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
 * Item 4 answer layout (latest card and expanded history alike): the model chip, the
 * "guide says" quote only when it adds to the answer (>= 70% overlap hides it), the
 * answer first in sections, one compact source line that expands to the highlighted
 * passages with "Open official document", then one buttons row: read aloud, explain,
 * check online, more detail, share.
 */
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
    onEscalate: (String) -> Unit,
    context: Context,
    hindi: Boolean
) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    val grounded = result.sources.isNotEmpty() && !result.sourceOnly && !result.r8Blocked
    val showQuote = result.quote.isNotBlank() && !ArogyaAnswerFormat.isDuplicate(result.text, result.quote)
    var srcOpen by remember(result) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AiResultChip(result.provider, result.online, result.elapsedMs, hindi)
        if (grounded) Text(tr("📚 From official guides", "📚 आधिकारिक गाइड से"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        if (showQuote) Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(12.dp)) {
                Text(tr("The guide says", "गाइड में लिखा है"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text("“${result.quote}”")
                if (result.quoteSource.isNotBlank()) Text("— " + result.quoteSource, style = MaterialTheme.typography.bodySmall)
            }
        }
        SopAnswerText(result.text)
        if (ArogyaFlags.r7SourceLine && result.sources.isNotEmpty()) {
            val s0 = result.sources.first()
            TextButton(onClick = { srcOpen = !srcOpen }) {
                Text(tr("📚 From the guide: ", "📚 गाइड से: ") + s0.title +
                    (if (s0.locator.isNotBlank()) ", ${s0.locator}" else "") + (if (srcOpen) " ▴" else " ▾"),
                    fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
            }
            if (srcOpen) result.sources.forEach { s ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("[${s.id}] ${s.title}" + (if (s.locator.isNotBlank()) " · ${s.locator}" else ""), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                        ArogyaPassageText(s)
                        if (s.url.startsWith("http")) TextButton(onClick = { onOpenUrl(ArogyaGuideLibrary.pageUrl(s.url, s.locator)) }) { Text(tr("Open official document", "आधिकारिक दस्तावेज़ खोलें")) }
                    }
                }
            }
        }
        if (result.r8Blocked) {
            Button(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, tr("Please advise: ", "कृपया बताएँ: ") + question.trim())
                }
                runCatching { context.startActivity(Intent.createChooser(send, tr("Ask ANM", "ANM से पूछें"))) }
            }, modifier = Modifier.fillMaxWidth()) { Text(tr("Ask ANM", "ANM से पूछें")) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { onSpeak(result.text) }) { Text(tr("🔊 Read aloud", "🔊 सुनें")) }
            TextButton(onClick = onStopSpeak) { Text(tr("Stop", "रोकें")) }
            if (ai != null && !result.sourceOnly) {
                Button(onClick = { onFamily(true) }, enabled = !familyBusy && !busy) { Text(if (familyBusy) tr("Writing…", "लिखा जा रहा है…") else tr("👪 Explain to family (हिंदी)", "👪 परिवार को समझाएँ")) }
                TextButton(onClick = { onFamily(false) }, enabled = !familyBusy && !busy) { Text(tr("English", "English")) }
                AiBadge("Explain to family")
            }
            if (!result.online && !ArogyaOnlinePolicy.localOnly(question)) Button(onClick = onCheckOnline, enabled = !busy && pending == 0 && asked.isNotBlank()) { Text(tr("☁ Check online", "☁ ऑनलाइन जाँचें")) }
            if (!result.sourceOnly) OutlinedButton(onClick = onMoreDetail, enabled = !busy && pending == 0 && asked.isNotBlank()) { Text(tr("More detail", "और विस्तार")) }
            OutlinedButton(onClick = { ArogyaShare.shareText(context, "Guide answer", ArogyaShare.guideEntryText(ArogyaGuideEntry(System.currentTimeMillis(), question, result.text, result.quote, result.quoteSource, result.sources, result.provider, result.elapsedMs))) }) { Text(tr("Share answer", "उत्तर भेजें")) }
        }
        if (warning != null && inVisit) Button(onClick = { onEscalate(question) }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), modifier = Modifier.fillMaxWidth()) { Text(tr("Escalate to ANM", "ANM को सूचित करें")) }
        if (showFamily && ai != null && !result.sourceOnly && familyText.isNotBlank()) Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(12.dp)) {
                Text(tr("For the family", "परिवार के लिए"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                if (familyModel.isNotBlank()) AiResultChip(familyModel, !familyModel.startsWith("On-phone"), familyMs, hindi)
                Text(familyText, style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = { onSpeak(familyText) }) { Text(tr("🔊 Read to the family", "🔊 परिवार को सुनाएँ")) }
                Text(tr("AI rewrite of the guide answer in simple words. No new advice or numbers are added — check before reading out.", "गाइड के उत्तर का सरल रूप। कोई नई सलाह या संख्या नहीं जोड़ी गई — सुनाने से पहले देख लें।"), style = MaterialTheme.typography.bodySmall)
            }
        }
        Text((if (result.online) tr("Online", "ऑनलाइन") else tr("Offline on this phone", "इस फ़ोन पर ऑफ़लाइन")) + " · ${result.elapsedMs / 1000}s · " + tr("Not a diagnosis — ask your ANM if unsure.", "यह निदान नहीं है — शंका हो तो ANM से पूछें।"), style = MaterialTheme.typography.bodySmall)
    }
}
