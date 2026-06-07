package com.zunobotics.okellonexus.data.mqtt

import android.util.Log
import com.zunobotics.okellonexus.data.db.entity.KnowledgeEntry
import com.zunobotics.okellonexus.data.repository.FaceProfileRepository
import com.zunobotics.okellonexus.data.repository.KnowledgeRepository
import com.zunobotics.okellonexus.data.repository.LocationRepository
import com.zunobotics.okellonexus.data.repository.PersonaRepository
import com.zunobotics.okellonexus.data.repository.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject
import java.net.ServerSocket
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ConfigHttpServer"

@Singleton
class ConfigHttpServer @Inject constructor(
    private val personaRepo: PersonaRepository,
    private val locationRepo: LocationRepository,
    private val settingsRepo: SettingsRepository,
    private val knowledgeRepo: KnowledgeRepository,
    private val faceProfileRepo: FaceProfileRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null

    fun start(port: Int = 8080) {
        scope.launch {
            try {
                serverSocket = ServerSocket(port).also { it.reuseAddress = true }
                Log.i(TAG, "Config HTTP server listening on :$port")
                while (isActive) {
                    val socket = serverSocket!!.accept()
                    launch {
                        try {
                            val reader = socket.getInputStream().bufferedReader()
                            val firstLine = reader.readLine() ?: ""
                            val parts = firstLine.split(" ")
                            val method = parts.getOrElse(0) { "GET" }
                            val path = parts.getOrElse(1) { "/config" }

                            var contentLength = 0
                            var headerLine = reader.readLine()
                            while (!headerLine.isNullOrBlank()) {
                                if (headerLine.startsWith("Content-Length:", ignoreCase = true))
                                    contentLength = headerLine.substringAfter(":").trim().toIntOrNull() ?: 0
                                headerLine = reader.readLine()
                            }

                            val out = socket.getOutputStream()
                            if (method == "POST" && path.startsWith("/knowledge") && contentLength > 0) {
                                val buf = CharArray(contentLength)
                                reader.read(buf, 0, contentLength)
                                handlePostKnowledge(String(buf))
                                out.write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".toByteArray())
                            } else {
                                val json = buildConfigJson()
                                val body = json.toByteArray(Charsets.UTF_8)
                                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                out.write(body)
                            }
                            out.flush()
                        } catch (e: Exception) {
                            Log.d(TAG, "Request error: ${e.message}")
                        } finally {
                            socket.close()
                        }
                    }
                }
            } catch (e: Exception) {
                if (scope.isActive) Log.e(TAG, "Server error: ${e.message}")
            }
        }
    }

    fun stop() {
        scope.cancel()
        try { serverSocket?.close() } catch (_: Exception) {}
    }

    private suspend fun handlePostKnowledge(body: String) {
        try {
            val j = JSONObject(body)
            val entry = KnowledgeEntry(
                id = UUID.randomUUID().toString(),
                title = j.optString("title"),
                content = j.optString("content"),
                category = j.optString("category", "taught"),
                personaId = j.optString("personaId"),
                locationId = j.optString("locationId").ifBlank { null },
                source = "taught"
            )
            knowledgeRepo.add(entry)
            Log.i(TAG, "Quest taught new fact: ${entry.title}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save taught fact: ${e.message}")
        }
    }

    private suspend fun buildConfigJson(): String {
        val persona = personaRepo.getActive()
        val location = locationRepo.getActive()
        val settings = settingsRepo.settings.first()
        val people = faceProfileRepo.profiles.first()

        val tags: List<String> = try {
            Json.decodeFromString(persona?.personality ?: "[]")
        } catch (_: Exception) { emptyList() }

        val languages = settings.selectedLanguages.split(",").filter { it.isNotBlank() }

        val personaId = persona?.id ?: ""
        val knowledge = if (personaId.isBlank()) knowledgeRepo.getAll()
                        else knowledgeRepo.getByPersona(personaId)

        val factsArray = JsonArray(knowledge.map { f ->
            buildJsonObject {
                put("id", f.id); put("title", f.title)
                put("content", f.content); put("category", f.category)
            }
        })

        val peopleArray = JsonArray(people.map { p ->
            buildJsonObject {
                put("id", p.id)
                put("name", p.name)
                put("roleTag", p.roleTag)
                put("isVip", p.isCurrentVip)
                put("notes", p.notes)
            }
        })

        return buildJsonObject {
            put("personaName", persona?.name ?: "")
            put("role", persona?.role ?: "")
            put("greeting", persona?.greeting ?: "")
            put("personality", Json.encodeToString(tags))
            put("extraInstructions", persona?.extraInstructions ?: "")
            put("languages", Json.encodeToString(languages))
            put("codeSwitching", settings.codeSwitching)
            put("locationName", location?.name ?: "")
            put("locationDescription", location?.description ?: "")
            put("locationOpeningHours", location?.openingHours ?: "")
            put("locationSpecialInstructions", location?.specialInstructions ?: "")
            put("facts", factsArray)
            put("people", peopleArray)
            put("pendingCommand", settings.pendingCommand)
            put("learningMode", settings.learningMode)
        }.toString()
    }
}
