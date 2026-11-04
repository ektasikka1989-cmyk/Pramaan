package com.example.arfas

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

private val SetupGreen = Color(0xFF1B8E3E)

/**
 * First-run setup wizard (and from the Home banner / More → Setup), in the style of mobile games' "download
 * resources" screens and phone setup: Welcome → 1 Offline AI models → 2 Health guide → 3 Meta key → All set.
 * Every step can be skipped; the last screen says in plain words what works with what is installed.
 * [startAt] opens the wizard on a given step (the Home banner opens the first unfinished one).
 */
@Composable
fun ArogyaSetupWizard(
    hindi: Boolean, ai: ArogyaVisitAi?, knowledgeReady: Boolean, sampleLoaded: Boolean,
    onImportKnowledge: (Uri) -> Unit, onLoadSample: () -> Unit, onRefreshKnowledge: () -> Unit,
    onDone: () -> Unit, startAt: ArogyaSetupPlan.Step? = null
) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val kbReady by rememberUpdatedState(knowledgeReady)
    // 0 welcome · 1 models · 2 guide · 3 key · 4 all set
    var page by remember { mutableStateOf(when (startAt) { ArogyaSetupPlan.Step.MODELS -> 1; ArogyaSetupPlan.Step.GUIDE -> 2; ArogyaSetupPlan.Step.KEY -> 3; null -> 0 }) }
    var status by remember { mutableStateOf(ArogyaSetupPlan.Status(false, false, false)) }
    suspend fun refresh() {
        val g = withContext(Dispatchers.IO) { (ai?.guideVersion() ?: -1) >= 1 } || kbReady
        status = withContext(Dispatchers.IO) { ArogyaSetup.status(context, g) }
    }
    LaunchedEffect(page, kbReady) { refresh() }

    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
                // Header: step bars (like phone setup), title.
                if (page in 1..3) {
                    Text(tr("Step $page of 3", "कदम $page / 3"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        (1..3).forEach { i -> Box(Modifier.weight(1f).height(5.dp).background(
                            if (i <= page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp))) }
                    }
                    Spacer(Modifier.height(14.dp))
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (page) {
                        0 -> WelcomePage(hindi)
                        1 -> ModelsPage(hindi, status.models) { scope.launch { refresh() } }
                        2 -> GuidePage(hindi, ai, status.guide, onImportKnowledge, onRefreshKnowledge) { scope.launch { refresh() } }
                        3 -> KeyPage(hindi, status.key) { scope.launch { refresh() } }
                        else -> DonePage(hindi, status)
                    }
                }
                Spacer(Modifier.height(10.dp))
                // Footer: Skip (always allowed) and the main button.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    when (page) {
                        0 -> {
                            TextButton(onClick = { ArogyaSetup.markDone(context); if (!sampleLoaded) runCatching { onLoadSample() }; onDone() }) { Text(tr("Skip for now", "अभी छोड़ें")) }
                            Spacer(Modifier.weight(1f))
                            Button(onClick = { page = 1 }) { Text(tr("Start setup", "सेटअप शुरू करें")) }
                        }
                        in 1..3 -> {
                            val done = when (page) { 1 -> status.models; 2 -> status.guide; else -> status.key }
                            if (!done) TextButton(onClick = { page++ }) { Text(tr("Skip", "छोड़ें")) }
                            Spacer(Modifier.weight(1f))
                            Button(onClick = { page++ }, enabled = true) { Text(if (page == 3) tr("Finish →", "पूरा करें →") else tr("Next →", "आगे →")) }
                        }
                        else -> Button(onClick = {
                            ArogyaSetup.markDone(context)
                            if (!sampleLoaded) runCatching { onLoadSample() }
                            onRefreshKnowledge()
                            Log.i("ArogyaDemo", "AROGYA_SETUP done models=${status.models} guide=${status.guide} key=${status.key}")
                            onDone()
                        }, modifier = Modifier.fillMaxWidth()) { Text(tr("Open Pramaan", "Pramaan खोलें")) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepIcon(emoji: String) {
    Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) { Text(emoji, fontSize = 34.sp) }
}

@Composable
private fun DoneBadge(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE6F4EA)), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).background(SetupGreen, CircleShape), contentAlignment = Alignment.Center) { Text("✓", color = Color.White, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(10.dp)); Text(text, color = Color(0xFF0D5C26), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun WelcomePage(hindi: Boolean) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    Spacer(Modifier.height(24.dp))
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Image(painterResource(R.drawable.pramaan_avatar), contentDescription = "Pramaan", modifier = Modifier.size(132.dp))
        Text("Pramaan", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(tr("Set up in 3 quick steps", "3 आसान कदमों में तैयार करें"), style = MaterialTheme.typography.titleMedium)
        Text(tr("Each step can be skipped. Registers, visit forms and danger checks work from the start, even with no internet.",
            "हर कदम छोड़ा जा सकता है। रजिस्टर, विज़िट फ़ॉर्म और खतरे की जाँच शुरू से, बिना इंटरनेट भी चलते हैं।"),
            textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(8.dp))
    listOf(Triple("📦", tr("Offline AI models", "ऑफ़लाइन AI मॉडल"), tr("Meta Llama 3.2 and more · ${ArogyaSetupPlan.gb(ArogyaSetupPlan.MODELS_BYTES)} · AI on the phone, no internet needed", "Meta Llama 3.2 और अन्य · ${ArogyaSetupPlan.gb(ArogyaSetupPlan.MODELS_BYTES)} · बिना इंटरनेट फ़ोन पर AI")),
        Triple("📚", tr("Health guide", "स्वास्थ्य गाइड"), tr("Official ASHA guides · comes with the app", "आधिकारिक आशा गाइड · ऐप के साथ आती है")),
        Triple("🔑", tr("Meta AI key (optional)", "Meta AI key (वैकल्पिक)"), tr("Better answers online with Muse Spark", "Muse Spark से बेहतर ऑनलाइन जवाब")))
        .forEachIndexed { i, (e, t, d) ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(e, fontSize = 26.sp); Spacer(Modifier.width(12.dp))
                    Column { Text("${i + 1}. $t", fontWeight = FontWeight.Bold); Text(d, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
}

@Composable
private fun ModelsPage(hindi: Boolean, installed: Boolean, onChanged: () -> Unit) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val name = ArogyaSetupPlan.MODELS_FILE
    var wifiOnly by remember { mutableStateOf(true) }
    var dl by remember { mutableStateOf(ArogyaSetupDownload.query(context, name)) }
    var speed by remember { mutableStateOf(0L) }
    var installing by remember { mutableStateOf(false) }
    var installMsg by remember { mutableStateOf("") }
    var installPct by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf("") }
    val free = remember { (context.getExternalFilesDir(null) ?: context.filesDir).usableSpace }
    fun install(uri: Uri, fromDownload: Boolean) {
        if (installing) return
        installing = true; error = ""; installMsg = tr("Installing the models…", "मॉडल इंस्टॉल हो रहे हैं…")
        scope.launch {
            try {
                val report = ModelFileManager(context.applicationContext).installOneZipFromUri(uri) { msg, pct -> installMsg = msg; installPct = pct }
                val ok = withContext(Dispatchers.IO) { ArogyaSetup.llamaInstalled(context) }
                Log.i("ArogyaDemo", "AROGYA_SETUP models_install ok=$ok ready=${report.successCount}/${report.totalCount}")
                if (ok) { if (fromDownload) ArogyaSetupDownload.finish(context, name); dl = null }
                else error = report.logs.firstOrNull { it.startsWith("✗") }?.removePrefix("✗ ") ?: tr("The Llama model was not found in this zip.", "इस zip में Llama मॉडल नहीं मिला।")
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                error = tr("Install stopped: ", "इंस्टॉल रुका: ") + (e.message ?: "").take(160)
            } finally { installing = false; onChanged() }
        }
    }
    // Follow the download; when it finishes, install straight away (like a game's resource download).
    LaunchedEffect(Unit) {
        var lastBytes = -1L; var lastT = 0L
        while (true) {
            val s = withContext(Dispatchers.IO) { ArogyaSetupDownload.query(context, name) }
            dl = s
            if (s != null) {
                val now = System.currentTimeMillis()
                if (lastBytes >= 0 && now > lastT && s.done >= lastBytes) speed = ((s.done - lastBytes) * 1000 / (now - lastT)).let { if (speed == 0L) it else (speed * 3 + it) / 4 }
                lastBytes = s.done; lastT = now
                if (s.ok && !installing && !installed) install(Uri.fromFile(ArogyaSetupDownload.file(context, name)), true)
                if (s.failed) error = tr("Download failed: ", "डाउनलोड नहीं हुआ: ") + s.reason
            }
            delay(800)
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) install(u, false) }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StepIcon("📦")
        Text(tr("Offline AI models", "ऑफ़लाइन AI मॉडल"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(tr("Meta Llama 3.2 3B and the other Meta models, so the phone can answer, read photos and fill forms with no internet.",
            "Meta Llama 3.2 3B और दूसरे Meta मॉडल, ताकि फ़ोन बिना इंटरनेट जवाब दे, फ़ोटो पढ़े और फ़ॉर्म भरे।"), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
    }
    if (installed) { DoneBadge(tr("Done — offline AI models are installed", "हो गया — ऑफ़लाइन AI मॉडल इंस्टॉल हैं")); return }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row { Text(tr("Download size", "डाउनलोड"), Modifier.weight(1f)); Text(ArogyaSetupPlan.gb(ArogyaSetupPlan.MODELS_BYTES), fontWeight = FontWeight.Bold) }
            val enough = free >= ArogyaSetupPlan.MODELS_FREE_NEEDED
            Row { Text(tr("Free space on phone", "फ़ोन में खाली जगह"), Modifier.weight(1f))
                Text(ArogyaSetupPlan.gb(free) + if (enough) " ✓" else " ✗", fontWeight = FontWeight.Bold, color = if (enough) SetupGreen else MaterialTheme.colorScheme.error) }
            if (!enough) Text(tr("Needs about ${ArogyaSetupPlan.gb(ArogyaSetupPlan.MODELS_FREE_NEEDED)} free while installing (the zip is deleted afterwards). Free some space, or skip this step.",
                "इंस्टॉल के समय लगभग ${ArogyaSetupPlan.gb(ArogyaSetupPlan.MODELS_FREE_NEEDED)} खाली जगह चाहिए (बाद में zip हट जाती है)। जगह खाली करें या यह कदम छोड़ें।"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            val s = dl
            when {
                installing -> {
                    Text(installMsg.ifBlank { tr("Installing…", "इंस्टॉल हो रहा है…") }, style = MaterialTheme.typography.bodySmall)
                    if (installPct in 1..99) LinearProgressIndicator(progress = { installPct / 100f }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(tr("Keep the app open until this finishes.", "पूरा होने तक ऐप खुला रखें।"), style = MaterialTheme.typography.labelSmall)
                }
                s != null && s.running -> {
                    val total = if (s.total > 0) s.total else ArogyaSetupPlan.MODELS_BYTES
                    LinearProgressIndicator(progress = { (s.done.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(8.dp))
                    Text("${(s.done * 100 / total).coerceIn(0, 100)}% · " + ArogyaSetupPlan.progressLine(s.done, total, speed, hindi), style = MaterialTheme.typography.bodySmall)
                    if (s.reason.isNotBlank()) Text(if (s.waitingForWifi) tr("Waiting for Wi-Fi — connect to Wi-Fi, or untick \"Wi-Fi only\" and start again.", "Wi-Fi का इंतज़ार — Wi-Fi जोड़ें, या \"सिर्फ़ Wi-Fi\" हटाकर फिर शुरू करें।") else s.reason,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    Text(tr("You can leave this screen — the download continues in the background and shows a notification.", "आप यह स्क्रीन छोड़ सकती हैं — डाउनलोड पीछे चलता रहेगा और सूचना दिखेगी।"),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { ArogyaSetupDownload.cancel(context, ArogyaSetupPlan.MODELS_FILE); dl = null; speed = 0 }) { Text(tr("Cancel download", "डाउनलोड रोकें")) }
                }
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(wifiOnly, { wifiOnly = it }); Text(tr("Download on Wi-Fi only (recommended)", "सिर्फ़ Wi-Fi पर डाउनलोड करें (सुझाव)"), style = MaterialTheme.typography.bodyMedium)
                    }
                    Button(onClick = {
                        error = ""
                        runCatching { ArogyaSetupDownload.start(context, ArogyaSetupPlan.MODELS_URL, ArogyaSetupPlan.MODELS_FILE, tr("Pramaan — offline AI models", "Pramaan — ऑफ़लाइन AI मॉडल"), wifiOnly) }
                            .onFailure { error = tr("Could not start the download: ", "डाउनलोड शुरू नहीं हुआ: ") + it.message }
                        speed = 0
                    }, enabled = enough, modifier = Modifier.fillMaxWidth()) { Text(tr("⬇ Download and install", "⬇ डाउनलोड और इंस्टॉल करें")) }
                }
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("Already have the file?", "फ़ाइल पहले से है?"), fontWeight = FontWeight.Bold)
            Text(tr("Choose ${ArogyaSetupPlan.MODELS_FILE} from the phone (for example copied from a laptop).", "फ़ोन से ${ArogyaSetupPlan.MODELS_FILE} चुनें (जैसे लैपटॉप से कॉपी की हुई)।"), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { runCatching { pick.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) } }, enabled = !installing) { Text(tr("📂 Choose file", "📂 फ़ाइल चुनें")) }
        }
    }
}

@Composable
private fun GuidePage(hindi: Boolean, ai: ArogyaVisitAi?, installed: Boolean, onImportKnowledge: (Uri) -> Unit, onRefreshKnowledge: () -> Unit, onChanged: () -> Unit) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val name = ArogyaSetupPlan.GUIDE_FILE
    var phase by remember { mutableStateOf("") }      // "", "bundled", "download", "importing", "failed"
    var dl by remember { mutableStateOf<ArogyaSetupDownload.State?>(null) }
    var error by remember { mutableStateOf("") }
    val nowInstalled by rememberUpdatedState(installed)
    suspend fun waitForGuide(seconds: Int): Boolean {
        repeat(seconds) {
            if (nowInstalled || withContext(Dispatchers.IO) { (ai?.guideVersion() ?: -1) >= 1 }) { onRefreshKnowledge(); onChanged(); return true }
            delay(1000)
        }
        return false
    }
    // 1st choice: the guide that comes inside the app. Online only if that does not install.
    LaunchedEffect(Unit) {
        if (installed || phase.isNotBlank()) return@LaunchedEffect
        phase = "bundled"
        val uri = ArogyaSetup.bundledGuideV2Uri(context)
        if (uri != null) { onImportKnowledge(uri); if (waitForGuide(90)) { phase = ""; return@LaunchedEffect } }
        Log.w("ArogyaDemo", "AROGYA_SETUP guide bundled install did not finish — offering download")
        phase = "failed"
    }
    // Follow an online download of the guide, then install it.
    LaunchedEffect(phase) {
        if (phase != "download") return@LaunchedEffect
        while (true) {
            val s = withContext(Dispatchers.IO) { ArogyaSetupDownload.query(context, name) }; dl = s
            if (s?.ok == true) {
                phase = "importing"
                val u = ArogyaSetupDownload.contentUri(context, name) ?: Uri.fromFile(ArogyaSetupDownload.file(context, name))
                onImportKnowledge(u)
                if (waitForGuide(90)) { phase = ""; ArogyaSetupDownload.finish(context, name) } else { phase = "failed"; error = tr("The downloaded guide did not install.", "डाउनलोड हुई गाइड इंस्टॉल नहीं हुई।") }
                return@LaunchedEffect
            }
            if (s == null || s.failed) { phase = "failed"; error = tr("Download failed", "डाउनलोड नहीं हुआ") + (s?.reason?.let { ": $it" } ?: ""); return@LaunchedEffect }
            delay(700)
        }
    }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StepIcon("📚")
        Text(tr("Health guide", "स्वास्थ्य गाइड"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(tr("The official ASHA guides Pramaan answers from, with the source page for every answer.", "आधिकारिक आशा गाइड जिनसे Pramaan जवाब देता है, हर जवाब के स्रोत पेज के साथ।"),
            textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
    }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = true, onCheckedChange = null)
            Column(Modifier.weight(1f)) {
                Text(tr("ASHA Health Guide (English + Hindi)", "आशा स्वास्थ्य गाइड (अंग्रेज़ी + हिंदी)"), fontWeight = FontWeight.Bold)
                Text(tr("Official MoHFW / NHM guides · ${ArogyaSetupPlan.mb(ArogyaSetupPlan.GUIDE_BYTES)} · comes with the app", "आधिकारिक MoHFW / NHM गाइड · ${ArogyaSetupPlan.mb(ArogyaSetupPlan.GUIDE_BYTES)} · ऐप के साथ"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(if (installed) "✓" else "", color = SetupGreen, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
    }
    when {
        installed -> DoneBadge(tr("Done — the health guide is installed", "हो गया — स्वास्थ्य गाइड इंस्टॉल है"))
        phase == "bundled" || phase == "importing" -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (phase == "bundled") tr("Installing the guide that comes with the app…", "ऐप के साथ आई गाइड इंस्टॉल हो रही है…") else tr("Installing the downloaded guide…", "डाउनलोड हुई गाइड इंस्टॉल हो रही है…"))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        phase == "download" -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val s = dl; val total = s?.total?.takeIf { it > 0 } ?: ArogyaSetupPlan.GUIDE_BYTES
            Text(tr("Downloading the guide…", "गाइड डाउनलोड हो रही है…"))
            LinearProgressIndicator(progress = { ((s?.done ?: 0L).toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text(ArogyaSetupPlan.progressLine(s?.done ?: 0, total, 0, hindi) + (s?.reason?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
        }
        else -> Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("The built-in guide did not install. Download it from the internet instead (${ArogyaSetupPlan.mb(ArogyaSetupPlan.GUIDE_BYTES)}).",
                    "ऐप वाली गाइड इंस्टॉल नहीं हुई। इंटरनेट से डाउनलोड करें (${ArogyaSetupPlan.mb(ArogyaSetupPlan.GUIDE_BYTES)})।"))
                if (error.isNotBlank()) Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Button(onClick = {
                    error = ""
                    runCatching { ArogyaSetupDownload.start(context, ArogyaSetupPlan.GUIDE_URL, name, tr("Pramaan — health guide", "Pramaan — स्वास्थ्य गाइड"), false) }
                        .onSuccess { phase = "download" }.onFailure { error = it.message.orEmpty() }
                }, modifier = Modifier.fillMaxWidth()) { Text(tr("⬇ Download the guide", "⬇ गाइड डाउनलोड करें")) }
                OutlinedButton(onClick = { scope.launch { phase = "bundled"; ArogyaSetup.bundledGuideV2Uri(context)?.let { onImportKnowledge(it) }
                    phase = if (waitForGuide(90)) "" else "failed" } }, modifier = Modifier.fillMaxWidth()) { Text(tr("Try the built-in guide again", "ऐप वाली गाइड फिर आज़माएँ")) }
            }
        }
    }
}

@Composable
private fun KeyPage(hindi: Boolean, saved: Boolean, onChanged: () -> Unit) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    var ok by remember { mutableStateOf(false) }
    var offerSaveAnyway by remember { mutableStateOf(false) }
    fun save(k: String) {
        FixSightRuntimeGraph.get(context).updateGroqKey(k)   // encrypted Meta key + the answer engine updated at once
        onChanged()
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StepIcon("🔑")
        Text(tr("Meta AI key (optional)", "Meta AI key (वैकल्पिक)"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(tr("With a key, Pramaan gives better answers online with Meta Muse Spark, plus online voice (Muse Voice) and drug-kit counting (SAM 3.1). Without it, everything runs on the phone.",
            "Key से Pramaan ऑनलाइन Meta Muse Spark से बेहतर जवाब देता है, साथ में ऑनलाइन आवाज़ (Muse Voice) और दवा किट गिनती (SAM 3.1)। इसके बिना सब फ़ोन पर चलता है।"),
            textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
    }
    if (saved && result.isBlank()) DoneBadge(tr("Done — a Meta key is saved", "हो गया — Meta key सेव है"))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("1. No key yet? Create one free on Meta's site, then copy it.", "1. Key नहीं है? Meta की साइट पर मुफ़्त बनाएँ, फिर कॉपी करें।"), fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ArogyaSetupPlan.KEY_PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                modifier = Modifier.fillMaxWidth()) { Text(tr("🔗 Create a free key at dev.meta.ai", "🔗 dev.meta.ai पर मुफ़्त key बनाएँ")) }
            Text(tr("2. Paste the key here and test it.", "2. Key यहाँ चिपकाएँ और जाँचें।"), fontWeight = FontWeight.Bold)
            OutlinedTextField(key, { key = it.trim(); result = ""; ok = false; offerSaveAnyway = false }, label = { Text(if (saved) tr("Replace saved key", "सेव key बदलें") else tr("Meta API key", "Meta API key")) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) tr("Hide", "छिपाएँ") else tr("Show", "दिखाएँ")) } })
            Button(onClick = {
                val problem = ArogyaSetupPlan.keyProblem(key, hindi)
                if (problem != null) { result = problem; ok = false; return@Button }
                testing = true; result = ""; offerSaveAnyway = false
                scope.launch {
                    val k = key
                    val outcome: Pair<Boolean, String> = withContext(Dispatchers.IO) {
                        if (!MetaOnline.hasInternet(context)) return@withContext false to "offline"
                        try {
                            val api = MetaModelApi(apiKey = { k }, gate = PrivacyGate(File(context.filesDir, "meta_egress.log")))
                            val reply = withTimeoutOrNull(25_000) { api.spark("arogya_setup_key_test", "Reply with one word.", "Say OK.") }
                            if (reply == null) false to "timeout" else true to reply.take(20)
                        } catch (e: CancellationException) { throw e } catch (e: MetaModelApi.ApiException) {
                            false to (if (e.code == 401 || e.code == 403) "rejected" else "http ${e.code}")
                        } catch (e: Exception) { false to "error ${e.message?.take(80)}" }
                    }
                    testing = false
                    when {
                        outcome.first -> { save(k); ok = true; key = ""; result = tr("✓ The key works — Meta Muse Spark answered. Saved.", "✓ Key चल रही है — Meta Muse Spark ने जवाब दिया। सेव हो गई।") }
                        outcome.second == "offline" -> { result = tr("No internet right now, so the key cannot be tested.", "अभी इंटरनेट नहीं, इसलिए key जाँची नहीं जा सकती।"); offerSaveAnyway = true }
                        outcome.second == "rejected" -> result = tr("Meta rejected this key. Copy it again from dev.meta.ai and paste.", "Meta ने यह key नहीं मानी। dev.meta.ai से फिर कॉपी करके चिपकाएँ।")
                        outcome.second == "timeout" -> { result = tr("Meta did not reply in 25 s (weak signal?).", "Meta ने 25 सेकंड में जवाब नहीं दिया (कमज़ोर सिग्नल?)।"); offerSaveAnyway = true }
                        else -> { result = tr("The test did not work: ", "जाँच नहीं हुई: ") + outcome.second; offerSaveAnyway = true }
                    }
                    Log.i("ArogyaDemo", "AROGYA_SETUP key_test ok=${outcome.first} why=${if (outcome.first) "ok" else outcome.second.take(30)}")
                }
            }, enabled = !testing && key.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(if (testing) tr("Testing…", "जाँच रहे हैं…") else tr("Test and save key", "जाँचें और सेव करें")) }
            if (result.isNotBlank()) Text(result, color = if (ok) SetupGreen else MaterialTheme.colorScheme.error, fontWeight = if (ok) FontWeight.Bold else FontWeight.Normal)
            if (offerSaveAnyway) OutlinedButton(onClick = { save(key); key = ""; offerSaveAnyway = false; ok = true; result = tr("Saved. It will be used when there is internet.", "सेव हो गई। इंटरनेट होने पर इस्तेमाल होगी।") }) {
                Text(tr("Save without testing", "बिना जाँचे सेव करें")) }
            Text(tr("🔒 Stored encrypted on this phone only. Names, phone numbers and Aadhaar are removed before anything is sent online.",
                "🔒 सिर्फ़ इसी फ़ोन पर सुरक्षित (एन्क्रिप्टेड)। ऑनलाइन भेजने से पहले नाम, फ़ोन नंबर और आधार हटाए जाते हैं।"),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DonePage(hindi: Boolean, s: ArogyaSetupPlan.Status) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    val sum = ArogyaSetupPlan.summary(s, hindi)
    Spacer(Modifier.height(16.dp))
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(84.dp).background(if (s.complete) SetupGreen else MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
            Text(if (s.complete) "✓" else "${s.doneCount}/3", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
        }
        Text(sum.headline, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(s.models to tr("Offline AI models (Meta Llama 3.2 and more)", "ऑफ़लाइन AI मॉडल (Meta Llama 3.2 और अन्य)"),
                s.guide to tr("ASHA Health Guide", "आशा स्वास्थ्य गाइड"),
                s.key to tr("Meta AI key (Muse Spark, Muse Voice, SAM 3.1)", "Meta AI key (Muse Spark, Muse Voice, SAM 3.1)")).forEach { (done, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(24.dp).background(if (done) SetupGreen else MaterialTheme.colorScheme.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
                        Text(if (done) "✓" else "–", color = if (done) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(10.dp))
                    Text(label + if (done) "" else tr(" — skipped", " — छोड़ा"), color = if (done) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    Text(sum.offline, style = MaterialTheme.typography.bodyMedium)
    Text(sum.online, style = MaterialTheme.typography.bodyMedium)
    if (sum.tip.isNotBlank()) Text(sum.tip, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
