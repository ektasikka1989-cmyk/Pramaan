package com.example.arfas

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Choose who you are visiting (search), or add a new person. */
@Composable
fun ArogyaPersonPicker(hindi: Boolean, people: List<Beneficiary>, due: List<DueItem>, onPick: (Beneficiary) -> Unit, onAdd: () -> Unit, onDismiss: () -> Unit) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    var q by remember { mutableStateOf("") }
    val dueIds = due.map { it.beneficiaryId }.toSet()
    val shown = people.filter { it.active && (q.isBlank() || listOf(it.name, it.household, it.village).any { f -> f.contains(q, true) }) }
        .sortedWith(compareBy({ it.id !in dueIds }, { it.name.lowercase() })).take(60)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(tr("Who are you visiting?", "किसकी विज़िट है?")) }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()).imePadding(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(q, { q = it }, placeholder = { Text(tr("Search name or house", "नाम या घर खोजें")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (people.isEmpty()) Text(tr("No people yet. Add one, or load the sample village from Home.", "अभी कोई नहीं। जोड़ें, या होम से नमूना गाँव लोड करें।"))
            shown.forEach { b -> Card(Modifier.fillMaxWidth().clickable { onPick(b) }) { Column(Modifier.padding(10.dp)) {
                Text(b.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(ArogyaGuided.kindFor(b).let { if (hindi) it.hi else it.title } + " · " + b.household.ifBlank { b.village } + if (b.id in dueIds) tr(" · due", " · बाकी") else "", style = MaterialTheme.typography.bodySmall)
            } } }
        }
    }, confirmButton = { TextButton(onClick = onAdd) { Text(tr("+ New person", "+ नया व्यक्ति")) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "रद्द करें")) } })
}

/** The official form as it looks on paper: rows × visit columns, earlier visits filled, today's column highlighted. */
@Composable
fun OfficialFormGrid(t: FormTable, highlight: Int?, sources: Map<String, String> = emptyMap(), hindi: Boolean = false) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    val ink = Color(0xFF1A2A4A); val paper = Color(0xFFFFFBF0); val line = Color(0xFF8C8C8C); val today = Color(0xFFFFF0B3)
    Column(Modifier.background(paper).border(1.dp, line).padding(6.dp)) {
        Text(t.title.substringBefore(" · "), fontWeight = FontWeight.Bold, color = ink, style = MaterialTheme.typography.titleSmall)
        Text(t.source, color = ink, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(4.dp))
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            val w = t.headers.mapIndexed { i, _ -> if (i == 0) 190.dp else 78.dp }
            @Composable fun cell(text: String, i: Int, bold: Boolean, col: Int?) {
                val bg = if (col != null && col == highlight) today else paper
                Text(text, Modifier.width(w.getOrElse(i) { 78.dp }).border(0.5.dp, line).background(bg).padding(4.dp), color = ink, maxLines = 4,
                    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.height(IntrinsicSize.Min)) { t.headers.forEachIndexed { i, h -> cell(h, i, true, if (i == 0) null else i - 1) } }
            t.rows.forEach { r -> Row(Modifier.height(IntrinsicSize.Min)) { r.forEachIndexed { i, c -> cell(c, i, i == 0 && (c.startsWith("Date") || c.startsWith("Next")), if (i == 0) null else i - 1) } } }
        }
        if (t.note.isNotBlank()) Text(t.note, color = ink, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
        if (highlight != null) Text(tr("Yellow column = today's visit", "पीला कॉलम = आज की विज़िट"), color = ink, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Visit with the official form: 1 what to record (from the form for this person's age/stage) + voice note →
 * 2 AI fills today's column, ASHA checks and corrects → 3 save (form, MCP card, drug kit, diary, next date) and share the form.
 */
@Composable
fun ArogyaGuidedVisit(person: Beneficiary, hindi: Boolean, visits: List<ArogyaVisit>, queue: ArogyaVisitQueue, ai: ArogyaVisitAi?, onClose: () -> Unit,
                      onAskSop: (String) -> Unit, onSpeak: ((String) -> Unit) -> Unit, speak: (String) -> Unit = {}) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { ArogyaRegistryStore.get(context) }
    val data by store.state.collectAsState()
    val today = LocalDate.now()
    val b = remember(person) { ArogyaOfficialForms.subjectFor(person, store.state.value.beneficiaries) }
    val (spec, column) = remember(b) { ArogyaOfficialForms.formFor(b, store.state.value.forms, today) }
    val rows = remember(spec, column) { ArogyaOfficialForms.fieldsFor(spec, column) }
    val prev = remember(b, data.forms) { ArogyaOfficialForms.previous(spec, b, data.forms, column) }
    val savedToday = remember(b) { store.state.value.forms.firstOrNull { it.beneficiaryId == b.id && it.formId == spec.id && it.column == column } }
    var step by rememberSaveable { mutableStateOf(0) }
    var values by rememberSaveable { mutableStateOf(savedToday?.values?.filterKeys { !it.startsWith("_") } ?: ArogyaOfficialForms.carried(spec, b, store.state.value.forms, column)) }
    var sources by rememberSaveable { mutableStateOf(savedToday?.sources ?: values.mapValues { "carried" }) }
    var evidence by rememberSaveable { mutableStateOf(mapOf<String, String>()) }
    var note by rememberSaveable { mutableStateOf("") }
    var vaccines by rememberSaveable { mutableStateOf(setOf<String>()) }
    var aiBusy by rememberSaveable { mutableStateOf(false) }
    var aiMsg by rememberSaveable { mutableStateOf("") }
    var modelLabel by rememberSaveable { mutableStateOf("") }
    var saved by rememberSaveable { mutableStateOf(false) }
    var showCard by rememberSaveable { mutableStateOf(false) }
    val dueVaccines = remember(b) { if (b.category != BenCategory.CHILD) emptyList() else runCatching { LocalDate.parse(b.dob) }.getOrNull()?.let { dob ->
        ArogyaSchedule.VACCINE_CONTACTS.filter { !it.due(dob).isAfter(today.plusDays(7)) }.flatMap { it.codes }.filter { b.mcp.vaccines[it] == null } } ?: emptyList() }
    val dangers = remember(values) { ArogyaOfficialForms.dangers(spec, values) }
    val missing = remember(values) { ArogyaOfficialForms.missing(spec, column, values) }
    val next = remember { ArogyaOfficialForms.nextDue(spec, b, column, today) }
    val colName = spec.columns.getOrElse(column) { "" }
    fun label(f: FieldSpec) = if (hindi) f.hi else f.label

    fun fill(text: String) {
        val add = text.trim(); if (add.isBlank()) return
        note = (note + "\n" + add).trim()
        val engine = ai
        aiBusy = true; aiMsg = ""
        scope.launch {
            try {
                val (vals, ex) = if (engine != null) engine.fillForm(b, spec, column, note)
                    else ArogyaOfficialForms.parse("", spec, column, note) to ArogyaExtraction(emptyList(), "Fixed patterns only (no model)", false, 0, "")
                // AI never overwrites what the ASHA typed or tapped herself.
                val keep = values.filterKeys { sources[it] == "asha" }
                values = values + vals.mapValues { it.value.value } + keep
                sources = sources + vals.filterKeys { it !in keep }.mapValues { it.value.source }
                evidence = evidence + vals.mapValues { it.value.evidence }
                modelLabel = ex.modelLabel
                aiMsg = tr("AI filled ${vals.size} of ${rows.size} rows · ${ex.modelLabel}${if (ex.ms > 0) " · ${ex.ms / 1000}s" else ""}", "AI ने ${rows.size} में से ${vals.size} भरे · ${ex.modelLabel}") +
                    if (ex.note.isNotBlank()) "\n" + ex.note else ""
                step = 1
            } catch (e: CancellationException) { throw e } catch (e: Exception) { aiMsg = e.message ?: "Could not fill" }
            finally { aiBusy = false }
        }
    }
    fun set(id: String, v: String) { values = values + (id to v); sources = sources + (id to "asha") }

    @Composable fun valueEditor(f: FieldSpec) {
        val v = values[f.id].orEmpty()
        when (f.type) {
            QType.YESNO -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Yes" to tr("Yes", "हाँ"), "No" to tr("No", "नहीं")).forEach { (code, l) ->
                    val sel = v == code
                    FilterChip(selected = sel, onClick = { set(f.id, if (sel) "" else code) }, modifier = Modifier.heightIn(min = 48.dp),
                        label = { Text(l, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), maxLines = 1) },
                        colors = if (sel && f.dangerIf == code) FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.errorContainer) else FilterChipDefaults.filterChipColors())
                }
            }
            QType.CHOICE -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                f.choices.forEach { c -> FilterChip(selected = v == c, onClick = { set(f.id, c) }, modifier = Modifier.heightIn(min = 48.dp),
                    label = { Text(c, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp), maxLines = 1) }) } }
            else -> OutlinedTextField(v, { nv -> set(f.id, if (f.type == QType.NUMBER) nv.filter { c -> c.isDigit() || c == '.' || c == '/' }.take(7) else nv.replace("\n", " ").take(120)) },
                singleLine = true, suffix = { if (f.unit.isNotBlank()) Text(f.unit) }, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = if (f.type == QType.NUMBER && f.id != "bp") KeyboardType.Decimal else KeyboardType.Text))
        }
    }

    Dialog(onDismissRequest = { if (!aiBusy) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(14.dp).imePadding()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { if (step > 0 && !saved) step-- else onClose() }, enabled = !aiBusy) { Text(if (step > 0 && !saved) tr("‹ Back", "‹ वापस") else tr("‹ Close", "‹ बंद")) }
                    Text(b.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
                    TextButton(onClick = { showCard = true }) { Text(tr("📄 Card", "📄 कार्ड")) }
                }
                Text((if (hindi) spec.hi else spec.title.substringBefore(" (")) + " · " + colName, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                LinearProgressIndicator(progress = (step + 1) / 3f, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                Text(listOf(tr("1 · What to record today", "1 · आज क्या दर्ज करें"), tr("2 · Check the form", "2 · फ़ॉर्म जाँचें"), tr("3 · Save", "3 · सहेजें"))[step],
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (step) {
                        0 -> {
                            Text(tr("From the official form (${spec.source}). Check these, then speak one note — AI writes them into the form.",
                                "आधिकारिक फ़ॉर्म से (${spec.source})। ये जाँचें, फिर एक बार बोलें — AI फ़ॉर्म में लिखेगा।"), style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Button(onClick = { onSpeak { fill(it) } }, enabled = !aiBusy, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text(tr("🎤 Speak visit note", "🎤 विज़िट नोट बोलें")) }
                                OutlinedButton(onClick = { speak(tr("Please check: ", "कृपया जाँचें: ") + rows.joinToString(". ") { label(it) }) }) { Text("🔊") }
                                AiBadge("Finish visit")
                            }
                            if (aiBusy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(tr("AI is filling the form from your note…", "AI आपके नोट से फ़ॉर्म भर रहा है…"), style = MaterialTheme.typography.bodySmall) }
                            if (aiMsg.isNotBlank()) Text(aiMsg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            var typed by remember { mutableStateOf("") }
                            OutlinedTextField(typed, { typed = it.take(3000) }, label = { Text(tr("…or type the note", "…या नोट लिखें")) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                            if (typed.isNotBlank()) OutlinedButton(onClick = { fill(typed); typed = "" }, enabled = !aiBusy, modifier = Modifier.fillMaxWidth()) { Text(tr("✨ Fill the form from this note", "✨ इस नोट से फ़ॉर्म भरें")) }
                            var hintFor by remember { mutableStateOf<FieldSpec?>(null) }
                            var lastSec = ""
                            rows.forEach { f ->
                                if (f.section != lastSec) { lastSec = f.section; Text(f.section, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp)) }
                                Row(Modifier.fillMaxWidth().padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("☐ " + label(f) + if (f.unit.isNotBlank() && f.type == QType.NUMBER) " (${f.unit})" else "",
                                        Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                    if (f.hint.isNotBlank() || prev[f.id] != null) TextButton(onClick = { hintFor = f }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("ⓘ") }
                                }
                            }
                            hintFor?.let { f -> AlertDialog(onDismissRequest = { hintFor = null }, title = { Text(label(f)) }, text = {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (f.hint.isNotBlank()) Text(f.hint)
                                    prev[f.id]?.let { (v, c) -> Text(tr("Last time: $v ($c)", "पिछली बार: $v ($c)"), fontWeight = FontWeight.Medium) }
                                }
                            }, confirmButton = { TextButton(onClick = { hintFor = null }) { Text(tr("OK", "ठीक")) } }) }
                            if (dueVaccines.isNotEmpty()) Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp)) {
                                Text(tr("Vaccines due — tick those given today (goes on the MCP card)", "बाकी टीके — आज दिए गए पर ✓"), fontWeight = FontWeight.Medium)
                                dueVaccines.forEach { code -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(code in vaccines, { c -> vaccines = if (c) vaccines + code else vaccines - code }); Text(ArogyaSchedule.VACCINE_NAMES[code] ?: code) } }
                            } }
                        }
                        1 -> {
                            if (dangers.isNotEmpty()) Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(tr("⚠ REFER NOW", "⚠ तुरंत रेफ़र करें"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                                    dangers.forEach { Text("• $it") }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { ArogyaShare.dial(context, "108") }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("📞 108") }
                                        OutlinedButton(onClick = { val ph = ArogyaShare.anmPhone(context); if (ph.isBlank()) Toast.makeText(context, tr("Add the ANM number in Escalate to ANM", "ANM नंबर जोड़ें"), Toast.LENGTH_LONG).show() else ArogyaShare.dial(context, ph) }) { Text("📞 ANM") }
                                        OutlinedButton(onClick = { ArogyaShare.whatsApp(context, ArogyaShare.anmPhone(context), "URGENT — ${b.name}, ${b.household} ${b.village} (${spec.columns.getOrElse(column) { "" }}): " + dangers.joinToString("; ") + ". Please advise. (ASHA via ArogyaDoot)") }) { Text("💬") }
                                    }
                                    Text(tr("Fixed action rules printed on the form — not AI.", "फ़ॉर्म पर छपे तय नियम — AI नहीं।"), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            ArogyaOfficialForms.weightNote(prev, values)?.let { Text("⚖ $it", fontWeight = FontWeight.Medium, color = if (it.contains("fell")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
                            if (aiMsg.isNotBlank()) Text(aiMsg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            Text(tr("✨ = filled by AI from your words (tap ✨ to see them). Change anything that is wrong.", "✨ = AI ने आपके शब्दों से भरा। गलत हो तो बदलें।"), style = MaterialTheme.typography.bodySmall)
                            var lastSec = ""
                            rows.forEach { f ->
                                if (f.section != lastSec) { lastSec = f.section; Text(f.section, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp)) }
                                var showEv by remember { mutableStateOf(false) }
                                val src = sources[f.id]; val empty = values[f.id].isNullOrBlank()
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (empty) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)) {
                                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(label(f), Modifier.weight(1f), fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyLarge)
                                            when (src) {
                                                "ai" -> TextButton(onClick = { showEv = !showEv }) { Text("✨") }
                                                "rule" -> Text(tr("pattern", "पैटर्न"), style = MaterialTheme.typography.labelSmall)
                                                "carried" -> Text(tr("from last visit", "पिछली विज़िट से"), style = MaterialTheme.typography.labelSmall)
                                                "asha" -> Text(tr("you", "आप"), style = MaterialTheme.typography.labelSmall)
                                                else -> if (empty) Text(tr("not said", "नहीं बताया"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                        if (showEv) evidence[f.id]?.let { Text("“$it”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                                        valueEditor(f)
                                    }
                                }
                            }
                            if (missing.isNotEmpty()) Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(tr("${missing.size} rows not covered yet", "${missing.size} पंक्तियाँ बाकी"), fontWeight = FontWeight.Medium)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { speak(tr("Not recorded yet: ", "अभी दर्ज नहीं: ") + missing.joinToString(". ") { label(it) }) }) { Text(tr("🔊 Read them", "🔊 सुनें")) }
                                    Button(onClick = { onSpeak { fill(it) } }, enabled = !aiBusy) { Text(tr("🎤 Add more", "🎤 और बोलें")) }
                                }
                                if (aiBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text(tr("Leave blank what you did not check — blanks stay blank on the form.", "जो नहीं जाँचा वह खाली छोड़ें।"), style = MaterialTheme.typography.labelSmall)
                            } }
                            val sugg = buildList {
                                if (dangers.isNotEmpty()) add(tr("What should I do before the patient reaches the hospital?", "अस्पताल पहुँचने से पहले क्या करें?"))
                                if ((values["weight"]?.toDoubleOrNull() ?: 9.0) < 2.5 && spec.id == "annex9") add(tr("How to care for a low birth weight baby at home?", "कम वज़न वाले बच्चे की घर पर देखभाल?"))
                                if (values["jaundice"] == "Yes") add(tr("What to do if a newborn has yellow eyes or skin?", "नवजात में पीलापन हो तो क्या करें?"))
                                if (values["diarrhoea"] == "Yes") add(tr("How to give ORS and zinc to a child with diarrhoea?", "दस्त में ORS और ज़िंक कैसे दें?"))
                                if (spec.id == "hbyc") add(tr("What complementary food should a child get at this age?", "इस उम्र में बच्चे को क्या ऊपरी आहार दें?"))
                                if (spec.id == "mcp_anc") add(tr("How many IFA tablets in pregnancy and how to take them?", "गर्भावस्था में कितनी IFA गोलियाँ?"))
                            }
                            if (sugg.isNotEmpty()) Text(tr("Need the SOP?", "SOP चाहिए?"), fontWeight = FontWeight.Bold)
                            sugg.take(3).forEach { q -> OutlinedButton(onClick = { onAskSop(q) }, modifier = Modifier.fillMaxWidth()) { Text("❓ $q") } }
                        }
                        else -> {
                            val current = column to (values + ("_next" to (next?.toString().orEmpty())))
                            OfficialFormGrid(ArogyaOfficialForms.table(spec, b, data.forms, current), column)
                            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(if (saved) tr("✓ Saved on this phone", "✓ फ़ोन पर सहेजा गया") else tr("Saving will update:", "सहेजने पर अपडेट होगा:"), fontWeight = FontWeight.Bold)
                                Text(tr("📄 This form — $colName column", "📄 यह फ़ॉर्म — $colName कॉलम"))
                                values["weight"]?.takeIf { it.isNotBlank() && spec.id != "mcp_anc" }?.let { Text(tr("🗂 MCP card growth chart: $it kg", "🗂 MCP कार्ड वज़न: $it kg")) }
                                if (spec.id == "mcp_anc") Text(tr("🗂 MCP card ANC ${column + 1}: weight, BP, Hb, TT/Td, IFA", "🗂 MCP कार्ड ANC ${column + 1}"))
                                if (vaccines.isNotEmpty()) Text("🗂 MCP card: " + vaccines.joinToString { ArogyaSchedule.VACCINE_NAMES[it] ?: it })
                                listOf("ors" to "ORS", "zinc" to "Zinc", "ifa" to "IFA", "ifa_syrup" to "IFA syrup", "condoms" to "Condoms", "ocp" to "Pills", "napkins" to "Napkins").forEach { (k, l) ->
                                    values[k]?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 }?.let { Text(tr("💊 Drug kit register: $l −$it", "💊 दवा किट: $l −$it")) } }
                                Text(tr("📒 Activity diary + visit list", "📒 डायरी + विज़िट सूची"))
                                if (dangers.isNotEmpty()) Text(tr("⚠ Referral needed", "⚠ रेफ़रल ज़रूरी"), color = MaterialTheme.colorScheme.error)
                                Text(next?.let { tr("📅 Next visit: $it (${spec.columns.getOrElse(column + 1) { "follow-up" }})", "📅 अगली विज़िट: $it") } ?: tr("📅 This form is complete after today", "📅 आज के बाद फ़ॉर्म पूरा"), fontWeight = FontWeight.Bold)
                            } }
                            if (saved) OutlinedButton(onClick = {
                                scope.launch {
                                    if (!store.shareFormPdf(ArogyaOfficialForms.table(spec, b, store.state.value.forms))) Toast.makeText(context, "Could not create the PDF", Toast.LENGTH_LONG).show()
                                }
                            }, modifier = Modifier.fillMaxWidth()) { Text(tr("📤 Share form as PDF (to ANM / print)", "📤 फ़ॉर्म PDF भेजें")) }
                        }
                    }
                }
                if (!saved) Button(onClick = {
                    when (step) {
                        0 -> step = 1
                        1 -> step = 2
                        else -> {
                            val ok = runCatching {
                                ArogyaVisitSave.save(store, queue, b, spec, column, values, sources, vaccines, today)
                            }.onFailure { Toast.makeText(context, it.message ?: "Could not save", Toast.LENGTH_LONG).show() }.isSuccess
                            if (ok) { saved = true; android.util.Log.i("ArogyaDemo", "AROGYA_FORM_SAVED form=${spec.id} col=$column filled=${values.count { it.value.isNotBlank() }}/${rows.size} ai=${sources.count { it.value == "ai" }} danger=${dangers.size} next=$next model=\"$modelLabel\"") }
                        }
                    }
                }, enabled = !aiBusy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    colors = if (step == 1 && dangers.isNotEmpty()) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors()) {
                    Text(listOf(tr("Fill by hand instead ›", "हाथ से भरें ›"), tr("Next: see the form ›", "आगे: फ़ॉर्म देखें ›"), tr("✓ Save to form, card and registers", "✓ फ़ॉर्म, कार्ड, रजिस्टर में सहेजें"))[step], style = MaterialTheme.typography.titleMedium)
                } else Button(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(tr("Done", "पूरा")) }
            }
        }
    }
    if (showCard) AlertDialog(onDismissRequest = { showCard = false }, confirmButton = { TextButton(onClick = { showCard = false }) { Text(tr("Close", "बंद")) } },
        dismissButton = { TextButton(onClick = { scope.launch { store.shareFormPdf(ArogyaOfficialForms.table(spec, b, data.forms)) } }) { Text(tr("Share PDF", "PDF भेजें")) } },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { OfficialFormGrid(ArogyaOfficialForms.table(spec, b, data.forms, if (saved) null else column to values), column) } })
}
