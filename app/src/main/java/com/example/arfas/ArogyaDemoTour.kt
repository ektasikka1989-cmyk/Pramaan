package com.example.arfas

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/*
 * The 3-minute auto-demo. It drives the REAL features on the sample village (fictional people):
 * schedule → official form for today → voice-note text → AI fills the form → danger rules → Ask SOP with sources →
 * AI message to ANM + referral to PHC → MCP card photo found on the phone and read by AI → save to form, MCP card,
 * diary and visit list → registers and monthly report → which models did the work.
 * Nothing is sent to anyone: calls and WhatsApp are shown, not placed.
 */
object ArogyaDemoScript {
    const val HERO_PREFIX = "Baby of Neha"
    const val CARD_FILE = "ArogyaDoot_sample_MCP_card.jpg"
    val NOTE = "Baby of Neha, day 7 visit. Baby weight 2650 grams. Baby temperature 38.4 C. Baby is feeding less since morning and the cry is weak. " +
        "No pus on the cord, eyes are clean. Baby is kept warm near the mother. Mother has taken 4 full meals, changed 3 pads today, mother temperature 37 C."
    const val QUESTION = "A 7-day-old baby has fever and is feeding less. What should the ASHA do, and what before reaching the hospital?"

    data class Scene(val title: String, val hi: String, val narration: String, val minSeconds: Int)
    val scenes = listOf(
        Scene("Meet Sunita", "मिलिए सुनीता से", "Sunita is an ASHA in Demo Gaon. She looks after fifty families, fills paper registers and MCP cards by hand, and must follow hundreds of guidelines. Watch ArogyaDoot do the paperwork with her.", 12),
        Scene("Her day, planned", "आज का काम", "Every morning the app works out who is due, from the official schedules. These are fixed rules, not AI.", 12),
        Scene("Today's visit: what to check", "आज की विज़िट: क्या जाँचें", "Baby of Neha was born at home seven days ago. The app opens the official Home Visit Form, Annexure 9, at the Day 7 column, and lists exactly what to check.", 12),
        Scene("One voice note → official form", "एक वॉइस नोट → आधिकारिक फ़ॉर्म", "Sunita speaks one note. AI fills the form, and every value shows the words it came from.", 14),
        Scene("Danger found → refer", "खतरा मिला → रेफ़र", "The form's own rules flag danger signs: fever and poor feeding in a newborn. The app tells her to refer now.", 10),
        Scene("Ask the SOP", "SOP से पूछें", "She asks what to do. The answer comes only from the official ASHA guides, with the document and page.", 14),
        Scene("Escalate to ANM and PHC", "ANM और PHC को सूचना", "AI drafts the message to the ANM from the visit facts. Call 108, call the ANM or send on WhatsApp in one tap.", 12),
        Scene("MCP card photo → card updated", "MCP कार्ड फ़ोटो → कार्ड अपडेट", "The family's MCP card photo is on the phone. AI reads it and adds the vaccines given at the PHC. Only values printed on the card are kept.", 12),
        Scene("Saved everywhere at once", "एक बार में हर जगह सेव", "One tap saves the form, the MCP card, the activity diary, the visit list and the next visit date. The form prints exactly like the paper one.", 12),
        Scene("Registers and monthly claim", "रजिस्टर और मासिक दावा", "Registers, the monthly report and her incentive claim update themselves.", 12),
        Scene("Offline first, Meta when online", "पहले ऑफ़लाइन, ऑनलाइन पर Meta", "Everything you saw works without internet on the phone. With signal, Meta's cloud models take over. Sunita saved her evening.", 12)
    )
}

/** Writes a fictional sample MCP card photo for the demo baby to Pictures/ArogyaDoot and finds it again via MediaStore. */
object ArogyaDemoCard {
    private val DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    fun render(b: Beneficiary): Bitmap {
        val w = 1200; val h = 1500
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(bmp)
        c.drawColor(0xFFFFFDF7.toInt())
        val band = Paint().apply { color = 0xFFF8D7E3.toInt() }; c.drawRect(0f, 0f, w.toFloat(), 190f, band)
        val title = Paint().apply { color = 0xFF222222.toInt(); textSize = 50f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        val text = Paint().apply { color = 0xFF111111.toInt(); textSize = 44f; isAntiAlias = true }
        val small = Paint().apply { color = 0xFF555555.toInt(); textSize = 32f; isAntiAlias = true }
        val line = Paint().apply { color = 0xFFAAAAAA.toInt(); strokeWidth = 2f }
        c.drawText("MOTHER AND CHILD PROTECTION CARD", 60f, 90f, title)
        c.drawText("SAMPLE - fictional card for ArogyaDoot demo", 60f, 150f, small)
        val dob = runCatching { LocalDate.parse(b.dob) }.getOrDefault(LocalDate.now().minusDays(7))
        val given = dob.plusDays(1)
        var y = 270f
        fun row(s: String, p: Paint = text) { c.drawText(s, 60f, y, p); y += 78f }
        row("Child's name: ${b.name}")
        row("Mother's name: ${b.name.removePrefix("Baby of ").substringBefore(" (")} (demo)")
        row("Date of birth: ${dob.format(DMY)}")
        row("Birth weight: ${b.birthWeightKg ?: 2.8} kg")
        row("Place of birth: Home")
        c.drawLine(60f, y - 40f, w - 60f, y - 40f, line); y += 20f
        row("IMMUNIZATION", title)
        row("BCG          ${given.format(DMY)}")
        row("OPV-0        ${given.format(DMY)}")
        row("Hep B-0      ${given.format(DMY)}")
        c.drawLine(60f, y - 40f, w - 60f, y - 40f, line); y += 20f
        row("WEIGHT RECORD", title)
        row("${given.format(DMY)}   2.75 kg")
        y += 40f; row("Given at: PHC Demo Gaon (sample)", small)
        return bmp
    }

    /** (Re)writes the sample card so its dates match today's sample village; returns its Uri. */
    fun putOnPhone(context: Context, b: Beneficiary): Uri? = runCatching {
        val cr = context.contentResolver
        find(context)?.let { cr.delete(it, null, null) }
        val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, ArogyaDemoScript.CARD_FILE); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ArogyaDoot")
        }) ?: return null
        cr.openOutputStream(uri)?.use { render(b).compress(Bitmap.CompressFormat.JPEG, 92, it) }
        uri
    }.onFailure { Log.w("ArogyaDemo", "AROGYA_DEMO_CARD_WRITE_FAILED ${it.message}") }.getOrNull()

    /** Finds the sample card photo on the phone (only this app's own file). */
    fun find(context: Context): Uri? = runCatching {
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME}=?", arrayOf(ArogyaDemoScript.CARD_FILE), null)?.use { cur ->
            if (cur.moveToFirst()) android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cur.getLong(0)) else null
        }
    }.getOrNull()
}

@Composable
fun ArogyaDemoTour(ai: ArogyaVisitAi?, queue: ArogyaVisitQueue, visits: List<ArogyaVisit>, hindi: Boolean, speak: (String) -> Unit,
                   onLoadSample: () -> Unit, onClose: () -> Unit) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    val context = LocalContext.current
    val store = remember { ArogyaRegistryStore.get(context) }
    val data by store.state.collectAsState()
    val scenes = ArogyaDemoScript.scenes
    var scene by remember { mutableStateOf(0) }
    var ready by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(true) }
    var narrate by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val startedAt = remember { System.currentTimeMillis() }
    var elapsed by remember { mutableStateOf(0L) }
    // Results of each scene (real outputs).
    var hero by remember { mutableStateOf<Beneficiary?>(null) }
    var spec by remember { mutableStateOf<FormSpec?>(null) }
    var column by remember { mutableStateOf(0) }
    var filled by remember { mutableStateOf<Map<String, FilledValue>>(emptyMap()) }
    var formModel by remember { mutableStateOf("") }
    var formMs by remember { mutableStateOf(0L) }
    var dangers by remember { mutableStateOf<List<String>>(emptyList()) }
    var sop by remember { mutableStateOf<ArogyaAiResult?>(null) }
    var anm by remember { mutableStateOf("") }
    var card by remember { mutableStateOf<ArogyaCardReading?>(null) }
    var cardUri by remember { mutableStateOf<Uri?>(null) }
    var vaccinesAdded by remember { mutableStateOf<List<String>>(emptyList()) }
    var saved by remember { mutableStateOf(false) }
    val models = remember { mutableStateListOf<String>() }

    LaunchedEffect(Unit) { while (true) { elapsed = (System.currentTimeMillis() - startedAt) / 1000; delay(1000) } }

    // Prepare: fresh sample village, the hero baby, the sample MCP card photo on the phone.
    LaunchedEffect(Unit) {
        busy = true; status = tr("Loading the sample village (fictional people)…", "नमूना गाँव लोड हो रहा है…")
        onLoadSample(); delay(1500)
        val b = store.state.value.beneficiaries.firstOrNull { it.name.startsWith(ArogyaDemoScript.HERO_PREFIX) }
        if (b == null) { busy = false; status = tr("Sample village not found — load it from Home first.", "नमूना गाँव नहीं मिला — होम से लोड करें।"); return@LaunchedEffect }
        val (sp, col) = ArogyaOfficialForms.formFor(b, store.state.value.forms); spec = sp; column = col; hero = b
        cardUri = withContext(Dispatchers.IO) { ArogyaDemoCard.putOnPhone(context, b) }
        runCatching { ai?.warmUp() }
        busy = false; status = ""; ready = true
        Log.i("ArogyaDemo", "AROGYA_DEMO_START hero=${hero?.id} card=${cardUri != null}")
    }

    // Run each scene's real work, narrate, then move on.
    LaunchedEffect(scene, ready) {
        if (!ready) return@LaunchedEffect
        val b = hero ?: return@LaunchedEffect
        val sp = spec ?: return@LaunchedEffect
        val sc = scenes[scene]
        if (narrate) speak(sc.narration)
        val t0 = System.currentTimeMillis()
        busy = true; status = ""
        try {
            when (scene) {
                3 -> { status = tr("AI is filling the form…", "AI फ़ॉर्म भर रहा है…")
                    val engine = ai ?: error("AI is not ready")
                    val (v, ex) = engine.fillForm(b, sp, column, ArogyaDemoScript.NOTE)
                    filled = v; formModel = ex.modelLabel; formMs = ex.ms; models += "Form filling: ${ex.modelLabel}" }
                4 -> dangers = ArogyaOfficialForms.dangers(sp, filled.mapValues { it.value.value })
                5 -> { status = tr("Searching the official guides…", "आधिकारिक गाइड में खोज…")
                    val engine = ai ?: error("AI is not ready")
                    val r = engine.run(ArogyaAiRequest(ArogyaAiTask.EXPLAIN, ArogyaVisit(id = SOP_VISIT_ID, personId = SOP_VISIT_ID, name = "", kind = ArogyaKind.FOLLOW_UP), question = ArogyaDemoScript.QUESTION))
                    sop = r; models += "SOP answer: ${r.provider}" }
                6 -> { status = tr("AI is drafting the ANM message…", "AI ANM संदेश लिख रहा है…")
                    val facts = "Baby of Neha (demo), day 7, Demo Gaon. " + dangers.joinToString("; ") + ". Referred to PHC today."
                    anm = runCatching { ai?.anmMessage(facts).orEmpty() }.getOrDefault("").ifBlank { "URGENT: $facts Please advise. — ASHA Sunita (ArogyaDoot)" }
                    models += "ANM message: " + if (ArogyaCloud.cloudFirst(context)) "cloud first (phone fallback)" else "on-phone first" }
                7 -> { status = tr("Finding the MCP card photo on the phone…", "फ़ोन पर MCP कार्ड फ़ोटो ढूँढ रहे हैं…")
                    val uri = withContext(Dispatchers.IO) { ArogyaDemoCard.find(context) } ?: error("Sample card photo not found")
                    cardUri = uri; status = tr("AI is reading the card…", "AI कार्ड पढ़ रहा है…")
                    val r = ai?.readCard(uri) ?: error("AI is not ready"); card = r
                    val before = store.state.value.beneficiaries.first { it.id == b.id }
                    val after = ArogyaCardApply.apply(before, r.fields)
                    vaccinesAdded = (after.mcp.vaccines.keys - before.mcp.vaccines.keys).map { ArogyaSchedule.VACCINE_NAMES[it] ?: it }
                    store.update { reg -> reg.copy(beneficiaries = reg.beneficiaries.map { if (it.id == b.id) after else it }) }
                    models += "MCP card: ML Kit text reading + AI (" + (if (ArogyaCloud.cloudFirst(context)) "cloud first" else "phone first") + ")" }
                8 -> { val vals = filled.mapValues { it.value.value }
                    val cur = store.state.value.beneficiaries.first { it.id == b.id }
                    ArogyaVisitSave.save(store, queue, cur, sp, column, vals, filled.mapValues { it.value.source }, emptySet()); saved = true
                    Log.i("ArogyaDemo", "AROGYA_DEMO_SAVED form=${sp.id} col=$column filled=${vals.size} dangers=${dangers.size}") }
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            status = tr("This step could not finish: ", "यह चरण पूरा नहीं हुआ: ") + (e.message ?: "")
            Log.w("ArogyaDemo", "AROGYA_DEMO_STEP_FAILED scene=$scene ${e.message}")
        }
        busy = false; if (status.startsWith(tr("This step", "यह चरण")).not()) status = ""
        val wait = sc.minSeconds * 1000L - (System.currentTimeMillis() - t0)
        if (wait > 0) delay(wait)
        while (!playing) delay(300)
        if (scene < scenes.lastIndex) scene++
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("▶ " + tr("ArogyaDoot · 3-minute demo", "ArogyaDoot · 3 मिनट डेमो"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("%d:%02d".format(elapsed / 60, elapsed % 60), style = MaterialTheme.typography.labelLarge)
                }
                LinearProgressIndicator(progress = (scene + 1) / scenes.size.toFloat(), modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                Text("${scene + 1}/${scenes.size} · " + (if (hindi) scenes[scene].hi else scenes[scene].title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(scenes[scene].narration, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp)) }
                if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val b = hero; val sp = spec
                    if (b == null || sp == null) { Text(tr("Preparing…", "तैयारी…")); return@Column }
                    when (scene) {
                        0 -> DemoCard(tr("The problem", "समस्या")) {
                            Text("• " + tr("50 families · 5 types of visits · 12+ paper registers and cards", "50 परिवार · 5 तरह की विज़िट · 12+ रजिस्टर और कार्ड"))
                            Text("• " + tr("Hundreds of SOPs — hard to know which applies", "सैकड़ों SOP — कौन सा लागू है, जानना मुश्किल"))
                            Text("• " + tr("Patchy network in the village", "गाँव में कमज़ोर नेटवर्क"))
                            Text(tr("Sample village: 50 fictional people", "नमूना गाँव: 50 काल्पनिक लोग"), style = MaterialTheme.typography.labelMedium)
                        }
                        1 -> DemoCard(tr("Due this week (official schedules — rules, not AI)", "इस हफ़्ते बाकी (सरकारी शेड्यूल — नियम, AI नहीं)")) {
                            val due = remember(data, visits) { ArogyaSchedule.dueList(data.beneficiaries, visits, LocalDate.now(), 7).filter { !it.done } }
                            Text(tr("${due.size} visits due", "${due.size} विज़िट बाकी"), fontWeight = FontWeight.Bold)
                            due.sortedBy { if (it.name.startsWith(ArogyaDemoScript.HERO_PREFIX)) 0 else 1 }.take(5).forEach { Text("• ${it.name} — ${it.label} (${it.due})") }
                        }
                        2 -> DemoCard("${sp.title.substringBefore(" (")} · ${sp.columns[column]}") {
                            Text(sp.source, style = MaterialTheme.typography.labelSmall)
                            ArogyaOfficialForms.fieldsFor(sp, column).take(12).forEach { Text("☐ " + it.label + if (it.hint.isNotBlank()) "  — ${it.hint}" else "", style = MaterialTheme.typography.bodySmall) }
                            Text(tr("…and ${ArogyaOfficialForms.fieldsFor(sp, column).size - 12} more rows", "…और ${ArogyaOfficialForms.fieldsFor(sp, column).size - 12} पंक्तियाँ"), style = MaterialTheme.typography.labelSmall)
                        }
                        3 -> {
                            DemoCard(tr("🎤 Voice note (text of what she said)", "🎤 वॉइस नोट (जो उसने कहा)")) { Text("“${ArogyaDemoScript.NOTE}”", style = MaterialTheme.typography.bodySmall) }
                            if (filled.isNotEmpty()) DemoCard(tr("✨ AI filled ${filled.size} rows · $formModel · ${formMs / 1000}s", "✨ AI ने ${filled.size} पंक्तियाँ भरीं · $formModel")) {
                                filled.forEach { (id, v) -> val f = sp.fields.firstOrNull { it.id == id } ?: return@forEach
                                    Text("${f.label}: ${v.value}${if (f.type == QType.NUMBER && f.unit.isNotBlank()) " " + f.unit else ""}", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
                                    Text("   ← “${v.evidence}”", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                            }
                        }
                        4 -> DemoCard(if (dangers.isEmpty()) tr("✓ No danger signs", "✓ कोई खतरा नहीं") else tr("⚠ REFER NOW", "⚠ तुरंत रेफ़र करें"), danger = dangers.isNotEmpty()) {
                            dangers.forEach { Text("• $it") }
                            Text(tr("Rules printed on Annexure 9 — not AI.", "अनुलग्नक 9 पर छपे नियम — AI नहीं।"), style = MaterialTheme.typography.labelSmall)
                        }
                        5 -> {
                            DemoCard(tr("❓ Question", "❓ सवाल")) { Text(ArogyaDemoScript.QUESTION) }
                            sop?.let { r -> DemoCard(tr("Answer from the official guides · ${r.provider}", "आधिकारिक गाइड से उत्तर · ${r.provider}")) {
                                Text(r.text.take(900), style = MaterialTheme.typography.bodySmall)
                                if (r.quote.isNotBlank()) Text("“${r.quote}” — ${r.quoteSource}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                r.sources.take(3).forEach { s -> Text("📄 ${s.title} · ${s.locator}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium) }
                            } }
                        }
                        6 -> {
                            DemoCard(tr("Escalation", "सूचना")) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = {}, enabled = false) { Text("📞 108") }
                                    OutlinedButton(onClick = {}, enabled = false) { Text("📞 ANM") }
                                    OutlinedButton(onClick = {}, enabled = false) { Text("💬 WhatsApp") }
                                }
                                Text(tr("Referral to PHC recorded on the visit. (Demo: nothing is sent.)", "PHC रेफ़रल विज़िट पर दर्ज। (डेमो: कुछ भेजा नहीं जाता)"), style = MaterialTheme.typography.labelSmall)
                            }
                            if (anm.isNotBlank()) DemoCard(tr("✨ Message to ANM (AI draft, she edits before sending)", "✨ ANM को संदेश (AI ड्राफ़्ट)")) { Text(anm, style = MaterialTheme.typography.bodySmall) }
                        }
                        7 -> {
                            DemoCard(tr("📷 Found on this phone", "📷 फ़ोन पर मिला")) { Text("Pictures/ArogyaDoot/${ArogyaDemoScript.CARD_FILE}", style = MaterialTheme.typography.bodySmall) }
                            card?.let { r -> DemoCard(tr("✨ Read from the card (${r.fields.size} values, each found in the card's text)", "✨ कार्ड से पढ़ा (${r.fields.size})")) {
                                r.fields.forEach { Text("${it.label}: ${it.value}", style = MaterialTheme.typography.bodySmall) }
                                Text(if (vaccinesAdded.isEmpty()) tr("Already on the MCP card", "MCP कार्ड पर पहले से") else tr("Added to MCP card: ", "MCP कार्ड में जोड़ा: ") + vaccinesAdded.joinToString(), fontWeight = FontWeight.Bold)
                            } }
                        }
                        8 -> {
                            val nextDue = remember(saved) { ArogyaOfficialForms.nextDue(sp, b, column, LocalDate.now()) }
                            OfficialFormGrid(ArogyaOfficialForms.table(sp, b, data.forms), column)
                            DemoCard(tr("Saved", "सहेजा")) {
                                Text("📄 " + tr("Annexure 9 — ${sp.columns[column]} column", "अनुलग्नक 9 — ${sp.columns[column]} कॉलम"))
                                Text("🗂 " + tr("MCP card: weight + vaccines", "MCP कार्ड: वज़न + टीके"))
                                Text("📒 " + tr("Activity diary + visit list (referral: needed)", "डायरी + विज़िट सूची (रेफ़रल: ज़रूरी)"))
                                Text("📅 " + tr("Next visit: ", "अगली विज़िट: ") + (nextDue?.toString() ?: "—"), fontWeight = FontWeight.Bold)
                            }
                        }
                        9 -> {
                            val month = YearMonth.now()
                            val rep = remember(data, visits) { ArogyaRegistryLogic.monthlyReport(data, visits, month) }
                            val inc = remember(data, visits) { ArogyaRegistryLogic.incentiveEstimate(data, visits, month) }
                            val cur = data.beneficiaries.firstOrNull { it.id == b.id }
                            DemoCard(tr("Updated automatically", "अपने आप अपडेट")) {
                                Text(tr("Home visits this month: ${rep.homeVisits}", "इस महीने गृह भ्रमण: ${rep.homeVisits}"))
                                Text(tr("Referrals this week: ${visits.count { it.referral == ArogyaReferral.NEEDED && it.date >= LocalDate.now().minusDays(7).toString() }}", "इस हफ़्ते रेफ़रल: ${visits.count { it.referral == ArogyaReferral.NEEDED && it.date >= LocalDate.now().minusDays(7).toString() }}"))
                                Text(tr("Visit forms filled: ${data.forms.size}", "भरे गए फ़ॉर्म: ${data.forms.size}"))
                                cur?.let { Text(tr("MCP card of ${it.name}: ${it.mcp.vaccines.size} vaccines, ${it.mcp.weights.size} weights", "${it.name} का MCP कार्ड: ${it.mcp.vaccines.size} टीके, ${it.mcp.weights.size} वज़न")) }
                                Text(tr("Estimated incentive this month: ₹${inc.total}", "इस महीने अनुमानित प्रोत्साहन: ₹${inc.total}"), fontWeight = FontWeight.Bold)
                            }
                        }
                        else -> DemoCard(tr("What did the work in this demo", "इस डेमो में किसने काम किया")) {
                            models.distinct().forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                            Text("• " + tr("Danger signs, schedules, incentives: fixed rules (no AI)", "खतरे के संकेत, शेड्यूल, प्रोत्साहन: तय नियम (AI नहीं)"), style = MaterialTheme.typography.bodySmall)
                            Text(tr("Setting now: ", "अभी सेटिंग: ") + (if (ArogyaCloud.cloudFirst(context)) "☁ Cloud first" else "📱 Local first") + " · " + (if (MetaOnline.hasInternet(context)) tr("online", "ऑनलाइन") else tr("offline", "ऑफ़लाइन")), fontWeight = FontWeight.Bold)
                            Text(tr("Offline: Llama 3.2 3B, Whisper, ML Kit on the phone. Online: Meta Muse Spark, Muse Voice Transcribe, SAM 3.1, Muse Glimmer.", "ऑफ़लाइन: फ़ोन पर Llama 3.2 3B, Whisper, ML Kit। ऑनलाइन: Meta Muse Spark, Muse Voice Transcribe, SAM 3.1, Muse Glimmer।"), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { playing = !playing }) { Text(if (playing) tr("⏸ Pause", "⏸ रोकें") else tr("▶ Play", "▶ चलाएँ")) }
                    OutlinedButton(onClick = { if (scene < scenes.lastIndex) scene++ }, enabled = scene < scenes.lastIndex) { Text(tr("Next ›", "आगे ›")) }
                    FilterChip(selected = narrate, onClick = { narrate = !narrate }, label = { Text("🔊") })
                    Spacer(Modifier.weight(1f))
                    Button(onClick = onClose) { Text(tr("Close", "बंद")) }
                }
            }
        }
    }
}

@Composable
private fun DemoCard(title: String, danger: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = if (danger) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer) else CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            content()
        }
    }
}
