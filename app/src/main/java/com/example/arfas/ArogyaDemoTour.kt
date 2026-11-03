package com.example.arfas

import android.content.ContentValues
import android.content.Context
import android.content.Intent
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
    val scenes = ArogyaDemoScript.scenes
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
    var photoFor by remember { mutableStateOf("") }
    var shotUri by remember { mutableStateOf<Uri?>(null) }
    val ticks = remember { mutableStateListOf<ArogyaAiTick>() }
    fun secs(ms: Long) = if (ms < 1000) tr("<1 s", "<1 से.") else tr("${ms / 1000} s", "${ms / 1000} से.")
    val online = MetaOnline.hasInternet(context)
    fun lines(): List<String> = if (scene >= n) listOf(tr("The demo is complete. Thank you.", "डेमो पूरा हुआ। धन्यवाद।"))
        else if (demoHindi) scenes[scene].narrationHi else scenes[scene].narration

    // Navigation: stop narration and any running work cleanly, then move.
    fun go(i: Int) { speech.stop(); manualJob?.cancel(); status = ""; scene = i.coerceIn(0, n) }
    fun replay() { speech.stop(); manualJob?.cancel(); ran = ran - scene; status = ""; replayTick++ }

    // Resume: the last scene is saved; the start screen offers it back.
    val demoPrefs = remember { context.getSharedPreferences("arogya_demo", 0) }
    LaunchedEffect(scene, started) { if (started) demoPrefs.edit().putInt("last_scene", scene).apply() }

    // Prepare after Start: fresh sample village, the hero baby, the guide counts, a warm model.
    LaunchedEffect(started) {
        if (!started || ready) return@LaunchedEffect
        busy = true; status = tr("Loading the sample village (fictional people)…", "नमूना गाँव लोड हो रहा है…")
        onLoadSample()
        // Suspend until the store holds the hero (demo sample, today's dates) — no fixed sleep.
        var b: Beneficiary? = null
        for (i in 0 until 150) {
            b = store.state.value.beneficiaries.firstOrNull { it.name.startsWith(ArogyaDemoScript.HERO_PREFIX) }
            if (b != null) break
            delay(100)
        }
        if (b == null) { busy = false; status = tr("Sample village not found — load it from Home first.", "नमूना गाँव नहीं मिला — होम से लोड करें।"); return@LaunchedEffect }
        val (sp, col) = ArogyaOfficialForms.formFor(b, store.state.value.forms); spec = sp; column = col; hero = b
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
            when (scene) {
                4 -> {
                    val voiceAsset = if (demoHindi) ArogyaDemoScript.VOICE_HI else ArogyaDemoScript.VOICE_EN
                    val choice = if (demoHindi) ArogyaLang.VOICE_HINDI else ArogyaLang.VOICE_ENGLISH
                    val pcm = withContext(Dispatchers.IO) {
                        runCatching { context.assets.open(voiceAsset).use { ArogyaWav.decode(it.readBytes()) } }.getOrNull()
                            ?.takeIf { it.size > ArogyaWav.TARGET_RATE }
                    }
                    if (pcm == null) {
                        transcript = if (demoHindi) ArogyaDemoScript.NOTE_HI else ArogyaDemoScript.NOTE_EN
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
                5 -> {
                    dangers = ArogyaOfficialForms.dangers(sp, filled.mapValues { it.value.value }); dangersRan = true
                    status = tr("AI is drafting the ANM message…", "AI ANM संदेश लिख रहा है…")
                    val facts = "Baby of Neha, day 7, Rampur. " + dangers.joinToString("; ") + ". Referred to PHC Rampur today."
                    val tanm = System.currentTimeMillis()
                    val got = runCatching { ai?.anmMessageDetailed(facts, ArogyaVisit(name = "Baby of Neha", village = "Rampur")) }.getOrNull()
                    anm = got?.first.orEmpty().ifBlank { "URGENT: $facts Please advise. — ASHA Sunita (ArogyaDoot)" }
                    anmModel = got?.second.orEmpty()
                    if (got != null && got.first.isNotBlank()) {
                        val by = got.second
                        ticks += ArogyaAiTick("ANM message", "ANM संदेश", by, System.currentTimeMillis() - tanm, !by.startsWith("On-phone"))
                    }
                }
                6 -> {
                    status = tr("Explaining to the family in Hindi…", "परिवार को हिंदी में समझा रहे हैं…")
                    val engine = ai ?: error("AI is not ready")
                    val r = engine.run(ArogyaAiRequest(ArogyaAiTask.EXPLAIN,
                        ArogyaVisit(id = SOP_VISIT_ID, personId = SOP_VISIT_ID, name = "", kind = ArogyaKind.FOLLOW_UP),
                        question = if (demoHindi) ArogyaDemoScript.QUESTION_HI else ArogyaDemoScript.QUESTION_EN,
                        hindi = demoHindi))
                    val (txt, model, ms) = engine.familyExplainDetailed(r.text, r.sources.firstOrNull()?.excerpt.orEmpty(), true)
                    familyText = txt; familyModel = model
                    ticks += ArogyaAiTick("family explanation", "परिवार को समझाना", model, ms, !model.startsWith("On-phone"))
                }
                7 -> { status = tr("Reading the guide library on this phone…", "फ़ोन की गाइड लाइब्रेरी पढ़ रहे हैं…")
                    if (guideDocs == null) guideDocs = withContext(Dispatchers.IO) { ai?.guideLibrary() } }
                8 -> { status = tr("Searching the official guides…", "आधिकारिक गाइड में खोज…")
                    val engine = ai ?: error("AI is not ready")
                    suspend fun ask(q: String) = engine.run(ArogyaAiRequest(ArogyaAiTask.EXPLAIN,
                        ArogyaVisit(id = SOP_VISIT_ID, personId = SOP_VISIT_ID, name = "", kind = ArogyaKind.FOLLOW_UP),
                        question = q, hindi = demoHindi))
                    val r = ask(if (demoHindi) ArogyaDemoScript.QUESTION_HI else ArogyaDemoScript.QUESTION_EN)
                    sop = r
                    ticks += ArogyaAiTick("guide search + grounded answer", "गाइड खोज + उत्तर", r.provider, r.elapsedMs, r.online)
                    status = tr("Asking the follow-up…", "दोबारा पूछ रहे हैं…")
                    val f = ask(if (demoHindi) ArogyaDemoScript.FOLLOWUP_HI else ArogyaDemoScript.FOLLOWUP_EN)
                    sopFollow = f
                    ticks += ArogyaAiTick("guide follow-up", "दोबारा सवाल", f.provider, f.elapsedMs, f.online)
                    // Item 8: one Check online moment (skipped honestly when offline).
                    status = tr("Checking the answer online…", "उत्तर ऑनलाइन जाँच रहे हैं…")
                    if (MetaOnline.ready(context)) {
                        val o = engine.run(ArogyaAiRequest(ArogyaAiTask.EXPLAIN,
                            ArogyaVisit(id = SOP_VISIT_ID, personId = SOP_VISIT_ID, name = "", kind = ArogyaKind.FOLLOW_UP),
                            question = if (demoHindi) ArogyaDemoScript.QUESTION_HI else ArogyaDemoScript.QUESTION_EN,
                            hindi = demoHindi, onlineCheck = true))
                        sopOnline = o
                        ticks += ArogyaAiTick("online check", "ऑनलाइन जाँच", o.provider, o.elapsedMs, true)
                    } else sopOnlineSkip = true }
                11 -> {
                    if (!saved) {
                        val vals = filled.mapValues { it.value.value }
                        val cur = store.state.value.beneficiaries.first { it.id == b.id }
                        savedSummary = ArogyaVisitSave.save(store, queue, cur, sp, column, vals, filled.mapValues { it.value.source }, emptySet())
                        saved = true
                        val rec = queue.state.value.visits.firstOrNull { it.id == "official:${b.id}:${sp.id}:$column" }
                        savedVisit = rec
                        savedReferral = if (rec == null) tr("Visit record not found after save.", "सेव के बाद विज़िट नहीं मिला।")
                            else if (rec.referral == ArogyaReferral.NEEDED) tr("Referral recorded: NEEDED — ", "रेफ़रल दर्ज: ज़रूरी — ") + rec.referralNote
                            else tr("Referral recorded: none needed.", "रेफ़रल दर्ज: ज़रूरत नहीं।")
                        Log.i("ArogyaDemo", "AROGYA_DEMO_SAVED form=${sp.id} col=$column filled=${vals.size} dangers=${dangers.size}")
                    }
                }
                12 -> { status = tr("Making 3 questions from the guides…", "गाइड से 3 सवाल बना रहे हैं…")
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
        val raw = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: throw IllegalStateException("Could not open the photo")
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
                        val ocr = try { withContext(Dispatchers.IO) { ocrProc.recognizeText(bmp) } }
                            finally { runCatching { ocrProc.close() } }
                        if (ocr.isBlank()) error(tr("No readable text on this photo", "फ़ोटो में पढ़ने लायक अक्षर नहीं मिले"))
                        personMsg = if (ArogyaPersonExtract.hasAadhaar(ocr))
                            tr("Aadhaar number found and removed — it is never stored.", "आधार नंबर मिला और हटाया — वो कभी सेव नहीं होता।") else ""
                        val (fields, eng, _) = (ai ?: error("AI is not ready")).extractPersonFields(ocr)
                        if (fields.isEmpty()) error(tr("No person details found on this photo", "फ़ोटो में व्यक्ति की जानकारी नहीं मिली"))
                        personPreview = ArogyaPersonCsv.toBeneficiary(fields)
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
                            samMsg = tr("Online only — skipped offline (SAM 3.1 needs internet and the Meta key). Nothing is guessed.",
                                "सिर्फ़ ऑनलाइन — ऑफ़लाइन छोड़ा (SAM 3.1 को इंटरनेट और Meta key चाहिए)। अंदाज़ा नहीं लगाया।")
                        } else {
                            status = tr("SAM 3.1 is counting…", "SAM 3.1 गिन रहा है…")
                            val t = System.currentTimeMillis()
                            val phrase = ArogyaKitPhrases.phrase("ORS")
                            val found = MetaOnline.api(context).sam("arogya_demo_kit", jpeg, phrase)
                            val count = ArogyaSamCount.count(found.map { it.confidence })
                            samMsg = "SAM 3.1 " + tr("found $count \"$phrase\" in ${(System.currentTimeMillis() - t) / 1000}s. Sunita confirms before it reaches the register (demo: not written).",
                                "\"$phrase\" $count मिला ${(System.currentTimeMillis() - t) / 1000} सेकंड में। रजिस्टर से पहले सुनीता पक्की करती हैं (डेमो: लिखा नहीं)।")
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
            if (u != null) { shotUri = u; runCatching { shoot.launch(u) } }
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) handlePhoto(u, false) }
    fun takePhoto(forWhat: String) { photoFor = forWhat; camPerm.launch(android.Manifest.permission.CAMERA) }
    fun choosePhoto(forWhat: String) { photoFor = forWhat; pick.launch(arrayOf("image/*")) }

    Dialog(onDismissRequest = { exitAsk = true }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            if (!started) {
                val cloudFirst = ArogyaCloud.cloudFirst(context)
                val (hiVoice, enVoice) = remember(tts) { speech.offlineVoices() }
                val resumeAt = demoPrefs.getInt("last_scene", 0)
                Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("▶ " + tr("ArogyaDoot · guided demo", "ArogyaDoot · गाइडेड डेमो"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
                    if (!online) Text(tr("Offline now: the full demo runs on the phone; answers take longer. Cloud-only steps show as skipped.",
                        "अभी ऑफ़लाइन: पूरा डेमो फ़ोन पर चलेगा; उत्तरों में ज़्यादा समय लगेगा। क्लाउड वाले चरण छूटे दिखेंगे।"), style = MaterialTheme.typography.bodySmall)
                    Text((if (hiVoice && enVoice) "✓ " else "⚠ ") + tr("Offline voices: Hindi ", "ऑफ़लाइन आवाज़: हिन्दी ") +
                        (if (hiVoice) tr("ready", "तैयार") else tr("missing", "नहीं है")) + " · " + tr("English ", "अंग्रेज़ी ") +
                        (if (enVoice) tr("ready", "तैयार") else tr("missing", "नहीं है")),
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    if (!hiVoice || !enVoice) OutlinedButton(onClick = {
                        runCatching { context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }
                    }) { Text(tr("Install offline voice", "ऑफ़लाइन आवाज़ इंस्टॉल करें")) }
                    Text(tr("Voice note: the owner's recording when bundled, else the typed note (labelled, speech-to-text skipped). Photos are picked by hand.",
                        "वॉइस नोट: मालिक की रिकॉर्डिंग हो तो वही, नहीं तो लिखा नोट (लिखा होगा, बोल→लिख छूटेगा)। फ़ोटो हाथ से चुनी जाती हैं।"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(tr("You move with Back / Replay / Next — no timer.", "आप Back / Replay / Next से चलेंगे — कोई टाइमर नहीं।"),
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    if (resumeAt in 1 until n) Button(onClick = { scene = resumeAt; started = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(tr("Resume from scene ${resumeAt + 1} of $n", "सीन ${resumeAt + 1} / $n से आगे बढ़ें"))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = onClose) { Text(tr("Close", "बंद")) }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = { scene = 0; started = true }) { Text(if (resumeAt in 1 until n) tr("Start over", "शुरू से") else tr("Start", "शुरू करें")) }
                    }
                }
                return@Surface
            }
            if (!ready) {
                Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("▶ " + tr("ArogyaDoot · guided demo", "ArogyaDoot · गाइडेड डेमो"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                        Text(tr("DEMO · sample village (fictional people)", "डेमो · नमूना गाँव (काल्पनिक लोग)"), Modifier.padding(12.dp), fontWeight = FontWeight.Bold)
                    }
                    Text("✓ " + tr("Demo complete — thank you", "डेमो पूरा — धन्यवाद"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(tr("${ticks.size} AI steps ran live: ${phoneTicks.size} on this phone, ${cloudTicks.size} on Meta's cloud.",
                        "${ticks.size} AI चरण लाइव चले: ${phoneTicks.size} इसी फ़ोन पर, ${cloudTicks.size} Meta के क्लाउड पर।"))
                    Text(tr("Built with Muse Code.", "Muse Code से बनाया गया।"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { go(n - 1) }) { Text(tr("◀ Back", "◀ पीछे")) }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = onClose) { Text(tr("Close", "बंद")) }
                    }
                }
                return@Surface
            }
            Column(Modifier.fillMaxSize().padding(14.dp)) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Text(tr("DEMO · sample village (fictional people)", "डेमो · नमूना गाँव (काल्पनिक लोग)"),
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(ArogyaDemoNav.label(scene, n, demoHindi), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    FilterChip(selected = !demoHindi, onClick = { speech.stop(); demoHindi = false }, label = { Text("EN") })
                    FilterChip(selected = demoHindi, onClick = { speech.stop(); demoHindi = true }, label = { Text("हिं") })
                    FilterChip(selected = narrate, onClick = { speech.stop(); narrate = !narrate }, label = { Text("🔊") })
                }
                LinearProgressIndicator(progress = (scene + 1) / n.toFloat(), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                Text(if (demoHindi) scenes[scene].hi else scenes[scene].title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                // Captions: large, current sentence highlighted; "Next when ready" after the last.
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).weight(0.32f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    lines().forEachIndexed { i, s ->
                        Text(s, style = MaterialTheme.typography.bodyLarge,
                            color = if (i == caption && narrate) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().background(
                                if (i == caption && narrate) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface))
                    }
                    if (narrDone) Text(tr("▶ Next when ready", "▶ तैयार हों तो Next"), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val b = hero; val sp = spec
                    if (b == null || sp == null) { Text(tr("Preparing…", "तैयारी…")); return@Column }
                    if (ticks.isNotEmpty()) Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth().clickable { aiWorkOpen = !aiWorkOpen }, verticalAlignment = Alignment.CenterVertically) {
                                Text("✨ " + tr("AI at work", "AI काम पर") + " (${ticks.size})", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Text(if (aiWorkOpen) "▲" else "▼", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                            if (aiWorkOpen) ticks.forEach { t -> Text("• " + (if (demoHindi) t.featureHi else t.feature) + " · ${t.model}" + (if (t.ms > 0) " · ${secs(t.ms)}" else ""),
                                style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    DemoSceneBody(scene, demoHindi, tr = { en, hi -> tr(en, hi) }, data = data, visits = visits, b = b, sp = sp, column = column,
                        filled = filled, formModel = formModel, formMs = formMs, formConfirmed = formConfirmed, onConfirmForm = { formConfirmed = true },
                        transcript = transcript, transcriptEngine = transcriptEngine, transcriptLive = transcriptLive,
                        dangers = dangers, sop = sop, sopFollow = sopFollow, sopOnline = sopOnline, sopOnlineSkip = sopOnlineSkip,
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
                        }, vaccinesAdded = vaccinesAdded, weightAdded = weightAdded,
                        onTakePhoto = { takePhoto(it) }, onChoosePhoto = { choosePhoto(it) },
                        medScan = medScan, medEngine = medEngine, medMs = medMs, medOcrLen = medOcrLen, medMatched = medMatched,
                        medSaved = medSaved, onSaveMed = {
                            val s = medScan ?: return@DemoSceneBody
                            val item = medMatched.ifBlank { s.name.ifBlank { return@DemoSceneBody } }
                            val known = medMatched.isNotBlank()
                            store.update { reg ->
                                val items = if (known) reg.stockItems else reg.stockItems + StockItem(item, "units", 0)
                                reg.copy(stockItems = items, stock = reg.stock + StockTxn(item = item,
                                    qty = s.qty.coerceAtLeast(0), note = "Demo scan", kind = "receive",
                                    batch = s.batch, expiry = ArogyaMedScan.normExpiry(s.expiry), source = tr("Demo", "डेमो")))
                            }
                            medSaved = true
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
                            val np = p.copy(id = "demo-person-${System.currentTimeMillis()}", demo = true)
                            store.update { reg -> reg.copy(beneficiaries = reg.beneficiaries + np) }
                            personDue = ArogyaSchedule.forBeneficiary(np, emptyList(), LocalDate.now())
                                .firstOrNull { !it.done }?.let { "${it.label} (${it.due})" }
                                ?: tr("No dues — registered.", "कुछ बाकी नहीं — दर्ज हो गया।")
                            personSaved = true
                        },
                        savedSummary = savedSummary, cardDoc = cardDoc, onViewCard = {
                            val cur = store.state.value.beneficiaries.firstOrNull { it.id == b.id } ?: return@DemoSceneBody
                            cardDoc = store.mcpDoc(cur, store.state.value.beneficiaries, LocalDate.now())
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
                        onCloseCard = { cardDoc = null })
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { go(scene - 1) }, enabled = scene > 0, modifier = Modifier.weight(1f)) { Text(tr("◀ Back", "◀ पीछे")) }
                    OutlinedButton(onClick = { replay() }, modifier = Modifier.weight(1f)) { Text(tr("⟳ Replay", "⟳ दोबारा")) }
                    Button(onClick = { go(scene + 1) }, modifier = Modifier.weight(1f)) { Text(tr("Next ▶", "आगे ▶")) }
                }
                OutlinedButton(onClick = { exitAsk = true }, modifier = Modifier.fillMaxWidth()) { Text(tr("Exit demo", "डेमो बंद करें")) }
            }
            if (exitAsk) AlertDialog(onDismissRequest = { exitAsk = false },
                title = { Text(tr("Exit the demo?", "डेमो बंद करें?")) },
                text = { Text(tr("Your place (scene ${scene + 1} of $n) is saved — you can resume later.", "आपकी जगह (सीन ${scene + 1} / $n) सेव है — बाद में आगे बढ़ सकते हैं।")) },
                confirmButton = { Button(onClick = { speech.stop(); manualJob?.cancel(); onClose() }) { Text(tr("Exit", "बंद करें")) } },
                dismissButton = { TextButton(onClick = { exitAsk = false }) { Text(tr("Stay", "रुकें")) } })
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
                onAskGuide = { _, _ -> libraryOpen = false; go(8) }, onClose = { libraryOpen = false })
        }
    }
}

@Composable
private fun DemoSceneBody(scene: Int, hindi: Boolean, tr: (String, String) -> String,
    data: RegistryData, visits: List<ArogyaVisit>, b: Beneficiary, sp: FormSpec, column: Int,
    filled: Map<String, FilledValue>, formModel: String, formMs: Long, formConfirmed: Boolean, onConfirmForm: () -> Unit,
    transcript: String, transcriptEngine: String, transcriptLive: Boolean,
    dangers: List<String>, sop: ArogyaAiResult?, sopFollow: ArogyaAiResult?, sopOnline: ArogyaAiResult?, sopOnlineSkip: Boolean,
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
    onShareCard: () -> Unit, onPrintCard: () -> Unit, onCloseCard: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (scene) {
            0 -> DemoCard(tr("The problem", "समस्या")) {
                Text("• " + tr("Visits to plan, paper forms, registers and MCP cards by hand", "विज़िट तय करना, कागज़ी फ़ॉर्म, रजिस्टर और MCP कार्ड हाथ से"))
                Text("• " + run {
                    val docs = guideDocs
                    if (docs.isNullOrEmpty()) tr("Official guides — hard to know which applies", "आधिकारिक गाइड — कौन सी लागू है, जानना मुश्किल")
                    else { val (nd, pages, _) = ArogyaGuideLibrary.totals(docs)
                        tr("$nd official guides (~$pages pages) — hard to know which applies", "$nd आधिकारिक गाइड (~$pages पेज) — कौन सी लागू है, जानना मुश्किल") }
                })
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onTakePhoto("person") }) { Text(tr("📷 Take photo", "📷 फ़ोटो लें")) }
                    OutlinedButton(onClick = { onChoosePhoto("person") }) { Text(tr("Choose photo", "फ़ोटो चुनें")) }
                }
                if (personMsg.isNotBlank()) Text(personMsg, style = MaterialTheme.typography.bodySmall)
                personPreview?.let { p -> DemoCard(tr("✨ Review — from photo", "✨ जाँचें — फ़ोटो से")) {
                    ConfirmChip(hindi)
                    Text(p.name.ifBlank { "—" } + (if (p.relativeName.isNotBlank()) " · ${p.relativeName}" else "") +
                        (if (p.village.isNotBlank()) " · ${p.village}" else ""), fontWeight = FontWeight.Bold)
                    if (p.phone.isNotBlank()) Text(tr("Phone: ", "फ़ोन: ") + p.phone, style = MaterialTheme.typography.bodySmall)
                    Text(if (hindi) p.category.titleHi else p.category.title, style = MaterialTheme.typography.bodySmall)
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
            3 -> {
                if (sp.id == "annex9") Text("Annexure 9 · ${sp.columns[column]}",
                    fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                DemoCard("${sp.title.substringBefore(" (")} · ${sp.columns[column]}") {
                    Text(sp.source, style = MaterialTheme.typography.labelSmall)
                    ArogyaOfficialForms.fieldsFor(sp, column).take(12).forEach { Text("☐ " + it.label + if (it.hint.isNotBlank()) "  — ${it.hint}" else "", style = MaterialTheme.typography.bodySmall) }
                    Text(tr("…and ${ArogyaOfficialForms.fieldsFor(sp, column).size - 12} more rows", "…और ${ArogyaOfficialForms.fieldsFor(sp, column).size - 12} पंक्तियाँ"), style = MaterialTheme.typography.labelSmall)
                }
                if (onDeepLink != null) OutlinedButton(onClick = { onDeepLink("guided:${b.id}") }) { Text(tr("Open this visit ↗", "यह विज़िट खोलें ↗")) }
            }
            4 -> {
                DemoCard((if (transcriptLive) "🎤 " else "⌨ ") + tr("Voice note", "वॉइस नोट") + " · $transcriptEngine") {
                    Text("“${transcript.ifBlank { tr("Listening…", "सुन रहे हैं…") }}”", style = MaterialTheme.typography.bodySmall) }
                if (filled.isNotEmpty()) DemoCard(tr("✨ AI filled ${filled.size} rows · $formModel · ${secs(formMs)}", "✨ AI ने ${filled.size} पंक्तियाँ भरीं · $formModel · ${secs(formMs)}")) {
                    ConfirmChip(hindi)
                    filled.forEach { (id, v) -> val f = sp.fields.firstOrNull { it.id == id } ?: return@forEach
                        Text("${f.label}: ${v.value}${if (f.type == QType.NUMBER && f.unit.isNotBlank()) " " + f.unit else ""}", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
                        Text("   ← “${v.evidence}”", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                }
                if (filled.isNotEmpty() && !formConfirmed) Button(onClick = onConfirmForm) { Text(tr("✓ Sunita confirms these values", "✓ सुनीता इन मानों को पक्का करती हैं")) }
                if (formConfirmed) Text("✓ " + tr("Confirmed by Sunita — nothing saved before this.", "सुनीता ने पक्का किया — इससे पहले कुछ सेव नहीं।"),
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            5 -> {
                DemoCard(if (dangers.isEmpty()) tr("✓ No danger signs", "✓ कोई खतरा नहीं") else tr("⚠ REFER NOW", "⚠ तुरंत रेफ़र करें"), danger = dangers.isNotEmpty()) {
                    dangers.forEach { Text("• $it") }
                    Text(tr("Rules printed on the form — not AI.", "फ़ॉर्म पर छपे नियम — AI नहीं।"), style = MaterialTheme.typography.labelSmall)
                }
                DemoCard(tr("Tell ANM + PHC", "ANM-PHC सूचना")) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onDemoMsg(tr("Demo: not sent — in real use this calls 108.", "डेमो: भेजा नहीं — असल में 108 पर कॉल होगा।")) }) { Text("📞 108") }
                        OutlinedButton(onClick = { onDemoMsg(tr("Demo: not sent — in real use this calls the ANM.", "डेमो: भेजा नहीं — असल में ANM को कॉल होगा।")) }) { Text("📞 ANM") }
                        OutlinedButton(onClick = { onDemoMsg(tr("Demo: not sent — in real use this opens WhatsApp.", "डेमो: भेजा नहीं — असल में WhatsApp खुलेगा।")) }) { Text("💬 WhatsApp") }
                    }
                    if (demoMsg.isNotBlank()) Text(demoMsg, fontWeight = FontWeight.Bold)
                    Text(tr("Referral to PHC will be recorded when the visit is saved. (Demo: nothing is sent.)", "PHC रेफ़रल विज़िट सेव होने पर दर्ज होगा। (डेमो: कुछ भेजा नहीं जाता)"), style = MaterialTheme.typography.labelSmall)
                }
                if (anm.isNotBlank()) DemoCard(tr("✨ Message to ANM (AI draft, she edits before sending)", "✨ ANM को संदेश (AI ड्राफ़्ट)") + if (anmModel.isNotBlank()) " · $anmModel" else "") { Text(anm, style = MaterialTheme.typography.bodySmall) }
            }
            6 -> {
                if (familyText.isBlank()) Text(tr("Explaining…", "समझा रहे हैं…"))
                else DemoCard(tr("✨ For the family (Hindi, from the guide)", "✨ परिवार के लिए (हिंदी, गाइड से)") +
                    if (familyModel.isNotBlank()) " · $familyModel" else "") {
                    Text(familyText, style = MaterialTheme.typography.bodyMedium)
                }
                if (familyText.isNotBlank()) Button(onClick = onReadFamily) {
                    Text(tr("🔊 Read aloud (offline Hindi)", "🔊 पढ़कर सुनाएँ (ऑफ़लाइन हिंदी)")) }
            }
            7 -> {
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
            8 -> {
                DemoCard("❓ " + (if (hindi) ArogyaDemoScript.QUESTION_HI else ArogyaDemoScript.QUESTION_EN)) {}
                sop?.let { DemoSopAnswer(it, hindi, tr, onOpenSource, secs) }
                DemoCard("❓ " + tr("Follow-up (the app remembers the first question)", "दोबारा सवाल (ऐप पहला सवाल याद रखता है)") + ": " +
                    (if (hindi) ArogyaDemoScript.FOLLOWUP_HI else ArogyaDemoScript.FOLLOWUP_EN)) {}
                sopFollow?.let { DemoSopAnswer(it, hindi, tr, onOpenSource, secs) }
                sopOnline?.let { o ->
                    DemoCard("☁ " + tr("Checked online — both answers kept", "ऑनलाइन जाँचा — दोनों उत्तर रखे")) {
                        val base = sop?.text.orEmpty()
                        val added = if (base.isNotBlank()) ArogyaOfficialAnswer.newSentences(base, o.text).size else 0
                        Text(tr("$added sentence(s) add to the offline answer (highlighted in the app's Online tab).",
                            "$added वाक्य ऑफ़लाइन उत्तर से ज़्यादा (ऐप के Online टैब में रँगे)।"),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    DemoSopAnswer(o, hindi, tr, onOpenSource, secs)
                }
                if (sopOnlineSkip) DemoCard("☁ " + tr("Online check", "ऑनलाइन जाँच")) {
                    Text(tr("Online only — skipped offline.", "सिर्फ़ ऑनलाइन — ऑफ़लाइन छोड़ा।"), style = MaterialTheme.typography.bodySmall)
                }
            }
            9 -> {
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
            10 -> {
                DemoCard("🔍 " + tr("Medicine scan (offline)", "दवा स्कैन (ऑफ़लाइन)")) {
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
                DemoCard("🔢 " + tr("Drug-kit count (SAM 3.1, online)", "दवा किट गिनती (SAM 3.1, ऑनलाइन)")) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onTakePhoto("sam") }) { Text(tr("📷 Take photo", "📷 फ़ोटो लें")) }
                        OutlinedButton(onClick = { onChoosePhoto("sam") }) { Text(tr("Choose photo", "फ़ोटो चुनें")) }
                    }
                    samBitmap?.let { Image(it.asImageBitmap(), contentDescription = tr("Drug-kit photo", "दवा किट फ़ोटो"), modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)) }
                    if (samMsg.isNotBlank()) Text(samMsg, style = MaterialTheme.typography.bodySmall)
                }
            }
            11 -> {
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
                if (onDeepLink != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onDeepLink("registers") }) { Text(tr("Open registers ↗", "रजिस्टर खोलें ↗")) }
                    OutlinedButton(onClick = { onDeepLink("diary") }) { Text(tr("Open diary ↗", "डायरी खोलें ↗")) }
                }
            }
            12 -> {
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
            13 -> {
                DemoCard(tr("One setting — the same switch as Home", "एक सेटिंग — होम वाला ही बटन")) {
                    // The Home Offline | Online control itself (flipping here flips Home too).
                    ArogyaModeSwitch(hindi, LocalContext.current) { }
                    Text(tr("Everything works offline either way — the phone is the fallback.", "दोनों में सब ऑफ़लाइन चलता है — फ़ोन बैकअप है।"), style = MaterialTheme.typography.bodySmall)
                }
            }
            14 -> {
                DemoCard(tr("What AI did in this visit", "इस विज़िट में AI ने क्या किया")) {
                    val rows = ArogyaDemoTicks.rows(ticks, dangersRan, hindi)
                    if (rows.isEmpty()) Text(tr("Run the earlier scenes first — skipped steps stay out of this table.", "पहले पिछले सीन चलाएँ — छूटे चरण इस तालिका में नहीं आते।"))
                    rows.forEach { r ->
                        Text((if (r.cloud) "☁ " else "📱 ") + r.step, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                        Text("   " + r.benefit, style = MaterialTheme.typography.bodySmall)
                        Text("   " + r.model + " · " + (if (r.ms > 0) secs(r.ms) else "—"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(tr("Built with Muse Code.", "Muse Code से बनाया गया।"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                val rows = ArogyaDemoTicks.beforeAfter(ticks, hindi)
                DemoCard(tr("Sunita's day, before and after", "सुनीता का दिन, पहले और अब")) {
                    if (rows.isEmpty()) Text(tr("Nothing measured yet — run the earlier scenes first.", "अभी कुछ नापा नहीं — पहले पिछले सीन चलाएँ।"))
                    rows.forEach { r ->
                        Text(r.task, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                        Text(tr("Before: ", "पहले: ") + r.before, style = MaterialTheme.typography.bodySmall)
                        Text(tr("After: ", "अब: ") + secs(r.afterMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Text(tr("No invented percentages — only measured times.", "मनगढ़ंत प्रतिशत नहीं — सिर्फ़ नपे समय।"), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DemoSopAnswer(r: ArogyaAiResult, hindi: Boolean, tr: (String, String) -> String, onOpenSource: (ArogyaSource) -> Unit, secs: (Long) -> String) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    if (r.sources.isNotEmpty()) DemoCard(tr("Top passages", "मुख्य हिस्से")) {
        r.sources.take(3).forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📄 ${s.title} · ${s.locator}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                TextButton(onClick = { onOpenSource(s) }) { Text(tr("Open", "खोलें")) }
            }
            // Item 8: the same "Open official document" the answer card offers (jumps to the page online).
            if (s.url.startsWith("http")) TextButton(onClick = {
                runCatching { uriHandler.openUri(ArogyaGuideLibrary.pageUrl(s.url, s.locator)) }
                    .onFailure { Toast.makeText(context, tr("No browser available.", "ब्राउज़र नहीं है।"), Toast.LENGTH_LONG).show() }
            }) { Text(tr("Open official document", "आधिकारिक दस्तावेज़ खोलें"), style = MaterialTheme.typography.bodySmall) }
            if (s.excerpt.isNotBlank()) ArogyaPassageText(s.copy(excerpt = s.excerpt.take(600) + if (s.excerpt.length > 600) "…" else ""))
        }
    }
    DemoCard(tr("Answer from the official guides · ${r.provider} · ${secs(r.elapsedMs)}", "आधिकारिक गाइड से उत्तर · ${r.provider} · ${secs(r.elapsedMs)}")) {
        // Item 8: the live answer layout — sections with [n] citation markers (tap = the passage).
        SopAnswerText(r.text, r.segments, emptySet()) { id -> r.sources.firstOrNull { it.id == id }?.let(onOpenSource) }
        if (r.quote.isNotBlank() && !ArogyaAnswerFormat.isDuplicate(r.text, r.quote))
            Text("“${r.quote}” — ${r.quoteSource}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        r.sources.firstOrNull()?.let { s -> Text(tr("Source: ", "स्रोत: ") + "${s.title}, ${s.locator}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall) }
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
