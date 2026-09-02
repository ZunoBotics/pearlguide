package com.okello.robot.head.detection

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.okello.robot.head.camera.StereoDepthEstimator
import com.okello.robot.head.mqtt.EnrolledPerson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "ObstacleDetector"

data class ObstacleResult(
    val className: String,
    val distanceCm: Int,
    val direction: String,  // "left", "forward", "right"
    val confidence: Float
)

class ObstacleDetector(context: Context) {

    private val yolo = YoloDetector(context)
    val faceRecognizer = FaceRecognizer(context.assets)
    var lastYoloResults: List<YoloResult> = emptyList()
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val faceDetecting = AtomicBoolean(false)

    fun setFaceThreshold(threshold: Float) {
        faceRecognizer.matchThreshold = threshold
    }

    fun enrollFaces(people: List<EnrolledPerson>) {
        scope.launch { faceRecognizer.enroll(people) }
    }

    /**
     * YOLO + face detection on both camera frames.
     * Left camera boxes are mapped to the left half of the screen [cx*0.5].
     * Right camera boxes are mapped to the right half [0.5 + cx*0.5].
     * When rightBitmap is null, left camera occupies the full screen (single-eye mode).
     */
    suspend fun detectObstacles(leftBitmap: Bitmap, rightBitmap: Bitmap? = null): List<ObstacleResult> {
        val results = mutableListOf<ObstacleResult>()
        val stereo = rightBitmap != null

        // ── YOLO on left camera only (saves ~50% detection CPU vs running on both) ──
        val leftRaw = yolo.detect(leftBitmap)
        val leftYolo = if (stereo) leftRaw.map { it.copy(cx = it.cx * 0.5f, w = it.w * 0.5f) }
                       else leftRaw

        for (det in leftYolo) {
            if (det.label !in OBSTACLE_CLASSES) continue
            val direction = if (stereo) {
                if (det.cx < 0.25f) "left" else "forward"
            } else {
                when { det.cx < 0.33f -> "left"; det.cx > 0.67f -> "right"; else -> "forward" }
            }
            results.add(ObstacleResult(det.label, estimateDistance(det, null), direction, det.confidence))
        }

        // ── Face recognition: right camera in stereo mode, left as fallback ──
        val faceOverlay = mutableListOf<YoloResult>()
        val faceBitmap = rightBitmap ?: leftBitmap
        val faceDirection = if (stereo) "right" else "forward"

        val faces = faceRecognizer.detect(faceBitmap)
        for (face in faces) {
            val label = if (face.name == "Unknown") "face:unknown" else "face:${face.name}"
            val conf = if (face.name == "Unknown") 0.5f else face.similarity
            results.add(ObstacleResult(label, 150, faceDirection, conf))
            // Remap to right half of screen in stereo, full width in single-eye
            val screenCx = if (stereo) 0.5f + face.cx * 0.5f else face.cx
            val screenW  = if (stereo) face.w * 0.5f else face.w
            faceOverlay.add(YoloResult(-1, label, conf, screenCx, face.cy, screenW, face.h))
        }

        // Merge YOLO boxes (left only) + face boxes (both cameras) for the overlay
        lastYoloResults = leftYolo + faceOverlay

        return results
    }

    /** Face recognition via FaceSDK.yuv2Bitmap() — called from onNv21Frame when available.
     *  Skips the frame if a previous detection is still running to prevent JNI concurrency crashes. */
    fun detectFaces(nv21: ByteArray, width: Int, height: Int) {
        if (!faceDetecting.compareAndSet(false, true)) return
        try {
            faceRecognizer.detectFromNv21(nv21, width, height)
        } finally {
            faceDetecting.set(false)
        }
    }

    /** Legacy combined detect. */
    suspend fun detect(leftBitmap: Bitmap?, rightBitmap: Bitmap?): List<ObstacleResult> {
        if (leftBitmap == null) return emptyList()
        return detectObstacles(leftBitmap, rightBitmap)
    }

    private fun estimateDistance(det: YoloResult, depthNearestCm: Int?): Int {
        // Person height heuristic: assume ~170cm person with normalised bounding box height
        return when {
            det.label == "person" && det.h > 0.05f -> {
                val heuristic = (170f / det.h / 5f).toInt().coerceIn(20, 500)
                // Blend with depth map if available
                if (depthNearestCm != null) (heuristic * 0.6f + depthNearestCm * 0.4f).toInt()
                else heuristic
            }
            depthNearestCm != null -> depthNearestCm
            else -> (1f / (det.h.coerceAtLeast(0.01f)) * 30f).toInt().coerceIn(20, 500)
        }
    }

    fun close() {
        yolo.close()
        faceRecognizer.close()
    }
}
