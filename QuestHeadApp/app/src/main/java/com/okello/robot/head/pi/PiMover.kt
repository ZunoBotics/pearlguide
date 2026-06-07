package com.okello.robot.head.pi

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "PiMover"
private const val PI_PORT = 5000

class PiMover {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    @Volatile var piIp: String = ""

    // Active continuous-drive job (for "follow me", "keep going" etc.)
    private var driveJob: Job? = null

    // Velocity currently being applied in a continuous drive
    private var continuousX = 0f
    private var continuousY = 0f
    private var continuousTheta = 0f

    /** Execute a named movement command parsed from Gemini output. */
    fun execute(command: String) {
        when (command.trim().lowercase()) {
            "forward"    -> startContinuous(x = 0.18f,  y = 0f,    theta = 0f)
            "backward"   -> startContinuous(x = -0.18f, y = 0f,    theta = 0f)
            "left"       -> startContinuous(x = 0f,     y = 0.18f, theta = 0f)
            "right"      -> startContinuous(x = 0f,     y = -0.18f,theta = 0f)
            "turn_left"  -> startContinuous(x = 0f,     y = 0f,    theta = 35f)
            "turn_right" -> startContinuous(x = 0f,     y = 0f,    theta = -35f)
            "stop"       -> stopAll()
            else -> Log.w(TAG, "Unknown move command: $command")
        }
    }

    private fun startContinuous(x: Float, y: Float, theta: Float) {
        continuousX = x; continuousY = y; continuousTheta = theta
        driveJob?.cancel()
        driveJob = scope.launch {
            Log.i(TAG, "Continuous drive: x=$x y=$y theta=$theta")
            while (isActive) {
                post("/move", JSONObject().apply { put("x", x); put("y", y); put("theta", theta) })
                delay(300)
            }
        }
    }

    fun stopAll() {
        driveJob?.cancel()
        driveJob = null
        scope.launch { post("/stop", JSONObject()) }
        Log.i(TAG, "Stop")
    }

    private fun post(path: String, body: JSONObject) {
        val ip = piIp.ifBlank { return }
        try {
            val req = Request.Builder()
                .url("http://$ip:$PI_PORT$path")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().close()
        } catch (e: Exception) {
            Log.d(TAG, "$path failed: ${e.message}")
        }
    }

    fun close() {
        stopAll()
        scope.cancel()
    }
}
