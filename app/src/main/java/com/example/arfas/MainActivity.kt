package com.example.arfas

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.Frame
import com.google.ar.core.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"
    private val arogyaVisitAi by lazy { ArogyaVisitAi(androRuntime, phase1Features, { groqKeyStore.getKey() }, runtimeGraph.attachmentManager, { runtimeGraph.ensureLocalModelReady() }, applicationContext) }
    private var showArogyaVisits by mutableStateOf(true)
    private var arogyaAssistantPrompt by mutableStateOf("")

    private lateinit var runtimeGraph: FixSightRuntimeGraph
    private lateinit var androRuntime: AndroBaselineRuntime
    private lateinit var phase1Features: Phase1FeatureBundle
    private lateinit var groqKeyStore: GroqKeyStore
    private lateinit var attachmentManager: Phase1AttachmentManager
    private var thinkingWatchdog: Job? = null
    @Volatile private var latestVisionBitmap: Bitmap? = null

    // App state
    private var appState by mutableStateOf(AppState.IDLE)
    // FIX BUG-5: liveCaptionText is now per-turn; cleared at start of each turn
    private var liveCaptionText by mutableStateOf("")
    private var currentObservation by mutableStateOf("Start a session when you are ready.")
    private var showRcaDialog by mutableStateOf(false)
    private var rcaReportResult by mutableStateOf<RCAReport?>(null)
    private var showNotebook by mutableStateOf(false)

    // Tracks active mode
    private var baselineSessionActive by mutableStateOf(false)
    private var isVisionMode   by mutableStateOf(false)
    private var modelLoaded    by mutableStateOf(false)
    private var modelStatusText by mutableStateOf("")
    private var zipStatusText by mutableStateOf("")
    private var groqKeyConfigured by mutableStateOf(false)
    private var groqStatusText by mutableStateOf("No API key configured")
    private var onlineEngine by mutableStateOf(GroqKeyStore.Engine.GROQ)
    private var groqKeySaved by mutableStateOf(false)
    private var metaKeySaved by mutableStateOf(false)
    private var answerPolicy by mutableStateOf(AnswerPolicy.LOCAL_FIRST)
    private var onlineBackup by mutableStateOf(false)
    private var onlineVoice by mutableStateOf(false)
    private var knowledgeModeEnabled by mutableStateOf(false)
    private var knowledgePreselected by mutableStateOf(false)
    private var knowledgePacks by mutableStateOf<List<GenericKnowledgePackRepository.InstalledPack>>(emptyList())
    private var knowledgeImporting by mutableStateOf(false)
    private var knowledgeSwitching by mutableStateOf(false)
    private var knowledgeInstalled by mutableStateOf(false)
    private var knowledgeReady by mutableStateOf(false)
    private var knowledgeStatusText by mutableStateOf("Not installed")
    /** One-tap Guide v1 → v2 update, when the wanted file sits in Downloads. Null = nothing offered. */
    private var guideUpdateOffer by mutableStateOf<KbGuideUpdate?>(null)
    private var pendingAttachmentLabel by mutableStateOf("")
    private var attachmentPreparing by mutableStateOf(false)
    private var runtimeHealthText by mutableStateOf("")
    // Unified assistant UI presentation state. The foreground service remains the owner
    // of microphone/TTS/generation; these fields are observation-only.
    private var voiceReady by mutableStateOf(false)
    private var voiceInitComplete by mutableStateOf(false)
    private var voiceTurnActive by mutableStateOf(false)
    private var voiceListening by mutableStateOf(false)
    private var currentUserTranscript by mutableStateOf("")
    private var currentAssistantAnswer by mutableStateOf("")
    private var troubleshootingMode by mutableStateOf(false)
    private var conversationSessions by mutableStateOf<List<AndroConversationSession>>(emptyList())
    private var pendingOneShotVoiceAfterAudioPermission = false
    private var pendingVisionEnableAfterPermission = false
    private var pendingPhotoCaptureAfterPermission = false
    private var cameraCloudValidationInFlight = false
    private var pendingQuickPhotoUri: Uri? = null

    // Live notebook snapshot for UI — updated after every turn
    private var notebookSnapshot  by mutableStateOf<InvestigationNotebook?>(null)
    private var progressSnapshot  by mutableStateOf(InvestigationProgress())
    private var currentGoalText   by mutableStateOf("")
    private var showIssueResolved by mutableStateOf(false)
    private var showSettingsFromSession by mutableStateOf(false)
    private var selfTestText by mutableStateOf("")
    private var selfTestRunning by mutableStateOf(false)
    private var kbCheckText by mutableStateOf("")
    private var kbCheckRunning by mutableStateOf(false)
    private var selectedARTool    by mutableStateOf(ARTool.BOX)
    private var selectedARColor   by mutableStateOf(ARColor.GREEN)
    // FIXED: TurnSummary replaces Pair<String,String> — tracks provider for RCA attribution
    data class TurnSummary(
        val query: String,
        val response: String,
        val provider: AndroBaselineRuntime.Provider,
        val sourceIds: List<String> = emptyList(),
        // Snapshot the KB-selection state at commit time. Do not derive this
        // later from the current toggle, otherwise old replies can be relabelled.
        val knowledgeUsed: Boolean = false
    )
    private var stepHistory by mutableStateOf<List<TurnSummary>>(emptyList())

    // ADDED: session timing for RCA duration
    private var sessionStartMs = 0L
    // ADDED: turn counter for VisualStateStore indexing
    private var currentTurnNumber = 0
    // ADDED: visual state store for change-aware vision reasoning (handoff §26)
    private val visualStateStore = VisualStateStore()

    // Phase 7: AR is a strict on-demand subsystem. Normal Live Camera uses the
    // lightweight Camera2 preview/capture path and does not create an ARCore session.
    private var arGuidanceActive by mutableStateOf(false)

    companion object {
        @Volatile private var crashHandlerInstalled = false
        /** The user's "use the Knowledge Base for the next session" choice, kept across process restarts. */
        private const val UI_PREFS = "fixsight_ui_state"
        private const val PREF_KB_PRESELECTED = "kb_preselected"
    }

    private fun rememberKnowledgePreselection(selected: Boolean) {
        runCatching { getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit().putBoolean(PREF_KB_PRESELECTED, selected).apply() }
    }

    private val attachmentPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && ::attachmentManager.isInitialized) {
            attachmentPreparing = true
            pendingAttachmentLabel = "Preparing attachment…"
            lifecycleScope.launch(Dispatchers.IO) {
                val prepared = attachmentManager.stageUri(uri)
                withContext(Dispatchers.Main) {
                    attachmentPreparing = false
                    if (prepared != null) {
                        pendingAttachmentLabel = attachmentManager.pendingDisplayLabel()
                        AndroVoiceController.setPendingAttachment(pendingAttachmentLabel)
                        Toast.makeText(this@MainActivity, "Attached: ${prepared.displayName}", Toast.LENGTH_SHORT).show()
                    } else {
                        pendingAttachmentLabel = ""
                        AndroVoiceController.setPendingAttachment("")
                        Toast.makeText(this@MainActivity, "That file type could not be prepared.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // Full-resolution camera capture. TakePicturePreview() only returns the camera app's
    // thumbnail (observed as 187x250 on-device), which is too small for reliable VLM use.
    // TakePicture() writes the original camera output into an app-created writable MediaStore URI.
    // The existing stageUri() path remains the sole owner of image decode/downscale/analysis.
    private val quickPhotoLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { captured ->
        val uri = pendingQuickPhotoUri
        pendingQuickPhotoUri = null
        Log.i(TAG, "FULL_RES_PHOTO_RESULT captured=$captured uriPresent=${uri != null}")

        if (!captured || uri == null) {
            uri?.let { deleteQuickPhotoUri(it) }
            attachmentPreparing = false
            if (pendingAttachmentLabel == "Preparing photo…") pendingAttachmentLabel = ""
            return@registerForActivityResult
        }

        if (!::attachmentManager.isInitialized) {
            deleteQuickPhotoUri(uri)
            return@registerForActivityResult
        }

        attachmentPreparing = true
        pendingAttachmentLabel = "Preparing photo…"
        lifecycleScope.launch(Dispatchers.IO) {
            val prepared = try {
                attachmentManager.stageUri(uri)
            } finally {
                // stageUri() already caches the selected source when required, so the
                // temporary MediaStore entry can be removed immediately afterwards.
                deleteQuickPhotoUri(uri)
            }

            withContext(Dispatchers.Main) {
                attachmentPreparing = false
                if (prepared != null) {
                    pendingAttachmentLabel = attachmentManager.pendingDisplayLabel()
                    AndroVoiceController.setPendingAttachment(pendingAttachmentLabel)
                    Toast.makeText(this@MainActivity, "Photo attached to next turn", Toast.LENGTH_SHORT).show()
                } else {
                    pendingAttachmentLabel = ""
                    AndroVoiceController.setPendingAttachment("")
                    Toast.makeText(this@MainActivity, "Photo could not be prepared.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO]
            ?: (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        val cameraGranted = permissions[Manifest.permission.CAMERA]
            ?: (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        val cameraWasRequested = permissions.containsKey(Manifest.permission.CAMERA)

        if (!audioGranted && permissions.containsKey(Manifest.permission.RECORD_AUDIO)) {
            pendingOneShotVoiceAfterAudioPermission = false
            Toast.makeText(this, "Microphone permission is required for voice input.", Toast.LENGTH_LONG).show()
        } else if (audioGranted) {
            Log.i(TAG, "VOICE_PERMISSION_READY owner=foreground_service")
            if (pendingOneShotVoiceAfterAudioPermission) {
                pendingOneShotVoiceAfterAudioPermission = false
                toggleOneShotVoiceInput()
            }
        }

        if (cameraWasRequested) {
            when {
                cameraGranted && pendingVisionEnableAfterPermission -> {
                    pendingVisionEnableAfterPermission = false
                    pendingPhotoCaptureAfterPermission = false
                    if (baselineSessionActive) switchVisionModeInSession(true)
                }
                cameraGranted && pendingPhotoCaptureAfterPermission -> {
                    pendingPhotoCaptureAfterPermission = false
                    pendingVisionEnableAfterPermission = false
                    launchQuickPhotoCapture()
                }
                !cameraGranted -> {
                    pendingVisionEnableAfterPermission = false
                    pendingPhotoCaptureAfterPermission = false
                    Toast.makeText(this, "Camera permission was not granted.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("showArogyaVisits", showArogyaVisits)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ArogyaNetWatch.register(applicationContext)
        savedInstanceState?.let { showArogyaVisits = it.getBoolean("showArogyaVisits", true) }
        ARController.setContext(this)
        Log.i(TAG, "AR_CAPABILITY offlineReady=${ARController.isArCoreReadyForOfflineUse()}")
        synchronized(MainActivity::class.java) {
            if (!crashHandlerInstalled) {
                val previous = Thread.getDefaultUncaughtExceptionHandler()
                val appContext = applicationContext
                Thread.setDefaultUncaughtExceptionHandler { t, e ->
                    Log.e(TAG, "UNCAUGHT_CRASH thread=${t.name} type=${e.javaClass.simpleName} breadcrumbs=${CrashBreadcrumbs.snapshot()}", e)
                    val isLitertlmThread = t.name.contains("callback_thread") ||
                        e.stackTrace.any { it.className.contains("litertlm") || it.className.contains("NativeLibraryLoader") }
                    val isLitertlmError = e is NoSuchMethodError &&
                        (e.message?.contains("close\$default") == true || isLitertlmThread)
                    if (isLitertlmError || (e is Error && isLitertlmThread)) {
                        Log.w(TAG, "litertlm native error on ${t.name} — quarantining local backend: ${e.message?.take(60)}")
                        runCatching {
                            val runtime = FixSightRuntimeGraph.get(appContext).runtime
                            runtime.localModelManager?.let { lm ->
                                lm.permanentlyDisabled = true
                                // Quarantine the broken native session now, but arm one
                                // controlled clean reload for the background readiness
                                // path instead of disabling Local for the entire process.
                                lm.engineNeedsReload = true
                                lm.needsSessionReopen = true
                                lm.requestCancelActiveGeneration("uncaught_litertlm_callback_error")
                            }
                            AndroVoiceController.setError(
                                "The local model backend became unhealthy. Pramaan will avoid reusing that native session.",
                                keepSession = AndroVoiceController.state.value.sessionActive
                            )
                        }
                    } else {
                        previous?.uncaughtException(t, e)
                    }
                }
                crashHandlerInstalled = true
            }
        }

        // AndroLLM-style process graph: Activity observes; foreground service owns voice/turn execution.
        runtimeGraph = FixSightRuntimeGraph.get(this)
        androRuntime = runtimeGraph.runtime
        answerPolicy = androRuntime.answerPolicy()
        onlineBackup = androRuntime.onlineBackup()
        onlineVoice = androRuntime.onlineVoice()
        phase1Features = runtimeGraph.phase1Features
        groqKeyStore = runtimeGraph.groqKeyStore
        attachmentManager = runtimeGraph.attachmentManager

        refreshOnlineKeys()
        groqStatusText = if (groqKeyConfigured) "Not tested" else "No API key configured"

        androRuntime.onVisualObservation = { observation ->
            val turnNum = currentTurnNumber
            lifecycleScope.launch(Dispatchers.IO) { visualStateStore.record(observation, turnNum) }
        }
        androRuntime.onArPlacement = { intent, target ->
            val liveReady = withContext(Dispatchers.Main.immediate) {
                if (!baselineSessionActive || !isVisionMode) {
                    Log.w(TAG, "AI_AR_SKIPPED reason=live_video_inactive target='${target.label}'")
                    false
                } else {
                    arGuidanceActive = false
                    true
                }
            }
            if (!liveReady) {
                ARPlacementResult.NOT_READY
            } else {
                // V19: the dedicated locator already analyzed an ARCore-owned capture.
                // Reuse that exact capture snapshot for placement. V18's current-frame
                // mapping path performs a direct world-ray hit first and permits IMAGE->VIEW
                // mapping only while the capture reference remains spatially stable, so a
                // second expensive VLM re-localization is unnecessary on the normal path.
                // V20: ARCore live guidance attempts a real tracked anchor first. If
                // transient geometry is unavailable, ARController may return a bounded
                // current-frame 2D highlight rather than failing the user-visible action.
                val worldResult = ARController.placeCurrentFrameLocalization(
                    intent = intent,
                    target = target,
                    // A world anchor remains first choice, but a safe current-frame
                    // 2D highlight is preferable to reporting that AR is unavailable
                    // when ARCore geometry is temporarily unstable or unsupported.
                    allow2dFallback = true,
                    timeoutMs = 1_200L
                )
                val result = worldResult

                withContext(Dispatchers.Main.immediate) {
                    arGuidanceActive = result != ARPlacementResult.NOT_READY
                    if (arGuidanceActive) {
                        VisualEngine.startPeriodicCleanup()
                        Log.i(TAG, "AR_GUIDANCE_ACTIVE rev=V20_STABLE_WORLD_AR target='${target.label}' result=$result type=${intent.type}")
                    } else {
                        VisualEngine.stopPeriodicCleanup()
                        Log.w(TAG, "AR_GUIDANCE_NOT_PLACED rev=V20_STABLE_WORLD_AR target='${target.label}' world=$worldResult result=$result")
                    }
                }
                Log.i(
                    TAG,
                    "AI_AR_CALLBACK rev=V20_STABLE_WORLD_AR target='${target.label}' " +
                        "world=$worldResult result=$result conf=${target.confidence} type=${intent.type} maxWaitMs=1200"
                )
                result
            }
        }
        // Phase 21: one AR placement authority only. Legacy fire-and-forget localization
        // is disabled so stale callbacks cannot create a second marker.
        androRuntime.onApiKeyFailed = {
            lifecycleScope.launch(Dispatchers.Main) {
                groqStatusText = "Authentication failed"
                Toast.makeText(
                    this@MainActivity,
                    "The online Meta AI key was rejected. Open Settings → Online Meta AI and enter a valid key.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        // MainActivity never creates a recognizer/TTS/runtime loop. It only supplies
        // UI-owned camera pixels/context to the foreground service when requested.
        AndroVoiceAssistant.setVisualFrameProvider { captureCameraFrame() }
        AndroVoiceAssistant.setVisualContextProvider { visualStateStore.buildPriorContext() }

        val lm = androRuntime.localModelManager
        if (lm != null) {
            lm.onStateChanged = { newState ->
                lifecycleScope.launch(Dispatchers.Main) {
                    when (newState) {
                        LocalModelManager.State.READY -> {
                            modelLoaded = true
                            modelStatusText = if (lm.isTextWarm) {
                                localReadyLabel(lm)
                            } else "Local model loaded — warm-up required"
                        }
                        LocalModelManager.State.ENGINE_READY -> { modelLoaded = false; modelStatusText = "Local engine loaded — warm-up required" }
                        LocalModelManager.State.LOADING -> { modelLoaded = false; modelStatusText = "Loading weights…" }
                        LocalModelManager.State.ERROR -> { modelLoaded = false; modelStatusText = "✗ Load failed: " + lm.lastLoadError.ifBlank { "unknown error" } }
                        LocalModelManager.State.NOT_LOADED -> modelLoaded = false
                        else -> {}
                    }
                }
            }
        } else {
            modelStatusText = "Device RAM insufficient — cloud only"
        }

        // Surface an already-installed model immediately. RuntimeGraph still owns
        // the actual serialized native load; this is presentation/recovery only.
        run {
            val mfm = androRuntime.modelFileManager
            var selected = mfm.getSelectedModelFileName().trim()
            if (selected.isBlank()) selected = mfm.recoverSingleInstalledModel().orEmpty()
            if (selected.isNotBlank()) {
                val installedFile = mfm.getModelFile(selected)
                if (installedFile.exists() && installedFile.length() > 200_000_000L) {
                    modelStatusText = "Installed • ${selected} • loading…"
                }
            }
        }

        runtimeGraph.initialiseAsync { progress ->
            lifecycleScope.launch(Dispatchers.Main) { modelStatusText = progress }
        }
        refreshConversationSessions()

        // A knowledge base is never switched on silently: a new chat after the app starts uses one
        // only when the user turns it on (restoring the last choice put general questions such as
        // "how does a rocket engine work" through the knowledge base without the user knowing).
        rememberKnowledgePreselection(false)

        lifecycleScope.launch {
            AndroVoiceController.state.collectLatest { state ->
                baselineSessionActive = state.sessionActive
                isVisionMode = state.visionMode
                knowledgeModeEnabled = state.knowledgeMode
                troubleshootingMode = state.troubleshootingMode
                voiceReady = state.voiceReady
                voiceInitComplete = state.voiceInitComplete
                voiceTurnActive = state.turnActive
                voiceListening = state.listening
                currentUserTranscript = state.transcript
                currentAssistantAnswer = state.answerText
                // Keep RCA visible after service teardown; otherwise service is authoritative.
                if (!showRcaDialog) appState = state.phase
                if (state.displayText.isNotBlank()) liveCaptionText = state.displayText
                runtimeHealthText = state.runtimeHealth
                pendingAttachmentLabel = state.pendingAttachmentLabel
                // V4 live-assist: camera preview stays continuous. Each committed
                // visual turn performs a short local perception pass and stores the
                // resulting visual state; the text model owns troubleshooting/reasoning.
                if (state.error.isNotBlank()) Log.w(TAG, "VOICE_CONTROLLER_ERROR ${state.error}")
            }
        }

        lifecycleScope.launch {
            AndroVoiceController.interruptedTurnEvents.collectLatest { event ->
                val partial = event.partialAnswer.trim()
                val response = if (partial.isNotBlank()) {
                    partial + "\n\n[Interrupted]"
                } else {
                    "Response interrupted before completion."
                }
                val duplicate = stepHistory.lastOrNull()?.let {
                    it.query.trim() == event.query.trim() && it.response.trim() == response.trim()
                } == true
                if (!duplicate) {
                    val next = stepHistory + TurnSummary(
                        query = event.query,
                        response = response,
                        provider = AndroBaselineRuntime.Provider.NONE,
                        sourceIds = listOf("interrupted:${event.reason}")
                    )
                    stepHistory = if (next.size > 80) next.takeLast(80) else next
                }
            }
        }

        lifecycleScope.launch {
            AndroVoiceController.turnEvents.collectLatest { event ->
                currentTurnNumber++
                currentObservation = event.result.text
                liveCaptionText = event.result.text
                // The turn may already be on screen: the screen was rebuilt (app in the background)
                // and restored it from the saved conversation just before this event arrived.
                val alreadyShown = stepHistory.lastOrNull()?.let {
                    it.query.trim() == event.query.trim() && it.response.trim() == event.result.text.trim()
                } == true
                val base = if (alreadyShown) stepHistory.dropLast(1) else stepHistory
                val next = base + TurnSummary(
                    query = event.query,
                    response = event.result.text,
                    provider = event.result.provider,
                    sourceIds = event.result.sourceIds,
                    knowledgeUsed = knowledgeModeEnabled
                )
                stepHistory = if (next.size > 80) next.takeLast(80) else next
                runtimeHealthText = androRuntime.healthSnapshot().compactLabel()
                if (event.attachmentName != null) pendingAttachmentLabel = ""
                if (event.result.troubleshootingResolutionPrompt) {
                    showIssueResolved = true
                }
                refreshConversationSessions()
            }
        }

        // V3.5: Notebook is authoritative process-scoped state.  Do not make its
        // visibility depend on a transient turnEvents emission.
        lifecycleScope.launch {
            phase1Features.notebookState.collectLatest { snapshot ->
                val hasContent = snapshot.problem.isNotBlank() ||
                    snapshot.confirmedFacts.isNotEmpty() ||
                    snapshot.visualFindings.isNotEmpty() ||
                    snapshot.actionsSuggested.isNotEmpty() ||
                    snapshot.questionsAlreadyAsked.isNotEmpty() ||
                    snapshot.userResponses.isNotEmpty()
                notebookSnapshot = snapshot.takeIf { hasContent }
                if (!hasContent) showNotebook = false
                Log.d(
                    TAG,
                    "NOTEBOOK_UI_STATE rev=V3_5_AUTHORITATIVE_STATE visible=$hasContent " +
                        "facts=${snapshot.confirmedFacts.size} questions=${snapshot.questionsAlreadyAsked.size}"
                )
            }
        }

        if (groqKeyConfigured) testGroqConnection()

        // AR rendering stays idle until an explicit user localization request or
        // manual placement creates a marker. The camera itself remains continuously
        // available in Live Camera mode; no background VLM loop is required for AR.

        // Permissions are capability-driven: microphone only when the user taps voice input;
        // camera only when the user explicitly enables camera or takes a photo.

        setContent {
            MaterialTheme(colorScheme = if(showArogyaVisits) ArogyaDesign.colors else darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (showArogyaVisits) ArogyaVisitScreen(
                        knowledgeName = knowledgePacks.firstOrNull { it.active }?.displayName.orEmpty(),
                        knowledgeReady = knowledgeReady && knowledgePacks.any { it.active && it.displayName.contains("ASHA", ignoreCase = true) },
                        knowledgeBusy = knowledgeImporting || knowledgeSwitching,
                        knowledgeStatus = knowledgeStatusText,
                        onImportKnowledge = { handleKbFilePick(it) },
                        onRefreshKnowledge = { refreshKnowledgeSettingsStatus() },
                        onRunAi = { request ->
                            check(!baselineSessionActive && !voiceTurnActive) { "End the active chat voice session before using visit AI." }
                            arogyaVisitAi.run(request)
                        },
                        onCancelAi = { arogyaVisitAi.cancel() },
                        onAssistant = { prompt ->
                            arogyaAssistantPrompt = prompt
                            showArogyaVisits = false
                            if (prompt.isNotBlank()) {
                                val activePack = knowledgePacks.firstOrNull { it.active }?.displayName.orEmpty()
                                if (activePack.contains("ASHA", ignoreCase = true)) {
                                    if (baselineSessionActive) switchKnowledgeModeInSession(true)
                                    else preselectKnowledgeForNextSession(true)
                                } else {
                                    Toast.makeText(this@MainActivity, "Select your ASHA knowledge pack before asking for guidance.", Toast.LENGTH_LONG).show()
                                    openSettingsWithKnowledgeStatusRefresh()
                                }
                            }
                        },
                        onSettings = { openSettingsWithKnowledgeStatusRefresh() },
                        ai = arogyaVisitAi
                    ) else FixSightUnifiedScreen(
                        initialComposerText = arogyaAssistantPrompt,
                        onVisitDashboard = {
                            AndroVoiceAssistant.stopSession(this@MainActivity)
                            showArogyaVisits = true
                            arogyaAssistantPrompt = ""
                        },
                        activeKnowledgeName = knowledgePacks.firstOrNull { it.active }?.displayName.orEmpty(),
                        appState = appState,
                        sessionActive = baselineSessionActive,
                        voiceReady = voiceReady,
                        voiceInitComplete = voiceInitComplete,
                        listening = voiceListening,
                        turnActive = voiceTurnActive,
                        currentUserTranscript = currentUserTranscript,
                        currentAssistantAnswer = currentAssistantAnswer,
                        isVisionMode = isVisionMode,
                        knowledgeModeEnabled = if (baselineSessionActive) knowledgeModeEnabled else knowledgePreselected,
                        troubleshootingMode = troubleshootingMode,
                        conversations = conversationSessions,
                        showNotebook = showNotebook,
                        notebook = notebookSnapshot,
                        stepHistory = stepHistory,
                        selectedTool = selectedARTool,
                        selectedColor = selectedARColor,
                        pendingAttachmentLabel = pendingAttachmentLabel,
                        attachmentPreparing = attachmentPreparing,
                        arGuidanceActive = arGuidanceActive,
                        onStartSession = { startSession(false, startNewChat = true) },
                        onNewChat = { startFreshNormalChat() },
                        onOpenConversation = { id -> openSavedConversation(id) },
                        onStartTroubleshooting = { startTroubleshootingMode() },
                        onToolSelected = { selectedARTool = it },
                        onColorSelected = { selectedARColor = it },
                        onARTap = { x, y, w, h, label ->
                            ARController.performManualPlacement(
                                x, y, w, h, selectedARTool, selectedARColor, customLabel = label
                            )
                        },
                        onToggleNotebook = {
                            notebookSnapshot = phase1Features.notebookSnapshot()
                            showNotebook = !showNotebook
                        },
                        onSettings = { openSettingsWithKnowledgeStatusRefresh() },
                        onFrame = { session, frame ->
                            // Phase 19: SceneView/ARCore owns Live Video continuously while
                            // video mode is enabled. AI inference is still one captured image
                            // per committed turn; frame updates only maintain local geometry.
                            ARController.updateState(session, frame)
                        },
                        onInterrupt = { interruptSpeechAndListen() },
                        onStopSpeech = { stopSpeechOnly() },
                        onToggleVoiceInput = { toggleOneShotVoiceInput() },
                        onSpeakAnswer = { speakCurrentAnswer() },
                        onSpeakText = { text ->
                            val clean = text.trim()
                            if (clean.isNotBlank()) AndroVoiceAssistant.speakText(this@MainActivity, clean)
                        },
                        onSubmitText = { text -> submitTypedUserTurn(text) },
                        onCheckOnline = { query -> submitForcedOnlineTurn(query) },
                        onAttachFile = { pickPhase1Attachment() },
                        onCapturePhoto = { capturePhase1Photo() },
                        onClearAttachment = { clearPendingAttachment() },
                        onToggleVisionMode = { enabled ->
                            if (enabled && !baselineSessionActive) startSession(true)
                            else switchVisionModeInSession(enabled)
                        },
                        onToggleKnowledgeMode = { enabled ->
                            if (baselineSessionActive) switchKnowledgeModeInSession(enabled)
                            else preselectKnowledgeForNextSession(enabled)
                        },
                        onEndSession = { finishSession() }
                    )

                    // Settings stays an overlay in both Idle and Live states.
                    if (showSettingsFromSession) {
                        SettingsDialog(
                            modelLoaded = modelLoaded,
                            modelStatusText = modelStatusText,
                            modelFileManager = androRuntime.modelFileManager,
                            onModelFilePicked = { uri -> handleModelFilePick(uri) },
                            onMetaModelFilePicked = { type, uri -> handleMetaModelFilePick(type, uri) },
                            onDeleteMetaModel = { type -> handleDeleteMetaModel(type) },
                            onUseMetaStackChanged = { enabled ->
                                androRuntime.modelFileManager.setUseMetaStack(enabled)
                            },
                            onPhoneEngineChanged = { reloadPreferredLocalModel() },
                            onZipFilePicked = { uri -> handleZipFilePick(uri) },
                            onRunSelfTest = { runSelfTest() },
                            selfTestText = selfTestText,
                            selfTestRunning = selfTestRunning,
                            zipStatusText = zipStatusText,
                            onKbFilePicked = { uri -> handleKbFilePick(uri) },
                            kbInstalled = knowledgeInstalled,
                            kbReady = knowledgeReady,
                            kbStatusText = knowledgeStatusText,
                            kbPacks = knowledgePacks,
                            kbSwitching = knowledgeSwitching,
                            onUseKbPack = { id -> switchKnowledgePack(id) },
                            onRemoveKbPack = { id -> confirmRemoveKnowledgePack(id) },
                            kbGuideUpdate = guideUpdateOffer,
                            onInstallGuideUpdate = { installGuideUpdateFromDownloads() },
                            kbCheckText = kbCheckText,
                            kbCheckRunning = kbCheckRunning,
                            onCheckKb = { runKbCheck() },
                            groqKeyConfigured = groqKeyConfigured,
                            groqStatusText = groqStatusText,
                            onSaveGroqKey = { key -> saveGroqApiKey(key) },
                            onClearGroqKey = { clearGroqApiKey() },
                            onTestGroq = { testGroqConnection() },
                            onlineEngine = onlineEngine,
                            groqKeySaved = groqKeySaved,
                            metaKeySaved = metaKeySaved,
                            onOnlineEngineChanged = { engine -> switchOnlineEngine(engine) },
                            answerPolicy = answerPolicy,
                            onAnswerPolicyChanged = { policy ->
                                answerPolicy = policy
                                androRuntime.setAnswerPolicy(policy)
                            },
                            onlineBackup = onlineBackup,
                            onOnlineBackupChanged = { enabled ->
                                onlineBackup = enabled
                                androRuntime.setOnlineBackup(enabled)
                            },
                            onlineVoice = onlineVoice,
                            onOnlineVoiceChanged = { enabled ->
                                onlineVoice = enabled
                                androRuntime.setOnlineVoice(enabled)
                            },
                            onDismiss = { showSettingsFromSession = false }
                        )
                    }

                    // Issue Resolved popup remains product behavior, not part of the UI rewrite.
                    if (showIssueResolved) {
                        IssueResolvedDialog(
                            onContinue = {
                                showIssueResolved = false
                                androRuntime.continueTroubleshootingAfterResolutionPrompt()
                            },
                            onEndSession = {
                                showIssueResolved = false
                                androRuntime.finalizeTroubleshootingResolution()
                                finishSession()
                            }
                        )
                    }

                    // Diagnostics overlay removed from the home screen; status is shown in Settings.

                    if (showRcaDialog && rcaReportResult != null) {
                        RcaReportDialog(
                            report = rcaReportResult!!,
                            onSharePdf = { includeTranscript -> shareSessionReportPdf(rcaReportResult!!, includeTranscript) },
                            onSavePdf = { includeTranscript -> saveSessionReportPdf(rcaReportResult!!, includeTranscript) },
                            onShareSummary = { shareSessionSummaryText(rcaReportResult!!) },
                            onDismiss = { startNew ->
                                showRcaDialog = false
                                rcaReportResult = null
                                notebookSnapshot = null
                                showNotebook = false
                                progressSnapshot = InvestigationProgress()
                                currentGoalText = ""
                                stepHistory = emptyList()
                                currentTurnNumber = 0
                                liveCaptionText = ""
                                currentObservation = "Start a session when you are ready."
                                appState = AppState.IDLE
                                if (startNew) {
                                    // Brief delay so the home screen renders before session starts
                                    lifecycleScope.launch {
                                        delay(300)
                                        startSession(false, startNewChat = true)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // AndroLLM ownership: the foreground service owns microphone/TTS lifetime.
        // Activity backgrounding must not create, stop or restart a second voice stack.
    }

    override fun onResume() {
        super.onResume()
        // UI state is normally restored from the service. Local-model readiness is
        // application-scoped, however, so reconcile the visible model status on every
        // foreground resume. This does not create a second model loader: RuntimeGraph
        // remains the single serialized loading authority.
        reconcileLocalModelReadinessOnResume()
        restoreActiveConversationUiIfNeeded()
    }

    /**
     * Rehydrate committed conversation presentation after Activity recreation/background
     * eviction. The SQLite conversation store is authoritative; Compose state is only a
     * projection. Never overwrite a non-empty live UI, so normal foreground/resume and an
     * in-flight turn remain untouched. Stable persisted messages make this idempotent.
     */
    /**
     * The saved conversation as turns for the chat screen, with each answer's provider badge,
     * sources, Check online and knowledge-base cards (saved with the message since version 3 of
     * the conversation store; older messages come back without them).
     */
    private fun savedTurnsForDisplay(): List<TurnSummary> {
        val messages = runCatching { androRuntime.currentConversationDisplayMessages(300) }.getOrDefault(emptyList())
        val turns = ArrayList<TurnSummary>()
        var index = 0
        while (index < messages.size) {
            val user = messages[index]
            if (user.role != "user") { index++; continue }
            val assistant = messages.getOrNull(index + 1)?.takeIf { it.role == "assistant" }
            if (assistant != null) {
                turns += TurnSummary(
                    query = user.content,
                    response = assistant.content,
                    provider = runCatching { AndroBaselineRuntime.Provider.valueOf(assistant.provider) }
                        .getOrDefault(AndroBaselineRuntime.Provider.NONE),
                    sourceIds = assistant.sourceIds,
                    knowledgeUsed = assistant.knowledgeUsed
                )
                index += 2
            } else index++
        }
        return turns
    }

    private fun restoreActiveConversationUiIfNeeded() {
        if (!::androRuntime.isInitialized || !::phase1Features.isInitialized) return
        // While a turn is running the live screen is authoritative; it is saved when it ends.
        if (AndroVoiceController.state.value.turnActive) return
        val shownBefore = stepHistory.size
        lifecycleScope.launch(Dispatchers.IO) {
            val turns = savedTurnsForDisplay()
            if (turns.isEmpty()) return@launch
            val notebook = runCatching { phase1Features.notebookSnapshot() }.getOrNull()
            withContext(Dispatchers.Main) {
                // Empty screen (rebuilt by Android), or the saved conversation has turns the screen
                // missed while it was in the background: show the saved conversation. Re-checked
                // after the read, in case a live turn changed the screen meanwhile.
                val missedTurns = stepHistory.size == shownBefore && turns.size > stepHistory.size &&
                    stepHistory.size < 80
                if (stepHistory.isEmpty() || missedTurns) {
                    stepHistory = turns.takeLast(80)
                    if (notebook != null && (
                            notebook.problem.isNotBlank() || notebook.confirmedFacts.isNotEmpty() ||
                                notebook.visualFindings.isNotEmpty() || notebook.actionsSuggested.isNotEmpty()
                        )) {
                        notebookSnapshot = notebook
                    }
                    Log.i(TAG, "CONVERSATION_UI_RESTORED turns=${turns.size}")
                }
            }
        }
    }

    /** "✓ Llama-3.2-3B-Instruct-Q4_K_M ready • llama.cpp" — the real loaded model, never a fixed string. */
    private fun localReadyLabel(lm: LocalModelManager): String {
        val name = lm.loadedModelFileName?.substringBeforeLast('.') ?: "Local model"
        val vision = if (lm.isReadyForVision) " • Vision" else ""
        return "✓ $name ready • ${lm.engineName}$vision"
    }

    /** After a model install: load (or switch to) the preferred model without waiting for a restart. */
    private fun reloadPreferredLocalModel() {
        if (!::runtimeGraph.isInitialized) return
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = runtimeGraph.ensureLocalModelReady { progress ->
                lifecycleScope.launch(Dispatchers.Main) { modelStatusText = progress }
            }
            val lm = androRuntime.localModelManager
            withContext(Dispatchers.Main) {
                modelLoaded = ok && lm?.isReady == true
                if (modelLoaded && lm != null) modelStatusText = localReadyLabel(lm)
            }
        }
    }

    private fun reconcileLocalModelReadinessOnResume() {
        if (!::runtimeGraph.isInitialized || !::androRuntime.isInitialized) return
        val lm = androRuntime.localModelManager ?: run {
            modelLoaded = false
            modelStatusText = "Device RAM insufficient — cloud only"
            return
        }

        when (lm.state) {
            LocalModelManager.State.READY -> {
                modelLoaded = lm.isTextWarm
                modelStatusText = if (lm.isTextWarm) {
                    localReadyLabel(lm)
                } else {
                    "Local model loaded — warm-up required"
                }
                return
            }
            LocalModelManager.State.INFERRING -> {
                // A real in-flight generation is not a loading failure.
                modelLoaded = lm.isReady
                return
            }
            LocalModelManager.State.LOADING -> {
                if (runtimeGraph.isLocalModelLoadInProgress) {
                    modelLoaded = false
                    modelStatusText = "Loading weights…"
                    return
                }
                // Impossible under the normal RuntimeGraph contract: manager claims a
                // load is active while the serialized graph says there is none. Recover
                // only this stale UI/native state; never interrupt a genuine load.
                Log.w(TAG, "LOCAL_MODEL_RESUME_STALE_LOADING_RECOVERY")
                lm.unload()
            }
            LocalModelManager.State.ENGINE_READY -> {
                modelLoaded = false
                modelStatusText = "Local engine loaded — warm-up required"
            }
            LocalModelManager.State.ERROR -> {
                modelLoaded = false
                modelStatusText = "Recovering local model…"
            }
            LocalModelManager.State.NOT_LOADED -> {
                modelLoaded = false
            }
        }

        val mfm = androRuntime.modelFileManager
        val installedFile = mfm.preferredTextModelFile()
        if (installedFile == null) {
            modelStatusText = "Not loaded — tap ⚙ and install the Meta models zip"
            return
        }
        if (!installedFile.exists() || installedFile.length() <= 0L) {
            modelStatusText = "Not loaded — model file missing"
            return
        }

        // Fire-and-observe only. RuntimeGraph serializes this with Activity/Service
        // callers, so resume cannot start a second concurrent native initialization.
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = runtimeGraph.ensureLocalModelReady { progress ->
                lifecycleScope.launch(Dispatchers.Main) { modelStatusText = progress }
            }
            withContext(Dispatchers.Main) {
                modelLoaded = ok && lm.isReady && lm.isTextWarm
                if (modelLoaded) {
                    modelStatusText = localReadyLabel(lm)
                } else if (lm.state == LocalModelManager.State.ERROR) {
                    modelStatusText = "✗ Load failed"
                }
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) ArogyaNetWatch.unregister(applicationContext)
        thinkingWatchdog?.cancel()
        AndroVoiceAssistant.setVisualFrameProvider(null)
        AndroVoiceAssistant.setVisualContextProvider(null)
        arGuidanceActive = false
        VisualEngine.stopPeriodicCleanup()
        ARController.clearState()
        // Deliberately do NOT stop/release LiteRT, Whisper, Piper or the foreground
        // service here. Their worker-owned lifecycle is independent of Activity recreation.
        super.onDestroy()
    }

    private fun refreshConversationSessions() {
        if (!::androRuntime.isInitialized) return
        lifecycleScope.launch(Dispatchers.IO) {
            val sessions = runCatching { androRuntime.conversationSessions(5) }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) { conversationSessions = sessions }
        }
    }

    private fun resetConversationUiForSwitch() {
        currentUserTranscript = ""
        currentAssistantAnswer = ""
        liveCaptionText = ""
        stepHistory = emptyList()
        currentTurnNumber = 0
        notebookSnapshot = null
        showNotebook = false
        clearPendingAttachment()
        visualStateStore.clear()
        deactivateArGuidance(clearMarkers = true, reason = "conversation_switch")
    }

    private fun startFreshNormalChat() {
        resetConversationUiForSwitch()
        troubleshootingMode = false
        if (AndroVoiceController.state.value.sessionActive) {
            AndroVoiceAssistant.stopSession(this)
            lifecycleScope.launch {
                delay(300L)
                startSession(false, startNewChat = true)
                delay(250L)
                refreshConversationSessions()
            }
        } else {
            startSession(false, startNewChat = true)
            lifecycleScope.launch { delay(250L); refreshConversationSessions() }
        }
    }

    private fun startTroubleshootingMode() {
        // A troubleshooting session is a new investigation case. Start a fresh
        // conversation/Notebook scope while keeping the foreground service alive;
        // the service performs the conversation switch atomically with no delay race.
        val launchWithVision = isVisionMode &&
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        resetConversationUiForSwitch()
        baselineSessionActive = true
        troubleshootingMode = true
        isVisionMode = launchWithVision
        currentObservation = "Troubleshooting mode active."
        appState = AppState.WAITING

        AndroVoiceAssistant.startTroubleshooting(
            this,
            visionMode = launchWithVision,
            knowledgeMode = knowledgeModeEnabled || knowledgePreselected
        )
        lifecycleScope.launch {
            delay(250L)
            refreshConversationSessions()
            restoreActiveConversationUiIfNeeded()
        }
        Log.i(
            TAG,
            "TROUBLESHOOTING_NEW_CHAT_REQUEST vision=$launchWithVision " +
                "kb=${knowledgeModeEnabled || knowledgePreselected} notebookFresh=true"
        )
    }

    private fun openSavedConversation(conversationId: String) {
        val id = conversationId.trim()
        if (id.isBlank()) return
        resetConversationUiForSwitch()
        troubleshootingMode = false
        AndroVoiceAssistant.openConversation(this, id)
        lifecycleScope.launch(Dispatchers.IO) {
            var opened = false
            var attempts = 0
            while (!opened && attempts < 30) {
                opened = runCatching { androRuntime.currentConversationId() == id }.getOrDefault(false)
                if (!opened) delay(100L)
                attempts++
            }
            if (!opened) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Unable to open that conversation.", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            val turns = savedTurnsForDisplay()
            val sessions = androRuntime.conversationSessions(5)
            withContext(Dispatchers.Main) {
                baselineSessionActive = true
                stepHistory = turns.takeLast(80)
                notebookSnapshot = phase1Features.notebookSnapshot().takeIf { notebook ->
                    notebook.problem.isNotBlank() || notebook.confirmedFacts.isNotEmpty() ||
                        notebook.visualFindings.isNotEmpty() || notebook.actionsSuggested.isNotEmpty()
                }
                conversationSessions = sessions
                appState = AppState.WAITING
            }
        }
    }

    private fun startSession(withVision: Boolean, startNewChat: Boolean = false) {
        val cameraGranted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (withVision && !cameraGranted) {
            Toast.makeText(this, "Camera permission is required for Live Camera.", Toast.LENGTH_LONG).show()
            Log.w(TAG, "SESSION_START_ABORTED requestedVision=true cameraGranted=false")
            return
        }
        isVisionMode = withVision
        knowledgeModeEnabled = false
        phase1Features.setKnowledgeEnabled(false)
        baselineSessionActive = true
        sessionStartMs = System.currentTimeMillis()
        currentTurnNumber = 0
        visualStateStore.clear()
        arGuidanceActive = false
        VisualEngine.stopPeriodicCleanup()
        VisualEngine.clearAll()
        // Do not clear a just-staged first-turn attachment. Explicit New Chat /
        // conversation switching already clears stale pending sources before calling here.
        currentObservation = if (startNewChat) "Pramaan session started." else currentObservation
        liveCaptionText = ""
        if (startNewChat) {
            notebookSnapshot = null
            stepHistory = emptyList()
        }
        runtimeHealthText = androRuntime.healthSnapshot().compactLabel()
        appState = AppState.WAITING

        Log.i(TAG, "SESSION_START requestedVision=$withVision owner=AndroVoiceForegroundService")
        AndroVoiceAssistant.startSession(this, withVision, knowledgePreselected, startNewChat)
    }

    private fun toggleOneShotVoiceInput() {
        if (attachmentPreparing) {
            Toast.makeText(this, "Attachment is still preparing.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!baselineSessionActive) startSession(false)
        if (AndroVoiceController.state.value.listening) {
            AndroVoiceAssistant.stopVoiceInput(this)
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingOneShotVoiceAfterAudioPermission = true
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        AndroVoiceAssistant.startVoiceInput(this)
    }

    private fun speakCurrentAnswer() {
        val text = currentAssistantAnswer.trim()
        if (text.isNotBlank()) AndroVoiceAssistant.speakText(this, text)
    }

    private fun stopSpeechOnly() {
        AndroVoiceAssistant.stopSpeech(this)
    }

    private fun submitTypedUserTurn(rawText: String) {
        val text = rawText.trim()
        if (text.isBlank()) return
        if (attachmentPreparing) {
            Toast.makeText(this, "Attachment is still preparing. Send will be available when it is ready.", Toast.LENGTH_SHORT).show()
            return
        }
        if (baselineSessionActive) {
            Log.i(TAG, "TYPED_TURN_ACCEPTED chars=${text.length} owner=voice_service")
            AndroVoiceAssistant.submitTypedTurn(this, text)
            return
        }

        // ChatGPT-style first-turn UX: the composer is available immediately.
        // Start the normal foreground-service session, then submit the already typed
        // first turn as soon as the controller reports that the session is active.
        startSession(false)
        lifecycleScope.launch {
            repeat(60) {
                if (AndroVoiceController.state.value.sessionActive) {
                    Log.i(TAG, "TYPED_FIRST_TURN_ACCEPTED chars=${text.length} owner=voice_service")
                    AndroVoiceAssistant.submitTypedTurn(this@MainActivity, text)
                    return@launch
                }
                delay(100)
            }
            Toast.makeText(this@MainActivity, "Pramaan could not start the conversation.", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Explicit user-owned online verification. This is a routing control, not a
     * rewritten natural-language query, so Local cannot reclaim the turn.
     */
    private fun submitForcedOnlineTurn(rawText: String) {
        val text = rawText.trim()
        if (text.isBlank()) return
        if (!groqKeyConfigured) {
            // No key: say how to enable it instead of failing with "couldn't reach the cloud".
            Toast.makeText(this, "Online check needs a Meta API key. Add it in Settings → Online Meta AI.", Toast.LENGTH_LONG).show()
            showSettingsFromSession = true
            return
        }
        if (attachmentPreparing) {
            Toast.makeText(this, "Attachment is still preparing. Try Check online again when it is ready.", Toast.LENGTH_SHORT).show()
            return
        }
        if (baselineSessionActive) {
            Log.i(TAG, "CHECK_ONLINE_SUBMIT chars=${text.length} forceExternal=true")
            AndroVoiceAssistant.submitTypedTurn(this, text, forceExternalInformation = true)
            return
        }
        startSession(false)
        lifecycleScope.launch {
            repeat(60) {
                if (AndroVoiceController.state.value.sessionActive) {
                    Log.i(TAG, "CHECK_ONLINE_FIRST_TURN chars=${text.length} forceExternal=true")
                    AndroVoiceAssistant.submitTypedTurn(
                        this@MainActivity, text, forceExternalInformation = true
                    )
                    return@launch
                }
                delay(100)
            }
            Toast.makeText(this@MainActivity, "Pramaan could not start online verification.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pickPhase1Attachment() {
        AndroVoiceAssistant.holdForeground(this)
        // The service already gives an attachment exclusive ownership of its turn
        // while Live Camera remains active, so file selection is safe here. A separate
        // camera-photo capture remains blocked while ARCore owns the camera.
        attachmentPickerLauncher.launch(
            arrayOf(
                "image/*",
                "application/pdf",
                "text/*",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.ms-word.document.macroEnabled.12",
                "application/msword",
                "application/vnd.ms-word",
                "application/vnd.msword",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "application/vnd.ms-powerpoint.presentation.macroenabled.12",
                "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
                "application/vnd.ms-powerpoint",
                "application/mspowerpoint",
                "application/powerpoint",
                "application/json",
                "application/xml"
            )
        )
    }

    private fun capturePhase1Photo() {
        if (isVisionMode) {
            Toast.makeText(this, "Live Camera is already active.", Toast.LENGTH_SHORT).show()
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingPhotoCaptureAfterPermission = true
            pendingVisionEnableAfterPermission = false
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
            return
        }
        launchQuickPhotoCapture()
    }

    private fun launchQuickPhotoCapture() {
        if (pendingQuickPhotoUri != null) return

        val uri = createQuickPhotoUri()
        if (uri == null) {
            Toast.makeText(this, "Unable to create a full-resolution photo target.", Toast.LENGTH_LONG).show()
            return
        }

        pendingQuickPhotoUri = uri
        AndroVoiceAssistant.holdForeground(this)
        try {
            quickPhotoLauncher.launch(uri)
        } catch (t: Throwable) {
            pendingQuickPhotoUri = null
            deleteQuickPhotoUri(uri)
            Log.e(TAG, "FULL_RES_PHOTO_LAUNCH_FAILED ${t.javaClass.simpleName}: ${t.message}", t)
            Toast.makeText(this, "Unable to open the camera.", Toast.LENGTH_LONG).show()
        }
    }

    private fun createQuickPhotoUri(): Uri? {
        return try {
            // Use a normal MediaStore row rather than an IS_PENDING row. Some Samsung
            // Camera builds crash when ACTION_IMAGE_CAPTURE is given a pending output URI.
            // ActivityResultContracts.TakePicture() still grants temporary write access,
            // and we delete this temporary row after stageUri() has cached the source.
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "fixsight_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null

            // Fail before launching an external camera if the target cannot actually be
            // opened for writing on this device/provider implementation.
            val writable = runCatching {
                contentResolver.openFileDescriptor(uri, "rw")?.use { true } ?: false
            }.getOrDefault(false)
            if (!writable) {
                runCatching { contentResolver.delete(uri, null, null) }
                Log.e(TAG, "FULL_RES_PHOTO_URI_NOT_WRITABLE uri=$uri")
                null
            } else {
                Log.i(TAG, "FULL_RES_PHOTO_URI_READY scheme=${uri.scheme} authority=${uri.authority}")
                uri
            }
        } catch (t: Throwable) {
            Log.e(TAG, "FULL_RES_PHOTO_URI_FAILED ${t.javaClass.simpleName}: ${t.message}", t)
            null
        }
    }

    private fun deleteQuickPhotoUri(uri: Uri) {
        try {
            contentResolver.delete(uri, null, null)
        } catch (t: Throwable) {
            Log.w(TAG, "FULL_RES_PHOTO_CLEANUP_FAILED ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun clearPendingAttachment() {
        if (::attachmentManager.isInitialized) attachmentManager.clearPending()
        pendingAttachmentLabel = ""
        AndroVoiceController.setPendingAttachment("")
    }

    private fun switchVisionModeInSession(enable: Boolean) {
        if (!baselineSessionActive) return
        val voiceState = AndroVoiceController.state.value
        if (voiceState.turnActive || appState == AppState.THINKING) {
            Toast.makeText(this, "Finish the current generation before switching mode.", Toast.LENGTH_SHORT).show()
            return
        }
        if (enable) {
            // Phase 1 offline-camera admission:
            // A ready vision-capable local model (for example Gemma 3n VLM) owns
            // Live Camera locally. Do NOT require Groq/network in that case.
            // Cloud validation remains only the fallback admission path when no
            // usable local VLM is currently loaded.
            val localVisionReady = androRuntime.localCameraReady
            if (localVisionReady) {
                Log.i(TAG, "LIVE_CAMERA_ADMISSION route=${if (androRuntime.isMetaStackActive) "META_VISION+LLAMA" else "LOCAL_VLM"} offlineAllowed=true")
            } else {
                val cloudStatus = androRuntime.groqProviderStatus()
                if (!androRuntime.isGroqKeyConfigured() || cloudStatus == GroqProviderStatus.AUTH_FAILED) {
                    openSettingsWithKnowledgeStatusRefresh()
                    Toast.makeText(
                        this,
                        "Live Camera needs either a ready local vision model or a valid Cloud AI key.",
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }
                if (cloudStatus != GroqProviderStatus.READY) {
                    if (cameraCloudValidationInFlight) return
                    cameraCloudValidationInFlight = true
                    Toast.makeText(this, "Checking Cloud AI for Live Camera…", Toast.LENGTH_SHORT).show()
                    lifecycleScope.launch {
                        val ok = try { androRuntime.validateGroqConnection() } catch (_: Throwable) { false }
                        cameraCloudValidationInFlight = false
                        if (!baselineSessionActive) return@launch
                        if (ok) {
                            switchVisionModeInSession(true)
                        } else {
                            val failedStatus = androRuntime.groqProviderStatus()
                            if (failedStatus == GroqProviderStatus.AUTH_FAILED || !androRuntime.isGroqKeyConfigured()) {
                                openSettingsWithKnowledgeStatusRefresh()
                                Toast.makeText(
                                    this@MainActivity,
                                    "The online Meta AI key was rejected. Check it in Settings → Online Meta AI.",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                Toast.makeText(
                                    this@MainActivity,
                                    "No ready local vision model and Cloud AI is not reachable, so Live Camera was not started.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                    return
                }
            }
        }
        if (enable && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingVisionEnableAfterPermission = true
            pendingPhotoCaptureAfterPermission = false
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
            return
        }
        if (isVisionMode == enable) return
        if (!enable && arGuidanceActive) {
            deactivateArGuidance(clearMarkers = true, reason = "vision_mode_off")
        }
        isVisionMode = enable
        AndroVoiceAssistant.setVisionMode(this, enable)
        // V3.5.1: enabling Live Camera only enables the local preview.  It must not
        // start a background cloud-analysis loop.  The next committed user turn
        // captures exactly one fresh frame in AndroVoiceForegroundService.handleTurn().
    }

    private fun preselectKnowledgeForNextSession(enable: Boolean, announce: Boolean = true) {
        if (baselineSessionActive) {
            switchKnowledgeModeInSession(enable)
            return
        }
        if (!enable) {
            knowledgePreselected = false
            rememberKnowledgePreselection(false)
            return
        }
        knowledgeStatusText = "Checking Knowledge Base…"
        lifecycleScope.launch(Dispatchers.IO) {
            val ready = phase1Features.ensureKnowledgeReady()
            val install = phase1Features.inspectKnowledgeInstallation()
            val status = phase1Features.knowledgeStatusText()
            val guideSuffix = kbGuideSuffix()
            withContext(Dispatchers.Main) {
                knowledgeInstalled = install.valid
                knowledgeReady = ready
                knowledgeStatusText = if (install.valid && ready) {
                    "${install.packName.ifBlank { "Knowledge Base" }} • ${install.rowCount} chunks • Semantic search ready$guideSuffix"
                } else if (install.valid) {
                    "${install.packName.ifBlank { "Knowledge Base" }} • ${install.rowCount} chunks • Semantic search unavailable$guideSuffix"
                } else {
                    status
                }
                knowledgePreselected = ready
                rememberKnowledgePreselection(ready)
                if (ready) {
                    if (announce) Toast.makeText(this@MainActivity, "Selected Knowledge Base will be used for the next session.", Toast.LENGTH_SHORT).show()
                } else {
                    openSettingsWithKnowledgeStatusRefresh()
                    Toast.makeText(this@MainActivity, "Knowledge Base is installed but semantic search is not ready.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun switchKnowledgeModeInSession(enable: Boolean) {
        if (!baselineSessionActive) return
        val current = knowledgeModeEnabled
        if (enable == current) return

        // V1 contract: Knowledge mode is selected before session start and is locked
        // for the entire investigation. Never mutate retrieval/routing mid-case.
        android.app.AlertDialog.Builder(this)
            .setTitle("Knowledge mode is locked")
            .setMessage(
                if (current) {
                    "This investigation started with a Knowledge Base enabled. Continue this session, or end it and start a new session with KB off."
                } else {
                    "This investigation started without a Knowledge Base. Continue this session, or end it and start a new session with KB enabled."
                }
            )
            .setNegativeButton("Continue") { dialog, _ -> dialog.dismiss() }
            .setPositiveButton("End & Change Mode") { dialog, _ ->
                dialog.dismiss()
                finishSession()
                lifecycleScope.launch {
                    delay(350L)
                    if (enable) {
                        preselectKnowledgeForNextSession(true)
                    } else {
                        knowledgePreselected = false
                        rememberKnowledgePreselection(false)
                        knowledgeModeEnabled = false
                        Toast.makeText(
                            this@MainActivity,
                            "KB will be off for the next session.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .show()
    }

    private suspend fun captureCameraFrame(): Bitmap? {
        // Live Video has exactly one camera owner: ARCore/SceneView on supported
        // devices, otherwise Camera2 fallback. Both backends capture exactly one
        // fresh image per committed user turn. Audio-only mode touches neither.
        return if (isVisionMode) {
            ARController.captureBitmap()
        } else {
            null
        }
    }

    /**
     * Shut ARCore back down without ending the voice/camera session. When no anchored
     * marker needs to persist, normal Live Camera returns to Camera2 automatically.
     */
    private fun deactivateArGuidance(clearMarkers: Boolean, reason: String) {
        if (clearMarkers) VisualEngine.clearAll()
        VisualEngine.stopPeriodicCleanup()
        ARController.clearState()
        if (arGuidanceActive) {
            arGuidanceActive = false
            Log.i(TAG, "AR_ON_DEMAND_DEACTIVATED reason=$reason")
        }
    }


    /** Which online keys are saved and which engine is chosen (Settings → Online AI). */
    private fun refreshOnlineKeys() {
        onlineEngine = groqKeyStore.engine()
        groqKeySaved = groqKeyStore.groqKey().isNotBlank()
        metaKeySaved = groqKeyStore.metaKey().isNotBlank()
        groqKeyConfigured = groqKeyStore.getKey().isNotBlank()
    }

    private fun switchOnlineEngine(engine: GroqKeyStore.Engine) {
        runtimeGraph.setOnlineEngine(engine)
        refreshOnlineKeys()
        groqStatusText = if (groqKeyConfigured) "Not tested" else "No key for this engine yet — paste one below"
        if (groqKeyConfigured) testGroqConnection()
    }

    private fun saveGroqApiKey(key: String) {
        val clean = key.trim()
        if (clean.isBlank()) return
        runtimeGraph.updateGroqKey(clean)
        refreshOnlineKeys()
        groqStatusText = "Saved — not tested"
        testGroqConnection()
    }

    private fun clearGroqApiKey() {
        runtimeGraph.updateGroqKey("")
        refreshOnlineKeys()
        groqStatusText = "No API key configured"
    }

    private fun testGroqConnection() {
        if (!groqKeyConfigured) {
            groqStatusText = "No API key configured"
            return
        }
        groqStatusText = "Testing…"
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = try { androRuntime.validateGroqConnection() } catch (_: Throwable) { false }
            withContext(Dispatchers.Main) {
                groqStatusText = if (ok) "Connected — ${androRuntime.onlineEngineLabel()}" else when (androRuntime.groqProviderStatus()) {
                    GroqProviderStatus.AUTH_FAILED -> "Authentication failed"
                    GroqProviderStatus.RATE_LIMITED -> "Rate limited"
                    GroqProviderStatus.NO_KEY -> "No API key configured"
                    GroqProviderStatus.NETWORK_ERROR -> "Network/provider error"
                    else -> "Connection test failed"
                }
            }
        }
    }

    // ── File pick handlers for Settings ──────────────────────────────────────

    private fun handleModelFilePick(uri: android.net.Uri) {
        if (AndroVoiceController.state.value.sessionActive || baselineSessionActive) {
            endSessionThen("Replace the phone model?", "Replacing the model starts a new chat; the current chat will end.") { handleModelFilePick(uri) }
            return
        }
        val lm = androRuntime.localModelManager ?: run {
            android.widget.Toast.makeText(this, "Device needs ≥3.5GB RAM for local model.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val mfm = androRuntime.modelFileManager
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // Step 1: Unload any currently loaded model to free GPU/CPU memory.
            if (lm.isReady || lm.state == LocalModelManager.State.LOADING) {
                lm.unload()
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                modelLoaded = false
                modelStatusText = "Copying… 0%"
            }

            // Step 2: Derive filename from the URI display name — no variant hard-coded.
            // copyFromUri() saves the filename to SharedPreferences on success.
            val fileName = resolveDisplayName(uri)
            if (fileName.isBlank() || !fileName.endsWith(".litertlm", ignoreCase = true)) {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    modelStatusText = "✗ Please select a .litertlm file"
                    modelLoaded = false
                }
                return@launch
            }

            var lastPct = -1
            val copied = mfm.copyFromUri(uri, fileName) { pct ->
                if (pct != lastPct) {
                    lastPct = pct
                    lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                        modelStatusText = "Copying… $pct%"
                    }
                }
            }
            if (!copied) {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    modelStatusText = "✗ Copy failed — ensure enough free storage and try again"
                    modelLoaded = false
                }
                return@launch
            }

            // Step 3: Load the verified file — load phase messages update modelStatusText
            withContext(kotlinx.coroutines.Dispatchers.Main) { modelStatusText = "Loading weights…" }
            val modelPath = mfm.getModelFile(fileName).absolutePath
            val ok = lm.loadModel(modelPath) { progress ->
                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    modelStatusText = progress
                }
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                modelLoaded = ok
                modelStatusText = if (ok) {
                    localReadyLabel(lm)
                } else "✗ Load failed — file may be corrupt"
            }
        }
    }

    private fun handleMetaModelFilePick(type: ModelFileManager.ModelType, uri: android.net.Uri) {
        val mfm = androRuntime.modelFileManager
        val fileName = resolveDisplayName(uri)
        if (fileName.isBlank()) {
            Toast.makeText(this, "Could not resolve file name.", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                modelStatusText = "Copying ${type.name}… 0%"
            }
            var lastPct = -1
            val ok = mfm.installFromUri(uri, type, fileName) { pct ->
                if (pct != lastPct) {
                    lastPct = pct
                    lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                        modelStatusText = "Copying ${type.name}… $pct%"
                    }
                }
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (ok) {
                    modelStatusText = "✓ ${type.name} installed"
                    Toast.makeText(this@MainActivity, "${type.name} model installed.", Toast.LENGTH_SHORT).show()
                    androRuntime.metaVision.reset()
                    if (type == ModelFileManager.ModelType.LLAMA3B) reloadPreferredLocalModel()
                } else {
                    modelStatusText = "✗ Install failed for ${type.name}" + mfm.lastInstallError.let { if (it.isBlank()) "" else ": $it" }
                    val hint = when (type) {
                        ModelFileManager.ModelType.LLAMA3B -> mfm.lastInstallError.ifBlank { "Choose a Llama .gguf file." }
                        ModelFileManager.ModelType.GEMMA_E2B, ModelFileManager.ModelType.OCR -> "Check free storage and try again."
                        else -> "Choose an .onnx file (convert PyTorch weights with tools/export_meta_models.py)."
                    }
                    Toast.makeText(this@MainActivity, "Failed to install ${type.name}. $hint", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun handleDeleteMetaModel(type: ModelFileManager.ModelType) {
        val mfm = androRuntime.modelFileManager
        if (mfm.deleteModel(type)) {
            Toast.makeText(this, "${type.name} model removed.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun runSelfTest() {
        if (selfTestRunning) return
        if (baselineSessionActive || AndroVoiceController.state.value.sessionActive) {
            endSessionThen("Run the self-test?", "The self-test needs the models to itself, so the current chat will end.") { runSelfTest() }
            return
        }
        selfTestRunning = true
        selfTestText = "Starting self-test… keep this screen open."
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val progress = StringBuilder()
            val report = runCatching {
                PramaanSelfTest.run(this@MainActivity, runtimeGraph) { line ->
                    progress.appendLine(line)
                    lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) { selfTestText = progress.toString() }
                }
            }.getOrElse { "Self-test stopped: ${it.javaClass.simpleName}: ${it.message}\n\n$progress" }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                selfTestText = report
                selfTestRunning = false
                Toast.makeText(this@MainActivity, "Self-test finished. Tap Copy report and paste it to share.", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** KB validation for the active pack: Settings → Check this knowledge base. */
    private fun runKbCheck() {
        if (kbCheckRunning) return
        if (baselineSessionActive || AndroVoiceController.state.value.sessionActive) {
            endSessionThen("Check the knowledge base?", "The check needs the models to itself, so the current chat will end.") { runKbCheck() }
            return
        }
        kbCheckRunning = true
        kbCheckText = "Checking… keep this screen open."
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val lm = androRuntime.localModelManager?.takeIf { it.isReady }
            val text = runCatching {
                // With Llama loaded, the phone's model writes the test questions (closer to real use);
                // without it, each passage's key sentence is the question.
                phase1Features.genericKnowledgeProvider.healthCheck(
                    samples = 20,
                    questionWriter = { passage ->
                        lm?.generateDetached(
                            "Write one short question that the passage below answers. Reply with the question only.\n\nPassage: $passage",
                            48, "kb_health_question"
                        )
                    }
                ) { step ->
                    lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) { kbCheckText = "Checking passage $step… keep this screen open." }
                }?.let { KbHealthCheck.render(it) } ?: "No generic knowledge base is installed, or it could not be opened."
            }.getOrElse { "Check stopped: ${it.javaClass.simpleName}: ${it.message}" }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                kbCheckText = text
                kbCheckRunning = false
            }
        }
    }

    private fun handleZipFilePick(uri: android.net.Uri) {
        val mfm = androRuntime.modelFileManager
        zipStatusText = "Checking zip... (keep this screen open)"
        // A 3 GB install takes minutes; stop the screen from sleeping (Samsung kills apps in the
        // background) and release the Llama model so its old file is not held open.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        runCatching { androRuntime.localModelManager?.unload() }
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val report = try {
                mfm.installOneZipFromUri(uri) { status, pct ->
                    lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                        zipStatusText = "$status ($pct%)"
                    }
                }
            } catch (t: Throwable) {
                ZipExtractionReport(0, 5, listOf("✗ Install stopped: ${t.javaClass.simpleName}: ${t.message}"))
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val dashboard = buildString {
                    appendLine("Meta models: ${report.successCount}/${report.totalCount} ready")
                    report.logs.forEach { appendLine(it) }
                }.trim()
                zipStatusText = dashboard
                Toast.makeText(
                    this@MainActivity,
                    "Meta models ${report.successCount}/${report.totalCount} ready. ${if (report.successCount < report.totalCount) "See the report for what is missing." else "All Meta models ready!"}",
                    Toast.LENGTH_LONG
                ).show()
                androRuntime.metaVision.reset()
                reloadPreferredLocalModel()
            }
        }
    }

    /**
     * Resolves the display name for a content URI.
     * Falls back to the last path segment if the cursor query fails.
     */
    private fun resolveDisplayName(uri: android.net.Uri): String {
        try {
            contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return cursor.getString(idx) ?: ""
                }
            }
        } catch (_: Exception) {}
        return uri.lastPathSegment?.substringAfterLast('/') ?: ""
    }

    /**
     * Opens Settings immediately, then validates the lazily-loaded Knowledge Base
     * off the UI thread. This keeps app/session startup fast while ensuring an
     * already-installed valid ANM database is shown as Ready in Settings.
     */
    private fun openSettingsWithKnowledgeStatusRefresh() {
        showSettingsFromSession = true
        refreshKnowledgeSettingsStatus()
    }

    /** " • Guide v1/v2" suffix for every Knowledge Base status line (IO thread: reads the DB marker). */
    private fun kbGuideSuffix(): String {
        val v = phase1Features.guideVersion()
        return " • " + ArogyaGuideDb.describe(v) +
            (if (ArogyaGuideDb.needsSwitch(v)) " — replace with ${ArogyaGuideDb.wantedName()}" else "")
    }

    /** Recomputes the one-tap guide update offer (IO: DB marker + Downloads lookup). */
    private suspend fun refreshGuideUpdate() {
        val offer = withContext(Dispatchers.IO) {
            val v = phase1Features.guideVersion()
            if (ArogyaGuideDb.needsSwitch(v) && findDownloadDbUri(ArogyaGuideDb.wantedName()) != null)
                KbGuideUpdate(ArogyaGuideDb.describe(v), ArogyaGuideDb.wantedName()) else null
        }
        withContext(Dispatchers.Main) { guideUpdateOffer = offer }
    }

    private fun findDownloadDbUri(fileName: String): android.net.Uri? = runCatching {
        val files = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        contentResolver.query(files, arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME}=?", arrayOf(fileName), null)?.use { c ->
            if (c.moveToFirst()) android.content.ContentUris.withAppendedId(files, c.getLong(0)) else null
        }
    }.getOrNull()

    /** One tap: install the wanted guide file from Downloads through the normal install path. */
    private fun installGuideUpdateFromDownloads() {
        lifecycleScope.launch(Dispatchers.IO) {
            val uri = findDownloadDbUri(ArogyaGuideDb.wantedName())
            withContext(Dispatchers.Main) {
                if (uri == null) {
                    Toast.makeText(this@MainActivity, "${ArogyaGuideDb.wantedName()} was not found in Downloads.", Toast.LENGTH_LONG).show()
                    lifecycleScope.launch { refreshGuideUpdate() }
                } else handleKbFilePick(uri)
            }
        }
    }

    private fun refreshKnowledgeSettingsStatus() {
        knowledgeStatusText = "Checking installed Knowledge Base…"
        lifecycleScope.launch(Dispatchers.IO) {
            val install = try {
                phase1Features.inspectKnowledgeInstallation()
            } catch (t: Throwable) {
                Log.w(TAG, "FIXSIGHT_KB_INSTALL_STATUS_FAILED ${t.message}", t)
                Phase1FeatureBundle.KnowledgeInstallationStatus()
            }
            val ready = if (install.valid) {
                try { phase1Features.ensureKnowledgeReady() } catch (t: Throwable) {
                    Log.w(TAG, "FIXSIGHT_KB_SETTINGS_REFRESH_FAILED ${t.message}", t)
                    false
                }
            } else false
            val suffix = kbGuideSuffix()
            withContext(Dispatchers.Main) {
                knowledgeInstalled = install.valid
                knowledgeReady = ready
                knowledgeStatusText = when {
                    install.valid && ready -> "${install.packName.ifBlank { "Knowledge Base" }} • ${install.rowCount} chunks • Semantic search ready$suffix"
                    install.valid -> "${install.packName.ifBlank { "Knowledge Base" }} • ${install.rowCount} chunks • Semantic search unavailable$suffix"
                    install.fileExists -> "Knowledge Base file found but validation failed"
                    else -> "Not installed"
                }
                Log.i(TAG, "FIXSIGHT_KB_SETTINGS_REFRESH installed=${install.valid} ready=$ready rows=${install.rowCount}")
            }
            refreshKnowledgePacks()
            refreshGuideUpdate()
        }
    }

    private suspend fun refreshKnowledgePacks() {
        val packs = phase1Features.installedKnowledgePacks()
        withContext(Dispatchers.Main) { knowledgePacks = packs }
    }

    /** Switches the active Knowledge Base to an installed pack (seconds: its search files are kept). */
    private fun switchKnowledgePack(packId: String) {
        if (AndroVoiceController.state.value.sessionActive || baselineSessionActive) {
            val name = knowledgePacks.firstOrNull { it.packId == packId }?.displayName.orEmpty().ifBlank { "this knowledge base" }
            endSessionThen("Switch to $name?", "Switching the knowledge base starts a new chat; the current chat will end.") { switchKnowledgePack(packId) }
            return
        }
        knowledgeSwitching = true
        knowledgeStatusText = "Switching Knowledge Base…"
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = phase1Features.switchKnowledgePack(packId)
            val install = phase1Features.inspectKnowledgeInstallation()
            refreshKnowledgePacks()
            refreshGuideUpdate()
            val suffix = kbGuideSuffix()
            withContext(Dispatchers.Main) {
                knowledgeSwitching = false
                knowledgeInstalled = install.valid
                knowledgeReady = ok
                knowledgeStatusText = if (install.valid) {
                    "${install.packName.ifBlank { "Knowledge Base" }} • ${install.rowCount} chunks • " +
                        (if (ok) "Semantic search ready" else "Semantic search unavailable") + suffix
                } else "Not installed"
                // A KB chosen for the next session now refers to the new pack.
                if (knowledgePreselected && !ok) {
                    knowledgePreselected = false
                    rememberKnowledgePreselection(false)
                }
                Toast.makeText(
                    this@MainActivity,
                    if (ok) "Now using ${install.packName.ifBlank { "the selected Knowledge Base" }}" else "Could not switch; the previous Knowledge Base is still in use.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun confirmRemoveKnowledgePack(packId: String) {
        val name = knowledgePacks.firstOrNull { it.packId == packId }?.displayName.orEmpty().ifBlank { "this knowledge pack" }
        android.app.AlertDialog.Builder(this)
            .setTitle("Remove knowledge pack?")
            .setMessage("$name will be deleted from this phone. You can install it again from its .db file.")
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .setPositiveButton("Remove") { d, _ ->
                d.dismiss()
                lifecycleScope.launch(Dispatchers.IO) {
                    phase1Features.removeKnowledgePack(packId)
                    refreshKnowledgePacks()
                }
            }
            .show()
    }

    private fun handleKbFilePick(uri: android.net.Uri) {
        if (AndroVoiceController.state.value.sessionActive || baselineSessionActive) {
            endSessionThen("Add this knowledge base?", "Adding a knowledge base starts a new chat; the current chat will end.") { handleKbFilePick(uri) }
            return
        }
        // Item 6: never swap the guide under a running answer; the ASHA retries in a moment.
        if (runCatching { arogyaVisitAi.isBusy }.getOrDefault(false)) {
            Toast.makeText(this, "An answer is being written — wait a moment, then retry.", Toast.LENGTH_LONG).show()
            return
        }
        if (knowledgeImporting) return
        knowledgeImporting = true
        knowledgeStatusText = "Preparing health guide…"
        lifecycleScope.launch(Dispatchers.IO) {
          try {
            val outcome = phase1Features.installKnowledgeDatabaseDetailed(uri, resolveDisplayName(uri))
            val install = phase1Features.inspectKnowledgeInstallation()
            val ready = phase1Features.isKnowledgeReady()
            refreshKnowledgePacks()
            refreshGuideUpdate()
            val suffix = kbGuideSuffix()
            withContext(Dispatchers.Main) {
                knowledgeInstalled = install.valid
                knowledgeReady = ready
                knowledgeStatusText = (if (!outcome.ok) "Install failed: ${outcome.reason}. Current guide: " else "") + when {
                    install.valid && ready -> "${install.packName.ifBlank { "Knowledge Base" }} • ${install.rowCount} chunks • Semantic search ready$suffix"
                    install.valid -> "${install.packName.ifBlank { "Knowledge Base" }} • ${install.rowCount} chunks • Semantic search unavailable$suffix"
                    else -> "Not installed"
                }
                Toast.makeText(
                    this@MainActivity,
                    if (outcome.ok && ready) "Health guide ready" else if (outcome.ok) "Guide attached. Set up the search model in Settings to ask questions." else "Could not attach guide: ${outcome.reason}",
                    Toast.LENGTH_LONG
                ).show()
            }
          } catch (e: kotlinx.coroutines.CancellationException) { throw e }
          catch (_: Exception) {
            withContext(Dispatchers.Main) { knowledgeStatusText = "Could not attach guide. Retry the .db file; check the search model in Settings." }
          } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { knowledgeImporting = false }
          }
        }
    }



    /** Explicit user interruption. Acoustic VAD is disabled; a tap stops active
     * generation/presentation without automatically reopening the microphone. */
    private fun interruptSpeechAndListen() {
        if (!baselineSessionActive) return
        val state = AndroVoiceController.state.value
        if (!state.turnActive && !state.speaking && appState != AppState.THINKING) return
        AndroVoiceAssistant.interrupt(this, rearmListening = false)
    }

    /**
     * Actions that need the models to themselves (switching or adding a knowledge base, the
     * self-test, the KB check, replacing the model) used to stop with "End the active session"
     * and no way to do it from there. Now: one confirmation, the chat ends, the action runs.
     */
    private fun endSessionThen(title: String, message: String, action: () -> Unit) {
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .setPositiveButton("Continue") { d, _ ->
                d.dismiss()
                finishSession()
                lifecycleScope.launch {
                    // Wait (at most ~5 s) for the voice/turn service to release the session.
                    var waited = 0
                    while ((AndroVoiceController.state.value.sessionActive || baselineSessionActive) && waited < 5_000) {
                        kotlinx.coroutines.delay(100); waited += 100
                    }
                    if (AndroVoiceController.state.value.sessionActive || baselineSessionActive) {
                        Toast.makeText(this@MainActivity, "Could not end the chat; please try again.", Toast.LENGTH_LONG).show()
                    } else action()
                }
            }
            .show()
    }

    private fun finishSession() {
        thinkingWatchdog?.cancel()

        // Capture authoritative session evidence BEFORE service teardown clears the
        // process-scoped Notebook for the next case.  UI stepHistory is presentation
        // state only and is no longer the RCA source of truth.
        val capturedNotebook = phase1Features.notebookSnapshot()
        val capturedRows = phase1Features.notebookRows()
        val capturedAudit = runtimeGraph.sessionAuditTrail.snapshot()
        val sessionMs = if (sessionStartMs > 0) System.currentTimeMillis() - sessionStartMs else 0L

        // Phase 21 deterministic teardown: block UI/AR callbacks first, remove the
        // Live Video surface (which invalidates the AR generation), then ask the
        // foreground service to cancel/join the active turn and release voice/native chat.
        baselineSessionActive = false
        isVisionMode = false
        deactivateArGuidance(clearMarkers = true, reason = "session_end")
        CrashBreadcrumbs.add("SESSION_TEARDOWN_AR_RELEASED")
        AndroVoiceAssistant.stopSession(this)
        knowledgePreselected = false
        knowledgeModeEnabled = false
        clearPendingAttachment()

        if (capturedRows.isNotEmpty()) {
            lifecycleScope.launch(Dispatchers.IO) {
                val report = buildRcaReport(capturedRows, capturedNotebook, capturedAudit, sessionMs)
                withContext(Dispatchers.Main) {
                    rcaReportResult = report
                    showRcaDialog = true
                    showNotebook = false
                    appState = AppState.INVESTIGATION_COMPLETE
                    Log.i(
                        TAG,
                        "RCA_READY rev=V3_5_AUTHORITATIVE_NOTEBOOK rows=${capturedRows.size} " +
                            "facts=${capturedNotebook.confirmedFacts.size}"
                    )
                }
            }
        } else {
            notebookSnapshot = null
            progressSnapshot = InvestigationProgress()
            currentGoalText = ""
            showNotebook = false
            showRcaDialog = false
            rcaReportResult = null
            lifecycleScope.launch {
                delay(250L)
                appState = AppState.IDLE
                Toast.makeText(this@MainActivity, "Session ended.", Toast.LENGTH_SHORT).show()
            }
        }
    }


    /**
     * V3.5 RCA is derived only from persisted accepted Notebook rows plus the
     * structured Notebook.  No action/visual/follow-up keyword lists and no
     * guessed "resolved" state are used.
     */
    private fun buildRcaReport(
        rows: List<PassiveNotebookObserver.Row>,
        notebook: InvestigationNotebook,
        audit: SessionAuditTrail.Snapshot,
        sessionMs: Long
    ): RCAReport {
        val problem = notebook.problem.ifBlank {
            audit.turns.firstOrNull { it.userText.isNotBlank() }?.userText?.take(300).orEmpty()
        }.ifBlank {
            rows.firstOrNull { it.user.isNotBlank() }?.user?.take(300).orEmpty()
        }.ifBlank { "Unknown problem" }

        val goal = notebook.currentGoal.ifBlank { problem }.trim()

        val confirmedFacts = notebook.confirmedFacts
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(20)

        val actionsTaken = notebook.actionsSuggested
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(16)

        val visualFindings = notebook.visualFindings
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(16)

        val authoritativeTurns = if (audit.turns.isNotEmpty()) audit.turns else rows.mapIndexed { index, row ->
            SessionAuditTrail.Turn(
                number = index + 1,
                timestampMs = 0L,
                userText = row.user,
                fullAnswer = row.assistant,
                answerSummary = summarizeReportAnswer(row.assistant),
                provider = row.provider,
                sourceIds = emptyList(),
                visualUsed = false,
                attachmentName = "",
                elapsedMs = 0L
            )
        }

        val lastAccepted = authoritativeTurns.lastOrNull { it.fullAnswer.isNotBlank() }
        val outcome = lastAccepted?.answerSummary?.ifBlank { lastAccepted.fullAnswer.take(420) }
            ?: "Session ended without an accepted final answer."

        val sourcesUsed = buildList {
            authoritativeTurns.forEach { turn ->
                add(providerFriendlyLabel(turn.provider))
                turn.sourceIds.filterNot(KnowledgeSourcePresentation::isInternalMarker).forEach { id -> add("Knowledge/source: $id") }
                if (turn.visualUsed) add("Camera AI")
                if (turn.attachmentName.isNotBlank()) add("Attachment: ${turn.attachmentName}")
            }
        }.distinct().take(24)

        val rcaSteps = authoritativeTurns.map { turn ->
            val sourceDetails = buildList {
                if (turn.visualUsed) add("Camera AI")
                turn.sourceIds.filterNot(KnowledgeSourcePresentation::isInternalMarker).takeIf { it.isNotEmpty() }?.let { add(it.joinToString(", ")) }
                if (turn.attachmentName.isNotBlank()) add("Attachment: ${turn.attachmentName}")
            }.joinToString(" • ")
            RCAStep(
                stepNumber = turn.number,
                description = turn.userText,
                result = turn.fullAnswer,
                summary = turn.answerSummary.ifBlank { summarizeReportAnswer(turn.fullAnswer) },
                source = sourceDetails,
                answeredBy = answerSource(turn.provider),
                answeredByLabel = providerFriendlyLabel(turn.provider),
                timestampMs = turn.timestampMs,
                elapsedMs = turn.elapsedMs,
                visualUsed = turn.visualUsed,
                attachmentName = turn.attachmentName
            )
        }

        val resolutionEntry = notebook.typedEntries
            .lastOrNull { it.type == NotebookEvidenceType.RESOLUTION }
            ?.content
            ?.trim()
            .orEmpty()
        val unresolved = notebook.questionsAlreadyAsked.lastOrNull()?.trim().orEmpty()
        val rootCause = when {
            notebook.primaryTheory.isNotBlank() -> notebook.primaryTheory.trim()
            notebook.secondaryTheory.isNotBlank() -> notebook.secondaryTheory.trim()
            resolutionEntry.isNotBlank() -> "The underlying root cause was not independently confirmed. The issue was resolved after the recorded troubleshooting action and the successful outcome was confirmed by the user."
            else -> "No root cause was confirmed during this session. Latest assessment: $outcome"
        }
        val confidence = when {
            resolutionEntry.isNotBlank() -> "Resolution confirmed by user"
            notebook.primaryTheory.isNotBlank() && confirmedFacts.size >= 2 -> "Medium"
            notebook.primaryTheory.isNotBlank() -> "Possible"
            else -> "Unresolved"
        }
        val status = when {
            resolutionEntry.isNotBlank() -> "Resolved"
            rootCause.startsWith("No root cause", ignoreCase = true) -> "Further diagnosis required"
            unresolved.isNotBlank() -> "Further diagnosis required"
            else -> "Investigation completed"
        }
        val followUp = when {
            resolutionEntry.isNotBlank() -> "No further troubleshooting requested. Session ended after user-confirmed resolution."
            else -> unresolved.ifBlank { actionsTaken.lastOrNull().orEmpty() }
        }
        val finalOutcome = if (resolutionEntry.isNotBlank()) resolutionEntry else outcome

        val startedAt = audit.startedAtMs.takeIf { it > 0 } ?: sessionStartMs
        val endedAt = audit.endedAtMs.takeIf { it > 0 } ?: System.currentTimeMillis()
        val sessionId = "FS-${endedAt.toString(36).uppercase()}"

        return RCAReport(
            sessionDurationMs = sessionMs,
            totalTurns = rcaSteps.size,
            sessionId = sessionId,
            startedAtMs = startedAt,
            endedAtMs = endedAt,
            goal = goal,
            sessionStatus = status,
            problemStatement = problem,
            confirmedFacts = confirmedFacts,
            checksPerformed = notebook.checksPerformed.toList(),
            rejectedTheories = notebook.rejectedTheories.toList(),
            actionsTaken = actionsTaken,
            visualFindings = visualFindings,
            outcome = finalOutcome,
            rootCause = rootCause,
            rootCauseConfidence = confidence,
            recommendedFollowUp = followUp,
            sourcesUsed = sourcesUsed,
            steps = rcaSteps,
            resolution = resolutionEntry,
            sessionDurationMinutes = (sessionMs / 60_000).toInt()
        )
    }

    private fun summarizeReportAnswer(raw: String): String {
        val clean = FixSightOutputSanitizer.sanitize(raw).replace(Regex("\\s+"), " ").trim()
        if (clean.isBlank()) return ""
        val sentences = Regex("[^.!?।！？]+(?:[.!?।！？]+|$)")
            .findAll(clean).map { it.value.trim() }.filter { it.isNotBlank() }.toList()
        val summary = if (sentences.size >= 2) sentences.take(2).joinToString(" ") else clean
        return if (summary.length <= 280) summary else summary.take(277).trimEnd() + "…"
    }

    private fun providerFriendlyLabel(provider: String): String = when {
        provider.equals(AndroBaselineRuntime.Provider.CLOUD.name, ignoreCase = true) -> "Cloud AI"
        provider.equals(AndroBaselineRuntime.Provider.LOCAL.name, ignoreCase = true) -> "On-device AI"
        provider.equals("INTERRUPTED", ignoreCase = true) -> "Interrupted by user"
        else -> "Pramaan"
    }

    private fun answerSource(provider: String): AnswerSource = when {
        provider.equals(AndroBaselineRuntime.Provider.CLOUD.name, ignoreCase = true) -> AnswerSource.CLOUD
        provider.equals(AndroBaselineRuntime.Provider.LOCAL.name, ignoreCase = true) -> AnswerSource.LOCAL
        else -> AnswerSource.OFFLINE_FALLBACK
    }


    private fun saveSessionReportPdf(report: RCAReport, includeTranscript: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            val uri = SessionReportExporter.savePdf(this@MainActivity, report, includeTranscript)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@MainActivity,
                    if (uri != null) "Session PDF saved to Downloads/Pramaan" else "Unable to save PDF on this device.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun shareSessionReportPdf(report: RCAReport, includeTranscript: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            val uri = SessionReportExporter.savePdf(this@MainActivity, report, includeTranscript)
            withContext(Dispatchers.Main) {
                if (uri != null) {
                    if (!SessionReportExporter.sharePdfUri(this@MainActivity, uri)) {
                        Toast.makeText(this@MainActivity, "Unable to open the share sheet.", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(this@MainActivity, "Unable to create session PDF.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun shareSessionSummaryText(report: RCAReport) {
        if (!SessionReportExporter.shareSummaryText(this, report)) {
            Toast.makeText(this, "Unable to open the share sheet.", Toast.LENGTH_LONG).show()
        }
    }


// ─────────────────────────────────────────────────────────────────────────────
// Unified FixSight assistant surface
//
// UI contract only. Runtime ownership is unchanged:
//   MainActivity -> AndroVoiceAssistant command bridge -> foreground service.
// Voice, TTS, local/cloud routing, visual capture, Notebook and RCA remain in
// their existing owners. Camera is a capability inside one session, not a
// separate session type.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun FixSightUnifiedScreen(
    initialComposerText: String = "",
    onVisitDashboard: () -> Unit = {},
    activeKnowledgeName: String = "",
    appState: AppState,
    sessionActive: Boolean,
    voiceReady: Boolean,
    voiceInitComplete: Boolean,
    listening: Boolean,
    turnActive: Boolean,
    currentUserTranscript: String,
    currentAssistantAnswer: String,
    isVisionMode: Boolean,
    knowledgeModeEnabled: Boolean,
    troubleshootingMode: Boolean,
    conversations: List<AndroConversationSession>,
    showNotebook: Boolean,
    notebook: InvestigationNotebook?,
    stepHistory: List<MainActivity.TurnSummary>,
    selectedTool: ARTool,
    selectedColor: ARColor,
    pendingAttachmentLabel: String,
    attachmentPreparing: Boolean,
    arGuidanceActive: Boolean,
    onStartSession: () -> Unit,
    onNewChat: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onStartTroubleshooting: () -> Unit,
    onToolSelected: (ARTool) -> Unit,
    onColorSelected: (ARColor) -> Unit,
    onARTap: (Float, Float, Int, Int, String?) -> Unit,
    onToggleNotebook: () -> Unit,
    onSettings: () -> Unit,
    onFrame: (Session, Frame) -> Unit,
    onInterrupt: () -> Unit,
    onStopSpeech: () -> Unit,
    onToggleVoiceInput: () -> Unit,
    onSpeakAnswer: () -> Unit,
    onSpeakText: (String) -> Unit,
    onSubmitText: (String) -> Unit,
    onCheckOnline: (String) -> Unit,
    onAttachFile: () -> Unit,
    onCapturePhoto: () -> Unit,
    onClearAttachment: () -> Unit,
    onToggleVisionMode: (Boolean) -> Unit,
    onToggleKnowledgeMode: (Boolean) -> Unit,
    onEndSession: () -> Unit
) {
    var composerText by remember(initialComposerText) { mutableStateOf(initialComposerText) }
    var attachmentMenuOpen by remember { mutableStateOf(false) }
    var showArTools by remember { mutableStateOf(false) }
    var showLiveTranscript by remember { mutableStateOf(false) }
    var arLabelText by remember { mutableStateOf("") }
    var arPlacementLabel by remember { mutableStateOf<String?>(null) }
    var arTextToolPending by remember { mutableStateOf<ARTool?>(null) }
    // AR V1 guided-step presentation is deliberately UI-only. It derives displayable
    // steps from the already accepted assistant answer; it never changes KB retrieval,
    // provider routing, Notebook state, or the accepted conversation. Advancement is
    // user-controlled so a visual guess can never silently complete a procedure step.
    val arGuidanceSteps = remember(currentAssistantAnswer) { extractArGuidanceSteps(currentAssistantAnswer) }
    var arGuidanceStepIndex by remember(currentAssistantAnswer) { mutableStateOf(0) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val uiScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val softwareKeyboardController = LocalSoftwareKeyboardController.current

    arTextToolPending?.let { pendingTool ->
        AlertDialog(
            onDismissRequest = { arTextToolPending = null },
            title = { Text(if (pendingTool == ARTool.INSTRUCTION_CARD) "Instruction card" else "AR text label") },
            text = {
                OutlinedTextField(
                    value = arLabelText,
                    onValueChange = { arLabelText = it.take(240) },
                    label = { Text(if (pendingTool == ARTool.INSTRUCTION_CARD) "Instruction" else "Label") },
                    minLines = if (pendingTool == ARTool.INSTRUCTION_CARD) 2 else 1,
                    maxLines = 4,
                    singleLine = pendingTool == ARTool.TEXT
                )
            },
            confirmButton = {
                TextButton(
                    enabled = arLabelText.isNotBlank(),
                    onClick = {
                        arPlacementLabel = arLabelText.trim()
                        onToolSelected(pendingTool)
                        arTextToolPending = null
                    }
                ) { Text("Use") }
            },
            dismissButton = {
                TextButton(onClick = { arTextToolPending = null }) { Text("Cancel") }
            }
        )
    }

    val stateLabel = when {
        !sessionActive -> "Ready"
        appState == AppState.LISTENING -> "Listening"
        appState == AppState.THINKING -> "Thinking…"
        appState == AppState.SPEAKING -> "Speaking"
        troubleshootingMode -> "Troubleshooting"
        isVisionMode -> "Live camera"
        else -> "Ready"
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Color(0xFF171717),
                modifier = Modifier.widthIn(max = 330.dp)
            ) {
                Spacer(Modifier.height(14.dp))
                Text(
                    "Pramaan",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
                )

                NavigationDrawerItem(
                    label = { Text("ASHA visits & next actions") },
                    selected = false,
                    onClick = { onVisitDashboard() },
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                NavigationDrawerItem(
                    label = { Text("New chat") },
                    selected = false,
                    onClick = {
                        uiScope.launch { drawerState.close() }
                        onNewChat()
                    },
                    icon = { Text("＋", color = Color.White, fontSize = 22.sp) },
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedContainerColor = Color.Transparent,
                        unselectedTextColor = Color(0xFFF2F2F2)
                    ),
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                NavigationDrawerItem(
                    label = { Text(if (troubleshootingMode) "Troubleshooting active" else "Troubleshoot") },
                    selected = troubleshootingMode,
                    onClick = {
                        uiScope.launch { drawerState.close() }
                        if (!troubleshootingMode) onStartTroubleshooting()
                    },
                    icon = { Text("✦", color = Color.White, fontSize = 18.sp) },
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = Color(0xFF2F2F2F),
                        unselectedContainerColor = Color.Transparent,
                        selectedTextColor = Color.White,
                        unselectedTextColor = Color(0xFFF2F2F2)
                    ),
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                if (conversations.isNotEmpty()) {
                    Text(
                        "RECENT",
                        color = Color(0xFF8E8E93),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 8.dp)
                    )
                    conversations.take(5).forEach { conversation ->
                        NavigationDrawerItem(
                            label = {
                                Column {
                                    Text(conversation.title, maxLines = 1)
                                    if (conversation.mode == "TROUBLESHOOTING") {
                                        Text("Troubleshooting", color = Color(0xFF8E8E93), fontSize = 10.sp)
                                    }
                                }
                            },
                            selected = false,
                            onClick = {
                                uiScope.launch { drawerState.close() }
                                onOpenConversation(conversation.id)
                            },
                            colors = NavigationDrawerItemDefaults.colors(
                                unselectedContainerColor = Color.Transparent,
                                unselectedTextColor = Color(0xFFE7E7E7)
                            ),
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }

                Spacer(Modifier.weight(1f))
                HorizontalDivider(color = Color(0xFF2D2D2D))
                NavigationDrawerItem(
                    label = {
                        Text(
                            "Notebook",
                            color = if (notebook != null) Color(0xFFF2F2F2) else Color(0xFF666666)
                        )
                    },
                    selected = false,
                    onClick = {
                        if (notebook != null) {
                            uiScope.launch { drawerState.close() }
                            onToggleNotebook()
                        }
                    },
                    icon = {
                        Text(
                            "▤",
                            color = if (notebook != null) Color(0xFFDADADA) else Color(0xFF666666),
                            fontSize = 18.sp
                        )
                    },
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedContainerColor = Color.Transparent,
                        unselectedTextColor = Color(0xFFF2F2F2)
                    ),
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                NavigationDrawerItem(
                    label = { Text("Settings") },
                    selected = false,
                    onClick = {
                        uiScope.launch { drawerState.close() }
                        onSettings()
                    },
                    icon = { Text("⚙", color = Color(0xFFDADADA), fontSize = 18.sp) },
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedContainerColor = Color.Transparent,
                        unselectedTextColor = Color(0xFFF2F2F2)
                    ),
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Knowledge Base", color = Color(0xFFD0D0D0), fontSize = 13.sp)
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = knowledgeModeEnabled,
                        onCheckedChange = { onToggleKnowledgeMode(it) },
                        enabled = appState != AppState.THINKING && appState != AppState.SPEAKING
                    )
                }
            }
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF212121))
                .statusBarsPadding()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                FixSightTopBar(
                    knowledgeOn = knowledgeModeEnabled,
                    knowledgeName = activeKnowledgeName,
                    onKnowledgeOff = { onToggleKnowledgeMode(false) },
                    appState = appState,
                    stateLabel = stateLabel,
                    sessionActive = sessionActive,
                    isVisionMode = isVisionMode,
                    troubleshootingMode = troubleshootingMode,
                    onMenu = { uiScope.launch { drawerState.open() } },
                    onInterrupt = onInterrupt,
                    onStopSpeech = onStopSpeech,
                    onEndTroubleshooting = onEndSession,
                    interruptEnabled = appState == AppState.SPEAKING || appState == AppState.THINKING
                )

                if (isVisionMode && sessionActive) {
                    // V15 stable live workspace: the AR/Camera surface owns the remaining
                    // content area and is NOT remeasured by IME animation or streaming text.
                    // Only a bounded live instruction strip sits below it. The composer is
                    // rendered as an overlay sibling further below so imePadding() cannot
                    // continuously resize the native SurfaceView / ARSceneView.
                    val liveComposerReserve = if (troubleshootingMode) 132.dp else 78.dp
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(bottom = liveComposerReserve)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(0.60f)
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(Color.Black)
                        ) {
                            ARHUDView(
                                modifier = Modifier.fillMaxSize(),
                                isVisionMode = true,
                                arGuidanceActive = arGuidanceActive,
                                onFrame = onFrame
                            )
                            // Dedicated top-level Compose gesture surface. Keeping this as a
                            // sibling ABOVE the Android/SceneView makes manual taps deterministic
                            // and guarantees the tap coordinates share the exact AR viewport.
                            if (showArTools) {
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .pointerInput(arPlacementLabel, selectedTool) {
                                            detectTapGestures { offset ->
                                                Log.i(
                                                    "AR_DEBUG_TRACE",
                                                    "AR_MANUAL_TAP_RECEIVED tool=$selectedTool x=${offset.x} y=${offset.y} view=${size.width}x${size.height}"
                                                )
                                                onARTap(offset.x, offset.y, size.width, size.height, arPlacementLabel)
                                            }
                                        }
                                )
                            }
                            // Manual AR is a Live Video capability, not something the user must
                            // unlock by first succeeding at AI localization.
                            Surface(
                                color = Color.Black.copy(alpha = 0.60f),
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                                    .clickable { showArTools = !showArTools }
                            ) {
                                Text(if (showArTools) "Close AR tools" else "AR", color = Color.White,
                                    fontSize = 12.sp, modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp))
                            }
                            if (showArTools) {
                                Box(modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)) {
                                    ARToolbar(
                                        selectedTool = selectedTool,
                                        selectedColor = selectedColor,
                                        onToolSelected = { tool ->
                                            if (tool == ARTool.TEXT || tool == ARTool.INSTRUCTION_CARD) {
                                                arLabelText = ""
                                                arTextToolPending = tool
                                            } else {
                                                arPlacementLabel = null
                                                onToolSelected(tool)
                                            }
                                        },
                                        onColorSelected = onColorSelected
                                    )
                                }
                            }
                            // V1 guided procedure overlay. The current accepted answer remains the
                            // authority. "Place" only arms the existing INSTRUCTION_CARD path;
                            // the user must tap the intended physical location before an ARCore
                            // anchor is created (or a 2D card on the Camera2 fallback backend).
                            if (arGuidanceActive && arGuidanceSteps.isNotEmpty()) {
                                val safeIndex = arGuidanceStepIndex.coerceIn(0, arGuidanceSteps.lastIndex)
                                Surface(
                                    color = Color.Black.copy(alpha = 0.72f),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp).widthIn(max = 360.dp)
                                ) {
                                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            "Step ${safeIndex + 1}/${arGuidanceSteps.size}",
                                            color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            arGuidanceSteps[safeIndex],
                                            color = Color.White, fontSize = 12.sp, maxLines = 4
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            TextButton(
                                                enabled = safeIndex > 0,
                                                onClick = { arPlacementLabel = null; arGuidanceStepIndex = (safeIndex - 1).coerceAtLeast(0) }
                                            ) { Text("Back") }
                                            TextButton(
                                                onClick = {
                                                    arPlacementLabel = "Step ${safeIndex + 1}: ${arGuidanceSteps[safeIndex]}".take(240)
                                                    onToolSelected(ARTool.INSTRUCTION_CARD)
                                                }
                                            ) { Text("Place") }
                                            TextButton(
                                                enabled = safeIndex < arGuidanceSteps.lastIndex,
                                                onClick = { arPlacementLabel = null; arGuidanceStepIndex = (safeIndex + 1).coerceAtMost(arGuidanceSteps.lastIndex) }
                                            ) { Text("Next") }
                                        }
                                    }
                                }
                            }
                        }

                        // V21: Live Camera keeps the full conversation visible in-place.
                        // The same LazyColumn used by normal chat is scrollable, preserves
                        // history, and follows the newest turn only while the user stays at bottom.
                        FixSightConversationPanel(
                            modifier = Modifier
                                .weight(0.40f)
                                .fillMaxWidth(),
                            sessionActive = sessionActive,
                            stepHistory = stepHistory,
                            currentUserTranscript = currentUserTranscript,
                            currentAssistantAnswer = currentAssistantAnswer,
                            turnActive = turnActive,
                            onSpeakText = onSpeakText,
                            onSubmitText = onSubmitText,
                            onCheckOnline = onCheckOnline
                        )
                    }
                } else {
                    FixSightConversationPanel(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        sessionActive = sessionActive,
                        stepHistory = stepHistory,
                        currentUserTranscript = currentUserTranscript,
                        currentAssistantAnswer = currentAssistantAnswer,
                        turnActive = turnActive,
                        onSpeakText = onSpeakText,
                        onSubmitText = onSubmitText,
                        onCheckOnline = onCheckOnline
                    )

                    // Normal chat keeps traditional resize behavior. IME insets are owned by
                    // the composer rather than the whole screen so the policy is explicit.
                    FixSightBottomComposer(
                        modifier = Modifier.navigationBarsPadding().imePadding(),
                        sessionActive = sessionActive,
                        appState = appState,
                        listening = listening,
                        isVisionMode = isVisionMode,
                        troubleshootingMode = troubleshootingMode,
                        composerText = composerText,
                        pendingAttachmentLabel = pendingAttachmentLabel,
                        attachmentPreparing = attachmentPreparing,
                        attachmentMenuOpen = attachmentMenuOpen,
                        onComposerTextChange = { composerText = it },
                        onAttachmentMenuChange = { attachmentMenuOpen = it },
                        onStartSession = onStartSession,
                        onSubmitText = {
                            val clean = composerText.trim()
                            if (clean.isNotBlank()) {
                                composerText = ""
                                focusManager.clearFocus(force = true)
                                softwareKeyboardController?.hide()
                                onSubmitText(clean)
                            }
                        },
                        onAttachFile = onAttachFile,
                        onCapturePhoto = onCapturePhoto,
                        onClearAttachment = onClearAttachment,
                        onToggleVisionMode = onToggleVisionMode,
                        onToggleVoiceInput = onToggleVoiceInput,
                        onStartTroubleshooting = onStartTroubleshooting
                    )
                }
            }

            if (isVisionMode && sessionActive) {
                // Overlay composer: IME padding moves ONLY this layer. The persistent
                // ARHUDView above keeps the same composition identity/session/anchors.
                FixSightBottomComposer(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .imePadding(),
                    sessionActive = sessionActive,
                    appState = appState,
                    listening = listening,
                    isVisionMode = isVisionMode,
                    troubleshootingMode = troubleshootingMode,
                    composerText = composerText,
                    pendingAttachmentLabel = pendingAttachmentLabel,
                    attachmentPreparing = attachmentPreparing,
                    attachmentMenuOpen = attachmentMenuOpen,
                    onComposerTextChange = { composerText = it },
                    onAttachmentMenuChange = { attachmentMenuOpen = it },
                    onStartSession = onStartSession,
                    onSubmitText = {
                        val clean = composerText.trim()
                        if (clean.isNotBlank()) {
                            composerText = ""
                            focusManager.clearFocus(force = true)
                            softwareKeyboardController?.hide()
                            onSubmitText(clean)
                        }
                    },
                    onAttachFile = onAttachFile,
                    onCapturePhoto = onCapturePhoto,
                    onClearAttachment = onClearAttachment,
                    onToggleVisionMode = onToggleVisionMode,
                    onToggleVoiceInput = onToggleVoiceInput,
                    onStartTroubleshooting = onStartTroubleshooting
                )
            }
        }
    }

    if (showLiveTranscript && isVisionMode && sessionActive) {
        Dialog(onDismissRequest = { showLiveTranscript = false }) {
            Surface(
                color = Color(0xFF212121),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.76f)
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Conversation",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { showLiveTranscript = false }) { Text("Close") }
                    }
                    FixSightConversationPanel(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        sessionActive = sessionActive,
                        stepHistory = stepHistory,
                        currentUserTranscript = currentUserTranscript,
                        currentAssistantAnswer = currentAssistantAnswer,
                        turnActive = turnActive,
                        onSpeakText = onSpeakText,
                        onSubmitText = onSubmitText,
                        onCheckOnline = onCheckOnline
                    )
                }
            }
        }
    }

    if (showNotebook && notebook != null) {
        FixSightNotebookDialog(notebook = notebook, onDismiss = onToggleNotebook)
    }
}

/**
 * Extracts only explicit list/step lines from an already accepted answer. This is a
 * presentation helper, not a procedure parser: prose is never converted into invented
 * steps, and no step is marked complete automatically. Source labels such as [S1] are
 * preserved verbatim so a grounded KB answer keeps its visible provenance.
 */
private fun extractArGuidanceSteps(answer: String): List<String> {
    if (answer.isBlank()) return emptyList()
    val numbered = Regex("""^\s*(?:step\s+)?\d{1,2}[.)\-:]\s+(.+)$""", RegexOption.IGNORE_CASE)
    val bullets = Regex("""^\s*[-•]\s+(.+)$""")
    return answer.lineSequence()
        .mapNotNull { line ->
            numbered.find(line)?.groupValues?.getOrNull(1)
                ?: bullets.find(line)?.groupValues?.getOrNull(1)
        }
        .map { it.trim() }
        .filter { it.length >= 4 }
        .distinct()
        .take(12)
        .toList()
}

@Composable
private fun FixSightTopBar(
    knowledgeOn: Boolean = false,
    knowledgeName: String = "",
    onKnowledgeOff: () -> Unit = {},
    appState: AppState,
    stateLabel: String,
    sessionActive: Boolean,
    isVisionMode: Boolean,
    troubleshootingMode: Boolean,
    onMenu: () -> Unit,
    onInterrupt: () -> Unit,
    onStopSpeech: () -> Unit,
    onEndTroubleshooting: () -> Unit,
    interruptEnabled: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(color = Color.Transparent, shape = CircleShape,
            modifier = Modifier.size(44.dp).clickable(onClick = onMenu)) {
            Box(contentAlignment = Alignment.Center) { Text("☰", color = Color.White, fontSize = 22.sp) }
        }
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(if (isVisionMode) "Pramaan  ·  Camera" else "Pramaan",
                color = Color(0xFFF5F5F5), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            // Always visible while a knowledge base answers the questions, with a one-tap way out.
            if (knowledgeOn) {
                Text(
                    (if (knowledgeName.isNotBlank()) "KB: ${knowledgeName.take(28)} · tap to turn off" else "Knowledge base ON · tap to turn off"),
                    color = Color(0xFFFFD8A8), fontSize = 10.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable(onClick = onKnowledgeOff)
                )
            }
            if (sessionActive && stateLabel != "Ready") {
                Text(
                    stateLabel,
                    color = Color(0xFFA6A6A6),
                    fontSize = 10.sp,
                    modifier = Modifier.clickable(
                        enabled = interruptEnabled,
                        onClick = { if (appState == AppState.SPEAKING) onStopSpeech() else onInterrupt() }
                    )
                )
            }
        }
        Spacer(Modifier.weight(1f))
        if (appState == AppState.SPEAKING) {
            TextButton(onClick = onStopSpeech) {
                Text("Stop voice", color = Color(0xFFF0F0F0), fontSize = 12.sp)
            }
        } else if (troubleshootingMode) {
            TextButton(onClick = onEndTroubleshooting) {
                Text("End", color = Color(0xFFF0F0F0), fontSize = 12.sp)
            }
        } else if (interruptEnabled) {
            TextButton(onClick = onInterrupt) { Text("Stop", color = Color(0xFFF0F0F0), fontSize = 12.sp) }
        }
    }
}

@Composable
private fun FixSightLiveConversationStrip(
    modifier: Modifier,
    stepHistory: List<MainActivity.TurnSummary>,
    currentUserTranscript: String,
    currentAssistantAnswer: String,
    turnActive: Boolean,
    onOpenTranscript: () -> Unit
) {
    val latestAssistant = currentAssistantAnswer.trim().ifBlank {
        stepHistory.lastOrNull()?.response?.trim().orEmpty()
    }
    val latestUser = currentUserTranscript.trim().ifBlank {
        stepHistory.lastOrNull()?.query?.trim().orEmpty()
    }
    val displayText = when {
        latestAssistant.isNotBlank() -> latestAssistant
        turnActive && latestUser.isNotBlank() -> "Working on: $latestUser"
        latestUser.isNotBlank() -> latestUser
        else -> "Live Camera is ready. Ask about what you see."
    }
    val answerScroll = rememberScrollState()
    LaunchedEffect(displayText) { answerScroll.scrollTo(0) }

    Surface(color = Color(0xFF212121), modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (turnActive && latestAssistant.isBlank()) "Pramaan is looking" else "Pramaan reply",
                        color = Color(0xFF9F9F9F),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (latestUser.isNotBlank()) {
                        Text(
                            text = latestUser,
                            color = Color(0xFFBEBEBE),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                TextButton(onClick = onOpenTranscript) {
                    Text("History", color = Color(0xFFD6D6D6), fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(4.dp))
            Surface(
                color = Color(0xFF303030),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                Text(
                    text = displayText,
                    color = Color(0xFFF2F2F2),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(answerScroll)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun FixSightConversationPanel(
    modifier: Modifier,
    sessionActive: Boolean,
    stepHistory: List<MainActivity.TurnSummary>,
    currentUserTranscript: String,
    currentAssistantAnswer: String,
    turnActive: Boolean,
    onSpeakText: (String) -> Unit,
    onSubmitText: (String) -> Unit,
    onCheckOnline: (String) -> Unit
) {
    val listState = rememberLazyListState()
    var followLatest by remember { mutableStateOf(true) }
    val lastCompletedQuery = stepHistory.lastOrNull()?.query?.trim().orEmpty()
    val lastCompletedAnswer = stepHistory.lastOrNull()?.response?.trim().orEmpty()
    val cleanCurrentUser = currentUserTranscript.trim()
    val cleanCurrentAnswer = currentAssistantAnswer.trim()
    val showCurrentUser = sessionActive && cleanCurrentUser.isNotBlank() && cleanCurrentUser != lastCompletedQuery
    // V22: create the assistant slot as soon as a turn starts. Clean Local deltas
    // progressively replace the placeholder; buffered visual/grounded/cloud paths
    // keep the placeholder until their accepted final answer arrives.
    val showCurrentAnswer = sessionActive && turnActive ||
        (sessionActive && cleanCurrentAnswer.isNotBlank() && cleanCurrentAnswer != lastCompletedAnswer)
    val showGreeting = sessionActive && stepHistory.isEmpty() && !showCurrentUser && !showCurrentAnswer
    // V23: include a terminal bottom anchor. Scrolling to the last message item aligns
    // the TOP of a tall assistant bubble with the pane, which can leave the end of the
    // answer below the 40% Live Camera conversation viewport. The anchor gives auto-follow
    // a stable target below the growing/final answer.
    val contentItemCount = stepHistory.size * 2 + (if (showGreeting) 1 else 0) +
        (if (showCurrentUser) 1 else 0) + (if (showCurrentAnswer) 1 else 0)
    val itemCount = contentItemCount + 1

    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) followLatest = !listState.canScrollForward
    }
    LaunchedEffect(itemCount, cleanCurrentAnswer.length, followLatest) {
        if (followLatest && itemCount > 0) runCatching { listState.scrollToItem(itemCount - 1) }
    }

    Surface(color = Color(0xFF212121), modifier = modifier) {
        if (stepHistory.isEmpty() && !showGreeting && !showCurrentUser && !showCurrentAnswer) {
            Box(modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(color = Color(0xFFF3F3F3), shape = CircleShape, modifier = Modifier.size(44.dp)) {
                        Box(contentAlignment = Alignment.Center) { Text("✦", color = Color(0xFF212121), fontSize = 24.sp) }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("What can I help with?", color = Color(0xFFF2F2F2), fontSize = 22.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    Text(if (sessionActive) "Type a message, use the microphone, attach a file, or open Live Camera."
                        else "Start a conversation to use Pramaan offline or online.",
                        color = Color(0xFFA7A7A7), fontSize = 13.sp, lineHeight = 19.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    if (showGreeting) {
                        item(key = "greeting") {
                            FixSightMessage(text = "How can I help you?", isUser = false,
                                onSpeak = { onSpeakText("How can I help you?") })
                        }
                    }
                    stepHistory.forEachIndexed { index, turn ->
                        item(key = "user-$index") { FixSightMessage(text = turn.query, isUser = true) }
                        item(key = "assistant-$index") {
                            FixSightMessage(
                                text = turn.response,
                                isUser = false,
                                provider = turn.provider,
                                knowledgeUsed = turn.knowledgeUsed,
                                sourceIds = turn.sourceIds,
                                onSpeak = { onSpeakText(turn.response) },
                                // "Check online" under every answer from the phone's own model, and under
                                // "couldn't reach current information" replies (a retry online).
                                // Never for a visit record or the due list: patient details stay on the phone.
                                onCheckOnline = if (turn.sourceIds.none { it == "visit_note" || it == "due_list" } &&
                                    (turn.sourceIds.contains("verification:optional") ||
                                    turn.provider == AndroBaselineRuntime.Provider.LOCAL ||
                                    turn.sourceIds.any { it.startsWith("freshness_required:") })) ({
                                    onCheckOnline(turn.query.trim())
                                }) else null
                            )
                        }
                    }
                    if (showCurrentUser) {
                        item(key = "current-user") { FixSightMessage(text = cleanCurrentUser, isUser = true, isLive = turnActive) }
                    }
                    if (showCurrentAnswer) {
                        item(key = "current-assistant") {
                            val visibleAnswer = cleanCurrentAnswer.ifBlank { "Generating…" }
                            FixSightMessage(
                                text = visibleAnswer,
                                isUser = false,
                                isLive = turnActive,
                                onSpeak = if (turnActive || cleanCurrentAnswer.isBlank()) null else ({ onSpeakText(cleanCurrentAnswer) })
                            )
                        }
                    }
                    item(key = "conversation-bottom-anchor") {
                        Spacer(Modifier.height(1.dp))
                    }
                }
                if (!followLatest && listState.canScrollForward) {
                    Surface(
                        color = Color(0xFF343434), shape = CircleShape,
                        border = BorderStroke(1.dp, Color(0xFF505050)),
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).size(38.dp)
                            .clickable { followLatest = true }
                    ) {
                        Box(contentAlignment = Alignment.Center) { Text("↓", color = Color.White, fontSize = 18.sp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FixSightMessage(
    text: String,
    isUser: Boolean,
    isLive: Boolean = false,
    onSpeak: (() -> Unit)? = null,
    provider: AndroBaselineRuntime.Provider? = null,
    knowledgeUsed: Boolean = false,
    sourceIds: List<String> = emptyList(),
    onCheckOnline: (() -> Unit)? = null
) {
    if (isUser) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(color = Color(0xFF303030), shape = RoundedCornerShape(20.dp), modifier = Modifier.widthIn(max = 310.dp)) {
                Text(text = text, color = Color(0xFFF4F4F4), fontSize = 15.sp, lineHeight = 21.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
    } else {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Surface(color = Color(0xFFF2F2F2), shape = CircleShape, modifier = Modifier.size(28.dp)) {
                Box(contentAlignment = Alignment.Center) { Text("✦", color = Color(0xFF212121), fontSize = 14.sp) }
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                // Weak knowledge-base match: say so before the answer, not only below it, so a
                // plausible but unsupported answer is not read as a definite one.
                if (!isLive && sourceIds.contains(KnowledgeSourcePresentation.VERY_WEAK)) {
                    Text(
                        "⚠ Not clearly found in this knowledge base — closest information below. Use Check online to verify.",
                        color = Color(0xFFFFD8A8), fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(6.dp))
                }
                // "The document says": the source's own words first, then the explanation.
                val quote = if (!isLive) KnowledgeSourcePresentation.quoteOf(sourceIds) else null
                if (quote != null) {
                    val hindiUi = text.count { it.code in 0x0900..0x097F } > text.count { it in 'a'..'z' || it in 'A'..'Z' }
                    Surface(
                        color = Color(0xFF1B2A22),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color(0xFF2F5E45)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                            Text(
                                if (hindiUi) "📄 दस्तावेज़ में लिखा है" else "📄 The document says",
                                color = Color(0xFF9FD9B6), fontSize = 11.sp, fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text("“" + quote.text + "”", color = Color(0xFFE8F5EC), fontSize = 13.sp, lineHeight = 18.sp,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                            if (quote.source.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text("— " + KnowledgeSourcePresentation.cleanTitle(quote.source), color = Color(0xFF8FB8A0), fontSize = 10.sp, lineHeight = 13.sp)
                            }
                            if (quote.mismatch) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    if (hindiUi) "⚠ नीचे की व्याख्या के अंक दस्तावेज़ से मेल नहीं खाते — दस्तावेज़ के शब्द ही मान्य हैं।"
                                    else "⚠ The numbers in the explanation below differ from the document — go by the document's words.",
                                    color = Color(0xFFFFD8A8), fontSize = 11.sp, lineHeight = 15.sp
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(if (hindiUi) "व्याख्या" else "Explanation", color = Color(0xFF9F9F9F), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                }
                AssistantAnswerBody(text = text, knowledgeUsed = knowledgeUsed)
                if (isLive && text.trim() != "Generating…") {
                    Spacer(Modifier.height(6.dp))
                    Text("Generating…", color = Color(0xFF8F8F8F), fontSize = 11.sp)
                }
                if (onSpeak != null && !isLive) {
                    Spacer(Modifier.height(7.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = Color.Transparent, shape = CircleShape,
                            modifier = Modifier.size(34.dp).clickable(onClick = onSpeak)) {
                            Box(contentAlignment = Alignment.Center) { Text("🔊", color = Color(0xFFCECECE), fontSize = 15.sp) }
                        }
                        Text("Read aloud", color = Color(0xFF9F9F9F), fontSize = 11.sp)
                        // Share the answer (a visit record to the ANM on WhatsApp, an answer with its sources).
                        val shareContext = androidx.compose.ui.platform.LocalContext.current
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Share",
                            color = Color(0xFF9F9F9F),
                            fontSize = 11.sp,
                            modifier = Modifier.clickable {
                                runCatching {
                                    val plain = text.replace("**", "").trim()
                                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(android.content.Intent.EXTRA_TEXT, plain)
                                    }
                                    shareContext.startActivity(android.content.Intent.createChooser(send, "Share"))
                                }
                            }.padding(horizontal = 4.dp, vertical = 6.dp)
                        )
                        if (provider == AndroBaselineRuntime.Provider.LOCAL ||
                            provider == AndroBaselineRuntime.Provider.CLOUD) {
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                color = Color(0xFF303030),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, Color(0xFF505050))
                            ) {
                                Text(
                                    text = when (provider) {
                                        AndroBaselineRuntime.Provider.LOCAL ->
                                            if (knowledgeUsed) "LOCAL AI + KB" else "LOCAL AI"
                                        AndroBaselineRuntime.Provider.CLOUD ->
                                            if (knowledgeUsed) "CLOUD AI + KB" else "CLOUD AI"
                                        else -> ""
                                    },
                                    color = Color(0xFFBDBDBD),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }
                if (onCheckOnline != null && !isLive) {
                    Spacer(Modifier.height(7.dp))
                    OutlinedButton(onClick = onCheckOnline) {
                        Text("Check online", fontSize = 11.sp)
                    }
                }
                val kbNoMatch = sourceIds.firstOrNull { it.startsWith("kbnomatch:") }
                    ?.removePrefix("kbnomatch:")
                val kbUnavailable = sourceIds.firstOrNull { it.startsWith("kbunavailable:") }
                    ?.removePrefix("kbunavailable:")
                if (!isLive && (kbNoMatch != null || kbUnavailable != null)) {
                    Spacer(Modifier.height(7.dp))
                    Surface(
                        color = Color(0xFF2A2A2A),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color(0xFF4A4A4A)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (kbUnavailable != null)
                                "Knowledge Base note: ${kbUnavailable.ifBlank { "Selected Knowledge Base" }} was unavailable for this turn, so this answer is not grounded in it."
                            else
                                "Knowledge Base note: no confident match was found in ${kbNoMatch.orEmpty().ifBlank { "the selected Knowledge Base" }} for this turn, so this answer is not grounded in it.",
                            color = Color(0xFFBDBDBD),
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }
                // "Not in this KB" / weak-match card with the closest topics the KB does have.
                val kbNotFound = sourceIds.firstOrNull { it.startsWith(KnowledgeSourcePresentation.NOT_FOUND_PREFIX) }
                val kbWeak = sourceIds.firstOrNull { it.startsWith(KnowledgeSourcePresentation.WEAK_PREFIX) }
                if (!isLive && (kbNotFound != null || kbWeak != null)) {
                    val topics = KnowledgeSourcePresentation.topicsOf(kbNotFound ?: kbWeak.orEmpty())
                    Spacer(Modifier.height(7.dp))
                    Surface(
                        color = if (kbNotFound != null) Color(0xFF3A2A1E) else Color(0xFF2E2A1E),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, if (kbNotFound != null) Color(0xFF8A5A2B) else Color(0xFF7A6A2B)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                            Text(
                                if (kbNotFound != null) "Not found in this knowledge base"
                                else "Weak match — this knowledge base may not cover this exactly. Check the answer against the sources.",
                                color = Color(0xFFFFD8A8), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, lineHeight = 15.sp
                            )
                            if (topics.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text("Closest topics in the knowledge base:", color = Color(0xFFBDBDBD), fontSize = 10.sp)
                                topics.forEach { t ->
                                    Text("• $t", color = Color(0xFFBDBDBD), fontSize = 10.sp, lineHeight = 14.sp)
                                }
                            }
                            if (onCheckOnline != null) {
                                Spacer(Modifier.height(4.dp))
                                Text("Use “Check online” above to ask the online AI.", color = Color(0xFF9F9F9F), fontSize = 10.sp)
                            }
                        }
                    }
                }
                val knowledgeSources = KnowledgeSourcePresentation.visibleSources(sourceIds)
                val sourcePassages = KnowledgeSourcePresentation.passagesForRows(sourceIds)
                if (knowledgeSources.isNotEmpty() && !isLive) {
                    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                    var sourcesExpanded by remember(sourceIds) { mutableStateOf(true) }  // shown open: the evidence is the point
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = Color(0x22FFFFFF))
                    Spacer(Modifier.height(5.dp))
                    Surface(
                        color = Color(0xFF262626),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFF3D3D3D)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { sourcesExpanded = !sourcesExpanded }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (sourcesExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = if (sourcesExpanded) "Collapse sources" else "Expand sources",
                                    tint = Color(0xFFBDBDBD),
                                    modifier = Modifier.size(17.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Sources (${knowledgeSources.size})",
                                    color = Color(0xFFBDBDBD),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                // Pramaan's proof check: statements of this answer found in these passages.
                                AnswerProof.parseMarker(sourceIds)?.let { (found, total) ->
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (found == total) "✓ $found of $total statements matched — not clinical verification"
                                        else "$found of $total statements matched — not clinical verification — check the rest",
                                        color = if (found == total) Color(0xFF81C995) else Color(0xFFFFD8A8),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                            if (sourcesExpanded) {
                                HorizontalDivider(color = Color(0x22FFFFFF))
                                Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                                    knowledgeSources.forEachIndexed { index, source ->
                                        val url = Regex("https?://\\S+").find(source)?.value?.trimEnd('|', ',', ')', ']', '.')
                                        val label = if (url != null) source.replace(url, "").trim().trimEnd('|').trim() else source
                                        val passages = sourcePassages.getOrNull(index).orEmpty()
                                        var passageOpen by remember(sourceIds, index) { mutableStateOf(index == 0) }  // first passage open, best sentence highlighted
                                        // Numbered like the [1], [2] markers in the answer text. Tap the row to read the
                                        // passage(s) used, with the sentence that best matches the answer highlighted.
                                        // Tapping the source opens its document at the cited page; without a link,
                                        // tapping shows the passage text that was used.
                                        val target = KnowledgeSourcePresentation.openTarget(source)
                                        Text(
                                            text = "[${index + 1}] ${if (target != null) "↗ " else if (passages.isNotEmpty()) (if (passageOpen) "▾ " else "▸ ") else ""}${label.ifBlank { url.orEmpty() }}",
                                            color = if (target != null) Color(0xFF8AB4F8) else if (passages.isNotEmpty()) Color(0xFFD0D0D0) else Color(0xFF9F9F9F),
                                            fontSize = 10.sp,
                                            lineHeight = 14.sp,
                                            modifier = Modifier
                                                .padding(vertical = 3.dp)
                                                .then(
                                                    when {
                                                        target != null -> Modifier.clickable { runCatching { uriHandler.openUri(target) } }
                                                        passages.isNotEmpty() -> Modifier.clickable { passageOpen = !passageOpen }
                                                        else -> Modifier
                                                    }
                                                )
                                        )
                                        if (target != null && passages.isNotEmpty()) {
                                            Text(
                                                if (passageOpen) "▾ Hide passage used" else "▸ Show passage used",
                                                color = Color(0xFFBDBDBD), fontSize = 10.sp,
                                                modifier = Modifier.padding(start = 12.dp, bottom = 2.dp).clickable { passageOpen = !passageOpen }
                                            )
                                        }
                                        if (passageOpen) {
                                            passages.forEach { passage ->
                                                Surface(
                                                    color = Color(0xFF1E1E1E),
                                                    shape = RoundedCornerShape(8.dp),
                                                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 2.dp, bottom = 4.dp)
                                                ) {
                                                    // Every sentence the answer's statements come from is marked.
                                                    val shown = buildAnnotatedString {
                                                        val ranges = passage.highlights.mapNotNull { h ->
                                                            val at = passage.text.indexOf(h)
                                                            if (at < 0) null else at until at + h.length
                                                        }.sortedBy { it.first }
                                                        var pos = 0
                                                        for (r in ranges) {
                                                            if (r.first < pos) continue
                                                            append(passage.text.substring(pos, r.first))
                                                            withStyle(SpanStyle(background = Color(0xFF5A4A12), color = Color(0xFFFFF3C4))) {
                                                                append(passage.text.substring(r.first, r.last + 1))
                                                            }
                                                            pos = r.last + 1
                                                        }
                                                        append(passage.text.substring(pos))
                                                    }
                                                    Text(shown, color = Color(0xFFBDBDBD), fontSize = 10.sp, lineHeight = 14.sp,
                                                        modifier = Modifier.padding(8.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


private sealed class KnowledgeRenderBlock {
    data class Paragraph(val text: String) : KnowledgeRenderBlock()
    data class Table(val rows: List<List<String>>) : KnowledgeRenderBlock()
}

private fun parseKnowledgeRenderBlocks(text: String): List<KnowledgeRenderBlock> {
    val lines = text.lines()
    val blocks = mutableListOf<KnowledgeRenderBlock>()
    val paragraph = mutableListOf<String>()

    fun flushParagraph() {
        val value = paragraph.joinToString("\n").trim()
        if (value.isNotBlank()) blocks += KnowledgeRenderBlock.Paragraph(value)
        paragraph.clear()
    }

    fun cells(line: String): List<String> = line.trim().trim('|')
        .split('|').map { it.trim() }

    fun isTableLine(line: String): Boolean = line.count { it == '|' } >= 2
    fun isSeparator(row: List<String>): Boolean = row.isNotEmpty() && row.all { cell ->
        val c = cell.trim()
        c.isNotBlank() && c.all { it == '-' || it == ':' || it.isWhitespace() }
    }

    var i = 0
    while (i < lines.size) {
        if (isTableLine(lines[i])) {
            val tableLines = mutableListOf<String>()
            var j = i
            while (j < lines.size && isTableLine(lines[j])) {
                tableLines += lines[j]
                j++
            }
            val parsed = tableLines.map(::cells).filter { it.size >= 2 }
            val dataRows = parsed.filterNot(::isSeparator)
            if (dataRows.size >= 2) {
                flushParagraph()
                blocks += KnowledgeRenderBlock.Table(dataRows)
                i = j
                continue
            }
        }
        paragraph += lines[i]
        i++
    }
    flushParagraph()
    return blocks.ifEmpty { listOf(KnowledgeRenderBlock.Paragraph(text)) }
}



private fun normalizeAssistantDisplayText(raw: String): String {
    if (raw.isBlank()) return raw
    return raw
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        // Some local generations surface escaped newline control text literally.
        // Normalize only the presentation copy; accepted/model text is untouched.
        .replace("\\n", "\n")
        .replace(Regex("(?i)(?:/n\\s*){2,}"), "\n\n")
        .replace(Regex("(?i)(?<![A-Za-z0-9])/n(?=\\s|[-*#>0-9]|$)"), "\n")
        .replace(Regex("[ \t]+\n"), "\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

private fun markdownLiteForDisplay(raw: String): String {
    val normalized = normalizeAssistantDisplayText(raw)
    if (normalized.isBlank()) return normalized

    return normalized.lineSequence().joinToString("\n") { original ->
        var line = original.trimEnd()

        // Remove markdown heading markers while preserving the heading on its own line.
        line = line.replace(Regex("^\\s{0,3}#{1,6}\\s+"), "")

        // Convert markdown list markers to a stable visible bullet that works during streaming.
        line = line.replace(Regex("^(\\s*)[*+-]\\s+")) { m ->
            m.groupValues[1] + "• "
        }

        // Hide markdown emphasis delimiters. During a live stream one side of the
        // delimiter may arrive before the other; removing them keeps the UI clean.
        line = line.replace("**", "")
            .replace("__", "")

        line
    }
}

@Composable
private fun AssistantAnswerBody(text: String, knowledgeUsed: Boolean) {
    if (knowledgeUsed || text.contains("| --- |")) {
        KnowledgeAnswerBody(text)
        return
    }

    val displayText = remember(text) { markdownLiteForDisplay(text) }
    Text(
        text = displayText,
        color = Color(0xFFF1F1F1),
        fontSize = 15.sp,
        lineHeight = 22.sp
    )
}

@Composable
private fun KnowledgeAnswerBody(text: String) {
    val blocks = remember(text) { parseKnowledgeRenderBlocks(text) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is KnowledgeRenderBlock.Paragraph -> Text(
                    text = block.text,
                    color = Color(0xFFF1F1F1),
                    fontSize = 15.sp,
                    lineHeight = 22.sp
                )
                is KnowledgeRenderBlock.Table -> {
                    val columnCount = block.rows.maxOfOrNull { it.size } ?: 0
                    if (columnCount > 0) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .border(1.dp, Color(0xFF444444), RoundedCornerShape(8.dp))
                        ) {
                            block.rows.forEachIndexed { rowIndex, row ->
                                Row(
                                    modifier = Modifier
                                        .background(if (rowIndex == 0) Color(0xFF2B2B2B) else Color.Transparent)
                                ) {
                                    repeat(columnCount) { columnIndex ->
                                        val value = row.getOrNull(columnIndex).orEmpty()
                                        Text(
                                            text = value,
                                            color = Color(0xFFEAEAEA),
                                            fontSize = 11.sp,
                                            lineHeight = 15.sp,
                                            fontWeight = if (rowIndex == 0) FontWeight.SemiBold else FontWeight.Normal,
                                            modifier = Modifier
                                                .widthIn(min = 105.dp, max = 220.dp)
                                                .border(0.5.dp, Color(0xFF3C3C3C))
                                                .padding(horizontal = 8.dp, vertical = 7.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FixSightBottomComposer(
    modifier: Modifier = Modifier,
    sessionActive: Boolean,
    appState: AppState,
    listening: Boolean,
    isVisionMode: Boolean,
    troubleshootingMode: Boolean,
    composerText: String,
    pendingAttachmentLabel: String,
    attachmentPreparing: Boolean,
    attachmentMenuOpen: Boolean,
    onComposerTextChange: (String) -> Unit,
    onAttachmentMenuChange: (Boolean) -> Unit,
    onStartSession: () -> Unit,
    onSubmitText: () -> Unit,
    onAttachFile: () -> Unit,
    onCapturePhoto: () -> Unit,
    onClearAttachment: () -> Unit,
    onToggleVisionMode: (Boolean) -> Unit,
    onToggleVoiceInput: () -> Unit,
    onStartTroubleshooting: () -> Unit
) {
    Surface(color = Color(0xFF212121), modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)) {
            if (pendingAttachmentLabel.isNotBlank()) {
                Surface(color = Color(0xFF303030), shape = RoundedCornerShape(14.dp), modifier = Modifier.padding(start = 6.dp, bottom = 7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 7.dp, bottom = 7.dp)) {
                        Text("📎", fontSize = 12.sp); Spacer(Modifier.width(7.dp))
                        Text(pendingAttachmentLabel, color = Color(0xFFE4E4E4), fontSize = 12.sp, maxLines = 1,
                            modifier = Modifier.widthIn(max = 230.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("✕", color = if (attachmentPreparing) Color(0xFF666666) else Color(0xFFBEBEBE), fontSize = 12.sp,
                            modifier = Modifier.clickable(enabled = !attachmentPreparing, onClick = onClearAttachment).padding(6.dp))
                    }
                }
            }

            val canType = appState != AppState.THINKING && !attachmentPreparing
            val canUseTools = appState != AppState.THINKING && !attachmentPreparing

            Surface(color = Color(0xFF303030), shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(start = 7.dp, end = 7.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.Bottom) {
                        Box {
                            Surface(color = Color.Transparent, shape = CircleShape,
                                modifier = Modifier.size(42.dp).clickable(enabled = canUseTools) { onAttachmentMenuChange(true) }) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("＋", color = if (canUseTools) Color(0xFFE7E7E7) else Color(0xFF777777), fontSize = 24.sp)
                                }
                            }
                            DropdownMenu(
                                expanded = attachmentMenuOpen,
                                onDismissRequest = { onAttachmentMenuChange(false) }
                            ) {
                                if (!troubleshootingMode) {
                                    DropdownMenuItem(
                                        text = { Text(if (isVisionMode) "Close Live Camera" else "Live Camera", color = Color.White) },
                                        onClick = { onAttachmentMenuChange(false); onToggleVisionMode(!isVisionMode) }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Take photo", color = Color.White) }, enabled = !isVisionMode,
                                    onClick = { onAttachmentMenuChange(false); onCapturePhoto() }
                                )
                                if (!troubleshootingMode) {
                                    DropdownMenuItem(
                                        text = { Text("Start troubleshooting", color = Color.White) },
                                        onClick = { onAttachmentMenuChange(false); onStartTroubleshooting() }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Attach file or image", color = Color.White) },
                                    onClick = { onAttachmentMenuChange(false); onAttachFile() }
                                )
                                // Visit Note: say or type what happened at the visit; Pramaan checks and saves it.
                                DropdownMenuItem(
                                    text = { Text("Visit note", color = Color.White) },
                                    onClick = {
                                        onAttachmentMenuChange(false)
                                        if (!VisitNote.isRequest(composerText)) onComposerTextChange(VisitNote.PREFIX_EN + composerText.trimStart())
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Visits due", color = Color.White) },
                                    onClick = { onAttachmentMenuChange(false); onComposerTextChange("Which visits are due?") }
                                )
                            }
                        }

                        // troubleshooting_camera_compact: keep Live Camera beside the
                        // mic/send controls so troubleshooting does not consume a
                        // separate row or alter the normal composer layout.
                        if (troubleshootingMode) {
                            Surface(
                                color = if (isVisionMode) Color(0xFF3E5668) else Color.Transparent,
                                shape = CircleShape,
                                modifier = Modifier.size(38.dp).clickable(
                                    enabled = canUseTools,
                                    onClick = { onToggleVisionMode(!isVisionMode) }
                                )
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "📷",
                                        color = if (canUseTools) Color(0xFFF2F2F2) else Color(0xFF777777),
                                        fontSize = 16.sp
                                    )
                                }
                            }
                        }

                        TextField(
                            value = composerText,
                            onValueChange = onComposerTextChange,
                            placeholder = { Text(if (isVisionMode) "Ask about what you see" else "Ask anything",
                                color = Color(0xFFAAAAAA), fontSize = 15.sp) },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 128.dp),
                            enabled = canType,
                            maxLines = 5,
                            textStyle = LocalTextStyle.current.copy(color = Color(0xFFF5F5F5), fontSize = 15.sp, lineHeight = 20.sp),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                                focusedTextColor = Color(0xFFF5F5F5),
                                unfocusedTextColor = Color(0xFFF5F5F5),
                                disabledTextColor = Color(0xFF8A8A8A),
                                cursorColor = Color.White
                            )
                        )

                        if (composerText.isNotBlank()) {
                            Surface(color = if (canType) Color(0xFFF2F2F2) else Color(0xFF707070), shape = CircleShape,
                                modifier = Modifier.size(38.dp).clickable(enabled = canType, onClick = onSubmitText)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("↑", color = Color(0xFF242424), fontSize = 19.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        } else {
                            Surface(color = if (listening) Color(0xFFF2F2F2) else Color.Transparent, shape = CircleShape,
                                modifier = Modifier.size(42.dp).clickable(enabled = listening || canUseTools, onClick = onToggleVoiceInput)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(if (listening) "■" else "🎙",
                                        color = if (listening) Color(0xFF202020) else Color(0xFFF2F2F2),
                                        fontSize = if (listening) 14.sp else 17.sp)
                                }
                            }
                        }
                    }
                }
                Text(
                    when {
                        troubleshootingMode && listening -> "Troubleshooting • Recording… tap the mic to stop"
                        troubleshootingMode && appState == AppState.SPEAKING -> "Troubleshooting • Speaking"
                        troubleshootingMode && appState == AppState.THINKING -> "Troubleshooting • Thinking"
                        listening -> "Listening… tap the mic to stop"
                        appState == AppState.THINKING -> "Pramaan is generating a response"
                        isVisionMode -> "Live Camera is active"
                        else -> ""
                    },
                    color = Color(0xFF7F7F7F), fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp)
                )
        }
    }
}

@Composable
private fun FixSightNotebookDialog(
    notebook: InvestigationNotebook,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF111821),
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.78f)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Investigation Notes",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "✕",
                        color = Color(0xFFB8B8C0),
                        fontSize = 16.sp,
                        modifier = Modifier.clickable(onClick = onDismiss).padding(6.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (notebook.problem.isNotBlank())
                        NotebookDialogSection("Problem", listOf(notebook.problem), Color(0xFFFFD740))
                    if (notebook.currentGoal.isNotBlank())
                        NotebookDialogSection("Current Goal", listOf(notebook.currentGoal), Color(0xFF80D8FF))
                    val theories = listOfNotNull(
                        notebook.primaryTheory.takeIf { it.isNotBlank() },
                        notebook.secondaryTheory.takeIf { it.isNotBlank() && it != notebook.primaryTheory }
                    )
                    if (theories.isNotEmpty())
                        NotebookDialogSection("Theories", theories, Color(0xFF40C4FF))
                    if (notebook.confirmedFacts.isNotEmpty())
                        NotebookDialogSection("Confirmed Facts", notebook.confirmedFacts, Color(0xFF69F0AE))
                    if (notebook.visualFindings.isNotEmpty())
                        NotebookDialogSection("Visual Findings", notebook.visualFindings, Color(0xFFE6A4FF))
                    if (notebook.checksPerformed.isNotEmpty())
                        NotebookDialogSection("Checks Performed", notebook.checksPerformed, Color(0xFFFFD740))
                    if (notebook.actionsSuggested.isNotEmpty())
                        NotebookDialogSection("Actions Suggested", notebook.actionsSuggested, Color(0xFF80D8FF))
                    if (notebook.rejectedTheories.isNotEmpty())
                        NotebookDialogSection("Ruled Out", notebook.rejectedTheories, Color(0xFFFF8A80))
                }
            }
        }
    }
}

    // Session UI composables retained as presentation only.
    // Notebook/RCA reasoning remains dormant in the AndroLLM baseline.

@Composable
private fun NotebookDialogSection(title: String, items: List<String>, bulletColor: Color) {
    Spacer(Modifier.height(8.dp))
    Text(title, color = Color(0xFF9E9E9E), fontSize = 10.sp,
        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(3.dp))
    items.forEach { item ->
        Row(modifier = Modifier.padding(start = 8.dp, top = 2.dp)) {
            Text("• ", color = bulletColor, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Text(item, color = Color(0xFFE0E0E0), fontSize = 11.sp,
                fontFamily = FontFamily.Monospace, lineHeight = 15.sp)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Live troubleshooting step tracker — shown in HUD during session
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun TroubleshootingStepTracker(
    steps: List<Pair<String, String>>,   // (userSaid, aiReply)
    appState: AppState
) {
    val scrollState = rememberScrollState()
    // Auto-scroll to bottom when new step added
    LaunchedEffect(steps.size) { scrollState.animateScrollTo(scrollState.maxValue) }

    Surface(
        color = Color(0xFF0A0F1A).copy(alpha = 0.92f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "🔍 Steps",
                    color = Color(0xFF80D8FF),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${steps.size} turn${if (steps.size != 1) "s" else ""}",
                    color = Color(0xFF555577),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.height(6.dp))

            Column(modifier = Modifier.verticalScroll(scrollState)) {
                steps.forEachIndexed { index, (userSaid, aiReply) ->
                    StepRow(
                        number   = index + 1,
                        userSaid = userSaid,
                        aiReply  = aiReply,
                        isLast   = index == steps.lastIndex,
                        appState = appState
                    )
                    if (index < steps.lastIndex) {
                        // Connector line
                        Box(
                            modifier = Modifier
                                .padding(start = 11.dp)
                                .width(1.5.dp)
                                .height(6.dp)
                                .background(Color(0xFF222244))
                        )
                    }
                }

                // "Thinking…" indicator for current in-progress turn
                if (appState == AppState.THINKING || appState == AppState.SPEAKING) {
                    Box(
                        modifier = Modifier
                            .padding(start = 11.dp)
                            .width(1.5.dp)
                            .height(6.dp)
                            .background(Color(0xFF222244))
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(Color(0xFFFFD740), CircleShape)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (appState == AppState.THINKING) "Analysing…" else "Responding…",
                            color = Color(0xFFFFD740),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepRow(
    number: Int,
    userSaid: String,
    aiReply: String,
    isLast: Boolean,
    appState: AppState
) {
    Row(verticalAlignment = Alignment.Top) {
        // Step number circle
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(
                    if (isLast) Color(0xFF1A3A5C) else Color(0xFF111133),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "$number",
                color = if (isLast) Color(0xFF80D8FF) else Color(0xFF444466),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width(7.dp))
        Column(modifier = Modifier.weight(1f)) {
            // User utterance
            Text(
                text = "You: ${userSaid.take(55)}${if (userSaid.length > 55) "…" else ""}",
                color = Color(0xFF9E9E9E),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(2.dp))
            // AI reply
            Text(
                text = aiReply.take(75) + if (aiReply.length > 75) "…" else "",
                color = if (isLast) Color.White else Color(0xFF777799),
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }
    }
}



// ─────────────────────────────────────────────────────────────────────────────
// RCA Report Dialog — full step-by-step troubleshooting timeline
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun RcaReportDialog(
    report: RCAReport,
    onSharePdf: (Boolean) -> Unit,
    onSavePdf: (Boolean) -> Unit,
    onShareSummary: () -> Unit,
    onDismiss: (startNew: Boolean) -> Unit
) {
    var includeTranscript by remember { mutableStateOf(false) }
    var showTechnicalRca by remember { mutableStateOf(false) }
    var expandedStep by remember { mutableStateOf<Int?>(null) }

    Dialog(
        onDismissRequest = { onDismiss(false) },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = Color(0xFF081421),
            modifier = Modifier.fillMaxSize()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Full-width report header. Actions are icon-only so long labels can never
                // squeeze or clip the report title on narrow phones.
                Surface(color = Color(0xFF0D1B2E), tonalElevation = 3.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { onDismiss(false) }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Close report", tint = Color.White)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Session Report",
                                color = Color.White,
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "${report.totalTurns} turns  •  ${formatSessionDuration(report.sessionDurationMs)}",
                                color = Color(0xFF9AA9B8),
                                fontSize = 11.sp
                            )
                        }
                        ReportModeIcon(
                            selected = !showTechnicalRca,
                            icon = Icons.Default.Summarize,
                            contentDescription = "Session summary",
                            onClick = { showTechnicalRca = false }
                        )
                        ReportModeIcon(
                            selected = showTechnicalRca,
                            icon = Icons.Default.Timeline,
                            contentDescription = "Root cause analysis",
                            onClick = { showTechnicalRca = true }
                        )
                    }
                }

                // The selected mode remains named in the content so icons do not sacrifice clarity.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0A1828))
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (showTechnicalRca) Icons.Default.Timeline else Icons.Default.Summarize,
                        contentDescription = null,
                        tint = Color(0xFF80D8FF),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (showTechnicalRca) "Root Cause Analysis" else "Session Summary",
                        color = Color(0xFF80D8FF),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.weight(1f))
                    if (report.sessionStatus.isNotBlank()) {
                        ReportChip(report.sessionStatus, Color(0xFFFFD740))
                    }
                }

                if (!showTechnicalRca) {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (report.problemStatement.isNotBlank()) {
                            item {
                                ReportSectionCard(
                                    title = "Problem",
                                    icon = Icons.Default.ReportProblem,
                                    accent = Color(0xFFFFD740)
                                ) {
                                    Text(report.problemStatement, color = Color.White, fontSize = 13.sp, lineHeight = 19.sp)
                                }
                            }
                        }

                        if (report.resolution.isNotBlank()) {
                            item {
                                ReportSectionCard(
                                    title = "Resolution achieved",
                                    icon = Icons.Default.CheckCircle,
                                    accent = Color(0xFF69F0AE)
                                ) {
                                    Text(report.resolution, color = Color(0xFFE8FFF1), fontSize = 13.sp, lineHeight = 19.sp)
                                }
                            }
                        }

                        item {
                            ReportSectionCard(
                                title = "Final assessment",
                                icon = Icons.Default.TaskAlt,
                                accent = Color(0xFF69F0AE)
                            ) {
                                Text(
                                    report.outcome.ifBlank { "No final assessment was recorded." },
                                    color = Color(0xFFE8FFF1),
                                    fontSize = 13.sp,
                                    lineHeight = 19.sp
                                )
                                if (report.rootCauseConfidence.isNotBlank()) {
                                    Spacer(Modifier.height(8.dp))
                                    ReportChip("Confidence: ${report.rootCauseConfidence}", Color(0xFF69F0AE))
                                }
                            }
                        }

                        if (report.confirmedFacts.isNotEmpty()) {
                            item {
                                ReportSectionCard(
                                    title = "Key findings",
                                    icon = Icons.Default.FactCheck,
                                    accent = Color(0xFF80D8FF)
                                ) {
                                    report.confirmedFacts.forEach { ReportBullet(it) }
                                }
                            }
                        }

                        if (report.actionsTaken.isNotEmpty()) {
                            item {
                                ReportSectionCard(
                                    title = "Actions / recommendations",
                                    icon = Icons.Default.BuildCircle,
                                    accent = Color(0xFF40C4FF)
                                ) {
                                    report.actionsTaken.forEachIndexed { index, action ->
                                        ReportNumberedItem(index + 1, action)
                                    }
                                }
                            }
                        }

                        if (report.recommendedFollowUp.isNotBlank()) {
                            item {
                                ReportSectionCard(
                                    title = "Next step",
                                    icon = Icons.Default.ArrowCircleRight,
                                    accent = Color(0xFFFFD740)
                                ) {
                                    Text(report.recommendedFollowUp, color = Color(0xFFE6E6EE), fontSize = 13.sp, lineHeight = 19.sp)
                                }
                            }
                        }

                        if (report.steps.isNotEmpty()) {
                            item {
                                Text(
                                    "Conversation overview",
                                    color = Color(0xFF80D8FF),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                                )
                            }
                            report.steps.forEach { step ->
                                item(key = "summary_step_${step.stepNumber}") {
                                    RCAStepCard(
                                        step = step,
                                        expanded = expandedStep == step.stepNumber,
                                        onToggle = {
                                            expandedStep = if (expandedStep == step.stepNumber) null else step.stepNumber
                                        }
                                    )
                                }
                            }
                        }

                        item { TranscriptPdfOption(includeTranscript) { includeTranscript = it } }
                        item { Spacer(Modifier.height(8.dp)) }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            ReportSectionCard(
                                title = "Root cause",
                                icon = Icons.Default.AccountTree,
                                accent = Color(0xFF69F0AE)
                            ) {
                                Text(
                                    report.rootCause.ifBlank { "No root cause was confirmed during this session." },
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    lineHeight = 19.sp
                                )
                                if (report.rootCauseConfidence.isNotBlank()) {
                                    Spacer(Modifier.height(8.dp))
                                    ReportChip("Confidence: ${report.rootCauseConfidence}", Color(0xFFFFD740))
                                }
                            }
                        }

                        if (report.resolution.isNotBlank()) {
                            item {
                                ReportSectionCard("Resolution", Icons.Default.CheckCircle, Color(0xFF69F0AE)) {
                                    Text(report.resolution, color = Color(0xFFE8FFF1), fontSize = 13.sp, lineHeight = 19.sp)
                                }
                            }
                        }

                        if (report.checksPerformed.isNotEmpty()) {
                            item {
                                ReportSectionCard("Checks performed", Icons.Default.Checklist, Color(0xFF80D8FF)) {
                                    report.checksPerformed.forEach { ReportBullet(it) }
                                }
                            }
                        }

                        if (report.rejectedTheories.isNotEmpty()) {
                            item {
                                ReportSectionCard("Ruled out", Icons.Default.RemoveCircleOutline, Color(0xFFFF8A80)) {
                                    report.rejectedTheories.forEach { ReportBullet(it) }
                                }
                            }
                        }

                        if (report.visualFindings.isNotEmpty()) {
                            item {
                                ReportSectionCard("Visual / AR evidence", Icons.Default.Visibility, Color(0xFFE6A4FF)) {
                                    report.visualFindings.forEach { ReportBullet(it) }
                                }
                            }
                        }

                        if (report.sourcesUsed.isNotEmpty()) {
                            item {
                                ReportSectionCard("Sources & AI providers", Icons.Default.Hub, Color(0xFF40C4FF)) {
                                    report.sourcesUsed.forEach { source ->
                                        ReportChip(source, Color(0xFF80D8FF))
                                        Spacer(Modifier.height(5.dp))
                                    }
                                }
                            }
                        }

                        if (report.steps.isNotEmpty()) {
                            item {
                                Text(
                                    "Reasoning timeline",
                                    color = Color(0xFF80D8FF),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                                )
                            }
                            report.steps.forEach { step ->
                                item(key = "rca_step_${step.stepNumber}") {
                                    RCAStepCard(
                                        step = step,
                                        expanded = expandedStep == step.stepNumber,
                                        onToggle = {
                                            expandedStep = if (expandedStep == step.stepNumber) null else step.stepNumber
                                        }
                                    )
                                }
                            }
                        }

                        item { TranscriptPdfOption(includeTranscript) { includeTranscript = it } }
                        item { Spacer(Modifier.height(8.dp)) }
                    }
                }

                // Compact action dock: icons replace the old long text buttons.
                Surface(color = Color(0xFF0D1B2E), tonalElevation = 4.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ReportActionIcon(Icons.Default.Share, "Share summary", onShareSummary)
                        ReportActionIcon(Icons.Default.SaveAlt, "Save PDF") { onSavePdf(includeTranscript) }
                        ReportActionIcon(Icons.Default.PictureAsPdf, "Share PDF") { onSharePdf(includeTranscript) }
                        ReportActionIcon(Icons.Default.AddCircleOutline, "Start new session") { onDismiss(true) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportModeIcon(
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Surface(
        color = if (selected) Color(0xFF173A57) else Color.Transparent,
        shape = CircleShape
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(42.dp)) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = if (selected) Color(0xFF80D8FF) else Color(0xFF8FA0B2),
                modifier = Modifier.size(21.dp)
            )
        }
    }
}

@Composable
private fun ReportActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(46.dp)) {
        Icon(icon, contentDescription = contentDescription, tint = Color(0xFFD8E6F3), modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun ReportSectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        color = Color(0xFF0F2033),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color(0xFF1D344B)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(9.dp))
            content()
        }
    }
}

@Composable
private fun ReportChip(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(999.dp)) {
        Text(
            text,
            color = color,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun ReportBullet(text: String) {
    if (text.isBlank()) return
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text("•", color = Color(0xFF80D8FF), fontSize = 13.sp)
        Spacer(Modifier.width(7.dp))
        Text(text, color = Color(0xFFD5DBE3), fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ReportNumberedItem(number: Int, text: String) {
    if (text.isBlank()) return
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Surface(color = Color(0xFF173A57), shape = CircleShape, modifier = Modifier.size(22.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text("$number", color = Color(0xFF80D8FF), fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(text, color = Color(0xFFD5DBE3), fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TranscriptPdfOption(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Surface(color = Color(0xFF0D1B2E), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(!checked) }
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
            Icon(Icons.Default.Article, null, tint = Color(0xFF9AA9B8), modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            Text("Include complete transcript in PDF", color = Color(0xFFB8C2CE), fontSize = 11.sp)
        }
    }
}

@Composable
private fun RCAStepCard(step: RCAStep, expanded: Boolean, onToggle: () -> Unit) {
    val sourceColor = when (step.answeredBy) {
        AnswerSource.CLOUD, AnswerSource.LOCAL_PLUS_CLOUD -> Color(0xFF40C4FF)
        AnswerSource.LOCAL -> Color(0xFF69F0AE)
        AnswerSource.ML_KIT -> Color(0xFFFFD740)
        else -> Color(0xFF9A9AB0)
    }
    val sourceLabel = step.answeredByLabel.ifBlank {
        when (step.answeredBy) {
            AnswerSource.CLOUD -> "Cloud AI"
            AnswerSource.LOCAL -> "Local AI"
            AnswerSource.LOCAL_PLUS_CLOUD -> "Hybrid AI"
            AnswerSource.ML_KIT -> "Camera / ML Kit"
            else -> "Pramaan"
        }
    }
    val answerText = step.summary.ifBlank { step.result }

    Surface(
        color = Color(0xFF0F2033),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0xFF1B3248)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Color(0xFF173A57), shape = CircleShape, modifier = Modifier.size(26.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("${step.stepNumber}", color = Color(0xFF80D8FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(9.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        step.description.ifBlank { "Turn ${step.stepNumber}" },
                        color = Color(0xFFE8EEF5),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = if (expanded) Int.MAX_VALUE else 2
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                        ReportChip(sourceLabel, sourceColor)
                        if (step.visualUsed) ReportChip("Visual", Color(0xFFE6A4FF))
                        if (step.attachmentName.isNotBlank()) ReportChip("Attachment", Color(0xFFFFD740))
                    }
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse turn" else "Expand turn",
                    tint = Color(0xFF8FA0B2)
                )
            }

            if (answerText.isNotBlank()) {
                Spacer(Modifier.height(9.dp))
                Text(
                    if (expanded) answerText else answerText.take(180) + if (answerText.length > 180) "…" else "",
                    color = Color(0xFFC7D0DB),
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }

            if (expanded) {
                if (step.result.isNotBlank() && step.result != answerText) {
                    Spacer(Modifier.height(10.dp))
                    Text("Result", color = Color(0xFF80D8FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(3.dp))
                    Text(step.result, color = Color(0xFFBFC8D3), fontSize = 11.sp, lineHeight = 17.sp)
                }
                if (step.source.isNotBlank() || step.sopId.isNotBlank() || step.attachmentName.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = Color(0xFF1D344B))
                    Spacer(Modifier.height(8.dp))
                    if (step.source.isNotBlank()) Text("Source: ${step.source}", color = Color(0xFF8FA0B2), fontSize = 10.sp)
                    if (step.sopId.isNotBlank()) Text("SOP: ${step.sopId}", color = Color(0xFF8FA0B2), fontSize = 10.sp)
                    if (step.attachmentName.isNotBlank()) Text("Attachment: ${step.attachmentName}", color = Color(0xFF8FA0B2), fontSize = 10.sp)
                }
            }
        }
    }
}

private fun formatSessionDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}

// ─────────────────────────────────────────────────────────────────────────────
// NotebookPanel — live investigation state, toggled from HUD top bar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun NotebookPanel(notebook: InvestigationNotebook) {
    Surface(
        color = Color(0xFF0D1B2A).copy(alpha = 0.95f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp).verticalScroll(rememberScrollState())
        ) {
            Text(
                "📋  Notebook",
                color = Color(0xFF80D8FF),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(5.dp))

            NotebookRow("Goal",   notebook.currentGoal)
            NotebookRow("Theory", notebook.primaryTheory)
            if (notebook.secondaryTheory.isNotBlank())
                NotebookRow("Alt", notebook.secondaryTheory)

            if (notebook.confirmedFacts.isNotEmpty()) {
                NotebookSectionHeader("Facts")
                notebook.confirmedFacts.takeLast(4).forEach { NotebookBullet(it, Color(0xFF69F0AE)) }
            }
            if (notebook.checksPerformed.isNotEmpty()) {
                NotebookSectionHeader("Tried")
                notebook.checksPerformed.takeLast(3).forEach { NotebookBullet(it, Color(0xFFFFD740)) }
            }
            if (notebook.actionsSuggested.isNotEmpty()) {
                NotebookSectionHeader("Actions")
                notebook.actionsSuggested.takeLast(3).forEach { NotebookBullet(it, Color(0xFF40C4FF)) }
            }
            if (notebook.rejectedTheories.isNotEmpty()) {
                NotebookSectionHeader("Ruled Out")
                notebook.rejectedTheories.takeLast(2).forEach { NotebookBullet(it, Color(0xFFFF5252)) }
            }
        }
    }
}

@Composable
private fun NotebookRow(label: String, value: String) {
    if (value.isBlank() || value .isBlank()) return
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text("$label: ", color = Color(0xFF9E9E9E), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text(value.take(70), color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun NotebookSectionHeader(text: String) {
    Spacer(Modifier.height(4.dp))
    Text(text, color = Color(0xFF9E9E9E), fontSize = 9.sp,
        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
}

@Composable
private fun NotebookBullet(text: String, color: Color) {
    Row(modifier = Modifier.padding(start = 8.dp, top = 1.dp)) {
        Text("• ", color = color, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text(text.take(65), color = Color(0xFFE0E0E0), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}




@Composable
fun InvestigationProgressHUD(progress: InvestigationProgress) {
    Surface(color = Color(0xFF0A0F1A).copy(alpha = 0.92f), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Investigation Progress", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            ProgressCheckItem("Problem understood", progress.problemUnderstood)
            ProgressCheckItem("Initial evidence", progress.initialEvidenceCollected)
            ProgressCheckItem("Theory formed", progress.theoryFormed)
            ProgressCheckItem("Cause suspected", progress.rootCauseSuspected)
            ProgressCheckItem("Cause confirmed", progress.rootCauseConfirmed)
            ProgressCheckItem("Solution verified", progress.solutionValidated)
        }
    }
}

@Composable
fun ProgressCheckItem(label: String, checked: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Text(if (checked) "✅" else "⭕", fontSize = 14.sp, modifier = Modifier.padding(end = 8.dp))
        Text(label, color = if (checked) Color.White else Color(0xFF666666), fontSize = 13.sp)
    }
}

@Composable
fun IssueResolvedDialog(onContinue: () -> Unit, onEndSession: () -> Unit) {
    Dialog(onDismissRequest = onContinue) {
        Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFF0D1B2E),
            border = BorderStroke(2.dp, Color(0xFF00E676)), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(modifier = Modifier.size(56.dp).background(Color(0xFF00E676), CircleShape),
                    contentAlignment = Alignment.Center) {
                    Text("✓", color = Color.Black, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(16.dp))
                Text("✅ ISSUE RESOLVED", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("You confirmed the issue is resolved. Would you like to continue troubleshooting or end this session and finalize the RCA?",
                    color = Color(0xFF9E9E9E), fontSize = 14.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                Button(onClick = onContinue, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C4DFF)),
                    modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp)) {
                    Text("CONTINUE TROUBLESHOOTING", color = Color.White)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onEndSession, modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp)) { Text("END SESSION & FINALIZE RCA", color = Color(0xFF9E9E9E)) }
            }
        }
    }
}

@Composable
fun ARToolbar(selectedTool: ARTool, selectedColor: ARColor,
    onToolSelected: (ARTool) -> Unit, onColorSelected: (ARColor) -> Unit) {
    Surface(color = Color.Black.copy(alpha = 0.85f), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ARToolChip("BOX", ARTool.BOX, selectedTool, onToolSelected)
                ARToolChip("ARROW", ARTool.ARROW, selectedTool, onToolSelected)
                ARToolChip("TEXT", ARTool.TEXT, selectedTool, onToolSelected)
                ARToolChip("CARD", ARTool.INSTRUCTION_CARD, selectedTool, onToolSelected)
                ARToolChip("AI MARK", ARTool.AI_MARKER, selectedTool, onToolSelected)
            }
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ARColorDot(Color.Green, ARColor.GREEN, selectedColor, onColorSelected)
                ARColorDot(Color.Yellow, ARColor.YELLOW, selectedColor, onColorSelected)
                ARColorDot(Color.Red, ARColor.RED, selectedColor, onColorSelected)
                ARColorDot(Color.Blue, ARColor.BLUE, selectedColor, onColorSelected)
            }
        }
    }
}

@Composable
fun ARToolChip(label: String, tool: ARTool, selected: ARTool, onSelect: (ARTool) -> Unit) {
    Surface(color = if (tool == selected) Color(0xFF6200EE) else Color(0xFF1A1A2E),
        shape = RoundedCornerShape(8.dp), modifier = Modifier.clickable { onSelect(tool) }.padding(2.dp)) {
        Text(label, color = if (tool == selected) Color.White else Color(0xFF9E9E9E),
            fontSize = 11.sp, fontWeight = if (tool == selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

@Composable
fun ARColorDot(color: Color, arColor: ARColor, selected: ARColor, onSelect: (ARColor) -> Unit) {
    Box(modifier = Modifier.size(28.dp).clip(CircleShape).background(color)
        .then(if (arColor == selected) Modifier.border(2.dp, Color.White, CircleShape) else Modifier)
        .clickable { onSelect(arColor) })
}
}
