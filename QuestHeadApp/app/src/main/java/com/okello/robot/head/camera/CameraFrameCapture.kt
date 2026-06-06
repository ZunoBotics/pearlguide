package com.okello.robot.head.camera

import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
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

private const val TAG = "CameraFrameCapture"
private const val FRAME_INTERVAL_MS = 1000L   // 1 fps to Gemini

class CameraFrameCapture(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView? = null,
    private val onFrame: (base64Jpeg: String) -> Unit
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val lastSentAt = AtomicLong(0)

    fun start() {
        val cameraId = findBestCameraId()
        if (cameraId == null) {
            Log.w(TAG, "No accessible camera found — running audio-only.")
            return
        }

        Log.i(TAG, "Binding camera ID: $cameraId")
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()

            // Live preview in the UI
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView?.surfaceProvider)
            }

            // Frame analysis — encode and send to Gemini at 1fps
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { proxy ->
                val now = System.currentTimeMillis()
                if (now - lastSentAt.get() >= FRAME_INTERVAL_MS) {
                    lastSentAt.set(now)
                    try {
                        val bmp = proxy.toBitmap()
                        val scaled = Bitmap.createScaledBitmap(bmp, 640, 480, true)
                        val out = ByteArrayOutputStream()
                        scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
                        onFrame(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
                        scaled.recycle()
                        bmp.recycle()
                    } catch (e: Exception) {
                        Log.e(TAG, "Frame encode error: ${e.message}")
                    }
                }
                proxy.close()
            }

            @androidx.camera.camera2.interop.ExperimentalCamera2Interop
            val selector = CameraSelector.Builder()
                .addCameraFilter { infos ->
                    infos.filter { Camera2CameraInfo.from(it).cameraId == cameraId }
                        .toMutableList()
                }
                .build()

            try {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                Log.i(TAG, "Camera $cameraId bound with live preview — sending frames to Gemini at 1fps")
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed: ${e.message} — continuing audio-only")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun findBestCameraId(): String? {
        return try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

            // Quest 3 passthrough cameras (world-facing) report as LENS_FACING_BACK (1).
            // The user-facing eye/face camera reports as LENS_FACING_FRONT (0) — that one
            // looks INWARD and must be avoided. Prefer BACK cameras; fall back to EXTERNAL.
            var bestId: String? = null
            var bestPixels = 0
            var bestFacingScore = -1  // higher = preferred; BACK=2, EXTERNAL=1, FRONT=0

            for (id in manager.cameraIdList) {
                try {
                    val chars = manager.getCameraCharacteristics(id)
                    val configs = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val pixels = configs?.getOutputSizes(android.graphics.ImageFormat.JPEG)
                        ?.maxOfOrNull { it.width * it.height } ?: 0
                    val facing = chars.get(CameraCharacteristics.LENS_FACING)

                    val facingScore = when (facing) {
                        CameraCharacteristics.LENS_FACING_BACK -> 2     // world-facing — best
                        CameraCharacteristics.LENS_FACING_EXTERNAL -> 1 // external USB cam
                        else -> 0                                        // FRONT = user-facing, avoid
                    }

                    Log.d(TAG, "Camera $id: facing=$facing facingScore=$facingScore maxPixels=$pixels")

                    // Prefer by facing first, then by resolution
                    if (facingScore > bestFacingScore ||
                        (facingScore == bestFacingScore && pixels > bestPixels)) {
                        bestFacingScore = facingScore
                        bestPixels = pixels
                        bestId = id
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot read camera $id: ${e.message}")
                }
            }
            if (bestId != null) Log.i(TAG, "Selected camera: $bestId (facing=$bestFacingScore, $bestPixels px)")
            bestId
        } catch (e: Exception) {
            Log.w(TAG, "CameraManager unavailable: ${e.message}")
            null
        }
    }
}
