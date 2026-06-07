package com.okello.robot.head.mqtt

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val TAG = "ConfigPoller"
private const val CONFIG_PORT = 8080
private const val POLL_INTERVAL_MS = 3_000L
private const val PREFS_NAME = "nexus"
private const val KEY_PHONE_IP = "broker_ip"
private const val FALLBACK_IP = "192.168.49.1"

class ConfigPoller(
    private val context: Context,
    private val onConfigUpdate: (NexusConfig) -> Unit,
    private val onStatus: ((String) -> Unit)? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private var lastJson = ""

    fun start() {
        scope.launch { pollLoop() }
        Log.i(TAG, "ConfigPoller started")
    }

    fun stop() {
        scope.cancel()
        http.dispatcher.executorService.shutdown()
        Log.i(TAG, "ConfigPoller stopped")
    }

    private suspend fun pollLoop() {
        while (scope.isActive) {
            val ip = getPhoneIp()
            try {
                val url = "http://$ip:$CONFIG_PORT/config"
                val response = withContext(Dispatchers.IO) {
                    http.newCall(Request.Builder().url(url).build()).execute()
                }
                val json = response.body?.string() ?: ""
                response.close()

                if (json.isNotBlank() && json != lastJson) {
                    lastJson = json
                    Log.i(TAG, "Config updated from $ip")
                    parseAndNotify(json)
                    onStatus?.invoke("Config OK — $ip")
                } else if (json.isNotBlank()) {
                    onStatus?.invoke("Sync OK — $ip")
                }
            } catch (e: Exception) {
                val msg = "Config unreachable: $ip — ${e.message?.take(40)}"
                Log.w(TAG, msg)
                onStatus?.invoke(msg)
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun parseAndNotify(json: String) {
        try {
            val j = JSONObject(json)
            val config = NexusConfig(
                personaName           = j.optString("personaName"),
                role                  = j.optString("role"),
                greeting              = j.optString("greeting"),
                personalityTraits     = parseStringArray(j.optString("personality", "[]")),
                extraInstructions     = j.optString("extraInstructions"),
                selectedLanguages     = parseStringArray(j.optString("languages", "[\"en\"]")),
                codeSwitching         = j.optBoolean("codeSwitching", false),
                locationName          = j.optString("locationName"),
                locationDescription   = j.optString("locationDescription"),
                locationOpeningHours  = j.optString("locationOpeningHours"),
                locationSpecialInstructions = j.optString("locationSpecialInstructions"),
                knowledgeFacts        = parseFacts(j.optJSONArray("facts")),
                pendingCommand        = j.optString("pendingCommand"),
                enrolledPeople        = parsePeople(j.optJSONArray("people"))
            )
            onConfigUpdate(config)
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
        }
    }

    private fun getPhoneIp(): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PHONE_IP, FALLBACK_IP) ?: FALLBACK_IP

    private fun parseStringArray(json: String): List<String> = try {
        val arr = JSONArray(json); (0 until arr.length()).map { arr.getString(it) }
    } catch (_: Exception) { emptyList() }

    private fun parseFacts(arr: JSONArray?): List<KnowledgeFact> {
        arr ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val f = arr.getJSONObject(i)
            KnowledgeFact(
                id       = f.optString("id").ifEmpty { UUID.randomUUID().toString() },
                title    = f.optString("title"),
                content  = f.optString("content"),
                category = f.optString("category")
            )
        }
    }

    private fun parsePeople(arr: JSONArray?): List<EnrolledPerson> {
        arr ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            EnrolledPerson(
                id      = p.optString("id").ifEmpty { UUID.randomUUID().toString() },
                name    = p.optString("name"),
                roleTag = p.optString("roleTag", "Guest"),
                isVip   = p.optBoolean("isVip", false),
                notes   = p.optString("notes")
            )
        }
    }
}
