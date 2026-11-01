package com.example.arfas

import android.Manifest
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.Bitmap
import android.app.DatePickerDialog
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.util.Locale

@Composable
fun ArogyaVisitScreen(
    onAssistant: (String) -> Unit, onSettings: () -> Unit,
    onRunAi: suspend (ArogyaAiRequest) -> ArogyaAiResult,
    onCancelAi: () -> Unit,
    knowledgeName: String, knowledgeReady: Boolean, knowledgeBusy: Boolean,
    knowledgeStatus: String, onImportKnowledge: (android.net.Uri) -> Unit,
    onRefreshKnowledge: () -> Unit,
    /** Pramaan-backed AI helpers (attachments, card reading, family explanation, ANM message, refresher). */
    ai: ArogyaVisitAi? = null
) {
    val context=LocalContext.current
    val knowledgePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) onImportKnowledge(uri)
    }
    LaunchedEffect(Unit) { onRefreshKnowledge() }
    val uriHandler=LocalUriHandler.current
    val store=remember { ArogyaVisitStore.get(context.applicationContext) }
    val repo=store.first; val queue=store.second
    val stored by queue.state.collectAsState()
    val visits=stored.visits; val loaded=stored.loaded; val error=stored.error
    val pending=if(stored.saving) 1 else 0
    val scope=rememberCoroutineScope()
    var mediaBusy by remember { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var stage by rememberSaveable { mutableStateOf(0) }; var stepIndex by rememberSaveable { mutableStateOf(0) }
    var hindi by rememberSaveable { mutableStateOf(ArogyaLang.uiHindi(context)) }
    fun setHindi(v:Boolean) { hindi=v; ArogyaLang.setUiHindi(context,v) }
    var filter by rememberSaveable { mutableStateOf("Today") }
    var chooseStage by remember { mutableStateOf(false) }
    var chooseFilter by remember { mutableStateOf(false) }
    var choosePerson by remember { mutableStateOf(false) }
    var showCards by remember {mutableStateOf(false)}
    var showOnlineOptions by remember {mutableStateOf(false)}
    var showFullRecord by remember {mutableStateOf(false)}
    var aiOriginalNotes by remember { mutableStateOf("") }
    var autoOrganise by remember { mutableStateOf(false) }
    fun tr(en:String,hi:String)=if(hindi) hi else en
    var source by remember { mutableStateOf<ArogyaSource?>(null) }
    var voice by remember { mutableStateOf(false) }; var ocr by remember { mutableStateOf<ArogyaAttachment?>(null) }
    var ocrVisitId by remember { mutableStateOf<String?>(null) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var aiVisitId by remember { mutableStateOf<String?>(null) }
    var aiOpen by remember { mutableStateOf(false) }
    var aiTask by remember { mutableStateOf(ArogyaAiTask.EXPLAIN) }
    var aiQuestion by remember { mutableStateOf("") }
    var aiTopic by remember { mutableStateOf("") }
    var aiPrevious by remember { mutableStateOf("") }
    var aiResult by remember { mutableStateOf<ArogyaAiResult?>(null) }
    var aiOnline by remember { mutableStateOf(false) }
    var aiError by remember { mutableStateOf("") }
    var aiBusy by remember { mutableStateOf(false) }
    var aiJob by remember { mutableStateOf<Job?>(null) }
    var questionDictation by remember { mutableStateOf(false) }
    var aiCopy by remember { mutableStateOf("") }
    var aiCapture by remember { mutableStateOf<List<ArogyaCaptureItem>>(emptyList()) }
    var autoRunDraft by remember { mutableStateOf(false) }
    var escalateOpen by remember { mutableStateOf(false) }
    var escalateConcern by remember { mutableStateOf("") }
    var historyOpen by remember { mutableStateOf(false) }
    var showPassages by remember { mutableStateOf(false) }
    var historyEntry by remember { mutableStateOf<ArogyaGuideEntry?>(null) }
    fun escalate(concern:String) { escalateConcern=concern; escalateOpen=true }
    // Ask the SOP without a visit: a transient record that is never saved.
    val sopVisit=remember { ArogyaVisit(id=SOP_VISIT_ID,personId=SOP_VISIT_ID,name="",kind=ArogyaKind.FOLLOW_UP) }
    // Question attachments, live progress, family explanation, card reading, refresher
    var qFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    var qFileBusy by remember { mutableStateOf(false) }
    var aiDraft by remember { mutableStateOf("") }
    var aiStartedAt by remember { mutableStateOf(0L) }
    var lastDeltaAt by remember { mutableStateOf(0L) }
    var nowMs by remember { mutableStateOf(0L) }
    var familyText by remember { mutableStateOf("") }
    var familyBusy by remember { mutableStateOf(false) }
    var cardBusy by remember { mutableStateOf(false) }
    var cardReading by remember { mutableStateOf<ArogyaCardReading?>(null) }
    var cardChecked by remember { mutableStateOf(setOf<Int>()) }
    var cardVisitId by remember { mutableStateOf<String?>(null) }
    var quizOpen by remember { mutableStateOf(false) }
    var quizBusy by remember { mutableStateOf(false) }
    var quizItems by remember { mutableStateOf<List<ArogyaQuizItem>>(emptyList()) }
    var quizChoice by remember { mutableStateOf(mapOf<Int,Int>()) }
    var quizError by remember { mutableStateOf("") }
    var photoTarget by remember { mutableStateOf("") }
    // Finish Visit
    var modelSettingsOpen by remember { mutableStateOf(false) }
    var fvOpen by remember { mutableStateOf(false) }
    var fvVisitId by remember { mutableStateOf<String?>(null) }
    var fvStep by remember { mutableStateOf(1) }
    var fvBusy by remember { mutableStateOf(false) }
    var fvError by remember { mutableStateOf("") }
    var fvExtraction by remember { mutableStateOf<ArogyaExtraction?>(null) }
    var fvValues by remember { mutableStateOf<List<String>>(emptyList()) }
    var fvChecked by remember { mutableStateOf(setOf<Int>()) }
    var fvPlan by remember { mutableStateOf<FinishPlan?>(null) }
    var fvHandover by remember { mutableStateOf("") }
    var fvDate by remember { mutableStateOf("") }
    var fvReason by remember { mutableStateOf("") }
    var fvIssues by remember { mutableStateOf<List<String>>(emptyList()) }
    // Offline registers (patients / MCP cards / schedules / registers / backup)
    val registry=remember { ArogyaRegistryStore.get(context.applicationContext) }
    val reg by registry.state.collectAsState()
    var hubOpen by rememberSaveable { mutableStateOf<String?>(null) }
    var visitsOpen by rememberSaveable { mutableStateOf(false) }
    var aiPageOpen by rememberSaveable { mutableStateOf(false) }
    var guidedFor by remember { mutableStateOf<Beneficiary?>(null) }
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    var demoOpen by rememberSaveable { mutableStateOf(false) }
    var profileOpen by rememberSaveable { mutableStateOf(false) }
    val profilePrefs=remember { context.getSharedPreferences("arogya_profile",android.content.Context.MODE_PRIVATE) }
    var ashaName by remember { mutableStateOf(profilePrefs.getString("name","").orEmpty()) }
    var ashaVillage by remember { mutableStateOf(profilePrefs.getString("village","").orEmpty()) }
    var guidedSpeak by remember { mutableStateOf<((String)->Unit)?>(null) }
    var askedQuestionForWarning by remember { mutableStateOf("") }
    fun loadSample() {
        val sm=ArogyaSampleVillage.build()
        runCatching {
            registry.update { r -> r.copy(beneficiaries=r.beneficiaries.filterNot {it.demo}+sm.registry.beneficiaries,
                diary=r.diary.filterNot {it.id.startsWith("demo-")}+sm.registry.diary, stock=r.stock.filterNot {it.id.startsWith("demo-")}+sm.registry.stock,
                vitals=r.vitals.filterNot {it.id.startsWith("demo-")}+sm.registry.vitals,
                forms=r.forms.filterNot {f -> f.id.startsWith("demo-") || f.beneficiaryId.startsWith("demo-")}+sm.registry.forms) }
            queue.resetDemo(sm.visits)
        }.onSuccess { Toast.makeText(context,"Sample village loaded: 50 fictional people",Toast.LENGTH_SHORT).show() }
         .onFailure { Toast.makeText(context,it.message ?: "Could not load the sample",Toast.LENGTH_LONG).show() }
    }
    LaunchedEffect(Unit) { registry.load(); registry.autoBackupIfDue() }
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    // Load the model and select the guide as soon as ArogyaDoot opens, so the first question does not wait for it.
    LaunchedEffect(knowledgeReady) { if(knowledgeReady) runCatching { ai?.warmUp() } }
    // Every ArogyaDoot AI action follows the Settings order (Cloud first / Local first).
    LaunchedEffect(ai) { ai?.cloudChoice = { ArogyaCloud.choice(context.applicationContext) } }
    fun openAi(task:ArogyaAiTask, topic:String="", sop:Boolean=false) {
        aiOriginalNotes=queue.state.value.visits.firstOrNull {it.id==selectedId}?.notes.orEmpty();aiVisitId=if(sop) SOP_VISIT_ID else selectedId;aiTask=task;aiTopic=topic;aiQuestion=if(task==ArogyaAiTask.EXPLAIN && topic.isNotBlank() && topic!="home visit") "Explain $topic in simple language for the family." else ""
        showOnlineOptions=false;showPassages=false;familyText="";askedQuestionForWarning="";aiDraft="";ai?.clearAttachments();qFiles=emptyList();aiCapture=emptyList();aiResult=null;aiError="";aiPrevious="";aiCopy="";aiOpen=true
    }
    DisposableEffect(Unit) { onDispose { if(aiBusy) onCancelAi();aiJob?.cancel() } }
    var exportVisitId by rememberSaveable { mutableStateOf<String?>(null) }
    var exportRevision by rememberSaveable { mutableStateOf<Long?>(null) }
    var attachmentVisitId by rememberSaveable { mutableStateOf<String?>(null) }
    var ttsReady by remember { mutableStateOf(false) }
    val tts=remember { TextToSpeech(context.applicationContext) { ttsReady=it==TextToSpeech.SUCCESS } }
    var voiceMissing by remember { mutableStateOf<String?>(null) }
    fun speak(text: String) {
        val lang=if(text.any { it in '\u0900'..'\u097F' }) "hi" else "en"
        // Cloud first + signal: the better online Android voice; otherwise (or as fallback) the offline voice.
        // hi-IN/en-IN outranks other regions; rate 0.9 for clarity. Meta has no TTS: this is the Android voice.
        val all=if(ttsReady) tts.voices.orEmpty().toList() else emptyList()
        val online=ArogyaCloud.cloudFirst(context) && MetaOnline.hasInternet(context)
        val at=runCatching { ArogyaLang.rankVoice(all.map { Triple(it.locale,it.quality,it.isNetworkConnectionRequired) },lang,online) }.getOrDefault(-1)
        val pick=if(at in all.indices) all[at] else null
        if(pick==null) voiceMissing=lang
        else { tts.voice=pick; tts.setSpeechRate(0.9f); tts.speak(text,TextToSpeech.QUEUE_FLUSH,null,"arogya-step") }
    }
    DisposableEffect(tts) {
        val owner=context as? LifecycleOwner
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_STOP) { tts.stop(); voice=false } }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer); tts.stop(); tts.shutdown() }
    }
    fun save(value: ArogyaVisit, invalidateReview: Boolean = true) {
        runCatching {queue.save(value.copy(reviewed=if(invalidateReview) false else value.reviewed))}.onFailure {Toast.makeText(context,it.message ?: "Save failed",Toast.LENGTH_LONG).show()}
    }
    fun edit(id:String,invalidateReview:Boolean=true,change:(ArogyaVisit)->ArogyaVisit):Boolean = runCatching {
        queue.update(id) {row->change(row).let {it.copy(reviewed=if(invalidateReview) false else it.reviewed)}}
    }.onFailure {Toast.makeText(context,it.message ?: "Record changed; reopen it.",Toast.LENGTH_LONG).show()}.isSuccess
    fun appendNotes(id: String?, addition: String): Boolean {
        val current=queue.state.value.visits.firstOrNull {it.id==id} ?: return false
        if(current.completed) return false
        val combined=ArogyaWorkflow.appendNote(current.notes,addition)
        if(combined==null) {
            Toast.makeText(context,"Note limit reached or draft empty. Shorten the draft; nothing was added or cut.",Toast.LENGTH_LONG).show()
            return false
        }
        return edit(current.id) {latest->latest.copy(notes=ArogyaWorkflow.appendNote(latest.notes,addition) ?: error("Note limit reached; nothing added"))}
    }
    val visit=visits.firstOrNull { it.id==selectedId }
    val dueNow=remember(reg,visits) { ArogyaSchedule.dueList(reg.beneficiaries,visits,LocalDate.now(),7) }
    val locked=pending>0 || mediaBusy || aiBusy || error.isNotBlank()
    fun start(v: ArogyaVisit) { save(v); selectedId=v.id; stage=0; stepIndex=0 }
    fun acceptAttachment(vId: String, attachment: ArogyaAttachment) {
        val target=queue.state.value.visits.firstOrNull { it.id==vId }
        if(target!=null && !target.completed && edit(target.id) {row->row.copy(attachments=row.attachments+attachment)}) { ocrVisitId=target.id; ocr=attachment }
        else scope.launch(Dispatchers.IO) { repo.deleteAttachment(attachment.id) }
    }
    val pick=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id=attachmentVisitId; attachmentVisitId=null
        if(uri!=null && id!=null) { mediaBusy=true; scope.launch {
            try { queue.state.first { it.loaded || it.error.isNotBlank() }; check(queue.state.value.loaded); acceptAttachment(id,ArogyaVisitMedia.import(context,uri,repo)) }
            catch(e:CancellationException) { throw e }
            catch (_: Exception) { Toast.makeText(context,"Could not attach. Choose an image/PDF under 15 MB.",Toast.LENGTH_LONG).show() }
            finally { mediaBusy=false }
        } }
    }
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        val id=attachmentVisitId; attachmentVisitId=null
        if(bitmap!=null && id!=null) { mediaBusy=true; scope.launch {
            try { queue.state.first { it.loaded || it.error.isNotBlank() }; check(queue.state.value.loaded); acceptAttachment(id,ArogyaVisitMedia.camera(context,bitmap,repo)) }
            catch(e:CancellationException) { throw e }
            catch (_: Exception) { Toast.makeText(context,"Photo could not be attached.",Toast.LENGTH_LONG).show() }
            finally { mediaBusy=false }
        } }
    }
    val cameraPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted) runCatching { camera.launch(null) }.onFailure { Toast.makeText(context,"Camera unavailable. Attach a photo instead.",Toast.LENGTH_LONG).show() }
    }
    val mic=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if(granted) voice=true else {autoOrganise=false;Toast.makeText(context,"Microphone permission was not granted. Type your observations below.",Toast.LENGTH_LONG).show()} }
    val pdf=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val id=exportVisitId; val revision=exportRevision; exportVisitId=null;exportRevision=null
        if(uri!=null) { mediaBusy=true; scope.launch {
            try {
                val saved=withContext(Dispatchers.IO) { repo.all().firstOrNull {it.id==id && it.updatedAt==revision} }
                    ?: error("Export record changed or is missing. Reopen it and export again.")
                ArogyaVisitMedia.exportPdf(context,uri,ArogyaWorkflow.report(saved))
                Toast.makeText(context,"Visit PDF saved to your chosen destination.",Toast.LENGTH_LONG).show()
            } catch(e:CancellationException) { throw e }
            catch (_: Exception) { Toast.makeText(context,"PDF could not be saved. Reopen the visit and retry; remove any incomplete destination file.",Toast.LENGTH_LONG).show() }
            finally { mediaBusy=false }
        } }
    }
    fun toast(msg:String) = Toast.makeText(context,msg,Toast.LENGTH_LONG).show()
    fun deleteTemp(uri:Uri) { runCatching { context.contentResolver.delete(uri,null,null) } }
    fun readCard(uri:Uri, temp:Boolean) {
        val vid=selectedId ?: return
        if(ai==null) { toast("Card reading is not available."); return }
        cardBusy=true
        scope.launch {
            try {
                // The card photo itself is kept with the visit (encrypted on this phone).
                val v=queue.state.value.visits.firstOrNull {it.id==vid}
                if(v!=null && !v.completed && v.attachments.size<8) runCatching {
                    val a=ArogyaVisitMedia.import(context,uri,repo)
                    if(!edit(vid) {row->row.copy(attachments=row.attachments+a)}) withContext(Dispatchers.IO) { repo.deleteAttachment(a.id) }
                }
                val r=ai.readCard(uri)
                cardReading=r; cardChecked=r.fields.indices.toSet(); cardVisitId=vid
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { toast(e.message ?: "Could not read the card.") }
            finally { if(temp) deleteTemp(uri); cardBusy=false }
        }
    }
    fun attachToQuestion(uri:Uri, temp:Boolean) {
        if(ai==null) { toast("Attachments are not available."); return }
        qFileBusy=true
        scope.launch {
            try {
                val name=ai.attach(uri)
                if(name==null) toast(tr("Could not read this file. Use a photo or PDF.","यह फ़ाइल पढ़ी नहीं जा सकी। फ़ोटो या PDF चुनें।"))
                qFiles=ai.attachedNames()
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { toast(e.message ?: "Could not attach.") }
            finally { if(temp) deleteTemp(uri); qFileBusy=false }
        }
    }
    val takePhoto=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri=photoUri; photoUri=null
        if(uri!=null) { if(!ok) deleteTemp(uri) else if(photoTarget=="card") readCard(uri,true) else attachToQuestion(uri,true) }
    }
    fun launchPhoto() {
        // Full-resolution photo (the preview thumbnail is too small to read a card).
        val uri=runCatching { context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME,"arogya_${System.currentTimeMillis()}.jpg"); put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg") }) }.getOrNull()
        if(uri==null) { toast("Camera unavailable. Choose a photo instead."); return }
        photoUri=uri
        runCatching { takePhoto.launch(uri) }.onFailure { deleteTemp(uri); photoUri=null; toast("Camera unavailable. Choose a photo instead.") }
    }
    val photoPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if(granted) launchPhoto() else toast("Camera permission was not granted.") }
    fun startPhoto(target:String) { photoTarget=target; if(context.checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) launchPhoto() else photoPermission.launch(Manifest.permission.CAMERA) }
    val questionFilePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null) attachToQuestion(uri,false) }
    val cardFilePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null) readCard(uri,false) }
    fun fvVerified(): List<ExtractedField> = fvExtraction?.fields.orEmpty().mapIndexedNotNull { i,f ->
        if(i !in fvChecked) null else { val v=fvValues.getOrNull(i)?.trim().orEmpty(); if(v.isBlank()) null else if(v!=f.value) f.copy(value=v,source="asha") else f }
    }
    fun startFinish() {
        val v=queue.state.value.visits.firstOrNull {it.id==selectedId} ?: return
        if(ai==null) { toast("AI is not available."); return }
        fvVisitId=v.id; fvStep=1; fvError=""; fvExtraction=null; fvPlan=null; fvIssues=emptyList(); fvOpen=true; fvBusy=true
        scope.launch {
            try {
                val r=ai.extractFields(v)
                fvExtraction=r; fvValues=r.fields.map {it.value}; fvChecked=r.fields.indices.toSet()
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { fvError=e.message ?: "Could not read the notes." }
            finally { fvBusy=false }
        }
    }
    fun loadQuiz() {
        if(ai==null) return
        quizBusy=true; quizError=""; quizItems=emptyList(); quizChoice=emptyMap()
        scope.launch {
            try { quizItems=ai.refresher(3) }
            catch(e:CancellationException) { throw e }
            catch(e:Exception) { quizError=e.message ?: "Could not prepare the questions." }
            finally { quizBusy=false }
        }
    }
    BackHandler(selectedId!=null) { if(!locked && !voice && !aiOpen) selectedId=null }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal=16.dp)) {
        if(visit==null) {
            // Home header: app name, what this screen is, today, and whether AI is on the phone or the cloud.
            Row(Modifier.fillMaxWidth().padding(top=8.dp),verticalAlignment=Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("ArogyaDoot",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)
                    Text(tr("ASHA Dashboard","आशा डैशबोर्ड"),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                    Text(tr("Accredited Social Health Activist","मान्यता प्राप्त सामाजिक स्वास्थ्य कार्यकर्ता"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if(ashaName.isBlank()) tr("👤 Tap to add your name and village","👤 अपना नाम और गाँव जोड़ें") else tr("Namaste, $ashaName","नमस्ते, $ashaName")+(if(ashaVillage.isNotBlank()) " · $ashaVillage" else ""),
                        style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.primary,modifier=Modifier.padding(top=4.dp).clickable { profileOpen=true })
                }
                Column(horizontalAlignment=Alignment.End) {
                    TextButton(onClick={setHindi(!hindi)}) {Text(if(hindi) "English" else "हिन्दी")}
                    TextButton(onClick=onSettings,enabled=!locked) { Text(tr("⚙ Setup","⚙ सेटअप")) }
                }
            }
            val online=remember(aiPageOpen,hubOpen,pickerOpen) { MetaOnline.hasInternet(context) }
            val cloudFirst=remember(aiPageOpen,hubOpen,pickerOpen) { ArogyaCloud.cloudFirst(context) }
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM",if(hindi) java.util.Locale("hi","IN") else java.util.Locale.ENGLISH)),
                    style=MaterialTheme.typography.bodyMedium)
                AssistChip(onClick={aiPageOpen=true},label={ Text(when {
                    !online -> tr("📱 Offline · phone AI","📱 ऑफ़लाइन · फ़ोन AI")
                    cloudFirst -> tr("☁ Online · Meta cloud AI","☁ ऑनलाइन · Meta क्लाउड AI")
                    else -> tr("📱 Online · phone AI first","📱 ऑनलाइन · पहले फ़ोन AI") },style=MaterialTheme.typography.labelMedium) })
            }
        } else {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={ if(!locked) selectedId=null },enabled=!locked) { Text("‹ ArogyaDoot",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold) }
                TextButton(onClick={setHindi(!hindi)}) {Text(if(hindi) "English" else "हिन्दी")}
            }
            Text(visit.name.ifBlank { tr("New visit","नई विज़िट") },style=MaterialTheme.typography.titleLarge)
        }
        Text(if(pending>0) "Saving on this phone…" else if(mediaBusy) "Processing locally…" else tr("Saved on this phone • private by default","इस फ़ोन पर सुरक्षित • अपने आप अपलोड नहीं होता"),style=MaterialTheme.typography.bodySmall)
        if(error.isNotBlank()) {
            Text(error,color=MaterialTheme.colorScheme.error)
            if(loaded) TextButton(onClick={ queue.retry() },enabled=pending==0) { Text(tr("Retry save","फिर सहेजें")) }
        }
        if(!loaded) { if(error.isBlank()) CircularProgressIndicator(); return@Column }
        if(visit==null) {
            val drafts=visits.filter {!it.completed}
            LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp),modifier=Modifier.weight(1f)) {
                // 1. Today — what to do now
                item { ArogyaTodayCard(hindi,dueNow,LocalDate.now()) { hubOpen="due" } }
                // 2. Four things she does, nothing else
                item { ArogyaHomeGrid(hindi,reg.beneficiaries.count {it.active},
                    onPeople={ hubOpen="patients" }, onVisit={ pickerOpen=true },
                    onAsk={ if(knowledgeReady) openAi(ArogyaAiTask.EXPLAIN,"",sop=true) else Toast.makeText(context,tr("Install the ASHA guide first (below).","पहले ASHA गाइड इंस्टॉल करें (नीचे)।"),Toast.LENGTH_LONG).show() },
                    onRegisters={ hubOpen="registers" },
                    dueToday=dueNow.count { !it.done && !it.due.isAfter(LocalDate.now()) },
                    referrals=visits.count { it.completed && it.referral==ArogyaReferral.NEEDED && it.date>=LocalDate.now().minusDays(7).toString() }) }
                item {
                    Button(onClick={demoOpen=true},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.tertiary)) {
                        Text(tr("▶ Watch the 3-minute demo","▶ 3 मिनट का डेमो देखें"),style=MaterialTheme.typography.titleMedium) }
                }
                // 3. Unfinished work
                if(drafts.isNotEmpty()) item {
                    OutlinedButton(onClick={visitsOpen=true},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) { Text(tr("Continue unfinished visits (${drafts.size})","अधूरी विज़िट जारी रखें (${drafts.size})")) }
                }
                // 4. Small extras
                item {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                        TextButton(onClick={visitsOpen=true}) { Text(tr("🕘 Visits","🕘 विज़िट")) }
                        TextButton(onClick={hubOpen="backup"}) { Text(tr("☁ Backup","☁ बैकअप")) }
                        TextButton(onClick={aiPageOpen=true}) { Text("✨ AI") }
                        if(knowledgeReady && ai!=null) TextButton(onClick={quizOpen=true;if(quizItems.isEmpty() && !quizBusy) loadQuiz()}) { Text(tr("📚 Practice","📚 अभ्यास")) }
                    }
                }
                if(reg.beneficiaries.isEmpty()) item {
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(tr("No people yet","अभी कोई व्यक्ति नहीं"),fontWeight=FontWeight.Bold)
                        Text(tr("Add the families from your household survey under People, or try a sample village of 50 fictional people.","घर सर्वे के परिवार 'लोग' में जोड़ें, या 50 काल्पनिक लोगों का नमूना गाँव देखें।"),style=MaterialTheme.typography.bodySmall)
                        Button(onClick={loadSample()}) { Text(tr("Load sample village (50 people)","नमूना गाँव लोड करें (50 लोग)")) }
                    } }
                }
                if(!knowledgeReady || knowledgeBusy) item {
                    Card(Modifier.fillMaxWidth(),shape=androidx.compose.foundation.shape.RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text(tr("HEALTH GUIDE","स्वास्थ्य गाइड"),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Bold)
                            // One obvious state: ready offline, installing, or install. Technical detail stays small.
                            Text(when { knowledgeBusy -> tr("Installing ASHA guide…","ASHA गाइड इंस्टॉल हो रही है…")
                                knowledgeReady -> tr("✓ ASHA guide ready offline","✓ ASHA गाइड ऑफ़लाइन तैयार है")
                                else -> tr("Install ASHA guide","ASHA गाइड इंस्टॉल करें") },
                                style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,
                                color=if(knowledgeReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            if(knowledgeReady && knowledgeName.isNotBlank()) Text(knowledgeName,style=MaterialTheme.typography.bodyMedium)
                            Text(knowledgeStatus,style=MaterialTheme.typography.bodySmall)
                            if(knowledgeBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                            if(knowledgeReady) TextButton(onClick={knowledgePicker.launch(arrayOf("*/*"))},enabled=!knowledgeBusy && !locked) {Text(tr("Replace guide","गाइड बदलें"))}
                            else Button(onClick={knowledgePicker.launch(arrayOf("*/*"))},enabled=!knowledgeBusy && !locked,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) {
                                Text(if(knowledgeBusy) tr("Installing…","इंस्टॉल हो रहा है…") else tr("Install ASHA guide","ASHA गाइड इंस्टॉल करें"),style=MaterialTheme.typography.titleMedium)
                            }
                            if(!knowledgeReady && !knowledgeBusy) Text(tr("Choose the file ${ArogyaGuideDb.wantedName()} from your phone. If it fails, copy the file to Downloads and try again.","फ़ोन से ${ArogyaGuideDb.wantedName()} फ़ाइल चुनें। न हो तो फ़ाइल को Downloads में कॉपी करके फिर कोशिश करें।"),style=MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if(knowledgeReady && ai!=null) item {
                    OutlinedButton(onClick={quizOpen=true;if(quizItems.isEmpty() && !quizBusy) loadQuiz()},modifier=Modifier.fillMaxWidth()) {
                        Text(tr("📚 Today's 2-minute refresher — 3 questions from your guide","📚 आज का 2 मिनट अभ्यास — गाइड से 3 सवाल"))
                    }
                } else item {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(tr("✓ ASHA guide ready offline","✓ ASHA गाइड ऑफ़लाइन तैयार"),color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.bodySmall,modifier=Modifier.weight(1f))
                        TextButton(onClick={knowledgePicker.launch(arrayOf("*/*"))},enabled=!locked) { Text(tr("Change","बदलें"),style=MaterialTheme.typography.bodySmall) }
                    }
                }
                if(reg.beneficiaries.any {it.demo}) item {
                    TextButton(onClick={loadSample()}) { Text(tr("Reset sample village","नमूना गाँव फिर से शुरू करें"),style=MaterialTheme.typography.bodySmall) }
                }
            }
        } else {
            if(visit.demo) Text(tr("SYNTHETIC DEMO RECORD","नमूना डेमो रिकॉर्ड"),color=MaterialTheme.colorScheme.primary)
            if(ArogyaWorkflow.priorityConcern(visit) || ArogyaUrgentTriage.assess(visit.notes,visit)!=null) Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(12.dp)) { Text(tr("Urgent concern recorded","तत्काल चिंता दर्ज"),fontWeight=FontWeight.Bold); Text(tr("Arrange urgent assessment through your referral pathway. Do not wait for AI, a report or the next routine visit.","अपने रेफ़रल रास्ते से तुरंत जाँच कराएँ। AI, रिपोर्ट या अगली विज़िट का इंतज़ार न करें।")); Button(onClick={escalate("")},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)) { Text(tr("Escalate to ANM","ANM को सूचित करें")) } }
            }
            val phases=listOf(tr("Person","व्यक्ति"),tr("Check","जाँच"),tr("Speak & review","बोलें और जाँचें"),tr("Next action","अगला कदम"),tr("Save","सहेजें"))
            val order=listOf(0,2,1,3,4)
            LinearProgressIndicator(progress=(order.indexOf(stage)+1)/5f,modifier=Modifier.fillMaxWidth().padding(vertical=12.dp))
            Row(Modifier.fillMaxWidth()) {
                Text("${order.indexOf(stage)+1} / 5 · ${phases[stage]}",modifier=Modifier.weight(1f),fontWeight=FontWeight.Bold)
                TextButton(onClick={chooseStage=!chooseStage}) {Text(if(chooseStage) "Close" else "Go to…")}
            }
            if(chooseStage) Column {
                order.forEach { i -> val label=phases[i]; TextButton(onClick={stage=i;chooseStage=false},enabled=i!=1 || ArogyaWorkflow.profileIssues(visit).isEmpty()) {Text("${order.indexOf(i)+1}. $label")} }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                if(visit.completed && stage!=4) Text(tr("Completed records are read-only. Start a follow-up to record a new action.","पूरे रिकॉर्ड सिर्फ़ पढ़े जा सकते हैं। नया काम दर्ज करने के लिए फ़ॉलो-अप शुरू करें।"))
                val editable=!visit.completed && !mediaBusy && !aiBusy && error.isBlank()
                when(stage) {
                    0 -> {
                        ArogyaSection(tr("STEP 1","चरण 1"),tr("Who are we visiting?","किसकी विज़िट है?"))
                        ArogyaField("Name or beneficiary code",visit.name,editable) { edit(visit.id) {row->row.copy(name=it.take(100))} }
                        Text(tr("Local ID","स्थानीय ID")+": ${visit.personId.take(8)} • "+tr("use Follow-up visit to keep this identity","यही पहचान रखने के लिए फ़ॉलो-अप विज़िट करें"),style=MaterialTheme.typography.bodySmall)
                        ArogyaField("Village / locality",visit.village,editable) { edit(visit.id) {row->row.copy(village=it.take(120))} }
                        ArogyaDateField(tr("Visit date","विज़िट की तारीख"),visit.date,editable) { value -> edit(visit.id) {row->row.copy(date=value)} }
                        Text(tr("Visit type","विज़िट का प्रकार"))
                        ArogyaKind.values().forEach { kind ->
                            FilterChip(selected=visit.kind==kind,enabled=editable && visit.answers.isEmpty(),onClick={edit(visit.id) {row->row.copy(kind=kind)};stepIndex=0},label={Text(kind.title)})
                        }
                        Text(tr("Type locks after the first step response. Start a new visit if the wrong checklist was selected.","पहले जवाब के बाद प्रकार नहीं बदलेगा। गलत सूची चुनी हो तो नई विज़िट शुरू करें।"),style=MaterialTheme.typography.bodySmall)
                        if(visit.kind!=ArogyaKind.FOLLOW_UP) {
                            ArogyaDateField(tr("Child’s date of birth","बच्चे की जन्म तारीख"),visit.birthDate,editable) { value -> edit(visit.id) {row->row.copy(birthDate=value)} }
                            Row { Checkbox(checked=visit.homeBirth,onCheckedChange={edit(visit.id) {row->row.copy(homeBirth=it)}},enabled=editable); Text(tr("Birth at home","घर पर जन्म")) }
                        }
                        Text(ArogyaWorkflow.FRESHNESS,style=MaterialTheme.typography.bodySmall)
                        ArogyaWorkflow.profileIssues(visit).forEach {Text(it,color=MaterialTheme.colorScheme.error)}
                        Button(onClick={stage=2},enabled=ArogyaWorkflow.profileIssues(visit).isEmpty(),modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) { Text(tr("Continue to voice notes","बोलकर नोट दर्ज करें")) }
                    }
                    1 -> {
                        val steps=ArogyaWorkflow.steps(visit); val step=steps[stepIndex.coerceIn(0,steps.lastIndex)]
                        Text(tr("Step","चरण")+" ${stepIndex+1} / ${steps.size} • ${visit.answers.size} "+tr("responses recorded","जवाब दर्ज"))
                        val instruction=if(hindi) ArogyaHindi.instruction(step,visit.kind) else step.instruction
                        Text(step.title,style=MaterialTheme.typography.titleLarge); Text(instruction)
                        if(hindi) Text("सरल हिन्दी विवरण — मूल स्रोत अलग से देखें।",style=MaterialTheme.typography.bodySmall)
                        Text(tr("Record what you found below. Checked means the question was covered; it does not mean the person is well.","नीचे जो पाया लिखें। निशान का मतलब सवाल पूछा गया, यह नहीं कि व्यक्ति ठीक है।"),style=MaterialTheme.typography.bodySmall)
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            listOf(ArogyaAnswer.DONE to (if(hindi) "जांच की" else "Checked"),ArogyaAnswer.CONCERN to (if(hindi) "चिंता" else "Concern"),ArogyaAnswer.UNKNOWN to (if(hindi) "पता नहीं" else "Not sure")).forEach { (a,label) ->
                                FilterChip(selected=visit.answers[step.id]==a,onClick={
                                    edit(visit.id) {row->row.copy(answers=row.answers+(step.id to a),referral=if(step.priority && a==ArogyaAnswer.CONCERN && row.referral==ArogyaReferral.NONE) ArogyaReferral.NEEDED else row.referral)}
                                },enabled=editable,label={Text(label)})
                            }
                        }
                        ArogyaField("Visit observations — record what you found, with units",visit.notes,editable,3) { value ->
                            edit(visit.id) { row -> row.copy(notes=value.take(20000)) }
                        }
                        Text(tr("Saved with this visit. Include the question name when adding an observation.","इसी विज़िट के साथ सहेजा जाएगा। कुछ देखें तो सवाल का नाम भी लिखें।"),style=MaterialTheme.typography.bodySmall)
                        Row { TextButton(onClick={speak(instruction)}) { Text(tr("Read aloud","सुनें")) }; TextButton(onClick={tts.stop()}) { Text(tr("Stop","रोकें")) } }
                        OutlinedButton(onClick={openAi(ArogyaAiTask.EXPLAIN,step.title)},enabled=!locked && knowledgeReady) { Text(if(hindi) "स्वास्थ्य गाइड से पूछें" else "Help with this question") }
                        step.sourceId?.let { id -> TextButton(onClick={source=ArogyaSources.all[id]}) { Text(tr("View source passage","स्रोत अनुच्छेद देखें")) } }
                        Row {
                            TextButton(onClick={stepIndex--},enabled=stepIndex>0) { Text(tr("Previous","पिछला")) }
                            Button(onClick={if(stepIndex<steps.lastIndex) stepIndex++ else stage=3},enabled=visit.answers[step.id]!=null) { Text(if(stepIndex<steps.lastIndex) "Next" else tr("Plan next action","अगला कदम")) }
                        }
                    }
                    2 -> {
                        ArogyaSection(tr("VOICE TO VISIT RECORD","आवाज़ से विज़िट रिकॉर्ड"),tr("Tell me about the visit","विज़िट के बारे में बताइए"),tr("Ask permission before recording. Say what the family told you, what you observed and what you did.","रिकॉर्ड करने से पहले अनुमति लें। परिवार ने क्या बताया, आपने क्या देखा और क्या किया—बोलकर दर्ज करें।"))
                        Button(onClick={questionDictation=false;autoOrganise=true;if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) voice=true else mic.launch(Manifest.permission.RECORD_AUDIO)},enabled=editable && !locked,modifier=Modifier.fillMaxWidth().heightIn(min=64.dp)) {Text(tr("●  Speak about this visit","●  विज़िट के बारे में बोलें"),fontWeight=FontWeight.Bold)}
                        ArogyaField("Observed facts, measurements with units, concerns and action taken",visit.notes,editable,5) { edit(visit.id) {row->row.copy(notes=it.take(20000))} }
                        TextButton(onClick={showCards=!showCards}) {Text(tr(if(showCards) "Hide cards and photos" else "Add a card or photo",if(showCards) "कार्ड और फ़ोटो छिपाएँ" else "कार्ड या फ़ोटो जोड़ें"))}
                        // Read an MCP card with AI: the photo is kept with the visit; values are shown for the ASHA to tick.
                        Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.secondaryContainer)) { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment=Alignment.CenterVertically) { Text(tr("Read the MCP card with AI","AI से MCP कार्ड पढ़ें"),fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f)); AiBadge("Read MCP card photo") }
                            Text(tr("Take a photo of the card. Dates, weights and vaccines are read on this phone; you tick what is correct.","कार्ड की फ़ोटो लें। तारीख, वज़न और टीके फ़ोन पर पढ़े जाते हैं; सही को आप चुनें।"),style=MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                Button(onClick={startPhoto("card")},enabled=editable && !locked && !cardBusy && ai!=null,modifier=Modifier.weight(1f)) { Text(tr("📷 Photo of card","📷 कार्ड की फ़ोटो")) }
                                OutlinedButton(onClick={cardFilePicker.launch(arrayOf("image/*","application/pdf"))},enabled=editable && !locked && !cardBusy && ai!=null,modifier=Modifier.weight(1f)) { Text(tr("Choose file","फ़ाइल चुनें")) }
                            }
                            if(cardBusy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(tr("Reading the card on this phone…","फ़ोन पर कार्ड पढ़ा जा रहा है…"),style=MaterialTheme.typography.bodySmall) }
                        } }
                        if(showCards) Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick={attachmentVisitId=visit.id;pick.launch(arrayOf("image/*","application/pdf"))},enabled=editable && !locked && visit.attachments.size<8,modifier=Modifier.fillMaxWidth()) { Text(tr("Attach a card or document","कार्ड या दस्तावेज़ जोड़ें")) }
                        TextButton(onClick={
                            attachmentVisitId=visit.id
                            if(context.checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) runCatching { camera.launch(null) }.onFailure { Toast.makeText(context,"Camera unavailable",Toast.LENGTH_SHORT).show() }
                            else cameraPermission.launch(Manifest.permission.CAMERA)
                        },enabled=editable && !locked && visit.attachments.size<8) { Text(tr("Photograph a card (preview quality)","कार्ड की फ़ोटो लें")) }
                        Text(tr("Ask permission first. Images and OCR remain on this phone. OCR may misread dates, handwriting and units; nothing is filled automatically.","पहले इजाज़त लें। फ़ोटो और पढ़ा पाठ इसी फ़ोन पर रहेगा। तारीख़, लिखावट और इकाई गलत पढ़ी जा सकती है; कुछ अपने आप नहीं भरेगा।"),style=MaterialTheme.typography.bodySmall)
                        visit.attachments.forEach { a ->
                            Row { TextButton(onClick={ocrVisitId=visit.id;ocr=a}) { Text("${a.label}: review OCR") }
                                TextButton(onClick={edit(visit.id) {row->row.copy(attachments=row.attachments.filterNot { it.id==a.id })}},enabled=editable && !locked) { Text(tr("Remove","हटाएँ")) } }
                        }
                        }
                        OutlinedButton(onClick={openAi(ArogyaAiTask.ORGANISE_NOTES);autoRunDraft=true},enabled=editable && !locked && visit.notes.isNotBlank()) { Text(tr("Organise with AI","AI से नोट व्यवस्थित करें")) }
                        Row {Checkbox(checked=visit.notesReviewedForDanger,onCheckedChange={value->edit(visit.id){row->row.copy(notesReviewedForDanger=value)}},enabled=editable && !locked);Text(tr("I checked these notes against the priority steps and recorded concerns or uncertainty there.","मैंने नोट ज़रूरी चरणों से मिलाए और चिंता या संदेह वहीं दर्ज किया।"),modifier=Modifier.weight(1f))}
                        OutlinedButton(onClick={escalate("")},enabled=!locked,modifier=Modifier.fillMaxWidth()) {Text(tr("⚠ Escalate to ANM","⚠ ANM को सूचित करें"),color=MaterialTheme.colorScheme.error)}
                        ArogyaCapture.missing(visit).forEach { Text(it,style=MaterialTheme.typography.bodySmall) }
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Button(onClick={startFinish()},enabled=editable && !locked && visit.notes.isNotBlank() && ai!=null,modifier=Modifier.weight(1f).heightIn(min=56.dp)) { Text(tr("✅ Finish visit with AI","✅ AI से विज़िट पूरी करें"),style=MaterialTheme.typography.titleMedium) }
                            AiBadge("Finish visit",Modifier.padding(start=6.dp))
                        }
                        OutlinedButton(onClick={stage=1},modifier=Modifier.fillMaxWidth()) { Text(tr("Continue to visit checks","विज़िट की जाँच जारी रखें")) }
                    }
                    3 -> {
                        ArogyaSection(tr("FOLLOW-UP","अगली विज़िट"),tr("What happens next?","अगला कदम क्या है?"),tr("Record only actions actually taken. Confirm the next date with the family and your applicable plan.","केवल किए गए काम दर्ज करें। अगली तारीख परिवार और लागू योजना से जाँचें।"))
                        Button(onClick={escalate("")},enabled=!locked,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)) { Text(tr("Escalate to ANM — call / WhatsApp / SMS","ANM को सूचित करें — कॉल / व्हाट्सऐप / SMS")) }
                        OutlinedButton(onClick={openAi(ArogyaAiTask.HANDOVER)},enabled=!locked && visit.notes.isNotBlank()) { Text(tr("Draft ANM handover • local AI","ANM संदेश बनाएँ • फ़ोन AI")) }
                        ArogyaReferral.values().forEach { r -> FilterChip(selected=visit.referral==r,onClick={edit(visit.id) {row->row.copy(referral=r,referralResolutionConfirmed=false)}},enabled=editable,label={Text(when(r) { ArogyaReferral.NONE->"No referral recorded"; ArogyaReferral.NEEDED->"Help needed"; ArogyaReferral.CONTACTED->"ANM / facility contacted"; ArogyaReferral.ARRANGED->"Transport / referral arranged"; ArogyaReferral.SEEN->"Seen by provider (confirm outcome below)" })}) }
                        ArogyaField("Referral destination, contact, action/time and outcome or pending task",visit.referralNote,editable,3) { edit(visit.id) {row->row.copy(referralNote=it.take(5000),referralResolutionConfirmed=false)} }
                        TextButton(onClick={
                            val d=ArogyaWorkflow.parseDate(visit.nextDate) ?: LocalDate.now()
                            DatePickerDialog(context,{_,y,m,day -> edit(visit.id) {row->row.copy(nextDate=LocalDate.of(y,m+1,day).toString(),planConfirmed=false)}},d.year,d.monthValue-1,d.dayOfMonth).show()
                        },enabled=editable) { Text(tr("Choose next visit date","अगली विज़िट की तारीख़ चुनें")) }
                        if(visit.referral==ArogyaReferral.SEEN) Row {Checkbox(checked=visit.referralResolutionConfirmed,onCheckedChange={value->edit(visit.id){row->row.copy(referralResolutionConfirmed=value)}},enabled=editable && visit.referralNote.isNotBlank());Text(tr("I confirmed and recorded the provider outcome for this referral episode.","मैंने इस रेफ़रल का अस्पताल/डॉक्टर परिणाम पक्का करके दर्ज किया।"),modifier=Modifier.weight(1f))}
                        ArogyaField("Next visit date (YYYY-MM-DD), or leave blank",visit.nextDate,editable) { edit(visit.id) {row->row.copy(nextDate=it.take(10),planConfirmed=false)} }
                        ArogyaField("Next action / reason; if no date, explain why",visit.nextReason,editable,3) { edit(visit.id) {row->row.copy(nextReason=it.take(3000),planConfirmed=false)} }
                        Text(tr("Reference schedule only — not an automatic plan","सिर्फ़ संदर्भ सूची — अपने आप योजना नहीं"),fontWeight=FontWeight.Bold)
                        Text(ArogyaWorkflow.FRESHNESS,style=MaterialTheme.typography.bodySmall)
                        ArogyaWorkflow.referenceDates(visit).forEach { (label,date) -> Text("$label: $date",style=MaterialTheme.typography.bodySmall) }
                        Text(tr("Verify day-counting and dates against the card/local protocol. At-risk or discharged babies need an individual provider plan. Missed visits are not marked completed.","दिन-गिनती और तारीख़ कार्ड/स्थानीय नियम से मिलाएँ। जोखिम वाले या छुट्टी पाए बच्चों के लिए अलग डॉक्टर योजना चाहिए। छूटी विज़िट पूरी नहीं मानी जाती।"),style=MaterialTheme.typography.bodySmall)
                        Row { Checkbox(checked=visit.planConfirmed,onCheckedChange={edit(visit.id) {row->row.copy(planConfirmed=it)}},enabled=editable); Text(tr("I confirmed this next plan against the applicable programme / supervisor instructions.","मैंने अगली योजना कार्यक्रम/सुपरवाइज़र निर्देश से मिलाई।"),modifier=Modifier.weight(1f)) }
                        Button(onClick={stage=4}) { Text(tr("Review record","रिकॉर्ड देखें")) }
                    }
                    4 -> {
                        ArogyaSection(tr("READY TO SAVE","सहेजने से पहले"),tr("Review this visit","विज़िट की समीक्षा करें"))
                        if(!visit.completed && visit.notes.isNotBlank()) Row {Checkbox(checked=visit.notesReviewedForDanger,onCheckedChange={value->edit(visit.id){row->row.copy(notesReviewedForDanger=value)}},enabled=editable && !locked);Text(tr("I checked the observations against the visit checks and recorded concerns or uncertainty.","मैंने नोट और विज़िट की जाँच मिलाई है, चिंता या अनिश्चितता दर्ज की है।"),modifier=Modifier.weight(1f))}
                        Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text(visit.name,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleLarge)
                            Text("${visit.kind.title} · ${visit.date}",style=MaterialTheme.typography.bodySmall)
                            if(visit.captureItems.isEmpty()) Text(visit.notes.ifBlank {tr("No observations recorded","कोई नोट दर्ज नहीं")})
                            else visit.captureItems.forEach {fact->
                                Text(fact.category,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                                Text(fact.quote)
                            }
                            HorizontalDivider()
                            Text(tr("Next visit: ","अगली विज़िट: ")+visit.nextDate.ifBlank {tr("Not scheduled","तारीख तय नहीं")},fontWeight=FontWeight.Bold)
                            Text(visit.nextReason)
                            if(visit.referral!=ArogyaReferral.NONE) Text("${visit.referral}: ${visit.referralNote}",color=MaterialTheme.colorScheme.error)
                        }}
                        TextButton(onClick={showFullRecord=!showFullRecord}) {Text(tr(if(showFullRecord) "Hide full record" else "See all checks and details",if(showFullRecord) "पूरा रिकॉर्ड छिपाएँ" else "सभी जाँच और विवरण देखें"))}
                        if(showFullRecord) {
                            Text(ArogyaWorkflow.report(visit),style=MaterialTheme.typography.bodyMedium)
                            if(visit.captureOriginal.isNotBlank()) {Text(tr("Original recording before AI review","AI समीक्षा से पहले की मूल रिकॉर्डिंग"),fontWeight=FontWeight.Bold);Text(visit.captureOriginal)}
                        }
                        if(!visit.completed) {
                            Row { Checkbox(checked=visit.reviewed,onCheckedChange={edit(visit.id,false) {row->row.copy(reviewed=it)}},enabled=editable); Text(tr("I reviewed this record, including dates, observations and pending actions.","मैंने रिकॉर्ड देखा — तारीख़, अवलोकन और बाकी काम समेत।"),modifier=Modifier.weight(1f)) }
                            val issues=ArogyaWorkflow.validation(visit)
                            issues.forEach { Text("• $it",color=MaterialTheme.colorScheme.error) }
                            Button(onClick={edit(visit.id,false) {row->row.copy(completed=true)}},enabled=issues.isEmpty() && !locked) { Text(tr("Complete and save visit","विज़िट पूरी करें और सहेजें")) }
                        }
                        if(visit.guideLog.isNotEmpty()) TextButton(onClick={historyOpen=true}) { Text(tr("Guide questions in this visit: ${visit.guideLog.size} — view","इस विज़िट के गाइड सवाल: ${visit.guideLog.size} — देखें")) }
                        if(!visit.completed && visit.notes.isNotBlank() && ai!=null) Button(onClick={startFinish()},enabled=!locked,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) { Text(tr("✅ Finish visit with AI","✅ AI से विज़िट पूरी करें")) }
                        Text(tr("Share this visit","विज़िट साझा करें"),fontWeight=FontWeight.Bold)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(onClick={ if(!ArogyaShare.shareText(context,"Visit — ${visit.name}",ArogyaShare.visitText(visit))) Toast.makeText(context,"No app available to share.",Toast.LENGTH_LONG).show() },enabled=!locked,modifier=Modifier.weight(1f)) { Text(tr("Share as text","टेक्स्ट भेजें")) }
                            OutlinedButton(onClick={ mediaBusy=true; scope.launch { try { if(!ArogyaShare.shareVisitPdf(context,visit)) Toast.makeText(context,"PDF could not be created.",Toast.LENGTH_LONG).show() } finally { mediaBusy=false } } },enabled=!locked,modifier=Modifier.weight(1f)) { Text(tr("Share PDF","PDF भेजें")) }
                        }
                        Text(tr("Includes the visit, observations, referral and every guide question with its sources. You choose who receives it.","विज़िट, नोट, रेफ़रल और हर गाइड सवाल स्रोत सहित। किसे भेजना है आप चुनें।"),style=MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick={exportVisitId=visit.id;exportRevision=visit.updatedAt;pdf.launch("ArogyaDoot-visit-${visit.date}.pdf")},enabled=!locked) { Text(if(visit.completed) "Export visit PDF" else "Export DRAFT PDF") }
                        Text(tr("Export includes the beneficiary details above. Choose a destination you trust. This app does not send it to anyone.","निर्यात में ऊपर का लाभार्थी विवरण होगा। भरोसे की जगह चुनें। यह ऐप किसी को नहीं भेजता।"),style=MaterialTheme.typography.bodySmall)
                        if(visit.completed) Button(onClick={start(ArogyaWorkflow.followUp(visit))},enabled=!locked) { Text(tr("Start follow-up visit","फ़ॉलो-अप विज़िट शुरू करें")) }
                        TextButton(onClick={deleteId=visit.id},enabled=!locked) { Text(tr("Delete this local record","यह स्थानीय रिकॉर्ड हटाएँ")) }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
            Row {
                TextButton(onClick={selectedId=null},enabled=!locked) { Text(tr("Back to my visits","मेरी विज़िट पर वापस")) }
                TextButton(onClick={openAi(ArogyaAiTask.EXPLAIN,"home visit")},enabled=!locked && knowledgeReady) { Text(tr("Ask the guide","गाइड से पूछें")) }
                if(visit.guideLog.isNotEmpty()) TextButton(onClick={historyOpen=true}) { Text(tr("Questions (${visit.guideLog.size})","सवाल (${visit.guideLog.size})")) }
            }
        }
    }
    if(choosePerson) AlertDialog(onDismissRequest={choosePerson=false},title={Text(tr("Start a visit","विज़िट शुरू करें"))},text={
        Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(tr("Choose an existing person, or add someone new.","पुराना व्यक्ति चुनें या नया व्यक्ति जोड़ें।"))
            visits.sortedByDescending {it.updatedAt}.distinctBy {it.personId}.forEach { previous ->
                OutlinedButton(onClick={choosePerson=false;if(!previous.completed) {selectedId=previous.id;stage=2} else start(ArogyaWorkflow.followUp(previous))},modifier=Modifier.fillMaxWidth()) {
                    Text(previous.name.ifBlank {"Unnamed draft"}+if(!previous.completed) " · Resume" else " · New follow-up")
                }
            }
        }
    },confirmButton={Button(onClick={choosePerson=false;start(ArogyaVisit())}) {Text(tr("New person","नया व्यक्ति"))}},dismissButton={TextButton(onClick={choosePerson=false}) {Text(tr("Cancel","रद्द करें"))}})
    source?.let { s -> AlertDialog(onDismissRequest={source=null},title={Text(s.title,style=MaterialTheme.typography.titleMedium)},text={Column(Modifier.heightIn(max=460.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(s.locator.isNotBlank()) Text(s.locator.replaceFirstChar{it.uppercase()},fontWeight=FontWeight.Bold)
        Text(tr("Text read from the guide:","गाइड में लिखा है:"),style=MaterialTheme.typography.labelMedium)
        if(s.excerpt.isBlank()) Text(tr("The passage text was not recorded for this source.","इस स्रोत का पाठ उपलब्ध नहीं है।")) else ArogyaPassageText(s)
        if(s.highlights.isNotEmpty()) Text(tr("Highlighted: the sentences this answer came from.","हाइलाइट: जिन वाक्यों से उत्तर बना।"),style=MaterialTheme.typography.bodySmall)
        if(!s.url.startsWith("http")) Text(tr("No online copy is linked for this document.","इस दस्तावेज़ की ऑनलाइन प्रति उपलब्ध नहीं है।"),style=MaterialTheme.typography.bodySmall)
    }},confirmButton={ if(s.url.startsWith("http")) Button(onClick={runCatching {uriHandler.openUri(s.url)}.onFailure {Toast.makeText(context,"No browser or PDF viewer is available.",Toast.LENGTH_LONG).show()}}) {Text(tr("Open guideline","गाइडलाइन खोलें"))} },
        dismissButton={TextButton(onClick={source=null}) {Text(tr("Close","बंद करें"))}}) }
    ocr?.let { a ->
        var reviewedText by remember(a.id) { mutableStateOf(a.extractedText) }
        AlertDialog(onDismissRequest={ocr=null},title={Text(tr("Review OCR — never auto-filled","OCR देखें — अपने आप नहीं भरेगा"))},text={Column(Modifier.verticalScroll(rememberScrollState())) { ArogyaAttachmentPreview(repo,a,hindi); Text(tr("Compare with the original card. Edit or remove uncertain text before adding it to Notes.","असली कार्ड से मिलाएँ। नोट्स में जोड़ने से पहले संदिग्ध पाठ सुधारें या हटाएँ।")); OutlinedTextField(value=reviewedText,onValueChange={reviewedText=it.take(16000)},modifier=Modifier.fillMaxWidth(),minLines=5) }},
            confirmButton={TextButton(onClick={if(appendNotes(ocrVisitId,"Worker-reviewed card transcription:\n"+reviewedText)) ocr=null},enabled=visit!=null && visit.id==ocrVisitId && !visit.completed && !locked) {Text(tr("Confirm and add to Notes","पक्का करें और नोट्स में जोड़ें"))}},dismissButton={TextButton(onClick={ocr=null}) {Text(tr("Close","बंद करें"))}})
    }
    if(voice) ArogyaDictationDialog(onDismiss={voice=false;autoOrganise=false;guidedSpeak=null},cloudVoice={ samples,langs -> ArogyaCloud.transcribe(context,samples,langs) },cloudFirst=ArogyaCloud.cloudFirst(context),hindi=hindi,onAccept={text ->
        val cb=guidedSpeak
        if(cb!=null) { guidedSpeak=null; voice=false; cb(text) }
        else if(questionDictation) {
            if(text.length<=700) {aiQuestion=text;voice=false}
            else Toast.makeText(context,"Shorten the question to 700 characters; it was not cut or submitted.",Toast.LENGTH_LONG).show()
        } else if(appendNotes(selectedId,text)) {
            voice=false
            if(autoOrganise) {autoOrganise=false;openAi(ArogyaAiTask.ORGANISE_NOTES);autoRunDraft=true}
        }
    })
    val gv=if(aiVisitId==SOP_VISIT_ID) sopVisit else visit
    if(aiOpen && gv!=null && gv.id==aiVisitId) Dialog(onDismissRequest={ if(!aiBusy) aiOpen=false },properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.94f).statusBarsPadding().padding(12.dp),shape=MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp).imePadding(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(when(aiTask) { ArogyaAiTask.EXPLAIN->tr("Ask the ASHA guide","ASHA गाइड से पूछें");ArogyaAiTask.ORGANISE_NOTES->tr("Your AI visit draft","AI का विज़िट ड्राफ़्ट");ArogyaAiTask.HANDOVER->tr("Prepare ANM handover","ANM के लिए विवरण") },style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
                    AiBadge(if(aiTask==ArogyaAiTask.EXPLAIN) "Ask SOP" else "Organise notes")
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                // Warning appears after "Ask", not while typing (it used to push the text out of view).
                val warningNow = if(aiTask==ArogyaAiTask.EXPLAIN && askedQuestionForWarning.isNotBlank()) ArogyaUrgentTriage.assess(askedQuestionForWarning,gv) else null
                if(aiTask==ArogyaAiTask.EXPLAIN) {
                    ArogyaField(tr("Your question","आपका सवाल"),aiQuestion,!aiBusy,2) {aiQuestion=it.take(700)}
                    Row(horizontalArrangement=Arrangement.spacedBy(4.dp),modifier=Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(onClick={questionDictation=true;if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) voice=true else mic.launch(Manifest.permission.RECORD_AUDIO)},enabled=!aiBusy) {Text(tr("🎤 Speak","🎤 बोलें"))}
                        TextButton(onClick={startPhoto("question")},enabled=!aiBusy && !qFileBusy && ai!=null && qFiles.size<3) {Text(tr("📷 Photo","📷 फ़ोटो"))}
                        TextButton(onClick={questionFilePicker.launch(arrayOf("image/*","application/pdf"))},enabled=!aiBusy && !qFileBusy && ai!=null && qFiles.size<3) {Text(tr("📎 Photo / PDF","📎 फ़ोटो / PDF"))}
                        AiBadge("Photo / PDF with a question")
                    }
                    if(qFileBusy) Text(tr("Reading the attachment on this phone…","फ़ोन पर अटैचमेंट पढ़ा जा रहा है…"),style=MaterialTheme.typography.bodySmall)
                    if(qFiles.isNotEmpty()) Row(verticalAlignment=Alignment.CenterVertically) {
                        Text("📎 "+qFiles.joinToString(", "),style=MaterialTheme.typography.bodySmall,modifier=Modifier.weight(1f),maxLines=2)
                        TextButton(onClick={ai?.clearAttachments();qFiles=emptyList()},enabled=!aiBusy) {Text(tr("Remove","हटाएँ"))}
                    }
                    if(qFiles.isNotEmpty()) Text(tr("A photo is added to your question. A PDF is answered from that PDF itself.","फ़ोटो सवाल के साथ गाइड में खोजी जाती है। PDF का उत्तर उसी PDF से आता है।"),style=MaterialTheme.typography.bodySmall)
                    // Danger signs: shown instantly, once, short. Never repeated in the answer.
                    warningNow?.let { warning ->
                        Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.errorContainer),modifier=Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(warning,fontWeight=FontWeight.Medium)
                                if(gv.id!=SOP_VISIT_ID) Button(onClick={escalate(aiQuestion)},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)) { Text(tr("Escalate to ANM","ANM को सूचित करें")) }
                            }
                        }
                    }
                }
                fun runVisitAi(forceOnline:Boolean,afterSafety:Boolean=false) {
                    val snapshot=gv
                    val askedQuestion=aiQuestion.ifBlank { tr("About the attached file","अटैच की गई फ़ाइल के बारे में") }+(if(qFiles.isNotEmpty()) "  [📎 ${qFiles.joinToString(", ")}]" else "")
                    aiOnline=forceOnline
                    aiBusy=true;aiError="";aiDraft="";familyText="";aiStartedAt=System.currentTimeMillis();lastDeltaAt=0L
                    aiJob=scope.launch {
                        try {
                            val result=onRunAi(ArogyaAiRequest(aiTask,snapshot,aiQuestion,hindi,aiTopic,aiPrevious,forceOnline,afterSafetyPrompt=afterSafety,
                                onProgress={ p -> if(p.draft.isNotBlank()) { aiDraft=p.draft; lastDeltaAt=System.currentTimeMillis() } }))
                            qFiles=ai?.attachedNames().orEmpty()
                            aiResult=result;aiCopy=result.text;aiCapture=result.captureItems;showPassages=false
                            // Every guide question is recorded with the gv: question, answer, the guide's sentence and the passages used.
                            if(aiTask==ArogyaAiTask.EXPLAIN && !result.sourceOnly && !result.safetyPrompt) {
                                val entry=ArogyaGuideEntry(System.currentTimeMillis(),askedQuestion,result.text,result.quote,result.quoteSource,result.sources,result.provider,result.elapsedMs,ArogyaUrgentTriage.assess(askedQuestion,snapshot).orEmpty())
                                val current=queue.state.value.visits.firstOrNull {it.id==snapshot.id}
                                if(current!=null && !current.completed) edit(snapshot.id,false) {row->row.copy(guideLog=(row.guideLog+entry).takeLast(30))}
                            }
                            aiPrevious="Previous question: $aiQuestion\nPrevious draft explanation: ${result.text.take(800)}"
                        } catch(e:CancellationException) {aiError="Cancelled or timed out. Any previous answer remains unchanged; no freshness check was accepted.";throw e}
                        catch(e:Exception) {aiError=e.message ?: "AI unavailable. Your visit is unchanged."}
                        finally {aiBusy=false}
                    }
                }
                LaunchedEffect(aiOpen,pending) {
                    if(autoRunDraft && pending==0 && !aiBusy) {autoRunDraft=false;runVisitAi(false)}
                }
                Button(onClick={askedQuestionForWarning=aiQuestion;runVisitAi(false,afterSafety=ArogyaUrgentTriage.assess(aiQuestion,gv)!=null)},enabled=!aiBusy && !qFileBusy && pending==0 && (aiTask!=ArogyaAiTask.EXPLAIN || aiQuestion.isNotBlank() || qFiles.isNotEmpty()),modifier=Modifier.fillMaxWidth()) {
                    Text(if(aiBusy) tr("Working on your phone…","फ़ोन पर काम हो रहा है…") else if(aiTask!=ArogyaAiTask.EXPLAIN) tr("Create my visit draft","मेरा विज़िट ड्राफ़्ट बनाएँ") else tr("Ask the guide","गाइड से पूछें"))
                }
                LaunchedEffect(aiBusy) { while(aiBusy) { nowMs=System.currentTimeMillis(); delay(500) } }
                if(aiBusy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    val secs=((nowMs-aiStartedAt).coerceAtLeast(0))/1000
                    val stage=when {
                        aiOnline -> tr("Checking online…","ऑनलाइन जाँच हो रही है…")
                        aiTask!=ArogyaAiTask.EXPLAIN -> tr("Organising your notes on this phone…","फ़ोन पर नोट्स व्यवस्थित हो रहे हैं…")
                        aiDraft.isNotBlank() && nowMs-lastDeltaAt>2500 -> tr("Step 3 of 3 · Checking every statement against the guide…","चरण 3/3 · हर बात गाइड से मिलाई जा रही है…")
                        aiDraft.isNotBlank() || secs>=20 -> tr("Step 2 of 3 · Writing the answer…","चरण 2/3 · उत्तर लिखा जा रहा है…")
                        else -> tr("Step 1 of 3 · Searching the ASHA guide…","चरण 1/3 · ASHA गाइड में खोज…")
                    }
                    Text("$stage  ${secs}s",fontWeight=FontWeight.Medium)
                    if(aiDraft.isNotBlank()) Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp)) {
                        Text(tr("Draft — checked against the guide before it is shown as the answer","ड्राफ़्ट — उत्तर बनने से पहले गाइड से जाँचा जाएगा"),style=MaterialTheme.typography.labelSmall)
                        Text(aiDraft,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    } }
                    TextButton(onClick={onCancelAi();aiJob?.cancel()}) {Text(tr("Cancel","रद्द करें"))}
                }
                if(aiError.isNotBlank()) Text(aiError,color=MaterialTheme.colorScheme.error)
                aiResult?.let { result ->
                    Text("${result.provider} • ${result.elapsedMs/1000.0}s",style=MaterialTheme.typography.bodySmall)
                    Text(if(result.canCopyToNotes) tr("Review each recorded fact","हर तथ्य की जाँच करें") else tr("Guidance with sources","स्रोत के साथ जानकारी"),fontWeight=FontWeight.Bold)
                    if(result.canCopyToNotes) {
                        aiCapture.forEachIndexed { index,item -> Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(14.dp)) {
                            var categoryMenu by remember(index) {mutableStateOf(false)}
                            Box {
                                TextButton(onClick={categoryMenu=true}) {Text(item.category+" ▾")}
                                DropdownMenu(expanded=categoryMenu,onDismissRequest={categoryMenu=false}) {
                                    (ArogyaCapture.categories+"Unsorted observation").forEach {category-> DropdownMenuItem(text={Text(category)},onClick={
                                        aiCapture=aiCapture.mapIndexed {i,f->if(i==index) f.copy(category=category,selectedByAi=false) else f};aiCopy=ArogyaCapture.render(aiCapture);categoryMenu=false
                                    }) }
                                }
                            }
                            ArogyaField(tr("Recorded words","दर्ज शब्द"),item.quote,!aiBusy,2) { value ->
                                aiCapture=aiCapture.mapIndexed {i,f->if(i==index) f.copy(quote=value.take(6500),selectedByAi=false) else f};aiCopy=ArogyaCapture.render(aiCapture)
                            }
                        }} }
                        Text(tr("Original recording","मूल रिकॉर्डिंग"),fontWeight=FontWeight.Bold)
                        Text(aiOriginalNotes,style=MaterialTheme.typography.bodySmall)
                        Text(tr("Every original sentence is kept. Check the categories and correct any speech recognition errors.","हर मूल वाक्य रखा गया है। श्रेणी और आवाज़ से लिखे शब्दों की गलती सुधारें।"))
                        Button(onClick={
                            val current=queue.state.value.visits.firstOrNull {it.id==aiVisitId}
                            if(current==null || current.completed || current.notes!=aiOriginalNotes) aiError="The visit changed. Close this draft and create a new one; your saved notes were not replaced."
                            else if(aiCopy.isBlank() || aiCapture.any {it.quote.isBlank()} || aiCopy.length>20000) aiError="The draft is empty. Keep your original notes or enter a reviewed record."
                            else if(aiTask==ArogyaAiTask.HANDOVER) {if(appendNotes(aiVisitId,"Reviewed handover:\n"+aiCopy)) aiOpen=false}
                            else if(edit(current.id) {row->ArogyaCapture.applyReviewed(row,aiOriginalNotes,aiCapture)}) {aiOpen=false;stage=1}
                        },enabled=!gv.completed && !locked,modifier=Modifier.fillMaxWidth()) {Text(tr("Confirm reviewed record","जाँचा हुआ रिकॉर्ड सहेजें"))}
                    } else {
                        if(result.quote.isNotBlank()) Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.secondaryContainer)) {
                            Column(Modifier.padding(12.dp)) {
                                Text(tr("The guide says","गाइड में लिखा है"),style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.Bold)
                                Text("“${result.quote}”")
                                if(result.quoteSource.isNotBlank()) Text("— "+result.quoteSource,style=MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text(ArogyaMarkdown.strip(result.text))
                        if(ArogyaFlags.r7SourceLine && aiTask==ArogyaAiTask.EXPLAIN && result.sources.isNotEmpty()) {
                            val s0 = result.sources.first()
                            Text(tr("Source: ","स्रोत: ")+s0.title+(if(s0.locator.isNotBlank()) ", ${s0.locator}" else ""),fontWeight=FontWeight.Medium,style=MaterialTheme.typography.bodySmall)
                        }
                        if(result.r8Blocked) {
                            Button(onClick={
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_TEXT, tr("Please advise: ","कृपया बताएँ: ")+aiQuestion.trim())
                                }
                                runCatching { context.startActivity(android.content.Intent.createChooser(send, tr("Ask ANM","ANM से पूछें"))) }
                            },modifier=Modifier.fillMaxWidth()) { Text(tr("Ask ANM","ANM से पूछें")) }
                        }
                        if(result.sources.isNotEmpty()) {
                            Text(tr("Sources — tap to read","स्रोत — पढ़ने के लिए दबाएँ"),fontWeight=FontWeight.Bold)
                            result.sources.forEach { s -> OutlinedButton(onClick={source=s},modifier=Modifier.fillMaxWidth()) {
                                Text("📄 [${s.id}] ${s.title}"+(if(s.locator.isNotBlank()) " · ${s.locator}" else ""),style=MaterialTheme.typography.bodySmall,maxLines=2)
                            } }
                        }
                        if(result.sources.any {it.excerpt.isNotBlank()}) {
                            TextButton(onClick={showPassages=!showPassages}) { Text(if(showPassages) tr("Hide guide text","गाइड का पाठ छिपाएँ") else tr("Show the guide text used for this answer","इस उत्तर के लिए पढ़ा गया गाइड का पाठ")) }
                            if(showPassages) result.sources.filter {it.excerpt.isNotBlank()}.forEach { s -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                                Text("[${s.id}] ${s.title}"+(if(s.locator.isNotBlank()) " · ${s.locator}" else ""),fontWeight=FontWeight.Bold,style=MaterialTheme.typography.bodySmall)
                                ArogyaPassageText(s)
                                if(s.url.startsWith("http")) TextButton(onClick={runCatching {uriHandler.openUri(s.url)}.onFailure {Toast.makeText(context,"No browser or PDF viewer is available.",Toast.LENGTH_LONG).show()}}) { Text(tr("Open guideline","गाइडलाइन खोलें")) }
                            } } }
                        }
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick={ ArogyaShare.shareText(context,"Guide answer",ArogyaShare.guideEntryText(ArogyaGuideEntry(System.currentTimeMillis(),aiQuestion,result.text,result.quote,result.quoteSource,result.sources,result.provider,result.elapsedMs))) }) { Text(tr("Share answer","उत्तर भेजें")) }
                            if(warningNow!=null) if(gv.id!=SOP_VISIT_ID) Button(onClick={escalate(aiQuestion)},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)) { Text(tr("Escalate to ANM","ANM को सूचित करें")) }
                        }
                        if(ai!=null && !result.sourceOnly) {
                            fun family(h:Boolean) { familyBusy=true; scope.launch { try { familyText=ai.familyExplain(result.text,result.quote,h) } catch(e:CancellationException) { throw e } catch(e:Exception) { aiError=e.message ?: "" } finally { familyBusy=false } } }
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Button(onClick={family(true)},enabled=!familyBusy && !aiBusy) { Text(if(familyBusy) tr("Writing…","लिखा जा रहा है…") else tr("👪 Explain to family (हिंदी)","👪 परिवार को समझाएँ")) }
                                TextButton(onClick={family(false)},enabled=!familyBusy && !aiBusy) { Text(tr("English","English")) }
                                AiBadge("Explain to family")
                            }
                            if(familyText.isNotBlank()) Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)) { Column(Modifier.padding(12.dp)) {
                                Text(tr("For the family","परिवार के लिए"),style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.Bold)
                                Text(familyText,style=MaterialTheme.typography.bodyLarge)
                                TextButton(onClick={speak(familyText)}) { Text(tr("🔊 Read to the family","🔊 परिवार को सुनाएँ")) }
                                Text(tr("AI rewrite of the guide answer in simple words. No new advice or numbers are added — check before reading out.","गाइड के उत्तर का सरल रूप। कोई नई सलाह या संख्या नहीं जोड़ी गई — सुनाने से पहले देख लें।"),style=MaterialTheme.typography.bodySmall)
                            } }
                        }
                        Text((if(result.online) tr("Online","ऑनलाइन") else tr("Offline on this phone","इस फ़ोन पर ऑफ़लाइन"))+" · ${result.elapsedMs/1000}s · "+tr("Not a diagnosis — ask your ANM if unsure.","यह निदान नहीं है — शंका हो तो ANM से पूछें।"),style=MaterialTheme.typography.bodySmall)
                        if(!result.online && !ArogyaOnlinePolicy.localOnly(aiQuestion)) TextButton(onClick={runVisitAi(true,afterSafety=true)},enabled=!aiBusy && pending==0 && aiQuestion.isNotBlank()) {Text(tr("Also check online (sends only the question)","ऑनलाइन भी जाँचें (सिर्फ़ सवाल भेजा जाएगा)"),style=MaterialTheme.typography.bodySmall)}
                    }
                    Row {TextButton(onClick={speak(if(result.canCopyToNotes) aiCopy else result.text)}) {Text(tr("🔊 Read aloud","🔊 सुनें"))};TextButton(onClick={tts.stop()}) {Text(tr("Stop","रोकें"))}}
                }
                TextButton(onClick={aiOpen=false},enabled=!aiBusy) {Text(if(aiVisitId==SOP_VISIT_ID) tr("Close","बंद करें") else tr("Back to visit","विज़िट पर वापस"))}
                }
            }
        }
    }
    if(escalateOpen && visit!=null) {
        var anmName by remember { mutableStateOf(ArogyaShare.anmName(context)) }
        var anmPhone by remember { mutableStateOf(ArogyaShare.anmPhone(context)) }
        var message by remember(visit.id,escalateConcern) { mutableStateOf(ArogyaShare.anmMessage(visit,escalateConcern)) }
        fun record(how:String) {
            ArogyaShare.setAnmName(context,anmName); ArogyaShare.setAnmPhone(context,anmPhone)
            val current=queue.state.value.visits.firstOrNull {it.id==visit.id}
            if(current!=null && !current.completed) {
                val stamp=java.time.LocalTime.now().withNano(0).withSecond(0).toString()
                val note="$stamp — ANM ${anmName.ifBlank {""}} contacted by $how".replace("  "," ")
                if(edit(visit.id) {row->row.copy(referral=if(row.referral==ArogyaReferral.NONE || row.referral==ArogyaReferral.NEEDED) ArogyaReferral.CONTACTED else row.referral,
                        referralNote=ArogyaWorkflow.appendNote(row.referralNote,note,5000) ?: row.referralNote,referralResolutionConfirmed=false)})
                    Toast.makeText(context,tr("Recorded in this visit: $how","विज़िट में दर्ज: $how"),Toast.LENGTH_SHORT).show()
            }
        }
        AlertDialog(onDismissRequest={escalateOpen=false},title={Text(tr("Escalate to ANM","ANM को सूचित करें"))},text={
            Column(Modifier.heightIn(max=520.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(anmName,{anmName=it.take(60)},label={Text(tr("ANM name","ANM का नाम"))},singleLine=true,modifier=Modifier.weight(1f))
                    OutlinedTextField(anmPhone,{anmPhone=it.filter {c->c.isDigit() || c=='+'}.take(15)},label={Text(tr("ANM phone","ANM फ़ोन"))},singleLine=true,modifier=Modifier.weight(1f),
                        keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Phone))
                }
                val hasPhone=anmPhone.count {it.isDigit()}>=10
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button(onClick={record("phone call");ArogyaShare.dial(context,anmPhone)},enabled=hasPhone,modifier=Modifier.weight(1f)) { Text(tr("📞 Call ANM","📞 ANM को कॉल")) }
                    Button(onClick={record("108 call");ArogyaShare.dial(context,"108")},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error),modifier=Modifier.weight(1f)) { Text(tr("📞 Call 108","📞 108 कॉल")) }
                }
                Text(tr("Message (you can edit it)","संदेश (बदल सकते हैं)"),style=MaterialTheme.typography.labelMedium)
                OutlinedTextField(message,{message=it.take(1500)},modifier=Modifier.fillMaxWidth(),minLines=5)
                var anmBusy by remember { mutableStateOf(false) }
                if(ai!=null) TextButton(onClick={ anmBusy=true; scope.launch {
                    try { message=ai.anmMessage(ArogyaShare.anmMessage(visit,escalateConcern)+"\nVisit notes: "+visit.notes.take(1500)+(if(visit.referralNote.isNotBlank()) "\nActions: "+visit.referralNote.take(500) else "")) }
                    catch(e:CancellationException) { throw e } catch(e:Exception) { toast(e.message ?: "Could not draft the message.") }
                    finally { anmBusy=false } } },enabled=!anmBusy) { Text(if(anmBusy) tr("Writing…","लिखा जा रहा है…") else tr("✨ Write it from my notes (AI)","✨ मेरे नोट्स से लिखें (AI)")) }
                if(ai!=null) AiBadge("Message to ANM")
                if(anmBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={record("WhatsApp");ArogyaShare.whatsApp(context,anmPhone,message)},modifier=Modifier.weight(1f)) { Text(tr("WhatsApp","WhatsApp")) }
                    OutlinedButton(onClick={record("SMS");ArogyaShare.sms(context,anmPhone,message)},enabled=hasPhone,modifier=Modifier.weight(1f)) { Text(tr("SMS","SMS")) }
                }
                if(!hasPhone) Text(tr("Add the ANM's number once; it is remembered on this phone.","ANM का नंबर एक बार डालें; फ़ोन पर याद रहेगा।"),style=MaterialTheme.typography.bodySmall)
                Text(tr("Nothing is sent until you press Send in the call / WhatsApp / SMS app. Each contact is noted in the visit's referral record.","आप भेजें दबाएँगी तभी जाएगा। हर संपर्क विज़िट के रेफ़रल में दर्ज होता है।"),style=MaterialTheme.typography.bodySmall)
                Text(tr("Referral status: ","रेफ़रल स्थिति: ")+(queue.state.value.visits.firstOrNull {it.id==visit.id}?.referral ?: visit.referral).name,fontWeight=FontWeight.Bold)
            }
        },confirmButton={TextButton(onClick={escalateOpen=false;stage=3;if(aiOpen && !aiBusy) aiOpen=false}) {Text(tr("Record outcome","परिणाम दर्ज करें"))}},
        dismissButton={TextButton(onClick={escalateOpen=false}) {Text(tr("Close","बंद करें"))}})
    }
    if(historyOpen && visit!=null) AlertDialog(onDismissRequest={historyOpen=false;historyEntry=null},title={Text(if(historyEntry==null) tr("Guide questions — this visit","गाइड सवाल — यह विज़िट") else historyEntry!!.question)},text={
        Column(Modifier.heightIn(max=520.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            val e=historyEntry
            if(e==null) {
                if(visit.guideLog.isEmpty()) Text(tr("No questions asked yet.","अभी कोई सवाल नहीं पूछा।"))
                visit.guideLog.asReversed().forEach { row -> OutlinedButton(onClick={historyEntry=row},modifier=Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(row.question,fontWeight=FontWeight.Bold,maxLines=2)
                        Text(java.text.SimpleDateFormat("dd MMM, hh:mm a",Locale.getDefault()).format(java.util.Date(row.at))+" · ${row.sources.size} "+tr("sources","स्रोत"),style=MaterialTheme.typography.bodySmall)
                    }
                } }
            } else {
                if(e.warning.isNotBlank()) Text(e.warning,color=MaterialTheme.colorScheme.error)
                if(e.quote.isNotBlank()) Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.secondaryContainer)) { Column(Modifier.padding(12.dp)) {
                    Text(tr("The guide says","गाइड में लिखा है"),style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.Bold); Text("“${e.quote}”")
                    if(e.quoteSource.isNotBlank()) Text("— "+e.quoteSource,style=MaterialTheme.typography.bodySmall) } }
                Text(e.answer)
                e.sources.forEach { s -> OutlinedButton(onClick={source=s},modifier=Modifier.fillMaxWidth()) { Text("📄 [${s.id}] ${s.title}"+(if(s.locator.isNotBlank()) " · ${s.locator}" else ""),style=MaterialTheme.typography.bodySmall,maxLines=2) } }
                Text("${e.provider} · ${e.elapsedMs/1000}s",style=MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={ArogyaShare.shareText(context,"Guide answer",ArogyaShare.guideEntryText(e))}) { Text(tr("Share","भेजें")) }
                    TextButton(onClick={historyEntry=null}) { Text(tr("All questions","सभी सवाल")) }
                }
            }
        }
    },confirmButton={TextButton(onClick={historyOpen=false;historyEntry=null}) {Text(tr("Close","बंद करें"))}},
    dismissButton={ if(visit.guideLog.isNotEmpty()) TextButton(onClick={ArogyaShare.shareText(context,"Guide questions — ${visit.name}",visit.guideLog.mapIndexed {i,e->ArogyaShare.guideEntryText(e,i+1)}.joinToString("\n\n"))}) {Text(tr("Share all","सभी भेजें"))} })
    cardReading?.let { r -> AlertDialog(onDismissRequest={cardReading=null},title={Text(tr("Details read from the card","कार्ड से पढ़ी गई जानकारी"))},text={
        Column(Modifier.heightIn(max=520.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            if(r.fields.isEmpty()) Text(tr("No details could be confirmed from this card. The photo is saved with the visit — type the details yourself.","कार्ड से कोई जानकारी पक्की नहीं हो सकी। फ़ोटो विज़िट में सहेजी गई है — जानकारी स्वयं लिखें।"))
            else Text(tr("Untick anything that does not match the card.","जो कार्ड से मेल न खाए उसे हटा दें।"),style=MaterialTheme.typography.bodySmall)
            r.fields.forEachIndexed { i,f -> Row(verticalAlignment=Alignment.Top) {
                Checkbox(checked=i in cardChecked,onCheckedChange={c->cardChecked=if(c) cardChecked+i else cardChecked-i})
                Column(Modifier.weight(1f)) {
                    Text("${f.label}: ${f.value}",fontWeight=FontWeight.Bold)
                    Text(tr("On card: ","कार्ड पर: ")+f.evidence.take(120),style=MaterialTheme.typography.bodySmall)
                }
            } }
        }
    },confirmButton={ if(r.fields.isNotEmpty()) Button(onClick={
        val chosen=r.fields.filterIndexed { i,_ -> i in cardChecked }
        val vid=cardVisitId
        if(chosen.isNotEmpty() && vid!=null && edit(vid) { row ->
                var next=row
                chosen.firstOrNull {it.key=="birth_date"}?.let { next=next.copy(birthDate=it.value) }
                chosen.firstOrNull {it.key=="child_name"}?.let { if(next.name.isBlank()) next=next.copy(name=it.value) }
                next.copy(notes=ArogyaWorkflow.appendNote(next.notes,ArogyaAiExtras.cardNote(chosen)) ?: next.notes)
            }) { toast(tr("Added ${chosen.size} details to the visit","${chosen.size} जानकारी विज़िट में जोड़ी गई")); cardReading=null }
    },enabled=cardChecked.isNotEmpty()) { Text(tr("Add ticked details","चुनी जानकारी जोड़ें")) } },
    dismissButton={TextButton(onClick={cardReading=null}) {Text(tr("Close","बंद करें"))}}) }
    if(quizOpen) AlertDialog(onDismissRequest={quizOpen=false},title={Row(verticalAlignment=Alignment.CenterVertically) { Text(tr("2-minute refresher","2 मिनट अभ्यास"),modifier=Modifier.weight(1f)); AiBadge("Daily practice") }},text={
        Column(Modifier.heightIn(max=540.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            if(quizBusy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(tr("Making 3 questions from your ASHA guide on this phone…","आपकी ASHA गाइड से फ़ोन पर 3 सवाल बन रहे हैं…")) }
            if(quizError.isNotBlank()) Text(quizError,color=MaterialTheme.colorScheme.error)
            quizItems.forEachIndexed { qi,item ->
                Text("${qi+1}. ${item.question}",fontWeight=FontWeight.Bold)
                val chosen=quizChoice[qi]
                item.options.forEachIndexed { oi,opt ->
                    val mark=when { chosen==null -> ""; oi==item.correct -> "✓ "; oi==chosen -> "✗ "; else -> "" }
                    OutlinedButton(onClick={ if(chosen==null) quizChoice=quizChoice+(qi to oi) },modifier=Modifier.fillMaxWidth(),
                        colors=if(chosen!=null && oi==item.correct) ButtonDefaults.outlinedButtonColors(containerColor=MaterialTheme.colorScheme.primaryContainer) else ButtonDefaults.outlinedButtonColors()) {
                        Text(mark+"ABC"[oi]+") "+opt,modifier=Modifier.fillMaxWidth())
                    }
                }
                if(chosen!=null) TextButton(onClick={source=item.source}) { Text(tr("From the guide: ","गाइड से: ")+item.source.title.take(50)+" · "+item.source.locator,style=MaterialTheme.typography.bodySmall) }
            }
            if(quizItems.isNotEmpty() && quizChoice.size==quizItems.size)
                Text(tr("Score: ","स्कोर: ")+"${quizChoice.count { (qi,oi) -> quizItems[qi].correct==oi }} / ${quizItems.size}",fontWeight=FontWeight.Bold)
            Text(tr("Questions are written by AI from guide passages; the right answer is checked to be in the passage.","सवाल AI ने गाइड से बनाए हैं; सही उत्तर गाइड में होने की जाँच की गई है।"),style=MaterialTheme.typography.bodySmall)
        }
    },confirmButton={TextButton(onClick={quizOpen=false}) {Text(tr("Close","बंद करें"))}},
    dismissButton={TextButton(onClick={loadQuiz()},enabled=!quizBusy) {Text(tr("New questions","नए सवाल"))}})
    if(demoOpen) ArogyaDemoTour(ai,queue,visits,hindi,speak={ t -> speak(t) },onLoadSample={ loadSample() },onClose={ demoOpen=false; tts.stop() },
        onDeepLink={ target -> demoOpen=false; tts.stop(); if(target.startsWith("guided:")) guidedFor=reg.beneficiaries.firstOrNull { it.id==target.removePrefix("guided:") } else hubOpen=target })
    if(profileOpen) {
        var n by remember { mutableStateOf(ashaName) }; var v by remember { mutableStateOf(ashaVillage) }
        AlertDialog(onDismissRequest={profileOpen=false},title={Text(tr("Your details","आपकी जानकारी"))},text={ Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(n,{n=it.take(40)},label={Text(tr("Your name","आपका नाम"))},singleLine=true)
            OutlinedTextField(v,{v=it.take(40)},label={Text(tr("Village / area","गाँव / क्षेत्र"))},singleLine=true)
            Text(tr("Kept only on this phone.","केवल इस फ़ोन पर रहता है।"),style=MaterialTheme.typography.bodySmall)
        } },confirmButton={TextButton(onClick={ ashaName=n.trim(); ashaVillage=v.trim(); profilePrefs.edit().putString("name",ashaName).putString("village",ashaVillage).apply(); profileOpen=false }) {Text(tr("Save","सहेजें"))}},
            dismissButton={TextButton(onClick={profileOpen=false}) {Text(tr("Cancel","रद्द करें"))}})
    }
    hubOpen?.let { tab -> ArogyaRegistryHub(hindi,tab,visits,queue,onClose={hubOpen=null},ai=ai) { b -> hubOpen=null; guidedFor=b } }
    if(pickerOpen) ArogyaPersonPicker(hindi,reg.beneficiaries,dueNow,onPick={ b -> pickerOpen=false; guidedFor=b },onAdd={ pickerOpen=false; hubOpen="patients" },onDismiss={ pickerOpen=false })
    guidedFor?.let { b -> ArogyaGuidedVisit(b,hindi,visits,queue,ai,onClose={ guidedFor=null },
        onAskSop={ q -> if(knowledgeReady) { openAi(ArogyaAiTask.EXPLAIN,"",sop=true); aiQuestion=q } else Toast.makeText(context,"Install the ASHA guide first.",Toast.LENGTH_LONG).show() },
        onSpeak={ cb -> guidedSpeak=cb; questionDictation=false; if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) voice=true else mic.launch(Manifest.permission.RECORD_AUDIO) },
        speak={ t -> speak(t) },
        ) }
    if(visitsOpen) {
        var showDone by remember { mutableStateOf(false) }
        val list=(if(showDone) visits.filter {it.completed}.sortedByDescending {it.date} else visits.filter {!it.completed}.sortedByDescending {it.updatedAt})
        AlertDialog(onDismissRequest={visitsOpen=false},title={Text(tr("Visits","विज़िट"))},text={
            Column(Modifier.heightIn(max=520.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected=!showDone,onClick={showDone=false},label={Text(tr("Unfinished (${visits.count {!it.completed}})","अधूरी (${visits.count {!it.completed}})"))})
                    FilterChip(selected=showDone,onClick={showDone=true},label={Text(tr("Completed (${visits.count {it.completed}})","पूरी (${visits.count {it.completed}})"))})
                }
                if(list.isEmpty()) Text(tr("Nothing here.","यहाँ कुछ नहीं।"))
                list.take(100).forEach { row -> Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(10.dp),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(row.name.ifBlank { tr("Unnamed","बिना नाम") },fontWeight=FontWeight.Bold)
                        Text("${row.date} · ${row.kind.title}"+(if(row.referral!=ArogyaReferral.NONE) " · referral ${row.referral.name.lowercase()}" else ""),style=MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick={visitsOpen=false;selectedId=row.id;stage=if(row.completed) 4 else 2;stepIndex=0},enabled=!locked) { Text(if(row.completed) tr("View","देखें") else tr("Continue","जारी")) }
                } } }
            }
        },confirmButton={TextButton(onClick={visitsOpen=false}) {Text(tr("Close","बंद करें"))}})
    }
    if(aiPageOpen) ArogyaAiPage(hindi,
        modelStatus=ai?.modelStatus() ?: tr("AI not available","AI नहीं है"),
        onlineStatus=ArogyaCloud.status(context),
        onDismiss={aiPageOpen=false},ai=ai)
    val fvVisit=visits.firstOrNull {it.id==fvVisitId}
    if(fvOpen && fvVisit!=null) Dialog(onDismissRequest={ if(!fvBusy) fvOpen=false },properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.95f).padding(12.dp),shape=MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(tr("Finish visit","विज़िट पूरी करें")+" · ${fvVisit.name}",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
                    AiBadge("Finish visit")
                }
                Text(listOf(tr("1 Check","1 जाँचें"),tr("2 Plan","2 योजना"),tr("3 Saved","3 सहेजा")).mapIndexed { i,t -> if(i+1==fvStep) "● $t" else "○ $t" }.joinToString("   "),style=MaterialTheme.typography.labelLarge)
                ArogyaUrgentTriage.assess(fvVisit.notes,fvVisit)?.let { w -> Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.errorContainer),modifier=Modifier.fillMaxWidth()) { Text(w,Modifier.padding(12.dp),fontWeight=FontWeight.Medium) } }
                if(fvError.isNotBlank()) Text(fvError,color=MaterialTheme.colorScheme.error)
                when(fvStep) {
                    1 -> {
                        Text(tr("AI read your notes. Tick what is correct, fix any value.","AI ने आपके नोट्स पढ़े। सही पर ✓ करें, गलत को ठीक करें।"),fontWeight=FontWeight.Bold)
                        if(fvBusy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(tr("Reading your notes…","नोट्स पढ़े जा रहे हैं…")) }
                        val ex=fvExtraction
                        if(ex!=null) {
                            Text("${ex.modelLabel} · ${ex.ms/1000.0}s"+(if(ex.metaModel) "" else tr("  (not a Muse model)","  (Muse मॉडल नहीं)")),style=MaterialTheme.typography.bodySmall)
                            if(ex.note.isNotBlank()) Text(ex.note,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
                            if(ex.fields.isEmpty()) Text(tr("Nothing could be confirmed from the notes. Add measurements (temperature, weight) and what you saw, then try again.","नोट्स से कुछ पक्का नहीं हुआ। तापमान, वज़न और जो देखा वह लिखें, फिर कोशिश करें।"))
                            ex.fields.forEachIndexed { i,f -> Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(8.dp),verticalAlignment=Alignment.Top) {
                                Checkbox(checked=i in fvChecked,onCheckedChange={c -> fvChecked=if(c) fvChecked+i else fvChecked-i})
                                Column(Modifier.weight(1f)) {
                                    OutlinedTextField(fvValues.getOrElse(i) {""},{nv -> fvValues=fvValues.toMutableList().also { l -> if(i<l.size) l[i]=nv.take(160) }},label={Text(f.label+(if(f.key=="temperature_c") " (°C)" else if(f.key=="weight_kg") " (kg)" else ""))},singleLine=true,modifier=Modifier.fillMaxWidth())
                                    Text(tr("From your notes: ","आपके नोट्स से: ")+"“${f.evidence.take(140)}”"+(if(f.source=="rule") tr(" · read by fixed rule"," · नियम से पढ़ा") else ""),style=MaterialTheme.typography.bodySmall)
                                }
                            } } }
                        }
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick={modelSettingsOpen=true},enabled=!fvBusy) { Text(tr("AI model…","AI मॉडल…")) }
                            TextButton(onClick={startFinish()},enabled=!fvBusy) { Text(tr("Read again","फिर पढ़ें")) }
                        }
                        Button(onClick={
                            val p=ArogyaFinishVisit.plan(fvVisit,fvVerified())
                            fvPlan=p; fvHandover=p.handoverText; fvDate=p.followUpDate; fvReason=p.followUpReason; fvStep=2
                        },enabled=!fvBusy && fvExtraction!=null,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) { Text(tr("Next: prepare record and handover","आगे: रिकॉर्ड और ANM संदेश")) }
                    }
                    2 -> fvPlan?.let { p ->
                        if(p.urgent) Text(tr("URGENT by the danger-sign rules — referral will be marked as needed.","खतरे के नियम से तुरंत — रेफ़रल ज़रूरी दर्ज होगा।"),color=MaterialTheme.colorScheme.error,fontWeight=FontWeight.Bold)
                        if(p.missing.isNotEmpty()) Text(tr("Not recorded: ","दर्ज नहीं: ")+p.missing.joinToString(", "),style=MaterialTheme.typography.bodySmall)
                        Text(tr("Record","रिकॉर्ड"),fontWeight=FontWeight.Bold)
                        Card(Modifier.fillMaxWidth()) { Text(p.recordText,Modifier.padding(12.dp)) }
                        Text(tr("Message for ANM (edit if needed)","ANM के लिए संदेश (बदल सकते हैं)"),fontWeight=FontWeight.Bold)
                        OutlinedTextField(fvHandover,{fvHandover=it.take(2000)},modifier=Modifier.fillMaxWidth(),minLines=4)
                        Text(tr("Next visit","अगली विज़िट"),fontWeight=FontWeight.Bold)
                        ArogyaDateField(tr("Date","तारीख"),fvDate,true) { fvDate=it }
                        OutlinedTextField(fvReason,{fvReason=it.take(300)},label={Text(tr("Reason","कारण"))},modifier=Modifier.fillMaxWidth())
                        Text(tr("Urgency comes from fixed danger-sign rules and dates from the HBNC schedule — not from the AI. You approve everything.","तात्कालिकता तय नियमों से और तारीख HBNC सूची से — AI से नहीं। मंज़ूरी आप देती हैं।"),style=MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick={fvStep=1}) { Text(tr("Back","वापस")) }
                            Button(onClick={
                                val verified=fvVerified()
                                val ok=edit(fvVisit.id) { row -> ArogyaFinishVisit.applyApproved(row,p,verified,fvHandover,fvDate,fvReason) }
                                if(ok) {
                                    val updated=queue.state.value.visits.firstOrNull {it.id==fvVisit.id}
                                    val issues=updated?.let { ArogyaWorkflow.validation(it) }.orEmpty()
                                    fvIssues=issues
                                    if(issues.isEmpty()) edit(fvVisit.id,false) { row -> row.copy(reviewed=true,completed=true) }
                                    // The verified weight also goes onto the patient's MCP card in the registers.
                                    verified.firstOrNull {it.key=="weight_kg"}?.value?.toDoubleOrNull()?.let { w -> runCatching { registry.update { r -> r.copy(beneficiaries=r.beneficiaries.map { if(it.id==fvVisit.personId) it.copy(mcp=it.mcp.copy(weights=it.mcp.weights+WeightEntry(fvVisit.date,w))) else it }) } } }
                                    android.util.Log.i("ArogyaDemo","AROGYA_FINISH_APPROVED fields=${verified.size} urgent=${p.urgent} completed=${issues.isEmpty()} issues=${issues.size}")
                                    fvStep=3
                                }
                            },modifier=Modifier.weight(1f)) { Text(tr("Approve and save","मंज़ूर करें और सहेजें")) }
                        }
                    }
                    else -> {
                        Text(tr("✓ Saved on this phone (encrypted). Works offline.","✓ इस फ़ोन पर सुरक्षित (एन्क्रिप्टेड)। बिना इंटरनेट।"),fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)
                        if(fvIssues.isEmpty()) Text(tr("Visit completed. Next visit: ","विज़िट पूरी। अगली विज़िट: ")+fvDate)
                        else { Text(tr("Saved as a draft. Still needed before closing:","ड्राफ़्ट में सहेजा। बंद करने से पहले ज़रूरी:"),fontWeight=FontWeight.Bold); fvIssues.forEach { Text("• $it",style=MaterialTheme.typography.bodySmall) } }
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick={ArogyaShare.shareText(context,"Message to ANM",fvHandover)}) { Text(tr("Share handover","संदेश भेजें")) }
                            if(fvPlan?.urgent==true) Button(onClick={fvOpen=false;escalate("")},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)) { Text(tr("Escalate to ANM","ANM को सूचित करें")) }
                        }
                        if(fvIssues.isNotEmpty()) TextButton(onClick={fvOpen=false;stage=1}) { Text(tr("Go to visit checks","विज़िट जाँच पर जाएँ")) }
                    }
                }
                TextButton(onClick={fvOpen=false},enabled=!fvBusy) { Text(tr("Close","बंद करें")) }
            }
        }
    }
    if(modelSettingsOpen) ArogyaCloudSettingsDialog(hindi) { modelSettingsOpen=false }
    voiceMissing?.let { lang -> AlertDialog(onDismissRequest={voiceMissing=null},title={Text(tr("Voice not installed","आवाज़ नहीं है"))},text={Text(tr("Reading aloud uses the Android voice. Install a ${if(lang=="hi") "Hindi" else "English"} voice, then try again.","बोलकर सुनाने के लिए Android आवाज़ चाहिए। ${if(lang=="hi") "हिन्दी" else "English"} आवाज़ इंस्टॉल करें, फिर सुनें।"))},confirmButton={TextButton(onClick={voiceMissing=null;runCatching {context.startActivity(android.content.Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))}}) {Text(tr("Download voice","आवाज़ डाउनलोड करें"))}},dismissButton={TextButton(onClick={voiceMissing=null}) {Text(tr("Later","बाद में"))}}) }
    deleteId?.let { id -> AlertDialog(onDismissRequest={deleteId=null},title={Text(tr("Delete visit?","विज़िट हटाएँ?"))},text={Text(tr("This deletes this local visit and its attachments. Exported copies are not deleted.","यह स्थानीय विज़िट और इसके अटैचमेंट हटाएगा। भेजी गई कॉपी नहीं हटेगी।"))},confirmButton={TextButton(onClick={deleteId=null;queue.delete(id);selectedId=null}) {Text(tr("Delete","हटाएँ"))}},dismissButton={TextButton(onClick={deleteId=null}) {Text(tr("Cancel","रद्द करें"))}}) }
}

@Composable
private fun ArogyaField(label: String,value: String,enabled: Boolean,lines: Int=1,onChange:(String)->Unit) {
    OutlinedTextField(value=value,onValueChange=onChange,label={Text(label)},enabled=enabled,modifier=Modifier.fillMaxWidth(),minLines=lines,singleLine=lines==1)
}

@Composable
internal fun ArogyaDictationDialog(onDismiss:()->Unit,cloudVoice:(suspend (FloatArray,List<String>)->String?)?=null,cloudFirst:Boolean=false,hindi:Boolean=false,onAccept:(String)->Unit) {
    fun tr(en:String,hi:String)=if(hindi) hi else en
    val context=LocalContext.current
    var status by remember {mutableStateOf(tr("Getting ready to listen…","सुनने की तैयारी…"))}
    var text by remember {mutableStateOf("")}; var ready by remember {mutableStateOf(false)}
    var recorder by remember {mutableStateOf<AndroAudioRecorder?>(null)}
    var engine by remember {mutableStateOf("")}
    var voiceChoice by remember {mutableStateOf(ArogyaLang.voiceLang(context))}
    DisposableEffect(Unit) { onDispose {recorder?.stop()} }
    LaunchedEffect(Unit) {
        val recognizer=AndroWhisperRecognizer(context.applicationContext)
        try {
            // Whisper (offline) and Muse Voice Transcribe (online): the Settings order decides which is tried first; the other is the fallback.
            val whisperOk=withContext(Dispatchers.IO) {recognizer.ensureInitialized()}
            if(!whisperOk && cloudVoice==null) {ready=true;status=tr("Speech model unavailable. Type your note instead.","बोलने वाला मॉडल नहीं है। नोट लिखें।");return@LaunchedEffect}
            val session=if(whisperOk) recognizer.newSession(language=ArogyaLang.whisperLang(voiceChoice),maxSeconds=60,streaming=false) else null
            val audio=AndroAudioRecorder();recorder=audio
            if(!audio.start()) {ready=true;status=tr("Microphone unavailable. Type your note instead.","माइक नहीं है। नोट लिखें।");return@LaunchedEffect}
            status=tr("Listening (up to 60 seconds)… Tap Stop when done.","सुन रहे हैं (60 सेकंड तक)… हो जाए तो रोकें दबाएँ।")
            val heard=ArrayList<FloatArray>()
            try { withTimeoutOrNull(60000) { for(chunk in audio.chunks) { session?.append(chunk); if(cloudVoice!=null) heard.add(chunk) } } }
            finally {audio.stop()}
            val choiceAtStop=voiceChoice
            suspend fun cloud():String? {
                if(cloudVoice==null || heard.isEmpty()) return null
                status=tr("Transcribing with Muse Voice Transcribe (Meta, online)…","Muse Voice Transcribe (Meta, ऑनलाइन) से लिख रहे हैं…")
                val all=FloatArray(heard.sumOf {it.size}); var o=0; heard.forEach { c -> c.copyInto(all,o); o+=c.size }
                return cloudVoice(all,ArogyaLang.metaLanguages(choiceAtStop))?.takeIf { it.isNotBlank() }?.also { engine=tr("Muse Voice Transcribe (Meta, online)","Muse Voice Transcribe (Meta, ऑनलाइन)") }
            }
            suspend fun phone():String? {
                if(session==null) return null
                status=tr("Transcribing on this phone…","इसी फ़ोन पर लिख रहे हैं…")
                // An explicit Hindi/English choice re-decodes in that language ("hi" fixes
                // Hindi coming out in Urdu script); Auto keeps the session's own result.
                val t=withContext(Dispatchers.Default) { if(choiceAtStop==ArogyaLang.VOICE_AUTO) session.finish() else session.finishWithLanguage(ArogyaLang.whisperLang(choiceAtStop)) }
                return t.takeIf { it.isNotBlank() }?.also { engine=tr("Whisper (on this phone, offline)","Whisper (इसी फ़ोन पर, ऑफलाइन)") }
            }
            text=(if(cloudFirst) cloud() ?: phone() else phone() ?: cloud()).orEmpty()
            ready=true
            status=if(text.isBlank()) tr("No speech recognised. Type your note instead.","कुछ सुनाई नहीं दिया। नोट लिखें।") else tr("Review and correct the transcript before adding it. ($engine)","जोड़ने से पहले देखकर सुधारें। ($engine)")
        } catch(e:CancellationException) {throw e}
        catch(_:Exception) {ready=true;status=tr("Voice unavailable. You can type and confirm your note below.","आवाज़ नहीं चली। नीचे नोट लिखकर पक्का करें।")}
        finally {recorder?.stop();withContext(NonCancellable+Dispatchers.IO) {recognizer.release()} }
    }
    AlertDialog(onDismissRequest=onDismiss,title={Row(verticalAlignment=Alignment.CenterVertically) { Text(tr("Visit dictation","विज़िट बोलकर लिखें"),modifier=Modifier.weight(1f)); AiBadge("Speak instead of typing") }},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf(ArogyaLang.VOICE_AUTO to tr("Auto (Hindi+English)","ऑटो (हिन्दी+English)"),ArogyaLang.VOICE_HINDI to tr("Hindi","हिन्दी"),ArogyaLang.VOICE_ENGLISH to tr("English","English")).forEach { (v,label) ->
                FilterChip(selected=voiceChoice==v,onClick={voiceChoice=v;ArogyaLang.setVoiceLang(context,v)},label={Text(label,maxLines=1)})
            }
        }
        Text(status);OutlinedTextField(value=text,onValueChange={text=it.take(10000)},minLines=4,modifier=Modifier.fillMaxWidth());TextButton(onClick={recorder?.stop()},enabled=!ready) {Text(tr("Stop recording","रिकॉर्डिंग रोकें"))}}},confirmButton={TextButton(onClick={onAccept(text)},enabled=ready&&text.isNotBlank()) {Text(tr("Confirm transcript","लिखा पक्का करें"))}},dismissButton={TextButton(onClick=onDismiss) {Text(tr("Cancel","रद्द करें"))}})
}

@Composable
private fun ArogyaAttachmentPreview(repo: ArogyaVisitRepository, attachment: ArogyaAttachment, hindi: Boolean = false) {
    fun tr(en:String,hi:String)=if(hindi) hi else en
    val context=LocalContext.current
    var page by remember(attachment.id) {mutableStateOf(0)}
    var count by remember(attachment.id) {mutableStateOf(1)}
    var bitmap by remember(attachment.id) {mutableStateOf<Bitmap?>(null)}
    var failed by remember(attachment.id) {mutableStateOf(false)}
    LaunchedEffect(attachment.id,page) {
        try { val result=ArogyaVisitMedia.preview(context,repo,attachment,page);bitmap=result.first;count=result.second;failed=false }
        catch(e:CancellationException) {throw e}
        catch(_:Exception) {failed=true}
    }
    if(failed) Text(tr("Original preview unavailable. Check the original card manually.","असली झलक नहीं है। असली कार्ड खुद देखें।"))
    bitmap?.let {Image(it.asImageBitmap(),contentDescription=tr("Original visit card or PDF page","असली विज़िट कार्ड या PDF पेज"),modifier=Modifier.fillMaxWidth().heightIn(max=300.dp))}
    if(attachment.mime=="application/pdf") Row {
        TextButton(onClick={page--},enabled=page>0) {Text(tr("Previous page","पिछला पेज"))}
        Text("${page+1} / $count")
        TextButton(onClick={page++},enabled=page+1<count) {Text(tr("Next page","अगला पेज"))}
    }
}

@Composable
private fun ArogyaDateField(label:String,value:String,enabled:Boolean,onChange:(String)->Unit) {
    val context=LocalContext.current
    OutlinedButton(onClick={
        val date=ArogyaWorkflow.parseDate(value) ?: LocalDate.now()
        DatePickerDialog(context,{_,y,m,d->onChange(LocalDate.of(y,m+1,d).toString())},date.year,date.monthValue-1,date.dayOfMonth)
            .apply {datePicker.maxDate=System.currentTimeMillis()}.show()
    },enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) {
        Text(label+": "+value.ifBlank {"—"})
    }
}

/** A guide passage with the sentences the answer came from highlighted. */
@Composable
fun ArogyaPassageText(s: ArogyaSource) {
    val hl = MaterialTheme.colorScheme.tertiaryContainer
    val text = s.excerpt
    val ranges = s.highlights.mapNotNull { h -> val i = text.indexOf(h.trim()); if (h.isBlank() || i < 0) null else i until i + h.trim().length }.sortedBy { it.first }
    Text(buildAnnotatedString {
        var at = 0
        ranges.forEach { r -> if (r.first >= at) { append(text.substring(at, r.first)); withStyle(SpanStyle(background = hl, fontWeight = FontWeight.SemiBold)) { append(text.substring(r.first, r.last + 1)) }; at = r.last + 1 } }
        if (at < text.length) append(text.substring(at))
    }, style = MaterialTheme.typography.bodyMedium)
}

/** Id of the transient visit used when the ASHA asks the SOP from the home screen (never saved). */
const val SOP_VISIT_ID = "ask-sop"
