package com.example.arfas

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Part B: the presenter-controlled walkthrough. No timers, no auto-advance: the live
 * step runs once when a scene opens, narration reads every sentence to the end, and
 * the presenter moves with Back / Replay / Next. It drives the REAL features on the
 * sample village (fictional people). The script data lives in ArogyaDemoScript.kt
 * (pure, host-tested). Nothing is sent to anyone: calls and WhatsApp show a
 * "Demo: not sent" note instead of placing anything.
 */

/**
 * Demo narration over Android TTS: one sentence at a time (per-sentence queue with
 * UtteranceProgressListener), rate 0.9, offline voices preferred. When no offline
 * voice exists for the language it falls back to any voice, labelled online; when no
 * voice exists at all the captions still show and speakAll returns false.
 */
class ArogyaDemoSpeech(private val tts: TextToSpeech?) {
    private var gen = 0
    private val main = Handler(Looper.getMainLooper())
    var voiceLabel = "Android voice"
        private set
    var voiceCloud = false
        private set

    fun stop() { gen++; runCatching { tts?.stop() } }

    /** Offline-voice check for Hindi and English: matching locale, no network needed. */
    fun offlineVoices(): Pair<Boolean, Boolean> {
        val vs = runCatching { tts?.voices.orEmpty().toList() }.getOrDefault(emptyList())
        return vs.any { it.locale?.language == "hi" && !it.isNetworkConnectionRequired } to
            vs.any { it.locale?.language == "en" && !it.isNetworkConnectionRequired }
    }

    fun speakAll(context: Context, sentences: List<String>, hindi: Boolean,
                 onSentence: (Int) -> Unit, onDone: () -> Unit): Boolean {
        stop()
        val t = tts ?: return false
        val lang = if (hindi) "hi" else "en"
        val vs = runCatching { t.voices.orEmpty().toList() }.getOrDefault(emptyList())
            .filter { it.locale?.language == lang }
        if (vs.isEmpty()) { Log.w("ArogyaDemo", "AROGYA_DEMO_TTS lang=$lang voices=none"); return false }
        val pick = vs.sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.name })).first()
        voiceCloud = pick.isNetworkConnectionRequired
        voiceLabel = "Android voice (${if (voiceCloud) "online" else "offline"})"
        val g = gen
        Log.i("ArogyaDemo", "AROGYA_DEMO_TTS lang=$lang voice=${pick.name} offline=${!voiceCloud} sentences=${sentences.size}")
        runCatching { t.voice = pick; t.setSpeechRate(0.9f) }
        fun say(i: Int) {
            if (g != gen || i >= sentences.size) return
            main.post { if (g == gen) onSentence(i) }
            runCatching { t.speak(sentences[i], TextToSpeech.QUEUE_FLUSH, null, "demo-$g-$i") }
        }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {}
            override fun onDone(id: String) {
                val i = id.substringAfterLast("-").toIntOrNull() ?: return
                if (id != "demo-$g-$i") return
                if (i + 1 >= sentences.size) main.post { if (g == gen) onDone() } else say(i + 1)
            }
            override fun onError(id: String) { onDone(id) }
        })
        say(0)
        return true
    }
}

@Composable
fun ArogyaDemoTour(ai: ArogyaVisitAi?, queue: ArogyaVisitQueue, visits: List<ArogyaVisit>, hindi: Boolean, tts: TextToSpeech?,
                   onLoadSample: () -> Unit, onClose: () -> Unit, onDeepLink: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val speech = remember(tts) { ArogyaDemoSpeech(tts) }
    DisposableEffect(speech) { onDispose { speech.stop() } }
    var started by remember { mutableStateOf(false) }
    var demoHindi by remember(hindi) { mutableStateOf(hindi) }
    fun tr(en: String, hi: String) = if (demoHindi) hi else en
    val store = remember { ArogyaRegistryStore.get(context) }
    val data by store.state.collectAsState()
    // Three complete workflows; the presenter picks one on the demo home screen.
    var wf by remember { mutableStateOf(2) }
    val scenes = remember(wf) { ArogyaDemoScript.flow(wf) }
    val n = scenes.size
    var scene by remember { mutableStateOf(0) }
    var ready by remember { mutableStateOf(false) }
    var narrate by remember { mutableStateOf(true) }
    var caption by remember { mutableStateOf(-1) }
    var narrDone by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var exitAsk by remember { mutableStateOf(false) }
    var libraryOpen by remember { mutableStateOf(false) }
    var aiWorkOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var liveExpanded by remember { mutableStateOf(false) }
    var tickMark by remember { mutableStateOf(0) }
    var manualJob by remember { mutableStateOf<Job?>(null) }
    var ran by remember { mutableStateOf(setOf<Int>()) }
    var replayTick by remember { mutableStateOf(0) }
    // Results of each scene (real outputs).
    var hero by remember { mutableStateOf<Beneficiary?>(null) }
    var spec by remember { mutableStateOf<FormSpec?>(null) }
    var column by remember { mutableStateOf(0) }
    var filled by remember { mutableStateOf<Map<String, FilledValue>>(emptyMap()) }
    var formModel by remember { mutableStateOf("") }
    var formMs by remember { mutableStateOf(0L) }
    var formConfirmed by remember { mutableStateOf(false) }
    var transcript by remember { mutableStateOf("") }
    var transcriptEngine by remember { mutableStateOf("") }
    var transcriptLive by remember { mutableStateOf(false) }
    var dangers by remember { mutableStateOf<List<String>>(emptyList()) }
    var dangersRan by remember { mutableStateOf(false) }
    var sop by remember { mutableStateOf<ArogyaAiResult?>(null) }
    var sopFollow by remember { mutableStateOf<ArogyaAiResult?>(null) }
    var sopOnline by remember { mutableStateOf<ArogyaAiResult?>(null) }
    var sopOnlineSkip by remember { mutableStateOf(false) }
    var sopGold by remember { mutableStateOf<ArogyaAiResult?>(null) }
    var anm by remember { mutableStateOf("") }
    var anmModel by remember { mutableStateOf("") }
    var demoMsg by remember { mutableStateOf("") }
    var familyText by remember { mutableStateOf("") }
    var familyModel by remember { mutableStateOf("") }
    var personPreview by remember { mutableStateOf<Beneficiary?>(null) }
    var personMsg by remember { mutableStateOf("") }
    var personAgreed by remember { mutableStateOf(false) }
    var personSaved by remember { mutableStateOf(false) }
    var personDue by remember { mutableStateOf("") }
    var savedSummary by remember { mutableStateOf("") }
    var cardDoc by remember { mutableStateOf<ArogyaDocRender.FilledDoc?>(null) }
    var cardShareBusy by remember { mutableStateOf(false) }
    var guideDocs by remember { mutableStateOf<List<GuideDoc>?>(null) }
    var card by remember { mutableStateOf<ArogyaCardReading?>(null) }
    var cardModel by remember { mutableStateOf("") }
    var cardChecked by remember { mutableStateOf(setOf<Int>()) }
    var cardApplied by remember { mutableStateOf(false) }
    var vaccinesAdded by remember { mutableStateOf<List<String>>(emptyList()) }
    var weightAdded by remember { mutableStateOf("") }
    var medScan by remember { mutableStateOf<MedScan?>(null) }
    var medEngine by remember { mutableStateOf("") }
    var medMs by remember { mutableStateOf(0L) }
    var medOcrLen by remember { mutableStateOf(0) }
    var medMatched by remember { mutableStateOf("") }
    var medSaved by remember { mutableStateOf(false) }
    var samBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var samMsg by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var savedVisit by remember { mutableStateOf<ArogyaVisit?>(null) }
    var savedReferral by remember { mutableStateOf("") }
    var shareMsg by remember { mutableStateOf("") }
    var quizItems by remember { mutableStateOf<List<ArogyaQuizItem>>(emptyList()) }
    var quizChoice by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var quizModel by remember { mutableStateOf("") }
    var quizMs by remember { mutableStateOf(0L) }
    var openSource by remember { mutableStateOf<ArogyaSource?>(null) }
    var sourceErr by remember { mutableStateOf("") }
    var photoFor by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var shotUri by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Uri?>(null) }
    val ticks = remember { mutableStateListOf<ArogyaAiTick>() }
    // What this run changed: shown on the save and "Everything updated" screens (real values from the store).
    var updates by remember { mutableStateOf<List<String>>(emptyList()) }
    var stockBefore by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var stockDelta by remember { mutableStateOf<List<String>>(emptyList()) }
    var refillText by remember { mutableStateOf("") }
    // "Open to check": per screen, the records this run wrote, each opened in the real document viewer.
    var opensBy by remember { mutableStateOf<Map<String, List<Pair<String, String>>>>(emptyMap()) }
    var viewDoc by remember { mutableStateOf<Pair<String, ArogyaDocRender.FilledDoc>?>(null) }
    var formNote by remember { mutableStateOf("") }
    var personEngine by remember { mutableStateOf("") }
    fun secs(ms: Long) = if (ms < 1000) tr("<1 s", "<1 से.") else tr("${ms / 1000} s", "${ms / 1000} से.")
    val online = MetaOnline.hasInternet(context)
    fun lines(): List<String> = if (scene >= n) listOf(tr("The demo is complete. Thank you.", "डेमो पूरा हुआ। धन्यवाद।"))
        else if (demoHindi) scenes[scene].narrationHi else scenes[scene].narration

    // Navigation: stop narration and any running work cleanly, then move.
    // Demo UI pass: Local | Cloud toggle on AI scenes (the same setting as Home's Offline | Online).
    var cloudMode by remember { mutableStateOf(ArogyaCloud.cloudFirst(context)) }
    // Item 7 + 9: Neha's card as it was when this demo run started (Start rebuilds the sample
    // village, so every run begins from the same card); the demo view highlights what changed.
    var cardBeforeHtml by remember { mutableStateOf("") }
    var cardChanged by remember { mutableStateOf(-1) }
    val aiScenes = remember { setOf("addperson", "voice", "anm", "explain", "sops", "card", "medscan", "practice") }
    fun balances(): Map<String, Int> = store.state.value.stock.groupBy { it.item }.mapValues { (_, l) -> l.sumOf { it.qty } }
    fun addOpens(screen: String, vararg o: Pair<String, String>) { opensBy = opensBy + (screen to (opensBy[screen].orEmpty() + o).distinctBy { it.second }) }
    /** Opens one record in the document viewer: "card:<id>", "form:<formId>:<id>:<col>", "village:<id>", "anc:<id>", "imm:<id>", "stock". */
    fun openDoc(key: String) {
        val reg = store.state.value; val today = LocalDate.now(); val parts = key.split(":")
        fun regDoc(title: String, table: RegTable, pid: String?, mark: Set<String> = emptySet()): ArogyaDocRender.FilledDoc {
            fun hit(r: RegRow): Boolean = (pid != null && r.beneficiaryId == pid) || mark.contains(r.cells.firstOrNull().orEmpty())
            val rows = table.rows.sortedBy { if (hit(it)) 0 else 1 }
                .map { r -> if (hit(r)) r.copy(cells = r.cells.mapIndexed { i, c -> if (i == 0) "★ $c" else c }) else r }
            return store.tableDoc(ArogyaRegisterTables.toFormTable(title, tr("★ = updated today · Pramaan register · ", "★ = आज अपडेट · Pramaan रजिस्टर · ") + today, RegTable(table.headers, rows), sample = true))
        }
        runCatching {
            when (parts[0]) {
                "card" -> {
                    val pp = reg.beneficiaries.firstOrNull { it.id == parts.getOrNull(1) } ?: error(tr("person not found", "व्यक्ति नहीं मिला"))
                    val d = store.mcpDoc(pp, reg.beneficiaries, today)
                    val html = if (pp.id == hero?.id && cardBeforeHtml.isNotBlank()) ArogyaDocRender.demoMark(cardBeforeHtml, d.html, demoHindi).first else d.html
                    viewDoc = (tr("MCP card — ", "MCP कार्ड — ") + pp.name) to d.copy(html = html)
                }
                "form" -> {
                    val fs = ArogyaOfficialForms.byId(parts.getOrNull(1).orEmpty()) ?: error("form")
                    val pp = reg.beneficiaries.firstOrNull { it.id == parts.getOrNull(2) } ?: error(tr("person not found", "व्यक्ति नहीं मिला"))
                    viewDoc = (fs.title + " — " + pp.name) to store.tableDoc(ArogyaOfficialForms.table(fs, pp, reg.forms), parts.getOrNull(3)?.toIntOrNull())
                }
                "village" -> viewDoc = tr("Village register", "गाँव रजिस्टर") to regDoc(tr("Village register", "गाँव रजिस्टर"), ArogyaRegisterTables.villageTable(reg.beneficiaries, today, demoHindi), parts.getOrNull(1))
                "anc" -> viewDoc = tr("Pregnant women (ANC) register", "गर्भवती (ANC) रजिस्टर") to regDoc(tr("Pregnant women (ANC) register", "गर्भवती (ANC) रजिस्टर"), ArogyaRegisterTables.ancTable(reg.beneficiaries, today, demoHindi), parts.getOrNull(1))
                "imm" -> viewDoc = tr("Immunization register", "टीकाकरण रजिस्टर") to regDoc(tr("Immunization register", "टीकाकरण रजिस्टर"), ArogyaRegisterTables.immunizationTable(reg.beneficiaries, java.time.YearMonth.from(today), today, demoHindi), parts.getOrNull(1))
                "stock" -> viewDoc = tr("Drug-kit stock register", "दवा किट स्टॉक रजिस्टर") to regDoc(tr("Drug-kit stock register", "दवा किट स्टॉक रजिस्टर"),
                    ArogyaRegistryLogic.stockRegister(reg.stock, reg.stockItems, java.time.YearMonth.from(today), today, demoHindi), null, stockDelta.map { it.substringBefore(":") }.toSet())
            }
            Unit
        }.onFailure { status = tr("Could not open: ", "नहीं खुला: ") + (it.message ?: "") }
    }
    /** A fresh run of the chosen workflow: every result of the previous run is cleared. */
    fun resetRun() {
        ran = emptySet(); hero = null; spec = null; column = 0; filled = emptyMap(); formConfirmed = false; transcript = ""; transcriptEngine = ""
        dangers = emptyList(); dangersRan = false; sop = null; sopFollow = null; sopOnline = null; sopOnlineSkip = false; anm = ""; anmModel = ""; demoMsg = ""
        familyText = ""; personPreview = null; personMsg = ""; personAgreed = false; personSaved = false; personDue = ""; savedSummary = ""; cardDoc = null
        card = null; cardApplied = false; vaccinesAdded = emptyList(); weightAdded = ""; medScan = null; medSaved = false; samBitmap = null; samMsg = ""
        saved = false; savedVisit = null; savedReferral = ""; shareMsg = ""; quizItems = emptyList(); quizChoice = emptyMap()
        updates = emptyList(); stockBefore = emptyMap(); stockDelta = emptyList(); refillText = ""; ticks.clear(); tickMark = 0; status = ""
        opensBy = emptyMap(); viewDoc = null; formNote = ""; personEngine = ""
    }
    fun go(i: Int) { speech.stop(); manualJob?.cancel(); status = ""; aiWorkOpen = false; liveExpanded = false; tickMark = ticks.size; scene = i.coerceIn(0, n) }
    fun replay() { speech.stop(); manualJob?.cancel(); ran = ran - scene; status = ""; aiWorkOpen = false; liveExpanded = false; tickMark = ticks.size; replayTick++ }

    // Resume: the last scene is saved; the start screen offers it back.
    val demoPrefs = remember { context.getSharedPreferences("arogya_demo", 0) }
    // Item 11: the last measured time of each AI feature, online and offline, kept across runs
    // (flipping Local | Cloud in a scene measures the other mode). Only measured ticks count.
    var rememberedTimes by remember { mutableStateOf(ArogyaDemoTicks.timesDecode(demoPrefs.getString("times", "").orEmpty())) }
    LaunchedEffect(ticks.size) {
        if (ticks.isEmpty()) return@LaunchedEffect
        rememberedTimes = ticks.fold(rememberedTimes) { m, t -> ArogyaDemoTicks.timesRecord(m, t) }
        demoPrefs.edit().putString("times", ArogyaDemoTicks.timesEncode(rememberedTimes)).apply()
    }
    LaunchedEffect(scene, started) { if (started) demoPrefs.edit().putInt("last_scene_w$wf", scene).apply() }

    // Prepare after Start: fresh sample village, the hero baby, the guide counts, a warm model.
    LaunchedEffect(started) {
        if (!started || ready) return@LaunchedEffect
        busy = true; status = tr("Loading the sample village (fictional people)…", "नमूना गाँव लोड हो रहा है…")
        onLoadSample()
        // Suspend until the store holds the fresh sample (today's dates) — no fixed sleep.
        var b: Beneficiary? = null
        for (i in 0 until 150) {
            b = store.state.value.beneficiaries.firstOrNull { it.name.startsWith(ArogyaDemoScript.HERO_PREFIX) && it.demo }
            if (b != null) break
            delay(100)
        }
        if (b == null) { busy = false; status = tr("Sample village not found — load it from Home first.", "नमूना गाँव नहीं मिला — होम से लोड करें।"); return@LaunchedEffect }
        // Who this workflow is about: W1 a newly registered pregnant woman (until the slip photo adds her, the
        // slip's own details stand in, not yet saved), W2 Baby of Neha, W3 Child Rohan on the sick-child form.
        when (wf) {
            1 -> {
                b = Beneficiary(id = "demo-person-w1", name = ArogyaDemoScript.W1_NAME, relativeName = ArogyaDemoScript.W1_RELATIVE, age = ArogyaDemoScript.W1_AGE,
                    phone = ArogyaDemoScript.W1_PHONE, household = ArogyaDemoScript.W1_HOUSE, village = "Rampur", category = BenCategory.PREGNANT, sex = "F",
                    lmp = LocalDate.now().minusWeeks(ArogyaDemoScript.W1_WEEKS).toString(), demo = true)
                spec = ArogyaOfficialForms.MCP_ANC; column = 0
            }
            3 -> {
                val rohan: Beneficiary = store.state.value.beneficiaries.firstOrNull { it.name == ArogyaDemoScript.W3_NAME && it.demo } ?: b
                b = rohan
                spec = ArogyaOfficialForms.SICK; column = ArogyaOfficialForms.columnFor(rohan, "sick", store.state.value.forms)
            }
            else -> { val nb: Beneficiary = b; val (s0, c0) = ArogyaOfficialForms.formFor(nb, store.state.value.forms); spec = s0; column = c0 }
        }
        val hb: Beneficiary = b ?: return@LaunchedEffect
        hero = hb
        stockBefore = balances()
        val sp = spec ?: return@LaunchedEffect; val col = column
        cardBeforeHtml = runCatching { store.mcpDoc(hb, store.state.value.beneficiaries, LocalDate.now()).html }.getOrDefault("")
        cardChanged = -1
        Log.i("ArogyaDemo", "AROGYA_DEMO_HERO form=${sp.id} column=$col(${sp.columns.getOrElse(col) { "" }})")
        status = tr("Reading the guide library on this phone…", "फ़ोन की गाइड लाइब्रेरी पढ़ रहे हैं…")
        guideDocs = withContext(Dispatchers.IO) { runCatching { ai?.guideLibrary() }.getOrNull() }
        runCatching { ai?.warmUp() }
        busy = false; status = ""; ready = true
        Log.i("ArogyaDemo", "AROGYA_DEMO_START hero=${hero?.id} lang=${if (demoHindi) "hi" else "en"}")
    }

    // Narration for the open scene (or the end screen): every sentence to the end,
    // captions highlighting the sentence being read. Language switch restarts it.
    LaunchedEffect(scene, demoHindi, ready, narrate, replayTick, started) {
        if (!started || !ready) return@LaunchedEffect
        if (!narrate) { caption = -1; return@LaunchedEffect }
        caption = 0; narrDone = false
        val ok = speech.speakAll(context, lines(), demoHindi, onSentence = { caption = it }, onDone = { narrDone = true })
        if (ok && ticks.none { it.feature == "reading aloud" })
            ticks += ArogyaAiTick("reading aloud", "सुनाना", speech.voiceLabel, 0, speech.voiceCloud)
    }

    // The scene's live step runs once when the scene opens; its result stays until
    // Replay. Leaving the scene cancels this block, stopping the work cleanly.
    LaunchedEffect(scene, ready, replayTick) {
        if (!started || !ready || scene >= n || scene in ran) return@LaunchedEffect
        ran = ran + scene
        val b = hero ?: return@LaunchedEffect
        val sp = spec ?: return@LaunchedEffect
        busy = true; status = ""
        try {
            when (scenes.getOrNull(scene)?.id) {
                "voice" -> {
                    val voiceAsset = if (demoHindi) ArogyaDemoScript.VOICE_HI else ArogyaDemoScript.VOICE_EN
                    val choice = if (demoHindi) ArogyaLang.VOICE_HINDI else ArogyaLang.VOICE_ENGLISH
                    // Only workflow 2 has a recording; workflows 1 and 3 use their typed note (labelled).
                    val pcm = if (wf != 2) null else withContext(Dispatchers.IO) {
                        runCatching { context.assets.open(voiceAsset).use { ArogyaWav.decode(it.readBytes()) } }.getOrNull()
                            ?.takeIf { it.size > ArogyaWav.TARGET_RATE }
                    }
                    if (pcm == null) {
                        transcript = ArogyaDemoScript.note(wf, demoHindi)
                        transcriptEngine = tr("typed note (recording not bundled — speech-to-text skipped)",
                            "लिखा नोट (रिकॉर्डिंग नहीं है — बोल→लिख छोड़ा)")
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
                                val t = withContext(Dispatchers.Default) { session.finishWithLanguage(lang) }.takeIf { it.isNotBlank() } ?: return null
                                return t to "Whisper (on this phone, offline)"
                            } finally { withContext(NonCancellable + Dispatchers.IO) { recognizer.release() } }
                        }
                        val heard = if (ArogyaCloud.cloudFirst(context)) (cloud() ?: phone()) else phone()
                        val sttMs = System.currentTimeMillis() - tstt
                        if (heard == null) {
                            transcript = ArogyaDemoScript.note(wf, demoHindi)
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
                    val note = transcript.ifBlank { ArogyaDemoScript.note(wf, demoHindi) }
                    val (v, ex) = engine.fillForm(b, sp, column, note)
                    filled = v; formModel = ex.modelLabel; formMs = ex.ms; formNote = ex.note
                    ticks += ArogyaAiTick("form filling + evidence check", "फ़ॉर्म + सबूत जाँच", ex.modelLabel, ex.ms, ex.metaModel)
                }
                "danger" -> {
                    // Fixed rules only: the danger-sign engine on her words + the form's own action rules.
                    val words = transcript.ifBlank { ArogyaDemoScript.note(wf, demoHindi) }
                    dangers = ArogyaDemoScript.dangerLines(words, sp, filled.mapValues { it.value.value },
                        ArogyaVisit(name = b.name, kind = if (sp.id == "annex9") ArogyaKind.NEWBORN else if (b.category == BenCategory.CHILD) ArogyaKind.CHILD else ArogyaKind.FOLLOW_UP,
                            date = LocalDate.now().toString(), birthDate = if (b.category == BenCategory.CHILD) b.dob else ""), demoHindi)
                    dangersRan = true
                    Log.i("ArogyaDemo", "AROGYA_DEMO_DANGER wf=$wf found=${dangers.size}")
                }
                "anm" -> {
                    if (dangers.isEmpty() && !dangersRan) dangers = ArogyaDemoScript.dangerLines(transcript.ifBlank { ArogyaDemoScript.note(wf, demoHindi) }, sp,
                        filled.mapValues { it.value.value }, ArogyaVisit(name = b.name, kind = ArogyaKind.NEWBORN, date = LocalDate.now().toString(), birthDate = b.dob), false)
                    status = tr("AI is drafting the ANM message…", "AI ANM संदेश लिख रहा है…")
                    val facts = "${b.name}, ${sp.columns.getOrElse(column) { "" }}, Rampur. " + dangers.joinToString("; ") { it.removePrefix("⚠ ").removePrefix("• ") } + ". Referred to PHC Rampur today."
                    val tanm = System.currentTimeMillis()
                    val got = runCatching { ai?.anmMessageDetailed(facts, ArogyaVisit(name = "Baby of Neha", village = "Rampur")) }.getOrNull()
                    anm = got?.first.orEmpty().ifBlank { "URGENT: $facts Please advise. — ASHA Sunita (Pramaan)" }
                    anmModel = got?.second.orEmpty()
                    if (got != null && got.first.isNotBlank()) {
                        val by = got.second
                        ticks += ArogyaAiTick("ANM message", "ANM संदेश", by, System.currentTimeMillis() - tanm, !by.startsWith("On-phone"))
                    }
                }
                "explain" -> {
                    status = tr("Explaining to the family in Hindi…", "परिवार को हिंदी में समझा रहे हैं…")
                    val engine = ai ?: error("AI is not ready")
                    // Source: the fixed referral advice (if danger signs) + the guide answer from the Ask screen.
                    // Only when neither exists does it search the guides itself.
                    val r = sop ?: if (dangers.isEmpty()) engine.run(ArogyaAiRequest(ArogyaAiTask.EXPLAIN,
                        ArogyaVisit(id = SOP_VISIT_ID, personId = SOP_VISIT_ID, name = "", kind = ArogyaKind.FOLLOW_UP),
                        question = ArogyaDemoScript.question(wf, demoHindi), hindi = demoHindi)).also { sop = it } else null
                    val source = listOf(ArogyaDemoScript.referralAdvice(dangers), r?.text.orEmpty()).filter { it.isNotBlank() }.joinToString("\n")
                    val (txt, model, ms) = engine.familyExplainDetailed(source, r?.sources?.firstOrNull()?.excerpt.orEmpty(), true)
                    familyText = txt; familyModel = model
                    ticks += ArogyaAiTick("family explanation", "परिवार को समझाना", model, ms, !model.startsWith("On-phone"))
                }
                "guides" -> { status = tr("Reading the guide library on this phone…", "फ़ोन की गाइड लाइब्रेरी पढ़ रहे हैं…")
                    if (guideDocs == null) guideDocs = withContext(Dispatchers.IO) { ai?.guideLibrary() } }
                "sops" -> { status = tr("Searching the official guides…", "आधिकारिक गाइड में खोज…")
                    val engine = ai ?: error("AI is not ready")
                    suspend fun ask(q: String) = engine.run(ArogyaAiRequest(ArogyaAiTask.EXPLAIN,
                        ArogyaVisit(id = SOP_VISIT_ID, personId = SOP_VISIT_ID, name = "", kind = ArogyaKind.FOLLOW_UP),
                        question = q, hindi = demoHindi))
                    val r = ask(ArogyaDemoScript.question(wf, demoHindi))
                    sop = r
                    ticks += ArogyaAiTick("guide search + grounded answer", "गाइड खोज + उत्तर", r.provider, r.elapsedMs, r.online)
                    status = tr("Asking the follow-up…", "दोबारा पूछ रहे हैं…")
                    val f = ask(ArogyaDemoScript.followUp(wf, demoHindi))
                    sopFollow = f
                    ticks += ArogyaAiTick("guide follow-up", "दोबारा सवाल", f.provider, f.elapsedMs, f.online)
                    // Item 8: one Check online moment (skipped honestly when offline).
                    status = tr("Checking the answer online…", "उत्तर ऑनलाइन जाँच रहे हैं…")
                    if (MetaOnline.ready(context)) {
                        val o = engine.run(ArogyaAiRequest(ArogyaAiTask.EXPLAIN,
                            ArogyaVisit(id = SOP_VISIT_ID, personId = SOP_VISIT_ID, name = "", kind = ArogyaKind.FOLLOW_UP),
                            question = ArogyaDemoScript.question(wf, demoHindi),
                            hindi = demoHindi, onlineCheck = true))
                        sopOnline = o
                        ticks += ArogyaAiTick("online check", "ऑनलाइन जाँच", o.provider, o.elapsedMs, true)
                    } else sopOnlineSkip = true }
                // The MCP scene opens Neha's official card, filled from the visit.
                "mcpcard" -> { status = tr("Opening the official card…", "आधिकारिक कार्ड खोल रहे हैं…")
                    runCatching {
                        val cur = store.state.value.beneficiaries.firstOrNull { it.id == b.id }
                        if (cur != null) {
                            val d = store.mcpDoc(cur, store.state.value.beneficiaries, LocalDate.now())
                            val (html, changed) = ArogyaDocRender.demoMark(cardBeforeHtml, d.html, demoHindi)
                            cardDoc = d.copy(html = html); cardChanged = changed
                        }
                    } }
                "saved" -> {
                    if (!saved && !formConfirmed) {
                        // AI output is a proposal: nothing is saved until Sunita confirms the values.
                        status = tr("Sunita confirms the values first — tap ✓ below.", "पहले सुनीता मान पक्के करें — नीचे ✓ दबाएँ।")
                        ran = ran - scene; busy = false; return@LaunchedEffect
                    }
                    if (!saved) {
                        val vals = filled.mapValues { it.value.value }
                        // Workflow 1 without the slip photo: the slip's details are registered now (labelled).
                        if (store.state.value.beneficiaries.none { it.id == b.id }) {
                            store.update { reg -> reg.copy(beneficiaries = reg.beneficiaries + b) }
                            updates = updates + tr("✓ Village + pregnancy register: ${b.name} added (typed from the slip, photo skipped)",
                                "✓ गाँव + गर्भवती रजिस्टर: ${b.name} जोड़ी गईं (पर्ची से लिखा, फ़ोटो नहीं)")
                        }
                        val cur = store.state.value.beneficiaries.first { it.id == b.id }
                        savedSummary = ArogyaVisitSave.save(store, queue, cur, sp, column, vals, filled.mapValues { it.value.source }, emptySet())
                        saved = true
                        val rec = queue.state.value.visits.firstOrNull { it.id == "official:${b.id}:${sp.id}:$column" }
                        savedVisit = rec
                        savedReferral = if (rec == null) tr("Visit record not found after save.", "सेव के बाद विज़िट नहीं मिला।")
                            else if (rec.referral == ArogyaReferral.NEEDED) tr("Referral recorded: NEEDED — ", "रेफ़रल दर्ज: ज़रूरी — ") + rec.referralNote
                            else tr("Referral recorded: none needed.", "रेफ़रल दर्ज: ज़रूरत नहीं।")
                        // HBNC has no medicine rows: the medicines Sunita said she gave go through the drug kit's
                        // "Give to person" path (stock line + diary + card IFA total), read by fixed rules from her note.
                        if (sp.id == "annex9") {
                            val mum = store.state.value.beneficiaries.firstOrNull { it.category == BenCategory.MOTHER && it.household == cur.household && cur.household.isNotBlank() }
                            val gives = ArogyaDemoScript.medicinesGiven(transcript, ArogyaRegistryLogic.catalog(store.state.value.stockItems).map { it.name })
                            if (mum != null) gives.forEach { g ->
                                store.update { reg -> ArogyaOfficialForms.applyGive(reg, mum, g.item, g.qty, "demo-w2-${g.item.filter(Char::isLetterOrDigit)}", "demo") }
                            }
                        }
                        val after = balances()
                        stockDelta = (stockBefore.keys + after.keys).mapNotNull { k ->
                            val a = stockBefore[k] ?: 0; val z = after[k] ?: 0
                            if (a == z) null else "$k: $a → $z"
                        }
                        val mcpCol = if (sp.id == "annex9") ArogyaOfficialForms.mcpColumnForHbnc(column)?.let { ArogyaOfficialForms.MCP_BABY.columns[it] } else null
                        updates = updates + listOfNotNull(
                            "✓ " + sp.title.substringBefore(" (").substringBefore(" —") + " · " + sp.columns.getOrElse(column) { "" },
                            if (sp.id == "mcp_anc") tr("✓ MCP card p.3 identification + p.5 ANC visit ${column + 1}", "✓ MCP कार्ड पेज 3 पहचान + पेज 5 ANC विज़िट ${column + 1}") else null,
                            mcpCol?.let { tr("✓ MCP card p.7 · $it column (baby + mother)", "✓ MCP कार्ड पेज 7 · $it कॉलम (बच्चा + माँ)") },
                            vals["weight"]?.takeIf { it.isNotBlank() && sp.id != "mcp_anc" }?.let { tr("✓ Growth chart · $it kg", "✓ वज़न चार्ट · $it kg") },
                            savedVisit?.takeIf { it.referral == ArogyaReferral.NEEDED }?.let { tr("✓ Referral recorded · ", "✓ रेफ़रल दर्ज · ") + it.referralNote },
                            savedVisit?.nextDate?.takeIf { it.isNotBlank() }?.let { tr("✓ Next due: ", "✓ अगली तारीख़: ") + "${savedVisit?.nextReason.orEmpty()} ($it)" },
                            tr("✓ ASHA diary · home visit", "✓ आशा डायरी · गृह भ्रमण")) +
                            stockDelta.map { tr("✓ Stock register · ", "✓ स्टॉक रजिस्टर · ") + it }
                        addOpens("saved", (tr("Official form · ", "सरकारी फ़ॉर्म · ") + sp.columns.getOrElse(column) { "" }) to "form:${sp.id}:${cur.id}:$column",
                            tr("MCP card", "MCP कार्ड") to "card:${cur.id}")
                        if (sp.id == "mcp_anc") addOpens("saved", tr("Pregnant women (ANC) register", "गर्भवती (ANC) रजिस्टर") to "anc:${cur.id}")
                        if (sp.id == "annex9") store.state.value.beneficiaries.firstOrNull { it.category == BenCategory.MOTHER && it.household == cur.household && cur.household.isNotBlank() }
                            ?.let { addOpens("saved", (tr("Mother's MCP card · ", "माँ का MCP कार्ड · ") + it.name) to "card:${it.id}") }
                        if (stockDelta.isNotEmpty()) addOpens("saved", tr("Drug-kit stock register", "दवा किट स्टॉक रजिस्टर") to "stock")
                        if (vals.isEmpty()) updates = updates + tr("⚠ No values were filled — go back to the voice screen and check the note.", "⚠ कोई मान नहीं भरा — वॉइस स्क्रीन पर लौटकर नोट जाँचें।")
                        Log.i("ArogyaDemo", "AROGYA_DEMO_SAVED wf=$wf form=${sp.id} col=$column filled=${vals.size} dangers=${dangers.size} stock=${stockDelta.joinToString("; ")}")
                    }
                }
                "refill" -> {
                    // Fixed rule: kit items at or below their minimum, with exact balances, in the ANM template.
                    val reg = store.state.value
                    val low = ArogyaRegistryLogic.lowItems(reg.stock, reg.stockItems, LocalDate.now())
                    val prof = context.getSharedPreferences("arogya_profile", android.content.Context.MODE_PRIVATE)
                    val body = if (low.isEmpty()) tr("No kit item is below its minimum.", "किट की कोई दवा न्यूनतम से कम नहीं है।")
                        else ArogyaRegistryLogic.refillRequest(reg.stock, reg.stockItems, LocalDate.now(), demoHindi)
                    addOpens("refill", tr("Drug-kit stock register", "दवा किट स्टॉक रजिस्टर") to "stock")
                    refillText = ArogyaAnmTemplate.refillMessage(prof.getString("name", "").orEmpty(), prof.getString("village", "").orEmpty(), "",
                        "", java.time.LocalDateTime.now().withNano(0).toString().replace('T', ' '), body, demoHindi)
                }
                "practice" -> { status = tr("Making 3 questions from the guides…", "गाइड से 3 सवाल बना रहे हैं…")
                    val got = (ai ?: error("AI is not ready")).refresherDetailed(3)
                    quizItems = got.first; quizModel = got.second; quizMs = got.third
                    ticks += ArogyaAiTick("daily practice", "रोज़ अभ्यास", got.second, got.third, !got.second.startsWith("On-phone")) }
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            status = tr("This step could not finish: ", "यह चरण पूरा नहीं हुआ: ") + (e.message ?: "")
            Log.w("ArogyaDemo", "AROGYA_DEMO_STEP_FAILED scene=$scene ${e.message}")
        }
        busy = false; if (!status.startsWith(tr("This step", "यह चरण"))) status = ""
    }

    suspend fun decodePhoto(uri: Uri, maxDim: Int): Bitmap = withContext(Dispatchers.IO) {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1; while (maxOf(opts.outWidth, opts.outHeight) / sample > 2000) sample *= 2
        val raw = (context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: throw IllegalStateException("Could not open the photo")).let { ArogyaPhotoGuard.upright(context, uri, it) }
        val scale = maxDim.toFloat() / maxOf(raw.width, raw.height)
        if (scale < 1f) Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt(), (raw.height * scale).toInt(), true) else raw
    }

    // Manual photo steps (MCP card, medicine strip, SAM shelf): the presenter picks
    // the photo by hand; nothing is generated and nothing is searched for.
    fun handlePhoto(uri: Uri, temp: Boolean) {
        manualJob?.cancel()
        manualJob = scope.launch {
            busy = true; status = ""
            try {
                when (photoFor) {
                    "card" -> {
                        status = tr("AI is reading the card…", "AI कार्ड पढ़ रहा है…")
                        val t = System.currentTimeMillis()
                        val got = (ai ?: error("AI is not ready")).readCardDetailed(uri)
                        card = got.first; cardModel = got.second
                        cardChecked = got.first.fields.indices.toSet(); cardApplied = false
                        val by = got.second
                        ticks += ArogyaAiTick("card text reading + extraction", "कार्ड पढ़ना",
                            "ML Kit text + $by", System.currentTimeMillis() - t, !by.startsWith("On-phone"))
                    }
                    "med" -> {
                        status = tr("Reading the label…", "लेबल पढ़ रहे हैं…")
                        val t = System.currentTimeMillis()
                        val bmp = decodePhoto(uri, 1600)
                        val ocrProc = MLKitProcessor()
                        val ocr = try { withContext(Dispatchers.IO) { ocrProc.recognizeText(bmp) } }
                            finally { runCatching { ocrProc.close() } }
                        if (ocr.isBlank()) error(tr("No readable text on this photo", "फ़ोटो में पढ़ने लायक अक्षर नहीं मिले"))
                        medOcrLen = ocr.length
                        val (res, eng, _) = withContext(Dispatchers.IO) { (ai ?: error("AI is not ready")).scanMedicine(ocr) }
                        medScan = res; medEngine = eng; medMs = System.currentTimeMillis() - t; medSaved = false
                        medMatched = ArogyaMedScan.matchItem(res.name,
                            ArogyaRegistryLogic.catalog(store.state.value.stockItems).map { it.name })
                        val cloud = !eng.contains("offline", ignoreCase = true)
                        ticks += ArogyaAiTick("medicine scan", "दवा स्कैन", "ML Kit text + $eng", medMs, cloud)
                        Log.i("ArogyaDemo", "AROGYA_MEDSCAN name=\"${res.name}\" batch=\"${res.batch}\" expiry=\"${res.expiry}\" qty=${res.qty} engine=$eng ms=$medMs")
                    }
                    "person" -> {
                        status = tr("Reading the photo…", "फ़ोटो पढ़ रहे हैं…")
                        val t = System.currentTimeMillis()
                        val bmp = decodePhoto(uri, 2000)
                        val ocrProc = MLKitProcessor()
                        val (ocr, lines) = try { withContext(Dispatchers.IO) { ocrProc.recognizeText(bmp) to runCatching { ocrProc.recognizeLines(bmp) }.getOrDefault(emptyList()) } }
                            finally { runCatching { ocrProc.close() } }
                        if (ocr.isBlank()) error(tr("No readable text on this photo", "फ़ोटो में पढ़ने लायक अक्षर नहीं मिले"))
                        val rows = ArogyaTableOcr.people(lines)
                        personMsg = if (ArogyaPersonExtract.hasAadhaar(ocr))
                            tr("Aadhaar number found and removed — it is never stored.", "आधार नंबर मिला और हटाया — वो कभी सेव नहीं होता।") else ""
                        val (fields, eng, _) = if (rows.isNotEmpty()) Triple(rows.first().mapValues { ArogyaPersonExtract.maskAadhaar(it.value) }, "On-phone rules (offline): register rows", 0L)
                            else (ai ?: error("AI is not ready")).extractPersonFields(ocr)
                        if (fields.isEmpty()) error(tr("No person details found on this photo", "फ़ोटो में व्यक्ति की जानकारी नहीं मिली"))
                        personPreview = ArogyaPersonCsv.toBeneficiary(fields); personEngine = eng
                        Log.i("ArogyaDemo", "AROGYA_DEMO_PERSON engine=\"$eng\" fields=${fields.keys.joinToString(",")}")
                        personAgreed = false; personSaved = false; personDue = ""
                        ticks += ArogyaAiTick("add person from photo", "फ़ोटो से नया व्यक्ति",
                            "ML Kit text + $eng", System.currentTimeMillis() - t, !eng.contains("offline", ignoreCase = true))
                    }
                    "sam" -> {
                        status = tr("Loading the photo…", "फ़ोटो लोड हो रही है…")
                        val bmp = decodePhoto(uri, 1280)
                        val jpeg = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
                        samBitmap = bmp
                        if (!withContext(Dispatchers.IO) { MetaOnline.ready(context) }) {
                            samMsg = tr("No internet — SAM 3.1 runs only online. Count by hand.",
                                "इंटरनेट नहीं — SAM 3.1 सिर्फ़ ऑनलाइन चलता है। हाथ से गिनें।")
                        } else {
                            status = tr("SAM 3.1 is counting…", "SAM 3.1 गिन रहा है…")
                            val t = System.currentTimeMillis()
                            val phrase = ArogyaKitPhrases.phrase("ORS")
                            val found = MetaOnline.api(context).sam("arogya_demo_kit", jpeg, phrase)
                            val count = ArogyaSamCount.count(found.map { it.confidence })
                            samMsg = "SAM 3.1 " + tr("found $count \"$phrase\" in ${(System.currentTimeMillis() - t) / 1000}s. Sunita confirms the count before it goes in the register.",
                                "\"$phrase\" $count मिला ${(System.currentTimeMillis() - t) / 1000} सेकंड में। रजिस्टर से पहले सुनीता गिनती पक्की करती हैं।")
                            ticks += ArogyaAiTick("SAM 3.1 count", "SAM 3.1 गिनती", "SAM 3.1 (Meta, online)", System.currentTimeMillis() - t, true)
                            Log.i("ArogyaDemo", "AROGYA_SAM_COUNT item=\"ORS\" phrase=\"$phrase\" found=${found.size} counted=$count ms=${System.currentTimeMillis() - t}")
                        }
                    }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                status = tr("This step could not finish: ", "यह चरण पूरा नहीं हुआ: ") + (e.message ?: "")
            } finally {
                busy = false
                if (temp) runCatching { context.contentResolver.delete(uri, null, null) }
            }
        }
    }
    val shoot = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = shotUri; shotUri = null
        if (u != null) { if (ok) handlePhoto(u, true) else runCatching { context.contentResolver.delete(u, null, null) } }
    }
    val camPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { g ->
        if (g) {
            val u = runCatching { context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "demo_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg") }) }.getOrNull()
            if (u != null) { shotUri = u; ArogyaPhotoGuard.hold(context); runCatching { shoot.launch(u) } }
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) handlePhoto(u, false) }
    fun takePhoto(forWhat: String) { photoFor = forWhat; camPerm.launch(android.Manifest.permission.CAMERA) }
    fun choosePhoto(forWhat: String) { photoFor = forWhat; ArogyaPhotoGuard.hold(context); pick.launch(arrayOf("image/*")) }

    Dialog(onDismissRequest = { exitAsk = true }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            if (!started) {
                val (hiVoice, enVoice) = remember(tts) { speech.offlineVoices() }
                val resumeAt = demoPrefs.getInt("last_scene_w$wf", 0)
                Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Spacer(Modifier.height(12.dp))
                    Text("Pramaan", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                    Text(tr("Guided demo · 3 workflows · every step runs live", "गाइडेड डेमो · 3 वर्कफ़्लो · हर चरण लाइव"),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text(tr("Sample data · fictional village", "नमूना डेटा · काल्पनिक गाँव"),
                            Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
                    }
                    HorizontalDivider()
                    Text(tr("Language", "भाषा"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !demoHindi, onClick = { demoHindi = false }, label = { Text("English") })
                        FilterChip(selected = demoHindi, onClick = { demoHindi = true }, label = { Text("हिन्दी") })
                    }
                    Text(tr("AI runs on", "AI कहाँ चले"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !cloudMode, onClick = { ArogyaCloud.setCloudFirst(context, false); cloudMode = false },
                            label = { Text(tr("📱 Phone (offline)", "📱 फ़ोन (ऑफ़लाइन)")) })
                        FilterChip(selected = cloudMode, onClick = { ArogyaCloud.setCloudFirst(context, true); cloudMode = true },
                            label = { Text(tr("☁ Cloud (Meta)", "☁ क्लाउड (Meta)")) })
                    }
                    Text((if (online) tr("Internet: on", "इंटरनेट: चालू") else tr("Internet: off — cloud steps fall back to the phone", "इंटरनेट: बंद — क्लाउड चरण फ़ोन पर चलेंगे")) +
                        "  ·  " + tr("Voices: ", "आवाज़: ") + (if (hiVoice) "हिन्दी ✓" else "हिन्दी ✗") + " " + (if (enVoice) "English ✓" else "English ✗"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!hiVoice || !enVoice) TextButton(onClick = {
                        runCatching { context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }
                    }) { Text(tr("Install offline voice", "ऑफ़लाइन आवाज़ इंस्टॉल करें")) }
                    Text(tr("Choose a workflow", "वर्कफ़्लो चुनें"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    ArogyaDemoScript.workflows.forEach { w ->
                        val sel = w.id == wf
                        Card(Modifier.fillMaxWidth().clickable { wf = w.id },
                            colors = CardDefaults.cardColors(containerColor = if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text((if (sel) "● " else "○ ") + "${w.id}. " + (if (demoHindi) w.hi else w.title), fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall)
                                Text(if (demoHindi) w.lineHi else w.line, style = MaterialTheme.typography.bodySmall)
                                Text(tr("${w.scenes.size} screens", "${w.scenes.size} स्क्रीन"), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { resetRun(); scene = 0; ready = false; started = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text(if (resumeAt in 1 until n) tr("Start over", "शुरू से") else tr("Start the demo", "डेमो शुरू करें"),
                            style = MaterialTheme.typography.titleSmall)
                    }
                    if (resumeAt in 1 until n) OutlinedButton(onClick = { resetRun(); scene = resumeAt; ready = false; started = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(tr("Resume at screen ${resumeAt + 1}", "स्क्रीन ${resumeAt + 1} से आगे"), maxLines = 1)
                    }
                    TextButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(tr("Close", "बंद")) }
                }
                return@Surface
            }
            if (!ready) {
                Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(tr("Getting the demo ready", "डेमो तैयार हो रहा है"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
            if (ArogyaDemoNav.isEnd(scene, n)) {
                val phoneTicks = ticks.filter { !it.cloud }; val cloudTicks = ticks.filter { it.cloud }
                Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Spacer(Modifier.height(12.dp))
                    Text("✓ " + tr("Demo complete", "डेमो पूरा"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                    Text(tr("Thank you", "धन्यवाद"), style = MaterialTheme.typography.titleMedium)
                    Text(tr("${ticks.size} AI steps ran live: ${phoneTicks.size} on this phone, ${cloudTicks.size} on Meta's cloud.",
                        "${ticks.size} AI चरण लाइव चले: ${phoneTicks.size} इसी फ़ोन पर, ${cloudTicks.size} Meta के क्लाउड पर।"))
                    Text(tr("Built with Muse Code.", "Muse Code से बनाया गया।"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { go(n - 1) }) { Text(tr("◀ Back", "◀ पीछे")) }
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = { speech.stop(); started = false; ready = false }) { Text(tr("Workflows", "वर्कफ़्लो")) }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = onClose) { Text(tr("Close", "बंद")) }
                    }
                }
                return@Surface
            }
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
                // Top bar: one compact row (chip · Scene N/M · EN|हिं · 🔊 · ⋮) + progress. Stays ≤ 72 dp.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val sc = scenes[scene]
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.weight(1f, fill = false)) {
                        Text(if (demoHindi) sc.screenHi else sc.screen,
                            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Text("${scene + 1}/$n", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f), maxLines = 1)
                    FilterChip(selected = true, onClick = { speech.stop(); demoHindi = !demoHindi },
                        label = { Text(if (demoHindi) "हिं" else "EN", style = MaterialTheme.typography.labelSmall) })
                    FilterChip(selected = narrate, onClick = { speech.stop(); narrate = !narrate },
                        label = { Text("🔊", style = MaterialTheme.typography.labelSmall) })
                    Box {
                        TextButton(onClick = { menuOpen = true }, contentPadding = PaddingValues(4.dp)) {
                            Text("⋮", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text(tr("Exit demo", "डेमो बंद करें")) },
                                onClick = { menuOpen = false; exitAsk = true })
                        }
                    }
                }
                LinearProgressIndicator(progress = (scene + 1) / n.toFloat(), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                Text(if (demoHindi) scenes[scene].hi else scenes[scene].title, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                if (scenes[scene].id in aiScenes) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tr("AI on:", "AI:"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilterChip(selected = !cloudMode, onClick = {
                        if (cloudMode) { ArogyaCloud.setCloudFirst(context, false); cloudMode = false; replay() }
                    }, label = { Text(tr("📱 Phone", "📱 फ़ोन"), style = MaterialTheme.typography.labelMedium) })
                    FilterChip(selected = cloudMode, onClick = {
                        if (!cloudMode) { ArogyaCloud.setCloudFirst(context, true); cloudMode = true; replay() }
                    }, label = { Text(tr("☁ Cloud", "☁ क्लाउड"), style = MaterialTheme.typography.labelMedium) })
                    if (cloudMode && !online) Text(tr("offline → phone", "ऑफ़लाइन → फ़ोन"), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error)
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                val b = hero; val sp = spec
                if (b == null || sp == null) { Text(tr("Preparing…", "तैयारी…")) }
                else {
                    // Short scenes grow the narration (up to 22 sp) instead of leaving dead space.
                    val narrSize = 16.sp
                    val narrStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = narrSize, lineHeight = narrSize * 1.4)
                    val sceneTicks = ticks.drop(tickMark)
                    val ruleScene = scenes.getOrNull(scene)?.id in setOf("plan", "due", "form", "danger", "refill")
                    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
                    @Composable fun sent(i: Int, s: String) {
                        val on = i == caption && narrate
                        Text(s, style = narrStyle, fontWeight = if (on) FontWeight.Bold else null,
                            color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth().background(
                                if (on) MaterialTheme.colorScheme.primaryContainer
                                else androidx.compose.ui.graphics.Color.Transparent).padding(vertical = 2.dp))
                    }
                    // Aim first (what this screen is for), then "How" (the narration, read aloud).
                    @Composable fun aimCard() {
                        val sc = scenes.getOrNull(scene) ?: return
                        val aim = if (demoHindi) sc.aimHi else sc.aim
                        if (aim.isBlank()) return
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                Text("🎯 " + tr("Aim", "लक्ष्य"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer)
                                Text(aim, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                Text("⚙ " + tr("How it is done:", "कैसे होता है:"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                    // Slim one-line AI chip row, expandable; nothing at all when the scene has no AI step.
                    @Composable fun aiChips() {
                        if (sceneTicks.isEmpty() && !ruleScene) return
                        Row(Modifier.fillMaxWidth().clickable { aiWorkOpen = !aiWorkOpen }
                            .horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (ruleScene) Text("⚙ " + tr("Fixed rule", "तय नियम"), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            sceneTicks.firstOrNull()?.let { first ->
                                Text("✨ " + (if (demoHindi) first.featureHi else first.feature) +
                                    " · ${first.model}" + (if (first.ms > 0) " · ${secs(first.ms)}" else "") +
                                    (if (sceneTicks.size > 1) "  +${sceneTicks.size - 1} ${if (aiWorkOpen) "▲" else "▼"}" else ""),
                                    style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                        if (aiWorkOpen) sceneTicks.drop(1).forEach { t ->
                            Text("• " + (if (demoHindi) t.featureHi else t.feature) + " · ${t.model}" +
                                (if (t.ms > 0) " · ${secs(t.ms)}" else ""), style = MaterialTheme.typography.bodySmall) }
                    }
                    val liveBody: @Composable () -> Unit = {
                        DemoSceneBody(scenes.getOrNull(scene)?.id.orEmpty(), demoHindi, tr = { en, hi -> tr(en, hi) }, data = data, visits = visits, b = b, sp = sp, column = column,
                        filled = filled, formModel = formModel, formMs = formMs, formConfirmed = formConfirmed, onConfirmForm = { formConfirmed = true },
                        transcript = transcript, transcriptEngine = transcriptEngine, transcriptLive = transcriptLive,
                        dangers = dangers, sop = sop, sopFollow = sopFollow, sopOnline = sopOnline, sopOnlineSkip = sopOnlineSkip,
                        sopGold = sopGold, onReadGold = {
                            val txt = sopGold?.text.orEmpty()
                            if (txt.isBlank()) return@DemoSceneBody
                            val (hiVoice, enVoice) = speech.offlineVoices()
                            if ((if (demoHindi) hiVoice else enVoice)) {
                                val sents = txt.split(if (demoHindi) "।" else ".").map { it.trim() }.filter { it.isNotBlank() }
                                speech.speakAll(context, sents.ifEmpty { listOf(txt) }, demoHindi, onSentence = {}, onDone = {})
                            } else status = tr("Offline voice missing — install it from the start screen.", "ऑफ़लाइन आवाज़ नहीं है — शुरू वाली स्क्रीन से इंस्टॉल करें।")
                        },
                        onOpenSource = { openSource = it }, secs = { secs(it) },
                        demoMsg = demoMsg, onDemoMsg = { demoMsg = it }, anm = anm, anmModel = anmModel,
                        guideDocs = guideDocs, onOpenLibrary = { libraryOpen = true },
                        card = card, cardModel = cardModel, cardChecked = cardChecked, onCheckCard = { i, on ->
                            cardChecked = if (on) cardChecked + i else cardChecked - i },
                        cardApplied = cardApplied, onApplyCard = {
                            val cur = store.state.value.beneficiaries.firstOrNull { it.id == b.id } ?: return@DemoSceneBody
                            val fields = (card?.fields.orEmpty()).filterIndexed { i, _ -> i in cardChecked }
                            val after = ArogyaCardApply.apply(cur, fields)
                            vaccinesAdded = (after.mcp.vaccines.keys - cur.mcp.vaccines.keys).map { ArogyaSchedule.VACCINE_NAMES[it] ?: it }
                            weightAdded = fields.firstOrNull { it.key == "weight" }?.value.orEmpty()
                            store.update { reg -> reg.copy(beneficiaries = reg.beneficiaries.map { if (it.id == b.id) after else it }) }
                            cardApplied = true
                            if (vaccinesAdded.isNotEmpty()) updates = updates + tr("✓ MCP card immunization: ", "✓ MCP कार्ड टीकाकरण: ") + vaccinesAdded.joinToString()
                            addOpens("card", tr("MCP card", "MCP कार्ड") to "card:${b.id}", tr("Immunization register", "टीकाकरण रजिस्टर") to "imm:${b.id}")
                        }, vaccinesAdded = vaccinesAdded, weightAdded = weightAdded,
                        onTakePhoto = { takePhoto(it) }, onChoosePhoto = { choosePhoto(it) },
                        medScan = medScan, medEngine = medEngine, medMs = medMs, medOcrLen = medOcrLen, medMatched = medMatched,
                        medSaved = medSaved, onSaveMed = {
                            val s = medScan ?: return@DemoSceneBody
                            val item = medMatched.ifBlank { s.name.ifBlank { return@DemoSceneBody } }
                            val known = medMatched.isNotBlank()
                            store.update { reg ->
                                val items = if (known) reg.stockItems else reg.stockItems + StockItem(item, "units", 0)
                                reg.copy(stockItems = items, stock = reg.stock + StockTxn(id = "demo-scan-${System.currentTimeMillis()}", item = item,
                                    qty = s.qty.coerceAtLeast(0), note = "Demo scan", kind = "receive",
                                    batch = s.batch, expiry = ArogyaMedScan.normExpiry(s.expiry), source = tr("Demo", "डेमो")))
                            }
                            medSaved = true
                            stockDelta = stockDelta + "$item: +${s.qty.coerceAtLeast(0)}"
                            updates = updates + tr("✓ Stock register · $item +${s.qty.coerceAtLeast(0)} (batch ${s.batch.ifBlank { "—" }})", "✓ स्टॉक रजिस्टर · $item +${s.qty.coerceAtLeast(0)} (बैच ${s.batch.ifBlank { "—" }})")
                            addOpens("medscan", tr("Drug-kit stock register", "दवा किट स्टॉक रजिस्टर") to "stock")
                        },
                        samBitmap = samBitmap, samMsg = samMsg,
                        saved = saved, savedReferral = savedReferral, shareMsg = shareMsg, onSharePdf = {
                            val v = savedVisit
                            if (v == null) { shareMsg = tr("Save finished without a visit record.", "सेव में विज़िट नहीं बना।"); return@DemoSceneBody }
                            shareMsg = tr("Making the PDF…", "PDF बना रहे हैं…")
                            manualJob = scope.launch {
                                val ok = ArogyaShare.shareVisitPdf(context, v)
                                shareMsg = if (ok) tr("PDF shared.", "PDF भेजा।") else tr("Could not make the PDF.", "PDF नहीं बना।")
                            }
                        },
                        quizItems = quizItems, quizChoice = quizChoice, onAnswer = { qi, oi -> quizChoice = quizChoice + (qi to oi) },
                        quizModel = quizModel, quizMs = quizMs, ticks = ticks, dangersRan = dangersRan, onDeepLink = onDeepLink,
                        familyText = familyText, familyModel = familyModel, onReadFamily = {
                            val (hiVoice, _) = speech.offlineVoices()
                            if (!hiVoice) { status = tr("Hindi offline voice missing — install it from the start screen.", "हिंदी ऑफ़लाइन आवाज़ नहीं है — शुरू वाली स्क्रीन से इंस्टॉल करें।"); return@DemoSceneBody }
                            val sents = familyText.split("।").map { it.trim() }.filter { it.isNotBlank() }.map { "$it।" }
                            speech.speakAll(context, sents.ifEmpty { listOf(familyText) }, true, onSentence = {}, onDone = {})
                        },
                        personPreview = personPreview, personMsg = personMsg, personAgreed = personAgreed,
                        onAgreePerson = { personAgreed = it }, personSaved = personSaved, personDue = personDue,
                        onSavePerson = {
                            val p = personPreview ?: return@DemoSceneBody
                            val np0 = p.copy(id = "demo-person-${System.currentTimeMillis()}", demo = true)
                            // Workflow 1: the slip is a pregnancy registration; the ANC page follows from her LMP.
                            val np = if (wf == 1 && np0.category != BenCategory.PREGNANT) np0.copy(category = BenCategory.PREGNANT) else np0
                            store.update { reg -> reg.copy(beneficiaries = reg.beneficiaries + np) }
                            if (wf == 1) { hero = np; spec = ArogyaOfficialForms.MCP_ANC; column = ArogyaOfficialForms.columnFor(np, "mcp_anc", store.state.value.forms)
                                updates = updates + tr("✓ Village + pregnancy register: ${np.name} added from the slip photo", "✓ गाँव + गर्भवती रजिस्टर: ${np.name} पर्ची की फ़ोटो से जुड़ीं") }
                            addOpens("addperson", tr("Village register", "गाँव रजिस्टर") to "village:${np.id}",
                                tr("Pregnant women (ANC) register", "गर्भवती (ANC) रजिस्टर") to "anc:${np.id}", tr("MCP card · page 3", "MCP कार्ड · पेज 3") to "card:${np.id}")
                            personDue = ArogyaSchedule.forBeneficiary(np, emptyList(), LocalDate.now())
                                .firstOrNull { !it.done }?.let { "${it.label} (${it.due})" }
                                ?: tr("No dues — registered.", "कुछ बाकी नहीं — दर्ज हो गया।")
                            personSaved = true
                        },
                        savedSummary = savedSummary, cardDoc = cardDoc, onViewCard = {
                            val cur = store.state.value.beneficiaries.firstOrNull { it.id == b.id } ?: return@DemoSceneBody
                            val d = store.mcpDoc(cur, store.state.value.beneficiaries, LocalDate.now())
                            val (html, changed) = ArogyaDocRender.demoMark(cardBeforeHtml, d.html, demoHindi)
                            cardDoc = d.copy(html = html); cardChanged = changed
                        }, cardShareBusy = cardShareBusy,
                        onShareCard = {
                            cardShareBusy = true
                            manualJob = scope.launch {
                                try {
                                    val cur = store.state.value.beneficiaries.firstOrNull { it.id == b.id }
                                    if (cur == null || !store.shareMcpPdf(cur, store.state.value.beneficiaries, LocalDate.now()))
                                        status = tr("Could not create the PDF.", "PDF नहीं बना।")
                                } finally { cardShareBusy = false }
                            }
                        },
                        onPrintCard = {
                            val doc = cardDoc ?: return@DemoSceneBody
                            cardShareBusy = true
                            manualJob = scope.launch {
                                try {
                                    val bytes = store.renderDoc(doc)
                                    if (bytes == null || !ArogyaPrint.printPdf(context, "MCP card", bytes))
                                        status = tr("Print failed.", "छपाई नहीं हुई।")
                                } finally { cardShareBusy = false }
                            }
                        },
                        onCloseCard = { cardDoc = null }, cardChanged = cardChanged, remembered = rememberedTimes,
                        updates = updates, stockDelta = stockDelta, refillText = refillText,
                        opens = opensBy[scenes.getOrNull(scene)?.id.orEmpty()].orEmpty(), onOpen = { openDoc(it) }, formNote = formNote, personEngine = personEngine,
                        sopQ = ArogyaDemoScript.question(wf, demoHindi), sopF = ArogyaDemoScript.followUp(wf, demoHindi),
                        onConfirmSave = { formConfirmed = true; replay() })
                    }
                    val listState = rememberLazyListState()
                    LaunchedEffect(caption, scene) { if (caption in lines().indices) listState.animateScrollToItem(caption) }
                    if (!landscape) {
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
                            verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            item { aimCard() }
                            itemsIndexed(lines()) { i, s -> sent(i, s) }
                            if (narrDone) item {
                                Text(tr("▶ Next when ready", "▶ तैयार हों तो Next"),
                                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                            item {
                                Card(Modifier.fillMaxWidth().clickable { liveExpanded = true }) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        aiChips()
                                        liveBody()
                                    }
                                }
                            }
                        }
                    } else {
                        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LazyColumn(Modifier.weight(1f).fillMaxHeight(), state = listState,
                                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                item { aimCard() }
                                itemsIndexed(lines()) { i, s -> sent(i, s) }
                                if (narrDone) item {
                                    Text(tr("▶ Next when ready", "▶ तैयार हों तो Next"),
                                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                                Card(Modifier.fillMaxWidth().clickable { liveExpanded = true }) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        aiChips()
                                        liveBody()
                                    }
                                }
                            }
                        }
                    }
                    if (liveExpanded) Dialog(onDismissRequest = { liveExpanded = false },
                        properties = DialogProperties(usePlatformDefaultWidth = false)) {
                        Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.fillMaxSize().padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(tr("Tap ✕ when done", "हो जाए तो ✕ दबाएँ"),
                                        style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { liveExpanded = false }) { Text("✕") }
                                }
                                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)) { liveBody() }
                            }
                        }
                    }
                }
                // Bottom bar: fixed, Next widest. Exit lives in the top-bar ⋮ menu.
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    val pad = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                    OutlinedButton(onClick = { go(scene - 1) }, enabled = scene > 0, modifier = Modifier.weight(1f), contentPadding = pad) {
                        Text(tr("◀ Back", "◀ पीछे"), maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge) }
                    OutlinedButton(onClick = { replay() }, modifier = Modifier.weight(1f), contentPadding = pad) {
                        Text(tr("⟳ Replay", "⟳ दोबारा"), maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge) }
                    Button(onClick = { go(scene + 1) }, modifier = Modifier.weight(1.4f), contentPadding = pad) {
                        Text(tr("Next ▶", "आगे ▶"), maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge) }
                }
            }
            if (exitAsk) AlertDialog(onDismissRequest = { exitAsk = false },
                title = { Text(tr("Exit the demo?", "डेमो बंद करें?")) },
                text = { Text(tr("Your place (scene ${scene + 1} of $n) is saved — you can resume later.", "आपकी जगह (सीन ${scene + 1} / $n) सेव है — बाद में आगे बढ़ सकते हैं।")) },
                confirmButton = { Button(onClick = { speech.stop(); manualJob?.cancel(); onClose() }) { Text(tr("Exit", "बंद करें")) } },
                dismissButton = { TextButton(onClick = { exitAsk = false }) { Text(tr("Stay", "रुकें")) } })
            viewDoc?.let { (title, d) -> ArogyaDocPdf.Viewer(d, title, cardShareBusy, demoHindi, onBack = { viewDoc = null },
                onSharePdf = { cardShareBusy = true; manualJob = scope.launch { try { store.renderDoc(d)?.let { bytes -> store.shareFile(ArogyaDocPdf.fileName(title, ".pdf"), "application/pdf", bytes) } } finally { cardShareBusy = false } } },
                onPrint = { cardShareBusy = true; manualJob = scope.launch { try { store.renderDoc(d)?.let { bytes -> ArogyaPrint.printPdf(context, title, bytes) } } finally { cardShareBusy = false } } }) }
            openSource?.let { s -> AlertDialog(onDismissRequest = { openSource = null; sourceErr = "" },
                title = { Text(s.title, style = MaterialTheme.typography.titleMedium) },
                text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.locator.isNotBlank()) Text(s.locator.replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.Bold)
                    Text(tr("Text read from the guide:", "गाइड में लिखा है:"), style = MaterialTheme.typography.labelMedium)
                    if (s.excerpt.isBlank()) Text(tr("The passage text was not recorded for this source.", "इस स्रोत का पाठ उपलब्ध नहीं है।")) else ArogyaPassageText(s)
                    if (s.highlights.isNotEmpty()) Text(tr("Highlighted: the sentences this answer came from.", "हाइलाइट: जिन वाक्यों से उत्तर बना।"), style = MaterialTheme.typography.bodySmall)
                    if (!s.url.startsWith("http")) Text(tr("No online copy is linked for this document.", "इस दस्तावेज़ की ऑनलाइन प्रति उपलब्ध नहीं है।"), style = MaterialTheme.typography.bodySmall)
                    if (sourceErr.isNotBlank()) Text(sourceErr, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                } },
                confirmButton = { if (s.url.startsWith("http")) Button(onClick = {
                    runCatching { uriHandler.openUri(s.url) }.onFailure { sourceErr = tr("No browser or PDF viewer is available.", "ब्राउज़र या PDF नहीं खुला।") }
                }) { Text(tr("Open guideline", "गाइडलाइन खोलें")) } },
                dismissButton = { TextButton(onClick = { openSource = null; sourceErr = "" }) { Text(tr("Close", "बंद करें")) } }) }
            if (libraryOpen) ArogyaGuideLibrary(demoHindi, ai, store,
                onAskGuide = { _, _ -> libraryOpen = false; scenes.indexOfFirst { it.id == "sops" }.takeIf { it >= 0 }?.let { go(it) } }, onClose = { libraryOpen = false })
        }
    }
}

@Composable
private fun DemoSceneBody(sceneId: String, hindi: Boolean, tr: (String, String) -> String,
    data: RegistryData, visits: List<ArogyaVisit>, b: Beneficiary, sp: FormSpec, column: Int,
    filled: Map<String, FilledValue>, formModel: String, formMs: Long, formConfirmed: Boolean, onConfirmForm: () -> Unit,
    transcript: String, transcriptEngine: String, transcriptLive: Boolean,
    dangers: List<String>, sop: ArogyaAiResult?, sopFollow: ArogyaAiResult?, sopOnline: ArogyaAiResult?, sopOnlineSkip: Boolean,
    sopGold: ArogyaAiResult?, onReadGold: () -> Unit,
    onOpenSource: (ArogyaSource) -> Unit, secs: (Long) -> String,
    demoMsg: String, onDemoMsg: (String) -> Unit, anm: String, anmModel: String,
    guideDocs: List<GuideDoc>?, onOpenLibrary: () -> Unit,
    card: ArogyaCardReading?, cardModel: String, cardChecked: Set<Int>, onCheckCard: (Int, Boolean) -> Unit,
    cardApplied: Boolean, onApplyCard: () -> Unit, vaccinesAdded: List<String>, weightAdded: String,
    onTakePhoto: (String) -> Unit, onChoosePhoto: (String) -> Unit,
    medScan: MedScan?, medEngine: String, medMs: Long, medOcrLen: Int, medMatched: String, medSaved: Boolean, onSaveMed: () -> Unit,
    samBitmap: Bitmap?, samMsg: String,
    saved: Boolean, savedReferral: String, shareMsg: String, onSharePdf: () -> Unit,
    quizItems: List<ArogyaQuizItem>, quizChoice: Map<Int, Int>, onAnswer: (Int, Int) -> Unit,
    quizModel: String, quizMs: Long, ticks: List<ArogyaAiTick>, dangersRan: Boolean,
    onDeepLink: ((String) -> Unit)?,
    familyText: String, familyModel: String, onReadFamily: () -> Unit,
    personPreview: Beneficiary?, personMsg: String, personAgreed: Boolean, onAgreePerson: (Boolean) -> Unit,
    personSaved: Boolean, personDue: String, onSavePerson: () -> Unit,
    savedSummary: String, cardDoc: ArogyaDocRender.FilledDoc?, onViewCard: () -> Unit, cardShareBusy: Boolean,
    onShareCard: () -> Unit, onPrintCard: () -> Unit, onCloseCard: () -> Unit,
    cardChanged: Int = -1, remembered: Map<String, Pair<Long, Long>> = emptyMap(),
    updates: List<String> = emptyList(), stockDelta: List<String> = emptyList(), refillText: String = "",
    opens: List<Pair<String, String>> = emptyList(), onOpen: (String) -> Unit = {}, formNote: String = "", personEngine: String = "",
    sopQ: String = "", sopF: String = "", onConfirmSave: () -> Unit = {}) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // "Updated": real changes this save made, shown on the save screen.
        if (sceneId == "saved" && !saved) DemoCard(tr("Confirm before saving", "सेव से पहले पक्का करें")) {
            Text(tr("${filled.size} values are ready from the voice note. Nothing is saved until Sunita confirms them.",
                "वॉइस नोट से ${filled.size} मान तैयार हैं। सुनीता के पक्का करने तक कुछ सेव नहीं होता।"), style = MaterialTheme.typography.bodySmall)
            Button(onClick = onConfirmSave, enabled = filled.isNotEmpty()) { Text(tr("✓ Sunita confirms — save", "✓ सुनीता पक्का करती हैं — सेव करें"), maxLines = 1) }
        }
        if (sceneId == "saved" && saved && updates.isNotEmpty()) DemoCard(tr("Updated records", "अपडेट हुए रिकॉर्ड")) {
            updates.forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium) }
        }
        if (opens.isNotEmpty()) DemoCard(tr("Open to check", "खोलकर जाँचें")) {
            opens.forEach { (label, key) -> OutlinedButton(onClick = { onOpen(key) }, modifier = Modifier.fillMaxWidth()) { Text("📄 $label", maxLines = 1) } }
        }
        val parts = when (sceneId) { "plan" -> listOf("meet", "due"); "summary" -> listOf("aitable", "beforeafter"); else -> listOf(sceneId) }
        for (sid in parts) when (sid) {
            "refill" -> {
                if (refillText.isBlank()) Text(tr("Checking the kit…", "किट जाँच रहे हैं…"))
                else DemoCard(tr("Refill request to the ANM (fixed template)", "ANM को रिफिल अनुरोध (तय प्रारूप)")) {
                    Text(refillText, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { onDemoMsg(tr("Not sent in demo — real use opens WhatsApp to the ANM.", "डेमो में नहीं भेजा — असल में ANM को WhatsApp खुलेगा।")) }) {
                        Text(tr("💬 Send to ANM", "💬 ANM को भेजें"), maxLines = 1) }
                    if (demoMsg.isNotBlank()) Text(demoMsg, style = MaterialTheme.typography.bodySmall)
                }
            }
            "records" -> {
                DemoCard(tr("Everything this visit updated", "इस विज़िट से जो अपडेट हुआ")) {
                    if (updates.isEmpty()) Text(tr("Save the visit first (the save screen) — then every update shows here.", "पहले विज़िट सेव करें — फिर हर अपडेट यहाँ दिखेगा।"))
                    updates.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                if (stockDelta.isNotEmpty()) DemoCard(tr("Drug-kit stock: before → after", "दवा किट स्टॉक: पहले → बाद")) {
                    stockDelta.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) }
                }
                if (onDeepLink != null) OutlinedButton(onClick = { onDeepLink("registers") }) {
                    Text(tr("Open the registers in the app ↗", "ऐप में रजिस्टर खोलें ↗"), maxLines = 1) }
            }
            "meet" -> DemoCard(tr("The problem", "समस्या")) {
                Text("• " + tr("Visits to plan, paper forms, registers and MCP cards by hand", "विज़िट तय करना, कागज़ी फ़ॉर्म, रजिस्टर और MCP कार्ड हाथ से"))
                Text("• " + run {
                    val docs = guideDocs
                    if (docs.isNullOrEmpty()) tr("Official guides — hard to know which applies", "आधिकारिक गाइड — कौन सी लागू है, जानना मुश्किल")
                    else { val (nd, pages, _) = ArogyaGuideLibrary.totals(docs)
                        tr("$nd official guides (~$pages pages) — hard to know which applies", "$nd आधिकारिक गाइड (~$pages पेज) — कौन सी लागू है, जानना मुश्किल") }
                })
                Text("• " + tr("Patchy network in the village", "गाँव में कमज़ोर नेटवर्क"))
                Text(tr("Rampur · ${data.beneficiaries.size} people in the register", "रामपुर · रजिस्टर में ${data.beneficiaries.size} लोग"),
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
            "due" -> DemoCard(tr("Due this week (official schedules — rules, not AI)", "इस हफ़्ते बाकी (सरकारी शेड्यूल — नियम, AI नहीं)")) {
                val due = remember(data, visits) { ArogyaSchedule.dueList(data.beneficiaries, visits, LocalDate.now(), 7).filter { !it.done } }
                Text(tr("${due.size} visits due", "${due.size} विज़िट बाकी"), fontWeight = FontWeight.Bold)
                if (due.none { it.name == b.name }) Text("• ${b.name} — " + sp.columns.getOrElse(column) { "" } + tr(" (today)", " (आज)"), fontWeight = FontWeight.Bold)
                due.sortedBy { if (it.name == b.name) 0 else 1 }.take(5).forEach { Text("• ${it.name} — ${it.label} (${it.due})") }
            }
            "addperson" -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onTakePhoto("person") }) { Text(tr("📷 Take photo", "📷 फ़ोटो लें")) }
                    OutlinedButton(onClick = { onChoosePhoto("person") }) { Text(tr("Choose photo", "फ़ोटो चुनें")) }
                }
                if (personMsg.isNotBlank()) Text(personMsg, style = MaterialTheme.typography.bodySmall)
                personPreview?.let { p -> DemoCard(tr("✨ Review — from photo", "✨ जाँचें — फ़ोटो से")) {
                    ConfirmChip(hindi)
                    Text(p.name.ifBlank { "—" } + (if (p.relativeName.isNotBlank()) " · ${p.relativeName}" else "") +
                        (if (p.village.isNotBlank()) " · ${p.village}" else ""), fontWeight = FontWeight.Bold)
                    if (personEngine.isNotBlank()) Text(tr("Read by: ", "पढ़ा: ") + personEngine, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val edd = ArogyaRegistryLogic.edd(p)
                    listOf(tr("Age", "उम्र") to p.age, tr("Mobile", "मोबाइल") to p.phone, tr("House", "मकान") to p.household, tr("Village", "गाँव") to p.village,
                        tr("Category", "श्रेणी") to (if (hindi) p.category.titleHi else p.category.title), "LMP" to p.lmp,
                        tr("EDD (rule: LMP + 280 days)", "EDD (नियम: LMP + 280 दिन)") to (edd?.toString() ?: ""), "RCH ID" to p.rchId)
                        .forEach { (k, v) -> Text("$k: " + v.ifBlank { tr("— not on the slip", "— पर्ची पर नहीं") }, style = MaterialTheme.typography.bodySmall) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(personAgreed, onAgreePerson)
                        Text(tr("Family agreed to be registered", "परिवार रजिस्टर होने को तैयार"), style = MaterialTheme.typography.bodySmall)
                    }
                    if (!personSaved) Button(onClick = onSavePerson, enabled = personAgreed && p.name.isNotBlank()) {
                        Text(tr("Save person", "व्यक्ति सेव करें")) }
                    else Text("✓ " + tr("Saved — in the due list: ", "सेव हो गया — बाकी सूची में: ") + personDue,
                        fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                } }
            }
            "form" -> {
                if (sp.id == "annex9") Text("Annexure 9 · ${sp.columns[column]}",
                    fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                DemoCard("${sp.title.substringBefore(" (")} · ${sp.columns[column]}") {
                    Text(sp.source, style = MaterialTheme.typography.labelSmall)
                    ArogyaOfficialForms.fieldsFor(sp, column).take(12).forEach { Text("☐ " + it.label + if (it.hint.isNotBlank()) "  — ${it.hint}" else "", style = MaterialTheme.typography.bodySmall) }
                    Text(tr("…and ${ArogyaOfficialForms.fieldsFor(sp, column).size - 12} more rows", "…और ${ArogyaOfficialForms.fieldsFor(sp, column).size - 12} पंक्तियाँ"), style = MaterialTheme.typography.labelSmall)
                }
                if (onDeepLink != null) OutlinedButton(onClick = { onDeepLink("guided:${b.id}") }) { Text(tr("Open this visit ↗", "यह विज़िट खोलें ↗")) }
            }
            "voice" -> {
                DemoCard((if (transcriptLive) "🎤 " else "⌨ ") + tr("Voice note", "वॉइस नोट") + " · $transcriptEngine") {
                    Text("“${transcript.ifBlank { tr("Listening…", "सुन रहे हैं…") }}”", style = MaterialTheme.typography.bodySmall) }
                if (formNote.isNotBlank()) Text(formNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (transcript.isNotBlank() && formModel.isNotBlank() && filled.isEmpty()) DemoCard(tr("⚠ Nothing could be read from this note", "⚠ इस नोट से कुछ नहीं पढ़ा जा सका"), danger = true) {
                    Text(tr("Replay this screen, or fill the form by hand in the visit.", "यह स्क्रीन दोबारा चलाएँ, या विज़िट में फ़ॉर्म हाथ से भरें।"), style = MaterialTheme.typography.bodySmall) }
                if (filled.isNotEmpty()) DemoCard(tr("${filled.size} rows filled (✨ ${filled.count { it.value.source == "ai" }} by AI · ⚙ ${filled.count { it.value.source != "ai" }} by rules) · $formModel · ${secs(formMs)}",
                    "${filled.size} पंक्तियाँ भरीं (✨ AI ${filled.count { it.value.source == "ai" }} · ⚙ नियम ${filled.count { it.value.source != "ai" }}) · $formModel · ${secs(formMs)}")) {
                    ConfirmChip(hindi)
                    filled.forEach { (id, v) -> val f = sp.fields.firstOrNull { it.id == id } ?: return@forEach
                        Text((if (v.source == "ai") "✨ " else "⚙ ") + "${if (hindi) f.hi else f.label}: ${v.value}${if (f.type == QType.NUMBER && f.unit.isNotBlank()) " " + f.unit else ""}", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
                        Text("   ← “${v.evidence}”", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                }
                if (filled.isNotEmpty() && !formConfirmed) Button(onClick = onConfirmForm) { Text(tr("✓ Sunita confirms these values", "✓ सुनीता इन मानों को पक्का करती हैं")) }
                if (formConfirmed) Text("✓ " + tr("Confirmed by Sunita — nothing saved before this.", "सुनीता ने पक्का किया — इससे पहले कुछ सेव नहीं।"),
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            "danger" -> {
                DemoCard(if (dangers.isEmpty()) tr("✓ No danger signs", "✓ कोई खतरा नहीं") else tr("⚠ REFER NOW", "⚠ तुरंत रेफ़र करें"), danger = dangers.isNotEmpty()) {
                    dangers.forEach { Text(it) }
                    if (dangers.isEmpty()) {
                        Text(tr("Checked by rules:", "नियमों से जाँचा:"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        ArogyaDemoScript.dangerChecked(sp, hindi).forEach { Text("✓ $it", style = MaterialTheme.typography.bodySmall) }
                    }
                    Text(tr("⚙ Fixed danger rules from the government guides — not AI.", "⚙ सरकारी गाइड के तय नियम — AI नहीं।"), style = MaterialTheme.typography.labelSmall)
                }
                if (dangers.isNotEmpty()) DemoCard(tr("Tell ANM + PHC", "ANM-PHC सूचना")) {
                    val bp = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { onDemoMsg(tr("Not sent in demo — real use calls 108.", "डेमो में नहीं भेजा — असल में 108 पर कॉल।")) },
                            modifier = Modifier.weight(1f), contentPadding = bp) { Text("📞 108", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge) }
                        OutlinedButton(onClick = { onDemoMsg(tr("Not sent in demo — real use calls the ANM.", "डेमो में नहीं भेजा — असल में ANM को कॉल।")) },
                            modifier = Modifier.weight(1f), contentPadding = bp) { Text("📞 ANM", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge) }
                        OutlinedButton(onClick = { onDemoMsg(tr("Not sent in demo — real use opens WhatsApp.", "डेमो में नहीं भेजा — असल में WhatsApp खुलेगा।")) },
                            modifier = Modifier.weight(1.2f), contentPadding = bp) { Text("💬 WhatsApp", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge) }
                    }
                    if (demoMsg.isNotBlank()) Text(demoMsg, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    Text(tr("The PHC referral is recorded when the visit is saved.", "PHC रेफ़रल विज़िट सेव होने पर दर्ज होता है।"), style = MaterialTheme.typography.labelSmall)
                }
            }
            "anm" -> {
                if (anm.isBlank()) Text(tr("Drafting the message…", "संदेश लिख रहे हैं…"))
                else DemoCard(tr("✨ WhatsApp to the ANM (AI draft, she edits before sending)", "✨ ANM को WhatsApp (AI ड्राफ़्ट, भेजने से पहले बदल सकती हैं)") + if (anmModel.isNotBlank()) " · $anmModel" else "") {
                    Text(anm, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { onDemoMsg(tr("Not sent in demo — real use opens WhatsApp to the ANM with this text.", "डेमो में नहीं भेजा — असल में यह संदेश ANM को WhatsApp पर खुलेगा।")) }) {
                        Text(tr("💬 Send on WhatsApp", "💬 WhatsApp पर भेजें"), maxLines = 1) }
                    if (demoMsg.isNotBlank()) Text(demoMsg, style = MaterialTheme.typography.bodySmall)
                }
            }
            "explain" -> {
                if (familyText.isBlank()) Text(tr("Explaining…", "समझा रहे हैं…"))
                else DemoCard(tr("✨ For the family (Hindi, from the guide)", "✨ परिवार के लिए (हिंदी, गाइड से)") +
                    if (familyModel.isNotBlank()) " · $familyModel" else "") {
                    Text(familyText, style = MaterialTheme.typography.bodyMedium)
                }
                if (familyText.isNotBlank()) Button(onClick = onReadFamily) {
                    Text(tr("🔊 Read aloud (offline Hindi)", "🔊 पढ़कर सुनाएँ (ऑफ़लाइन हिंदी)")) }
            }
            "guides" -> {
                val docs = guideDocs
                if (docs == null) Text(tr("Reading…", "पढ़ रहे हैं…"))
                else if (docs.isEmpty()) Text(tr("Guide database not installed — install it from Home first.", "गाइड डेटाबेस नहीं है — होम से इंस्टॉल करें।"))
                else {
                    val (nd, pages, passages) = ArogyaGuideLibrary.totals(docs)
                    DemoCard(tr("The pipeline", "पाइपलाइन")) {
                        Text(tr("Government portals → $nd documents → $passages passages → stored on this phone",
                            "सरकारी पोर्टल → $nd दस्तावेज़ → $passages हिस्से → इसी फ़ोन में"), fontWeight = FontWeight.Bold)
                        Text(tr("$pages pages · works offline", "$pages पेज · ऑफ़लाइन चलता है"), style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = onOpenLibrary, modifier = Modifier.fillMaxWidth()) { Text(tr("📚 Guides used ($nd)", "📚 इस्तेमाल गाइड ($nd)")) }
                    DemoCard(tr("On this phone", "इस फ़ोन पर")) {
                        ArogyaGuideLibrary.grouped(docs).forEach { (g, ds) ->
                            Text("$g (${ds.size})", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                            ds.forEach { Text("• ${it.title.ifBlank { it.sourceName }}", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
            "sops" -> {
                // Demo UI pass: the same chat layout as Ask the ASHA guide.
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SopUserBubble(sopQ.ifBlank { if (hindi) ArogyaDemoScript.QUESTION_HI else ArogyaDemoScript.QUESTION_EN })
                    if (sop == null) Text(tr("Searching the guides…", "गाइड में खोज…"), style = MaterialTheme.typography.bodySmall)
                    sop?.let { DemoSopAnswer(it, hindi, tr, onOpenSource, secs) }
                    if (sop != null) SopUserBubble(sopF.ifBlank { if (hindi) ArogyaDemoScript.FOLLOWUP_HI else ArogyaDemoScript.FOLLOWUP_EN })
                    sopFollow?.let { DemoSopAnswer(it, hindi, tr, onOpenSource, secs) }
                    sopOnline?.let { o ->
                        val base = sop?.text.orEmpty()
                        val added = if (base.isNotBlank()) ArogyaOfficialAnswer.newSentences(base, o.text).size else 0
                        Text("☁ " + tr("Checked online — $added sentence(s) added to the phone answer", "ऑनलाइन जाँचा — फ़ोन के उत्तर से $added वाक्य ज़्यादा"),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        DemoSopAnswer(o, hindi, tr, onOpenSource, secs)
                    }
                    if (sopOnlineSkip) Text("☁ " + tr("Online check needs internet — skipped.", "ऑनलाइन जाँच को इंटरनेट चाहिए — छोड़ा।"),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            "card" -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onTakePhoto("card") }) { Text(tr("📷 Take photo", "📷 फ़ोटो लें")) }
                    OutlinedButton(onClick = { onChoosePhoto("card") }) { Text(tr("Choose MCP card photo", "MCP कार्ड फ़ोटो चुनें")) }
                }
                OutlinedButton(onClick = onViewCard) { Text(tr("View official card (with growth chart)", "आधिकारिक कार्ड देखें (विकास चार्ट सहित)")) }
                cardDoc?.let { d -> ArogyaDocPdf.Viewer(d, tr("MCP card — ", "MCP कार्ड — ") + b.name,
                    cardShareBusy, hindi, onCloseCard, onShareCard, onPrintCard) }
                card?.let { r -> DemoCard(tr("✨ Read from the card (${r.fields.size} values, each found in the card's text)", "✨ कार्ड से पढ़ा (${r.fields.size})") + if (cardModel.isNotBlank()) " · $cardModel" else "") {
                    ConfirmChip(hindi)
                    r.fields.forEachIndexed { i, f -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(cardChecked.contains(i), { onCheckCard(i, it) })
                        Text("${f.label}: ${f.value}", style = MaterialTheme.typography.bodySmall) } }
                    if (!cardApplied) Button(onClick = onApplyCard, enabled = cardChecked.isNotEmpty()) {
                        Text(tr("Apply ${cardChecked.size} values to the card", "${cardChecked.size} मान कार्ड में लगाएँ")) }
                    else {
                        Text("✓ " + tr("Applied to the MCP card", "MCP कार्ड में लगाया"), fontWeight = FontWeight.Bold)
                        Text(if (vaccinesAdded.isEmpty()) tr("Vaccines already on the card — due list unchanged", "टीके कार्ड पर पहले से — बाकी सूची वैसी ही")
                            else tr("Added to MCP card (they leave the due list): ", "MCP कार्ड में जोड़े (बाकी सूची से हटे): ") + vaccinesAdded.joinToString())
                        if (weightAdded.isNotBlank()) Text(tr("Weight added: ", "वज़न जोड़ा: ") + weightAdded)
                    }
                } }
            }
            "mcpcard" -> {
                if (cardChanged >= 0) Text(
                    if (cardChanged == 0) tr("No field changed yet — run the voice note and save scenes first.", "अभी कोई खाना नहीं बदला — पहले वॉइस नोट और सेव वाले सीन चलाएँ।")
                    else tr("$cardChanged fields changed today — highlighted in yellow.", "आज $cardChanged खाने बदले — पीले रंग में।"),
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                Text(tr("NA = not applicable at this stage · Not recorded = still to be filled", "लागू नहीं = इस चरण पर नहीं · दर्ज नहीं = अभी भरना बाकी"),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onViewCard) { Text(tr("Open ${b.name}'s MCP card", "${b.name} का MCP कार्ड खोलें"), maxLines = 1) }
                cardDoc?.let { d -> ArogyaDocPdf.Viewer(d, tr("MCP card — ", "MCP कार्ड — ") + b.name,
                    cardShareBusy, hindi, onCloseCard, onShareCard, onPrintCard) }
            }
            "medscan" -> {
                DemoCard("🔍 " + tr("Medicine scan · works offline", "दवा स्कैन · ऑफ़लाइन चलता है")) {
                    Text(tr("On-phone text reading + rules; the AI model only fills what the rules miss.",
                        "फ़ोन पर अक्षर पढ़ना + नियम; जो नियम छोड़ें वही AI मॉडल भरता है।"),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onTakePhoto("med") }) { Text(tr("📷 Take photo", "📷 फ़ोटो लें")) }
                        OutlinedButton(onClick = { onChoosePhoto("med") }) { Text(tr("Choose photo", "फ़ोटो चुनें")) }
                    }
                    medScan?.let { s ->
                        AiResultChip("ML Kit text + $medEngine", !medEngine.contains("offline", ignoreCase = true), medMs, hindi)
                        ConfirmChip(hindi)
                        Text(tr("Name: ", "नाम: ") + s.name.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall)
                        Text(tr("Batch: ", "बैच: ") + s.batch.ifBlank { "—" } + " · " + tr("Expiry: ", "एक्सपायरी: ") + s.expiry.ifBlank { "—" } +
                            " · " + tr("Qty: ", "मात्रा: ") + (if (s.qty > 0) s.qty.toString() else "—"), style = MaterialTheme.typography.bodySmall)
                        Text(if (medMatched.isNotBlank()) tr("Matches kit item: $medMatched", "किट की दवा मिली: $medMatched")
                            else tr("Not in the kit list — will be added as a new item.", "किट सूची में नहीं — नई दवा जुड़ेगी।"),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        if (!medSaved) Button(onClick = onSaveMed) {
                            Text(if (medMatched.isNotBlank()) tr("Save as Stock received", "स्टॉक मिला — सहेजें") else tr("Add as new item", "नई दवा जोड़ें")) }
                        else Text("✓ " + tr("In the stock register", "स्टॉक रजिस्टर में"), fontWeight = FontWeight.Bold)
                    }
                }
                DemoCard("☁ " + tr("Drug-kit count · Meta SAM 3.1 (online only)", "दवा किट गिनती · Meta SAM 3.1 (सिर्फ़ ऑनलाइन)")) {
                    Text(tr("Offline, the kit is counted by hand — the app never guesses a count.",
                        "ऑफ़लाइन किट हाथ से गिनी जाती है — ऐप गिनती का अंदाज़ा नहीं लगाता।"),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onTakePhoto("sam") }) { Text(tr("📷 Take photo", "📷 फ़ोटो लें")) }
                        OutlinedButton(onClick = { onChoosePhoto("sam") }) { Text(tr("Choose photo", "फ़ोटो चुनें")) }
                    }
                    samBitmap?.let { Image(it.asImageBitmap(), contentDescription = tr("Drug-kit photo", "दवा किट फ़ोटो"), modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)) }
                    if (samMsg.isNotBlank()) Text(samMsg, style = MaterialTheme.typography.bodySmall)
                }
            }
            "saved" -> {
                if (savedSummary.isNotBlank()) DemoCard(tr("What changed", "क्या बदला")) {
                    Text(savedSummary, style = MaterialTheme.typography.bodySmall) }
                val nextDue = remember(saved) { ArogyaOfficialForms.nextDue(sp, b, column, LocalDate.now()) }
                OfficialFormGrid(ArogyaOfficialForms.table(sp, b, data.forms), column, hindi = hindi)
                DemoCard(tr("Saved", "सहेजा")) {
                    Text("📄 ${sp.title.substringBefore(" (")} — ${sp.columns[column]}")
                    Text("🗂 " + tr("MCP card: weight + vaccines", "MCP कार्ड: वज़न + टीके"))
                    Text("📒 " + tr("Activity diary + visit list", "डायरी + विज़िट सूची"))
                    Text("📅 " + tr("Next visit: ", "अगली विज़िट: ") + (nextDue?.toString() ?: "—"), fontWeight = FontWeight.Bold)
                    if (savedReferral.isNotBlank()) Text("🏥 $savedReferral", fontWeight = FontWeight.Bold)
                }
                Button(onClick = onSharePdf, enabled = saved) { Text(tr("Share visit PDF", "विज़िट PDF भेजें")) }
                if (shareMsg.isNotBlank()) Text(shareMsg, style = MaterialTheme.typography.bodySmall)
                if (onDeepLink != null) OutlinedButton(onClick = { onDeepLink("registers") }) { Text(tr("All registers in the app ↗", "ऐप के सभी रजिस्टर ↗"), maxLines = 1) }
            }
            "practice" -> {
                if (quizModel.isNotBlank()) AiResultChip(quizModel, !quizModel.startsWith("On-phone"), quizMs, hindi)
                if (quizItems.isEmpty()) Text(tr("Making questions…", "सवाल बन रहे हैं…"))
                quizItems.forEachIndexed { qi, item ->
                    DemoCard("${qi + 1}. ${item.question}") {
                        val chosen = quizChoice[qi]
                        item.options.forEachIndexed { oi, opt ->
                            val mark = when { chosen == null -> ""; oi == item.correct -> "✓ "; oi == chosen -> "✗ "; else -> "" }
                            OutlinedButton(onClick = { if (chosen == null) onAnswer(qi, oi) }, modifier = Modifier.fillMaxWidth(),
                                colors = if (chosen != null && oi == item.correct) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else ButtonDefaults.outlinedButtonColors()) {
                                Text(mark + "ABC"[oi] + ") " + opt, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        TextButton(onClick = { onOpenSource(item.source) }) {
                            Text(tr("From: ", "स्रोत: ") + item.source.title.take(50) + " · " + item.source.locator, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            "aitable" -> {
                // Item 11: every AI feature with its offline (phone) and online (cloud) tool; Meta tools marked;
                // ✓ = it ran in this demo (with where and how long). Tools are named as wired in code.
                val rows = remember(ticks.size, dangersRan) { ArogyaDemoTicks.toolRows(ticks, dangersRan) }
                val ranCount = rows.count { it.ran && !it.tool.rule }
                val (mOff, mOn) = remember { ArogyaDemoTicks.metaCounts() }
                Text(tr("Meta tools power $mOff features on the phone and $mOn in the cloud · $ranCount ran in this demo",
                    "Meta के टूल फ़ोन पर $mOff और क्लाउड में $mOn सुविधाएँ चलाते हैं · इस डेमो में $ranCount चलीं"),
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                val head = MaterialTheme.typography.labelSmall
                val cellStyle = MaterialTheme.typography.labelSmall
                val meta = MaterialTheme.colorScheme.primary
                @Composable fun toolText(text: String, isMeta: Boolean, m: Modifier) =
                    Text(text, style = cellStyle, modifier = m, color = if (isMeta) meta else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (isMeta) FontWeight.SemiBold else null)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(tr("Feature", "सुविधा"), style = head, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.1f))
                            Text(tr("📱 Offline tool", "📱 ऑफ़लाइन टूल"), style = head, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.2f))
                            Text(tr("☁ Online tool", "☁ ऑनलाइन टूल"), style = head, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.1f))
                            Text(tr("Today", "आज"), style = head, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.6f))
                        }
                        HorizontalDivider()
                        rows.forEach { r ->
                            val t = r.tool
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                                Text(if (hindi) t.featureHi else t.feature, style = cellStyle, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1.1f))
                                toolText(if (hindi) t.offlineHi else t.offline, t.metaOffline, Modifier.weight(1.2f))
                                toolText(if (hindi) t.onlineHi else t.online, t.metaOnline, Modifier.weight(1.1f))
                                Text(when {
                                    !r.ran -> "—"
                                    t.rule -> "✓ ⚙"
                                    else -> "✓ " + (if (r.ranCloud == true) "☁" else "📱") + (if (r.ranMs > 0) " " + secs(r.ranMs) else "")
                                }, style = cellStyle, modifier = Modifier.weight(0.6f),
                                    color = if (r.ran) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Text(tr("Blue = Meta tool · ⚙ = fixed government rule, not AI · names never leave the phone for card and register reading.",
                    "नीला = Meta टूल · ⚙ = तय सरकारी नियम, AI नहीं · कार्ड और रजिस्टर पढ़ते समय नाम फ़ोन से बाहर नहीं जाते।"),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(tr("App built and optimised with Muse Code.", "ऐप Muse Code से बनाया और बेहतर किया गया।"),
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
            else -> {
                // Item 11, last slide: what AI did on this visit, the by-hand estimate, today's measured
                // time and the usual online / offline time (last measured on this phone, either mode).
                val rows = remember(ticks.size, remembered) { ArogyaDemoTicks.impact(ticks, remembered) }
                if (rows.isEmpty()) Text(tr("Run the earlier screens first — only measured steps appear here.",
                    "पहले पिछली स्क्रीन चलाएँ — यहाँ सिर्फ़ नापे गए चरण आते हैं।"))
                else {
                    val hand = ArogyaDemoTicks.handTotal(rows)
                    val aiToday = rows.sumOf { it.todayMs }
                    Text(tr("On every visit AI saved Sunita about $hand minutes of paperwork.", "हर विज़िट में AI ने सुनीता का लगभग $hand मिनट का काग़ज़ी काम बचाया।"),
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    val st = MaterialTheme.typography.labelSmall
                    fun t(ms: Long) = if (ms > 0) secs(ms) else "—"
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(tr("AI did", "AI ने किया"), style = st, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.5f))
                                Text(tr("By hand*", "हाथ से*"), style = st, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.7f))
                                Text(tr("Today", "आज"), style = st, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.7f))
                                Text("☁ " + tr("Online", "ऑनलाइन"), style = st, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.7f))
                                Text("📱 " + tr("Offline", "ऑफ़लाइन"), style = st, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.7f))
                            }
                            HorizontalDivider()
                            rows.forEach { r ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(if (hindi) r.task.taskHi else r.task.task, style = st, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1.5f))
                                    Text("~${r.task.handMin} " + tr("min", "मिनट"), style = st, modifier = Modifier.weight(0.7f))
                                    Text(t(r.todayMs), style = st, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.7f))
                                    Text(t(r.onlineMs), style = st, modifier = Modifier.weight(0.7f))
                                    Text(t(r.offlineMs), style = st, modifier = Modifier.weight(0.7f))
                                }
                            }
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(tr("Total", "कुल"), style = st, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.5f))
                                Text("~$hand " + tr("min", "मिनट"), style = st, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.7f))
                                Text(t(aiToday), style = st, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(0.7f))
                                Spacer(Modifier.weight(1.4f))
                            }
                        }
                    }
                    Text(tr("* By-hand times are rough estimates, not measured. AI times are measured on this phone; online/offline show the last run in each mode (flip Phone | Cloud on a screen to fill both).",
                        "* हाथ वाले समय मोटे अनुमान हैं, नापे नहीं गए। AI के समय इसी फ़ोन पर नापे गए; ऑनलाइन/ऑफ़लाइन हर मोड का पिछला रन दिखाते हैं (दोनों भरने के लिए स्क्रीन पर फ़ोन | क्लाउड बदलें)।"),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun DemoSopAnswer(r: ArogyaAiResult, hindi: Boolean, tr: (String, String) -> String, onOpenSource: (ArogyaSource) -> Unit, secs: (Long) -> String) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    var passagesOpen by remember(r) { mutableStateOf(false) }
    // Demo UI pass: chat answer layout (guide avatar, answer with tappable citations, one-tap PDF).
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SopAssistantHeader(hindi)
        Text((if (r.online) "☁ " else "📱 ") + r.provider + " · " + secs(r.elapsedMs),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SopAnswerText(r.text, r.segments, emptySet()) { id -> r.sources.firstOrNull { it.id == id }?.let(onOpenSource) }
        r.sources.firstOrNull()?.let { s0 ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { passagesOpen = !passagesOpen }, modifier = Modifier.weight(1f)) {
                    Text(tr("📚 From the guide: ", "📚 गाइड से: ") + "${s0.title}, ${s0.locator}" +
                        (if (r.sources.size > 1) tr(" +${r.sources.size - 1} more", " +${r.sources.size - 1} और") else "") +
                        (if (passagesOpen) " ▴" else " ▾"), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
                val link = r.sources.firstOrNull { it.url.startsWith("http") }
                if (link != null) FilledTonalButton(onClick = {
                    runCatching { uriHandler.openUri(ArogyaGuideLibrary.pageUrl(link.url, link.locator)) }
                        .onFailure { Toast.makeText(context, tr("No browser available.", "ब्राउज़र नहीं है।"), Toast.LENGTH_LONG).show() }
                }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)) {
                    Text(tr("📄 Open PDF", "📄 PDF खोलें"), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
        }
        if (passagesOpen) r.sources.take(3).forEach { s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("📄 ${s.title} · ${s.locator}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onOpenSource(s) }) { Text(tr("Read", "पढ़ें")) }
                    }
                    if (s.excerpt.isNotBlank()) ArogyaPassageText(s.copy(excerpt = s.excerpt.take(600) + if (s.excerpt.length > 600) "…" else ""))
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
