package com.okello.robot.head.camera

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

private const val TAG = "FrameStreamer"
private const val RATE_LIMIT_MS = 1500L

data class DetectionInfo(val type: String, val direction: String, val distanceCm: Int)

class FrameStreamer(private val phoneIp: String) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lastSentAt = AtomicLong(0)
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    fun sendFrame(base64Jpeg: String, detections: List<DetectionInfo>) {
        val now = System.currentTimeMillis()
        if (now - lastSentAt.get() < RATE_LIMIT_MS) return
        lastSentAt.set(now)

        scope.launch {
            try {
                val detectionsArray = JSONArray().apply {
                    detections.forEach { d ->
                        put(JSONObject().apply {
                            put("type", d.type)
                            put("direction", d.direction)
                            put("distanceCm", d.distanceCm)
                        })
                    }
                }
                val body = JSONObject().apply {
                    put("jpeg", base64Jpeg)
                    put("detections", detectionsArray)
                }.toString()

                val request = Request.Builder()
                    .url("http://$phoneIp:8080/camera_frame")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "POST /camera_frame returned ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "sendFrame failed: ${e.message}")
            }
        }
    }

    fun stop() {
        scope.cancel()
        client.connectionPool.evictAll()
    }
}
