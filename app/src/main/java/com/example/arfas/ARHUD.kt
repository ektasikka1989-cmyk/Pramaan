package com.example.arfas

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.viewinterop.AndroidView
import android.view.TextureView
import com.google.ar.core.Config
import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.sceneview.ar.ARSceneView
import com.google.ar.core.Frame
import com.google.ar.core.Session
import kotlinx.coroutines.delay

// ─────────────────────────────────────────────────────────────────────────────
// AR + HUD view
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ARHUDView(
    modifier: Modifier = Modifier,
    isVisionMode: Boolean,
    arGuidanceActive: Boolean,
    onFrame: (Session, Frame) -> Unit
) {
    // FIX #3: Check CAMERA permission before handing control to ARSceneView.
    // ARCore's ACameraManager calls getDeviceIdsForUid on init; if the permission
    // was revoked or not yet granted (e.g. after a crash restart), it fails 5× and
    // causes AppOps "Operation not started" errors on cleanup.
    val context = LocalContext.current
    // ARCore remains the single Live Video camera owner on supported devices so a fresh
    // frame can be captured/localized without camera hand-off races. arGuidanceActive
    // controls marker/guidance UI state in MainActivity; it intentionally does not swap
    // camera backends mid-session. Read it here so that contract is explicit.
    @Suppress("UNUSED_VARIABLE") val guidanceState = arGuidanceActive
    val cameraGranted = ContextCompat.checkSelfPermission(
        context, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED
    var sceneSize by remember { mutableStateOf(IntSize.Zero) }
    // Three-state backend decision. UNKNOWN_CHECKING is not treated as ready: it gets
    // a short bounded re-check window, then safely falls back to Camera2. A later
    // SceneView session failure also flips this state to FALLBACK.
    var arCoreReadiness by remember(context, isVisionMode) {
        mutableStateOf(if (isVisionMode) ARController.arCoreReadiness() else ArCoreReadiness.FALLBACK)
    }
    LaunchedEffect(isVisionMode, cameraGranted) {
        if (!isVisionMode || !cameraGranted) return@LaunchedEffect
        arCoreReadiness = ARController.arCoreReadiness()
        if (arCoreReadiness == ArCoreReadiness.CHECKING) {
            repeat(6) {
                delay(200L)
                val next = ARController.arCoreReadiness()
                if (next != ArCoreReadiness.CHECKING) {
                    arCoreReadiness = next
                    return@LaunchedEffect
                }
            }
            Log.w("AR_DEBUG_TRACE", "AR_AVAILABILITY_TIMEOUT action=CAMERA2_FALLBACK")
            arCoreReadiness = ArCoreReadiness.FALLBACK
        }
    }

    DisposableEffect(isVisionMode) {
        if (isVisionMode) ARController.beginLiveVideoGeneration()
        onDispose {
            // SceneView/ARCore native objects must never survive the Live Video surface.
            ARController.clearState()
            VisualEngine.stopPeriodicCleanup()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { sceneSize = it; ARController.updateViewport(it.width, it.height) }
    ) {
        if (isVisionMode && cameraGranted && arCoreReadiness == ArCoreReadiness.READY) {
            SideEffect { ARController.useArCoreBackend() }
            // Phase 19: Live Video has exactly one camera owner. ARCore/SceneView is
            // created only while Live Video is enabled; audio-only/idle sessions stay
            // completely AR-free. Plane detection remains enabled internally for
            // hit-testing, but plane rendering is disabled so users never see the
            // white SceneView plane mesh.
            val depthMode = remember(context) {
                // Keep Depth disabled for the Live-assist camera path. The device logs
                // show repeated ARCore motion-stereo/spherical-rectifier failures while
                // Gemma GPU vision is active. Plane/Point/InstantPlacement are sufficient
                // for FixSight anchoring and avoid competing depth work on the GPU.
                Config.DepthMode.DISABLED
            }
            ARSceneView(
                modifier = Modifier.fillMaxSize(),
                planeRenderer = false,
                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL,
                depthMode = depthMode,
                instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP,
                focusMode = Config.FocusMode.AUTO,
                sessionConfiguration = { session, config ->
                    // Keep the raw callback aligned with the typed parameters. The
                    // typed parameters are important because SceneView's own defaults
                    // otherwise set Instant Placement to DISABLED.
                    config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    // Do not enable ARCore Depth merely because the device advertises
                    // support. FixSight uses Plane/Point/InstantPlacement for world
                    // anchoring; disabling Depth removes the failing motion-stereo path
                    // observed on-device and reduces contention with local Gemma vision.
                    config.depthMode = Config.DepthMode.DISABLED
                    config.instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
                    config.focusMode = Config.FocusMode.AUTO
                },
                onSessionFailed = { exception ->
                    Log.e(
                        "AR_DEBUG_TRACE",
                        "AR_SESSION_FAILED action=CAMERA2_FALLBACK ${exception.javaClass.simpleName}:${exception.message}"
                    )
                    arCoreReadiness = ArCoreReadiness.FALLBACK
                },
                onSessionUpdated = { session, frame ->
                    ARController.ensureLiveVideoSessionConfigured(session)
                    onFrame(session, frame)
                }
            )
            // Only FixSight-owned markers are visible. ARCore planes/point-cloud
            // diagnostics are intentionally not rendered.
            AROverlay()
        } else if (isVisionMode && cameraGranted && arCoreReadiness == ArCoreReadiness.CHECKING) {
            // Do not start a second camera owner while ARCore availability is unresolved.
            // The bounded LaunchedEffect above will choose ARCore or Camera2 shortly.
            Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        } else if (isVisionMode && cameraGranted) {
            // P0 compatibility fallback: phones without a usable Google Play Services
            // for AR installation still get Live Video, one-frame-per-turn VLM capture,
            // AI 2D localization, manual arrows/boxes/text/cards and the normal
            // local/cloud reasoning pipeline. Only world-space anchoring is unavailable.
            val fallbackCamera = remember(context) {
                Camera2FrameController(context.applicationContext)
            }
            DisposableEffect(fallbackCamera) {
                ARController.useCamera2Fallback(fallbackCamera)
                onDispose {
                    ARController.clearCamera2Fallback(fallbackCamera)
                    fallbackCamera.close()
                }
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    TextureView(ctx).also { fallbackCamera.attach(it) }
                },
                update = { view -> fallbackCamera.attach(view) }
            )
            AROverlay()
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp)
            ) {
                Text(
                    text = "2D AR",
                    color = Color.White,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                )
            }
        } else if (isVisionMode) {
            // Permission not yet granted — show blank until launcher re-grants it
            Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        } else {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center
            ) { OrbVisualizer() }
        }
    }
}

@Composable
fun AROverlay() {
    val markers = VisualEngine.observableMarkers
    val density = LocalDensity.current

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val overlayWidthPx = with(density) { maxWidth.toPx() }
        val overlayHeightPx = with(density) { maxHeight.toPx() }

        // Meta Ensemble DIAG Banner
        Surface(
            color = Color(0xE6111822),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, Color(0xFF00E676)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp, start = 16.dp, end = 16.dp)
        ) {
            Text(
                text = "Ensemble: DINOv2-B+DETR+SAM2+CLIP+OCR -> Llama 3B - 2.06GB offline, zero-shot, no 4.5GB ImageBind - AR READY",
                color = Color(0xFF00E676),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }

        // Geometry remains Canvas-backed so the existing BOX / CIRCLE / ARROW
        // rendering path is unchanged. Textual markers are layered separately
        // below; this avoids changing ARCore anchoring or marker projection.
        Canvas(modifier = Modifier.fillMaxSize()) {
            markers.filter { it.visible }.forEach { marker ->
                if (marker.type == ARMarkerType.TEXT_LABEL || marker.type == ARMarkerType.INSTRUCTION_CARD) {
                    return@forEach
                }
                val rect = marker.bbox
                val color = when (marker.color) {
                    ARColor.RED    -> Color.Red
                    ARColor.GREEN  -> Color.Green
                    ARColor.YELLOW -> Color.Yellow
                    ARColor.BLUE   -> Color.Blue
                }
                val left = rect.x * size.width
                val top = rect.y * size.height
                val width = rect.w * size.width
                val height = rect.h * size.height
                val center = androidx.compose.ui.geometry.Offset(left + width / 2f, top + height / 2f)
                when (marker.type) {
                    ARMarkerType.ARROW_POINTER -> {
                        val start = androidx.compose.ui.geometry.Offset(
                            (center.x - width.coerceAtLeast(72.dp.toPx()) * 0.8f).coerceAtLeast(0f),
                            (center.y - height.coerceAtLeast(72.dp.toPx()) * 0.8f).coerceAtLeast(0f)
                        )
                        drawLine(color = color, start = start, end = center, strokeWidth = 4.dp.toPx())
                        drawCircle(color = color, radius = 6.dp.toPx(), center = center, style = Stroke(width = 3.dp.toPx()))
                    }
                    ARMarkerType.CIRCLE_HIGHLIGHT -> {
                        drawCircle(
                            color = color,
                            radius = maxOf(width, height) / 2f,
                            center = center,
                            style = Stroke(width = 3.dp.toPx())
                        )
                    }
                    else -> {
                        drawRect(
                            color = color,
                            topLeft = androidx.compose.ui.geometry.Offset(left, top),
                            size = androidx.compose.ui.geometry.Size(width, height),
                            style = Stroke(width = 3.dp.toPx())
                        )
                    }
                }
            }
        }

        markers.filter {
            it.visible && (it.type == ARMarkerType.TEXT_LABEL || it.type == ARMarkerType.INSTRUCTION_CARD)
        }.forEach { marker ->
            val accent = when (marker.color) {
                ARColor.RED    -> Color.Red
                ARColor.GREEN  -> Color.Green
                ARColor.YELLOW -> Color.Yellow
                ARColor.BLUE   -> Color.Blue
            }
            val x = with(density) { (marker.bbox.x * overlayWidthPx).toDp() }
            val y = with(density) { (marker.bbox.y * overlayHeightPx).toDp() }
            val label = marker.label.trim().ifBlank {
                if (marker.type == ARMarkerType.INSTRUCTION_CARD) "Instruction" else "Label"
            }

            if (marker.type == ARMarkerType.INSTRUCTION_CARD) {
                Surface(
                    color = Color(0xE6111822),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, accent),
                    modifier = Modifier
                        .offset(x = x, y = y)
                        .widthIn(min = 150.dp, max = 280.dp)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("GUIDANCE", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            } else {
                Surface(
                    color = Color.Black.copy(alpha = 0.78f),
                    shape = RoundedCornerShape(7.dp),
                    border = BorderStroke(1.dp, accent),
                    modifier = Modifier
                        .offset(x = x, y = y)
                        .widthIn(max = 220.dp)
                ) {
                    Text(
                        label,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun OrbVisualizer() {
    val infiniteTransition = rememberInfiniteTransition(label = "")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f, targetValue = 1.2f,
        animationSpec = infiniteRepeatable(tween(2000), RepeatMode.Reverse), label = ""
    )
    Box(
        modifier = Modifier.size(200.dp).graphicsLayer { scaleX = scale; scaleY = scale },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier.fillMaxSize().clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFF00E5FF), Color(0xFF006064), Color.Transparent)))
                .border(2.dp, Color(0xFF00E5FF), CircleShape)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Settings dialog — model upload + KB upload
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun SettingsDialog(
    modelLoaded: Boolean,
    modelStatusText: String = "",
    modelFileManager: ModelFileManager? = null,
    onModelFilePicked: ((Uri) -> Unit)?,
    onMetaModelFilePicked: ((ModelFileManager.ModelType, Uri) -> Unit)? = null,
    onDeleteMetaModel: ((ModelFileManager.ModelType) -> Unit)? = null,
    onUseMetaStackChanged: ((Boolean) -> Unit)? = null,
    /** The user picked the phone's AI (Meta Llama or Gemma): load it now. */
    onPhoneEngineChanged: (() -> Unit)? = null,
    onZipFilePicked: ((Uri) -> Unit)? = null,
    onRunSelfTest: (() -> Unit)? = null,
    selfTestText: String = "",
    selfTestRunning: Boolean = false,
    zipStatusText: String = "",
    onKbFilePicked: ((Uri) -> Unit)?,
    kbInstalled: Boolean = false,
    kbReady: Boolean = false,
    kbStatusText: String = "Not loaded",
    kbPacks: List<GenericKnowledgePackRepository.InstalledPack> = emptyList(),
    kbSwitching: Boolean = false,
    onUseKbPack: ((String) -> Unit)? = null,
    onRemoveKbPack: ((String) -> Unit)? = null,
    /** One-tap Guide v1 → v2 update when the wanted file is in Downloads. Null = nothing offered. */
    kbGuideUpdate: KbGuideUpdate? = null,
    onInstallGuideUpdate: (() -> Unit)? = null,
    kbCheckText: String = "",
    kbCheckRunning: Boolean = false,
    onCheckKb: (() -> Unit)? = null,
    groqKeyConfigured: Boolean = false,
    groqStatusText: String = "No API key",
    onSaveGroqKey: ((String) -> Unit)? = null,
    onClearGroqKey: (() -> Unit)? = null,
    onTestGroq: (() -> Unit)? = null,
    onlineEngine: GroqKeyStore.Engine = GroqKeyStore.Engine.GROQ,
    groqKeySaved: Boolean = false,
    metaKeySaved: Boolean = false,
    onOnlineEngineChanged: ((GroqKeyStore.Engine) -> Unit)? = null,
    answerPolicy: AnswerPolicy = AnswerPolicy.LOCAL_FIRST,
    onAnswerPolicyChanged: ((AnswerPolicy) -> Unit)? = null,
    onlineBackup: Boolean = false,
    onOnlineBackupChanged: ((Boolean) -> Unit)? = null,
    onlineVoice: Boolean = false,
    onOnlineVoiceChanged: ((Boolean) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    // modelStatus is driven by modelStatusText from MainActivity (live copy/load progress).
    // Falls back to a sensible default based on modelLoaded when no external status is set.
    val modelStatus = when {
        modelStatusText.isNotBlank() -> modelStatusText
        modelLoaded -> "✓ Local model ready"
        else -> "Not loaded — tap Upload to add"
    }
    var groqKeyInput by remember { mutableStateOf("") }
    var showGroqKey by remember { mutableStateOf(false) }

    val modelPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            onModelFilePicked?.invoke(uri)
        }
    }

    val kbPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            onKbFilePicked?.invoke(uri)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF1A1A2E),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Text(
                    "Settings",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Divider(color = Color(0xFF333355))

                // ── Local AI Model ────────────────────────────────────────────
                val gemmaDisconnected = modelFileManager?.isGemmaDisconnected() == true
                val llamaDisconnected = modelFileManager?.isLlamaDisconnected() == true
                val forceMode = modelFileManager?.getForceModelMode() ?: ForceModelMode.AUTO

                // ── Phone AI: one clear status, one install button ────────────
                var showAdvanced by remember { mutableStateOf(false) }
                val summaryZipPicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.GetContent()
                ) { uri -> if (uri != null) onZipFilePicked?.invoke(uri) }
                val llamaFile = modelFileManager?.isModelInstalled(ModelFileManager.ModelType.LLAMA3B) == true
                val visionFiles = modelFileManager?.isModelInstalled(ModelFileManager.ModelType.DETR) == true
                val llamaFailed = !modelLoaded && llamaFile &&
                    (modelStatusText.contains("fail", true) || modelStatusText.contains("error", true) || modelStatusText.contains("✗"))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("AI on this phone", color = Color(0xFF80D8FF), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    // Phone AI choice: Meta Llama (with the Meta vision models) or Gemma (one model that
                    // also sees photos and the camera). Meta Llama falls back to Gemma if it cannot load.
                    val gemmaInstalled = modelFileManager?.gemmaModelFile() != null
                    var gemmaChosen by remember { mutableStateOf(forceMode == ForceModelMode.GEMMA_ONLY) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Phone AI:", color = Color.White, fontSize = 12.sp)
                        FilterChip(
                            selected = !gemmaChosen,
                            onClick = {
                                gemmaChosen = false
                                modelFileManager?.setForceModelMode(ForceModelMode.AUTO)
                                modelFileManager?.setLlamaDisconnected(false)
                                onPhoneEngineChanged?.invoke()
                            },
                            label = { Text("Meta Llama", fontSize = 12.sp) }
                        )
                        FilterChip(
                            selected = gemmaChosen,
                            enabled = gemmaInstalled,
                            onClick = {
                                gemmaChosen = true
                                modelFileManager?.setForceModelMode(ForceModelMode.GEMMA_ONLY)
                                modelFileManager?.setGemmaDisconnected(false)
                                onPhoneEngineChanged?.invoke()
                            },
                            label = { Text(if (gemmaInstalled) "Gemma" else "Gemma (upload in Advanced)", fontSize = 12.sp) }
                        )
                    }
                    PhoneAiStatusLine(
                        label = if (gemmaChosen) "Answers and camera — Gemma" else "Answers — Meta Llama 3.2",
                        ok = modelLoaded,
                        detail = when {
                            modelLoaded -> "Ready, works offline"
                            llamaFailed -> modelStatusText.removePrefix("Local model failed: ").removePrefix("✗ ").take(160)
                            llamaFile -> "Loading…"
                            else -> "Not installed — install the Meta models zip"
                        },
                        warn = llamaFile && !modelLoaded && !llamaFailed
                    )
                    PhoneAiStatusLine(
                        label = "Camera — Meta DETR · CLIP · DINOv2 · SAM",
                        ok = visionFiles,
                        detail = if (visionFiles) "Installed" else "Not installed — install the Meta models zip",
                        warn = false
                    )
                    Button(
                        onClick = { summaryZipPicker.launch("*/*") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Install Meta models zip", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    if (zipStatusText.isNotBlank()) {
                        Text(zipStatusText, color = Color(0xFF80D8FF), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    TextButton(onClick = { showAdvanced = !showAdvanced }) {
                        Text(if (showAdvanced) "Hide advanced model settings ▴" else "Advanced model settings ▾", color = Color(0xFF9E9E9E), fontSize = 12.sp)
                    }
                }

                if (showAdvanced) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Local Models & Debug Control", color = Color(0xFF80D8FF), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    
                    // Gemma Status & Disconnect Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            // Active only when the loaded engine really is LiteRT (status text comes from LocalModelManager).
                            val gemmaActive = !gemmaDisconnected && modelLoaded && modelStatusText.contains("LiteRT")
                            Text("Gemma (.litertlm, optional)", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Gemma: ${if (gemmaActive) "Active - answers coming from Gemma" else if (gemmaDisconnected) "Disconnected" else "Not in use (Meta Llama is preferred)"}",
                                color = if (gemmaActive) Color(0xFF00E676) else Color(0xFF9E9E9E),
                                fontSize = 11.sp
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                modelFileManager?.setGemmaDisconnected(!gemmaDisconnected)
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(if (gemmaDisconnected) "Connect" else "Disconnect", fontSize = 11.sp)
                        }
                    }

                    // Llama Status & Disconnect Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val llamaInstalled = modelFileManager?.isModelInstalled(ModelFileManager.ModelType.LLAMA3B) == true
                        Column(modifier = Modifier.weight(1f)) {
                            val llamaActive = !llamaDisconnected && modelLoaded && modelStatusText.contains("llama.cpp")
                            Text("Meta Llama 3.2 (GGUF, llama.cpp)", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Llama: ${when {
                                    llamaActive -> "Active - answers written on this phone"
                                    llamaDisconnected -> "Disconnected"
                                    llamaInstalled -> "Installed - loading"
                                    else -> "Not installed"
                                }}",
                                color = if (llamaActive) Color(0xFF00E676) else if (llamaInstalled) Color(0xFFFFD54F) else Color(0xFFFF5252),
                                fontSize = 11.sp
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                modelFileManager?.setLlamaDisconnected(!llamaDisconnected)
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(if (llamaDisconnected) "Connect" else "Disconnect", fontSize = 11.sp)
                        }
                    }

                    // Force Model Mode debug selector
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Force Model Mode:", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            ForceModelMode.values().forEach { mode ->
                                val selected = forceMode == mode
                                FilterChip(
                                    selected = selected,
                                    onClick = { modelFileManager?.setForceModelMode(mode) },
                                    label = { Text(mode.name, fontSize = 10.sp) }
                                )
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = { modelPicker.launch("*/*") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF80D8FF)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF80D8FF))
                    ) {
                        Text("Upload optional Gemma .litertlm file", fontSize = 12.sp)
                    }
                }

                if (modelFileManager != null) {
                    Divider(color = Color(0xFF333355))

                    // ── Meta Stack (Recommended) ──────────────────────────────
                    var pendingMetaType by remember { mutableStateOf<ModelFileManager.ModelType?>(null) }
                    val metaModelPicker = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.GetContent()
                    ) { uri ->
                        val type = pendingMetaType
                        if (uri != null && type != null) {
                            onMetaModelFilePicked?.invoke(type, uri)
                        }
                        pendingMetaType = null
                    }

                    val zipPicker = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.GetContent()
                    ) { uri ->
                        if (uri != null) {
                            onZipFilePicked?.invoke(uri)
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Meta Stack (Recommended)",
                                    color = Color(0xFF80D8FF),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "DINOv2 + SAM 3.1 / MobileSAM + Llama 3.2 3B local stack",
                                    color = Color(0xFF9E9E9E),
                                    fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = modelFileManager.getUseMetaStack(),
                                onCheckedChange = { enabled ->
                                    modelFileManager.setUseMetaStack(enabled)
                                    onUseMetaStackChanged?.invoke(enabled)
                                }
                            )
                        }

                        MetaModelRow(
                            title = "Vision Detector - DINOv2-B (86MB)",
                            subtitle = "dinov2_vitb14_reg4_pretrain.safetensors",
                            modelType = ModelFileManager.ModelType.DINOv2,
                            modelFileManager = modelFileManager,
                            isOptional = false,
                            onBrowseClick = {
                                pendingMetaType = ModelFileManager.ModelType.DINOv2
                                metaModelPicker.launch("*/*")
                            },
                            onDeleteClick = { onDeleteMetaModel?.invoke(ModelFileManager.ModelType.DINOv2) }
                        )

                        MetaModelRow(
                            title = "Vision Anchor - SAM2 / MobileSAM (38.5MB)",
                            subtitle = "sam2_hiera_t.pt (Optional for tight mask)",
                            modelType = ModelFileManager.ModelType.SAM31,
                            modelFileManager = modelFileManager,
                            isOptional = true,
                            onBrowseClick = {
                                pendingMetaType = ModelFileManager.ModelType.SAM31
                                metaModelPicker.launch("*/*")
                            },
                            onDeleteClick = { onDeleteMetaModel?.invoke(ModelFileManager.ModelType.SAM31) }
                        )

                        MetaModelRow(
                            title = "Object Detector - DETR ResNet-50 (159MB)",
                            subtitle = "detr_resnet50.onnx",
                            modelType = ModelFileManager.ModelType.DETR,
                            modelFileManager = modelFileManager,
                            isOptional = false,
                            onBrowseClick = {
                                pendingMetaType = ModelFileManager.ModelType.DETR
                                metaModelPicker.launch("*/*")
                            },
                            onDeleteClick = { onDeleteMetaModel?.invoke(ModelFileManager.ModelType.DETR) }
                        )

                        MetaModelRow(
                            title = "Reasoning - Llama 3.2 3B GGUF (1.9GB)",
                            subtitle = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
                            modelType = ModelFileManager.ModelType.LLAMA3B,
                            modelFileManager = modelFileManager,
                            isOptional = false,
                            onBrowseClick = {
                                pendingMetaType = ModelFileManager.ModelType.LLAMA3B
                                metaModelPicker.launch("*/*")
                            },
                            onDeleteClick = { onDeleteMetaModel?.invoke(ModelFileManager.ModelType.LLAMA3B) }
                        )

                        MetaModelRow(
                            title = "Text Extraction - PP-OCRv4 (100MB)",
                            subtitle = "ch_PP-OCRv4_det + ch_PP-OCRv4_rec_server",
                            modelType = ModelFileManager.ModelType.OCR,
                            modelFileManager = modelFileManager,
                            isOptional = false,
                            onBrowseClick = {
                                pendingMetaType = ModelFileManager.ModelType.OCR
                                metaModelPicker.launch("*/*")
                            },
                            onDeleteClick = { onDeleteMetaModel?.invoke(ModelFileManager.ModelType.OCR) }
                        )

                        MetaModelRow(
                            title = "Zero-Shot Embedding - CLIP ViT-B/32 (150MB)",
                            subtitle = "clip-vit-base-patch32.onnx",
                            modelType = ModelFileManager.ModelType.CLIP,
                            modelFileManager = modelFileManager,
                            isOptional = true,
                            onBrowseClick = {
                                pendingMetaType = ModelFileManager.ModelType.CLIP
                                metaModelPicker.launch("*/*")
                            },
                            onDeleteClick = { onDeleteMetaModel?.invoke(ModelFileManager.ModelType.CLIP) }
                        )
                    }
                }
                } // end advanced

                Divider(color = Color(0xFF333355))

                // ── Knowledge Base ────────────────────────────────────────────
                SettingsSection(
                    title     = "Knowledge Base",
                    subtitle  = "Installable private knowledge pack",
                    status    = kbStatusText,
                    statusOk  = kbInstalled,
                    buttonText = if (kbInstalled) "Add another Knowledge .db" else "Install Knowledge .db",
                    info      = "Supports Pramaan generic knowledge databases and the legacy ANM SOP database. Installed packs are kept, so you can switch between them below. The selected KB is used only when you enable KB for a session.",
                    onButtonClick = { kbPicker.launch("*/*") }
                )
                if (kbPacks.size > 1) {
                    KnowledgePackList(kbPacks, kbSwitching, onUseKbPack, onRemoveKbPack)
                }
                if (kbGuideUpdate != null && onInstallGuideUpdate != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Guide on this phone: ${kbGuideUpdate.activeLabel} — ${kbGuideUpdate.wantedName} is in Downloads",
                            color = Color(0xFFFFD54F), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Button(onClick = { onInstallGuideUpdate() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Install ${kbGuideUpdate.wantedName}")
                        }
                    }
                }
                // KB validation: does search find the pack's own passages, and is its data clean?
                if (kbInstalled && onCheckKb != null) {
                    val kbClip = androidx.compose.ui.platform.LocalClipboardManager.current
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Check this knowledge base", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("Samples passages from every document, checks that search finds each one, and checks the data (cut-off passages, duplicates, missing pages, titles). About 1–2 minutes.",
                            color = Color(0xFF9E9E9E), fontSize = 11.sp, lineHeight = 15.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(onClick = { onCheckKb() }, enabled = !kbCheckRunning, modifier = Modifier.weight(1f)) {
                                Text(if (kbCheckRunning) "Checking…" else "Check now")
                            }
                            OutlinedButton(
                                onClick = { kbClip.setText(androidx.compose.ui.text.AnnotatedString(kbCheckText)) },
                                enabled = kbCheckText.isNotBlank() && !kbCheckRunning,
                                modifier = Modifier.weight(1f)
                            ) { Text("Copy report") }
                        }
                        if (kbCheckText.isNotBlank()) {
                            Text(kbCheckText.take(4000), color = Color(0xFFB0BEC5), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }

                Divider(color = Color(0xFF333355))

                // ── Answer mode ───────────────────────────────────────────────
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Answer mode", color = Color(0xFF80D8FF), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (answerPolicy == AnswerPolicy.CLOUD_FIRST) "Cloud First" else "Local First",
                                color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                if (answerPolicy == AnswerPolicy.CLOUD_FIRST)
                                    "Cloud is preferred for exact factual and complex general questions; simple stable tasks stay local."
                                else
                                    "Uses on-device AI whenever possible. Current information and requested verification use Cloud when needed.",
                                color = Color(0xFF9E9E9E), fontSize = 11.sp, lineHeight = 15.sp
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = answerPolicy == AnswerPolicy.CLOUD_FIRST,
                            onCheckedChange = { enabled ->
                                onAnswerPolicyChanged?.invoke(if (enabled) AnswerPolicy.CLOUD_FIRST else AnswerPolicy.LOCAL_FIRST)
                            }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Online backup for knowledge-base answers", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "When the on-device answer fails its checks, the online AI answers from the same knowledge-base passages " +
                                    "(needs internet; those passages are sent online). Off: answers stay on this phone.",
                                color = Color(0xFF9E9E9E), fontSize = 11.sp, lineHeight = 15.sp
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = onlineBackup, onCheckedChange = { enabled -> onOnlineBackupChanged?.invoke(enabled) })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Meta online voice", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "With internet and a Meta key, spoken questions are transcribed by Meta Muse Voice (Hindi and English, " +
                                    "mixed too); the recording is sent online. Off, or offline: Whisper on this phone.",
                                color = Color(0xFF9E9E9E), fontSize = 11.sp, lineHeight = 15.sp
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = onlineVoice, onCheckedChange = { enabled -> onOnlineVoiceChanged?.invoke(enabled) })
                    }
                }

                Divider(color = Color(0xFF333355))

                // ── Cloud AI / Meta Llama API ─────────────────────────────────
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Online AI (optional)", color = Color(0xFF80D8FF), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("Meta Model API key (LLM|…): Muse Spark for answers, photos and web search, plus SAM 3.1 and Muse Voice. Stored encrypted on this phone only; names and phone numbers are masked before anything is sent.", color = Color(0xFF9E9E9E), fontSize = 11.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(6.dp).background(
                                if (groqKeyConfigured && groqStatusText.contains("Connected", ignoreCase = true)) Color(0xFF00E676)
                                else Color(0xFFFFD740), CircleShape
                            )
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(groqStatusText, color = Color(0xFFE0E0E0), fontSize = 12.sp)
                    }
                    OutlinedTextField(
                        value = groqKeyInput,
                        onValueChange = { groqKeyInput = it },
                        label = {
                            val name = "Meta API key (LLM|…)"
                            Text(if (groqKeyConfigured) "Replace $name" else name)
                        },
                        singleLine = true,
                        visualTransformation = if (showGroqKey) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { showGroqKey = !showGroqKey }, modifier = Modifier.weight(1f)) {
                            Text(if (showGroqKey) "Hide" else "Show")
                        }
                        Button(
                            onClick = { if (groqKeyInput.isNotBlank()) { onSaveGroqKey?.invoke(groqKeyInput.trim()); groqKeyInput = "" } },
                            enabled = groqKeyInput.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) { Text("Save") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { onTestGroq?.invoke() }, enabled = groqKeyConfigured, modifier = Modifier.weight(1f)) { Text("Test connection") }
                        OutlinedButton(onClick = { groqKeyInput = ""; onClearGroqKey?.invoke() }, enabled = groqKeyConfigured, modifier = Modifier.weight(1f)) { Text("Clear key") }
                    }
                }

                Divider(color = Color(0xFF333355))

                // ── Self-test ─────────────────────────────────────────────────
                if (onRunSelfTest != null) {
                    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Self-test", color = Color(0xFF80D8FF), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("Runs chat, Hindi, knowledge base, photo reading and camera models on this phone and writes one report (2–5 minutes).",
                            color = Color(0xFF9E9E9E), fontSize = 11.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(onClick = { onRunSelfTest() }, enabled = !selfTestRunning, modifier = Modifier.weight(1f)) {
                                Text(if (selfTestRunning) "Running…" else "Run self-test")
                            }
                            OutlinedButton(
                                onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(selfTestText)) },
                                enabled = selfTestText.isNotBlank() && !selfTestRunning,
                                modifier = Modifier.weight(1f)
                            ) { Text("Copy report") }
                        }
                        if (selfTestText.isNotBlank()) {
                            Text(selfTestText.take(4000), color = Color(0xFFB0BEC5), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                    Divider(color = Color(0xFF333355))
                }

                // ── About ──────────────────────────────────────────────────────
                val (lastSource, lastTime) = modelFileManager?.getLastAnswerSource() ?: (ModelSource.GEMMA_3N_E2B to 0L)
                val formattedTime = if (lastTime > 0L) java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(lastTime)) else "N/A"

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("About", color = Color(0xFF80D8FF), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("Pramaan • guidance for ASHA & ANM health workers", color = Color(0xFF9E9E9E), fontSize = 11.sp)
                    Text("Offline: Meta Llama 3.2 + Meta vision models, answers cited from your knowledge base", color = Color(0xFF9E9E9E), fontSize = 11.sp)
                    Text("Online (optional): Meta Muse Spark, SAM 3.1, Muse Voice via the Meta Model API", color = Color(0xFF9E9E9E), fontSize = 11.sp)
                    if (showAdvanced) {
                        Text("Last answer source: ${lastSource.detailText} at $formattedTime", color = Color(0xFF00E676), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3700B3))
                ) { Text("Done", color = Color.White) }
            }
        }
    }
}

@Composable
private fun PhoneAiStatusLine(label: String, ok: Boolean, detail: String, warn: Boolean) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Text(
            if (ok) "✓" else if (warn) "…" else "✗",
            color = if (ok) Color(0xFF00E676) else if (warn) Color(0xFFFFD54F) else Color(0xFFFF5252),
            fontSize = 15.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.width(22.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Color(0xFFB0B0B0), fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun KnowledgePackList(
    packs: List<GenericKnowledgePackRepository.InstalledPack>,
    switching: Boolean,
    onUse: ((String) -> Unit)?,
    onRemove: ((String) -> Unit)?
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Installed knowledge packs", color = Color(0xFF9E9E9E), fontSize = 11.sp)
        packs.forEach { pack ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(pack.displayName.ifBlank { "Knowledge Base" }, color = Color.White, fontSize = 13.sp, maxLines = 2)
                    Text("${pack.rowCount} chunks", color = Color(0xFF9E9E9E), fontSize = 11.sp)
                }
                if (pack.active) {
                    Text("In use", color = Color(0xFF00E676), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                } else {
                    TextButton(enabled = !switching && onUse != null, onClick = { onUse?.invoke(pack.packId) }) {
                        Text(if (switching) "…" else "Use", color = Color(0xFF80D8FF), fontSize = 12.sp)
                    }
                    TextButton(enabled = !switching && onRemove != null, onClick = { onRemove?.invoke(pack.packId) }) {
                        Text("Remove", color = Color(0xFFFF8A80), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title:        String,
    subtitle:     String,
    status:       String,
    statusOk:     Boolean,
    buttonText:   String,
    info:         String,
    onButtonClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = Color(0xFF80D8FF), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Color(0xFF9E9E9E), fontSize = 11.sp)

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (statusOk) {
                Text(
                    "✓",
                    color = Color(0xFF00E676),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Box(
                    modifier = Modifier.size(6.dp)
                        .background(Color(0xFFFFD740), CircleShape)
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                status,
                color = if (statusOk) Color(0xFF00E676) else Color(0xFFE0E0E0),
                fontSize = 12.sp,
                fontWeight = if (statusOk) FontWeight.SemiBold else FontWeight.Normal
            )
        }

        OutlinedButton(
            onClick = onButtonClick,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF80D8FF)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF80D8FF))
        ) {
            Text(buttonText, fontSize = 13.sp)
        }

        Text(info, color = Color(0xFF666688), fontSize = 11.sp)
    }
}

@Composable
private fun MetaModelRow(
    title: String,
    subtitle: String,
    modelType: ModelFileManager.ModelType,
    modelFileManager: ModelFileManager,
    isOptional: Boolean = false,
    onBrowseClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val installed = modelFileManager.isModelInstalled(modelType)
    val path = modelFileManager.getModelPath(modelType)
    val sizeMb = if (installed) modelFileManager.getModelSize(modelType) else 0L
    val displayPath = if (installed && !path.isNullOrBlank()) "Installed - ${sizeMb} MB - $path" else if (isOptional) "Optional — not installed" else "Not installed"

    val indicatorColor = when {
        installed -> Color(0xFF00E676)
        isOptional -> Color(0xFF757575)
        else -> Color(0xFFFF5252)
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = Color(0xFF9E9E9E), fontSize = 11.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(indicatorColor, CircleShape)
                )
                Spacer(Modifier.width(6.dp))
                if (installed) {
                    IconButton(onClick = onDeleteClick, modifier = Modifier.size(24.dp)) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete $title",
                            tint = Color(0xFFFF5252),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
        Text(
            text = if (installed) "$displayPath (${sizeMb}MB)" else displayPath,
            color = if (installed) Color(0xFF80D8FF) else Color(0xFF757575),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        OutlinedButton(
            onClick = onBrowseClick,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(if (installed) "Replace $title" else "Browse $title file", fontSize = 12.sp)
        }
    }
}

