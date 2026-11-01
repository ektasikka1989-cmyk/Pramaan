package com.example.arfas

import android.util.Log
import android.opengl.Matrix
import androidx.compose.runtime.mutableStateListOf
import com.google.ar.core.Anchor
import com.google.ar.core.DepthPoint
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.InstantPlacementPoint
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingState

/**
 * VisualEngine — manages all AR markers and 2D overlay annotations.
 *
 * Fixes in this version:
 *
 * 1. AI placement uses only trustworthy ARCore Plane/oriented-Point geometry for
 *    world anchors. Small components stay image-grounded; manual tap placement
 *    retains its separate Instant Placement behavior in ARController.
 *
 * 2. Fuzzy label matching — "laptop" and "laptop screen" now update the same
 *    marker. Prevents progressive box accumulation across turns.
 *
 * 3. Periodic cleanup timer keeps unlocked annotations bounded while AR is active.
 *
 * 4. Anchor detach on all removal paths — prevents ARCore native memory leak.
 *
 * 5. Confidence threshold 0.45 — low-confidence detections are rejected.
 */
object VisualEngine {

    private const val TAG            = "VisualEngine"
    private const val SMOOTHING_ALPHA = 0.4f
    private const val LOCK_THRESHOLD  = 2
    private const val EXPIRY_MS       = 20_000L   // 20s — stale unlocked boxes clear faster
    private const val MIN_CONFIDENCE  = 0.45f

    // AI localization must not invent depth for small components. A normalized
    // target must occupy a meaningful portion of the camera view before a plane/
    // feature-point hit is promoted to a world anchor. Smaller targets remain
    // image-grounded and are automatically hidden when camera motion makes that
    // 2D localization stale. This is geometry-based, not label/keyword based.
    private const val AI_WORLD_ANCHOR_MIN_AREA = 0.0025f
    private const val AI_WORLD_ANCHOR_MIN_SPAN = 0.03f
    private const val IMAGE_GROUNDED_MAX_TRANSLATION_M = 0.04f
    private const val IMAGE_GROUNDED_MAX_ROTATION_DEG = 4.0f

    private val markers = mutableListOf<VisualMarker>()
    private val screenGroundingPoseByMarkerId = mutableMapOf<String, com.google.ar.core.Pose>()
    val observableMarkers = mutableStateListOf<VisualMarker>()

    private var currentState: ARState = ARState.IDLE

    // Cleanup is serviced only from the AR/main-frame callback. No background
    // thread may mutate native anchor/marker state while SceneView is iterating it.
    private var cleanupRunning = false
    private var lastCleanupMs = 0L

    // ─────────────────────────────────────────────────────────────────────────
    // Periodic cleanup — Fix 3
    // ─────────────────────────────────────────────────────────────────────────

    fun startPeriodicCleanup() {
        cleanupRunning = true
        lastCleanupMs = System.currentTimeMillis()
        Log.d(TAG, "Frame-owned cleanup enabled")
    }

    fun stopPeriodicCleanup() {
        cleanupRunning = false
        lastCleanupMs = 0L
        Log.d(TAG, "Frame-owned cleanup disabled")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public — process AI intents
    // ─────────────────────────────────────────────────────────────────────────

    // Legacy multi-intent/centre-overlay placement removed in Phase 8.
    // AI AR now has one authoritative grounded localization path.

    fun placeLocalizedTarget(
        intent: ARIntent,
        target: DetectedObject,
        session: Session?,
        frame: Frame?,
        viewWidth: Int,
        viewHeight: Int,
        allow2dFallback: Boolean = true
    ): ARPlacementResult {
        if (target.confidence < MIN_CONFIDENCE || viewWidth <= 0 || viewHeight <= 0) {
            Log.w(TAG, "AI_AR_REJECT target='${target.label}' conf=${target.confidence} viewport=${viewWidth}x$viewHeight")
            return ARPlacementResult.NOT_READY
        }
        val marker = renderWithAnchorAndFallback(
            intent = intent,
            target = target,
            session = session,
            frame = frame,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            allow2dFallback = allow2dFallback
        ) ?: return ARPlacementResult.NOT_READY
        val placed = findExistingMarker(target.label) ?: marker
        placed.isLocked = true
        placed.lastSeen = System.currentTimeMillis()
        syncObservable(placed)
        val result = if (placed.anchor != null) ARPlacementResult.AR_ANCHORED else ARPlacementResult.IMAGE_GROUNDED_2D
        Log.i(TAG, "AI_AR_PLACED target='${target.label}' mode=${placed.mode} anchor=${placed.anchor != null} type=${placed.type} result=$result")
        return result
    }

    /**
     * Re-project every tracked ARCore anchor into the current screen each AR frame.
     * The initial VLM bbox determines target size, while the anchor determines where
     * that target appears as the phone moves. This replaces the old pseudo-anchored
     * behavior where the original 2D rectangle stayed fixed on the display.
     */
    fun updateAnchorScreenPositions(frame: Frame, viewWidth: Int, viewHeight: Int) {
        if (viewWidth <= 0 || viewHeight <= 0 || frame.camera.trackingState != TrackingState.TRACKING) return
        if (cleanupRunning) {
            val now = System.currentTimeMillis()
            if (now - lastCleanupMs >= 10_000L) {
                cleanupExpired()
                lastCleanupMs = now
            }
        }
        val projection = FloatArray(16)
        val view = FloatArray(16)
        frame.camera.getProjectionMatrix(projection, 0, 0.05f, 100f)
        frame.camera.getViewMatrix(view, 0)
        val vp = FloatArray(16)
        Matrix.multiplyMM(vp, 0, projection, 0, view, 0)

        val stoppedIds = mutableListOf<String>()
        markers.forEach { marker ->
            if (marker.mode == MarkerMode.IMAGE_GROUNDED_2D && marker.anchor == null) {
                val referencePose = screenGroundingPoseByMarkerId[marker.id]
                if (referencePose != null) {
                    val safe = isCameraCloseToReference(referencePose, frame.camera.pose)
                    if (marker.visible != safe) {
                        marker.visible = safe
                        syncObservable(marker)
                        if (!safe) {
                            Log.i(TAG, "AI_AR_2D_HIDDEN reason=camera_moved target='${marker.label}'")
                        }
                    }
                }
                return@forEach
            }

            val anchor = marker.anchor ?: return@forEach
            screenGroundingPoseByMarkerId.remove(marker.id)
            when (anchor.trackingState) {
                TrackingState.PAUSED -> {
                    if (marker.visible) {
                        marker.visible = false
                        syncObservable(marker)
                    }
                    return@forEach
                }
                TrackingState.STOPPED -> {
                    stoppedIds += marker.id
                    return@forEach
                }
                TrackingState.TRACKING -> Unit
            }
            val world = floatArrayOf(anchor.pose.tx(), anchor.pose.ty(), anchor.pose.tz(), 1f)
            val clip = FloatArray(4)
            Matrix.multiplyMV(clip, 0, vp, 0, world, 0)
            val wClip = clip[3]
            if (!wClip.isFinite() || wClip <= 0.0001f) {
                if (marker.visible) { marker.visible = false; syncObservable(marker) }
                return@forEach
            }
            val ndcX = clip[0] / wClip
            val ndcY = clip[1] / wClip
            if (!ndcX.isFinite() || !ndcY.isFinite() || ndcX < -1f || ndcX > 1f || ndcY < -1f || ndcY > 1f) {
                if (marker.visible) { marker.visible = false; syncObservable(marker) }
                return@forEach
            }
            val screenX = (ndcX + 1f) * 0.5f
            val screenY = (1f - ndcY) * 0.5f

            val cameraPose = frame.camera.pose
            val dx = anchor.pose.tx() - cameraPose.tx()
            val dy = anchor.pose.ty() - cameraPose.ty()
            val dz = anchor.pose.tz() - cameraPose.tz()
            val currentDistance = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.05f)

            if (marker.referenceDistanceMeters == null) {
                marker.referenceDistanceMeters = currentDistance
                marker.referenceBoxWidth = marker.bbox.w
                marker.referenceBoxHeight = marker.bbox.h
            }

            val perspectiveScale = when (marker.type) {
                ARMarkerType.BOX_HIGHLIGHT, ARMarkerType.CIRCLE_HIGHLIGHT ->
                    (marker.referenceDistanceMeters!! / currentDistance).coerceIn(0.45f, 2.2f)
                else -> 1f
            }
            val width = ((marker.referenceBoxWidth ?: marker.bbox.w) * perspectiveScale).coerceIn(0.03f, 0.9f)
            val height = ((marker.referenceBoxHeight ?: marker.bbox.h) * perspectiveScale).coerceIn(0.03f, 0.9f)
            marker.visible = true
            marker.bbox = marker.bbox.copy(
                x = (screenX - width / 2f).coerceIn(0f, 1f - width),
                y = (screenY - height / 2f).coerceIn(0f, 1f - height),
                w = width,
                h = height
            )
            syncObservable(marker)
        }

        if (stoppedIds.isNotEmpty()) {
            // STOPPED is terminal and ARCore has already invalidated the anchor. Do not
            // detach it again (device logs showed duplicate-detach warnings). Stable
            // Plane/Point anchors should survive ordinary off-screen movement; only a
            // genuinely terminal anchor is removed here.
            markers.removeAll { it.id in stoppedIds }
            observableMarkers.removeAll { it.id in stoppedIds }
            Log.d(TAG, "AR_STOPPED_ANCHORS_REMOVED count=${stoppedIds.size}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public — marker management
    // ─────────────────────────────────────────────────────────────────────────

    fun addOrUpdateMarker(marker: VisualMarker) = addOrUpdateInternal(marker)

    /**
     * Immediate screen-grounded feedback for an explicit visual-guidance turn.
     * This never claims a world anchor. It lets the user see the detected target
     * while ARCore decides whether trustworthy geometry exists at that same ray.
     */
    fun showImageGroundedLocalization(intent: ARIntent, target: DetectedObject) {
        val marker = VisualMarker(
            label = target.label,
            bbox = target.bbox,
            type = intent.type,
            color = intent.color,
            mode = MarkerMode.IMAGE_GROUNDED_2D,
            anchorType = ARAnchorType.SCREEN_ANCHOR,
            identityKey = "ai:${target.label.trim().lowercase()}"
        )
        addOrUpdateInternal(marker)
        Log.i(TAG, "AI_AR_2D_SHOWN target='${target.label}' bbox=${target.bbox} type=${intent.type}")
    }

    fun showImageGroundedLocalizationMapped(
        intent: ARIntent,
        target: DetectedObject,
        frame: Frame?,
        viewWidth: Int,
        viewHeight: Int
    ): ARPlacementResult {
        if (target.confidence < MIN_CONFIDENCE || viewWidth <= 0 || viewHeight <= 0) {
            return ARPlacementResult.NOT_READY
        }
        val viewBox = mapImageBoxToPreview(target, frame, viewWidth, viewHeight)
        val marker = VisualMarker(
            label = target.label,
            bbox = viewBox,
            type = intent.type,
            color = intent.color,
            mode = MarkerMode.IMAGE_GROUNDED_2D,
            anchorType = ARAnchorType.SCREEN_ANCHOR,
            identityKey = "ai:${target.label.trim().lowercase()}"
        )
        addOrUpdateInternal(marker)
        val placed = findExistingMarker(marker) ?: marker
        placed.isLocked = true
        placed.visible = true
        syncObservable(placed)
        Log.i(TAG, "AI_AR_2D_SHOWN_MAPPED target='${target.label}' bbox=$viewBox type=${intent.type}")
        return ARPlacementResult.IMAGE_GROUNDED_2D
    }


    /**
     * V18 current-frame placement. A fresh VLM bbox is mapped through ARCore's
     * IMAGE_PIXELS -> VIEW transform. The capture world ray is used only for a direct
     * geometry hit; there is no arbitrary-depth projection. If geometry is unavailable,
     * a 2D marker is allowed only while the camera remains close to the capture pose.
     */
    fun placeLocalizedTargetFromCurrentFrame(
        intent: ARIntent,
        target: DetectedObject,
        frame: Frame,
        viewWidth: Int,
        viewHeight: Int,
        rayOrigin: FloatArray?,
        rayDirection: FloatArray?,
        tryDirectWorldRay: Boolean,
        allowCurrentImageMapping: Boolean,
        allow2dFallback: Boolean
    ): ARPlacementResult {
        if (target.confidence < MIN_CONFIDENCE || viewWidth <= 0 || viewHeight <= 0 ||
            frame.camera.trackingState != TrackingState.TRACKING) {
            return ARPlacementResult.NOT_READY
        }

        val worldAnchorEligible = isAiWorldAnchorEligible(target)
        var hit: com.google.ar.core.HitResult? = null
        if (worldAnchorEligible && tryDirectWorldRay && rayOrigin != null && rayDirection != null) {
            val directHits = try {
                frame.hitTest(rayOrigin, 0, rayDirection, 0)
            } catch (t: Throwable) {
                Log.w(TAG, "AR_CURRENT_RAY_HIT_TEST_FAILED ${t.javaClass.simpleName}:${t.message}")
                emptyList()
            }
            hit = selectStableHit(directHits)
            if (hit != null) {
                Log.i(TAG, "AI_AR_CURRENT_DIRECT_HIT target='${target.label}' trackable=${hit.trackable::class.java.simpleName}")
            }
        }

        val viewBox = if (allowCurrentImageMapping) {
            mapImageBoxToPreview(target, frame, viewWidth, viewHeight)
        } else null

        if (worldAnchorEligible && hit == null && viewBox != null) {
            val cx = (viewBox.x + viewBox.w / 2f) * viewWidth
            val cy = (viewBox.y + viewBox.h / 2f) * viewHeight
            val screenHits = try { frame.hitTest(cx, cy) } catch (_: Throwable) { emptyList() }
            hit = selectStableHit(screenHits)
            if (hit == null) {
                // Explicit AR localization should still be anchorable before ARCore has
                // discovered a plane/normal at the exact pixel. Instant Placement is
                // designed for this case and later refines as geometry improves.
                hit = try { frame.hitTestInstantPlacement(cx, cy, 1.0f).firstOrNull() }
                catch (t: Throwable) {
                    Log.w(TAG, "AR_CURRENT_INSTANT_HIT_FAILED ${t.javaClass.simpleName}:${t.message}")
                    null
                }
                if (hit != null) {
                    Log.i(TAG, "AI_AR_CURRENT_INSTANT_HIT target='${target.label}' x=${"%.1f".format(cx)} y=${"%.1f".format(cy)}")
                }
            }
            if (hit != null) {
                Log.i(TAG, "AI_AR_CURRENT_SCREEN_HIT target='${target.label}' x=${"%.1f".format(cx)} y=${"%.1f".format(cy)} trackable=${hit.trackable::class.java.simpleName}")
            }
        }

        if (hit != null) {
            val anchor = try { hit.createAnchor() } catch (t: Throwable) {
                Log.w(TAG, "AR_CURRENT_CREATE_ANCHOR_FAILED ${t.javaClass.simpleName}:${t.message}")
                null
            }
            if (anchor != null) {
                val markerBox = viewBox ?: NormalizedBBox(0.45f, 0.45f, 0.10f, 0.10f)
                val marker = VisualMarker(
                    label = target.label,
                    bbox = markerBox,
                    type = intent.type,
                    color = intent.color,
                    mode = MarkerMode.AR_ANCHORED,
                    anchor = anchor,
                    anchorType = when (hit.trackable) {
                        is Plane -> ARAnchorType.WORLD_ANCHOR
                        is InstantPlacementPoint -> ARAnchorType.OBJECT_ANCHOR
                        else -> ARAnchorType.OBJECT_ANCHOR
                    },
                    isLocked = true,
                    identityKey = "ai:${target.label.trim().lowercase()}"
                )
                addOrUpdateInternal(marker)
                Log.i(TAG, "AI_AR_CURRENT_ANCHORED target='${target.label}' trackable=${hit.trackable::class.java.simpleName}")
                return ARPlacementResult.AR_ANCHORED
            }
        }

        if (!allow2dFallback || viewBox == null) return ARPlacementResult.NOT_READY

        val marker = VisualMarker(
            label = target.label,
            bbox = viewBox,
            type = intent.type,
            color = intent.color,
            mode = MarkerMode.IMAGE_GROUNDED_2D,
            anchorType = ARAnchorType.SCREEN_ANCHOR,
            isLocked = true,
            identityKey = "ai:${target.label.trim().lowercase()}"
        )
        addOrUpdateInternal(marker)
        rememberScreenGroundingPose(target.label, frame.camera.pose)
        Log.i(TAG, "AI_AR_CURRENT_2D target='${target.label}' bbox=$viewBox type=${intent.type}")
        return ARPlacementResult.IMAGE_GROUNDED_2D
    }

    fun placeLocalizedTargetFromWorldRay(
        intent: ARIntent,
        target: DetectedObject,
        displaySizeHint: NormalizedBBox,
        frame: Frame,
        viewWidth: Int,
        viewHeight: Int,
        rayOrigin: FloatArray,
        rayDirection: FloatArray,
        tryDirectWorldRay: Boolean,
        allow2dFallback: Boolean
    ): ARPlacementResult {
        if (target.confidence < MIN_CONFIDENCE || viewWidth <= 0 || viewHeight <= 0 ||
            frame.camera.trackingState != TrackingState.TRACKING) {
            return ARPlacementResult.NOT_READY
        }

        // The captured bbox is several seconds old by the time VLM localization returns.
        // Its raw IMAGE->VIEW rectangle must therefore not be reused as the current live
        // screen position. Carry the capture-time world ray forward and re-project that
        // ray into the CURRENT camera viewport instead.
        val projected = projectWorldRayToCurrentView(
            frame, viewWidth, viewHeight, rayOrigin, rayDirection
        )

        val worldAnchorEligible = isAiWorldAnchorEligible(target)
        var hit: com.google.ar.core.HitResult? = null
        if (worldAnchorEligible && tryDirectWorldRay) {
            val directHits = try {
                frame.hitTest(rayOrigin, 0, rayDirection, 0)
            } catch (t: Throwable) {
                Log.w(TAG, "AR_CAPTURE_RAY_HIT_TEST_FAILED ${t.javaClass.simpleName}:${t.message}")
                emptyList()
            }
            hit = selectStableHit(directHits)
            if (hit != null) {
                Log.i(TAG, "AI_AR_DIRECT_RAY_HIT target='${target.label}' trackable=${hit.trackable::class.java.simpleName}")
            }
        }

        if (worldAnchorEligible && hit == null && projected != null) {
            val (screenX, screenY) = projected
            val screenHits = try { frame.hitTest(screenX, screenY) } catch (_: Throwable) { emptyList() }
            hit = selectStableHit(screenHits)
            if (hit == null) {
                hit = try { frame.hitTestInstantPlacement(screenX, screenY, 1.0f).firstOrNull() }
                catch (t: Throwable) {
                    Log.w(TAG, "AR_PROJECTED_INSTANT_HIT_FAILED ${t.javaClass.simpleName}:${t.message}")
                    null
                }
                if (hit != null) {
                    Log.i(TAG, "AI_AR_PROJECTED_INSTANT_HIT target='${target.label}' x=${"%.1f".format(screenX)} y=${"%.1f".format(screenY)}")
                }
            }
            if (hit != null) {
                Log.i(
                    TAG,
                    "AI_AR_PROJECTED_SCREEN_HIT target='${target.label}' x=${"%.1f".format(screenX)} y=${"%.1f".format(screenY)} " +
                        "trackable=${hit.trackable::class.java.simpleName}"
                )
            }
        }

        if (hit != null) {
            val anchor = try { hit.createAnchor() } catch (t: Throwable) {
                Log.w(TAG, "AR_CREATE_ANCHOR_FAILED ${t.javaClass.simpleName}:${t.message}")
                null
            }
            if (anchor != null) {
                val markerBox = projected?.let { (x, y) ->
                    centeredBoxAtViewPoint(x, y, viewWidth, viewHeight, displaySizeHint)
                } ?: mapImageBoxToPreview(target, frame, viewWidth, viewHeight)
                val marker = VisualMarker(
                    label = target.label,
                    bbox = markerBox,
                    type = intent.type,
                    color = intent.color,
                    mode = MarkerMode.AR_ANCHORED,
                    anchor = anchor,
                    anchorType = when (hit.trackable) {
                        is Plane -> ARAnchorType.WORLD_ANCHOR
                        is InstantPlacementPoint -> ARAnchorType.SCREEN_ANCHOR
                        else -> ARAnchorType.OBJECT_ANCHOR
                    },
                    isLocked = true,
                    identityKey = "ai:${target.label.trim().lowercase()}"
                )
                addOrUpdateInternal(marker)
                Log.i(TAG, "AI_AR_CAPTURE_RAY_ANCHORED target='${target.label}' trackable=${hit.trackable::class.java.simpleName}")
                return ARPlacementResult.AR_ANCHORED
            }
        }

        if (!allow2dFallback) return ARPlacementResult.NOT_READY

        // A 2D fallback is only safe when the historical target ray is still visible in
        // the current camera. Never paint the old capture rectangle onto a resized/moved
        // live viewport; that caused the near-full-height displaced boxes in device logs.
        val screenPoint = projected ?: run {
            Log.w(TAG, "AI_AR_2D_SUPPRESSED reason=historical_target_not_in_current_view target='${target.label}'")
            return ARPlacementResult.NOT_READY
        }
        val box = centeredBoxAtViewPoint(
            screenPoint.first, screenPoint.second, viewWidth, viewHeight, displaySizeHint
        )
        val marker = VisualMarker(
            label = target.label,
            bbox = box,
            type = intent.type,
            color = intent.color,
            mode = MarkerMode.IMAGE_GROUNDED_2D,
            anchor = null,
            anchorType = ARAnchorType.SCREEN_ANCHOR,
            isLocked = true,
            identityKey = "ai:${target.label.trim().lowercase()}"
        )
        addOrUpdateInternal(marker)
        rememberScreenGroundingPose(target.label, frame.camera.pose)
        Log.i(TAG, "AI_AR_2D_PROJECTED_RAY target='${target.label}' bbox=$box type=${intent.type}")
        return ARPlacementResult.IMAGE_GROUNDED_2D
    }

    private fun projectWorldRayToCurrentView(
        frame: Frame,
        viewWidth: Int,
        viewHeight: Int,
        rayOrigin: FloatArray,
        rayDirection: FloatArray
    ): Pair<Float, Float>? {
        if (rayOrigin.size < 3 || rayDirection.size < 3 || viewWidth <= 0 || viewHeight <= 0) return null
        val projection = FloatArray(16)
        val view = FloatArray(16)
        val vp = FloatArray(16)
        frame.camera.getProjectionMatrix(projection, 0, 0.05f, 100f)
        frame.camera.getViewMatrix(view, 0)
        Matrix.multiplyMM(vp, 0, projection, 0, view, 0)

        // Try several points on the same historical ray. This is not choosing an object
        // depth; it is only finding a numerically stable point on the ray for projection.
        val distances = floatArrayOf(0.5f, 1f, 2f, 4f)
        for (distance in distances) {
            val world = floatArrayOf(
                rayOrigin[0] + rayDirection[0] * distance,
                rayOrigin[1] + rayDirection[1] * distance,
                rayOrigin[2] + rayDirection[2] * distance,
                1f
            )
            val clip = FloatArray(4)
            Matrix.multiplyMV(clip, 0, vp, 0, world, 0)
            val w = clip[3]
            if (!w.isFinite() || w <= 1e-5f) continue
            val ndcX = clip[0] / w
            val ndcY = clip[1] / w
            if (!ndcX.isFinite() || !ndcY.isFinite()) continue
            val x = (ndcX + 1f) * 0.5f * viewWidth
            val y = (1f - ndcY) * 0.5f * viewHeight
            if (x in 0f..viewWidth.toFloat() && y in 0f..viewHeight.toFloat()) {
                return x to y
            }
        }
        return null
    }

    private fun centeredBoxAtViewPoint(
        screenX: Float,
        screenY: Float,
        viewWidth: Int,
        viewHeight: Int,
        sizeHint: NormalizedBBox
    ): NormalizedBBox {
        val cx = (screenX / viewWidth.coerceAtLeast(1)).coerceIn(0f, 1f)
        val cy = (screenY / viewHeight.coerceAtLeast(1)).coerceIn(0f, 1f)
        val w = sizeHint.w.coerceIn(0.01f, 0.95f)
        val h = sizeHint.h.coerceIn(0.01f, 0.95f)
        val x = (cx - w / 2f).coerceIn(0f, (1f - w).coerceAtLeast(0f))
        val y = (cy - h / 2f).coerceIn(0f, (1f - h).coerceAtLeast(0f))
        return NormalizedBBox(x, y, w, h)
    }

    /** Fix 4: detach ARCore anchor before removing — prevents native memory leak */
    fun removeMarker(id: String) {
        markers.find { it.id == id }?.anchor?.detach()
        screenGroundingPoseByMarkerId.remove(id)
        markers.removeAll           { it.id == id }
        observableMarkers.removeAll { it.id == id }
    }

    /** Fix 4: detach ALL anchors on clear */
    fun clearAll() {
        markers.forEach { it.anchor?.detach() }
        screenGroundingPoseByMarkerId.clear()
        markers.clear()
        observableMarkers.clear()
        currentState = ARState.IDLE
        Log.d(TAG, "CLEARED — all anchors detached")
    }

    fun getAllMarkers(): List<VisualMarker> = markers.toList()

    // ─────────────────────────────────────────────────────────────────────────
    // Private — 3-level anchor hierarchy
    // ─────────────────────────────────────────────────────────────────────────

    private fun mapImageBoxToPreview(
        target: DetectedObject,
        frame: Frame?,
        viewWidth: Int,
        viewHeight: Int
    ): NormalizedBBox {
        val srcW = target.sourceImageWidth
        val srcH = target.sourceImageHeight
        if (frame == null || srcW <= 0 || srcH <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            return target.bbox
        }

        // Phase 9: target coordinates for AR requests originate from a raw ARCore
        // camera image. Convert IMAGE_PIXELS -> VIEW through ARCore itself instead
        // of assuming Camera2/ARCore share crop, FOV or texture geometry.
        return try {
            val left = target.bbox.x * srcW
            val top = target.bbox.y * srcH
            val right = (target.bbox.x + target.bbox.w) * srcW
            val bottom = (target.bbox.y + target.bbox.h) * srcH
            val imagePoints = floatArrayOf(
                left, top,
                right, top,
                right, bottom,
                left, bottom
            )
            val viewPoints = FloatArray(imagePoints.size)
            frame.transformCoordinates2d(
                Coordinates2d.IMAGE_PIXELS,
                imagePoints,
                Coordinates2d.VIEW,
                viewPoints
            )
            val xs = floatArrayOf(viewPoints[0], viewPoints[2], viewPoints[4], viewPoints[6])
            val ys = floatArrayOf(viewPoints[1], viewPoints[3], viewPoints[5], viewPoints[7])
            val minX = xs.minOrNull() ?: return target.bbox
            val maxX = xs.maxOrNull() ?: return target.bbox
            val minY = ys.minOrNull() ?: return target.bbox
            val maxY = ys.maxOrNull() ?: return target.bbox
            val nx = (minX / viewWidth).coerceIn(0f, 1f)
            val ny = (minY / viewHeight).coerceIn(0f, 1f)
            val nw = ((maxX - minX) / viewWidth).coerceIn(0.01f, 1f - nx)
            val nh = ((maxY - minY) / viewHeight).coerceIn(0.01f, 1f - ny)
            NormalizedBBox(nx, ny, nw, nh)
        } catch (t: Throwable) {
            Log.w(TAG, "AR_COORDINATE_TRANSFORM_FAILED ${t.javaClass.simpleName}:${t.message}")
            target.bbox
        }
    }


    private fun isAiWorldAnchorEligible(target: DetectedObject): Boolean {
        val w = target.bbox.w.coerceIn(0f, 1f)
        val h = target.bbox.h.coerceIn(0f, 1f)
        val area = w * h
        return area >= AI_WORLD_ANCHOR_MIN_AREA && minOf(w, h) >= AI_WORLD_ANCHOR_MIN_SPAN
    }

    private fun rememberScreenGroundingPose(label: String, pose: com.google.ar.core.Pose) {
        val marker = findExistingMarker(label) ?: return
        if (marker.mode == MarkerMode.IMAGE_GROUNDED_2D && marker.anchor == null) {
            screenGroundingPoseByMarkerId[marker.id] = pose
        }
    }

    private fun isCameraCloseToReference(
        reference: com.google.ar.core.Pose,
        current: com.google.ar.core.Pose
    ): Boolean {
        val dx = current.tx() - reference.tx()
        val dy = current.ty() - reference.ty()
        val dz = current.tz() - reference.tz()
        val translation = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        if (translation > IMAGE_GROUNDED_MAX_TRANSLATION_M) return false

        val qr = FloatArray(4)
        val qc = FloatArray(4)
        reference.getRotationQuaternion(qr, 0)
        current.getRotationQuaternion(qc, 0)
        val dot = kotlin.math.abs(
            qr[0] * qc[0] + qr[1] * qc[1] + qr[2] * qc[2] + qr[3] * qc[3]
        ).coerceIn(0f, 1f)
        val angleDeg = Math.toDegrees((2.0 * kotlin.math.acos(dot.toDouble()))).toFloat()
        return angleDeg <= IMAGE_GROUNDED_MAX_ROTATION_DEG
    }

    private fun selectStableHit(hits: List<com.google.ar.core.HitResult>): com.google.ar.core.HitResult? {
        return hits.firstOrNull { result ->
            val plane = result.trackable as? Plane
            plane != null && plane.isPoseInPolygon(result.hitPose)
        } ?: hits.firstOrNull { result ->
            val point = result.trackable as? Point
            point?.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
        }
    }

    /**
     * AI placement hierarchy:
     * L1 — real ARCore Plane / oriented Point, with Instant Placement as the explicit-AR
     *      bootstrap when stable geometry has not been discovered yet.
     * L2 — image-grounded bbox fallback when anchoring is not currently reliable.
     */
    private fun renderWithAnchorAndFallback(
        intent: ARIntent,
        target: DetectedObject,
        session: Session?,
        frame: Frame?,
        viewWidth: Int,
        viewHeight: Int,
        allow2dFallback: Boolean
    ): VisualMarker? {
        val viewBox = mapImageBoxToPreview(target, frame, viewWidth, viewHeight)
        val marker = VisualMarker(
            label = target.label,
            bbox = viewBox,
            type = intent.type,
            color = intent.color,
            mode = MarkerMode.AR_ANCHORED,
            anchorType = ARAnchorType.SCREEN_ANCHOR,
            identityKey = "ai:${target.label.trim().lowercase()}"
        )

        // L1: AI world anchoring is deliberately conservative. Small visual targets
        // (ports, LEDs, connectors, etc.) cannot be assigned trustworthy depth from a
        // single 2D bbox, so they remain image-grounded instead of being attached to a
        // desk/wall behind them.
        if (isAiWorldAnchorEligible(target) && session != null && frame != null && frame.camera.trackingState == TrackingState.TRACKING) {
            val cx = (viewBox.x + viewBox.w / 2f) * viewWidth
            val cy = (viewBox.y + viewBox.h / 2f) * viewHeight
            val hit = try {
                val normalHits = frame.hitTest(cx, cy)
                selectStableHit(normalHits)
            } catch (t: Throwable) {
                Log.w(TAG, "AR_HIT_TEST_FAILED ${t.javaClass.simpleName}:${t.message}")
                null
            }
            if (hit != null) {
                val anchor = try { hit.createAnchor() } catch (t: Throwable) {
                    Log.w(TAG, "AR_CREATE_ANCHOR_FAILED ${t.javaClass.simpleName}:${t.message}")
                    null
                }
                if (anchor != null) {
                    marker.anchor = anchor
                    marker.anchorType = when (hit.trackable) {
                        is Plane -> ARAnchorType.WORLD_ANCHOR
                        is InstantPlacementPoint -> ARAnchorType.SCREEN_ANCHOR
                        else -> ARAnchorType.OBJECT_ANCHOR
                    }
                    Log.d(TAG, "L1 anchor (${hit.trackable::class.java.simpleName}): ${target.label}")
                    addOrUpdateInternal(marker)
                    return marker
                }
            }
        }

        // During explicit AR guidance we first retry fresh ARCore frames rather than
        // immediately downgrading to a screen-space overlay. The caller enables this
        // fallback only after the bounded anchor-attempt window is exhausted.
        if (!allow2dFallback) {
            Log.d(TAG, "L1 anchor not ready; retry requested: ${target.label}")
            return null
        }
        marker.anchor = null
        marker.mode = MarkerMode.IMAGE_GROUNDED_2D
        Log.d(TAG, "L2 grounded 2D fallback: ${target.label}")
        addOrUpdateInternal(marker)
        frame?.let { rememberScreenGroundingPose(target.label, it.camera.pose) }
        return marker
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private — marker state with fuzzy label matching — Fix 2
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fix 2: fuzzy label matching via string containment in both directions.
     * "laptop screen" will find and update an existing "laptop" marker.
     * "capacitor" will find and update an existing "ceramic capacitor" marker.
     * This prevents a new box being created every turn for the same component.
     */
    private fun findExistingMarker(newMarker: VisualMarker): VisualMarker? {
        val key = newMarker.identityKey?.trim()?.takeIf { it.isNotEmpty() }
        if (key != null) {
            return markers.find { it.identityKey == key }
        }
        val label = newMarker.label.trim()
        return markers.find { it.identityKey == null && it.label.trim().equals(label, ignoreCase = true) }
    }

    private fun findExistingMarker(label: String): VisualMarker? {
        val key = "ai:${label.trim().lowercase()}"
        return markers.find { it.identityKey == key }
            ?: markers.find { it.identityKey == null && it.label.trim().equals(label.trim(), ignoreCase = true) }
    }

    private fun addOrUpdateInternal(newMarker: VisualMarker) {
        val existing = findExistingMarker(newMarker)
        if (existing != null) {
            existing.bbox           = if (newMarker.mode == MarkerMode.AR_ANCHORED) {
                existing.bbox.smooth(newMarker.bbox, SMOOTHING_ALPHA)
            } else {
                newMarker.bbox
            }
            existing.detectionCount++
            existing.lastSeen       = System.currentTimeMillis()
            existing.type           = newMarker.type
            existing.color          = newMarker.color
            existing.identityKey    = newMarker.identityKey ?: existing.identityKey
            existing.visible        = newMarker.visible

            if (newMarker.anchor != null) {
                if (existing.anchor !== newMarker.anchor) {
                    existing.anchor?.let { runCatching { it.detach() } }
                }
                existing.anchor     = newMarker.anchor
                existing.anchorType = newMarker.anchorType
                existing.mode       = newMarker.mode
                existing.referenceDistanceMeters = null
                existing.referenceBoxWidth = newMarker.bbox.w
                existing.referenceBoxHeight = newMarker.bbox.h
            } else {
                // A new screen-grounded result must never inherit a stale world anchor.
                existing.anchor?.let { runCatching { it.detach() } }
                existing.anchor = null
                existing.anchorType = newMarker.anchorType
                existing.mode = newMarker.mode
                existing.referenceDistanceMeters = null
                existing.referenceBoxWidth = null
                existing.referenceBoxHeight = null
            }

            if (!existing.isLocked && existing.detectionCount >= LOCK_THRESHOLD)
                existing.isLocked = true
            syncObservable(existing)
        } else {
            markers.add(newMarker)
            observableMarkers.add(newMarker.copy())
        }
    }

    private fun syncObservable(marker: VisualMarker) {
        val index = observableMarkers.indexOfFirst { it.id == marker.id }
        if (index != -1) observableMarkers[index] = marker.copy()
    }

    private fun cleanupExpired() {
        val now      = System.currentTimeMillis()
        val toRemove = markers.filter { now - it.lastSeen > EXPIRY_MS && !it.isLocked }
        if (toRemove.isEmpty()) return
        toRemove.forEach { marker ->
            marker.anchor?.detach()
            screenGroundingPoseByMarkerId.remove(marker.id)
        }
        markers.removeAll { it in toRemove }
        observableMarkers.removeAll { om -> toRemove.any { it.id == om.id } }
        Log.d(TAG, "Expired: removed ${toRemove.size} markers, anchors detached")
    }

    private fun transitionTo(state: ARState, reason: String = "") {
        Log.d(TAG, "AR $currentState → $state${if (reason.isNotBlank()) " | $reason" else ""}")
        currentState = state
    }
}