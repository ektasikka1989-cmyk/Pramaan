package com.example.arfas

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First-run setup wizard (also from More → Setup): finds the Llama model, Whisper
 * model and guide DB in Downloads or the bundled asset, installs them with progress,
 * loads the sample village, and ends on "Ready — works offline".
 */
@Composable
fun ArogyaSetupWizard(
    hindi: Boolean, ai: ArogyaVisitAi?, knowledgeReady: Boolean, sampleLoaded: Boolean,
    onImportKnowledge: (Uri) -> Unit, onLoadSample: () -> Unit, onRefreshKnowledge: () -> Unit,
    onDone: () -> Unit
) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    data class RowState(val label: String, val state: String, val detail: String, val progress: Int)
    var rows by remember { mutableStateOf<List<RowState>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    fun setRow(step: ArogyaSetup.Step, label: String, state: String, detail: String = "", progress: Int = 0) {
        rows = (rows.filterNot { it.label == label } + RowState(label, state, detail, progress)).sortedBy {
            when (ArogyaSetup.Step.valueOf(it.label.substringBefore("|"))) { ArogyaSetup.Step.LLAMA -> 0; ArogyaSetup.Step.WHISPER -> 1; ArogyaSetup.Step.GUIDE -> 2; ArogyaSetup.Step.SAMPLE -> 3 }
        }
    }
    fun labelOf(step: ArogyaSetup.Step, name: String) = "${step.name}|$name"
    suspend fun rescan(): ArogyaSetup.Scan {
        val v = withContext(Dispatchers.IO) { ai?.guideVersion() ?: -1 }
        return ArogyaSetup.scan(context, knowledgeReady, v == 2, sampleLoaded)
    }
    suspend fun installAll() {
        busy = true
        try {
            val scan = rescan()
            val found = scan.found.toMutableMap()
            // 1. Llama
            val llamaName = tr("Llama model (offline answers)", "Llama मॉडल (ऑफ़लाइन जवाब)")
            if (scan.llamaReady) setRow(ArogyaSetup.Step.LLAMA, labelOf(ArogyaSetup.Step.LLAMA, llamaName), "✓")
            else {
                val hit = found[ArogyaSetupMatch.Kind.LLAMA]
                if (hit == null) setRow(ArogyaSetup.Step.LLAMA, labelOf(ArogyaSetup.Step.LLAMA, llamaName), "✗",
                    tr("Not found. Put the Llama .gguf in Downloads, then Retry.", "नहीं मिला। Llama .gguf Downloads में रखें, फिर Retry दबाएँ।"))
                else {
                    setRow(ArogyaSetup.Step.LLAMA, labelOf(ArogyaSetup.Step.LLAMA, llamaName), "…", hit.second, 1)
                    val ok = ArogyaSetup.installLlama(context, hit.first, hit.second) { p ->
                        setRow(ArogyaSetup.Step.LLAMA, labelOf(ArogyaSetup.Step.LLAMA, llamaName), "…", hit.second, p)
                    }
                    setRow(ArogyaSetup.Step.LLAMA, labelOf(ArogyaSetup.Step.LLAMA, llamaName), if (ok) "✓" else "✗",
                        if (ok) hit.second else tr("Install failed — check free storage, then Retry.", "इंस्टॉल नहीं हुआ — जगह देखें, फिर Retry दबाएँ।"))
                }
            }
            // 2. Whisper
            val whisperName = tr("Whisper model (offline speech)", "Whisper मॉडल (ऑफ़लाइन आवाज़)")
            if (scan.whisperReady) setRow(ArogyaSetup.Step.WHISPER, labelOf(ArogyaSetup.Step.WHISPER, whisperName), "✓")
            else {
                val hit = found[ArogyaSetupMatch.Kind.WHISPER]
                if (hit == null) setRow(ArogyaSetup.Step.WHISPER, labelOf(ArogyaSetup.Step.WHISPER, whisperName), "✗",
                    tr("Not found. Put ggml-small.bin in Downloads, then Retry.", "नहीं मिला। ggml-small.bin Downloads में रखें, फिर Retry दबाएँ।"))
                else {
                    setRow(ArogyaSetup.Step.WHISPER, labelOf(ArogyaSetup.Step.WHISPER, whisperName), "…", hit.second, 1)
                    val ok = ArogyaSetup.installWhisper(context, hit.first, hit.second) { p ->
                        setRow(ArogyaSetup.Step.WHISPER, labelOf(ArogyaSetup.Step.WHISPER, whisperName), "…", hit.second, p)
                    }
                    setRow(ArogyaSetup.Step.WHISPER, labelOf(ArogyaSetup.Step.WHISPER, whisperName), if (ok) "✓" else "✗",
                        if (ok) hit.second else tr("Install failed — check free storage, then Retry.", "इंस्टॉल नहीं हुआ — जगह देखें, फिर Retry दबाएँ।"))
                }
            }
            // 3. Guide (+ v2)
            val guideName = tr("Official guides", "आधिकारिक गाइड")
            val v2 = found[ArogyaSetupMatch.Kind.GUIDE_V2]
            val v1 = found[ArogyaSetupMatch.Kind.GUIDE_V1]
            if (scan.guideV2) setRow(ArogyaSetup.Step.GUIDE, labelOf(ArogyaSetup.Step.GUIDE, guideName), "✓", tr("Guide v2 installed", "गाइड v2 लग गई"))
            else if (v2 != null) {
                setRow(ArogyaSetup.Step.GUIDE, labelOf(ArogyaSetup.Step.GUIDE, guideName), "…", v2.second, 50)
                onImportKnowledge(v2.first)
                setRow(ArogyaSetup.Step.GUIDE, labelOf(ArogyaSetup.Step.GUIDE, guideName), "…",
                    tr("Installing guide v2 — wait, then Retry to confirm.", "गाइड v2 लग रही है — रुकें, फिर Retry से देखें।"), 90)
            } else if (scan.guideReady) setRow(ArogyaSetup.Step.GUIDE, labelOf(ArogyaSetup.Step.GUIDE, guideName), "✓",
                tr("Guide installed (v1) — v2 not in Downloads", "गाइड लगी है (v1) — v2 Downloads में नहीं"))
            else if (v1 != null) {
                setRow(ArogyaSetup.Step.GUIDE, labelOf(ArogyaSetup.Step.GUIDE, guideName), "…", v1.second, 50)
                onImportKnowledge(v1.first)
                setRow(ArogyaSetup.Step.GUIDE, labelOf(ArogyaSetup.Step.GUIDE, guideName), "…",
                    tr("Installing guide — wait, then Retry to confirm.", "गाइड लग रही है — रुकें, फिर Retry से देखें।"), 90)
            } else {
                val asset = ArogyaSetup.bundledGuideV2Uri(context)
                if (asset != null) {
                    setRow(ArogyaSetup.Step.GUIDE, labelOf(ArogyaSetup.Step.GUIDE, guideName), "…",
                        tr("Installing the bundled guide v2…", "साथ वाली गाइड v2 लग रही है…"), 50)
                    onImportKnowledge(asset)
                    setRow(ArogyaSetup.Step.GUIDE, labelOf(ArogyaSetup.Step.GUIDE, guideName), "…",
                        tr("Installing guide v2 — wait, then Retry to confirm.", "गाइड v2 लग रही है — रुकें, फिर Retry से देखें।"), 90)
                } else setRow(ArogyaSetup.Step.GUIDE, labelOf(ArogyaSetup.Step.GUIDE, guideName), "✗",
                    tr("Not found. Put ASHA_Health_Guide_v2.db in Downloads, then Retry.", "नहीं मिली। ASHA_Health_Guide_v2.db Downloads में रखें, फिर Retry दबाएँ।"))
            }
            // 4. Sample village
            val sampleName = tr("Sample village (fictional people)", "नमूना गाँव (काल्पनिक लोग)")
            if (sampleLoaded) setRow(ArogyaSetup.Step.SAMPLE, labelOf(ArogyaSetup.Step.SAMPLE, sampleName), "✓")
            else {
                setRow(ArogyaSetup.Step.SAMPLE, labelOf(ArogyaSetup.Step.SAMPLE, sampleName), "…", "", 50)
                runCatching { onLoadSample() }
                setRow(ArogyaSetup.Step.SAMPLE, labelOf(ArogyaSetup.Step.SAMPLE, sampleName), "✓")
            }
            onRefreshKnowledge()
            val again = rescan()
            ready = again.llamaReady && again.whisperReady && (again.guideReady || again.guideV2)
            if (ready) { ArogyaSetup.markDone(context); android.util.Log.i("ArogyaDemo", "AROGYA_SETUP ready") }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            android.util.Log.w("ArogyaDemo", "AROGYA_SETUP failed ${e.message?.take(160)}")
        } finally { busy = false }
    }
    LaunchedEffect(Unit) { scope.launch { installAll() } }
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!ready) {
                    Text(tr("Setting up ArogyaDoot…", "ArogyaDoot तैयार हो रहा है…"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(tr("Finding the offline model, speech and guides — no tapping needed.", "ऑफ़लाइन मॉडल, आवाज़ और गाइड ढूँढ रहे हैं — कुछ दबाना नहीं है।"), style = MaterialTheme.typography.bodySmall)
                    rows.forEach { r ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(r.state, fontWeight = FontWeight.Bold)
                            Column(Modifier.weight(1f)) {
                                Text(r.label.substringAfter("|"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                if (r.detail.isNotBlank()) Text(r.detail, style = MaterialTheme.typography.bodySmall)
                                if (r.state == "…" && r.progress in 1..99) LinearProgressIndicator(progress = { r.progress / 100f }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { scope.launch { installAll() } }, enabled = !busy, modifier = Modifier.weight(1f)) {
                            Text(if (busy) tr("Working…", "चल रहा है…") else tr("Retry", "Retry"))
                        }
                        OutlinedButton(onClick = { ArogyaSetup.markDone(context); onDone() }, enabled = !busy) { Text(tr("Skip", "छोड़ें")) }
                    }
                } else {
                    Text("✓", style = MaterialTheme.typography.displayMedium)
                    Text(tr("Ready — works offline", "तैयार — ऑफ़लाइन चलता है"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    Text(tr("Speech, forms, guides and registers all run on this phone. Online features need a Meta Model API key — everything works offline without it.",
                        "आवाज़, फ़ॉर्म, गाइड और रजिस्टर सब इसी फ़ोन पर चलते हैं। ऑनलाइन फ़ीचर को Meta Model API key चाहिए — उसके बिना भी सब ऑफ़लाइन चलता है।"))
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(tr("Start", "शुरू करें")) }
                }
            }
        }
    }
}
