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

private const val TAG = "ObstacleDetector"

data class ObstacleResult(
    val className: String,
    val distanceCm: Int,
    val direction: String,  // "left", "forward", "right"
    val confidence: Float
)

class ObstacleDetector(context: Context) {

    private val yolo = YoloDetector(context)
    val faceRecognizer = FaceRecognizer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun enrollFaces(people: List<EnrolledPerson>) {
        scope.launch { faceRecognizer.enroll(people) }
    }

    /**
     * Runs YOLO on leftBitmap and stereo depth on both bitmaps.
     * Merges bounding-box horizontal position → direction, and depth map → distanceCm.
     * Also runs face detection; matched faces are returned as additional results.
     */
    suspend fun detect(leftBitmap: Bitmap?, rightBitmap: Bitmap?): List<ObstacleResult> {
        if (leftBitmap == null) return emptyList()

        val results = mutableListOf<ObstacleResult>()

        // ── YOLO object detection ───────────────────────────────────────────
        val yoloResults = yolo.detect(leftBitmap)

        // ── Stereo depth (used to refine distance per bounding box region) ──
        val depthResult = if (rightBitmap != null)
            StereoDepthEstimator.compute(leftBitmap, rightBitmap) else null
        val globalNearestCm = depthResult?.nearestCm?.toInt()

        for (det in yoloResults) {
            if (det.label !in OBSTACLE_CLASSES) continue

            val direction = when {
                det.cx < 0.33f -> "left"
                det.cx > 0.67f -> "right"
                else -> "forward"
            }

            // Estimate distance: use bounding box height as a proxy (person ~170cm tall)
            // If we have a depth map, use the median in the bbox region instead.
            val distanceCm = estimateDistance(det, globalNearestCm)

            results.add(ObstacleResult(
                className = det.label,
                distanceCm = distanceCm,
                direction = direction,
                confidence = det.confidence
            ))
        }

        // ── Face recognition ────────────────────────────────────────────────
        val faceMatches = faceRecognizer.detect(leftBitmap)
        for (face in faceMatches) {
            val label = if (face.name == "Unknown") "face:unknown" else "face:${face.name}"
            // Face position defaults to forward (centre of frame) since we don't track exact position here
            results.add(ObstacleResult(
                className = label,
                distanceCm = 150, // approximate — no per-face depth yet
                direction = "forward",
                confidence = if (face.name == "Unknown") 0.5f else face.similarity
            ))
        }

        // ── Fallback: depth-only heuristic if YOLO found nothing ────────────
        if (results.isEmpty() && globalNearestCm != null && globalNearestCm < 120) {
            results.add(ObstacleResult("obstacle", globalNearestCm, "forward", 0.6f))
        }

        return results
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
