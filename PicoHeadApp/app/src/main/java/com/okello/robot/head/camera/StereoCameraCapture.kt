package com.okello.robot.head.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "StereoCameraCapture"
private const val FRAME_INTERVAL_MS = 1000L   // Gemini vision: 1 fps
private const val DEPTH_INTERVAL_MS = 333L    // depth map: ~3 fps
private const val RIGHT_W = 320
private const val RIGHT_H = 240

// Camera 50 (left, world-facing) opened via CameraX with live preview + Gemini frames.
// Camera 51 (right, world-facing) opened directly via Camera2 for the second stereo eye.
// Depth map is computed whenever both left and right frames are fresh.
class StereoCameraCapture(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView? = null,
    private val onFrame: (base64Jpeg: String) -> Unit,
    private val onDepth: ((StereoDepthEstimator.DepthResult) -> Unit)? = null
) {
    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private val depthExecutor = Executors.newSingleThreadExecutor()
    private val lastSentAt = AtomicLong(0)
    private val lastDepthAt = AtomicLong(0)
    // Stored without explicit recycling — GC handles them; avoids race with depth thread
    private val lastLeftBmp = AtomicReference<Bitmap?>(null)
    private val lastRightBmp = AtomicReference<Bitmap?>(null)

    // Camera2 resources for Camera 51
    private var cam2Thread: HandlerThread? = null
    private var cam2Handler: Handler? = null
    private var cam2Device: CameraDevice? = null
    private var cam2Reader: ImageReader? = null

    fun start() {
        val (leftId, rightId) = findStereoIds()
        Log.i(TAG, "Stereo IDs: left=$leftId right=$rightId")

        // Start Camera 50 via CameraX (preview + Gemini)
        startCameraX(leftId)

        // Start Camera 51 via Camera2 (right eye for depth)
        if (rightId != null && onDepth != null) {
            startCamera2Right(rightId)
        }
    }

    fun stop() {
        depthExecutor.shutdownNow()
        analyzerExecutor.shutdownNow()
        try { cam2Device?.close() } catch (_: Exception) {}
        try { cam2Reader?.close() } catch (_: Exception) {}
        cam2Thread?.quitSafely()
        cam2Device = null
        cam2Reader = null
        cam2Thread = null
        cam2Handler = null
    }

    // ─── CameraX: Camera 50 (left, preview + Gemini) ─────────────────────────

    private fun startCameraX(leftId: String?) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()

            val selector = if (leftId != null) selectorForId(leftId) else CameraSelector.DEFAULT_BACK_CAMERA
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView?.surfaceProvider) }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analyzerExecutor) { proxy ->
                try {
                    val bmp = proxy.toBitmap()
                    val scaled = Bitmap.createScaledBitmap(bmp, 640, 480, true)
                    bmp.recycle()
                    lastLeftBmp.set(scaled)
                    maybePublishGeminiFrame(scaled)
                    maybeComputeDepth()
                } catch (e: Exception) {
                    Log.e(TAG, "Left frame error: ${e.message}")
                } finally {
                    proxy.close()
                }
            }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                Log.i(TAG, "CameraX bound to camera $leftId with preview + analysis")
            } catch (e: Exception) {
                Log.e(TAG, "CameraX bind failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // ─── Camera2: Camera 51 (right eye, depth only) ───────────────────────────

    @SuppressLint("MissingPermission")
    private fun startCamera2Right(rightId: String) {
        cam2Thread = HandlerThread("cam2-right").also { it.start() }
        cam2Handler = Handler(cam2Thread!!.looper)

        cam2Reader = ImageReader.newInstance(RIGHT_W, RIGHT_H, ImageFormat.JPEG, 2)
        cam2Reader!!.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val buf = image.planes[0].buffer
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@setOnImageAvailableListener
                lastRightBmp.set(bmp)
                maybeComputeDepth()
            } catch (e: Exception) {
                Log.e(TAG, "Right frame error: ${e.message}")
            } finally {
                image.close()
            }
        }, cam2Handler)

        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            mgr.openCamera(rightId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cam2Device = camera
                    val surface = cam2Reader!!.surface
                    val sessionCb = object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                            val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(surface)
                            }.build()
                            session.setRepeatingRequest(req, null, cam2Handler)
                            Log.i(TAG, "Camera2 right camera $rightId streaming at ${RIGHT_W}x${RIGHT_H}")
                        }
                        override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) {
                            Log.e(TAG, "Camera2 right session configure failed")
                        }
                    }
                    @Suppress("DEPRECATION")
                    camera.createCaptureSession(listOf(surface), sessionCb, cam2Handler)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera2 right disconnected")
                    camera.close()
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera2 right error: $error")
                    camera.close()
                }
            }, cam2Handler)
        } catch (e: Exception) {
            Log.e(TAG, "Camera2 right open failed: ${e.message} — depth estimation disabled")
        }
    }

    // ─── Depth computation ────────────────────────────────────────────────────

    private fun maybeComputeDepth() {
        if (onDepth == null) return
        val now = System.currentTimeMillis()
        if (now - lastDepthAt.get() < DEPTH_INTERVAL_MS) return
        lastDepthAt.set(now)
        // Snapshot references; if a newer frame arrives mid-compute we just use slightly stale data
        val left = lastLeftBmp.get() ?: return
        val right = lastRightBmp.get() ?: return
        depthExecutor.execute {
            try {
                if (!left.isRecycled && !right.isRecycled) {
                    val result = StereoDepthEstimator.compute(left, right)
                    onDepth.invoke(result)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Depth compute error: ${e.message}")
            }
        }
    }

    private fun maybePublishGeminiFrame(bmp: Bitmap) {
        val now = System.currentTimeMillis()
        if (now - lastSentAt.get() < FRAME_INTERVAL_MS) return
        lastSentAt.set(now)
        try {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
            onFrame(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
        } catch (e: Exception) {
            Log.e(TAG, "Gemini encode error: ${e.message}")
        }
    }

    // ─── Camera discovery ─────────────────────────────────────────────────────

    private fun findStereoIds(): Pair<String?, String?> {
        return try {
            val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val ids = mgr.cameraIdList.toSet()

            if ("50" in ids && "51" in ids) {
                Log.i(TAG, "Found Quest 3 stereo pair: 50 (left) + 51 (right)")
                return "50" to "51"
            }

            val back = ids.mapNotNull { id ->
                try {
                    val ch = mgr.getCameraCharacteristics(id)
                    val facing = ch.get(CameraCharacteristics.LENS_FACING) ?: return@mapNotNull null
                    if (facing != CameraCharacteristics.LENS_FACING_BACK) return@mapNotNull null
                    val px = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        ?.getOutputSizes(ImageFormat.JPEG)
                        ?.maxOfOrNull { it.width * it.height } ?: 0
                    id to px
                } catch (e: Exception) { null }
            }.sortedByDescending { it.second }

            when {
                back.size >= 2 -> back[0].first to back[1].first
                back.size == 1 -> back[0].first to null
                else -> null to null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Camera discovery failed: ${e.message}")
            null to null
        }
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    private fun selectorForId(id: String) = CameraSelector.Builder()
        .addCameraFilter { infos ->
            infos.filter { Camera2CameraInfo.from(it).cameraId == id }.toMutableList()
        }
        .build()
}
