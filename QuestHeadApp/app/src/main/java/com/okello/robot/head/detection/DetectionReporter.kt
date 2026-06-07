package com.okello.robot.head.detection

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "DetectionReporter"
private const val POLL_INTERVAL_MS = 2_000L
private const val MIN_REPORT_INTERVAL_MS = 5_000L

// Posts obstacle alerts to the Nexus phone app's /obstacle_alert endpoint.
// Called by MainActivity when camera frames are available.
//
// Motor enforcement (stop/slow) is deferred until Raspberry Pi 5 integration.
// See: deferred/obstacle_detection_deferred.md
class DetectionReporter(
    private val phoneIp: String,
    private val detector: ObstacleDetector
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private val lastReportedAt = mutableMapOf<String, Long>()

    fun start() {
        Log.i(TAG, "DetectionReporter started, phone=$phoneIp")
    }

    fun stop() {
        scope.cancel()
        http.dispatcher.executorService.shutdown()
    }

    fun onFramesAvailable(left: android.graphics.Bitmap?, right: android.graphics.Bitmap?) {
        scope.launch {
            try {
                val results = detector.detect(left, right)
                results.forEach { result ->
                    val key = result.className
                    val lastMs = lastReportedAt[key] ?: 0L
                    if (System.currentTimeMillis() - lastMs >= MIN_REPORT_INTERVAL_MS) {
                        postAlert(result)
                        lastReportedAt[key] = System.currentTimeMillis()
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Detection error: ${e.message}")
            }
        }
    }

    private fun postAlert(result: ObstacleResult) {
        try {
            val body = JSONObject().apply {
                put("type", result.className)
                put("direction", result.direction)
                put("distanceCm", result.distanceCm)
                put("confidence", result.confidence)
                put("timestampMs", System.currentTimeMillis())
            }.toString().toRequestBody("application/json".toMediaType())

            val resp = http.newCall(
                Request.Builder()
                    .url("http://$phoneIp:8080/obstacle_alert")
                    .post(body)
                    .build()
            ).execute()
            Log.i(TAG, "Alert posted: ${result.className} ${result.distanceCm}cm → ${resp.code}")
            resp.close()
        } catch (e: Exception) {
            Log.d(TAG, "postAlert failed: ${e.message}")
        }
    }
}
