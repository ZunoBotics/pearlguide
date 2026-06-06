package com.zunobotics.okellonexus.data.repository

import android.util.Log
import com.zunobotics.okellonexus.data.model.RobotConnectionState
import com.zunobotics.okellonexus.data.mqtt.IncomingMessage
import com.zunobotics.okellonexus.data.mqtt.MqttClientWrapper
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MqttRepository"

@Singleton
class MqttRepository @Inject constructor(
    private val client: MqttClientWrapper
) {
    val connectionState: StateFlow<RobotConnectionState> = client.connectionState
    val incomingMessages: SharedFlow<IncomingMessage> = client.incomingMessages

    fun connect(brokerUri: String, clientId: String) = client.connect(brokerUri, clientId)
    fun disconnect() = client.disconnect()

    private fun publish(topic: String, payload: String, qos: Int = 1, retained: Boolean = false) {
        client.publish(topic, payload.toByteArray(), qos, retained)
        Log.d(TAG, "Published [$topic]: ${payload.take(80)}")
    }

    // ─── Config ───────────────────────────────────────────────────────────────

    fun sendPersona(
        name: String, role: String, greeting: String,
        personality: List<String>, voiceSpeed: Float,
        extraInstructions: String, languageCode: String
    ) {
        val payload = buildJsonObject {
            put("name", name); put("role", role); put("greeting", greeting)
            put("personality", Json.encodeToString(personality))
            put("voice_speed", voiceSpeed)
            put("extra_instructions", extraInstructions)
            put("language_code", languageCode)
            put("timestamp_ms", System.currentTimeMillis())
        }
        publish("robot/config/persona", payload.toString(), qos = 1, retained = true)
    }

    fun sendLocation(
        name: String, type: String, description: String,
        gpsLat: Double?, gpsLng: Double?, openingHours: String,
        notes: String, specialInstructions: String
    ) {
        val payload = buildJsonObject {
            put("name", name); put("type", type); put("description", description)
            if (gpsLat != null && gpsLng != null) {
                putJsonObject("gps") { put("lat", gpsLat); put("lng", gpsLng) }
            }
            put("opening_hours", openingHours)
            put("notes", notes)
            put("special_instructions", specialInstructions)
            put("timestamp_ms", System.currentTimeMillis())
        }
        publish("robot/config/location", payload.toString(), qos = 1, retained = true)
    }

    fun sendLanguage(languages: List<String>, codeSwitching: Boolean) {
        val payload = buildJsonObject {
            put("languages", Json.encodeToString(languages))
            put("code_switching", codeSwitching)
            put("timestamp_ms", System.currentTimeMillis())
        }
        publish("robot/config/language", payload.toString(), qos = 1, retained = true)
    }

    // ─── Knowledge ────────────────────────────────────────────────────────────

    fun addKnowledge(id: String, title: String, content: String, category: String,
                     tags: List<String>, locationSpecific: Boolean, source: String) {
        val payload = buildJsonObject {
            put("id", id); put("title", title); put("content", content); put("category", category)
            put("tags", Json.encodeToString(tags))
            put("location_specific", locationSpecific); put("source", source)
            put("timestamp_ms", System.currentTimeMillis())
        }
        publish("robot/knowledge/add", payload.toString())
    }

    fun deleteKnowledge(id: String) {
        val payload = buildJsonObject {
            put("id", id); put("timestamp_ms", System.currentTimeMillis())
        }
        publish("robot/knowledge/delete", payload.toString())
    }

    fun sendKnowledgeSnapshot(entries: List<com.zunobotics.okellonexus.data.db.entity.KnowledgeEntry>) {
        val factsArray = JsonArray(entries.map { e ->
            buildJsonObject {
                put("id", e.id); put("title", e.title)
                put("content", e.content); put("category", e.category)
            }
        })
        val payload = buildJsonObject {
            put("facts", factsArray)
            put("timestamp_ms", System.currentTimeMillis())
        }
        publish("robot/knowledge/snapshot", payload.toString(), qos = 1, retained = true)
    }

    // ─── Commands ─────────────────────────────────────────────────────────────

    fun sendCustomInstruction(instruction: String, durationMinutes: Int = 0) {
        val payload = buildJsonObject {
            put("type", "custom_instruction"); put("instruction", instruction)
            put("duration_minutes", durationMinutes)
            put("timestamp_ms", System.currentTimeMillis())
        }
        publish("robot/command", payload.toString())
    }

    fun sendNavigate(target: String) {
        val payload = buildJsonObject {
            put("type", "navigate"); put("target", target)
            put("timestamp_ms", System.currentTimeMillis())
        }
        publish("robot/command", payload.toString())
    }

    fun sendQuickCommand(type: String) {
        val payload = buildJsonObject {
            put("type", type); put("timestamp_ms", System.currentTimeMillis())
        }
        publish("robot/command", payload.toString())
    }

    fun sendEmergencyStop() {
        val payload = buildJsonObject {
            put("type", "stop"); put("timestamp_ms", System.currentTimeMillis())
        }
        client.publish("robot/emergency", payload.toString().toByteArray(), qos = 2, retained = false)
    }

    // ─── Teach mode ───────────────────────────────────────────────────────────

    fun startTeachMode() = publish("robot/teach/start", "{}")
    fun stopTeachMode() = publish("robot/teach/stop", "{}")
    fun saveTeachView(label: String) {
        val payload = buildJsonObject { put("label", label); put("timestamp_ms", System.currentTimeMillis()) }
        publish("robot/teach/save_view", payload.toString())
    }
}
