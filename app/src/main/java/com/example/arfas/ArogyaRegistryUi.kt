package com.example.arfas

import android.app.DatePickerDialog
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Small "✨ AI" badge wherever AI is used; tap shows which model powers it (exactly as implemented). */
@Composable
fun AiBadge(feature: String, modifier: Modifier = Modifier, hindi: Boolean? = null) {
    val h = hindi ?: ArogyaLang.uiHindi(LocalContext.current)
    fun tr(en: String, hi: String) = if (h) hi else en
    var open by remember { mutableStateOf(false) }
    val items = ArogyaAiCatalog.features.filter { it.feature.startsWith(feature) }
    AssistChip(onClick = { open = true }, label = { Text("✨ AI", style = MaterialTheme.typography.labelSmall) }, modifier = modifier.height(28.dp))
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text("✨ $feature") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items.forEach { f ->
                Column {
                    Text(if (f.metaModel) "Powered by Meta — ${f.model}" else "Powered by ${f.model}", fontWeight = FontWeight.Bold)
                    Text("${f.maker} · ${f.where}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Text(f.whatAiDoes, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(tr("You check and approve everything the AI suggests.", "AI जो सुझाए, आप देखकर पक्का करती हैं।"), style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text(tr("OK", "ठीक")) } })
}

/** Every AI feature in the app, the model behind it, and the live state of the phone's model. */
@Composable
fun ArogyaAiPage(hindi: Boolean, ai: ArogyaVisitAi?, onOpenLibrary: () -> Unit, onDismiss: () -> Unit) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cloudOpen by rememberSaveable { mutableStateOf(false) }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    if (cloudOpen) ArogyaCloudSettingsDialog(hindi) { cloudOpen = false; refresh++ }
    if (advancedOpen && ai != null) ArogyaAiAdvancedDialog(hindi, ai) { advancedOpen = false }
    // Everything read live: the one setting, signal, loaded phone model, endpoint, keys.
    val cloudFirst = remember(refresh) { ArogyaCloud.cloudFirst(context) }
    val online = remember(refresh) { ArogyaCloud.online(context) }
    val metaKey = remember(refresh) { ArogyaCloud.metaKey(context).isNotBlank() }
    val togetherKey = remember(refresh) { ArogyaCloud.togetherKey(context).isNotBlank() }
    val ep = remember(refresh) { ArogyaCloud.textEndpoint(context) }
    val (llama, llamaReady) = remember(refresh) { ai?.modelInfo() ?: ("" to false) }
    val cloudText = when {
        ep == null -> ""
        ep.keyFor == "together" && !togetherKey -> ""
        else -> ep.label
    }
    val cloudTextKey = cloudText.isNotBlank() && (ep?.let { ArogyaCloud.keyFor(context, it).isNotBlank() } == true)
    val visionOk = remember(refresh) { ai?.visionOk() ?: false }
    val status = AiTableStatus(cloudFirst, online, llama, llamaReady, cloudText, cloudTextKey, metaKey, visionOk)
    val rows = remember(status, hindi) { ArogyaAiTable.rows(status, hindi) }
    var guideLabel by remember { mutableStateOf(tr("Guide …", "गाइड …")) }
    var guideCount by remember { mutableStateOf(-1) }
    LaunchedEffect(refresh) {
        guideLabel = withContext(Dispatchers.IO) { ArogyaGuideDb.describe(ai?.guideVersion() ?: -1) }
        guideCount = withContext(Dispatchers.IO) { ai?.guideLibrary()?.size ?: 0 }
    }
    var shareMsg by remember { mutableStateOf("") }
    val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    AlertDialog(onDismissRequest = onDismiss, title = { Text(tr("What makes ArogyaDoot smart", "ArogyaDoot को समझदार क्या बनाता है")) }, text = {
        Column(Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = {}, label = { Text(if (cloudFirst) "☁ Cloud first" else "📱 Local first") })
                AssistChip(onClick = {}, label = { Text(if (online) tr("✓ Online", "✓ ऑनलाइन") else tr("✗ Offline", "✗ ऑफ़लाइन")) })
                AssistChip(onClick = {}, label = { Text(if (metaKey) tr("Meta key ✓", "Meta key ✓") else tr("Meta key —", "Meta key —")) })
                AssistChip(onClick = {}, label = { Text(guideLabel) })
            }
            if (portrait) rows.forEach { r -> AiRowCard(r, hindi) } else AiTableGrid(rows, hindi)
            // Fixed rules panel (grey): never AI.
            Card(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, Color.Gray),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(tr("⚙ Fixed government rules, not AI", "⚙ तय सरकारी नियम, AI नहीं"), fontWeight = FontWeight.Bold)
                    Text(tr("Danger signs · schedules · vaccine due dates · incentives · next visit date",
                        "खतरे के लक्षण · शेड्यूल · टीकों की तारीख़ें · प्रोत्साहन · अगली विज़िट की तारीख़"),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            // Footer: live guide count + library link, privacy, credit.
            OutlinedButton(onClick = onOpenLibrary, modifier = Modifier.fillMaxWidth()) {
                Text(if (guideCount < 0) tr("📚 Official guides…", "📚 आधिकारिक गाइड…")
                    else tr("📚 $guideCount official guides", "📚 $guideCount आधिकारिक गाइड"))
            }
            Text(tr("Privacy: on this phone nothing leaves it; the cloud only receives text with names removed.",
                "गोपनीयता: इस फ़ोन की जानकारी फ़ोन से बाहर नहीं जाती; क्लाउड को सिर्फ़ बिना नाम का पाठ मिलता है।"),
                style = MaterialTheme.typography.bodySmall)
            Text(tr("Built with Muse Code.", "Muse Code से बनाया गया।"), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    shareMsg = tr("Making the PDF…", "PDF बना रहे हैं…")
                    scope.launch {
                        val ok = ArogyaShare.shareAiTablePdf(context,
                            ArogyaAiTable.pdfText(rows, status, guideCount.coerceAtLeast(0), guideLabel, hindi))
                        shareMsg = if (ok) tr("PDF shared.", "PDF भेजा।") else tr("Could not make the PDF.", "PDF नहीं बना।")
                    }
                }, modifier = Modifier.weight(1f)) { Text(tr("Share as PDF", "PDF भेजें")) }
                OutlinedButton(onClick = { cloudOpen = true }, modifier = Modifier.weight(1f)) { Text(tr("☁ Cloud AI settings", "☁ क्लाउड AI सेटिंग")) }
            }
            if (shareMsg.isNotBlank()) Text(shareMsg, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { advancedOpen = true }) {
                Text(tr("Advanced (testing)", "Advanced (परीक्षण)"), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Close", "बंद करें")) } })
}

private val AI_PHONE_GREEN = Color(0xFF2E7D32)
private val AI_CLOUD_BLUE = Color(0xFF1565C0)

@Composable
private fun AiEngineBox(text: String, ok: Boolean, active: Boolean, cloud: Boolean, hindi: Boolean, modifier: Modifier = Modifier) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    val tint = if (cloud) AI_CLOUD_BLUE else AI_PHONE_GREEN
    Card(modifier, border = BorderStroke(if (active) 2.dp else 1.dp, if (!ok && text != "—") Color.Gray else tint),
        colors = CardDefaults.cardColors(
            containerColor = if (active) tint.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text((if (active) "▶ " else "") + if (cloud) tr("☁ Meta cloud", "☁ Meta क्लाउड") else tr("📱 Phone", "📱 फ़ोन"),
                style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = tint)
            Text(text, style = MaterialTheme.typography.bodySmall,
                color = if (!ok && text != "—") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Portrait: each feature is a card with two side-by-side engine boxes. */
@Composable
private fun AiRowCard(r: AiTableRow, hindi: Boolean) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${r.icon} ${r.feature}", fontWeight = FontWeight.Bold)
        Text(r.does, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AiEngineBox(r.phone, r.phoneOk, r.active == 0, false, hindi, Modifier.weight(1f))
            AiEngineBox(r.cloud, r.cloudOk, r.active == 1, true, hindi, Modifier.weight(1f))
        }
        Text("✓ ${r.safety}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } }
}

/** Landscape: a true table (feature · phone · cloud · safety). */
@Composable
private fun AiTableGrid(rows: List<AiTableRow>, hindi: Boolean) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    Column(Modifier.horizontalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("What it does", "क्या करता है"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(190.dp))
            Text(tr("📱 On the phone (offline)", "📱 फ़ोन पर (ऑफ़लाइन)"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, color = AI_PHONE_GREEN, modifier = Modifier.width(210.dp))
            Text(tr("☁ Meta cloud (online)", "☁ Meta क्लाउड (ऑनलाइन)"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, color = AI_CLOUD_BLUE, modifier = Modifier.width(210.dp))
            Text(tr("Safety check", "सुरक्षा जाँच"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(220.dp))
        }
        HorizontalDivider()
        rows.forEach { r -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.width(190.dp)) {
                Text("${r.icon} ${r.feature}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                Text(r.does, style = MaterialTheme.typography.bodySmall)
            }
            AiEngineBox(r.phone, r.phoneOk, r.active == 0, false, hindi, Modifier.width(210.dp))
            AiEngineBox(r.cloud, r.cloudOk, r.active == 1, true, hindi, Modifier.width(210.dp))
            Text("✓ ${r.safety}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(220.dp))
        } }
    }
}

/** Test tools moved off the AI page: benchmarks, evidence/speed switches (debug builds only). */
@Composable
private fun ArogyaAiAdvancedDialog(hindi: Boolean, ai: ArogyaVisitAi, onDismiss: () -> Unit) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    val context = LocalContext.current
    val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    val benchScope = rememberCoroutineScope()
    var benchLog by remember { mutableStateOf("") }
    var benchBusy by remember { mutableStateOf(false) }
    var spBusy by remember { mutableStateOf(false) }
    var spLog by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(tr("Advanced (testing)", "Advanced (परीक्षण)")) }, text = {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!debuggable) { Text(tr("Test tools need a debug build.", "परीक्षण के लिए debug build चाहिए।")); return@Column }
            OutlinedButton(onClick = {
                if (benchBusy) { ai.cancel(); return@OutlinedButton }
                benchBusy = true; benchLog = ""
                benchScope.launch {
                    try {
                        ArogyaBenchmark.runAll(ai) { line -> benchLog = (benchLog + "\n" + line).takeLast(3000) }
                        benchLog += "\nDone — pull logs with _claude_work\\run\\logs.bat"
                    } catch (e: CancellationException) { benchLog += "\nCancelled" }
                    finally { benchBusy = false }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(if (benchBusy) tr("Stop benchmark", "बेंचमार्क रोकें") else tr("Run M1 benchmark (19 questions, test aid)", "M1 बेंचमार्क चलाएँ (19 सवाल)")) }
            if (benchLog.isNotBlank()) Text(benchLog.trim(), style = MaterialTheme.typography.bodySmall)
            var prof by remember { mutableStateOf(ArogyaFlags.r1Profile) }
            var fuCtx by remember { mutableStateOf(ArogyaFlags.sopFollowUpContext) }
            var salvFirst by remember { mutableStateOf(ArogyaFlags.salvageBeforeRecovery) }
            var b2048 by remember { mutableStateOf(ArogyaFlags.llamaBatch2048) }
            var faOn by remember { mutableStateOf(ArogyaFlags.llamaFlashAttn) }
            Text(tr("Evidence size (next question)", "साक्ष्य आकार (अगला सवाल)"), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0 to "4×900", 1 to "6×1100", 2 to "5×900").forEach { (p, l) ->
                    FilterChip(selected = prof == p, onClick = { prof = p; ArogyaFlags.r1Profile = p }, label = { Text(l) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = fuCtx, onClick = { fuCtx = !fuCtx; ArogyaFlags.sopFollowUpContext = fuCtx }, label = { Text(tr("Follow-up ctx", "फ़ॉलो-अप")) })
                FilterChip(selected = salvFirst, onClick = { salvFirst = !salvFirst; ArogyaFlags.salvageBeforeRecovery = salvFirst }, label = { Text(tr("Salvage 1st", "सैल्वेज पहले")) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = b2048, onClick = { b2048 = !b2048; ArogyaFlags.llamaBatch2048 = b2048 }, label = { Text(tr("Batch 2048*", "बैच 2048*")) })
                FilterChip(selected = faOn, onClick = { faOn = !faOn; ArogyaFlags.llamaFlashAttn = faOn }, label = { Text(tr("FlashAttn*", "फ़्लैश*")) })
            }
            Text(tr("* needs app restart", "* ऐप दोबारा खोलें"), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                if (spBusy) { ai.cancel(); return@OutlinedButton }
                spBusy = true; spLog = ""
                benchScope.launch {
                    try {
                        ArogyaBenchmark.runSpeedCheck(ai) { line -> spLog = (spLog + "\n" + line).takeLast(2000) }
                        spLog += "\nDone — pull logs with _claude_work\\run\\logs.bat"
                    } catch (e: CancellationException) { spLog += "\nCancelled" }
                    finally { spBusy = false }
                }
            }, modifier = Modifier.fillMaxWidth(), enabled = !benchBusy) { Text(if (spBusy) tr("Stop speed check", "स्पीड जाँच रोकें") else tr("Run 3-question speed check (this mode)", "3-सवाल स्पीड जाँच (यही मोड)")) }
            if (spLog.isNotBlank()) Text(spLog.trim(), style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Close", "बंद करें")) } })
}


/** Home: what is due today, in one card. */
@Composable
fun ArogyaTodayCard(hindi: Boolean, due: List<DueItem>, today: LocalDate, onOpen: () -> Unit) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    val overdue = due.count { it.overdue(today) }
    Card(Modifier.fillMaxWidth().clickable { onOpen() }, shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = if (overdue > 0) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("TODAY", "आज"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(if (due.isEmpty()) tr("Nothing due this week", "इस हफ़्ते कुछ बाकी नहीं") else tr("${due.size} visits due this week", "इस हफ़्ते ${due.size} विज़िट बाकी"),
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (overdue > 0) Text(tr("$overdue overdue — go first", "$overdue छूट गई — पहले जाएँ"), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            due.take(3).forEach { Text("• ${it.name.removeSuffix(" (demo)")} — ${it.label.removePrefix("Vaccines — ").substringBefore(" (")}", style = MaterialTheme.typography.bodyMedium, maxLines = 1) }
            if (due.isNotEmpty()) Text(tr("Tap to see the full list ›", "पूरी सूची देखें ›"), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Home card when kit items run low: balances + one-tap refill request to the ANM (number remembered). */
@Composable
fun ArogyaLowStockCard(hindi: Boolean, low: List<ArogyaRegistryLogic.LowItem>, stock: List<StockTxn>, storedItems: List<StockItem>, today: LocalDate) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    val context = LocalContext.current
    var phone by remember { mutableStateOf(ArogyaShare.anmPhone(context)) }
    val hasPhone = phone.count { it.isDigit() } >= 10
    val profile = remember { context.getSharedPreferences("arogya_profile", android.content.Context.MODE_PRIVATE) }
    val text = remember(stock, storedItems, today, hindi) {
        ArogyaAnmTemplate.refillMessage(
            profile.getString("name", "").orEmpty(), profile.getString("village", "").orEmpty(), "",
            ArogyaShare.anmName(context), today.toString(),
            ArogyaRegistryLogic.refillRequest(stock, storedItems, today, hindi), hindi)
    }
    fun send(whatsApp: Boolean) {
        ArogyaShare.setAnmPhone(context, phone)
        val ok = if (whatsApp) ArogyaShare.whatsApp(context, phone, text)
        else hasPhone && ArogyaShare.sms(context, phone, text)
        if (!ok) ArogyaShare.shareText(context, tr("Drug kit refill request", "दवा किट रिफिल अनुरोध"), text)
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("Low stock: ", "कम स्टॉक: ") + low.joinToString(", ") { "${it.name} ${it.balance}" },
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
            if (!hasPhone) OutlinedTextField(phone, { phone = it.filter { c -> c.isDigit() || c == '+' }.take(15) },
                label = { Text(tr("ANM phone (saved once)", "ANM फ़ोन (एक बार)")) }, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { send(true) }, modifier = Modifier.weight(1f)) { Text(tr("Request refill — WhatsApp", "रिफिल माँगें — WhatsApp"), maxLines = 1) }
                OutlinedButton(onClick = { send(false) }, enabled = hasPhone) { Text(tr("SMS", "SMS")) }
            }
        }
    }
}

/** Today's plan: due and overdue visits in order, each with its own Start visit. */
@Composable
fun ArogyaTodayPlan(hindi: Boolean, due: List<DueItem>, today: LocalDate, onStartVisit: (String) -> Unit, onOpenList: () -> Unit) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("Today's plan", "आज की योजना"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            if (due.isEmpty()) Text(tr("Nothing due this week", "इस हफ़्ते कुछ बाकी नहीं"))
            due.take(8).forEach { d ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(d.name, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text((if (d.overdue(today)) tr("Overdue · ", "छूटी · ") else "") + d.label.substringBefore(" (") + " · " + d.due,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (d.overdue(today)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    }
                    Button(onClick = { onStartVisit(d.beneficiaryId) }) { Text(tr("Start visit", "विज़िट शुरू"), maxLines = 1) }
                }
            }
            if (due.size > 8 || due.isNotEmpty()) TextButton(onClick = onOpenList) { Text(tr("Full due list ›", "पूरी सूची ›")) }
        }
    }
}

/** Backup status line: last Google Drive backup + Back up now (or Set up when missing). */
@Composable
fun ArogyaBackupStatusRow(hindi: Boolean, store: ArogyaRegistryStore, onSetup: () -> Unit) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val ready = store.password().isNotBlank() && store.backupUri != null
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(if (store.lastBackup > 0)
                    tr("Last backup to Google Drive: ", "Google Drive पर आखिरी बैकअप: ") +
                        java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", java.util.Locale.getDefault()).format(java.util.Date(store.lastBackup))
                    else tr("Not backed up yet", "अभी बैकअप नहीं हुआ"), style = MaterialTheme.typography.bodySmall)
                if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
            }
            if (busy) CircularProgressIndicator(Modifier.size(24.dp))
            else if (ready) Button(onClick = {
                busy = true
                scope.launch {
                    try { status = store.backupTo(store.backupUri!!, store.password()) }
                    catch (e: CancellationException) { throw e } catch (e: Exception) { status = e.message ?: tr("Failed", "नहीं हुआ") }
                    finally { busy = false }
                }
            }) { Text(tr("Back up now", "अभी बैकअप"), maxLines = 1) }
            else OutlinedButton(onClick = onSetup) { Text(tr("Set up backup", "बैकअप लगाएँ"), maxLines = 1) }
        }
    }
}

/** U7: "Next visits this week" strip — one-line chips, tap opens the full due list. */
@Composable
fun ArogyaNextStrip(hindi: Boolean, due: List<DueItem>, onOpen: () -> Unit) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    val open = due.filter { !it.done }.sortedBy { it.due }.take(7)
    if (open.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(tr("Next visits this week", "इस हफ़्ते अगली विज़िट"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            open.forEach { AssistChip(onClick = onOpen, label = { Text("${it.name.removeSuffix(" (demo)")} — ${it.label} (${it.due})", maxLines = 1) }) }
        }
    }
}

/** U7: first-time welcome, 3 slides, shown once (ArogyaLang.welcomed). */
@Composable
fun ArogyaWelcome(hindi: Boolean, onDone: () -> Unit) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    val slides = listOf(
        tr("ArogyaDoot does the paperwork with you", "ArogyaDoot आपके साथ कागज़ी काम करता है") to
            tr("Speak one visit note — the app fills the official form, MCP card, diary and registers.", "एक विज़िट नोट बोलें — ऐप आधिकारिक फ़ॉर्म, MCP कार्ड, डायरी और रजिस्टर भरता है।"),
        tr("Works offline, speaks your language", "ऑफ़लाइन चलता है, आपकी भाषा बोलता है") to
            tr("No internet needed. Ask the ASHA guides in Hindi or English, with the page for every answer.", "इंटरनेट ज़रूरी नहीं। आशा गाइड से हिन्दी या English में पूछें — हर उत्तर के साथ पेज।"),
        tr("You stay in charge", "फ़ैसला आपका") to
            tr("You approve everything AI suggests. Danger signs and schedules are fixed rules, never AI guesses.", "AI जो सुझाए आप पक्का करती हैं। खतरे के संकेत और शेड्यूल तय नियम हैं, AI अंदाज़ा नहीं।")
    )
    var page by remember { mutableStateOf(0) }
    AlertDialog(onDismissRequest = {}, title = { Text(slides[page].first) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(slides[page].second)
            Text("${page + 1} / ${slides.size}", style = MaterialTheme.typography.labelMedium)
        }
    }, confirmButton = { TextButton(onClick = { if (page < slides.lastIndex) page++ else onDone() }) { Text(if (page < slides.lastIndex) tr("Next ›", "आगे ›") else tr("Start", "शुरू करें")) } },
        dismissButton = { if (page > 0) TextButton(onClick = { page-- }) { Text(tr("‹ Back", "‹ पीछे")) } else TextButton(onClick = onDone) { Text(tr("Skip", "छोड़ें")) } })
}

/** Home: the four things an ASHA does. Big, two words each, Hindi on request. */
@Composable
fun ArogyaHomeGrid(hindi: Boolean, people: Int, onPeople: () -> Unit, onVisit: () -> Unit, onAsk: () -> Unit, onRegisters: () -> Unit,
                   dueToday: Int = 0, referrals: Int = 0, onPractice: () -> Unit = {}, practiceSub: String = "") {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    @Composable fun Tile(icon: String, title: String, sub: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
        Card(modifier.heightIn(min = 118.dp).clickable { onClick() }, shape = MaterialTheme.shapes.extraLarge,
            colors = if (primary) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) else CardDefaults.cardColors()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(icon, style = MaterialTheme.typography.headlineMedium)
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile("📝", tr("New visit", "नई विज़िट"), (if (dueToday > 0) tr("$dueToday due today · ", "आज $dueToday बाकी · ") else "") + tr("speak, AI fills the forms", "बोलें, AI फ़ॉर्म भरे"), true, Modifier.weight(1f), onVisit)
            Tile("🏠", tr("People", "लोग"), tr("$people in your area · MCP cards", "आपके क्षेत्र में $people · MCP कार्ड"), false, Modifier.weight(1f), onPeople)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile("❓", tr("Ask SOP", "SOP पूछें"), tr("Answer with guideline page", "गाइडलाइन पेज के साथ उत्तर"), false, Modifier.weight(1f), onAsk)
            Tile("📒", tr("Registers", "रजिस्टर"), (if (referrals > 0) tr("⚠ $referrals referral${if (referrals == 1) "" else "s"} to follow up · ", "⚠ $referrals रेफ़रल फ़ॉलो-अप · ") else "") + tr("diary, drug kit, monthly claim", "डायरी, दवा किट, मासिक दावा"), false, Modifier.weight(1f), onRegisters)
        }
        Tile("📚", tr("Today's practice", "आज का अभ्यास"), tr("3 questions", "3 सवाल") + (if (practiceSub.isNotBlank()) " · $practiceSub" else ""), false, Modifier.fillMaxWidth(), onPractice)
    }
}

@Composable
private fun DateBox(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    val context = LocalContext.current
    OutlinedButton(onClick = {
        val d = runCatching { LocalDate.parse(value) }.getOrDefault(LocalDate.now())
        DatePickerDialog(context, { _, y, m, day -> onChange(LocalDate.of(y, m + 1, day).toString()) }, d.year, d.monthValue - 1, d.dayOfMonth).show()
    }, modifier = modifier) { Text(label + ": " + value.ifBlank { "—" }, maxLines = 1) }
}

/** Full-screen hub: Due list · Patients · Registers · Backup. */
@Composable
fun ArogyaRegistryHub(hindi: Boolean, initial: String, visits: List<ArogyaVisit>, queue: ArogyaVisitQueue, onClose: () -> Unit, ai: ArogyaVisitAi? = null, onStartVisit: (Beneficiary) -> Unit,
    onRecordForm: ((Beneficiary, String) -> Unit)? = null) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    val context = LocalContext.current
    val store = remember { ArogyaRegistryStore.get(context) }
    val data by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(initial) }
    var openPatient by remember { mutableStateOf<String?>(null) }
    var editPatient by remember { mutableStateOf<Beneficiary?>(null) }
    var addOpen by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    fun toast(m: String) = Toast.makeText(context, m, Toast.LENGTH_LONG).show()
    fun save(change: (RegistryData) -> RegistryData) = runCatching { store.update(change) }.onFailure { toast(it.message ?: "Could not save") }.isSuccess

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClose) { Text(tr("‹ Home", "‹ होम")) }
                    Text(when (tab) { "due" -> tr("Today's visits", "आज की विज़िट"); "patients" -> tr("People", "लोग"); "registers" -> tr("Registers", "रजिस्टर"); else -> tr("Backup", "बैकअप") },
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                }
                if (!store.loaded) { LinearProgressIndicator(Modifier.fillMaxWidth()); if (store.error.isNotBlank()) Text(store.error, color = MaterialTheme.colorScheme.error) }
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        "due" -> DueTab(hindi, data, visits, today, store) { openPatient = it }
                        "patients" -> PatientsTab(hindi, data, visits, today, onOpen = { openPatient = it }, onAdd = { addOpen = true })
                        "registers" -> RegistersTab(hindi, data, visits, store, today, onOpen = { openPatient = it }, ai = ai) { c -> save(c) }
                        else -> BackupTab(hindi, store, queue)
                    }
                }
            }
        }
    }
    openPatient?.let { id -> data.beneficiaries.firstOrNull { it.id == id }?.let { b ->
        McpCardDialog(hindi, b, data, visits, today, ai, onClose = { openPatient = null }, onEdit = { editPatient = b },
            onStartVisit = { openPatient = null; onStartVisit(b) }, save = { c -> save(c) },
            onRecordForm = if (onRecordForm == null) null else { fid -> openPatient = null; onRecordForm(b, fid) })
    } }
    editPatient?.let { b -> PatientForm(hindi, b, onDismiss = { editPatient = null }) { updated ->
        if (save { r -> if (r.beneficiaries.any { it.id == updated.id }) r.copy(beneficiaries = r.beneficiaries.map { if (it.id == updated.id) updated.copy(updatedAt = System.currentTimeMillis()) else it })
                else r.copy(beneficiaries = r.beneficiaries + updated) }) { editPatient = null; openPatient = updated.id }
    } }
    if (addOpen) AddPersonSheet(hindi, ai, data, visits, { c -> save(c) }, onClose = { addOpen = false })
}

@Composable
private fun DueTab(hindi: Boolean, data: RegistryData, visits: List<ArogyaVisit>, today: LocalDate, store: ArogyaRegistryStore, onOpen: (String) -> Unit) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    var days by remember { mutableStateOf(7L) }
    val due = remember(data, visits, days) { ArogyaSchedule.dueList(data.beneficiaries, visits, today, days) }
    val table = remember(due) { ArogyaRegisterTables.dueTable(due, today, hindi) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0L to tr("Today", "आज"), 7L to tr("This week", "इस हफ़्ते"), 30L to tr("This month", "इस महीने")).forEach { (d, l) -> FilterChip(selected = days == d, onClick = { days = d }, label = { Text(l) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { FixedRuleChip(hindi); Text(tr("Fixed government schedules", "तय सरकारी सूची"), style = MaterialTheme.typography.bodySmall) }
        if (due.isEmpty()) Text(tr("Nothing due. Add patients with dates of birth or LMP to build the schedule.", "कुछ बाकी नहीं। जन्मतिथि या LMP के साथ लोग जोड़ें ताकि सूची बने।"), Modifier.padding(8.dp))
        else ArogyaRegisterTable(hindi, tr("Due visits", "बाकी विज़िट"), table, store, statusCol = 4, villageCol = 1, statusFilter = true,
            onOpenPerson = onOpen, colWidths = listOf(120.dp, 84.dp, 190.dp, 130.dp, 96.dp), maxHeight = 520.dp,
            note = tr("Tap a row to open the person, then Start visit. Overdue rows come first.", "पंक्ति दबाकर व्यक्ति खोलें, फिर विज़िट शुरू करें। छूटी पंक्तियाँ पहले।"), sample = data.beneficiaries.any { it.demo })
    }
}

@Composable
private fun PatientsTab(hindi: Boolean, data: RegistryData, visits: List<ArogyaVisit>, today: LocalDate, onOpen: (String) -> Unit, onAdd: () -> Unit) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    var query by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("All") }
    var byHouse by remember { mutableStateOf(true) }
    val groups = listOf("All" to tr("All", "सभी"), "Pregnant women" to tr("Pregnant women", "गर्भवती महिलाएँ"), "Infants (0–1 year)" to tr("Infants (0–1 year)", "शिशु (0–1 साल)"), "Children (1–6 years)" to tr("Children (1–6 years)", "बच्चे (1–6 साल)"), "Eligible couples" to tr("Eligible couples", "योग्य दंपति"), "High risk" to tr("High risk", "जोखिम वाले"))
    fun trGroup(b: Beneficiary): String { val g = ArogyaRegistryLogic.rchGroup(b, today); return groups.firstOrNull { it.first == g }?.second ?: g }
    val shown = remember(data, query, group) {
        data.beneficiaries.filter { it.active }.filter { b -> query.isBlank() || listOf(b.name, b.relativeName, b.household, b.village, b.rchId, b.phone).any { it.contains(query, true) } }
            .filter { b -> group == "All" || (group == "High risk" && ArogyaRegistryLogic.isHighRisk(b)) || ArogyaRegistryLogic.rchGroup(b, today) == group }
            .sortedBy { it.name.lowercase() }
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it }, placeholder = { Text(tr("Search", "खोजें")) }, singleLine = true, modifier = Modifier.weight(1f))
            Button(onClick = onAdd, modifier = Modifier.padding(start = 6.dp)) { Text(tr("+ Add", "+ जोड़ें")) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            groups.forEach { (k, l) -> FilterChip(selected = group == k, onClick = { group = k }, label = { Text(l) }) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${shown.size} / ${data.beneficiaries.count { it.active }} " + tr("people", "लोग"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { byHouse = !byHouse }) { Text(if (byHouse) tr("Show as list", "सूची में देखें") else tr("Show by household", "परिवार से देखें")) }
        }
        if (data.beneficiaries.isEmpty()) Text(tr("No people yet. Tap + Add for each family member from your household survey — or go back to Home and tap “Load sample village (50 people)” to try the app with fictional data.", "अभी कोई नहीं। घर-सर्वे के हर सदस्य के लिए + जोड़ें दबाएँ — या होम जाकर “नमूना गाँव लोड करें (50 लोग)” से काल्पनिक डेटा पर आज़माएँ।"), Modifier.padding(8.dp))
        val rows: List<Pair<String?, Beneficiary>> = if (!byHouse) shown.map { null to it } else ArogyaRegistryLogic.byHousehold(shown).entries.sortedBy { it.key }.flatMap { (h, l) -> l.mapIndexed { i, b -> (if (i == 0) h else null) to b } }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(rows, key = { it.second.id }) { (header, b) ->
                if (header != null) Text("🏠 $header", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                Card(Modifier.fillMaxWidth().clickable { onOpen(b.id) }) {
                    Column(Modifier.padding(10.dp)) {
                        Text(b.name + if (ArogyaRegistryLogic.isHighRisk(b)) tr("  ⚠ high risk", "  ⚠ जोखिम") else "", fontWeight = FontWeight.Bold)
                        Text(trGroup(b) + " · " + (if (b.household.isNotBlank()) "${b.household} · " else "") + b.village + (ArogyaRegistryLogic.edd(b)?.let { " · " + tr("EDD ", "प्रसव तिथि ") + it } ?: "") + (if (b.dob.isNotBlank()) " · " + tr("DOB ", "जन्म ") + b.dob else ""),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** Text field with a mic button: dictation follows the Offline/Online setting. */
@Composable
internal fun MicField(value: String, onValue: (String) -> Unit, label: String, dictate: (((String) -> Unit)) -> Unit,
    singleLine: Boolean = true, modifier: Modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(value, onValue, label = { Text(label) }, singleLine = singleLine, modifier = modifier,
        trailingIcon = { IconButton(onClick = { dictate { t -> onValue(t.take(200)) } }) { Text("🎤") } })
}

/** Shared editable person fields for add, edit and the import review screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PatientFormFields(hindi: Boolean, b: Beneficiary, weight: String, onWeight: (String) -> Unit,
    onChange: (Beneficiary) -> Unit, villages: List<String>, dictate: (((String) -> Unit)) -> Unit) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    var villageMenu by remember { mutableStateOf(false) }
    var villageNew by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(tr("Register as", "दर्ज करें"))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BenCategory.values().forEach { c -> FilterChip(selected = b.category == c, onClick = { onChange(b.copy(category = c, sex = if (c == BenCategory.CHILD) b.sex else "F")) }, label = { Text(if (hindi) c.titleHi else c.title) }) }
        }
        MicField(b.name, { onChange(b.copy(name = it.take(80))) }, tr("Name *", "नाम *"), dictate)
        MicField(b.relativeName, { onChange(b.copy(relativeName = it.take(80))) }, tr("Husband / mother / father", "पति / माँ / पिता"), dictate)
        MicField(b.household, { onChange(b.copy(household = it.take(60))) }, tr("Household (house no. / family head) — from line listing", "परिवार (मकान नं. / मुखिया) — सूची से"), dictate)
        if (villages.isNotEmpty() && !villageNew) {
            ExposedDropdownMenuBox(expanded = villageMenu, onExpandedChange = { villageMenu = it }) {
                OutlinedTextField(b.village, {}, readOnly = true, label = { Text(tr("Village / hamlet", "गाँव / टोला")) }, singleLine = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(villageMenu) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                ExposedDropdownMenu(villageMenu, { villageMenu = false }) {
                    (villages + tr("＋ New village", "＋ नया गाँव")).forEach { v ->
                        DropdownMenuItem({ Text(v) }, {
                            if (v.startsWith("＋")) villageNew = true else onChange(b.copy(village = v))
                            villageMenu = false
                        })
                    }
                }
            }
        } else MicField(b.village, { onChange(b.copy(village = it.take(80))) }, tr("Village / hamlet", "गाँव / टोला"), dictate)
        if (b.category != BenCategory.CHILD) OutlinedTextField(b.age, { onChange(b.copy(age = it.filter { c -> c.isDigit() }.take(3))) }, label = { Text(tr("Age (years)", "उम्र (साल)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        MicField(b.phone, { onChange(b.copy(phone = it.filter { c -> c.isDigit() || c == '+' }.take(15))) }, tr("Phone", "फ़ोन"), dictate)
        MicField(b.rchId, { onChange(b.copy(rchId = it.take(30))) }, tr("RCH ID (if any)", "RCH ID (हो तो)"), dictate)
        if (b.category == BenCategory.CHILD) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("F" to tr("Girl", "लड़की"), "M" to tr("Boy", "लड़का")).forEach { (s, l) -> FilterChip(selected = b.sex == s, onClick = { onChange(b.copy(sex = s)) }, label = { Text(l) }) } }
            DateBox(tr("Date of birth *", "जन्मतिथि *"), b.dob, Modifier.fillMaxWidth()) { onChange(b.copy(dob = it)) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("INSTITUTION" to tr("Hospital birth", "अस्पताल में जन्म"), "HOME" to tr("Home birth", "घर पर जन्म")).forEach { (p, l) -> FilterChip(selected = b.deliveryPlace == p, onClick = { onChange(b.copy(deliveryPlace = p)) }, label = { Text(l) }) } }
            OutlinedTextField(weight, { onWeight(it.filter { c -> c.isDigit() || c == '.' }.take(5)) }, label = { Text(tr("Birth weight (kg)", "जन्म वज़न (किलो)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(b.preterm, { onChange(b.copy(preterm = it)) }); Text(tr("Born preterm", "समय से पहले जन्म")) }
            DateBox(tr("SNCU/NBSU discharge (if admitted)", "SNCU/NBSU छुट्टी (भर्ती हुए तो)"), b.sncuDischarge, Modifier.fillMaxWidth()) { onChange(b.copy(sncuDischarge = it)) }
            if (b.sncuDischarge.isNotBlank()) TextButton(onClick = { onChange(b.copy(sncuDischarge = "")) }) { Text(tr("Clear discharge date", "छुट्टी तारीख़ हटाएँ")) }
        }
        if (b.category == BenCategory.PREGNANT) {
            DateBox(tr("LMP *", "अंतिम माहवारी *"), b.lmp, Modifier.fillMaxWidth()) { onChange(b.copy(lmp = it)) }
            ArogyaRegistryLogic.edd(b)?.let { Text(tr("EDD: ", "प्रसव तिथि: ") + it) }
            MicField(b.ancNumber, { onChange(b.copy(ancNumber = it.take(10))) }, tr("ANC number", "ANC नंबर"), dictate)
            DateBox(tr("Delivery date (after birth)", "प्रसव तारीख़ (जन्म के बाद)"), b.deliveryDate, Modifier.fillMaxWidth()) { onChange(b.copy(deliveryDate = it)) }
        }
        if (b.category == BenCategory.ELIGIBLE_COUPLE || b.category == BenCategory.MOTHER)
            MicField(b.familyPlanning, { onChange(b.copy(familyPlanning = it.take(60))) }, tr("Family planning method", "परिवार नियोजन साधन"), dictate)
        MicField(b.notes, { onChange(b.copy(notes = it.take(1000))) }, tr("Notes", "नोट"), dictate, singleLine = false)
    }
}

@Composable
private fun PatientForm(hindi: Boolean, initial: Beneficiary, onDismiss: () -> Unit, onSave: (Beneficiary) -> Unit) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    val context = LocalContext.current
    var b by remember { mutableStateOf(initial) }
    var weight by remember { mutableStateOf(initial.birthWeightKg?.toString().orEmpty()) }
    var dictateTarget by remember { mutableStateOf<((String) -> Unit)?>(null) }
    if (dictateTarget != null) ArogyaDictationDialog(onDismiss = { dictateTarget = null },
        cloudVoice = { s, l -> ArogyaCloud.transcribe(context, s, l) },
        cloudFirst = ArogyaCloud.cloudFirst(context), hindi = hindi,
        onAccept = { dictateTarget?.invoke(it); dictateTarget = null })
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (initial.name.isBlank()) tr("Add patient", "रोगी जोड़ें") else tr("Edit patient", "रोगी सुधारें")) }, text = {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("Use fictional names in demos.", "डेमो में काल्पनिक नाम लिखें।"), style = MaterialTheme.typography.bodySmall)
            PatientFormFields(hindi, b, weight, { weight = it }, { b = it }, emptyList()) { dictateTarget = it }
        }
    }, confirmButton = {
        val ok = b.name.isNotBlank() && (b.category != BenCategory.CHILD || b.dob.isNotBlank()) && (b.category != BenCategory.PREGNANT || b.lmp.isNotBlank())
        Button(onClick = { onSave(b.copy(birthWeightKg = weight.toDoubleOrNull()?.takeIf { it in 0.3..8.0 })) }, enabled = ok) { Text(tr("Save", "सहेजें")) }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "रद्द करें")) } })
}


/** One filled visit form: mini preview (tap = full-screen viewer) + PDF share. */
@Composable
private fun FilledFormRow(hindi: Boolean, t: FormTable, origins: ((rowLabel: String, col: Int) -> OriginInfo?)? = null, onOpen: (FormTable) -> Unit) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var formBusy by remember { mutableStateOf(false) }
    var cellOrigin by remember { mutableStateOf<OriginInfo?>(null) }
    Box(Modifier.clickable { onOpen(t) }) {
        OfficialFormGrid(t, null, hindi = hindi, onCell = origins?.let { lookup -> { label: String, col: Int -> cellOrigin = lookup(label, col) } })
    }
    cellOrigin?.let { OriginDialog(it, hindi, { cellOrigin = null }) }
    OutlinedButton(onClick = {
        formBusy = true
        scope.launch {
            try {
                if (!ArogyaRegistryStore.get(context).shareFormPdf(t)) Toast.makeText(context, tr("Could not create the PDF", "PDF नहीं बना"), Toast.LENGTH_LONG).show()
            } finally {
                formBusy = false
            }
        }
    }, enabled = !formBusy) { Text(if (formBusy) tr("Making PDF…", "PDF बन रही है…") else tr("Share this form (PDF)", "यह फ़ॉर्म भेजें (PDF)")) }
}

@Composable
private fun McpCardDialog(hindi: Boolean, b: Beneficiary, data: RegistryData, visits: List<ArogyaVisit>, today: LocalDate, ai: ArogyaVisitAi?, onClose: () -> Unit, onEdit: () -> Unit,
                          onStartVisit: () -> Unit, save: ((RegistryData) -> RegistryData) -> Boolean,
                          onRecordForm: ((String) -> Unit)? = null) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    fun updateBen(f: (Beneficiary) -> Beneficiary) = save { r -> r.copy(beneficiaries = r.beneficiaries.map { if (it.id == b.id) f(it).copy(updatedAt = System.currentTimeMillis()) else it }) }
    var giveOpen by remember { mutableStateOf(false) }
    var changedSummary by remember { mutableStateOf("") }
    var eventOpen by remember { mutableStateOf(false) }
    var viewForm by remember { mutableStateOf<FormTable?>(null) }
    var kg by remember { mutableStateOf("") }
    val schedule = remember(b, visits) { ArogyaSchedule.forBeneficiary(b, visits, today) }
    val context = LocalContext.current
    var updateMode by remember { mutableStateOf(false) }
    var ancEdit by remember { mutableStateOf<Int?>(null) }
    val official = remember(b, data, visits) { ArogyaForms.mcpCard(b, data.beneficiaries, visits, today) }
    // Read the paper MCP card: photo/PDF → ML Kit text (Google) → fields (Llama 3.2, Meta) → ASHA ticks → digital card.
    val scope = rememberCoroutineScope()
    var readBusy by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf<ArogyaCardReading?>(null) }
    var readModel by remember { mutableStateOf("") }
    var readMs by remember { mutableStateOf(0L) }
    var picked by remember { mutableStateOf(setOf<Int>()) }
    var shotUri by remember { mutableStateOf<Uri?>(null) }
    fun readUri(uri: Uri, temp: Boolean) {
        val engine = ai ?: return
        readBusy = true
        scope.launch {
            try { val t = System.currentTimeMillis(); val (r, by) = engine.readCardDetailed(uri); reading = r; readModel = by; readMs = System.currentTimeMillis() - t; picked = r.fields.indices.toSet() }
            catch (e: CancellationException) { throw e } catch (e: Exception) { Toast.makeText(context, e.message ?: "Could not read the card", Toast.LENGTH_LONG).show() }
            finally { readBusy = false; if (temp) runCatching { context.contentResolver.delete(uri, null, null) } }
        }
    }
    val shoot = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> val u = shotUri; shotUri = null; if (u != null) { if (ok) readUri(u, true) else runCatching { context.contentResolver.delete(u, null, null) } } }
    val camPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { g ->
        if (g) { val u = runCatching { context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "mcp_${System.currentTimeMillis()}.jpg"); put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg") }) }.getOrNull()
            if (u != null) { shotUri = u; runCatching { shoot.launch(u) } } }
    }
    val pickCard = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) readUri(u, false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(8.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(14.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("MCP card · ", "MCP कार्ड · ") + b.name, style = MaterialTheme.typography.titleLarge)
                Text(listOf(ArogyaRegistryLogic.rchGroup(b, today), b.village, b.relativeName.takeIf { it.isNotBlank() }?.let { "c/o $it" }, b.rchId.takeIf { it.isNotBlank() }?.let { "RCH $it" })
                    .filterNotNull().filter { it.isNotBlank() }.joinToString(" · "))
                if (ArogyaRegistryLogic.isHighRisk(b)) Text(tr("⚠ High risk (LBW / preterm / SNCU) — extra follow-up visits", "⚠ जोखिम (कम वज़न / समयपूर्व / SNCU) — ज़्यादा फ़ॉलो-अप"), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                if (b.dob.isNotBlank()) Text(tr("DOB ", "जन्म ") + b.dob + (b.birthWeightKg?.let { " · " + tr("birth weight ", "जन्म वज़न ") + "$it kg" } ?: "") + (if (b.deliveryPlace.isNotBlank()) " · ${b.deliveryPlace.lowercase()} " + tr("birth", "जन्म") else ""))
                ArogyaRegistryLogic.edd(b)?.let { Text(tr("LMP ", "अंतिम माहवारी ") + b.lmp + " · " + tr("EDD ", "प्रसव तिथि ") + it, fontWeight = FontWeight.Bold) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onStartVisit, modifier = Modifier.weight(1f)) { Text(tr("Start visit", "विज़िट शुरू करें")) }
                    OutlinedButton(onClick = onEdit) { Text(tr("Edit", "सुधारें")) }
                }
                if (ai != null) Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Text(tr("📷 Read the paper MCP card", "📷 कागज़ का MCP कार्ड पढ़ें"), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); AiBadge("Read MCP card photo") }
                        Text(tr("Photograph the family's card; dates, vaccines and weights are filled here after you tick them.", "परिवार के कार्ड की फ़ोटो लें; तारीख़, टीके और वज़न निशान के बाद यहाँ भरेंगे।"), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { camPerm.launch(android.Manifest.permission.CAMERA) }, enabled = !readBusy) { Text(tr("Take photo", "फ़ोटो लें")) }
                            OutlinedButton(onClick = { pickCard.launch(arrayOf("image/*", "application/pdf")) }, enabled = !readBusy) { Text(tr("Choose file", "फ़ाइल चुनें")) }
                        }
                        if (readBusy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(tr("Reading the card on this phone…", "इसी फ़ोन पर कार्ड पढ़ रहे हैं…"), style = MaterialTheme.typography.bodySmall) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = !updateMode, onClick = { updateMode = false }, label = { Text(tr("MCP card", "MCP कार्ड")) })
                    FilterChip(selected = updateMode, onClick = { updateMode = true }, label = { Text(tr("Update (tick what was done)", "अपडेट (जो किया निशान लगाएँ)")) })
                }
                if (!updateMode) {
                    Text(tr("Layout of the official Mother and Child Protection Card (MoHFW/MWCD 2018). Values are this register's data.", "आधिकारिक MCP कार्ड का खाका (MoHFW/MWCD 2018)। मान इस रजिस्टर के हैं।"), style = MaterialTheme.typography.bodySmall)
                    official.forEach { (title, lines) ->
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp)) {
                            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                            lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.contains("missed") || it.endsWith("YES")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
                        } }
                    }
                    val mcpOrigins = remember(b, data) { ArogyaOrigin.mcp(b, data.forms, data.stock, hindi) }
                    var mcpHistoryOpen by remember { mutableStateOf(false) }
                    var mcpOrigin by remember { mutableStateOf<OriginInfo?>(null) }
                    OutlinedButton(onClick = { mcpHistoryOpen = !mcpHistoryOpen }, modifier = Modifier.fillMaxWidth()) {
                        Text((if (mcpHistoryOpen) "▾ " else "▸ ") + tr("Where card values came from", "कार्ड के मान कहाँ से आए"))
                    }
                    if (mcpHistoryOpen) {
                        if (mcpOrigins.isEmpty()) Text(tr("No weights, vaccines, ANC or IFA recorded yet.", "अभी वज़न, टीके, ANC या IFA दर्ज नहीं।"), style = MaterialTheme.typography.bodySmall)
                        mcpOrigins.forEach { o ->
                            Text(o.label, Modifier.fillMaxWidth().clickable { mcpOrigin = o.info }.padding(6.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    mcpOrigin?.let { OriginDialog(it, hindi, { mcpOrigin = null }) }
                    var mcpBusy by remember { mutableStateOf(false) }
                    var mcpDoc by remember { mutableStateOf<ArogyaDocRender.FilledDoc?>(null) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { mcpDoc = ArogyaRegistryStore.get(context).mcpDoc(b, data.beneficiaries, today) }, modifier = Modifier.weight(1f)) { Text(tr("View printable card", "छपने वाला कार्ड देखें")) }
                        OutlinedButton(onClick = {
                            mcpBusy = true
                            scope.launch {
                                try {
                                    if (!ArogyaRegistryStore.get(context).shareMcpPdf(b, data.beneficiaries, today)) Toast.makeText(context, tr("Could not create the PDF", "PDF नहीं बना"), Toast.LENGTH_LONG).show()
                                } finally {
                                    mcpBusy = false
                                }
                            }
                        }, enabled = !mcpBusy, modifier = Modifier.weight(1f)) { Text(if (mcpBusy) tr("Making PDF…", "PDF बन रही है…") else tr("Share MCP card (PDF)", "MCP कार्ड भेजें (PDF)")) }
                    }
                    mcpDoc?.let { d ->
                        var shareBusy by remember { mutableStateOf(false) }
                        ArogyaDocPdf.Viewer(d, tr("MCP card — ", "MCP कार्ड — ") + b.name, shareBusy, hindi, { mcpDoc = null },
                            onSharePdf = {
                                shareBusy = true
                                scope.launch {
                                    try {
                                        if (!ArogyaRegistryStore.get(context).shareMcpPdf(b, data.beneficiaries, today)) Toast.makeText(context, tr("Could not create the PDF", "PDF नहीं बना"), Toast.LENGTH_LONG).show()
                                    } finally {
                                        shareBusy = false
                                    }
                                }
                            },
                            onPrint = {
                                shareBusy = true
                                scope.launch {
                                    try {
                                        val bytes = ArogyaRegistryStore.get(context).renderDoc(d)
                                        if (bytes == null || !ArogyaPrint.printPdf(context, "MCP card", bytes)) Toast.makeText(context, tr("Print failed", "छपाई नहीं हुई"), Toast.LENGTH_LONG).show()
                                    } finally {
                                        shareBusy = false
                                    }
                                }
                            })
                    }
                    val filled = ArogyaOfficialForms.ALL.filter { sp -> data.forms.any { it.beneficiaryId == b.id && it.formId == sp.id } }
                    filled.forEach { sp ->
                        Text(tr("Visit form — filled over visits (tap a value for its history, elsewhere to open full-screen)", "विज़िट फ़ॉर्म — विज़िटों में भरा (इतिहास के लिए मान दबाएँ, पूरी स्क्रीन के लिए कहीं और)"), fontWeight = FontWeight.Bold)
                        FilledFormRow(hindi, ArogyaOfficialForms.table(sp, b, data.forms), origins = { label, col ->
                            val entry = data.forms.filter { it.beneficiaryId == b.id && it.formId == sp.id && it.column == col }.maxByOrNull { it.date }
                            if (entry == null) null else {
                                val base = label.substringBefore(" (")
                                val field = sp.fields.firstOrNull { it.label == base || it.label == label || it.hi == base }
                                if (field != null) ArogyaOrigin.formCell(field.label, entry.values[field.id].orEmpty(), entry.sources[field.id].orEmpty(), entry.date, b.demo, hindi)
                                else ArogyaOrigin.formCell(label, "", "visit", entry.date, b.demo, hindi)
                            }
                        }) { viewForm = it }
                    }
                    // Record an MCP card section (ANM's entries copied from the paper card) through the guided visit.
                    if (onRecordForm != null) {
                        val recs = when (b.category) {
                            BenCategory.MOTHER -> listOf("mcp_pnc" to tr("＋ Record PNC (ANM)", "＋ PNC दर्ज करें (ANM)"))
                            BenCategory.CHILD -> listOf("mcp_baby" to tr("＋ Record baby care (ANM)", "＋ बच्चे की देखभाल (ANM)"),
                                "mcp_imm" to tr("＋ Record immunization", "＋ टीकाकरण दर्ज करें"),
                                "mcp_hbyc" to tr("＋ Record HBYC (card)", "＋ HBYC दर्ज करें (कार्ड)"),
                                "mcp_ifa" to tr("＋ Record IFA/deworming", "＋ IFA/कीड़े की दवा"))
                            else -> emptyList()
                        }
                        if (recs.isNotEmpty()) {
                            Text(tr("MCP card sections (MCP p.7–8, 27, 36–37)", "MCP कार्ड खंड (MCP पृ. 7–8, 27, 36–37)"), fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                recs.forEach { (fid, label) -> OutlinedButton(onClick = { onRecordForm(fid) }) { Text(label, maxLines = 1) } }
                            }
                        }
                    }
                    if (filled.none { it.id == "annex9" } && b.category == BenCategory.CHILD && b.dob.isNotBlank()) OutlinedButton(onClick = {
                        scope.launch {
                            ArogyaRegistryStore.get(context).shareFormPdf(ArogyaForms.homeVisitForm(b, visits))
                        }
                    }) { Text(tr("Share Home Visit Form (Annexure 9, PDF)", "होम विज़िट फ़ॉर्म भेजें (Annexure 9, PDF)")) }
                }
                if (updateMode) {
                Text(tr("Schedule", "सूची"), fontWeight = FontWeight.Bold)
                if (schedule.isEmpty()) Text(tr("Add date of birth (child) or LMP (pregnant woman) to see the schedule.", "सूची के लिए जन्मतिथि (बच्चा) या LMP (गर्भवती) जोड़ें।"), style = MaterialTheme.typography.bodySmall)
                schedule.filter { it.kind != "VACCINE" }.forEach { s ->
                    Text((if (s.done) "✓ " else if (s.overdue(today)) "✗ " else "○ ") + "${s.label} — ${s.due}" + (if (s.done) " (" + tr("done ", "हो गया ") + s.doneDate + ")" else if (s.overdue(today)) tr(" MISSED", " छूटा") else ""),
                        color = if (s.overdue(today)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                }
                if (b.category == BenCategory.PREGNANT) {
                    Text(tr("ANC check-ups (tick when done today)", "ANC जाँच (आज की तो निशान लगाएँ)"), fontWeight = FontWeight.Bold)
                    (1..5).forEach { n -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(b.mcp.anc[n] != null, { c -> if (updateBen { x -> x.copy(mcp = x.mcp.copy(anc = if (c) x.mcp.anc + (n to today.toString()) else x.mcp.anc - n)) } && c) ancEdit = n })
                        Text("ANC $n" + (b.mcp.anc[n]?.let { " — $it" } ?: "") + (b.mcp.ancDetails[n]?.let { " · ${it.weightKg} kg · " + tr("BP ", "रक्तचाप ") + it.bp + " · Hb " + it.hb } ?: ""), modifier = Modifier.weight(1f))
                        if (b.mcp.anc[n] != null) TextButton(onClick = { ancEdit = n }) { Text(tr("Details", "विवरण")) }
                    } }
                    AncTrendLine(hindi, b.mcp.ancDetails)
                }
                if (b.category == BenCategory.CHILD) {
                    Text(tr("Vaccines (tick when given today)", "टीके (आज लगाए तो निशान लगाएँ)"), fontWeight = FontWeight.Bold)
                    ArogyaSchedule.VACCINE_CONTACTS.forEach { c ->
                        val dob = runCatching { LocalDate.parse(b.dob) }.getOrNull()
                        Text(c.label.substringBefore(" (") + (dob?.let { " · due ${c.due(it)}" } ?: ""), style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            c.codes.forEach { code ->
                                val given = b.mcp.vaccines[code]
                                FilterChip(selected = given != null, onClick = { updateBen { x -> x.copy(mcp = x.mcp.copy(vaccines = if (given != null) x.mcp.vaccines - code else x.mcp.vaccines + (code to today.toString()))) } },
                                    label = { Text(ArogyaSchedule.VACCINE_NAMES[code] ?: code) }, modifier = Modifier.padding(end = 4.dp))
                            }
                        }
                    }
                    Text(tr("Weight", "वज़न"), fontWeight = FontWeight.Bold)
                    b.mcp.weights.sortedBy { it.date }.forEach { Text("${it.date}: ${it.kg} kg", style = MaterialTheme.typography.bodySmall) }
                    GrowthChartCard(hindi, b.sex, b.dob, b.mcp.weights)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(kg, { kg = it.filter { c -> c.isDigit() || c == '.' }.take(5) }, label = { Text(tr("kg today", "आज किलो")) }, singleLine = true, modifier = Modifier.weight(1f))
                        TextButton(onClick = { kg.toDoubleOrNull()?.takeIf { it in 0.3..40.0 }?.let { w -> if (updateBen { x -> x.copy(mcp = x.mcp.copy(weights = x.mcp.weights + WeightEntry(today.toString(), w))) }) kg = "" } }) { Text(tr("Add", "जोड़ें")) }
                    }
                }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { giveOpen = true }) { Text(tr("Give from drug kit", "दवा किट से दें")) }
                    OutlinedButton(onClick = { eventOpen = true }) { Text(tr("Record event", "घटना दर्ज करें")) }
                }
                val myVisits = visits.filter { it.personId == b.id }.sortedByDescending { it.date }
                if (myVisits.isNotEmpty()) { Text(tr("Visits", "विज़िट"), fontWeight = FontWeight.Bold); myVisits.take(10).forEach { Text("${it.date} · ${it.kind.title} · ${if (it.completed) tr("completed", "पूरी") else tr("draft", "अधूरी")}", style = MaterialTheme.typography.bodySmall) } }
                TextButton(onClick = { if (updateBen { it.copy(active = false) }) onClose() }) { Text(tr("Remove from active list", "सक्रिय सूची से हटाएँ")) }
                TextButton(onClick = onClose) { Text(tr("Close", "बंद करें")) }
            }
        }
    }
    reading?.let { r -> AlertDialog(onDismissRequest = { reading = null }, title = { Row(verticalAlignment = Alignment.CenterVertically) { Text(tr("Found on the card", "कार्ड पर मिला"), modifier = Modifier.weight(1f)); AiBadge("Read MCP card photo") } }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (readModel.isNotBlank()) AiResultChip("ML Kit text + $readModel", !readModel.startsWith("On-phone"), readMs, hindi)
            ConfirmChip(hindi)
            if (r.fields.isEmpty()) Text(tr("Nothing could be confirmed from this photo. Take a closer, brighter photo with the card flat.", "इस फ़ोटो से कुछ पक्का नहीं हुआ। कार्ड सीधा रखकर पास से, रोशनी में फ़ोटो लें।"))
            else Text(tr("Untick anything that does not match the card.", "जो कार्ड से न मिले उसका निशान हटाएँ।"), style = MaterialTheme.typography.bodySmall)
            r.fields.forEachIndexed { i, f -> Row(verticalAlignment = Alignment.Top) {
                Checkbox(i in picked, { c -> picked = if (c) picked + i else picked - i })
                Column(Modifier.weight(1f)) { Text("${f.label}: ${f.value}", fontWeight = FontWeight.Bold); Text(tr("On card: ", "कार्ड पर: ") + f.evidence.take(100), style = MaterialTheme.typography.bodySmall) }
            } }
        }
    }, confirmButton = { if (r.fields.isNotEmpty()) Button(onClick = {
        val chosen = r.fields.filterIndexed { i, _ -> i in picked }
        if (updateBen { x -> ArogyaCardApply.apply(x, chosen) }) { Toast.makeText(context, tr("Added ", "") + "${chosen.size} " + tr("details to the MCP card", "विवरण MCP कार्ड में जोड़े"), Toast.LENGTH_SHORT).show(); reading = null }
    }, enabled = picked.isNotEmpty()) { Text(tr("Add to MCP card", "MCP कार्ड में जोड़ें")) } }, dismissButton = { TextButton(onClick = { reading = null }) { Text(tr("Close", "बंद करें")) } }) }
    ancEdit?.let { n ->
        val cur = b.mcp.ancDetails[n] ?: AncDetail()
        var w by remember(n) { mutableStateOf(cur.weightKg) }; var bp by remember(n) { mutableStateOf(cur.bp) }; var hb by remember(n) { mutableStateOf(cur.hb) }
        AlertDialog(onDismissRequest = { ancEdit = null }, title = { Text("ANC $n — " + tr("as on the MCP card", "जैसा MCP कार्ड पर")) }, text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(w, { w = it.filter { c -> c.isDigit() || c == '.' }.take(5) }, label = { Text(tr("Weight (kg)", "वज़न (किलो)")) }, singleLine = true)
            OutlinedTextField(bp, { bp = it.filter { c -> c.isDigit() || c == '/' }.take(7) }, label = { Text(tr("Blood pressure (e.g. 110/70)", "रक्तचाप (जैसे 110/70)")) }, singleLine = true)
            OutlinedTextField(hb, { hb = it.filter { c -> c.isDigit() || c == '.' }.take(4) }, label = { Text(tr("Haemoglobin (g/dl)", "हीमोग्लोबिन (g/dl)")) }, singleLine = true)
            Text(tr("Copy from the ANM's entry on the card.", "कार्ड पर ANM की लिखावट से उतारें।"), style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { Button(onClick = { if (updateBen { x -> x.copy(mcp = x.mcp.copy(ancDetails = x.mcp.ancDetails + (n to AncDetail(w, bp, hb)))) }) ancEdit = null }) { Text(tr("Save", "सहेजें")) } },
            dismissButton = { TextButton(onClick = { ancEdit = null }) { Text(tr("Cancel", "रद्द करें")) } })
    }
    if (giveOpen) StockGiveDialog(hindi, emptyList(), ArogyaRegistryLogic.catalog(data.stockItems),
        ArogyaRegistryLogic.stockBalance(data.stock), fixedPerson = b, onDismiss = { giveOpen = false }) { item, qty, person, giveId ->
        var s = ""
        if (save { r -> ArogyaOfficialForms.applyGive(r, person, item, qty, giveId).let { a0 ->
            s = ArogyaChangeSummary.diff(r, a0, person.id, hindi); ArogyaChangeSummary.attachDiary(a0, person.id, today.toString(), s) } }) { giveOpen = false; changedSummary = s } }
    if (eventOpen) EventDialog(hindi, onDismiss = { eventOpen = false }) { kind, place, note ->
        if (save { r -> r.copy(vitals = r.vitals + VitalEvent(kind = kind, beneficiaryId = b.id, place = place, note = note)) }) eventOpen = false }
    viewForm?.let { f -> ArogyaFormViewer(f, hindi, ArogyaRegistryStore.get(context)) { viewForm = null } }
    if (changedSummary.isNotBlank()) WhatChangedDialog(changedSummary, hindi) { changedSummary = "" }
}

@Composable
private fun EventDialog(hindi: Boolean, onDismiss: () -> Unit, onSave: (VitalKind, String, String) -> Unit) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    var kind by remember { mutableStateOf(VitalKind.BIRTH) }
    var place by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(tr("Vital event / surveillance", "महत्वपूर्ण घटना / निगरानी")) }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            VitalKind.values().forEach { k -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(kind == k, { kind = k }); Text(if (hindi) k.titleHi else k.title) } }
            OutlinedTextField(place, { place = it.take(80) }, label = { Text(tr("Place", "जगह")) }, singleLine = true)
            OutlinedTextField(note, { note = it.take(300) }, label = { Text(tr("Note", "नोट")) })
            Text(tr("Deaths and AEFI must also be reported to your ANM / MO immediately.", "मृत्यु और AEFI की सूचना ANM/MO को तुरंत भी दें।"), style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { Button(onClick = { onSave(kind, place, note) }) { Text(tr("Save", "सहेजें")) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "रद्द करें")) } })
}

@Composable
private fun RegistersTab(hindi: Boolean, data: RegistryData, visits: List<ArogyaVisit>, store: ArogyaRegistryStore, today: LocalDate, onOpen: (String) -> Unit, ai: ArogyaVisitAi? = null, save: ((RegistryData) -> RegistryData) -> Boolean) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    val context = LocalContext.current
    var sub by remember { mutableStateOf("") }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var diaryOpen by remember { mutableStateOf(false) }
    var receiveOpen by rememberSaveable { mutableStateOf(false) }
    var samOpen by rememberSaveable { mutableStateOf(false) }
    var eventOpen by remember { mutableStateOf(false) }
    val names = remember(data) { data.beneficiaries.associate { it.id to it.name } }
    fun share(name: String, text: String) { if (!store.shareFile(name, "text/csv", text.toByteArray())) Toast.makeText(context, tr("Could not save the file.", "फ़ाइल नहीं बनी।"), Toast.LENGTH_LONG).show() }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val pages = listOf("forms" to (tr("📄 Official forms (print / share)", "📄 आधिकारिक फ़ॉर्म (छापें / भेजें)") to "RI Form 3 & 4, Home Visit Form, Drug Kit Stock Card"),
            "report" to (tr("💰 Monthly report & claim", "💰 मासिक रिपोर्ट और दावा") to tr("What to submit this month", "इस महीने क्या जमा करें")), "lists" to (tr("📋 Monthly lists", "📋 मासिक सूचियाँ") to tr("Immunization due · ANC · eligible couples", "टीकाकरण बाकी · ANC · योग्य दंपति")),
            "diary" to (tr("📓 Activity diary", "📓 कार्य डायरी") to tr("Visits are added automatically", "विज़िट अपने आप जुड़ती हैं")), "drugs" to (tr("💊 Drug kit", "💊 दवा किट") to tr("Stock and what you gave", "स्टॉक और जो दिया")),
            "vitals" to (tr("📍 Births, deaths & cases", "📍 जन्म, मृत्यु और मामले") to tr("Vital events and surveillance", "घटनाएँ और निगरानी")), "vhr" to (tr("🏘 Village health register", "🏘 गाँव स्वास्थ्य रजिस्टर") to tr("Households and people", "परिवार और लोग")),
            "rch" to (tr("📤 RCH register export", "📤 RCH रजिस्टर निर्यात") to tr("For your ANM / RCH portal", "ANM / RCH पोर्टल के लिए")))
        if (sub.isBlank()) pages.forEach { (id, t) ->
            Card(Modifier.fillMaxWidth().clickable { sub = id }) { Column(Modifier.padding(14.dp)) { Text(t.first, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Text(t.second, style = MaterialTheme.typography.bodySmall) } }
        } else TextButton(onClick = { sub = "" }) { Text(tr("‹ All registers", "‹ सभी रजिस्टर")) }
        when (sub) {
            "" -> {}
            "forms" -> {
                var viewer by remember { mutableStateOf<FormTable?>(null) }
                val babies = data.beneficiaries.filter { it.active && it.category == BenCategory.CHILD && (ArogyaRegistryLogic.ageDays(it, today) ?: 999) <= 60 }
                Text(tr("Official formats filled with this register's data. Share as PDF to print or send to your ANM.", "आधिकारिक खाके इस रजिस्टर के डेटा से भरे। छापने या ANM को भेजने के लिए PDF भेजें।"), style = MaterialTheme.typography.bodySmall)
                listOf(tr("House to House Survey — RI Form 3", "घर-घर सर्वे — RI फ़ॉर्म 3") to { ArogyaForms.riForm3(data.beneficiaries, today) }, tr("Pregnant Women Listing — RI Form 4", "गर्भवती सूची — RI फ़ॉर्म 4") to { ArogyaForms.riForm4(data.beneficiaries, today) },
                    tr("Drug Kit Stock Card — Annexure 5", "दवा किट स्टॉक कार्ड — Annexure 5") to { ArogyaForms.drugKitCard(data.stock, data.beneficiaries.any { it.demo }) }).forEach { (label, make) ->
                    OutlinedButton(onClick = { viewer = make() }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                }
                babies.filter { nb -> data.forms.none { it.beneficiaryId == nb.id && it.formId == "annex9" } }.forEach { nb -> OutlinedButton(onClick = { viewer = if (data.forms.any { it.beneficiaryId == nb.id && it.formId == "annex9" }) ArogyaOfficialForms.table(ArogyaOfficialForms.ANNEX9, nb, data.forms) else ArogyaForms.homeVisitForm(nb, visits) }, modifier = Modifier.fillMaxWidth()) { Text(tr("Home Visit Form (Annexure 9) — ", "होम विज़िट फ़ॉर्म (Annexure 9) — ") + nb.name) } }
                val filledForms = data.forms.groupBy { it.beneficiaryId to it.formId }.entries.mapNotNull { (k, es) -> val b = data.beneficiaries.firstOrNull { it.id == k.first }; val sp = ArogyaOfficialForms.byId(k.second); if (b == null || sp == null) null else Triple(b, sp, es.size) }
                    .sortedWith(compareBy({ ArogyaOfficialForms.ALL.indexOf(it.second) }, { it.first.name }))
                if (filledForms.isNotEmpty()) Text(tr("Visit forms filled from home visits (", "घर विज़िट से भरे फ़ॉर्म (") + filledForms.size + ")", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                filledForms.forEach { (b, sp, n) -> OutlinedButton(onClick = { viewer = ArogyaOfficialForms.table(sp, b, data.forms) }, modifier = Modifier.fillMaxWidth()) {
                    Text("${sp.title.substringBefore(" —").substringBefore(" (")} — ${b.name} · $n " + tr("visit", "विज़िट") + (if (n == 1 || hindi) "" else "s"), maxLines = 2) } }
                viewer?.let { f -> ArogyaFormViewer(f, hindi, store) { viewer = null } }
            }
            "vhr" -> {
                val v = remember(data) { ArogyaRegistryLogic.villageRegister(data.beneficiaries, today) }
                Text(tr("Village Health Register", "गाँव स्वास्थ्य रजिस्टर"), fontWeight = FontWeight.Bold)
                Text(tr("From your household line listing (do it at the start of the year, update every 6 months; births and deaths every month).", "आपकी घर-सूची से (साल शुरू में करें, हर 6 महीने अपडेट; जन्म-मृत्यु हर महीने)।"), style = MaterialTheme.typography.bodySmall)
                Text(tr("Households: ", "परिवार: ") + v.households + "   ·   " + tr("People listed: ", "लोग: ") + v.people + "   ·   " + tr("High risk: ", "जोखिम: ") + v.highRisk)
                v.byGroup.entries.sortedBy { it.key }.forEach { Text("${it.key}: ${it.value}") }
                val lastSurvey = data.diary.filter { it.kind == DiaryKind.SURVEY }.maxOfOrNull { it.date }
                Text(tr("Last line listing recorded: ", "आखिरी सूची: ") + (lastSurvey ?: tr("none — add it in the Activity diary", "नहीं — कार्य डायरी में जोड़ें")), color = if (lastSurvey == null || LocalDate.parse(lastSurvey).isBefore(today.minusMonths(6))) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                val births = data.vitals.count { it.kind == VitalKind.BIRTH && it.date.startsWith(YearMonth.from(today).toString()) }
                val deaths = data.vitals.count { it.kind.name.endsWith("DEATH") && it.date.startsWith(YearMonth.from(today).toString()) }
                Text(tr("This month: ", "इस महीने: ") + tr("births ", "जन्म ") + births + " · " + tr("deaths ", "मृत्यु ") + deaths + tr(" (record in Vital events)", " (घटना में दर्ज करें)"))
                val vhr = remember(data) { ArogyaRegisterTables.villageTable(data.beneficiaries, today, hindi) }
                ArogyaRegisterTable(hindi, tr("Village health register", "गाँव स्वास्थ्य रजिस्टर"), vhr, store, statusCol = 5, onOpenPerson = onOpen, sample = data.beneficiaries.any { it.demo })
            }
            "lists" -> {
                var immMonth by remember { mutableStateOf(YearMonth.from(today).plusMonths(1)) }
                val imm = remember(data, immMonth) { ArogyaRegisterTables.immunizationTable(data.beneficiaries, immMonth, today, hindi) }
                val anc = remember(data) { ArogyaRegisterTables.ancTable(data.beneficiaries, today, hindi) }
                val ecs = remember(data) { ArogyaRegisterTables.coupleTable(data.beneficiaries, hindi) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { immMonth = immMonth.minusMonths(1) }) { Text("◀") }
                    Text(tr("Due list of children to be immunized — ", "टीकाकरण बाकी बच्चों की सूची — ") + "$immMonth", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { immMonth = immMonth.plusMonths(1) }) { Text("▶") }
                }
                ArogyaRegisterTable(hindi, tr("Immunization due", "टीकाकरण बाकी") + " $immMonth", imm, store, statusCol = 6, villageCol = 2, statusFilter = true, onOpenPerson = onOpen,
                    colWidths = listOf(120.dp, 110.dp, 84.dp, 56.dp, 150.dp, 100.dp, 96.dp), sample = data.beneficiaries.any { it.demo })
                ArogyaRegisterTable(hindi, tr("ANC beneficiaries", "ANC लाभार्थी"), anc, store, statusCol = 4, villageCol = 1, statusFilter = true, onOpenPerson = onOpen, sample = data.beneficiaries.any { it.demo })
                ArogyaRegisterTable(hindi, tr("Eligible couples", "योग्य दंपति"), ecs, store, statusCol = 3, villageCol = 1, statusFilter = true, onOpenPerson = onOpen, sample = data.beneficiaries.any { it.demo })
                Text(tr("Share these with your ANM at the monthly meeting.", "मासिक बैठक में ANM को ये भेजें।"), style = MaterialTheme.typography.bodySmall)
            }
            "rch" -> {
                val groups = data.beneficiaries.filter { it.active }.groupBy { ArogyaRegistryLogic.rchGroup(it, today) }
                Text(tr("RCH register", "RCH रजिस्टर"), fontWeight = FontWeight.Bold)
                groups.entries.sortedBy { it.key }.forEach { (g, l) -> Text("$g: ${l.size}" + (l.count { ArogyaRegistryLogic.isHighRisk(it) }.takeIf { it > 0 }?.let { " ($it " + tr("high risk", "जोखिम") + ")" } ?: "")) }
                Button(onClick = { share("RCH-register-$today.csv", ArogyaRegistryLogic.rchCsv(data.beneficiaries.filter { it.active }, today)) }) { Text(tr("Export RCH register (CSV)", "RCH रजिस्टर भेजें (CSV)")) }
                OutlinedButton(onClick = { share("Visits-$today.csv", ArogyaRegistryLogic.visitsCsv(visits)) }) { Text(tr("Export visits (CSV)", "विज़िट भेजें (CSV)")) }
                Text(tr("For entry into the RCH portal / U-WIN by your ANM or data operator.", "ANM या ऑपरेटर RCH पोर्टल / U-WIN में भरें।"), style = MaterialTheme.typography.bodySmall)
            }
            "diary" -> {
                var diaryOriginKey by remember { mutableStateOf("") }
                Text(tr("Activity diary — visits are added automatically from completed visits. Tap a row for where it came from.", "कार्य डायरी — पूरी विज़िट अपने आप जुड़ती हैं। स्रोत जानने के लिए पंक्ति दबाएँ।"), fontWeight = FontWeight.Bold)
                Button(onClick = { diaryOpen = true }) { Text(tr("+ Add activity (VHND, meeting, escort…)", "+ काम जोड़ें (VHND, बैठक…)")) }
                val dt = remember(data, visits) { ArogyaRegisterTables.diaryTable(visits, data.diary, names + visits.associate { it.personId to it.name }, hindi) }
                ArogyaRegisterTable(hindi, tr("Activity diary", "कार्य डायरी"), dt, store, monthCol = 0, onRowKey = { diaryOriginKey = it }, sample = data.beneficiaries.any { it.demo })
                if (diaryOriginKey.isNotBlank()) {
                    val key = diaryOriginKey
                    val visit = if (key.startsWith("v")) visits.firstOrNull { "v" + it.id == key } else null
                    val entry = if (visit == null) data.diary.firstOrNull { it.id == key } else null
                    val ben = visit?.personId ?: entry?.beneficiaryId.orEmpty()
                    if (visit != null || entry != null) OriginDialog(
                        if (visit != null) ArogyaOrigin.visitRecord(visit, hindi) else ArogyaOrigin.diary(entry!!, data.stock, hindi),
                        hindi, { diaryOriginKey = "" },
                        extraButton = if (ben.isNotBlank() && data.beneficiaries.any { it.id == ben }) {
                            { TextButton(onClick = { diaryOriginKey = ""; onOpen(ben) }) { Text(tr("Open person", "व्यक्ति खोलें")) } }
                        } else null)
                    else diaryOriginKey = ""
                }
            }
            "drugs" -> {
                var stockMonth by remember { mutableStateOf(YearMonth.from(today)) }
                var addOpen by remember { mutableStateOf(false) }
                var adjustOpen by remember { mutableStateOf(false) }
                var writeoffOpen by remember { mutableStateOf(false) }
                var giveOpen by remember { mutableStateOf(false) }
                var viewer by remember { mutableStateOf<FormTable?>(null) }
                var historyItem by remember { mutableStateOf("") }
                var scanOpen by remember { mutableStateOf(false) }
                var voiceOpen by remember { mutableStateOf(false) }
                var changedSummary by remember { mutableStateOf("") }
                var scanReceive by remember { mutableStateOf<Pair<MedScan, String>?>(null) }
                var scanAdd by remember { mutableStateOf<MedScan?>(null) }
                val cat = remember(data) { ArogyaRegistryLogic.catalog(data.stockItems) }
                val bal = remember(data) { ArogyaRegistryLogic.stockBalance(data.stock) }
                val reg = remember(data, stockMonth) { ArogyaRegistryLogic.stockRegister(data.stock, data.stockItems, stockMonth, today, hindi) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { stockMonth = stockMonth.minusMonths(1) }) { Text("◀") }
                    Text(tr("Drug kit stock register — ", "दवा किट स्टॉक रजिस्टर — ") + "$stockMonth", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { stockMonth = stockMonth.plusMonths(1) }) { Text("▶") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    Button(onClick = { receiveOpen = true }) { Text(tr("+ Received", "+ मिला"), maxLines = 1) }
                    OutlinedButton(onClick = { giveOpen = true }) { Text(tr("Give", "दें"), maxLines = 1) }
                    OutlinedButton(onClick = { adjustOpen = true }) { Text(tr("Correction", "सुधार"), maxLines = 1) }
                    OutlinedButton(onClick = { writeoffOpen = true }) { Text(tr("Expired", "एक्सपायर"), maxLines = 1) }
                    OutlinedButton(onClick = { addOpen = true }) { Text(tr("+ Item", "+ दवा"), maxLines = 1) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    if (ai != null) OutlinedButton(onClick = { scanOpen = true }) { Text(tr("🔍 Scan medicine", "🔍 दवा स्कैन करें"), maxLines = 1) }
                    OutlinedButton(onClick = { voiceOpen = true }) { Text(tr("🎤 Speak stock", "🎤 स्टॉक बोलें"), maxLines = 1) }
                    OutlinedButton(onClick = { samOpen = true }) { Text(tr("📷 Count from photo", "📷 फ़ोटो से गिनें"), maxLines = 1) }
                    OutlinedButton(onClick = { viewer = ArogyaForms.drugKitCard(data.stock, data.beneficiaries.any { it.demo }) }) { Text(tr("Annexure 5", "Annexure 5"), maxLines = 1) }
                }
                if (reg.rows.any { it.status == "OUT" || it.status == "LOW" || it.status == "EXPIRED" || it.status == "EXPIRING" }) {
                    Button(onClick = { ArogyaShare.shareText(context, tr("Drug kit refill request", "दवा किट रिफिल अनुरोध"),
                        ArogyaRegistryLogic.refillRequest(data.stock, data.stockItems, today, hindi)) }, modifier = Modifier.fillMaxWidth()) {
                        Text(tr("Request refill from ANM", "ANM से रिफिल माँगें"))
                    }
                }
                ArogyaRegisterTable(hindi, tr("Drug kit stock", "दवा किट स्टॉक") + " $stockMonth", reg, store, statusCol = 8,
                    onRowKey = { historyItem = it },
                    colWidths = listOf(150.dp, 80.dp, 80.dp, 80.dp, 80.dp, 80.dp, 90.dp, 104.dp, 110.dp),
                    note = tr("Opening = before this month. Tap a row for its full history.", "शुरुआत = इस महीने से पहले। पूरी जानकारी के लिए पंक्ति दबाएँ।"),
                    sample = data.beneficiaries.any { it.demo })
                if (voiceOpen) VoiceStockDialog(hindi, ai, cat, bal,
                    onSave = { txns -> var s = ""
                        if (save { r -> r.copy(stock = r.stock + txns).also { s = ArogyaChangeSummary.diff(r, it, "", hindi) } }) { voiceOpen = false; changedSummary = s } },
                    onDismiss = { voiceOpen = false })
                if (scanOpen && ai != null) MedScanDialog(hindi, ai, cat,
                    onReceive = { s, m -> scanOpen = false; scanReceive = s to m; receiveOpen = true },
                    onAdd = { s -> scanOpen = false; scanAdd = s; addOpen = true },
                    onDismiss = { scanOpen = false })
                if (addOpen) StockAddItemDialog(hindi, initialName = scanAdd?.name.orEmpty(), onDismiss = { addOpen = false; scanAdd = null }) { item ->
                    if (save { r -> r.copy(stockItems = r.stockItems.filterNot { it.name == item.name } + item) }) { addOpen = false; scanAdd = null } }
                if (receiveOpen) { val pre = scanReceive
                    StockReceiveDialog(hindi, cat, initialItem = pre?.second.orEmpty(), initialQty = pre?.first?.qty ?: 0,
                        initialBatch = pre?.first?.batch.orEmpty(), initialExpiry = pre?.first?.expiry?.let { ArogyaMedScan.normExpiry(it) }.orEmpty(),
                        notePrefix = if (pre != null) "Scan: " else "",
                        onDismiss = { receiveOpen = false; scanReceive = null }) { txn ->
                        var s = ""
                        if (save { r -> r.copy(stock = r.stock + txn).also { s = ArogyaChangeSummary.diff(r, it, "", hindi) } }) { receiveOpen = false; scanReceive = null; changedSummary = s } } }
                if (adjustOpen) StockAdjustDialog(hindi, cat, bal, onDismiss = { adjustOpen = false }) { txn ->
                    var s = ""
                    if (save { r -> r.copy(stock = r.stock + txn).also { s = ArogyaChangeSummary.diff(r, it, "", hindi) } }) { adjustOpen = false; changedSummary = s } }
                if (writeoffOpen) StockWriteoffDialog(hindi, cat, bal, onDismiss = { writeoffOpen = false }) { txn ->
                    var s = ""
                    if (save { r -> r.copy(stock = r.stock + txn).also { s = ArogyaChangeSummary.diff(r, it, "", hindi) } }) { writeoffOpen = false; changedSummary = s } }
                if (giveOpen) StockGiveDialog(hindi, data.beneficiaries, cat, bal, onDismiss = { giveOpen = false }) { item, qty, person, giveId ->
                    var s = ""
                    if (save { r -> ArogyaOfficialForms.applyGive(r, person, item, qty, giveId).let { a0 ->
                        s = ArogyaChangeSummary.diff(r, a0, person.id, hindi); ArogyaChangeSummary.attachDiary(a0, person.id, today.toString(), s) } }) { giveOpen = false; changedSummary = s } }
                if (historyItem.isNotBlank()) StockHistoryDialog(hindi, historyItem,
                    ArogyaRegistryLogic.stockHistory(data.stock, historyItem), names, onDismiss = { historyItem = "" })
                viewer?.let { f -> ArogyaFormViewer(f, hindi, store) { viewer = null } }
                if (changedSummary.isNotBlank()) WhatChangedDialog(changedSummary, hindi) { changedSummary = "" }
            }
            "vitals" -> {
                Text(tr("Vital events & surveillance", "घटनाएँ और निगरानी"), fontWeight = FontWeight.Bold)
                Button(onClick = { eventOpen = true }) { Text(tr("+ Record event", "+ घटना दर्ज करें")) }
                val vt = remember(data) { ArogyaRegisterTables.vitalTable(data.vitals, names, hindi) }
                ArogyaRegisterTable(hindi, tr("Vital events & surveillance", "घटनाएँ और निगरानी"), vt, store, monthCol = 0, onOpenPerson = onOpen, sample = data.beneficiaries.any { it.demo })
            }
            "report" -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { month = month.minusMonths(1) }) { Text("◀") }
                    Text(month.toString(), fontWeight = FontWeight.Bold)
                    TextButton(onClick = { month = month.plusMonths(1) }, enabled = month < YearMonth.now()) { Text("▶") }
                }
                val rep = remember(data, visits, month) { ArogyaRegistryLogic.monthlyReport(data, visits, month) }
                val inc = remember(data, visits, month) { ArogyaRegistryLogic.incentiveEstimate(data, visits, month) }
                val claim = rep.text + "\n\nINCENTIVE ESTIMATE (ASHA Incentives 2025, NHSRC; rural rates; state rates may differ — verify with ANM):\n" +
                    inc.lines.filter { it.count > 0 }.joinToString("\n") { "${it.item}: ${it.count} × Rs ${it.rate} = Rs ${it.amount}" } + "\nEstimated total: Rs ${inc.total}"
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { FixedRuleChip(hindi); Text(tr("Fixed government rules", "तय सरकारी नियम"), style = MaterialTheme.typography.bodySmall) }
                Card(Modifier.fillMaxWidth()) { Text(claim, Modifier.padding(12.dp)) }
                var repViewer by remember { mutableStateOf<FormTable?>(null) }
                var repBusy by remember { mutableStateOf(false) }
                val repScope = rememberCoroutineScope()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { ArogyaShare.shareText(context, "ASHA monthly summary $month", claim) }) { Text(tr("Share summary & claim", "सारांश और दावा भेजें")) }
                    OutlinedButton(onClick = { repViewer = ArogyaRegistryLogic.monthlyTable(rep, inc, month, hindi, data.beneficiaries.any { it.demo }) }) { Text(tr("Printable", "छपने वाला")) }
                    OutlinedButton(onClick = {
                        repBusy = true
                        repScope.launch {
                            try {
                                if (!store.shareFormPdf(ArogyaRegistryLogic.monthlyTable(rep, inc, month, hindi, data.beneficiaries.any { it.demo }))) Toast.makeText(context, tr("Could not create the PDF", "PDF नहीं बना"), Toast.LENGTH_LONG).show()
                            } finally {
                                repBusy = false
                            }
                        }
                    }, enabled = !repBusy) { Text(if (repBusy) tr("Making PDF…", "PDF बन रही है…") else tr("Share PDF", "PDF भेजें")) }
                }
                repViewer?.let { f -> ArogyaFormViewer(f, hindi, store) { repViewer = null } }
                Text(tr("Use it to fill the monthly claim / RCH portal entry with your ANM. Check against your paper records.", "ANM के साथ मासिक दावा / RCH भरने में काम लें। कागज़ी रिकॉर्ड से मिलाएँ।"), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (diaryOpen) {
        var kind by remember { mutableStateOf(DiaryKind.VHND) }
        var note by remember { mutableStateOf("") }
        var date by remember { mutableStateOf(today.toString()) }
        AlertDialog(onDismissRequest = { diaryOpen = false }, title = { Text(tr("Add activity", "काम जोड़ें")) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DiaryKind.values().forEach { k -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(kind == k, { kind = k }); Text(if (hindi) k.titleHi else k.title) } }
                DateBox(tr("Date", "तारीख़"), date) { date = it }
                OutlinedTextField(note, { note = it.take(300) }, label = { Text(tr("Note", "नोट")) })
            }
        }, confirmButton = { Button(onClick = { if (save { r -> r.copy(diary = r.diary + DiaryEntry(date = date, kind = kind, note = note)) }) diaryOpen = false }) { Text(tr("Save", "सहेजें")) } },
            dismissButton = { TextButton(onClick = { diaryOpen = false }) { Text(tr("Cancel", "रद्द करें")) } })
    }
    if (samOpen) SamCountDialog(hindi, ArogyaRegistryLogic.stockBalance(data.stock), vision = ai?.vision(), onRecord = { item, counted, note ->
        val diff = counted - (ArogyaRegistryLogic.stockBalance(data.stock)[item] ?: 0)
        if (diff == 0 || save { r -> r.copy(stock = r.stock + StockTxn(item = item, qty = diff, note = "$note: counted $counted", kind = "adjust", reason = note)) }) samOpen = false
    }, onDismiss = { samOpen = false })
    if (eventOpen) EventDialog(hindi, onDismiss = { eventOpen = false }) { kind, place, note ->
        if (save { r -> r.copy(vitals = r.vitals + VitalEvent(kind = kind, place = place, note = note)) }) eventOpen = false }
}

@Composable
private fun BackupTab(hindi: Boolean, store: ArogyaRegistryStore, queue: ArogyaVisitQueue) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(store.lastStatus) }
    var pw1 by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var auto by remember { mutableStateOf(store.autoDaily) }
    var location by remember { mutableStateOf(store.backupUri) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var restorePw by remember { mutableStateOf("") }
    val hasPw = store.password().isNotBlank()
    fun go(block: suspend () -> String) { busy = true; scope.launch { try { status = block() } catch (e: CancellationException) { throw e } catch (e: Exception) { status = e.message ?: "Failed" } finally { busy = false } } }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            store.backupUri = uri; location = uri
            val pw = store.password(); if (pw.isNotBlank()) go { store.backupTo(uri, pw) }
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> restoreUri = uri }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Backup — like WhatsApp backup", "बैकअप — WhatsApp जैसा"), fontWeight = FontWeight.Bold)
        Text(tr("All patients, MCP cards, registers and visits go into one file locked with your password. Choose Google Drive in the file picker to keep it in your Drive (it uploads when you are online). Without the password nobody — including you — can open it.", "सभी लोग, MCP कार्ड, रजिस्टर और विज़िट एक फ़ाइल में आपके पासवर्ड से बंद। Drive में रखने के लिए फ़ाइल चुनते समय Google Drive चुनें (ऑनलाइन आने पर अपलोड होगा)। पासवर्ड बिना कोई नहीं खोल सकता — आप भी नहीं।"), style = MaterialTheme.typography.bodySmall)
        if (!hasPw) {
            OutlinedTextField(pw1, { pw1 = it }, label = { Text(tr("Backup password (8+ characters)", "बैकअप पासवर्ड (8+ अक्षर)")) }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pw2, { pw2 = it }, label = { Text(tr("Repeat password", "पासवर्ड दोहराएँ")) }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Button(onClick = { store.savePassword(pw1); pw1 = ""; pw2 = ""; status = tr("Password saved on this phone (encrypted). Write it down somewhere safe.", "पासवर्ड इस फ़ोन पर सहेजा (गुप्त)। कहीं लिखकर रखें।") }, enabled = pw1.length >= 8 && pw1 == pw2) { Text(tr("Save password", "पासवर्ड सहेजें")) }
        } else Text(tr("✓ Backup password saved", "✓ बैकअप पासवर्ड सहेजा"), color = MaterialTheme.colorScheme.primary)
        Text(tr("Location: ", "जगह: ") + (location?.let { it.authority?.let { a -> if (a.contains("google")) "Google Drive" else a } ?: it.toString() } ?: tr("not chosen", "चुनी नहीं")))
        Button(onClick = { create.launch("ArogyaDoot-backup.argb") }, enabled = hasPw && !busy, modifier = Modifier.fillMaxWidth()) { Text(tr("Choose backup location (Google Drive)", "बैकअप जगह चुनें (Google Drive)")) }
        Button(onClick = { location?.let { u -> go { store.backupTo(u, store.password()) } } }, enabled = hasPw && location != null && !busy, modifier = Modifier.fillMaxWidth()) { Text(tr("Back up now", "अभी बैकअप करें")) }
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(auto, { auto = it; store.autoDaily = it }, enabled = hasPw && location != null); Text(tr("  Back up automatically once a day (when the app is opened)", "  रोज़ अपने आप बैकअप (ऐप खोलने पर)")) }
        if (store.lastBackup > 0) Text(tr("Last backup: ", "आखिरी बैकअप: ") + java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", java.util.Locale.getDefault()).format(java.util.Date(store.lastBackup)), style = MaterialTheme.typography.bodySmall)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text(tr("Restore", "वापस लाएँ"), fontWeight = FontWeight.Bold)
        Text(tr("On a new phone: install ArogyaDoot, open the backup file from Drive, enter the password. Registers are replaced; visits already on the phone are kept.", "नए फ़ोन पर: ArogyaDoot डालें, Drive से बैकअप फ़ाइल खोलें, पासवर्ड डालें। रजिस्टर बदल जाएँगे; फ़ोन की विज़िट रहेंगी।"), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { open.launch(arrayOf("*/*")) }, enabled = !busy) { Text(tr("Choose backup file", "बैकअप फ़ाइल चुनें")) }
        restoreUri?.let { u ->
            OutlinedTextField(restorePw, { restorePw = it }, label = { Text(tr("Backup password", "बैकअप पासवर्ड")) }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Button(onClick = { go { store.restore(u, restorePw, queue).also { restoreUri = null; restorePw = "" } } }, enabled = restorePw.isNotBlank() && !busy) { Text(tr("Restore now", "अभी वापस लाएँ")) }
        }
        HorizontalDivider()
        Text(tr("Demo / scale test", "डेमो / जाँच"), fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { runCatching { store.loadSyntheticPatients(1000) }.onSuccess { status = tr("Added 1000 synthetic patients.", "1000 काल्पनिक लोग जोड़े।") } }, enabled = !busy) { Text(tr("Add 1000 synthetic", "1000 काल्पनिक जोड़ें")) }
            OutlinedButton(onClick = { runCatching { store.removeSynthetic() }.onSuccess { status = tr("Removed synthetic patients.", "काल्पनिक लोग हटाए।") } }, enabled = !busy) { Text(tr("Remove synthetic", "काल्पनिक हटाएँ")) }
        }
    }
}
