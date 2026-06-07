package com.okello.robot.head.mqtt

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private const val TAG = "NexusCommandClient"
private const val BROKER_PORT = 1883
private const val PREFS_NAME = "nexus"
private const val KEY_BROKER_IP = "broker_ip"
// Fallback when no IP is configured — phone hotspot mode
private const val HOTSPOT_FALLBACK = "192.168.49.1"

class NexusCommandClient(
    private val context: Context,
    private val onConfigUpdate: (NexusConfig) -> Unit,
    private val onCommand: (type: String, payload: String) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mqttClient: MqttAsyncClient? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val knowledgeFacts = mutableMapOf<String, KnowledgeFact>()
    private var config = NexusConfig()

    fun start() {
        scope.launch { discoverAndConnect() }
    }

    fun stop() {
        scope.cancel()
        try { mqttClient?.disconnect() } catch (_: Exception) {}
    }

    // ─── Discovery ────────────────────────────────────────────────────────────

    private suspend fun discoverAndConnect() {
        while (scope.isActive) {
            val host = getConfiguredBrokerIp() ?: HOTSPOT_FALLBACK
            Log.i(TAG, "Connecting to broker: $host")
            tryConnect("tcp://$host:$BROKER_PORT")
            // tryConnect blocks until disconnect; wait before retrying
            delay(3_000)
        }
    }

    /** Reads the manually configured broker IP from SharedPreferences. */
    private fun getConfiguredBrokerIp(): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BROKER_IP, "")?.ifEmpty { null }

    // ─── MQTT connection ──────────────────────────────────────────────────────

    /** Connects and blocks until the connection is lost, then returns. */
    private suspend fun tryConnect(brokerUri: String) {
        try {
            val client = MqttAsyncClient(
                brokerUri,
                "quest-head-${UUID.randomUUID()}",
                MemoryPersistence()
            )
            mqttClient = client

            val disconnected = CompletableDeferred<Unit>()

            client.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    _isConnected.value = false
                    Log.w(TAG, "Lost broker connection: ${cause?.message}")
                    disconnected.complete(Unit)
                }
                override fun messageArrived(topic: String, message: MqttMessage) {
                    handleMessage(topic, String(message.payload, Charsets.UTF_8))
                }
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            val opts = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 10
                keepAliveInterval = 30
                isAutomaticReconnect = false
            }

            client.connect(opts).waitForCompletion(10_000)
            _isConnected.value = true
            Log.i(TAG, "Connected to $brokerUri")

            client.subscribe("robot/config/persona",      1)
            client.subscribe("robot/config/language",     1)
            client.subscribe("robot/config/location",     1)
            client.subscribe("robot/knowledge/add",       1)
            client.subscribe("robot/knowledge/delete",    1)
            client.subscribe("robot/knowledge/snapshot",  1)
            client.subscribe("robot/command",             0)
            client.subscribe("robot/emergency",           0)

            disconnected.await() // wait here until connectionLost fires

        } catch (e: Exception) {
            _isConnected.value = false
            Log.w(TAG, "Connect to $brokerUri failed: ${e.message}")
            delay(5_000)
        }
    }

    // ─── Message handling ─────────────────────────────────────────────────────

    private fun handleMessage(topic: String, payload: String) {
        Log.d(TAG, "[$topic] $payload")
        try {
            when (topic) {
                "robot/config/persona" -> {
                    val j = JSONObject(payload)
                    val langCode = j.optString("language_code", "")
                    config = config.copy(
                        personaName       = j.optString("name", config.personaName),
                        role              = j.optString("role", config.role),
                        greeting          = j.optString("greeting", config.greeting),
                        personalityTraits = parseStringArray(j.optString("personality", "[]")),
                        extraInstructions = j.optString("extra_instructions", config.extraInstructions),
                        selectedLanguages = if (langCode.isNotBlank()) listOf(langCode) else config.selectedLanguages
                    )
                    onConfigUpdate(config)
                }
                "robot/config/language" -> {
                    val j = JSONObject(payload)
                    val langs = parseStringArray(j.optString("languages", "[]"))
                    config = config.copy(
                        selectedLanguages = langs.ifEmpty { config.selectedLanguages },
                        codeSwitching     = j.optBoolean("code_switching", config.codeSwitching)
                    )
                    onConfigUpdate(config)
                }
                "robot/knowledge/add" -> {
                    val j = JSONObject(payload)
                    val id = j.optString("id").ifEmpty { UUID.randomUUID().toString() }
                    knowledgeFacts[id] = KnowledgeFact(
                        id       = id,
                        title    = j.optString("title"),
                        content  = j.optString("content"),
                        category = j.optString("category")
                    )
                    config = config.copy(knowledgeFacts = knowledgeFacts.values.toList())
                    onConfigUpdate(config)
                }
                "robot/knowledge/delete" -> {
                    val id = JSONObject(payload).optString("id")
                    knowledgeFacts.remove(id)
                    config = config.copy(knowledgeFacts = knowledgeFacts.values.toList())
                    onConfigUpdate(config)
                }
                "robot/config/location" -> {
                    val j = JSONObject(payload)
                    config = config.copy(
                        locationName                = j.optString("name", config.locationName),
                        locationDescription         = j.optString("description", config.locationDescription),
                        locationOpeningHours        = j.optString("opening_hours", config.locationOpeningHours),
                        locationSpecialInstructions = j.optString("special_instructions", config.locationSpecialInstructions)
                    )
                    onConfigUpdate(config)
                }
                "robot/knowledge/snapshot" -> {
                    val factsArr = JSONObject(payload).optJSONArray("facts") ?: return
                    knowledgeFacts.clear()
                    for (i in 0 until factsArr.length()) {
                        val f = factsArr.getJSONObject(i)
                        val id = f.optString("id").ifEmpty { UUID.randomUUID().toString() }
                        knowledgeFacts[id] = KnowledgeFact(id, f.optString("title"), f.optString("content"), f.optString("category"))
                    }
                    config = config.copy(knowledgeFacts = knowledgeFacts.values.toList())
                    onConfigUpdate(config)
                }
                "robot/command"  -> onCommand(JSONObject(payload).optString("type", "custom_instruction"), payload)
                "robot/emergency" -> onCommand("EMERGENCY_STOP", payload)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse error [$topic]: ${e.message}")
        }
    }

    private fun parseStringArray(json: String): List<String> = try {
        val arr = JSONArray(json); (0 until arr.length()).map { arr.getString(it) }
    } catch (_: Exception) { emptyList() }
}
