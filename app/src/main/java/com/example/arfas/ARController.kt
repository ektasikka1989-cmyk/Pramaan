package com.example.arfas

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.media.Image
import android.util.Log
import android.view.Display
import android.view.Surface
import com.google.ar.core.*
import com.google.ar.core.exceptions.NotYetAvailableException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sqrt

enum class ARPlacementResult { NOT_READY, AR_ANCHORED, IMAGE_GROUNDED_2D }

enum class LiveVideoBackend { ARCORE, CAMERA2_FALLBACK }
enum class ArCoreReadiness { READY, CHECKING, FALLBACK }

/**
 * V10 AR lifecycle boundary.
 * No ARCore Frame or Session survives onSessionUpdated(). Requests are queued and
 * serviced by the next CURRENT frame. Only immutable capture metadata survives.
 */
object ARController {
    private const val TAG = "AR_DEBUG_TRACE"
    private const val TURN_AI_LONG_SIDE = 1280
    private const val REQUEST_TIMEOUT_MS = 2500L
    /**
     * A camera image does not need ARCore tracking; only the AR marker reference does. When
     * tracking has not started this long after the request (camera just opened, plain wall,
     * fast motion), the turn still gets the picture, without an AR reference for markers.
     */
    private const val UNTRACKED_CAPTURE_AFTER_MS = 900L
    private const val PLACEMENT_TIMEOUT_MS = 4500L

    @Volatile private var appContext: Context? = null
    @Volatile private var viewportWidth = 0
    @Volatile private var viewportHeight = 0
    @Volatile private var configuredLiveSessionIdentity = 0
    @Volatile private var liveVideoBackend = LiveVideoBackend.ARCORE
    @Volatile private var fallbackCameraController: Camera2FrameController? = null

    private val generation = AtomicLong(0L)
    private val pendingCapture = AtomicReference<CaptureRequest?>()
    private val pendingPlacement = AtomicReference<PlacementRequest?>()
    private val pendingManual = AtomicReference<ManualRequest?>()
    private val lastSnapshot = AtomicReference<TurnFrameSnapshot?>()

    data class TurnFrameSnapshot(
        val captureReferenceAnchor: Anchor,
        val timestampNs: Long,
        val rawWidth: Int,
        val rawHeight: Int,
        val aiWidth: Int,
        val aiHeight: Int,
        val aiRotationDegrees: Int,
        val rawCropLeft: Int,
        val rawCropTop: Int,
        val rawCropWidth: Int,
        val rawCropHeight: Int,
        val focalLengthX: Float,
        val focalLengthY: Float,
        val principalPointX: Float,
        val principalPointY: Float,
        val generation: Long
    )

    private data class CaptureRequest(
        val generation: Long,
        val result: CompletableDeferred<Bitmap?>,
        val requestedAtMs: Long = android.os.SystemClock.elapsedRealtime()
    )
    private data class PlacementRequest(
        val generation: Long,
        val intent: ARIntent,
        val target: DetectedObject,
        val allow2dFallback: Boolean,
        val result: CompletableDeferred<ARPlacementResult>,
        val currentFrameMapping: Boolean = false,
        var directWorldRayAttempted: Boolean = false
    )
    private data class ManualRequest(
        val generation: Long,
        val tapX: Float,
        val tapY: Float,
        val viewWidth: Int,
        val viewHeight: Int,
        val tool: ARTool,
        val color: ARColor,
        val customLabel: String?,
        val createdAtMs: Long = System.currentTimeMillis()
    )

    fun setContext(context: Context) { appContext = context.applicationContext }

    fun beginLiveVideoGeneration(): Long {
        val g = generation.incrementAndGet()
        configuredLiveSessionIdentity = 0
        lastSnapshot.getAndSet(null)?.captureReferenceAnchor?.let { runCatching { it.detach() } }
        cancelPending("generation_begin")
        CrashBreadcrumbs.add("AR_GENERATION_BEGIN g=$g")
        return g
    }

    fun arCoreReadiness(): ArCoreReadiness {
        val context = appContext ?: return ArCoreReadiness.FALLBACK
        val installed = try {
            context.packageManager.getPackageInfo("com.google.ar.core", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        if (!installed) return ArCoreReadiness.FALLBACK
        return try {
            when (ArCoreApk.getInstance().checkAvailability(context)) {
                ArCoreApk.Availability.SUPPORTED_INSTALLED -> ArCoreReadiness.READY
                ArCoreApk.Availability.UNKNOWN_CHECKING -> ArCoreReadiness.CHECKING
                else -> ArCoreReadiness.FALLBACK
            }
        } catch (t: Throwable) {
            Log.w(TAG, "AR_CAPABILITY_CHECK_FAILED ${t.javaClass.simpleName}:${t.message}")
            ArCoreReadiness.FALLBACK
        }
    }

    fun isArCoreReadyForOfflineUse(): Boolean = arCoreReadiness() == ArCoreReadiness.READY

    fun useArCoreBackend() {
        liveVideoBackend = LiveVideoBackend.ARCORE
        fallbackCameraController = null
        Log.i(TAG, "LIVE_VIDEO_BACKEND backend=ARCORE")
    }

    fun useCamera2Fallback(controller: Camera2FrameController) {
        fallbackCameraController = controller
        liveVideoBackend = LiveVideoBackend.CAMERA2_FALLBACK
        // ARCore-specific requests cannot be serviced in fallback mode.
        pendingCapture.getAndSet(null)?.result?.complete(null)
        pendingPlacement.getAndSet(null)?.result?.complete(ARPlacementResult.NOT_READY)
        pendingManual.set(null)
        lastSnapshot.getAndSet(null)?.captureReferenceAnchor?.let { runCatching { it.detach() } }
        Log.w(TAG, "LIVE_VIDEO_BACKEND backend=CAMERA2_FALLBACK reason=arcore_unavailable")
    }

    fun clearCamera2Fallback(controller: Camera2FrameController? = null) {
        if (controller == null || fallbackCameraController === controller) {
            fallbackCameraController = null
            if (liveVideoBackend == LiveVideoBackend.CAMERA2_FALLBACK) {
                liveVideoBackend = LiveVideoBackend.ARCORE
            }
        }
    }

    fun currentLiveVideoBackend(): LiveVideoBackend = liveVideoBackend

    fun ensureLiveVideoSessionConfigured(session: Session) {
        val identity = System.identityHashCode(session)
        if (configuredLiveSessionIdentity == identity) return
        try {
            val applied = session.config
            configuredLiveSessionIdentity = identity
            Log.i(TAG, "AR_CONFIG_VERIFY rev=V10_0_STABILITY instantPlacement=${applied.instantPlacementMode} depth=${applied.depthMode} planeFinding=${applied.planeFindingMode}")
        } catch (t: Throwable) {
            Log.w(TAG, "AR_CONFIG_VERIFY_FAILED rev=V10_0_STABILITY ${t.javaClass.simpleName}:${t.message}")
        }
    }

    /** Sole entry from SceneView. No Frame/Session is retained after this call. */
    fun updateState(session: Session, frame: Frame) {
        ensureLiveVideoSessionConfigured(session)
        val width = viewportWidth
        val height = viewportHeight
        if (width > 0 && height > 0) {
            runCatching { VisualEngine.updateAnchorScreenPositions(frame, width, height) }
                .onFailure { Log.w(TAG, "AR_MARKER_PROJECTION_FAILED ${it.javaClass.simpleName}:${it.message}") }
        }
        serviceCapture(frame, session)
        servicePlacement(frame, session)
        serviceManual(frame, session)
    }

    fun updateViewport(width: Int, height: Int) {
        if (width > 0 && height > 0) { viewportWidth = width; viewportHeight = height }
    }

    fun clearState() {
        val old = generation.getAndIncrement()
        cancelPending("clear_state")
        lastSnapshot.getAndSet(null)?.captureReferenceAnchor?.let { runCatching { it.detach() } }
        viewportWidth = 0
        viewportHeight = 0
        configuredLiveSessionIdentity = 0
        CrashBreadcrumbs.add("AR_STATE_CLEARED oldGeneration=$old newGeneration=${generation.get()}")
        Log.i(TAG, "AR_STATE_CLEARED rev=V10_0_STABILITY")
    }

    private fun cancelPending(reason: String) {
        pendingCapture.getAndSet(null)?.result?.complete(null)
        pendingPlacement.getAndSet(null)?.result?.complete(ARPlacementResult.NOT_READY)
        pendingManual.set(null)
        Log.d(TAG, "AR_PENDING_CANCELLED reason=$reason")
    }

    suspend fun captureBitmap(): Bitmap? {
        if (liveVideoBackend == LiveVideoBackend.CAMERA2_FALLBACK) {
            val controller = fallbackCameraController
            if (controller == null) {
                Log.w(TAG, "FALLBACK_CAPTURE_SKIPPED reason=controller_missing")
                return null
            }
            return controller.captureFreshBitmap(timeoutMs = REQUEST_TIMEOUT_MS).also { bitmap ->
                if (bitmap != null) {
                    Log.i(TAG, "FALLBACK_TURN_IMAGE_CAPTURE ai=${bitmap.width}x${bitmap.height} oneImage=true")
                } else {
                    Log.w(TAG, "FALLBACK_TURN_IMAGE_CAPTURE_FAILED")
                }
            }
        }
        val req = CaptureRequest(generation.get(), CompletableDeferred())
        if (!pendingCapture.compareAndSet(null, req)) {
            Log.w(TAG, "AR_CAPTURE_SKIPPED reason=request_already_pending")
            return null
        }
        CrashBreadcrumbs.add("AR_CAPTURE_REQUEST g=${req.generation}")
        return withTimeoutOrNull(REQUEST_TIMEOUT_MS) { req.result.await() }.also {
            if (it == null) pendingCapture.compareAndSet(req, null)
        }
    }

    suspend fun placeAiLocalization(
        intent: ARIntent,
        target: DetectedObject,
        allow2dFallback: Boolean = true,
        timeoutMs: Long = PLACEMENT_TIMEOUT_MS
    ): ARPlacementResult {
        if (liveVideoBackend == LiveVideoBackend.CAMERA2_FALLBACK) {
            if (!allow2dFallback) {
                Log.d(TAG, "AI_AR_WORLD_SKIPPED backend=CAMERA2_FALLBACK target='${target.label}'")
                return ARPlacementResult.NOT_READY
            }
            if (target.confidence < 0.45f) {
                Log.w(TAG, "AI_AR_2D_REJECT backend=CAMERA2_FALLBACK target='${target.label}' conf=${target.confidence}")
                return ARPlacementResult.NOT_READY
            }
            val mappedTarget = fallbackCameraController?.mapCapturedTargetToPreview(target) ?: target
            VisualEngine.showImageGroundedLocalization(intent, mappedTarget)
            Log.i(TAG, "AI_AR_2D_FALLBACK backend=CAMERA2_FALLBACK target='${target.label}' conf=${target.confidence} mapped=${mappedTarget.bbox}")
            return ARPlacementResult.IMAGE_GROUNDED_2D
        }
        val req = PlacementRequest(
            generation = generation.get(),
            intent = intent,
            target = target,
            allow2dFallback = allow2dFallback,
            result = CompletableDeferred()
        )
        if (!pendingPlacement.compareAndSet(null, req)) {
            Log.w(TAG, "AI_AR_SKIPPED reason=placement_already_pending target='${target.label}'")
            return ARPlacementResult.NOT_READY
        }
        CrashBreadcrumbs.add("AR_PLACE_REQUEST g=${req.generation} target=${target.label}")
        val boundedTimeoutMs = timeoutMs.coerceIn(250L, PLACEMENT_TIMEOUT_MS)
        return withTimeoutOrNull(boundedTimeoutMs) { req.result.await() }
            ?: ARPlacementResult.NOT_READY.also {
                pendingPlacement.compareAndSet(req, null)
                Log.d(TAG, "AI_AR_PLACEMENT_TIMEOUT target='${target.label}' timeoutMs=$boundedTimeoutMs allow2d=$allow2dFallback")
            }
    }


    /**
     * V18 authoritative automatic-AR placement path. The target was re-localized on
     * the most recently captured live frame, so its bbox belongs to that capture's
     * camera geometry. We use the capture world ray only for a direct ARCore hit and
     * otherwise map IMAGE_PIXELS -> current VIEW while the phone remains close to the
     * relocalization pose. This avoids the old arbitrary-depth ray projection.
     */
    suspend fun placeCurrentFrameLocalization(
        intent: ARIntent,
        target: DetectedObject,
        allow2dFallback: Boolean = true,
        timeoutMs: Long = PLACEMENT_TIMEOUT_MS
    ): ARPlacementResult {
        if (liveVideoBackend == LiveVideoBackend.CAMERA2_FALLBACK) {
            return placeAiLocalization(intent, target, allow2dFallback, timeoutMs)
        }
        val req = PlacementRequest(
            generation = generation.get(),
            intent = intent,
            target = target,
            allow2dFallback = allow2dFallback,
            result = CompletableDeferred(),
            currentFrameMapping = true
        )
        if (!pendingPlacement.compareAndSet(null, req)) {
            Log.w(TAG, "AI_AR_CURRENT_SKIPPED reason=placement_already_pending target='${target.label}'")
            return ARPlacementResult.NOT_READY
        }
        CrashBreadcrumbs.add("AR_CURRENT_PLACE_REQUEST g=${req.generation} target=${target.label}")
        val boundedTimeoutMs = timeoutMs.coerceIn(250L, PLACEMENT_TIMEOUT_MS)
        return withTimeoutOrNull(boundedTimeoutMs) { req.result.await() }
            ?: ARPlacementResult.NOT_READY.also {
                pendingPlacement.compareAndSet(req, null)
                Log.d(TAG, "AI_AR_CURRENT_PLACEMENT_TIMEOUT target='${target.label}' timeoutMs=$boundedTimeoutMs allow2d=$allow2dFallback")
            }
    }

    private fun serviceCapture(frame: Frame, session: Session) {
        val req = pendingCapture.get() ?: return
        if (req.generation != generation.get()) {
            if (pendingCapture.compareAndSet(req, null)) req.result.complete(null)
            return
        }
        val tracked = frame.camera.trackingState == TrackingState.TRACKING
        if (!tracked && android.os.SystemClock.elapsedRealtime() - req.requestedAtMs < UNTRACKED_CAPTURE_AFTER_MS) return

        var image: Image? = null
        try {
            // acquireCameraImage() is transiently unavailable on normal ARCore frames.
            // Keep the same request armed until a usable frame arrives or the caller timeout fires.
            image = frame.acquireCameraImage()

            // Claim this capture request only after the image has actually been acquired.
            if (!pendingCapture.compareAndSet(req, null)) return

            val rawWidth = image.width
            val rawHeight = image.height
            var fullBitmap = imageToBitmap(image)

            // V21 viewport-aligned VLM capture. The ARCore SurfaceView can be a very
            // different aspect ratio from the camera CPU image. Feed the VLM the exact
            // camera region visible in the current AR viewport, then retain that raw-image
            // crop so returned normalized boxes can be expanded back to IMAGE_PIXELS.
            val crop = resolveVisibleRawCrop(frame, rawWidth, rawHeight)
            val cropLeft = crop.left.coerceIn(0, (rawWidth - 1).coerceAtLeast(0))
            val cropTop = crop.top.coerceIn(0, (rawHeight - 1).coerceAtLeast(0))
            val cropRight = crop.right.coerceIn(cropLeft + 1, rawWidth)
            val cropBottom = crop.bottom.coerceIn(cropTop + 1, rawHeight)
            val rawCropWidth = cropRight - cropLeft
            val rawCropHeight = cropBottom - cropTop

            val scaleX = fullBitmap.width.toFloat() / rawWidth.coerceAtLeast(1)
            val scaleY = fullBitmap.height.toFloat() / rawHeight.coerceAtLeast(1)
            val bmpLeft = (cropLeft * scaleX).toInt().coerceIn(0, (fullBitmap.width - 1).coerceAtLeast(0))
            val bmpTop = (cropTop * scaleY).toInt().coerceIn(0, (fullBitmap.height - 1).coerceAtLeast(0))
            val bmpRight = (cropRight * scaleX).toInt().coerceIn(bmpLeft + 1, fullBitmap.width)
            val bmpBottom = (cropBottom * scaleY).toInt().coerceIn(bmpTop + 1, fullBitmap.height)
            var bitmap = Bitmap.createBitmap(fullBitmap, bmpLeft, bmpTop, bmpRight - bmpLeft, bmpBottom - bmpTop)
            if (bitmap !== fullBitmap && !fullBitmap.isRecycled) fullBitmap.recycle()
            bitmap = scaleBitmapLongSide(bitmap, TURN_AI_LONG_SIDE)

            val rotation = getDisplayRelativeRotation(session)
            val output = if (rotation == 0) bitmap else {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
                    if (it !== bitmap && !bitmap.isRecycled) bitmap.recycle()
                }
            }

            if (!tracked) {
                // Picture for the model, no AR reference: markers for this turn fall back to
                // "not placed" instead of being anchored to an older capture.
                lastSnapshot.getAndSet(null)?.captureReferenceAnchor?.let { old -> runCatching { old.detach() } }
                Log.i(
                    TAG,
                    "AR_TURN_IMAGE_CAPTURE rev=V11_CAPTURE_ANCHOR raw=${rawWidth}x$rawHeight ai=${output.width}x${output.height} " +
                        "rotation=$rotation tracked=false referenceAnchor=false"
                )
                req.result.complete(output)
                return
            }

            val intrinsics = frame.camera.imageIntrinsics
            val focal = intrinsics.focalLength
            val principal = intrinsics.principalPoint

            // Persist the capture camera pose through ARCore itself. A raw Pose is frame-local
            // numeric data; this temporary Anchor is the durable spatial reference that survives
            // the VLM round trip and lets us reconstruct the capture-time ray later.
            val captureReferenceAnchor = session.createAnchor(frame.camera.pose)
            val snapshot = TurnFrameSnapshot(
                captureReferenceAnchor = captureReferenceAnchor,
                timestampNs = frame.timestamp,
                rawWidth = rawWidth,
                rawHeight = rawHeight,
                aiWidth = output.width,
                aiHeight = output.height,
                aiRotationDegrees = rotation,
                rawCropLeft = cropLeft,
                rawCropTop = cropTop,
                rawCropWidth = rawCropWidth,
                rawCropHeight = rawCropHeight,
                focalLengthX = focal[0],
                focalLengthY = focal[1],
                principalPointX = principal[0],
                principalPointY = principal[1],
                generation = req.generation
            )
            lastSnapshot.getAndSet(snapshot)?.captureReferenceAnchor?.let { old ->
                if (old !== captureReferenceAnchor) runCatching { old.detach() }
            }

            Log.i(
                TAG,
                "AR_TURN_IMAGE_CAPTURE rev=V11_CAPTURE_ANCHOR raw=${rawWidth}x$rawHeight " +
                    "ai=${output.width}x${output.height} rotation=$rotation " +
                    "crop=${cropLeft},${cropTop},${rawCropWidth}x${rawCropHeight} viewport=${viewportWidth}x$viewportHeight " +
                    "oneImage=true referenceAnchor=true"
            )
            req.result.complete(output)
        } catch (_: NotYetAvailableException) {
            // Normal transient condition. Do not consume the pending request.
            Log.d(TAG, "AR_TURN_IMAGE_CAPTURE_RETRY reason=not_yet_available")
        } catch (t: Throwable) {
            if (pendingCapture.compareAndSet(req, null)) {
                Log.w(TAG, "AR_TURN_IMAGE_CAPTURE_FAILED ${t.javaClass.simpleName}:${t.message}")
                req.result.complete(null)
            }
        } finally {
            runCatching { image?.close() }
        }
    }

    private fun servicePlacement(frame: Frame, session: Session) {
        val req = pendingPlacement.get() ?: return
        if (req.generation != generation.get()) {
            if (pendingPlacement.compareAndSet(req, null)) req.result.complete(ARPlacementResult.NOT_READY)
            return
        }

        val snapshot = lastSnapshot.get() ?: return
        if (snapshot.generation != req.generation || frame.camera.trackingState != TrackingState.TRACKING) return

        val width = viewportWidth
        val height = viewportHeight
        if (width <= 0 || height <= 0) return

        val rawTarget = normalizeTargetToRawImage(req.target, snapshot)
        val referenceAnchor = snapshot.captureReferenceAnchor

        if (req.currentFrameMapping) {
            if (referenceAnchor.trackingState == TrackingState.STOPPED) {
                if (req.allow2dFallback && pendingPlacement.compareAndSet(req, null)) {
                    Log.w(TAG, "AI_AR_CURRENT_SUPPRESSED reason=capture_reference_stopped target='${req.target.label}'")
                    releaseSnapshot(snapshot)
                    req.result.complete(ARPlacementResult.NOT_READY)
                }
                return
            }

            val ray = buildCaptureWorldRay(rawTarget, snapshot)
            val stableForImageMapping = isCaptureViewStable(snapshot, frame)
            // V24: keep retrying the capture-derived world ray on each tracked ARCore
            // frame during the request's bounded timeout. Geometry can become available
            // a few frames after the VLM result; a one-shot ray test unnecessarily lost
            // that opportunity and forced NOT_READY despite later valid geometry.
            val tryDirectWorldRay = true
            req.directWorldRayAttempted = true
            val result = try {
                VisualEngine.placeLocalizedTargetFromCurrentFrame(
                    intent = req.intent,
                    target = rawTarget,
                    frame = frame,
                    viewWidth = width,
                    viewHeight = height,
                    rayOrigin = ray?.first,
                    rayDirection = ray?.second,
                    tryDirectWorldRay = tryDirectWorldRay,
                    allowCurrentImageMapping = stableForImageMapping,
                    allow2dFallback = req.allow2dFallback && stableForImageMapping
                )
            } catch (t: Throwable) {
                Log.w(TAG, "AI_AR_CURRENT_PLACEMENT_FAILED ${t.javaClass.simpleName}:${t.message}")
                ARPlacementResult.NOT_READY
            }

            if (result == ARPlacementResult.NOT_READY && !req.allow2dFallback) return
            if (pendingPlacement.compareAndSet(req, null)) {
                // A fallback-enabled request is the terminal placement attempt for this
                // relocalization snapshot, so release its temporary capture anchor even
                // when placement remains NOT_READY. World-only attempts retain it for the
                // bounded second pass.
                if (req.allow2dFallback || result != ARPlacementResult.NOT_READY) {
                    releaseSnapshot(snapshot)
                }
                req.result.complete(result)
            }
            return
        }
        if (referenceAnchor.trackingState == TrackingState.STOPPED) {
            // Once the capture reference is lost there is no safe way to paint an old
            // image rectangle onto the current live camera. Prefer no marker over a
            // confidently wrong marker. The caller may still answer verbally.
            if (!req.allow2dFallback) return
            if (pendingPlacement.compareAndSet(req, null)) {
                Log.w(TAG, "AI_AR_PLACEMENT_SUPPRESSED reason=capture_reference_stopped target='${req.target.label}'")
                releaseSnapshot(snapshot)
                req.result.complete(ARPlacementResult.NOT_READY)
            }
            return
        }

        val ray = buildCaptureWorldRay(rawTarget, snapshot)
        if (ray == null) {
            if (!req.allow2dFallback) return
            if (pendingPlacement.compareAndSet(req, null)) {
                Log.w(TAG, "AI_AR_PLACEMENT_SUPPRESSED reason=capture_ray_unavailable target='${req.target.label}'")
                releaseSnapshot(snapshot)
                req.result.complete(ARPlacementResult.NOT_READY)
            }
            return
        }

        val (origin, direction) = ray
        Log.d(
            TAG,
            "AR_CAPTURE_RAY_READY target='${req.target.label}' refState=${referenceAnchor.trackingState} " +
                "origin=${origin.joinToString(prefix = "[", postfix = "]") { "%.3f".format(it) }}"
        )

        val tryDirectWorldRay = !req.directWorldRayAttempted
        req.directWorldRayAttempted = true
        val result = try {
            VisualEngine.placeLocalizedTargetFromWorldRay(
                intent = req.intent,
                target = rawTarget,
                displaySizeHint = req.target.bbox,
                frame = frame,
                viewWidth = width,
                viewHeight = height,
                rayOrigin = origin,
                rayDirection = direction,
                tryDirectWorldRay = tryDirectWorldRay,
                allow2dFallback = req.allow2dFallback
            )
        } catch (t: Throwable) {
            Log.w(TAG, "AI_AR_PLACEMENT_FAILED ${t.javaClass.simpleName}:${t.message}")
            ARPlacementResult.NOT_READY
        }

        // A world-only request stays armed across subsequent ARCore frames until either
        // geometry becomes available or the caller's bounded timeout expires.
        if (result == ARPlacementResult.NOT_READY && !req.allow2dFallback) return

        if (pendingPlacement.compareAndSet(req, null)) {
            if (result != ARPlacementResult.NOT_READY) releaseSnapshot(snapshot)
            req.result.complete(result)
        }
    }

    private fun buildCaptureWorldRay(
        target: DetectedObject,
        snapshot: TurnFrameSnapshot
    ): Pair<FloatArray, FloatArray>? {
        val fx = snapshot.focalLengthX
        val fy = snapshot.focalLengthY
        if (!fx.isFinite() || !fy.isFinite() || fx <= 0f || fy <= 0f) return null

        val px = (target.bbox.x + target.bbox.w / 2f) * snapshot.rawWidth
        val py = (target.bbox.y + target.bbox.h / 2f) * snapshot.rawHeight

        val localPoint = floatArrayOf(
            (px - snapshot.principalPointX) / fx,
            -(py - snapshot.principalPointY) / fy,
            -1f
        )
        val pose = snapshot.captureReferenceAnchor.pose
        val origin = FloatArray(3)
        val worldPoint = FloatArray(3)
        pose.transformPoint(floatArrayOf(0f, 0f, 0f), 0, origin, 0)
        pose.transformPoint(localPoint, 0, worldPoint, 0)

        val dx = worldPoint[0] - origin[0]
        val dy = worldPoint[1] - origin[1]
        val dz = worldPoint[2] - origin[2]
        val mag = sqrt(dx * dx + dy * dy + dz * dz)
        if (!mag.isFinite() || mag < 1e-6f) return null

        return origin to floatArrayOf(dx / mag, dy / mag, dz / mag)
    }

    private fun releaseSnapshot(snapshot: TurnFrameSnapshot) {
        if (lastSnapshot.compareAndSet(snapshot, null)) {
            runCatching { snapshot.captureReferenceAnchor.detach() }
            Log.d(TAG, "AR_CAPTURE_REFERENCE_RELEASED g=${snapshot.generation}")
        }
    }

    private fun serviceManual(frame: Frame, session: Session) {
        val req = pendingManual.get() ?: return
        if (req.generation != generation.get()) {
            pendingManual.compareAndSet(req, null)
            return
        }

        val ageMs = System.currentTimeMillis() - req.createdAtMs
        if (ageMs > 1_500L) {
            if (pendingManual.compareAndSet(req, null)) {
                Log.w(TAG, "AR_MANUAL_NOT_PLACED reason=tracking_timeout ageMs=$ageMs tool=${req.tool} x=${req.tapX} y=${req.tapY}")
            }
            return
        }
        if (frame.camera.trackingState != TrackingState.TRACKING) return

        try {
            val hits = frame.hitTest(req.tapX, req.tapY)
            val hit = hits.firstOrNull { result ->
                val plane = result.trackable as? Plane
                plane != null && plane.isPoseInPolygon(result.hitPose)
            } ?: hits.firstOrNull { result ->
                val point = result.trackable as? Point
                point?.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
            } ?: runCatching {
                frame.hitTestInstantPlacement(req.tapX, req.tapY, 1.0f)
                    .firstOrNull { result ->
                        val instant = result.trackable as? InstantPlacementPoint
                        instant != null && instant.trackingState == TrackingState.TRACKING
                    }
            }.getOrNull()

            if (hit == null) {
                // Preserve the user's exact tap even when ARCore has not mapped geometry yet.
                // This is an explicit screen-grounded fallback, not a fabricated world anchor.
                if (pendingManual.compareAndSet(req, null)) {
                    addManualScreenFallback(req)
                    Log.i(TAG, "AR_MANUAL_SCREEN_FALLBACK reason=no_geometry tool=${req.tool} x=${req.tapX} y=${req.tapY}")
                }
                return
            }

            val anchor = runCatching { hit.createAnchor() }.getOrNull()
            if (anchor == null) {
                if (pendingManual.compareAndSet(req, null)) {
                    addManualScreenFallback(req)
                    Log.i(TAG, "AR_MANUAL_SCREEN_FALLBACK reason=anchor_create_failed tool=${req.tool} x=${req.tapX} y=${req.tapY}")
                }
                return
            }

            val markerType = markerTypeForTool(req.tool)
            val nx = req.tapX / req.viewWidth.coerceAtLeast(1)
            val ny = req.tapY / req.viewHeight.coerceAtLeast(1)
            val markerId = "manual:${System.nanoTime()}"
            VisualEngine.addOrUpdateMarker(
                VisualMarker(
                    label = req.customLabel ?: if (req.tool == ARTool.TEXT || req.tool == ARTool.INSTRUCTION_CARD) "" else req.tool.name,
                    bbox = manualMarkerBox(nx, ny),
                    type = markerType,
                    mode = MarkerMode.AR_ANCHORED,
                    color = req.color,
                    anchor = anchor,
                    anchorType = when (hit.trackable) {
                        is Plane -> ARAnchorType.WORLD_ANCHOR
                        is InstantPlacementPoint -> ARAnchorType.OBJECT_ANCHOR
                        else -> ARAnchorType.OBJECT_ANCHOR
                    },
                    isLocked = true,
                    identityKey = markerId
                )
            )
            if (pendingManual.compareAndSet(req, null)) {
                Log.i(TAG, "AR_MANUAL_ANCHORED tool=${req.tool} trackable=${hit.trackable::class.java.simpleName} x=${req.tapX} y=${req.tapY}")
            } else {
                runCatching { anchor.detach() }
            }
        } catch (t: Throwable) {
            if (pendingManual.compareAndSet(req, null)) {
                Log.w(TAG, "AR_MANUAL_NOT_PLACED reason=${t.javaClass.simpleName} tool=${req.tool} x=${req.tapX} y=${req.tapY}")
            }
        }
    }

    private fun markerTypeForTool(tool: ARTool): ARMarkerType = when (tool) {
        ARTool.ARROW -> ARMarkerType.ARROW_POINTER
        ARTool.TEXT -> ARMarkerType.TEXT_LABEL
        ARTool.INSTRUCTION_CARD -> ARMarkerType.INSTRUCTION_CARD
        else -> ARMarkerType.BOX_HIGHLIGHT
    }

    private fun manualMarkerBox(nx: Float, ny: Float): NormalizedBBox = NormalizedBBox(
        (nx - 0.05f).coerceIn(0f, 0.90f),
        (ny - 0.05f).coerceIn(0f, 0.90f),
        0.10f,
        0.10f
    )

    private fun addManualScreenFallback(req: ManualRequest) {
        val safeW = req.viewWidth.coerceAtLeast(1)
        val safeH = req.viewHeight.coerceAtLeast(1)
        val nx = (req.tapX / safeW).coerceIn(0f, 1f)
        val ny = (req.tapY / safeH).coerceIn(0f, 1f)
        VisualEngine.addOrUpdateMarker(
            VisualMarker(
                label = req.customLabel ?: if (req.tool == ARTool.TEXT || req.tool == ARTool.INSTRUCTION_CARD) "" else req.tool.name,
                bbox = manualMarkerBox(nx, ny),
                type = markerTypeForTool(req.tool),
                mode = MarkerMode.MANUAL_SCREEN_FALLBACK,
                color = req.color,
                anchorType = ARAnchorType.SCREEN_ANCHOR,
                isLocked = true,
                identityKey = "manual:${System.nanoTime()}"
            )
        )
    }

    fun performManualPlacement(tapX: Float, tapY: Float, viewWidth: Int, viewHeight: Int, tool: ARTool, color: ARColor, customLabel: String? = null): Boolean {
        if (liveVideoBackend == LiveVideoBackend.CAMERA2_FALLBACK) {
            val safeW = viewWidth.coerceAtLeast(1)
            val safeH = viewHeight.coerceAtLeast(1)
            val nx = (tapX / safeW).coerceIn(0f, 1f)
            val ny = (tapY / safeH).coerceIn(0f, 1f)
            VisualEngine.addOrUpdateMarker(
                VisualMarker(
                    label = customLabel ?: if (tool == ARTool.TEXT || tool == ARTool.INSTRUCTION_CARD) "" else tool.name,
                    bbox = manualMarkerBox(nx, ny),
                    type = markerTypeForTool(tool),
                    mode = MarkerMode.MANUAL_SCREEN_FALLBACK,
                    color = color,
                    anchorType = ARAnchorType.SCREEN_ANCHOR,
                    isLocked = true,
                    identityKey = "manual:${System.nanoTime()}"
                )
            )
            Log.i(TAG, "AR_MANUAL_2D_FALLBACK tool=$tool x=$nx y=$ny")
            return true
        }
        val req = ManualRequest(generation.get(), tapX, tapY, viewWidth, viewHeight, tool, color, customLabel)
        val accepted = pendingManual.compareAndSet(null, req)
        if (accepted) {
            Log.i(TAG, "AR_MANUAL_REQUEST_ACCEPTED tool=$tool x=$tapX y=$tapY view=${viewWidth}x$viewHeight")
        } else {
            Log.w(TAG, "AR_MANUAL_PLACEMENT_SKIPPED reason=request_pending")
        }
        return accepted
    }

    fun isStableSinceLocalizationCapture(maxTranslationMeters: Float = 0.03f, maxRotationDegrees: Float = 3f): Boolean {
        // Compatibility API only. Capture continuity is now owned by a persistent ARCore
        // reference Anchor rather than a raw Pose displacement threshold.
        return lastSnapshot.get()?.let {
            it.generation == generation.get() && it.captureReferenceAnchor.trackingState != TrackingState.STOPPED
        } == true
    }


    private fun isCaptureViewStable(
        snapshot: TurnFrameSnapshot,
        frame: Frame,
        maxTranslationMeters: Float = 0.05f,
        maxRotationDegrees: Float = 4f
    ): Boolean {
        val capturePose = snapshot.captureReferenceAnchor.pose
        val currentPose = frame.camera.pose
        val dx = currentPose.tx() - capturePose.tx()
        val dy = currentPose.ty() - capturePose.ty()
        val dz = currentPose.tz() - capturePose.tz()
        val translation = sqrt(dx * dx + dy * dy + dz * dz)

        fun forward(pose: Pose): FloatArray {
            val origin = FloatArray(3)
            val ahead = FloatArray(3)
            pose.transformPoint(floatArrayOf(0f, 0f, 0f), 0, origin, 0)
            pose.transformPoint(floatArrayOf(0f, 0f, -1f), 0, ahead, 0)
            val fx = ahead[0] - origin[0]
            val fy = ahead[1] - origin[1]
            val fz = ahead[2] - origin[2]
            val mag = sqrt(fx * fx + fy * fy + fz * fz).coerceAtLeast(1e-6f)
            return floatArrayOf(fx / mag, fy / mag, fz / mag)
        }

        val a = forward(capturePose)
        val b = forward(currentPose)
        val dot = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).coerceIn(-1f, 1f)
        val rotationDegrees = (acos(dot.toDouble()) * 180.0 / PI).toFloat()
        val stable = translation <= maxTranslationMeters && rotationDegrees <= maxRotationDegrees
        if (!stable) {
            Log.w(
                TAG,
                "AI_AR_CURRENT_MAPPING_SUPPRESSED reason=camera_moved " +
                    "translation=${"%.3f".format(translation)}m rotation=${"%.1f".format(rotationDegrees)}deg"
            )
        }
        return stable
    }

    private fun normalizeTargetToRawImage(target: DetectedObject, s: TurnFrameSnapshot): DetectedObject {
        val b = target.bbox
        val corners = arrayOf(b.x to b.y, (b.x+b.w) to b.y, (b.x+b.w) to (b.y+b.h), b.x to (b.y+b.h))
        fun inverse(u: Float, v: Float): Pair<Float, Float> = when (s.aiRotationDegrees) {
            90 -> v to (1f-u); 180 -> (1f-u) to (1f-v); 270 -> (1f-v) to u; else -> u to v
        }
        val mapped = corners.map { inverse(it.first, it.second) }
        val cropMinX = mapped.minOf { it.first }.coerceIn(0f,1f); val cropMaxX = mapped.maxOf { it.first }.coerceIn(0f,1f)
        val cropMinY = mapped.minOf { it.second }.coerceIn(0f,1f); val cropMaxY = mapped.maxOf { it.second }.coerceIn(0f,1f)

        // Expand crop-relative coordinates back into the original ARCore CPU image.
        // VisualEngine can then use ARCore's own IMAGE_PIXELS -> VIEW transform exactly.
        val minX = ((s.rawCropLeft + cropMinX * s.rawCropWidth) / s.rawWidth.toFloat()).coerceIn(0f, 1f)
        val maxX = ((s.rawCropLeft + cropMaxX * s.rawCropWidth) / s.rawWidth.toFloat()).coerceIn(0f, 1f)
        val minY = ((s.rawCropTop + cropMinY * s.rawCropHeight) / s.rawHeight.toFloat()).coerceIn(0f, 1f)
        val maxY = ((s.rawCropTop + cropMaxY * s.rawCropHeight) / s.rawHeight.toFloat()).coerceIn(0f, 1f)
        val raw = NormalizedBBox(minX,minY,(maxX-minX).coerceAtLeast(0.001f),(maxY-minY).coerceAtLeast(0.001f))
        Log.i(TAG, "AR_COORDINATE_NORMALIZED rev=V21_VIEWPORT_ALIGNED rotation=${s.aiRotationDegrees} ai=${target.bbox} raw=$raw " +
            "crop=${s.rawCropLeft},${s.rawCropTop},${s.rawCropWidth}x${s.rawCropHeight} rawSize=${s.rawWidth}x${s.rawHeight}")
        return target.copy(bbox=raw, sourceImageWidth=s.rawWidth, sourceImageHeight=s.rawHeight)
    }

    private fun resolveVisibleRawCrop(frame: Frame, rawWidth: Int, rawHeight: Int): Rect {
        val vw = viewportWidth
        val vh = viewportHeight
        if (vw <= 0 || vh <= 0 || rawWidth <= 0 || rawHeight <= 0) return Rect(0, 0, rawWidth, rawHeight)
        return try {
            val viewCorners = floatArrayOf(
                0f, 0f,
                vw.toFloat(), 0f,
                vw.toFloat(), vh.toFloat(),
                0f, vh.toFloat()
            )
            val imageCorners = FloatArray(viewCorners.size)
            frame.transformCoordinates2d(
                Coordinates2d.VIEW, viewCorners, Coordinates2d.IMAGE_PIXELS, imageCorners
            )
            val xs = floatArrayOf(imageCorners[0], imageCorners[2], imageCorners[4], imageCorners[6])
            val ys = floatArrayOf(imageCorners[1], imageCorners[3], imageCorners[5], imageCorners[7])
            val left = kotlin.math.floor(xs.minOrNull()?.toDouble() ?: 0.0).toInt().coerceIn(0, rawWidth - 1)
            val top = kotlin.math.floor(ys.minOrNull()?.toDouble() ?: 0.0).toInt().coerceIn(0, rawHeight - 1)
            val right = kotlin.math.ceil(xs.maxOrNull()?.toDouble() ?: rawWidth.toDouble()).toInt().coerceIn(left + 1, rawWidth)
            val bottom = kotlin.math.ceil(ys.maxOrNull()?.toDouble() ?: rawHeight.toDouble()).toInt().coerceIn(top + 1, rawHeight)
            Rect(left, top, right, bottom)
        } catch (t: Throwable) {
            Log.w(TAG, "AR_VISIBLE_CROP_FALLBACK ${t.javaClass.simpleName}:${t.message}")
            Rect(0, 0, rawWidth, rawHeight)
        }
    }

    private fun getDisplayRelativeRotation(session: Session): Int {
        val context = appContext ?: return 0
        return try {
            val cameraId = session.cameraConfig.cameraId
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val c = manager.getCameraCharacteristics(cameraId)
            val sensor = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val facing = c.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
            val displayRotation = (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
            val displayDegrees = when(displayRotation){Surface.ROTATION_90->90;Surface.ROTATION_180->180;Surface.ROTATION_270->270;else->0}
            if (facing == CameraCharacteristics.LENS_FACING_FRONT) (sensor + displayDegrees + 360) % 360 else (sensor - displayDegrees + 360) % 360
        } catch (t: Throwable) { Log.w(TAG, "Could not resolve camera/display rotation: ${t.message}"); 0 }
    }

    private fun scaleBitmapLongSide(source: Bitmap, maxLongSide: Int): Bitmap {
        val longSide=max(source.width,source.height).coerceAtLeast(1); if(longSide<=maxLongSide)return source
        val scale=maxLongSide.toFloat()/longSide; val w=(source.width*scale).toInt().coerceAtLeast(1); val h=(source.height*scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source,w,h,true).also{if(it!==source&&!source.isRecycled)source.recycle()}
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val width = image.width
        val height = image.height
        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]
        val nv21 = ByteArray(width * height * 3 / 2)
        var offset = 0

        val yb = y.buffer.duplicate()
        for (row in 0 until height) {
            yb.position(row * y.rowStride)
            yb.get(nv21, offset, width)
            offset += width
        }

        // U and V planes are not guaranteed to have identical stride/pixelStride on
        // every vendor implementation. The old code indexed both planes with U's
        // geometry, which can corrupt chroma and visibly degrade VLM/object quality.
        val ub = u.buffer.duplicate()
        val vb = v.buffer.duplicate()
        val uvH = height / 2
        val uvW = width / 2
        for (row in 0 until uvH) {
            for (col in 0 until uvW) {
                val vIndex = row * v.rowStride + col * v.pixelStride
                val uIndex = row * u.rowStride + col * u.pixelStride
                nv21[offset++] = vb.get(vIndex)
                nv21[offset++] = ub.get(uIndex)
            }
        }

        // This conversion happens only for the committed turn frame. Preserve detail
        // here because the image may later be JPEG-encoded again by the local/cloud VLM.
        val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        if (!yuv.compressToJpeg(Rect(0, 0, width, height), 96, out)) {
            throw IllegalStateException("YUV JPEG conversion failed")
        }
        return BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
            ?: throw IllegalStateException("YUV decode returned null")
    }
}
