package com.zunobotics.okellonexus.data.repository

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

private const val TAG = "PiRepository"
private const val PI_PORT = 5000

@Singleton
class PiRepository @Inject constructor(
    private val settingsRepo: SettingsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .build()

    @Volatile private var piIp = "10.154.26.49"

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    init {
        scope.launch {
            settingsRepo.settings.collect { piIp = it.piIp }
        }
        scope.launch { pingLoop() }
    }

    private suspend fun pingLoop() {
        while (true) {
            val ok = try {
                val resp = client.newCall(
                    Request.Builder().url("http://$piIp:$PI_PORT/ping").get().build()
                ).execute()
                val isOk = resp.code == 200
                resp.close()
                isOk
            } catch (_: Exception) { false }
            if (_connected.value != ok) {
                _connected.value = ok
                Log.i(TAG, "Pi ${if (ok) "connected" else "disconnected"} ($piIp)")
            }
            delay(if (ok) 8000L else 5000L)
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
            } catch (e: Exception) {
                Log.w(TAG, "move failed: ${e.message}")
            }
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
            } catch (e: Exception) {
                Log.w(TAG, "stop failed: ${e.message}")
            }
        }
    }
}
