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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Small "✨ AI" badge wherever AI is used; tap shows which model powers it (exactly as implemented). */
@Composable
fun AiBadge(feature: String, modifier: Modifier = Modifier) {
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
            Text("You check and approve everything the AI suggests.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text("OK") } })
}

/** Every AI feature in the app, the model behind it, and the live state of the phone's model. */
@Composable
fun ArogyaAiPage(modelStatus: String, onlineStatus: String, onDismiss: () -> Unit, ai: ArogyaVisitAi? = null) {
    var cloudOpen by rememberSaveable { mutableStateOf(false) }
    if (cloudOpen) ArogyaCloudSettingsDialog { cloudOpen = false }
    val benchScope = rememberCoroutineScope()
    var benchLog by remember { mutableStateOf("") }
    var benchBusy by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("✨ AI in ArogyaDoot") }, text = {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) { Column(Modifier.padding(10.dp)) {
                Text("On this phone now", fontWeight = FontWeight.Bold); Text(modelStatus, style = MaterialTheme.typography.bodySmall); Text(onlineStatus, style = MaterialTheme.typography.bodySmall)
            } }
            Button(onClick = { cloudOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("☁ Cloud AI (Meta) — keys and models") }
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
                }, modifier = Modifier.fillMaxWidth()) { Text(if (benchBusy) "Stop benchmark" else "Run M1 benchmark (19 questions, test aid)") }
                if (benchLog.isNotBlank()) Text(benchLog.trim(), style = MaterialTheme.typography.bodySmall)
            }
            ArogyaAiCatalog.features.forEach { f ->
                Column {
                    Text((if (f.metaModel) "Ⓜ " else "• ") + f.feature, fontWeight = FontWeight.Bold)
                    Text(f.model + " — " + f.maker, style = MaterialTheme.typography.bodySmall, color = if (f.metaModel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(f.whatAiDoes + " · " + f.where, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("Ⓜ = Meta model.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
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
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(sub, style = MaterialTheme.typography.bodySmall)
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
fun ArogyaRegistryHub(initial: String, visits: List<ArogyaVisit>, queue: ArogyaVisitQueue, onClose: () -> Unit, ai: ArogyaVisitAi? = null, onStartVisit: (Beneficiary) -> Unit) {
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
                    TextButton(onClick = onClose) { Text("‹ Home") }
                    Text(when (tab) { "due" -> "Today's visits"; "patients" -> "People"; "registers" -> "Registers"; else -> "Backup" },
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                }
                if (!store.loaded) { LinearProgressIndicator(Modifier.fillMaxWidth()); if (store.error.isNotBlank()) Text(store.error, color = MaterialTheme.colorScheme.error) }
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        "due" -> DueTab(data, visits, today, onVisit = { id -> data.beneficiaries.firstOrNull { it.id == id }?.let(onStartVisit) }) { openPatient = it }
                        "patients" -> PatientsTab(data, visits, today, onOpen = { openPatient = it }, onAdd = { editPatient = Beneficiary() })
                        "registers" -> RegistersTab(data, visits, store, today) { c -> save(c) }
                        else -> BackupTab(store, queue)
                    }
                }
            }
        }
    }
    openPatient?.let { id -> data.beneficiaries.firstOrNull { it.id == id }?.let { b ->
        McpCardDialog(b, data, visits, today, ai, onClose = { openPatient = null }, onEdit = { editPatient = b },
            onStartVisit = { openPatient = null; onStartVisit(b) }, save = { c -> save(c) })
    } }
    editPatient?.let { b -> PatientForm(b, onDismiss = { editPatient = null }) { updated ->
        if (save { r -> if (r.beneficiaries.any { it.id == updated.id }) r.copy(beneficiaries = r.beneficiaries.map { if (it.id == updated.id) updated.copy(updatedAt = System.currentTimeMillis()) else it })
                else r.copy(beneficiaries = r.beneficiaries + updated) }) { editPatient = null; openPatient = updated.id }
    } }
}

@Composable
private fun DueTab(data: RegistryData, visits: List<ArogyaVisit>, today: LocalDate, onVisit: (String) -> Unit, onOpen: (String) -> Unit) {
    var days by remember { mutableStateOf(7L) }
    val due = remember(data, visits, days) { ArogyaSchedule.dueList(data.beneficiaries, visits, today, days) }
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0L to "Today", 7L to "This week", 30L to "This month").forEach { (d, l) -> FilterChip(selected = days == d, onClick = { days = d }, label = { Text(l) }) }
        }
        if (due.isEmpty()) Text("Nothing due. Add patients with dates of birth or LMP to build the schedule.", Modifier.padding(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(due, key = { it.beneficiaryId + it.label }) { item ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(item.beneficiaryId) },
                    colors = if (item.overdue(today)) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer) else CardDefaults.cardColors()) {
                    Column(Modifier.padding(10.dp)) {
                        Text("${item.name} · ${item.village}", fontWeight = FontWeight.Bold)
                        Text(item.label)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text((if (item.overdue(today)) "OVERDUE — window ended ${item.windowEnd}" else if (item.due <= today) "Due now (until ${item.windowEnd})" else "Due ${item.due}"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Button(onClick = { onVisit(item.beneficiaryId) }) { Text("Start visit") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PatientsTab(data: RegistryData, visits: List<ArogyaVisit>, today: LocalDate, onOpen: (String) -> Unit, onAdd: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("All") }
    var byHouse by remember { mutableStateOf(true) }
    val groups = listOf("All", "Pregnant women", "Infants (0–1 year)", "Children (1–6 years)", "Eligible couples", "High risk")
    val shown = remember(data, query, group) {
        data.beneficiaries.filter { it.active }.filter { b -> query.isBlank() || listOf(b.name, b.relativeName, b.household, b.village, b.rchId, b.phone).any { it.contains(query, true) } }
            .filter { b -> group == "All" || (group == "High risk" && ArogyaRegistryLogic.isHighRisk(b)) || ArogyaRegistryLogic.rchGroup(b, today) == group }
            .sortedBy { it.name.lowercase() }
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it }, placeholder = { Text("Search") }, singleLine = true, modifier = Modifier.weight(1f))
            Button(onClick = onAdd, modifier = Modifier.padding(start = 6.dp)) { Text("+ Add") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            groups.forEach { g -> FilterChip(selected = group == g, onClick = { group = g }, label = { Text(g) }) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${shown.size} of ${data.beneficiaries.count { it.active }} people", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { byHouse = !byHouse }) { Text(if (byHouse) "Show as list" else "Show by household") }
        }
        if (data.beneficiaries.isEmpty()) Text("No people yet. Tap + Add for each family member from your household survey — or go back to Home and tap “Load sample village (50 people)” to try the app with fictional data.", Modifier.padding(8.dp))
        val rows: List<Pair<String?, Beneficiary>> = if (!byHouse) shown.map { null to it } else ArogyaRegistryLogic.byHousehold(shown).entries.sortedBy { it.key }.flatMap { (h, l) -> l.mapIndexed { i, b -> (if (i == 0) h else null) to b } }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(rows, key = { it.second.id }) { (header, b) ->
                if (header != null) Text("🏠 $header", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                Card(Modifier.fillMaxWidth().clickable { onOpen(b.id) }) {
                    Column(Modifier.padding(10.dp)) {
                        Text((if (b.demo) "[DEMO] " else "") + b.name + if (ArogyaRegistryLogic.isHighRisk(b)) "  ⚠ high risk" else "", fontWeight = FontWeight.Bold)
                        Text("${ArogyaRegistryLogic.rchGroup(b, today)} · " + (if (b.household.isNotBlank()) "${b.household} · " else "") + b.village + (ArogyaRegistryLogic.edd(b)?.let { " · EDD $it" } ?: "") + (if (b.dob.isNotBlank()) " · DOB ${b.dob}" else ""),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun PatientForm(initial: Beneficiary, onDismiss: () -> Unit, onSave: (Beneficiary) -> Unit) {
    var b by remember { mutableStateOf(initial) }
    var weight by remember { mutableStateOf(initial.birthWeightKg?.toString().orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (initial.name.isBlank()) "Add patient" else "Edit patient") }, text = {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Use fictional names in demos.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(b.name, { b = b.copy(name = it.take(80)) }, label = { Text("Name *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(b.relativeName, { b = b.copy(relativeName = it.take(80)) }, label = { Text("Husband / mother / father") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(b.household, { b = b.copy(household = it.take(60)) }, label = { Text("Household (house no. / family head) — from line listing") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(b.village, { b = b.copy(village = it.take(80)) }, label = { Text("Village / hamlet") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (b.category != BenCategory.CHILD) OutlinedTextField(b.age, { b = b.copy(age = it.filter { c -> c.isDigit() }.take(3)) }, label = { Text("Age (years)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(b.phone, { b = b.copy(phone = it.filter { c -> c.isDigit() || c == '+' }.take(15)) }, label = { Text("Phone") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(b.rchId, { b = b.copy(rchId = it.take(30)) }, label = { Text("RCH ID (if any)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Register as")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BenCategory.values().forEach { c -> FilterChip(selected = b.category == c, onClick = { b = b.copy(category = c, sex = if (c == BenCategory.CHILD) b.sex else "F") }, label = { Text(c.title) }) }
            }
            if (b.category == BenCategory.CHILD) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("F" to "Girl", "M" to "Boy").forEach { (s, l) -> FilterChip(selected = b.sex == s, onClick = { b = b.copy(sex = s) }, label = { Text(l) }) } }
                DateBox("Date of birth *", b.dob, Modifier.fillMaxWidth()) { b = b.copy(dob = it) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("INSTITUTION" to "Hospital birth", "HOME" to "Home birth").forEach { (p, l) -> FilterChip(selected = b.deliveryPlace == p, onClick = { b = b.copy(deliveryPlace = p) }, label = { Text(l) }) } }
                OutlinedTextField(weight, { weight = it.filter { c -> c.isDigit() || c == '.' }.take(5) }, label = { Text("Birth weight (kg)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(b.preterm, { b = b.copy(preterm = it) }); Text("Born preterm") }
                DateBox("SNCU/NBSU discharge (if admitted)", b.sncuDischarge, Modifier.fillMaxWidth()) { b = b.copy(sncuDischarge = it) }
                if (b.sncuDischarge.isNotBlank()) TextButton(onClick = { b = b.copy(sncuDischarge = "") }) { Text("Clear discharge date") }
            }
            if (b.category == BenCategory.PREGNANT) {
                DateBox("LMP *", b.lmp, Modifier.fillMaxWidth()) { b = b.copy(lmp = it) }
                ArogyaRegistryLogic.edd(b)?.let { Text("EDD: $it") }
                DateBox("Delivery date (after birth)", b.deliveryDate, Modifier.fillMaxWidth()) { b = b.copy(deliveryDate = it) }
            }
            if (b.category == BenCategory.ELIGIBLE_COUPLE || b.category == BenCategory.MOTHER)
                OutlinedTextField(b.familyPlanning, { b = b.copy(familyPlanning = it.take(60)) }, label = { Text("Family planning method") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(b.notes, { b = b.copy(notes = it.take(1000)) }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = {
        val ok = b.name.isNotBlank() && (b.category != BenCategory.CHILD || b.dob.isNotBlank()) && (b.category != BenCategory.PREGNANT || b.lmp.isNotBlank())
        Button(onClick = { onSave(b.copy(birthWeightKg = weight.toDoubleOrNull()?.takeIf { it in 0.3..8.0 })) }, enabled = ok) { Text("Save") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun McpCardDialog(b: Beneficiary, data: RegistryData, visits: List<ArogyaVisit>, today: LocalDate, ai: ArogyaVisitAi?, onClose: () -> Unit, onEdit: () -> Unit,
                          onStartVisit: () -> Unit, save: ((RegistryData) -> RegistryData) -> Boolean) {
    fun updateBen(f: (Beneficiary) -> Beneficiary) = save { r -> r.copy(beneficiaries = r.beneficiaries.map { if (it.id == b.id) f(it).copy(updatedAt = System.currentTimeMillis()) else it }) }
    var giveOpen by remember { mutableStateOf(false) }
    var eventOpen by remember { mutableStateOf(false) }
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
                Text("MCP card · " + b.name, style = MaterialTheme.typography.titleLarge)
                Text(listOf(ArogyaRegistryLogic.rchGroup(b, today), b.village, b.relativeName.takeIf { it.isNotBlank() }?.let { "c/o $it" }, b.rchId.takeIf { it.isNotBlank() }?.let { "RCH $it" })
                    .filterNotNull().filter { it.isNotBlank() }.joinToString(" · "))
                if (ArogyaRegistryLogic.isHighRisk(b)) Text("⚠ High risk (LBW / preterm / SNCU) — extra follow-up visits", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                if (b.dob.isNotBlank()) Text("DOB ${b.dob}" + (b.birthWeightKg?.let { " · birth weight $it kg" } ?: "") + (if (b.deliveryPlace.isNotBlank()) " · ${b.deliveryPlace.lowercase()} birth" else ""))
                ArogyaRegistryLogic.edd(b)?.let { Text("LMP ${b.lmp} · EDD $it", fontWeight = FontWeight.Bold) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onStartVisit, modifier = Modifier.weight(1f)) { Text("Start visit") }
                    OutlinedButton(onClick = onEdit) { Text("Edit") }
                }
                if (ai != null) Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Text("📷 Read the paper MCP card", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); AiBadge("Read MCP card photo") }
                        Text("Photograph the family's card; dates, vaccines and weights are filled here after you tick them.", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { camPerm.launch(android.Manifest.permission.CAMERA) }, enabled = !readBusy) { Text("Take photo") }
                            OutlinedButton(onClick = { pickCard.launch(arrayOf("image/*", "application/pdf")) }, enabled = !readBusy) { Text("Choose file") }
                        }
                        if (readBusy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Reading the card on this phone…", style = MaterialTheme.typography.bodySmall) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = !updateMode, onClick = { updateMode = false }, label = { Text("MCP card") })
                    FilterChip(selected = updateMode, onClick = { updateMode = true }, label = { Text("Update (tick what was done)") })
                }
                if (!updateMode) {
                    Text("Layout of the official Mother and Child Protection Card (MoHFW/MWCD 2018). Values are this register's data.", style = MaterialTheme.typography.bodySmall)
                    official.forEach { (title, lines) ->
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp)) {
                            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                            lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.contains("missed") || it.endsWith("YES")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
                        } }
                    }
                    OutlinedButton(onClick = { if (!ArogyaRegistryStore.get(context).shareCardPdf(b.name, official)) Toast.makeText(context, "Could not create the PDF", Toast.LENGTH_LONG).show() }) { Text("Share MCP card (PDF)") }
                    val filled = ArogyaOfficialForms.ALL.filter { sp -> data.forms.any { it.beneficiaryId == b.id && it.formId == sp.id } }
                    filled.forEach { sp ->
                        Text("Visit form — filled over visits", fontWeight = FontWeight.Bold)
                        val t = ArogyaOfficialForms.table(sp, b, data.forms)
                        OfficialFormGrid(t, null)
                        OutlinedButton(onClick = { if (!ArogyaRegistryStore.get(context).shareFormPdf(t)) Toast.makeText(context, "Could not create the PDF", Toast.LENGTH_LONG).show() }) { Text("Share this form (PDF)") }
                    }
                    if (filled.none { it.id == "annex9" } && b.category == BenCategory.CHILD && b.dob.isNotBlank()) OutlinedButton(onClick = { ArogyaRegistryStore.get(context).shareFormPdf(ArogyaForms.homeVisitForm(b, visits)) }) { Text("Share Home Visit Form (Annexure 9, PDF)") }
                }
                if (updateMode) {
                Text("Schedule", fontWeight = FontWeight.Bold)
                if (schedule.isEmpty()) Text("Add date of birth (child) or LMP (pregnant woman) to see the schedule.", style = MaterialTheme.typography.bodySmall)
                schedule.filter { it.kind != "VACCINE" }.forEach { s ->
                    Text((if (s.done) "✓ " else if (s.overdue(today)) "✗ " else "○ ") + "${s.label} — ${s.due}" + (if (s.done) " (done ${s.doneDate})" else if (s.overdue(today)) " MISSED" else ""),
                        color = if (s.overdue(today)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                }
                if (b.category == BenCategory.PREGNANT) {
                    Text("ANC check-ups (tick when done today)", fontWeight = FontWeight.Bold)
                    (1..4).forEach { n -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(b.mcp.anc[n] != null, { c -> if (updateBen { x -> x.copy(mcp = x.mcp.copy(anc = if (c) x.mcp.anc + (n to today.toString()) else x.mcp.anc - n)) } && c) ancEdit = n })
                        Text("ANC $n" + (b.mcp.anc[n]?.let { " — $it" } ?: "") + (b.mcp.ancDetails[n]?.let { " · ${it.weightKg} kg · BP ${it.bp} · Hb ${it.hb}" } ?: ""), modifier = Modifier.weight(1f))
                        if (b.mcp.anc[n] != null) TextButton(onClick = { ancEdit = n }) { Text("Details") }
                    } }
                }
                if (b.category == BenCategory.CHILD) {
                    Text("Vaccines (tick when given today)", fontWeight = FontWeight.Bold)
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
                    Text("Weight", fontWeight = FontWeight.Bold)
                    b.mcp.weights.sortedBy { it.date }.forEach { Text("${it.date}: ${it.kg} kg", style = MaterialTheme.typography.bodySmall) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(kg, { kg = it.filter { c -> c.isDigit() || c == '.' }.take(5) }, label = { Text("kg today") }, singleLine = true, modifier = Modifier.weight(1f))
                        TextButton(onClick = { kg.toDoubleOrNull()?.takeIf { it in 0.3..40.0 }?.let { w -> if (updateBen { x -> x.copy(mcp = x.mcp.copy(weights = x.mcp.weights + WeightEntry(today.toString(), w))) }) kg = "" } }) { Text("Add") }
                    }
                }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { giveOpen = true }) { Text("Give from drug kit") }
                    OutlinedButton(onClick = { eventOpen = true }) { Text("Record event") }
                }
                val myVisits = visits.filter { it.personId == b.id }.sortedByDescending { it.date }
                if (myVisits.isNotEmpty()) { Text("Visits", fontWeight = FontWeight.Bold); myVisits.take(10).forEach { Text("${it.date} · ${it.kind.title} · ${if (it.completed) "completed" else "draft"}", style = MaterialTheme.typography.bodySmall) } }
                TextButton(onClick = { if (updateBen { it.copy(active = false) }) onClose() }) { Text("Remove from active list") }
                TextButton(onClick = onClose) { Text("Close") }
            }
        }
    }
    reading?.let { r -> AlertDialog(onDismissRequest = { reading = null }, title = { Row(verticalAlignment = Alignment.CenterVertically) { Text("Found on the card", modifier = Modifier.weight(1f)); AiBadge("Read MCP card photo") } }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (r.fields.isEmpty()) Text("Nothing could be confirmed from this photo. Take a closer, brighter photo with the card flat.")
            else Text("Untick anything that does not match the card.", style = MaterialTheme.typography.bodySmall)
            r.fields.forEachIndexed { i, f -> Row(verticalAlignment = Alignment.Top) {
                Checkbox(i in picked, { c -> picked = if (c) picked + i else picked - i })
                Column(Modifier.weight(1f)) { Text("${f.label}: ${f.value}", fontWeight = FontWeight.Bold); Text("On card: " + f.evidence.take(100), style = MaterialTheme.typography.bodySmall) }
            } }
        }
    }, confirmButton = { if (r.fields.isNotEmpty()) Button(onClick = {
        val chosen = r.fields.filterIndexed { i, _ -> i in picked }
        if (updateBen { x -> ArogyaCardApply.apply(x, chosen) }) { Toast.makeText(context, "Added ${chosen.size} details to the MCP card", Toast.LENGTH_SHORT).show(); reading = null }
    }, enabled = picked.isNotEmpty()) { Text("Add to MCP card") } }, dismissButton = { TextButton(onClick = { reading = null }) { Text("Close") } }) }
    ancEdit?.let { n ->
        val cur = b.mcp.ancDetails[n] ?: AncDetail()
        var w by remember(n) { mutableStateOf(cur.weightKg) }; var bp by remember(n) { mutableStateOf(cur.bp) }; var hb by remember(n) { mutableStateOf(cur.hb) }
        AlertDialog(onDismissRequest = { ancEdit = null }, title = { Text("ANC $n — as on the MCP card") }, text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(w, { w = it.filter { c -> c.isDigit() || c == '.' }.take(5) }, label = { Text("Weight (kg)") }, singleLine = true)
            OutlinedTextField(bp, { bp = it.filter { c -> c.isDigit() || c == '/' }.take(7) }, label = { Text("Blood pressure (e.g. 110/70)") }, singleLine = true)
            OutlinedTextField(hb, { hb = it.filter { c -> c.isDigit() || c == '.' }.take(4) }, label = { Text("Haemoglobin (g/dl)") }, singleLine = true)
            Text("Copy from the ANM's entry on the card.", style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { Button(onClick = { if (updateBen { x -> x.copy(mcp = x.mcp.copy(ancDetails = x.mcp.ancDetails + (n to AncDetail(w, bp, hb)))) }) ancEdit = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { ancEdit = null }) { Text("Cancel") } })
    }
    if (giveOpen) StockDialog(title = "Give to ${b.name}", give = true, onDismiss = { giveOpen = false }) { item, qty, note ->
        if (save { r -> r.copy(stock = r.stock + StockTxn(item = item, qty = -qty, beneficiaryId = b.id, note = note)) }) giveOpen = false }
    if (eventOpen) EventDialog(onDismiss = { eventOpen = false }) { kind, place, note ->
        if (save { r -> r.copy(vitals = r.vitals + VitalEvent(kind = kind, beneficiaryId = b.id, place = place, note = note)) }) eventOpen = false }
}

@Composable
private fun StockDialog(title: String, give: Boolean, onDismiss: () -> Unit, onSave: (String, Int, String) -> Unit) {
    var item by remember { mutableStateOf(ArogyaRegistryLogic.DRUG_KIT.first()) }
    var qty by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ArogyaRegistryLogic.DRUG_KIT.forEach { i -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(item == i, { item = i }); Text(i) } }
            OutlinedTextField(qty, { qty = it.filter { c -> c.isDigit() }.take(5) }, label = { Text(if (give) "Quantity given" else "Quantity received") }, singleLine = true)
            OutlinedTextField(note, { note = it.take(200) }, label = { Text("Note") }, singleLine = true)
        }
    }, confirmButton = { Button(onClick = { qty.toIntOrNull()?.takeIf { it > 0 }?.let { onSave(item, it, note) } }, enabled = (qty.toIntOrNull() ?: 0) > 0) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun EventDialog(onDismiss: () -> Unit, onSave: (VitalKind, String, String) -> Unit) {
    var kind by remember { mutableStateOf(VitalKind.BIRTH) }
    var place by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Vital event / surveillance") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            VitalKind.values().forEach { k -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(kind == k, { kind = k }); Text(k.title) } }
            OutlinedTextField(place, { place = it.take(80) }, label = { Text("Place") }, singleLine = true)
            OutlinedTextField(note, { note = it.take(300) }, label = { Text("Note") })
            Text("Deaths and AEFI must also be reported to your ANM / MO immediately.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { Button(onClick = { onSave(kind, place, note) }) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun RegistersTab(data: RegistryData, visits: List<ArogyaVisit>, store: ArogyaRegistryStore, today: LocalDate, save: ((RegistryData) -> RegistryData) -> Boolean) {
    val context = LocalContext.current
    var sub by remember { mutableStateOf("") }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var diaryOpen by remember { mutableStateOf(false) }
    var receiveOpen by rememberSaveable { mutableStateOf(false) }
    var samOpen by rememberSaveable { mutableStateOf(false) }
    var eventOpen by remember { mutableStateOf(false) }
    val names = remember(data) { data.beneficiaries.associate { it.id to it.name } }
    fun share(name: String, text: String) { if (!store.shareFile(name, "text/csv", text.toByteArray())) Toast.makeText(context, "Could not save the file.", Toast.LENGTH_LONG).show() }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val pages = listOf("forms" to ("📄 Official forms (print / share)" to "RI Form 3 & 4, Home Visit Form, Drug Kit Stock Card"),
            "report" to ("💰 Monthly report & claim" to "What to submit this month"), "lists" to ("📋 Monthly lists" to "Immunization due · ANC · eligible couples"),
            "diary" to ("📓 Activity diary" to "Visits are added automatically"), "drugs" to ("💊 Drug kit" to "Stock and what you gave"),
            "vitals" to ("📍 Births, deaths & cases" to "Vital events and surveillance"), "vhr" to ("🏘 Village health register" to "Households and people"),
            "rch" to ("📤 RCH register export" to "For your ANM / RCH portal"))
        if (sub.isBlank()) pages.forEach { (id, t) ->
            Card(Modifier.fillMaxWidth().clickable { sub = id }) { Column(Modifier.padding(14.dp)) { Text(t.first, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Text(t.second, style = MaterialTheme.typography.bodySmall) } }
        } else TextButton(onClick = { sub = "" }) { Text("‹ All registers") }
        when (sub) {
            "" -> {}
            "forms" -> {
                var form by remember { mutableStateOf<FormTable?>(null) }
                val babies = data.beneficiaries.filter { it.active && it.category == BenCategory.CHILD && (ArogyaRegistryLogic.ageDays(it, today) ?: 999) <= 60 }
                Text("Official formats filled with this register's data. Share as PDF to print or send to your ANM.", style = MaterialTheme.typography.bodySmall)
                listOf("House to House Survey — RI Form 3" to { ArogyaForms.riForm3(data.beneficiaries, today) }, "Pregnant Women Listing — RI Form 4" to { ArogyaForms.riForm4(data.beneficiaries, today) },
                    "Drug Kit Stock Card — Annexure 5" to { ArogyaForms.drugKitCard(data.stock) }).forEach { (label, make) ->
                    OutlinedButton(onClick = { form = make() }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                }
                babies.filter { nb -> data.forms.none { it.beneficiaryId == nb.id && it.formId == "annex9" } }.forEach { nb -> OutlinedButton(onClick = { form = if (data.forms.any { it.beneficiaryId == nb.id && it.formId == "annex9" }) ArogyaOfficialForms.table(ArogyaOfficialForms.ANNEX9, nb, data.forms) else ArogyaForms.homeVisitForm(nb, visits) }, modifier = Modifier.fillMaxWidth()) { Text("Home Visit Form (Annexure 9) — ${nb.name}") } }
                val filledForms = data.forms.groupBy { it.beneficiaryId to it.formId }.entries.mapNotNull { (k, es) -> val b = data.beneficiaries.firstOrNull { it.id == k.first }; val sp = ArogyaOfficialForms.byId(k.second); if (b == null || sp == null) null else Triple(b, sp, es.size) }
                    .sortedWith(compareBy({ ArogyaOfficialForms.ALL.indexOf(it.second) }, { it.first.name }))
                if (filledForms.isNotEmpty()) Text("Visit forms filled from home visits (${filledForms.size})", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                filledForms.forEach { (b, sp, n) -> OutlinedButton(onClick = { form = ArogyaOfficialForms.table(sp, b, data.forms) }, modifier = Modifier.fillMaxWidth()) {
                    Text("${sp.title.substringBefore(" —").substringBefore(" (")} — ${b.name} · $n visit${if (n == 1) "" else "s"}", maxLines = 2) } }
                form?.let { f ->
                    Text(f.title, fontWeight = FontWeight.Bold); Text("Format: ${f.source}", style = MaterialTheme.typography.bodySmall)
                    Column(Modifier.horizontalScroll(rememberScrollState())) {
                        val w = f.headers.mapIndexed { i, hd -> if (i == 0 || hd.contains("Name") || hd.contains("Ask")) 170.dp else 84.dp }
                        Row { f.headers.forEachIndexed { i, hd -> Text(hd, Modifier.width(w[i]).padding(2.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall) } }
                        HorizontalDivider()
                        f.rows.take(80).forEach { r -> Row { r.forEachIndexed { i, c -> Text(c, Modifier.width(w.getOrElse(i) { 84.dp }).padding(2.dp), style = MaterialTheme.typography.bodySmall, maxLines = 2) } }; HorizontalDivider() }
                    }
                    if (f.note.isNotBlank()) Text(f.note, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { if (!store.shareFormPdf(f)) Toast.makeText(context, "Could not create the PDF", Toast.LENGTH_LONG).show() }) { Text("Share as PDF") }
                }
            }
            "vhr" -> {
                val v = remember(data) { ArogyaRegistryLogic.villageRegister(data.beneficiaries, today) }
                Text("Village Health Register", fontWeight = FontWeight.Bold)
                Text("From your household line listing (do it at the start of the year, update every 6 months; births and deaths every month).", style = MaterialTheme.typography.bodySmall)
                Text("Households: ${v.households}   ·   People listed: ${v.people}   ·   High risk: ${v.highRisk}")
                v.byGroup.entries.sortedBy { it.key }.forEach { Text("${it.key}: ${it.value}") }
                val lastSurvey = data.diary.filter { it.kind == DiaryKind.SURVEY }.maxOfOrNull { it.date }
                Text("Last line listing recorded: " + (lastSurvey ?: "none — add it in the Activity diary"), color = if (lastSurvey == null || LocalDate.parse(lastSurvey).isBefore(today.minusMonths(6))) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                val births = data.vitals.count { it.kind == VitalKind.BIRTH && it.date.startsWith(YearMonth.from(today).toString()) }
                val deaths = data.vitals.count { it.kind.name.endsWith("DEATH") && it.date.startsWith(YearMonth.from(today).toString()) }
                Text("This month: births $births · deaths $deaths (record in Vital events)")
            }
            "lists" -> {
                val next = YearMonth.from(today).plusMonths(1)
                val imm = remember(data, next) { ArogyaRegistryLogic.immunizationDueList(data.beneficiaries, next, today) }
                val anc = remember(data) { ArogyaRegistryLogic.ancList(data.beneficiaries, today) }
                val ecs = remember(data) { ArogyaRegistryLogic.eligibleCouples(data.beneficiaries) }
                Text("Due list of children to be immunized — $next (${imm.size})", fontWeight = FontWeight.Bold)
                imm.take(60).forEach { Text("${it.name} (${it.village}) — ${it.label.removePrefix("Vaccines — ")} · due ${it.due}" + if (it.overdue(today)) " · PENDING" else "", style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = { share("Immunization-due-list-$next.csv", "name,village,vaccines,due,pending\n" + imm.joinToString("\n") { "\"${it.name}\",\"${it.village}\",\"${it.label.removePrefix("Vaccines — ")}\",${it.due},${if (it.overdue(today)) "yes" else ""}" }) }) { Text("Share immunization due list") }
                Text("ANC beneficiaries (${anc.size})", fontWeight = FontWeight.Bold)
                anc.forEach { (b, info) -> Text("${b.name} (${b.village}) — $info", style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = { share("ANC-list-$today.csv", "name,village,status\n" + anc.joinToString("\n") { "\"${it.first.name}\",\"${it.first.village}\",\"${it.second}\"" }) }) { Text("Share ANC list") }
                Text("Eligible couples (${ecs.size})", fontWeight = FontWeight.Bold)
                ecs.take(60).forEach { Text("${it.name} (${it.village}) — method: ${it.familyPlanning.ifBlank { "not recorded" }}", style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = { share("Eligible-couples-$today.csv", "name,village,method\n" + ecs.joinToString("\n") { "\"${it.name}\",\"${it.village}\",\"${it.familyPlanning}\"" }) }) { Text("Share eligible couple list") }
                Text("Share these with your ANM at the monthly meeting.", style = MaterialTheme.typography.bodySmall)
            }
            "rch" -> {
                val groups = data.beneficiaries.filter { it.active }.groupBy { ArogyaRegistryLogic.rchGroup(it, today) }
                Text("RCH register", fontWeight = FontWeight.Bold)
                groups.entries.sortedBy { it.key }.forEach { (g, l) -> Text("$g: ${l.size}" + (l.count { ArogyaRegistryLogic.isHighRisk(it) }.takeIf { it > 0 }?.let { " ($it high risk)" } ?: "")) }
                Button(onClick = { share("RCH-register-$today.csv", ArogyaRegistryLogic.rchCsv(data.beneficiaries.filter { it.active }, today)) }) { Text("Export RCH register (CSV)") }
                OutlinedButton(onClick = { share("Visits-$today.csv", ArogyaRegistryLogic.visitsCsv(visits)) }) { Text("Export visits (CSV)") }
                Text("For entry into the RCH portal / U-WIN by your ANM or data operator.", style = MaterialTheme.typography.bodySmall)
            }
            "diary" -> {
                Text("Activity diary — visits are added automatically from completed visits", fontWeight = FontWeight.Bold)
                Button(onClick = { diaryOpen = true }) { Text("+ Add activity (VHND, meeting, escort…)") }
                val auto = visits.filter { it.completed }.map { Triple(it.date, "Home visit (${it.kind.title})", it.name) }
                val manual = data.diary.map { Triple(it.date, it.kind.title, names[it.beneficiaryId].orEmpty() + if (it.note.isNotBlank()) " — ${it.note}" else "") }
                (auto + manual).sortedByDescending { it.first }.take(200).forEach { (d, k, n) -> Text("$d · $k · $n", style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = {
                    val visitRows = visits.filter { it.completed }.map { DiaryEntry(id = it.id, date = it.date, kind = DiaryKind.HOME_VISIT, beneficiaryId = it.personId, note = it.kind.title) }
                    share("Activity-diary-$today.csv", ArogyaRegistryLogic.diaryCsv(data.diary + visitRows, names + visits.associate { it.personId to it.name }))
                }) { Text("Export diary (CSV)") }
            }
            "drugs" -> {
                Text("Drug kit — current stock", fontWeight = FontWeight.Bold)
                val bal = ArogyaRegistryLogic.stockBalance(data.stock)
                ArogyaRegistryLogic.DRUG_KIT.forEach { i -> Text("$i: ${bal[i] ?: 0}", color = if ((bal[i] ?: 0) <= 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { receiveOpen = true }) { Text("+ Stock received") }
                    OutlinedButton(onClick = { samOpen = true }) { Text("📷 Count from photo") }
                }
                Text("Give to a person from their MCP card.", style = MaterialTheme.typography.bodySmall)
                data.stock.sortedByDescending { it.date }.take(100).forEach { t -> Text("${t.date} · ${t.item} · ${if (t.qty > 0) "+${t.qty} received" else "${-t.qty} given"} ${names[t.beneficiaryId]?.let { "to $it" } ?: ""}", style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = { share("Drug-register-$today.csv", ArogyaRegistryLogic.stockCsv(data.stock, names)) }) { Text("Export drug register (CSV)") }
            }
            "vitals" -> {
                Text("Vital events & surveillance", fontWeight = FontWeight.Bold)
                Button(onClick = { eventOpen = true }) { Text("+ Record event") }
                data.vitals.sortedByDescending { it.date }.take(200).forEach { v -> Text("${v.date} · ${v.kind.title} · ${names[v.beneficiaryId].orEmpty()} ${v.place} ${v.note}", style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = { share("Vital-events-$today.csv", ArogyaRegistryLogic.vitalsCsv(data.vitals, names)) }) { Text("Export (CSV)") }
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
                Button(onClick = { ArogyaShare.shareText(context, "ASHA monthly summary $month", claim) }) { Text("Share summary & claim") }
                Text("Use it to fill the monthly claim / RCH portal entry with your ANM. Check against your paper records.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (diaryOpen) {
        var kind by remember { mutableStateOf(DiaryKind.VHND) }
        var note by remember { mutableStateOf("") }
        var date by remember { mutableStateOf(today.toString()) }
        AlertDialog(onDismissRequest = { diaryOpen = false }, title = { Text("Add activity") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DiaryKind.values().forEach { k -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(kind == k, { kind = k }); Text(k.title) } }
                DateBox("Date", date) { date = it }
                OutlinedTextField(note, { note = it.take(300) }, label = { Text("Note") })
            }
        }, confirmButton = { Button(onClick = { if (save { r -> r.copy(diary = r.diary + DiaryEntry(date = date, kind = kind, note = note)) }) diaryOpen = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { diaryOpen = false }) { Text("Cancel") } })
    }
    if (samOpen) SamCountDialog(ArogyaRegistryLogic.stockBalance(data.stock), onRecord = { item, counted, note ->
        val diff = counted - (ArogyaRegistryLogic.stockBalance(data.stock)[item] ?: 0)
        if (diff == 0 || save { r -> r.copy(stock = r.stock + StockTxn(item = item, qty = diff, note = "$note: counted $counted")) }) samOpen = false
    }, onDismiss = { samOpen = false })
    if (receiveOpen) StockDialog("Stock received", give = false, onDismiss = { receiveOpen = false }) { item, qty, note ->
        if (save { r -> r.copy(stock = r.stock + StockTxn(item = item, qty = qty, note = note)) }) receiveOpen = false }
    if (eventOpen) EventDialog(onDismiss = { eventOpen = false }) { kind, place, note ->
        if (save { r -> r.copy(vitals = r.vitals + VitalEvent(kind = kind, place = place, note = note)) }) eventOpen = false }
}

@Composable
private fun BackupTab(store: ArogyaRegistryStore, queue: ArogyaVisitQueue) {
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
        Text("Backup — like WhatsApp backup", fontWeight = FontWeight.Bold)
        Text("All patients, MCP cards, registers and visits go into one file locked with your password. Choose Google Drive in the file picker to keep it in your Drive (it uploads when you are online). Without the password nobody — including you — can open it.", style = MaterialTheme.typography.bodySmall)
        if (!hasPw) {
            OutlinedTextField(pw1, { pw1 = it }, label = { Text("Backup password (8+ characters)") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pw2, { pw2 = it }, label = { Text("Repeat password") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Button(onClick = { store.savePassword(pw1); pw1 = ""; pw2 = ""; status = "Password saved on this phone (encrypted). Write it down somewhere safe." }, enabled = pw1.length >= 8 && pw1 == pw2) { Text("Save password") }
        } else Text("✓ Backup password saved", color = MaterialTheme.colorScheme.primary)
        Text("Location: " + (location?.let { it.authority?.let { a -> if (a.contains("google")) "Google Drive" else a } ?: it.toString() } ?: "not chosen"))
        Button(onClick = { create.launch("ArogyaDoot-backup.argb") }, enabled = hasPw && !busy, modifier = Modifier.fillMaxWidth()) { Text("Choose backup location (Google Drive)") }
        Button(onClick = { location?.let { u -> go { store.backupTo(u, store.password()) } } }, enabled = hasPw && location != null && !busy, modifier = Modifier.fillMaxWidth()) { Text("Back up now") }
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(auto, { auto = it; store.autoDaily = it }, enabled = hasPw && location != null); Text("  Back up automatically once a day (when the app is opened)") }
        if (store.lastBackup > 0) Text("Last backup: " + java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", java.util.Locale.getDefault()).format(java.util.Date(store.lastBackup)), style = MaterialTheme.typography.bodySmall)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("Restore", fontWeight = FontWeight.Bold)
        Text("On a new phone: install ArogyaDoot, open the backup file from Drive, enter the password. Registers are replaced; visits already on the phone are kept.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { open.launch(arrayOf("*/*")) }, enabled = !busy) { Text("Choose backup file") }
        restoreUri?.let { u ->
            OutlinedTextField(restorePw, { restorePw = it }, label = { Text("Backup password") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Button(onClick = { go { store.restore(u, restorePw, queue).also { restoreUri = null; restorePw = "" } } }, enabled = restorePw.isNotBlank() && !busy) { Text("Restore now") }
        }
        HorizontalDivider()
        Text("Demo / scale test", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { runCatching { store.loadSyntheticPatients(1000) }.onSuccess { status = "Added 1000 synthetic patients (marked DEMO)." } }, enabled = !busy) { Text("Add 1000 synthetic") }
            OutlinedButton(onClick = { runCatching { store.removeSynthetic() }.onSuccess { status = "Removed synthetic patients." } }, enabled = !busy) { Text("Remove synthetic") }
        }
    }
}
