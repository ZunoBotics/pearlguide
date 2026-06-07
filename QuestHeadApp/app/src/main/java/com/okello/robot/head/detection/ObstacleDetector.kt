package com.okello.robot.head.detection

import android.graphics.Bitmap
import com.okello.robot.head.camera.StereoDepthEstimator

// Obstacle detection pipeline stub.
// Current implementation: uses StereoDepthEstimator for distance measurement.
//
// TODO (after camera pipeline integration):
// 1. Replace StereoDepthEstimator with Quest 3 Scene API depth sensor (hardware IR depth)
//    for metric-accurate distances — see Quest Platform SDK, ScenePlane / SceneAnchor APIs
// 2. Add YOLOv8-nano object classifier:
//    - Convert model to TFLite (yolov8n.pt → onnx → tflite with int8 quant)
//    - Run on Hexagon NPU via TFLite delegate: TfLiteHexagonDelegateCreate
//    - Feed left RGB frame → get bounding boxes + class labels
// 3. Combine depth map with YOLO bounding boxes to assign distance per detected object
// 4. Enable physical staircase detection:
//    - Scan depth row at ~60% of frame height (floor plane region)
//    - Drop-off > 15cm within 3 consecutive pixels → classify as staircase
//
// Motor stop/slow actions: deferred until Raspberry Pi 5 integration
// See: deferred/obstacle_detection_deferred.md

data class ObstacleResult(
    val className: String,    // "staircase", "person", "wall", "furniture", "door_closed", "object_floor"
    val distanceCm: Int,
    val direction: String,    // "forward", "left", "right"
    val confidence: Float
)

class ObstacleDetector {

    fun detect(leftBitmap: Bitmap?, rightBitmap: Bitmap?): List<ObstacleResult> {
        if (leftBitmap == null || rightBitmap == null) return emptyList()

        val results = mutableListOf<ObstacleResult>()

        val depthResult = StereoDepthEstimator.compute(leftBitmap, rightBitmap)
        val nearestCm = depthResult.nearestCm?.toInt() ?: return emptyList()

        // YOLO classification not yet available — use distance-only heuristic
        if (nearestCm < 200) {
            val className = "obstacle"  // will be replaced by YOLO class label
            val severity = when {
                nearestCm < 60 -> "danger"
                nearestCm < 100 -> "caution"
                else -> "clear"
            }
            if (severity != "clear") {
                results.add(ObstacleResult(
                    className = className,
                    distanceCm = nearestCm,
                    direction = "forward",
                    confidence = 0.70f  // placeholder until YOLO confidence available
                ))
            }
        }

        return results
    }
}
