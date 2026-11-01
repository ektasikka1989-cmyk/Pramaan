package com.example.arfas

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/*
 * The 3-minute auto-demo. It drives the REAL features on the sample village (fictional people):
 * start screen → schedule → official form for today → voice note through real speech-to-text →
 * AI fills the form → danger rules → Ask SOP with passages, answer and open page → AI message to
 * ANM + referral to PHC → MCP card photo found on the phone and read by AI → save to form, MCP card,
 * diary and visit list → registers and monthly report → SAM 3.1 drug-kit count → what ran where.
 * The script data lives in ArogyaDemoScript.kt (pure, host-tested). Nothing is sent to anyone:
 * calls and WhatsApp show a "Demo: not sent" confirmation instead of placing anything.
 */

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
                   onLoadSample: () -> Unit, onClose: () -> Unit, onDeepLink: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var started by remember { mutableStateOf(false) }
    var demoHindi by remember(hindi) { mutableStateOf(hindi) }
    fun tr(en: String, hi: String) = if (demoHindi) hi else en
    val store = remember { ArogyaRegistryStore.get(context) }
    val data by store.state.collectAsState()
    val scenes = ArogyaDemoScript.scenes
    var scene by remember { mutableStateOf(0) }
    var ready by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(true) }
    var narrate by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var startedAt by remember { mutableStateOf(0L) }
    var elapsed by remember { mutableStateOf(0L) }
    // Results of each scene (real outputs).
    var hero by remember { mutableStateOf<Beneficiary?>(null) }
    var spec by remember { mutableStateOf<FormSpec?>(null) }
    var column by remember { mutableStateOf(0) }
    var filled by remember { mutableStateOf<Map<String, FilledValue>>(emptyMap()) }
    var formModel by remember { mutableStateOf("") }
    var formMs by remember { mutableStateOf(0L) }
    var transcript by remember { mutableStateOf("") }
    var transcriptEngine by remember { mutableStateOf("") }
    var transcriptLive by remember { mutableStateOf(false) }
    var dangers by remember { mutableStateOf<List<String>>(emptyList()) }
    var sop by remember { mutableStateOf<ArogyaAiResult?>(null) }
    var anm by remember { mutableStateOf("") }
    var anmModel by remember { mutableStateOf("") }
    var demoMsg by remember { mutableStateOf("") }
    var card by remember { mutableStateOf<ArogyaCardReading?>(null) }
    var cardModel by remember { mutableStateOf("") }
    var cardUri by remember { mutableStateOf<Uri?>(null) }
    var vaccinesAdded by remember { mutableStateOf<List<String>>(emptyList()) }
    var saved by remember { mutableStateOf(false) }
    var savedReferral by remember { mutableStateOf("") }
    var samBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var samMsg by remember { mutableStateOf("") }
    var openSource by remember { mutableStateOf<ArogyaSource?>(null) }
    var sceneMs by remember { mutableStateOf<Long?>(null) }
    val ticks = remember { mutableStateListOf<ArogyaAiTick>() }
    fun secs(ms: Long) = if (ms < 1000) tr("<1 s", "<1 से.") else tr("${ms / 1000} s", "${ms / 1000} से.")
    val online = MetaOnline.hasInternet(context)
    val cloudFirst = ArogyaCloud.cloudFirst(context)

    LaunchedEffect(started) { if (started) { while (true) { elapsed = (System.currentTimeMillis() - startedAt) / 1000; delay(1000) } } }

    // Prepare after Start: fresh sample village, the hero baby, the sample MCP card photo on the phone.
    LaunchedEffect(started) {
        if (!started || ready) return@LaunchedEffect
        busy = true; status = tr("Loading the sample village (fictional people)…", "नमूना गाँव लोड हो रहा है…")
        onLoadSample(); delay(1500)
        val b = store.state.value.beneficiaries.firstOrNull { it.name.startsWith(ArogyaDemoScript.HERO_PREFIX) }
        if (b == null) { busy = false; status = tr("Sample village not found — load it from Home first.", "नमूना गाँव नहीं मिला — होम से लोड करें।"); return@LaunchedEffect }
        val (sp, col) = ArogyaOfficialForms.formFor(b, store.state.value.forms); spec = sp; column = col; hero = b
        cardUri = withContext(Dispatchers.IO) { ArogyaDemoCard.putOnPhone(context, b) }
        runCatching { ai?.warmUp() }
        busy = false; status = ""; ready = true
        Log.i("ArogyaDemo", "AROGYA_DEMO_START hero=${hero?.id} card=${cardUri != null} lang=${if (demoHindi) "hi" else "en"}")
    }

    // Run each scene's real work, narrate, hold a few seconds so the result can be read, then move on.
    LaunchedEffect(scene, ready) {
        if (!ready) return@LaunchedEffect
        val b = hero ?: return@LaunchedEffect
        val sp = spec ?: return@LaunchedEffect
        val sc = scenes[scene]
        if (narrate) {
            speak(if (demoHindi) sc.narrationHi else sc.narration)
            if (ticks.none { it.feature == "reading aloud" }) {
                val net = ArogyaCloud.cloudFirst(context) && MetaOnline.hasInternet(context)
                ticks += ArogyaAiTick("reading aloud", "सुनाना", if (net) "Android voice (online)" else "Android voice (on-phone)", 0, net)
            }
        }
        sceneMs = null
        val t0 = System.currentTimeMillis()
        busy = true; status = ""
        try {
            when (scene) {
                3 -> {
                    // D5: the owner's recording through the real speech-to-text, or the typed note when missing.
                    val voiceAsset = if (demoHindi) ArogyaDemoScript.VOICE_HI else ArogyaDemoScript.VOICE_EN
                    val choice = if (demoHindi) ArogyaLang.VOICE_HINDI else ArogyaLang.VOICE_ENGLISH
                    val pcm = withContext(Dispatchers.IO) {
                        runCatching { context.assets.open(voiceAsset).use { ArogyaWav.decode(it.readBytes()) } }.getOrNull()
                            ?.takeIf { it.size > ArogyaWav.TARGET_RATE }
                    }
                    if (pcm == null) {
                        transcript = if (demoHindi) ArogyaDemoScript.NOTE_HI else ArogyaDemoScript.NOTE_EN
                        transcriptEngine = tr("typed note (no recording)", "लिखा नोट (रिकॉर्डिंग नहीं)")
                        transcriptLive = false
                    } else {
                        transcriptLive = true; transcript = ""
                        val tstt = System.currentTimeMillis()
                        suspend fun cloud(): Pair<String, String>? {
                            status = tr("Transcribing with Muse Voice Transcribe (Meta, online)…", "Muse Voice Transcribe (Meta, ऑनलाइन) से लिख रहे हैं…")
                            val t = withContext(Dispatchers.IO) { ArogyaCloud.transcribe(context, pcm, ArogyaLang.metaLanguages(choice)) }?.takeIf { it.isNotBlank() } ?: return null
                            return t to "Muse Voice Transcribe (Meta, online)"
                        }
                        suspend fun phone(): Pair<String, String>? {
                            status = tr("Transcribing on this phone…", "इसी फ़ोन पर लिख रहे हैं…")
                            val recognizer = AndroWhisperRecognizer(context.applicationContext)
                            try {
                                if (!withContext(Dispatchers.IO) { recognizer.ensureInitialized() }) return null
                                val lang = ArogyaLang.whisperLang(choice)
                                val session = recognizer.newSession(language = lang, maxSeconds = 60, streaming = true)
                                var i = 0
                                while (i < pcm.size) {
                                    session.append(pcm.copyOfRange(i, minOf(i + 3200, pcm.size))); i += 3200
                                    if (i % 12800 == 0 || i >= pcm.size) { transcript = session.partial(); delay(60) }
                                }
                                // Explicit language re-decode ("hi" keeps Hindi out of Urdu script); Auto keeps its own.
                                val t = withContext(Dispatchers.Default) { session.finishWithLanguage(lang) }.takeIf { it.isNotBlank() } ?: return null
                                return t to "Whisper (on this phone, offline)"
                            } finally { withContext(NonCancellable + Dispatchers.IO) { recognizer.release() } }
                        }
                        val heard = if (ArogyaCloud.cloudFirst(context)) (cloud() ?: phone()) else (phone() ?: cloud())
                        val sttMs = System.currentTimeMillis() - tstt
                        if (heard == null) {
                            transcript = if (demoHindi) ArogyaDemoScript.NOTE_HI else ArogyaDemoScript.NOTE_EN
                            transcriptEngine = tr("typed note (voice failed)", "लिखा नोट (आवाज़ नहीं चली)")
                            transcriptLive = false
                        } else {
                            transcript = heard.first; transcriptEngine = heard.second
                            val isCloud = heard.second.startsWith("Muse")
                            ticks += ArogyaAiTick("speech-to-text", "बोल → लिख", heard.second, sttMs, isCloud)
                            Log.i("ArogyaDemo", "AROGYA_DEMO_STT engine=\"${heard.second}\" ms=$sttMs chars=${heard.first.length}")
                        }
                    }
                    status = tr("AI is filling the form…", "AI फ़ॉर्म भर रहा है…")
                    val engine = ai ?: error("AI is not ready")
                    val note = transcript.ifBlank { if (demoHindi) ArogyaDemoScript.NOTE_HI else ArogyaDemoScript.NOTE_EN }
                    val (v, ex) = engine.fillForm(b, sp, column, note)
                    filled = v; formModel = ex.modelLabel; formMs = ex.ms
                    ticks += ArogyaAiTick("form filling + evidence check", "फ़ॉर्म + सबूत जाँच", ex.modelLabel, ex.ms, ex.metaModel)
                }
                4 -> dangers = ArogyaOfficialForms.dangers(sp, filled.mapValues { it.value.value })
                5 -> { status = tr("Searching the official guides…", "आधिकारिक गाइड में खोज…")
                    val engine = ai ?: error("AI is not ready")
                    val r = engine.run(ArogyaAiRequest(ArogyaAiTask.EXPLAIN, ArogyaVisit(id = SOP_VISIT_ID, personId = SOP_VISIT_ID, name = "", kind = ArogyaKind.FOLLOW_UP),
                        question = if (demoHindi) ArogyaDemoScript.QUESTION_HI else ArogyaDemoScript.QUESTION_EN, hindi = demoHindi))
                    sop = r
                    ticks += ArogyaAiTick("guide search + grounded answer", "गाइड खोज + उत्तर", r.provider, r.elapsedMs, r.online) }
                6 -> { status = tr("AI is drafting the ANM message…", "AI ANM संदेश लिख रहा है…")
                    val facts = "Baby of Neha (demo), day 7, Demo Gaon. " + dangers.joinToString("; ") + ". Referred to PHC today."
                    val tanm = System.currentTimeMillis()
                    val got = runCatching { ai?.anmMessageDetailed(facts) }.getOrNull()
                    anm = got?.first.orEmpty().ifBlank { "URGENT: $facts Please advise. — ASHA Sunita (ArogyaDoot)" }
                    anmModel = got?.second.orEmpty()
                    if (got != null && got.first.isNotBlank()) {
                        val by = got.second
                        ticks += ArogyaAiTick("ANM message", "ANM संदेश", by, System.currentTimeMillis() - tanm, !by.startsWith("On-phone"))
                    } }
                7 -> { status = tr("Finding the MCP card photo on the phone…", "फ़ोन पर MCP कार्ड फ़ोटो ढूँढ रहे हैं…")
                    val uri = withContext(Dispatchers.IO) { ArogyaDemoCard.find(context) } ?: error("Sample card photo not found")
                    cardUri = uri; status = tr("AI is reading the card…", "AI कार्ड पढ़ रहा है…")
                    val tcard = System.currentTimeMillis()
                    val got = (ai ?: error("AI is not ready")).readCardDetailed(uri)
                    card = got.first; cardModel = got.second
                    val before = store.state.value.beneficiaries.first { it.id == b.id }
                    val after = ArogyaCardApply.apply(before, got.first.fields)
                    vaccinesAdded = (after.mcp.vaccines.keys - before.mcp.vaccines.keys).map { ArogyaSchedule.VACCINE_NAMES[it] ?: it }
                    store.update { reg -> reg.copy(beneficiaries = reg.beneficiaries.map { if (it.id == b.id) after else it }) }
                    ticks += ArogyaAiTick("card text reading + extraction", "कार्ड पढ़ना", "ML Kit text + ${got.second}", System.currentTimeMillis() - tcard, !got.second.startsWith("On-phone")) }
                8 -> { val vals = filled.mapValues { it.value.value }
                    val cur = store.state.value.beneficiaries.first { it.id == b.id }
                    ArogyaVisitSave.save(store, queue, cur, sp, column, vals, filled.mapValues { it.value.source }, emptySet()); saved = true
                    // The referral is recorded here, by the save — scene 6 only drafts the message.
                    val rec = queue.state.value.visits.firstOrNull { it.id == "official:${b.id}:${sp.id}:$column" }
                    savedReferral = if (rec == null) tr("Visit record not found after save.", "सेव के बाद विज़िट नहीं मिला।")
                        else if (rec.referral == ArogyaReferral.NEEDED) tr("Referral recorded: NEEDED — ", "रेफ़रल दर्ज: ज़रूरी — ") + rec.referralNote
                        else tr("Referral recorded: none needed.", "रेफ़रल दर्ज: ज़रूरत नहीं।")
                    Log.i("ArogyaDemo", "AROGYA_DEMO_SAVED form=${sp.id} col=$column filled=${vals.size} dangers=${dangers.size}") }
                10 -> { status = tr("Loading the sample drug-kit photo…", "दवा किट की नमूना फ़ोटो लोड हो रही है…")
                    val bytes = withContext(Dispatchers.IO) { runCatching { context.assets.open(ArogyaDemoScript.SAM_PHOTO).use { it.readBytes() } }.getOrNull() }
                    if (bytes == null) {
                        samMsg = tr("Sample photo not bundled yet — SAM 3.1 count skipped (its API passed the live V1 test).",
                            "नमूना फ़ोटो अभी नहीं है — SAM 3.1 गिनती छोड़ी (API लाइव V1 में चल चुका है)।")
                    } else {
                        val (bmp, jpeg) = withContext(Dispatchers.IO) {
                            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                            var sample = 1; while (maxOf(opts.outWidth, opts.outHeight) / sample > 2000) sample *= 2
                            val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                                ?: error("Could not open the photo")
                            val scale = 1280f / maxOf(raw.width, raw.height)
                            val b2 = if (scale < 1f) Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt(), (raw.height * scale).toInt(), true) else raw
                            b2 to ByteArrayOutputStream().also { b2.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
                        }
                        samBitmap = bmp
                        if (!withContext(Dispatchers.IO) { MetaOnline.ready(context) }) {
                            samMsg = tr("SAM 3.1 needs internet and the Meta key (Settings). The count is skipped, nothing is guessed.",
                                "SAM 3.1 को इंटरनेट और Meta key चाहिए। गिनती छोड़ी, अंदाज़ा नहीं लगाया।")
                        } else {
                            status = tr("SAM 3.1 is counting…", "SAM 3.1 गिन रहा है…")
                            val tsam = System.currentTimeMillis()
                            val phrase = ArogyaKitPhrases.phrase("ORS")
                            val found = MetaOnline.api(context).sam("arogya_demo_kit", jpeg, phrase)
                            val n = ArogyaSamCount.count(found.map { it.confidence })
                            samMsg = "SAM 3.1 " + tr("found $n \"$phrase\" in ${(System.currentTimeMillis() - tsam) / 1000}s. Sunita confirms before it reaches the register (demo: not written).",
                                "\"$phrase\" $n मिला ${(System.currentTimeMillis() - tsam) / 1000} सेकंड में। रजिस्टर से पहले सुनीता पक्की करती हैं (डेमो: लिखा नहीं)।")
                            ticks += ArogyaAiTick("SAM 3.1 count", "SAM 3.1 गिनती", "SAM 3.1 (Meta, online)", System.currentTimeMillis() - tsam, true)
                            Log.i("ArogyaDemo", "AROGYA_SAM_COUNT item=\"ORS\" phrase=\"$phrase\" found=${found.size} counted=$n ms=${System.currentTimeMillis() - tsam}")
                        }
                    } }
            }
            if (scene in setOf(3, 4, 5, 6, 7, 8, 10)) sceneMs = System.currentTimeMillis() - t0
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            status = tr("This step could not finish: ", "यह चरण पूरा नहीं हुआ: ") + (e.message ?: "")
            Log.w("ArogyaDemo", "AROGYA_DEMO_STEP_FAILED scene=$scene ${e.message}")
        }
        busy = false; if (status.startsWith(tr("This step", "यह चरण")).not()) status = ""
        delay(ArogyaDemoScript.HOLD_MS)
        while (!playing) delay(300)
        if (scene < scenes.lastIndex) scene++
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            if (!started) {
                // D1 start screen: language, banner, mode, Start.
                Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("▶ " + tr("ArogyaDoot · 3-minute demo", "ArogyaDoot · 3 मिनट डेमो"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                        Text(tr("DEMO · sample village (fictional people) · every step is done live by the app",
                            "डेमो · नमूना गाँव (काल्पनिक लोग) · हर चरण ऐप खुद लाइव करता है"), Modifier.padding(12.dp), fontWeight = FontWeight.Bold)
                    }
                    Text(tr("Language / भाषा", "Language / भाषा"), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !demoHindi, onClick = { demoHindi = false }, label = { Text("English") })
                        FilterChip(selected = demoHindi, onClick = { demoHindi = true }, label = { Text("हिन्दी") })
                    }
                    Text(tr("Mode now: ", "अभी मोड: ") + (if (cloudFirst) "☁ Cloud first" else "📱 Local first") + " · " +
                        (if (online) tr("online", "ऑनलाइन") else tr("offline", "ऑफ़लाइन")), fontWeight = FontWeight.Bold)
                    if (!online) Text(tr("Offline now: the full demo runs on the phone; answers take longer.",
                        "अभी ऑफ़लाइन: पूरा डेमो फ़ोन पर चलेगा; उत्तरों में ज़्यादा समय लगेगा।"), style = MaterialTheme.typography.bodySmall)
                    Text(tr("Voice note: the owner's recording when bundled, else the typed note (labelled). The SAM 3.1 count runs only with its sample photo, internet and the Meta key.",
                        "वॉइस नोट: मालिक की रिकॉर्डिंग हो तो वही, नहीं तो लिखा नोट (लिखा होगा)। SAM 3.1 गिनती सिर्फ़ नमूना फ़ोटो, इंटरनेट और Meta key से।"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = onClose) { Text(tr("Close", "बंद")) }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = { startedAt = System.currentTimeMillis(); elapsed = 0; started = true }) { Text(tr("Start", "शुरू करें")) }
                    }
                }
                return@Surface
            }
            if (!ready) {
                Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("▶ " + tr("ArogyaDoot · 3-minute demo", "ArogyaDoot · 3 मिनट डेमो"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (status.isNotBlank()) Text(status)
                    Spacer(Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { started = false; status = "" }) { Text(tr("‹ Back", "‹ पीछे")) }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = onClose) { Text(tr("Close", "बंद")) }
                    }
                }
                return@Surface
            }
            Column(Modifier.fillMaxSize().padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("▶ " + tr("ArogyaDoot · 3-minute demo", "ArogyaDoot · 3 मिनट डेमो"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    // D2-lite: LIVE while the app works, then what it did and how long it took.
                    if (busy) Text("🔴 LIVE", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    else if (sceneMs != null) Text("✓ " + tr("done by app in ", "ऐप ने किया ") + secs(sceneMs!!), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text("%d:%02d".format(elapsed / 60, elapsed % 60), style = MaterialTheme.typography.labelLarge)
                }
                LinearProgressIndicator(progress = (scene + 1) / scenes.size.toFloat(), modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                Text("${scene + 1}/${scenes.size} · " + (if (demoHindi) scenes[scene].hi else scenes[scene].title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(if (demoHindi) scenes[scene].narrationHi else scenes[scene].narration, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp)) }
                if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val b = hero; val sp = spec
                    if (b == null || sp == null) { Text(tr("Preparing…", "तैयारी…")); return@Column }
                    // D3: the persistent panel ticking each AI step live, with the engine that ran it.
                    if (ticks.isNotEmpty()) DemoCard("✨ " + tr("AI at work", "AI काम पर")) {
                        ticks.forEach { t -> Text("• " + (if (demoHindi) t.featureHi else t.feature) + " · ${t.model}" + (if (t.ms > 0) " · ${secs(t.ms)}" else ""),
                            style = MaterialTheme.typography.bodySmall) }
                    }
                    when (scene) {
                        0 -> DemoCard(tr("The problem", "समस्या")) {
                            Text("• " + tr("Visits to plan, paper forms, registers and MCP cards by hand", "विज़िट तय करना, कागज़ी फ़ॉर्म, रजिस्टर और MCP कार्ड हाथ से"))
                            Text("• " + tr("Hundreds of SOPs — hard to know which applies", "सैकड़ों SOP — कौन सा लागू है, जानना मुश्किल"))
                            Text("• " + tr("Patchy network in the village", "गाँव में कमज़ोर नेटवर्क"))
                            Text(tr("Sample village: ${data.beneficiaries.size} fictional people (counted live)", "नमूना गाँव: ${data.beneficiaries.size} काल्पनिक लोग (लाइव गिने)"),
                                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                        1 -> DemoCard(tr("Due this week (official schedules — rules, not AI)", "इस हफ़्ते बाकी (सरकारी शेड्यूल — नियम, AI नहीं)")) {
                            val due = remember(data, visits) { ArogyaSchedule.dueList(data.beneficiaries, visits, LocalDate.now(), 7).filter { !it.done } }
                            Text(tr("${due.size} visits due", "${due.size} विज़िट बाकी"), fontWeight = FontWeight.Bold)
                            due.sortedBy { if (it.name.startsWith(ArogyaDemoScript.HERO_PREFIX)) 0 else 1 }.take(5).forEach { Text("• ${it.name} — ${it.label} (${it.due})") }
                        }
                        2 -> {
                            DemoCard("${sp.title.substringBefore(" (")} · ${sp.columns[column]}") {
                                Text(sp.source, style = MaterialTheme.typography.labelSmall)
                                ArogyaOfficialForms.fieldsFor(sp, column).take(12).forEach { Text("☐ " + it.label + if (it.hint.isNotBlank()) "  — ${it.hint}" else "", style = MaterialTheme.typography.bodySmall) }
                                Text(tr("…and ${ArogyaOfficialForms.fieldsFor(sp, column).size - 12} more rows", "…और ${ArogyaOfficialForms.fieldsFor(sp, column).size - 12} पंक्तियाँ"), style = MaterialTheme.typography.labelSmall)
                            }
                            if (onDeepLink != null) OutlinedButton(onClick = { onDeepLink("guided:${b.id}") }) { Text(tr("Open this visit ↗", "यह विज़िट खोलें ↗")) }
                        }
                        3 -> {
                            DemoCard((if (transcriptLive) "🎤 " else "⌨ ") + tr("Voice note", "वॉइस नोट") + " · $transcriptEngine") {
                                Text("“${transcript.ifBlank { tr("Listening…", "सुन रहे हैं…") }}”", style = MaterialTheme.typography.bodySmall) }
                            if (filled.isNotEmpty()) DemoCard(tr("✨ AI filled ${filled.size} rows · $formModel · ${secs(formMs)}", "✨ AI ने ${filled.size} पंक्तियाँ भरीं · $formModel · ${secs(formMs)}")) {
                                filled.forEach { (id, v) -> val f = sp.fields.firstOrNull { it.id == id } ?: return@forEach
                                    Text("${f.label}: ${v.value}${if (f.type == QType.NUMBER && f.unit.isNotBlank()) " " + f.unit else ""}", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
                                    Text("   ← “${v.evidence}”", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                            }
                        }
                        4 -> DemoCard(if (dangers.isEmpty()) tr("✓ No danger signs", "✓ कोई खतरा नहीं") else tr("⚠ REFER NOW", "⚠ तुरंत रेफ़र करें"), danger = dangers.isNotEmpty()) {
                            dangers.forEach { Text("• $it") }
                            Text(tr("Rules printed on the form — not AI.", "फ़ॉर्म पर छपे नियम — AI नहीं।"), style = MaterialTheme.typography.labelSmall)
                        }
                        5 -> {
                            DemoCard(tr("❓ Question (M1 Q7 — confirmed in both modes)", "❓ सवाल (M1 Q7 — दोनों मोड में पक्का)")) {
                                Text(if (demoHindi) ArogyaDemoScript.QUESTION_HI else ArogyaDemoScript.QUESTION_EN) }
                            sop?.let { r ->
                                if (r.sources.isNotEmpty()) DemoCard(tr("Passages found", "मिले पैराग्राफ")) {
                                    r.sources.take(3).forEach { s ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("📄 ${s.title} · ${s.locator}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                            TextButton(onClick = { openSource = s }) { Text(tr("Open", "खोलें")) }
                                        }
                                        if (s.excerpt.isNotBlank()) ArogyaPassageText(s.copy(excerpt = s.excerpt.take(600) + if (s.excerpt.length > 600) "…" else ""))
                                    }
                                }
                                DemoCard(tr("Answer from the official guides · ${r.provider}", "आधिकारिक गाइड से उत्तर · ${r.provider}")) {
                                    Text(r.text.take(900), style = MaterialTheme.typography.bodySmall)
                                    if (r.quote.isNotBlank()) Text("“${r.quote}” — ${r.quoteSource}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    r.sources.firstOrNull()?.let { s -> Text(tr("Source: ", "स्रोत: ") + "${s.title}, ${s.locator}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                        6 -> {
                            DemoCard(tr("Escalation", "सूचना")) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { demoMsg = tr("Demo: not sent — in real use this calls 108.", "डेमो: भेजा नहीं — असल में 108 पर कॉल होगा।") }) { Text("📞 108") }
                                    OutlinedButton(onClick = { demoMsg = tr("Demo: not sent — in real use this calls the ANM.", "डेमो: भेजा नहीं — असल में ANM को कॉल होगा।") }) { Text("📞 ANM") }
                                    OutlinedButton(onClick = { demoMsg = tr("Demo: not sent — in real use this opens WhatsApp.", "डेमो: भेजा नहीं — असल में WhatsApp खुलेगा।") }) { Text("💬 WhatsApp") }
                                }
                                if (demoMsg.isNotBlank()) Text(demoMsg, fontWeight = FontWeight.Bold)
                                Text(tr("Referral to PHC will be recorded when the visit is saved. (Demo: nothing is sent.)", "PHC रेफ़रल विज़िट सेव होने पर दर्ज होगा। (डेमो: कुछ भेजा नहीं जाता)"), style = MaterialTheme.typography.labelSmall)
                            }
                            if (anm.isNotBlank()) DemoCard(tr("✨ Message to ANM (AI draft, she edits before sending)", "✨ ANM को संदेश (AI ड्राफ़्ट)") + if (anmModel.isNotBlank()) " · $anmModel" else "") { Text(anm, style = MaterialTheme.typography.bodySmall) }
                        }
                        7 -> {
                            DemoCard(tr("📷 Found on this phone", "📷 फ़ोन पर मिला")) { Text("Pictures/ArogyaDoot/${ArogyaDemoScript.CARD_FILE}", style = MaterialTheme.typography.bodySmall) }
                            card?.let { r -> DemoCard(tr("✨ Read from the card (${r.fields.size} values, each found in the card's text)", "✨ कार्ड से पढ़ा (${r.fields.size})") + if (cardModel.isNotBlank()) " · $cardModel" else "") {
                                r.fields.forEach { Text("${it.label}: ${it.value}", style = MaterialTheme.typography.bodySmall) }
                                Text(if (vaccinesAdded.isEmpty()) tr("Already on the MCP card", "MCP कार्ड पर पहले से") else tr("Added to MCP card: ", "MCP कार्ड में जोड़ा: ") + vaccinesAdded.joinToString(), fontWeight = FontWeight.Bold)
                            } }
                        }
                        8 -> {
                            val nextDue = remember(saved) { ArogyaOfficialForms.nextDue(sp, b, column, LocalDate.now()) }
                            OfficialFormGrid(ArogyaOfficialForms.table(sp, b, data.forms), column, hindi = demoHindi)
                            DemoCard(tr("Saved", "सहेजा")) {
                                Text("📄 ${sp.title.substringBefore(" (")} — ${sp.columns[column]}")
                                Text("🗂 " + tr("MCP card: weight + vaccines", "MCP कार्ड: वज़न + टीके"))
                                Text("📒 " + tr("Activity diary + visit list", "डायरी + विज़िट सूची"))
                                Text("📅 " + tr("Next visit: ", "अगली विज़िट: ") + (nextDue?.toString() ?: "—"), fontWeight = FontWeight.Bold)
                                if (savedReferral.isNotBlank()) Text("🏥 $savedReferral", fontWeight = FontWeight.Bold)
                            }
                            if (onDeepLink != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { onDeepLink("registers") }) { Text(tr("Open registers ↗", "रजिस्टर खोलें ↗")) }
                                OutlinedButton(onClick = { onDeepLink("diary") }) { Text(tr("Open diary ↗", "डायरी खोलें ↗")) }
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
                            if (onDeepLink != null) OutlinedButton(onClick = { onDeepLink("registers") }) { Text(tr("Open registers ↗", "रजिस्टर खोलें ↗")) }
                        }
                        10 -> {
                            if (samMsg.isBlank()) Text(tr("Preparing…", "तैयारी…"))
                            else DemoCard(tr("🔢 Drug-kit count", "🔢 दवा किट गिनती")) {
                                samBitmap?.let { Image(it.asImageBitmap(), contentDescription = tr("ORS stock photo", "ORS स्टॉक फ़ोटो"), modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)) }
                                Text(samMsg, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        else -> {
                            DemoCard(tr("Problem solved", "समस्या हल")) {
                                Text("• " + tr("One note did the form, card, diary, registers and claim — with sources for every answer.", "एक नोट से फ़ॉर्म, कार्ड, डायरी, रजिस्टर और दावा — हर उत्तर के साथ स्रोत।"))
                                Text("• " + tr("Danger rules and schedules stayed fixed rules, never AI guesses.", "खतरे के नियम और शेड्यूल तय नियम ही रहे, AI अंदाज़ा नहीं।"))
                            }
                            val phoneTicks = ticks.filter { !it.cloud }; val cloudTicks = ticks.filter { it.cloud }
                            DemoCard(tr("This run: phone vs cloud", "इस बार: फ़ोन बनाम क्लाउड")) {
                                Text("📱 " + tr("On this phone: ", "फ़ोन पर: ") + phoneTicks.map { if (demoHindi) it.featureHi else it.feature }.distinct().ifEmpty { listOf("—") }.joinToString(", "),
                                    style = MaterialTheme.typography.bodySmall)
                                Text("☁ " + tr("Meta cloud: ", "Meta क्लाउड: ") + cloudTicks.map { if (demoHindi) it.featureHi else it.feature }.distinct()
                                    .ifEmpty { listOf(tr("none (offline run)", "कुछ नहीं (ऑफ़लाइन)")) }.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                            }
                            DemoCard(tr("Meta models used", "इस्तेमाल हुए Meta मॉडल")) {
                                val used = ticks.filter { it.cloud }.map { it.model }.distinct()
                                if (used.isEmpty()) {
                                    Text(tr("None this run — everything ran on the phone.", "इस बार कुछ नहीं — सब फ़ोन पर चला।"), style = MaterialTheme.typography.bodySmall)
                                    Text(tr("When online, the app can also use Muse Spark answers, Muse Voice Transcribe and SAM 3.1 counting.",
                                        "ऑनलाइन होने पर ऐप Muse Spark उत्तर, Muse Voice Transcribe और SAM 3.1 गिनती भी कर सकता है।"), style = MaterialTheme.typography.bodySmall)
                                } else used.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                                Text("• " + tr("Danger signs, schedules, incentives: fixed rules (no AI) — from official MoHFW guidelines; clinical review pending.",
                                    "खतरे के संकेत, शेड्यूल, प्रोत्साहन: तय नियम (AI नहीं) — आधिकारिक MoHFW गाइडलाइन से; clinical review pending।"), style = MaterialTheme.typography.bodySmall)
                            }
                            DemoCard(tr("Setting now: ", "अभी सेटिंग: ") + (if (cloudFirst) "☁ Cloud first" else "📱 Local first") + " · " +
                                (if (online) tr("online", "ऑनलाइन") else tr("offline", "ऑफ़लाइन")) + tr(" · ${elapsed / 60}:${"%02d".format(elapsed % 60)} total", " · कुल ${elapsed / 60}:${"%02d".format(elapsed % 60)}")) {
                                Text(tr("No time-saved figure is shown — it was not measured.", "बचत का आँकड़ा नहीं दिखाया — नापा नहीं गया।"), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (scene == 1 || scene == 4 || scene == 9) DemoCard("📏 " + tr("Rules, not AI", "नियम, AI नहीं")) {
                        Text(tr("Danger signs, visit schedules, incentive rates — from official MoHFW guidelines; clinical review pending.",
                            "खतरे के संकेत, विज़िट शेड्यूल, प्रोत्साहन दरें — आधिकारिक MoHFW गाइडलाइन से; clinical review pending।"), style = MaterialTheme.typography.bodySmall)
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
            // R6-lite: read the passage page from the database offline, or open the official URL online.
            openSource?.let { s -> AlertDialog(onDismissRequest = { openSource = null },
                title = { Text(s.title, style = MaterialTheme.typography.titleMedium) },
                text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.locator.isNotBlank()) Text(s.locator.replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.Bold)
                    Text(tr("Text read from the guide:", "गाइड में लिखा है:"), style = MaterialTheme.typography.labelMedium)
                    if (s.excerpt.isBlank()) Text(tr("The passage text was not recorded for this source.", "इस स्रोत का पाठ उपलब्ध नहीं है।")) else ArogyaPassageText(s)
                    if (s.highlights.isNotEmpty()) Text(tr("Highlighted: the sentences this answer came from.", "हाइलाइट: जिन वाक्यों से उत्तर बना।"), style = MaterialTheme.typography.bodySmall)
                    if (!s.url.startsWith("http")) Text(tr("No online copy is linked for this document.", "इस दस्तावेज़ की ऑनलाइन प्रति उपलब्ध नहीं है।"), style = MaterialTheme.typography.bodySmall)
                } },
                confirmButton = { if (s.url.startsWith("http")) Button(onClick = { runCatching { uriHandler.openUri(s.url) }.onFailure { Toast.makeText(context, "No browser or PDF viewer is available.", Toast.LENGTH_LONG).show() } }) { Text(tr("Open guideline", "गाइडलाइन खोलें")) } },
                dismissButton = { TextButton(onClick = { openSource = null }) { Text(tr("Close", "बंद करें")) } }) }
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
