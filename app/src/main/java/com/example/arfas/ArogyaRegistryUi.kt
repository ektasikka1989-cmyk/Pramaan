package com.example.arfas

import android.app.DatePickerDialog
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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
fun ArogyaAiPage(hindi: Boolean, modelStatus: String, onlineStatus: String, onDismiss: () -> Unit, ai: ArogyaVisitAi? = null) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    var cloudOpen by rememberSaveable { mutableStateOf(false) }
    if (cloudOpen) ArogyaCloudSettingsDialog(hindi) { cloudOpen = false }
    val benchScope = rememberCoroutineScope()
    var benchLog by remember { mutableStateOf("") }
    var benchBusy by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(tr("✨ AI in ArogyaDoot", "✨ ArogyaDoot में AI")) }, text = {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) { Column(Modifier.padding(10.dp)) {
                Text(tr("On this phone now", "इस फ़ोन पर अभी"), fontWeight = FontWeight.Bold); Text(modelStatus, style = MaterialTheme.typography.bodySmall); Text(onlineStatus, style = MaterialTheme.typography.bodySmall)
            } }
            Button(onClick = { cloudOpen = true }, modifier = Modifier.fillMaxWidth()) { Text(tr("☁ Cloud AI (Meta) — keys and models", "☁ क्लाउड AI (Meta) — key और मॉडल")) }
            if (ai != null && (LocalContext.current.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
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
                // Item 2: evidence profile + speed switches (test aid). Evidence/salvage/context apply
                // to the next question; llama batch/FA apply when the model next loads (restart the app).
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
                var spBusy by remember { mutableStateOf(false) }
                var spLog by remember { mutableStateOf("") }
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
            ArogyaAiCatalog.features.forEach { f ->
                Column {
                    Text((if (f.metaModel) "Ⓜ " else "• ") + f.feature, fontWeight = FontWeight.Bold)
                    Text(f.model + " — " + f.maker, style = MaterialTheme.typography.bodySmall, color = if (f.metaModel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(f.whatAiDoes + " · " + f.where, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(tr("Ⓜ = Meta model.", "Ⓜ = Meta मॉडल।"), style = MaterialTheme.typography.bodySmall)
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
                   dueToday: Int = 0, referrals: Int = 0) {
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
fun ArogyaRegistryHub(hindi: Boolean, initial: String, visits: List<ArogyaVisit>, queue: ArogyaVisitQueue, onClose: () -> Unit, ai: ArogyaVisitAi? = null, onStartVisit: (Beneficiary) -> Unit) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    val context = LocalContext.current
    val store = remember { ArogyaRegistryStore.get(context) }
    val data by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(initial) }
    var openPatient by remember { mutableStateOf<String?>(null) }
    var editPatient by remember { mutableStateOf<Beneficiary?>(null) }
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
                        "due" -> DueTab(hindi, data, visits, today, onVisit = { id -> data.beneficiaries.firstOrNull { it.id == id }?.let(onStartVisit) }) { openPatient = it }
                        "patients" -> PatientsTab(hindi, data, visits, today, onOpen = { openPatient = it }, onAdd = { editPatient = Beneficiary() })
                        "registers" -> RegistersTab(hindi, data, visits, store, today) { c -> save(c) }
                        else -> BackupTab(hindi, store, queue)
                    }
                }
            }
        }
    }
    openPatient?.let { id -> data.beneficiaries.firstOrNull { it.id == id }?.let { b ->
        McpCardDialog(hindi, b, data, visits, today, ai, onClose = { openPatient = null }, onEdit = { editPatient = b },
            onStartVisit = { openPatient = null; onStartVisit(b) }, save = { c -> save(c) })
    } }
    editPatient?.let { b -> PatientForm(hindi, b, onDismiss = { editPatient = null }) { updated ->
        if (save { r -> if (r.beneficiaries.any { it.id == updated.id }) r.copy(beneficiaries = r.beneficiaries.map { if (it.id == updated.id) updated.copy(updatedAt = System.currentTimeMillis()) else it })
                else r.copy(beneficiaries = r.beneficiaries + updated) }) { editPatient = null; openPatient = updated.id }
    } }
}

@Composable
private fun DueTab(hindi: Boolean, data: RegistryData, visits: List<ArogyaVisit>, today: LocalDate, onVisit: (String) -> Unit, onOpen: (String) -> Unit) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    var days by remember { mutableStateOf(7L) }
    val due = remember(data, visits, days) { ArogyaSchedule.dueList(data.beneficiaries, visits, today, days) }
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0L to tr("Today", "आज"), 7L to tr("This week", "इस हफ़्ते"), 30L to tr("This month", "इस महीने")).forEach { (d, l) -> FilterChip(selected = days == d, onClick = { days = d }, label = { Text(l) }) }
        }
        if (due.isEmpty()) Text(tr("Nothing due. Add patients with dates of birth or LMP to build the schedule.", "कुछ बाकी नहीं। जन्मतिथि या LMP के साथ लोग जोड़ें ताकि सूची बने।"), Modifier.padding(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(due, key = { it.beneficiaryId + it.label }) { item ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(item.beneficiaryId) },
                    colors = if (item.overdue(today)) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer) else CardDefaults.cardColors()) {
                    Column(Modifier.padding(10.dp)) {
                        Text("${item.name} · ${item.village}", fontWeight = FontWeight.Bold)
                        Text(item.label)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text((if (item.overdue(today)) tr("OVERDUE — window ended ", "समय निकल गया — अवधि ख़त्म ") + item.windowEnd else if (item.due <= today) tr("Due now (until ", "अभी बाकी (") + item.windowEnd + ")" else tr("Due ", "तारीख़ ") + item.due), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Button(onClick = { onVisit(item.beneficiaryId) }) { Text(tr("Start visit", "विज़िट शुरू करें")) }
                        }
                    }
                }
            }
        }
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
                        Text((if (b.demo) "[DEMO] " else "") + b.name + if (ArogyaRegistryLogic.isHighRisk(b)) tr("  ⚠ high risk", "  ⚠ जोखिम") else "", fontWeight = FontWeight.Bold)
                        Text(trGroup(b) + " · " + (if (b.household.isNotBlank()) "${b.household} · " else "") + b.village + (ArogyaRegistryLogic.edd(b)?.let { " · " + tr("EDD ", "प्रसव तिथि ") + it } ?: "") + (if (b.dob.isNotBlank()) " · " + tr("DOB ", "जन्म ") + b.dob else ""),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun PatientForm(hindi: Boolean, initial: Beneficiary, onDismiss: () -> Unit, onSave: (Beneficiary) -> Unit) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    var b by remember { mutableStateOf(initial) }
    var weight by remember { mutableStateOf(initial.birthWeightKg?.toString().orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (initial.name.isBlank()) tr("Add patient", "रोगी जोड़ें") else tr("Edit patient", "रोगी सुधारें")) }, text = {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("Use fictional names in demos.", "डेमो में काल्पनिक नाम लिखें।"), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(b.name, { b = b.copy(name = it.take(80)) }, label = { Text(tr("Name *", "नाम *")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(b.relativeName, { b = b.copy(relativeName = it.take(80)) }, label = { Text(tr("Husband / mother / father", "पति / माँ / पिता")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(b.household, { b = b.copy(household = it.take(60)) }, label = { Text(tr("Household (house no. / family head) — from line listing", "परिवार (मकान नं. / मुखिया) — सूची से")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(b.village, { b = b.copy(village = it.take(80)) }, label = { Text(tr("Village / hamlet", "गाँव / टोला")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (b.category != BenCategory.CHILD) OutlinedTextField(b.age, { b = b.copy(age = it.filter { c -> c.isDigit() }.take(3)) }, label = { Text(tr("Age (years)", "उम्र (साल)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(b.phone, { b = b.copy(phone = it.filter { c -> c.isDigit() || c == '+' }.take(15)) }, label = { Text(tr("Phone", "फ़ोन")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(b.rchId, { b = b.copy(rchId = it.take(30)) }, label = { Text(tr("RCH ID (if any)", "RCH ID (हो तो)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(tr("Register as", "दर्ज करें"))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BenCategory.values().forEach { c -> FilterChip(selected = b.category == c, onClick = { b = b.copy(category = c, sex = if (c == BenCategory.CHILD) b.sex else "F") }, label = { Text(if (hindi) c.titleHi else c.title) }) }
            }
            if (b.category == BenCategory.CHILD) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("F" to tr("Girl", "लड़की"), "M" to tr("Boy", "लड़का")).forEach { (s, l) -> FilterChip(selected = b.sex == s, onClick = { b = b.copy(sex = s) }, label = { Text(l) }) } }
                DateBox(tr("Date of birth *", "जन्मतिथि *"), b.dob, Modifier.fillMaxWidth()) { b = b.copy(dob = it) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("INSTITUTION" to tr("Hospital birth", "अस्पताल में जन्म"), "HOME" to tr("Home birth", "घर पर जन्म")).forEach { (p, l) -> FilterChip(selected = b.deliveryPlace == p, onClick = { b = b.copy(deliveryPlace = p) }, label = { Text(l) }) } }
                OutlinedTextField(weight, { weight = it.filter { c -> c.isDigit() || c == '.' }.take(5) }, label = { Text(tr("Birth weight (kg)", "जन्म वज़न (किलो)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(b.preterm, { b = b.copy(preterm = it) }); Text(tr("Born preterm", "समय से पहले जन्म")) }
                DateBox(tr("SNCU/NBSU discharge (if admitted)", "SNCU/NBSU छुट्टी (भर्ती हुए तो)"), b.sncuDischarge, Modifier.fillMaxWidth()) { b = b.copy(sncuDischarge = it) }
                if (b.sncuDischarge.isNotBlank()) TextButton(onClick = { b = b.copy(sncuDischarge = "") }) { Text(tr("Clear discharge date", "छुट्टी तारीख़ हटाएँ")) }
            }
            if (b.category == BenCategory.PREGNANT) {
                DateBox(tr("LMP *", "अंतिम माहवारी *"), b.lmp, Modifier.fillMaxWidth()) { b = b.copy(lmp = it) }
                ArogyaRegistryLogic.edd(b)?.let { Text(tr("EDD: ", "प्रसव तिथि: ") + it) }
                DateBox(tr("Delivery date (after birth)", "प्रसव तारीख़ (जन्म के बाद)"), b.deliveryDate, Modifier.fillMaxWidth()) { b = b.copy(deliveryDate = it) }
            }
            if (b.category == BenCategory.ELIGIBLE_COUPLE || b.category == BenCategory.MOTHER)
                OutlinedTextField(b.familyPlanning, { b = b.copy(familyPlanning = it.take(60)) }, label = { Text(tr("Family planning method", "परिवार नियोजन साधन")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(b.notes, { b = b.copy(notes = it.take(1000)) }, label = { Text(tr("Notes", "नोट")) }, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = {
        val ok = b.name.isNotBlank() && (b.category != BenCategory.CHILD || b.dob.isNotBlank()) && (b.category != BenCategory.PREGNANT || b.lmp.isNotBlank())
        Button(onClick = { onSave(b.copy(birthWeightKg = weight.toDoubleOrNull()?.takeIf { it in 0.3..8.0 })) }, enabled = ok) { Text(tr("Save", "सहेजें")) }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "रद्द करें")) } })
}

@Composable
private fun McpCardDialog(hindi: Boolean, b: Beneficiary, data: RegistryData, visits: List<ArogyaVisit>, today: LocalDate, ai: ArogyaVisitAi?, onClose: () -> Unit, onEdit: () -> Unit,
                          onStartVisit: () -> Unit, save: ((RegistryData) -> RegistryData) -> Boolean) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    fun updateBen(f: (Beneficiary) -> Beneficiary) = save { r -> r.copy(beneficiaries = r.beneficiaries.map { if (it.id == b.id) f(it).copy(updatedAt = System.currentTimeMillis()) else it }) }
    var giveOpen by remember { mutableStateOf(false) }
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
    var picked by remember { mutableStateOf(setOf<Int>()) }
    var shotUri by remember { mutableStateOf<Uri?>(null) }
    fun readUri(uri: Uri, temp: Boolean) {
        val engine = ai ?: return
        readBusy = true
        scope.launch {
            try { val r = engine.readCard(uri); reading = r; picked = r.fields.indices.toSet() }
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
                    OutlinedButton(onClick = { if (!ArogyaRegistryStore.get(context).shareCardPdf(b.name, official)) Toast.makeText(context, tr("Could not create the PDF", "PDF नहीं बना"), Toast.LENGTH_LONG).show() }) { Text(tr("Share MCP card (PDF)", "MCP कार्ड भेजें (PDF)")) }
                    val filled = ArogyaOfficialForms.ALL.filter { sp -> data.forms.any { it.beneficiaryId == b.id && it.formId == sp.id } }
                    filled.forEach { sp ->
                        Text(tr("Visit form — filled over visits (tap to open full-screen)", "विज़िट फ़ॉर्म — विज़िटों में भरा (पूरी स्क्रीन में खोलने के लिए दबाएँ)"), fontWeight = FontWeight.Bold)
                        val t = ArogyaOfficialForms.table(sp, b, data.forms)
                        Box(Modifier.clickable { viewForm = t }) { OfficialFormGrid(t, null, hindi = hindi) }
                        OutlinedButton(onClick = { if (!ArogyaRegistryStore.get(context).shareFormPdf(t)) Toast.makeText(context, tr("Could not create the PDF", "PDF नहीं बना"), Toast.LENGTH_LONG).show() }) { Text(tr("Share this form (PDF)", "यह फ़ॉर्म भेजें (PDF)")) }
                    }
                    if (filled.none { it.id == "annex9" } && b.category == BenCategory.CHILD && b.dob.isNotBlank()) OutlinedButton(onClick = { ArogyaRegistryStore.get(context).shareFormPdf(ArogyaForms.homeVisitForm(b, visits)) }) { Text(tr("Share Home Visit Form (Annexure 9, PDF)", "होम विज़िट फ़ॉर्म भेजें (Annexure 9, PDF)")) }
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
                    (1..4).forEach { n -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(b.mcp.anc[n] != null, { c -> if (updateBen { x -> x.copy(mcp = x.mcp.copy(anc = if (c) x.mcp.anc + (n to today.toString()) else x.mcp.anc - n)) } && c) ancEdit = n })
                        Text("ANC $n" + (b.mcp.anc[n]?.let { " — $it" } ?: "") + (b.mcp.ancDetails[n]?.let { " · ${it.weightKg} kg · " + tr("BP ", "रक्तचाप ") + it.bp + " · Hb " + it.hb } ?: ""), modifier = Modifier.weight(1f))
                        if (b.mcp.anc[n] != null) TextButton(onClick = { ancEdit = n }) { Text(tr("Details", "विवरण")) }
                    } }
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
    if (giveOpen) StockDialog(hindi, title = tr("Give to ", "दें: ") + b.name, give = true, onDismiss = { giveOpen = false }) { item, qty, note ->
        if (save { r -> r.copy(stock = r.stock + StockTxn(item = item, qty = -qty, beneficiaryId = b.id, note = note)) }) giveOpen = false }
    if (eventOpen) EventDialog(hindi, onDismiss = { eventOpen = false }) { kind, place, note ->
        if (save { r -> r.copy(vitals = r.vitals + VitalEvent(kind = kind, beneficiaryId = b.id, place = place, note = note)) }) eventOpen = false }
    viewForm?.let { f -> ArogyaFormViewer(f, hindi, ArogyaRegistryStore.get(context)) { viewForm = null } }
}

@Composable
private fun StockDialog(hindi: Boolean, title: String, give: Boolean, onDismiss: () -> Unit, onSave: (String, Int, String) -> Unit) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    var item by remember { mutableStateOf(ArogyaRegistryLogic.DRUG_KIT.first()) }
    var qty by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ArogyaRegistryLogic.DRUG_KIT.forEach { i -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(item == i, { item = i }); Text(i) } }
            OutlinedTextField(qty, { qty = it.filter { c -> c.isDigit() }.take(5) }, label = { Text(if (give) tr("Quantity given", "कितनी दी") else tr("Quantity received", "कितनी मिली")) }, singleLine = true)
            OutlinedTextField(note, { note = it.take(200) }, label = { Text(tr("Note", "नोट")) }, singleLine = true)
        }
    }, confirmButton = { Button(onClick = { qty.toIntOrNull()?.takeIf { it > 0 }?.let { onSave(item, it, note) } }, enabled = (qty.toIntOrNull() ?: 0) > 0) { Text(tr("Save", "सहेजें")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "रद्द करें")) } })
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
private fun RegistersTab(hindi: Boolean, data: RegistryData, visits: List<ArogyaVisit>, store: ArogyaRegistryStore, today: LocalDate, save: ((RegistryData) -> RegistryData) -> Boolean) {
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
                    tr("Drug Kit Stock Card — Annexure 5", "दवा किट स्टॉक कार्ड — Annexure 5") to { ArogyaForms.drugKitCard(data.stock) }).forEach { (label, make) ->
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
            }
            "lists" -> {
                val next = YearMonth.from(today).plusMonths(1)
                val imm = remember(data, next) { ArogyaRegistryLogic.immunizationDueList(data.beneficiaries, next, today) }
                val anc = remember(data) { ArogyaRegistryLogic.ancList(data.beneficiaries, today) }
                val ecs = remember(data) { ArogyaRegistryLogic.eligibleCouples(data.beneficiaries) }
                Text(tr("Due list of children to be immunized — ", "टीकाकरण बाकी बच्चों की सूची — ") + "$next (${imm.size})", fontWeight = FontWeight.Bold)
                imm.take(60).forEach { Text("${it.name} (${it.village}) — ${it.label.removePrefix("Vaccines — ")} · " + tr("due ", "तारीख़ ") + it.due + if (it.overdue(today)) tr(" · PENDING", " · बाकी") else "", style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = { share("Immunization-due-list-$next.csv", "name,village,vaccines,due,pending\n" + imm.joinToString("\n") { "\"${it.name}\",\"${it.village}\",\"${it.label.removePrefix("Vaccines — ")}\",${it.due},${if (it.overdue(today)) "yes" else ""}" }) }) { Text(tr("Share immunization due list", "टीकाकरण सूची भेजें")) }
                Text(tr("ANC beneficiaries (", "ANC लाभार्थी (") + anc.size + ")", fontWeight = FontWeight.Bold)
                anc.forEach { (b, info) -> Text("${b.name} (${b.village}) — $info", style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = { share("ANC-list-$today.csv", "name,village,status\n" + anc.joinToString("\n") { "\"${it.first.name}\",\"${it.first.village}\",\"${it.second}\"" }) }) { Text(tr("Share ANC list", "ANC सूची भेजें")) }
                Text(tr("Eligible couples (", "योग्य दंपति (") + ecs.size + ")", fontWeight = FontWeight.Bold)
                ecs.take(60).forEach { Text("${it.name} (${it.village}) — " + tr("method: ", "साधन: ") + it.familyPlanning.ifBlank { tr("not recorded", "दर्ज नहीं") }, style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = { share("Eligible-couples-$today.csv", "name,village,method\n" + ecs.joinToString("\n") { "\"${it.name}\",\"${it.village}\",\"${it.familyPlanning}\"" }) }) { Text(tr("Share eligible couple list", "दंपति सूची भेजें")) }
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
                Text(tr("Activity diary — visits are added automatically from completed visits", "कार्य डायरी — पूरी विज़िट अपने आप जुड़ती हैं"), fontWeight = FontWeight.Bold)
                Button(onClick = { diaryOpen = true }) { Text(tr("+ Add activity (VHND, meeting, escort…)", "+ काम जोड़ें (VHND, बैठक…)")) }
                val auto = visits.filter { it.completed }.map { Triple(it.date, tr("Home visit (", "घर विज़िट (") + it.kind.title + ")", it.name) }
                val manual = data.diary.map { Triple(it.date, if (hindi) it.kind.titleHi else it.kind.title, names[it.beneficiaryId].orEmpty() + if (it.note.isNotBlank()) " — ${it.note}" else "") }
                (auto + manual).sortedByDescending { it.first }.take(200).forEach { (d, k, n) -> Text("$d · $k · $n", style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = {
                    val visitRows = visits.filter { it.completed }.map { DiaryEntry(id = it.id, date = it.date, kind = DiaryKind.HOME_VISIT, beneficiaryId = it.personId, note = it.kind.title) }
                    share("Activity-diary-$today.csv", ArogyaRegistryLogic.diaryCsv(data.diary + visitRows, names + visits.associate { it.personId to it.name }))
                }) { Text(tr("Export diary (CSV)", "डायरी भेजें (CSV)")) }
            }
            "drugs" -> {
                Text(tr("Drug kit — current stock", "दवा किट — अभी का स्टॉक"), fontWeight = FontWeight.Bold)
                val bal = ArogyaRegistryLogic.stockBalance(data.stock)
                ArogyaRegistryLogic.DRUG_KIT.forEach { i -> Text("$i: ${bal[i] ?: 0}", color = if ((bal[i] ?: 0) <= 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { receiveOpen = true }) { Text(tr("+ Stock received", "+ स्टॉक मिला")) }
                    OutlinedButton(onClick = { samOpen = true }) { Text(tr("📷 Count from photo", "📷 फ़ोटो से गिनें")) }
                }
                Text(tr("Give to a person from their MCP card.", "किसी को उनके MCP कार्ड से दें।"), style = MaterialTheme.typography.bodySmall)
                data.stock.sortedByDescending { it.date }.take(100).forEach { t -> Text("${t.date} · ${t.item} · ${if (t.qty > 0) "+${t.qty} " + tr("received", "मिला") else "${-t.qty} " + tr("given", "दिया")} ${names[t.beneficiaryId]?.let { tr("to ", "को ") + it } ?: ""}", style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = { share("Drug-register-$today.csv", ArogyaRegistryLogic.stockCsv(data.stock, names)) }) { Text(tr("Export drug register (CSV)", "दवा रजिस्टर भेजें (CSV)")) }
            }
            "vitals" -> {
                Text(tr("Vital events & surveillance", "घटनाएँ और निगरानी"), fontWeight = FontWeight.Bold)
                Button(onClick = { eventOpen = true }) { Text(tr("+ Record event", "+ घटना दर्ज करें")) }
                data.vitals.sortedByDescending { it.date }.take(200).forEach { v -> Text("${v.date} · " + (if (hindi) v.kind.titleHi else v.kind.title) + " · ${names[v.beneficiaryId].orEmpty()} ${v.place} ${v.note}", style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = { share("Vital-events-$today.csv", ArogyaRegistryLogic.vitalsCsv(data.vitals, names)) }) { Text(tr("Export (CSV)", "भेजें (CSV)")) }
            }
            "report" -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { month = month.minusMonths(1) }) { Text("◀") }
                    Text(month.toString(), fontWeight = FontWeight.Bold)
                    TextButton(onClick = { month = month.plusMonths(1) }, enabled = month < YearMonth.now()) { Text("▶") }
                }
                val rep = remember(data, visits, month) { ArogyaRegistryLogic.monthlyReport(data, visits, month) }
                val inc = remember(data, visits, month) { ArogyaRegistryLogic.incentiveEstimate(data, visits, month) }
                val claim = rep.text + "\n\nINCENTIVE ESTIMATE (national list in your guide; state rates may differ — verify with ANM):\n" +
                    inc.lines.filter { it.count > 0 }.joinToString("\n") { "${it.item}: ${it.count} × Rs ${it.rate} = Rs ${it.amount}" } + "\nEstimated total: Rs ${inc.total}"
                Card(Modifier.fillMaxWidth()) { Text(claim, Modifier.padding(12.dp)) }
                Button(onClick = { ArogyaShare.shareText(context, "ASHA monthly summary $month", claim) }) { Text(tr("Share summary & claim", "सारांश और दावा भेजें")) }
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
    if (samOpen) SamCountDialog(hindi, ArogyaRegistryLogic.stockBalance(data.stock), onRecord = { item, counted, note ->
        val diff = counted - (ArogyaRegistryLogic.stockBalance(data.stock)[item] ?: 0)
        if (diff == 0 || save { r -> r.copy(stock = r.stock + StockTxn(item = item, qty = diff, note = "$note: counted $counted")) }) samOpen = false
    }, onDismiss = { samOpen = false })
    if (receiveOpen) StockDialog(hindi, tr("Stock received", "स्टॉक मिला"), give = false, onDismiss = { receiveOpen = false }) { item, qty, note ->
        if (save { r -> r.copy(stock = r.stock + StockTxn(item = item, qty = qty, note = note)) }) receiveOpen = false }
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
            OutlinedButton(onClick = { runCatching { store.loadSyntheticPatients(1000) }.onSuccess { status = tr("Added 1000 synthetic patients (marked DEMO).", "1000 काल्पनिक लोग जोड़े (DEMO)।") } }, enabled = !busy) { Text(tr("Add 1000 synthetic", "1000 काल्पनिक जोड़ें")) }
            OutlinedButton(onClick = { runCatching { store.removeSynthetic() }.onSuccess { status = tr("Removed synthetic patients.", "काल्पनिक लोग हटाए।") } }, enabled = !busy) { Text(tr("Remove synthetic", "काल्पनिक हटाएँ")) }
        }
    }
}
