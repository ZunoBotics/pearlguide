package com.zunobotics.okellonexus.data.repository

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

private const val TAG = "PiRepository"
private const val PI_PORT = 5000

// Phone's own ConfigHttpServer already builds the full config (same as what Quest polled).
// We fetch it locally and forward it to the Pi — no duplication, guaranteed to be identical.
private const val LOCAL_CONFIG_URL = "http://127.0.0.1:8080/config"

@Singleton
class PiRepository @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val cameraStreamRepo: CameraStreamRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    @Volatile private var piIp = "100.116.191.56"

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _agentRunning = MutableStateFlow(true)
    val agentRunning: StateFlow<Boolean> = _agentRunning.asStateFlow()

    data class TemperatureReading(val ambientC: Float, val objectC: Float)
    private val _temperature = MutableStateFlow<TemperatureReading?>(null)
    val temperature: StateFlow<TemperatureReading?> = _temperature.asStateFlow()

    private var sessionJob: Job? = null

    init {
        scope.launch { settingsRepo.settings.collect { piIp = it.piIp } }
        scope.launch { pingLoop() }
    }

    private suspend fun pingLoop() {
        while (coroutineContext.isActive) {
            val ok = try {
                val resp = client.newCall(
                    Request.Builder().url("http://$piIp:$PI_PORT/ping").get().build()
                ).execute()
                val isOk = resp.code == 200
                if (isOk) {
                    try {
                        val body = resp.body?.string() ?: ""
                        resp.close()
                        val json = org.json.JSONObject(body)
                        val tempObj = json.optJSONObject("temperature")
                        if (tempObj != null && tempObj.optBoolean("ok", false)) {
                            _temperature.value = TemperatureReading(
                                ambientC = tempObj.optDouble("ambient", 0.0).toFloat(),
                                objectC  = tempObj.optDouble("object", 0.0).toFloat()
                            )
                        }
                    } catch (_: Exception) { resp.close() }
                } else {
                    resp.close()
                }
                isOk
            } catch (_: Exception) { false }

            if (_connected.value != ok) {
                _connected.value = ok
                Log.i(TAG, "Pi ${if (ok) "connected" else "disconnected"} ($piIp)")
                if (ok) startSession() else stopSession()
            }
            delay(if (ok) 8000L else 5000L)
        }
    }

    private fun startSession() {
        sessionJob?.cancel()
        sessionJob = scope.launch {
            fetchAgentStatus()
            syncConfig()
            launch { cameraLoop() }
            launch { configSyncLoop() }
        }
    }

    private fun stopSession() {
        sessionJob?.cancel()
        sessionJob = null
    }

    private suspend fun cameraLoop() {
        while (coroutineContext.isActive) {
            try {
                val resp = client.newCall(
                    Request.Builder().url("http://$piIp:$PI_PORT/frame").get().build()
                ).execute()
                if (resp.code == 200) {
                    val bytes = resp.body?.bytes()
                    resp.close()
                    if (bytes != null && bytes.isNotEmpty()) {
                        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        cameraStreamRepo.push(CameraFrame(jpegBase64 = b64, detections = emptyList()))
                    }
                } else resp.close()
            } catch (_: Exception) {}
            delay(150L)
        }
    }

    private suspend fun configSyncLoop() {
        while (coroutineContext.isActive) {
            delay(60_000L)
            syncConfig()
        }
    }

    private suspend fun syncConfig() {
        try {
            // Fetch from the phone's own ConfigHttpServer (same data Quest used to poll)
            val configResp = client.newCall(
                Request.Builder().url(LOCAL_CONFIG_URL).get().build()
            ).execute()
            if (configResp.code != 200) { configResp.close(); return }
            val configJson = configResp.body?.string() ?: run { configResp.close(); return }
            configResp.close()

            // Forward it to the Pi
            client.newCall(
                Request.Builder()
                    .url("http://$piIp:$PI_PORT/config")
                    .post(configJson.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().close()

            Log.i(TAG, "Config synced to Pi")
        } catch (e: Exception) {
            Log.w(TAG, "Config sync failed: ${e.message}")
        }
    }

    private suspend fun fetchAgentStatus() {
        try {
            val resp = client.newCall(
                Request.Builder().url("http://$piIp:$PI_PORT/agent/status").get().build()
            ).execute()
            if (resp.code == 200) {
                val json = JSONObject(resp.body?.string() ?: "{}")
                resp.close()
                _agentRunning.value = json.optBoolean("running", true)
            } else resp.close()
        } catch (_: Exception) {}
    }

    fun startAgent() {
        scope.launch {
            try {
                client.newCall(
                    Request.Builder()
                        .url("http://$piIp:$PI_PORT/agent/start")
                        .post("{}".toRequestBody("application/json".toMediaType()))
                        .build()
                ).execute().close()
                _agentRunning.value = true
            } catch (e: Exception) { Log.w(TAG, "startAgent: ${e.message}") }
        }
    }

    fun stopAgent() {
        scope.launch {
            try {
                client.newCall(
                    Request.Builder()
                        .url("http://$piIp:$PI_PORT/agent/stop")
                        .post("{}".toRequestBody("application/json".toMediaType()))
                        .build()
                ).execute().close()
                _agentRunning.value = false
            } catch (e: Exception) { Log.w(TAG, "stopAgent: ${e.message}") }
        }
    }

    fun move(x: Float, y: Float, theta: Float) {
        scope.launch {
            try {
                val body = JSONObject().apply {
                    put("x", x); put("y", y); put("theta", theta)
                }.toString().toRequestBody("application/json".toMediaType())
                client.newCall(
                    Request.Builder().url("http://$piIp:$PI_PORT/move").post(body).build()
                ).execute().close()
            } catch (e: Exception) { Log.w(TAG, "move: ${e.message}") }
        }
    }

    fun stop() {
        scope.launch {
            try {
                client.newCall(
                    Request.Builder()
                        .url("http://$piIp:$PI_PORT/stop")
                        .post("{}".toRequestBody("application/json".toMediaType()))
                        .build()
                ).execute().close()
            } catch (e: Exception) { Log.w(TAG, "stop: ${e.message}") }
        }
    }
}
