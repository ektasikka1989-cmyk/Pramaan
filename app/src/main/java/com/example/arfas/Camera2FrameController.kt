package com.example.arfas

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.display.DisplayManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Phase-1 visual capture:
 * Camera2 only.
 *
 * ARCore / tracking / depth / anchors are intentionally not involved.
 * ML Kit receives snapshots from latestBitmap().
 */
class Camera2FrameController(
    private val context: Context
) {
    private val cameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val workerThread = HandlerThread("FixSightCamera").apply { start() }
    private val workerHandler = Handler(workerThread.looper)

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var textureView: TextureView? = null
    private var cameraSensorOrientation = 90
    private var previewSize: Size? = null
    private val previewTransformMatrix = AtomicReference<Matrix?>(null)

    // Phase 9: preview frames are never converted continuously. Conversion is armed
    // only by captureFreshBitmap() after a committed user turn.
    private val captureRequested = AtomicBoolean(false)
    private val pendingCapture = AtomicReference<CompletableDeferred<Bitmap?>?>(null)
    private val latestBitmap = AtomicReference<Bitmap?>(null)
    private val cameraClosedCallbacks = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    private var reopenAllowed: Boolean = false

    fun attach(view: TextureView) {
        reopenAllowed = true
        if (textureView === view) {
            if (view.isAvailable && !isRunning && cameraDevice == null) openCamera(view.width, view.height)
            return
        }
        textureView = view

        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                openCamera(width, height)
            }

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                configurePreviewTransform(width, height)
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                reopenAllowed = false
                closeCamera()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }

        if (view.isAvailable) {
            openCamera(view.width, view.height)
        }
    }

    @SuppressLint("MissingPermission")
    /**
     * Never throws: Camera2 calls (cameraIdList, getCameraCharacteristics, openCamera) throw
     * CameraAccessException / SecurityException / IllegalArgumentException when the camera is in
     * use by another app, disabled by policy or not yet released. This runs on the main thread from
     * the TextureView callback, so an uncaught one closed the app when the camera view opened.
     */
    private fun openCamera(viewWidth: Int, viewHeight: Int) {
        try {
            openCameraUnchecked(viewWidth, viewHeight)
        } catch (t: Throwable) {
            Log.e("FixSightCamera", "CAMERA_OPEN_FAILED ${t.javaClass.simpleName}: ${t.message}")
            isRunning = false
            runCatching { imageReader?.close() }
            imageReader = null
        }
    }

    private fun openCameraUnchecked(viewWidth: Int, viewHeight: Int) {
        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        if (isRunning) return

        val cameraId =
            cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager
                    .getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            } ?: return

        val characteristics =
            cameraManager.getCameraCharacteristics(cameraId)

        cameraSensorOrientation =
            characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        val streamMap =
            characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            ) ?: return

        val previewSize =
            chooseSize(
                streamMap.getOutputSizes(SurfaceTexture::class.java),
                viewWidth,
                viewHeight
            )

        this.previewSize = previewSize

        // Apply display geometry before the first repeating request can become visible.
        // Previously this happened only after onConfigured(), producing a brief tilted
        // preview that then "autocorrected" a frame or two later.
        textureView?.let { v ->
            if (v.width > 0 && v.height > 0) {
                v.post { configurePreviewTransform(v.width, v.height) }
            }
        }

        val imageSize =
            chooseImageSize(
                streamMap.getOutputSizes(ImageFormat.YUV_420_888),
                previewSize
            )

        val reader =
            ImageReader.newInstance(
                imageSize.width,
                imageSize.height,
                ImageFormat.YUV_420_888,
                2
            )

        reader.setOnImageAvailableListener(
            { r ->
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val deferred = pendingCapture.get()
                    if (captureRequested.compareAndSet(true, false) && deferred != null && !deferred.isCompleted) {
                        val raw = yuvToBitmap(image)
                        val corrected = rotateBitmapForDisplay(raw)
                        latestBitmap.getAndSet(corrected)?.let { old ->
                            if (old !== corrected) {
                                try { old.recycle() } catch (_: Throwable) {}
                            }
                        }
                        if (corrected !== raw) {
                            try { raw.recycle() } catch (_: Throwable) {}
                        }
                        pendingCapture.compareAndSet(deferred, null)
                        deferred.complete(
                            try { corrected.copy(corrected.config ?: Bitmap.Config.ARGB_8888, false) }
                            catch (_: Throwable) { corrected }
                        )
                        Log.i("FixSightCamera", "TURN_FRAME_CAPTURED ${corrected.width}x${corrected.height}")
                    }
                } catch (t: Throwable) {
                    pendingCapture.getAndSet(null)?.let { deferred ->
                        if (!deferred.isCompleted) deferred.complete(null)
                    }
                    captureRequested.set(false)
                    Log.w("FixSightCamera", "Frame conversion failed: ${t.message}")
                } finally {
                    image.close()
                }
            },
            workerHandler
        )

        imageReader = reader

        cameraManager.openCamera(
            cameraId,
            object : CameraDevice.StateCallback() {

                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createSession(previewSize)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    isRunning = false
                    notifyCameraClosed()
                }

                override fun onError(
                    camera: CameraDevice,
                    error: Int
                ) {
                    camera.close()
                    cameraDevice = null
                    isRunning = false
                    notifyCameraClosed()
                    if (reopenAllowed && (error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE || error == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE)) {
                        val v = textureView
                        if (v != null) {
                            Log.w("FixSightCamera", "CAMERA_REOPEN_AFTER_AR error=$error")
                            workerHandler.postDelayed({
                                if (reopenAllowed && v.isAvailable && !isRunning && cameraDevice == null) {
                                    v.post { openCamera(v.width, v.height) }
                                }
                            }, 350L)
                        }
                    }
                }

                override fun onClosed(camera: CameraDevice) {
                    if (cameraDevice === camera) cameraDevice = null
                    isRunning = false
                    notifyCameraClosed()
                }
            },
            workerHandler
        )
    }

    /** Never throws (runs on the camera thread; an uncaught exception there closes the app). */
    private fun createSession(previewSize: Size) {
        try {
            createSessionUnchecked(previewSize)
        } catch (t: Throwable) {
            Log.e("FixSightCamera", "CAMERA_SESSION_FAILED ${t.javaClass.simpleName}: ${t.message}")
            isRunning = false
        }
    }

    private fun createSessionUnchecked(previewSize: Size) {
        val camera = cameraDevice ?: return
        val view = textureView ?: return
        val reader = imageReader ?: return
        val texture = view.surfaceTexture ?: return

        texture.setDefaultBufferSize(
            previewSize.width,
            previewSize.height
        )

        val previewSurface = Surface(texture)
        val imageSurface = reader.surface

        camera.createCaptureSession(
            listOf(previewSurface, imageSurface),
            object : CameraCaptureSession.StateCallback() {

                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session

                    try {
                        val request =
                            camera.createCaptureRequest(
                                CameraDevice.TEMPLATE_PREVIEW
                            ).apply {
                                addTarget(previewSurface)
                                addTarget(imageSurface)
                                set(
                                    CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                                )
                            }

                        session.setRepeatingRequest(
                            request.build(),
                            null,
                            workerHandler
                        )

                        view.post {
                            configurePreviewTransform(view.width, view.height)
                        }
                        isRunning = true
                    } catch (_: Throwable) {
                        isRunning = false
                    }
                }

                override fun onConfigureFailed(
                    session: CameraCaptureSession
                ) {
                    isRunning = false
                }
            },
            workerHandler
        )
    }

    /**
     * Arm exactly one YUV->Bitmap conversion and await the next camera frame.
     * Normal preview therefore performs zero JPEG/Bitmap work between user turns.
     */
    suspend fun captureFreshBitmap(timeoutMs: Long = 1_800L): Bitmap? {
        if (!isRunning || imageReader == null) return null
        val deferred = CompletableDeferred<Bitmap?>()
        val previous = pendingCapture.getAndSet(deferred)
        if (previous != null && !previous.isCompleted) previous.complete(null)
        captureRequested.set(true)
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
        if (result == null) {
            if (pendingCapture.compareAndSet(deferred, null)) captureRequested.set(false)
            Log.w("FixSightCamera", "TURN_FRAME_CAPTURE_TIMEOUT ms=$timeoutMs")
        }
        return result
    }

    fun mapCapturedTargetToPreview(target: DetectedObject): DetectedObject {
        val capture = latestBitmap.get() ?: return target
        val view = textureView ?: return target
        val viewW = view.width
        val viewH = view.height
        if (capture.width <= 0 || capture.height <= 0 || viewW <= 0 || viewH <= 0) return target

        // The ImageReader frame is rotated into display orientation before the VLM sees it.
        // ImageReader size is also chosen to match the preview aspect ratio. Therefore the
        // normalized VLM box already lives in the same visible content coordinates as the
        // TextureView; re-applying TextureView's buffer transform would rotate/crop it twice.
        val b = target.bbox
        val nx = b.x.coerceIn(0f, 0.99f)
        val ny = b.y.coerceIn(0f, 0.99f)
        val nr = (b.x + b.w).coerceIn(nx + 0.01f, 1f)
        val nb = (b.y + b.h).coerceIn(ny + 0.01f, 1f)
        val mapped = NormalizedBBox(nx, ny, nr - nx, nb - ny)
        Log.i(
            "FixSightCamera",
            "CAMERA2_BBOX_TO_PREVIEW_DISPLAY_ALIGNED capture=${capture.width}x${capture.height} view=${viewW}x$viewH raw=${target.bbox} mapped=$mapped"
        )
        return target.copy(bbox = mapped, sourceImageWidth = viewW, sourceImageHeight = viewH)
    }

    /**
     * Returns an immutable-for-the-caller snapshot of the latest camera frame.
     * The controller retains ownership of the stored bitmap.
     */
    fun latestBitmap(): Bitmap? =
        latestBitmap.get()?.let { source ->
            try {
                source.copy(source.config ?: Bitmap.Config.ARGB_8888, false)
            } catch (t: Throwable) {
                Log.w("FixSightCamera", "latestBitmap copy failed: ${t.message}")
                null
            }
        }

    /**
     * Close Camera2 and optionally invoke [onClosed] only after the CameraDevice has
     * actually reported closure. This is used by the on-demand AR handoff so ARCore
     * never races Camera2 for ownership of the rear camera.
     */
    fun closeCamera(onClosed: (() -> Unit)? = null) {
        if (onClosed != null) reopenAllowed = false
        onClosed?.let { cameraClosedCallbacks += it }
        isRunning = false

        val device = cameraDevice
        try { captureSession?.stopRepeating() } catch (_: Throwable) {}
        try { captureSession?.close() } catch (_: Throwable) {}
        captureSession = null

        try { imageReader?.close() } catch (_: Throwable) {}
        imageReader = null
        previewSize = null
        captureRequested.set(false)
        pendingCapture.getAndSet(null)?.let { if (!it.isCompleted) it.complete(null) }

        latestBitmap.getAndSet(null)?.let {
            try { it.recycle() } catch (_: Throwable) {}
        }

        if (device == null) {
            cameraDevice = null
            notifyCameraClosed()
        } else {
            try {
                device.close()
            } catch (_: Throwable) {
                if (cameraDevice === device) cameraDevice = null
                notifyCameraClosed()
            }
            // Defensive bound for vendor Camera2 implementations that omit onClosed().
            workerHandler.postDelayed({
                if (cameraClosedCallbacks.isNotEmpty()) {
                    if (cameraDevice === device) cameraDevice = null
                    Log.w("FixSightCamera", "CAMERA_CLOSE_ACK_TIMEOUT forcing handoff")
                    notifyCameraClosed()
                }
            }, 1200L)
        }
    }

    private fun notifyCameraClosed() {
        val callbacks = cameraClosedCallbacks.toList()
        cameraClosedCallbacks.clear()
        callbacks.forEach { callback ->
            try { callback() } catch (t: Throwable) {
                Log.w("FixSightCamera", "camera close callback failed: ${t.message}")
            }
        }
    }

    fun close() {
        previewTransformMatrix.set(null)
        closeCamera()
        workerThread.quitSafely()
    }

    private fun chooseSize(
        sizes: Array<Size>,
        targetWidth: Int,
        targetHeight: Int
    ): Size {
        if (sizes.isEmpty()) return Size(1280, 720)

        // The UI is portrait on the phone, while Camera2 commonly exposes landscape
        // buffers. Prefer a clean 16:9/4:3 camera stream and let the transform below
        // center-crop it instead of selecting a distorted portrait-compatible size.
        val preferred = sizes
            .filter { it.width <= 1920 && it.height <= 1080 }
            .minByOrNull { size ->
                val ratio = size.width.toFloat() / size.height.toFloat()
                minOf(kotlin.math.abs(ratio - 16f / 9f), kotlin.math.abs(ratio - 4f / 3f))
            }

        return preferred ?: sizes.maxByOrNull { it.width.toLong() * it.height.toLong() }!!
    }

    private fun chooseImageSize(
        sizes: Array<Size>,
        preview: Size
    ): Size {
        val targetRatio = preview.width.toFloat() / preview.height.toFloat().coerceAtLeast(1f)
        val candidates = sizes.filter { it.width >= 640 && it.height >= 480 }
        return candidates.minByOrNull { size ->
            val ratio = size.width.toFloat() / size.height.toFloat().coerceAtLeast(1f)
            val ratioPenalty = kotlin.math.abs(ratio - targetRatio) * 10_000_000f
            val areaPenalty = kotlin.math.abs(size.width.toFloat() * size.height - 1920f * 1080f)
            ratioPenalty + areaPenalty
        } ?: sizes.maxByOrNull { it.width.toLong() * it.height.toLong() } ?: Size(1280, 720)
    }

    private fun displayRotation(): Int = try {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        displayManager?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
    } catch (_: Throwable) {
        Surface.ROTATION_0
    }

    private fun displayDegrees(rotation: Int = displayRotation()): Int = when (rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    private fun configurePreviewTransform(viewWidth: Int, viewHeight: Int) {
        val view = textureView ?: return
        val size = previewSize ?: return
        if (viewWidth <= 0 || viewHeight <= 0) return

        // TextureView/SurfaceTexture already carries the camera buffer's natural sensor
        // transform. Apply only the display-orientation/crop transform here. The old
        // sensor-relative +90/-90 rotation duplicated that responsibility and was the
        // source of the persistent 90-degree preview on devices whose sensor is 90°.
        val rotation = displayRotation()
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            val bufferRect = RectF(0f, 0f, size.height.toFloat(), size.width.toFloat())
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = maxOf(
                viewHeight.toFloat() / size.height.toFloat(),
                viewWidth.toFloat() / size.width.toFloat()
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
        } else if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180f, centerX, centerY)
        }

        try {
            view.rotation = 0f
            view.setTransform(matrix)
            previewTransformMatrix.set(Matrix(matrix))
            Log.i(
                "FixSightCamera",
                "CAMERA_PREVIEW_GEOMETRY owner=TextureView display=${displayDegrees(rotation)} sensor=$cameraSensorOrientation size=${size.width}x${size.height} view=${viewWidth}x${viewHeight}"
            )
        } catch (t: Throwable) {
            Log.w("FixSightCamera", "Preview transform failed: ${t.message}")
        }
    }

    private fun rotateBitmapForDisplay(source: Bitmap): Bitmap {
        val rotation = displayRotation()
        val degrees = displayDegrees(rotation)
        // ImageReader pixels are raw sensor-oriented pixels (unlike TextureView preview),
        // so VLM capture still needs the standard back-camera sensor->display rotation.
        val correction = ((cameraSensorOrientation - degrees) + 360) % 360
        if (correction == 0) return source
        Log.i("FixSightCamera", "CAMERA_CAPTURE_ROTATION sensor=$cameraSensorOrientation display=$degrees correction=$correction")
        return try {
            Bitmap.createBitmap(
                source,
                0,
                0,
                source.width,
                source.height,
                Matrix().apply { postRotate(correction.toFloat()) },
                true
            )
        } catch (t: Throwable) {
            Log.w("FixSightCamera", "Bitmap rotation failed: ${t.message}")
            source
        }
    }

    private fun yuvToBitmap(image: Image): Bitmap {
        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yRowStride = yPlane.rowStride
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        val nv21 =
            ByteArray(width * height * 3 / 2)

        val yBuffer = yPlane.buffer.duplicate()
        var offset = 0

        for (row in 0 until height) {
            yBuffer.position(row * yRowStride)
            yBuffer.get(
                nv21,
                offset,
                width
            )
            offset += width
        }

        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()

        val uvHeight = height / 2
        val uvWidth = width / 2

        for (row in 0 until uvHeight) {
            for (col in 0 until uvWidth) {
                val uIndex = row * uRowStride + col * uPixelStride
                val vIndex = row * vRowStride + col * vPixelStride

                nv21[offset++] = vBuffer.get(vIndex)
                nv21[offset++] = uBuffer.get(uIndex)
            }
        }

        val yuv =
            android.graphics.YuvImage(
                nv21,
                ImageFormat.NV21,
                width,
                height,
                null
            )

        val output =
            ByteArrayOutputStream()

        yuv.compressToJpeg(
            Rect(0, 0, width, height),
            96,
            output
        )

        val bytes = output.toByteArray()

        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size
        )
    }
}
