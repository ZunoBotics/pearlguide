package com.zunobotics.okellonexus.data.mqtt

import android.util.Log
import com.zunobotics.okellonexus.data.db.entity.KnowledgeEntry
import com.zunobotics.okellonexus.data.repository.CameraDetection
import com.zunobotics.okellonexus.data.repository.CameraFrame
import com.zunobotics.okellonexus.data.repository.CameraStreamRepository
import com.zunobotics.okellonexus.data.repository.FaceProfileRepository
import com.zunobotics.okellonexus.data.repository.KnowledgeRepository
import com.zunobotics.okellonexus.data.repository.LocationRepository
import com.zunobotics.okellonexus.data.repository.ObstacleAlert
import com.zunobotics.okellonexus.data.repository.ObstacleAlertRepository
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
    private val faceProfileRepo: FaceProfileRepository,
    private val obstacleRepo: ObstacleAlertRepository,
    private val cameraStreamRepo: CameraStreamRepository
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
                            val bodyText = if (method == "POST" && contentLength > 0) {
                                // reader.read() may return fewer chars than requested for large bodies
                                val buf = CharArray(contentLength)
                                var totalRead = 0
                                while (totalRead < contentLength) {
                                    val n = reader.read(buf, totalRead, contentLength - totalRead)
                                    if (n == -1) break
                                    totalRead += n
                                }
                                String(buf, 0, totalRead)
                            } else ""

                            if (method == "POST" && path.startsWith("/enroll_face") && bodyText.isNotEmpty()) {
                                handlePostEnrollFace(bodyText)
                                out.write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".toByteArray())
                            } else if (method == "POST" && path.startsWith("/knowledge") && bodyText.isNotEmpty()) {
                                handlePostKnowledge(bodyText)
                                out.write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".toByteArray())
                            } else if (method == "POST" && path.startsWith("/obstacle_alert") && bodyText.isNotEmpty()) {
                                handlePostObstacle(bodyText)
                                out.write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".toByteArray())
                            } else if (method == "POST" && path.startsWith("/camera_frame") && bodyText.isNotEmpty()) {
                                handlePostCameraFrame(bodyText)
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

    private fun handlePostCameraFrame(body: String) {
        try {
            val j = JSONObject(body)
            val jpeg = j.optString("jpeg")
            if (jpeg.isBlank()) return
            val detectionsArr = j.optJSONArray("detections")
            val detections = if (detectionsArr != null) {
                (0 until detectionsArr.length()).map { i ->
                    val d = detectionsArr.getJSONObject(i)
                    CameraDetection(
                        type = d.optString("type", "obstacle"),
                        direction = d.optString("direction", "forward"),
                        distanceCm = d.optInt("distanceCm", 0)
                    )
                }
            } else emptyList()
            cameraStreamRepo.push(CameraFrame(jpegBase64 = jpeg, detections = detections))
        } catch (e: Exception) {
            Log.d(TAG, "camera_frame parse error: ${e.message}")
        }
    }

    private fun handlePostObstacle(body: String) {
        try {
            val j = JSONObject(body)
            val severity = when {
                j.optInt("distanceCm", 200) < 60 -> "danger"
                j.optInt("distanceCm", 200) < 100 -> "caution"
                j.optString("type") == "staircase" -> "danger"
                else -> "info"
            }
            obstacleRepo.push(ObstacleAlert(
                type = j.optString("type", "obstacle"),
                direction = j.optString("direction", "forward"),
                distanceCm = j.optInt("distanceCm", 0),
                confidence = j.optDouble("confidence", 0.0).toFloat(),
                severity = severity
            ))
            Log.i(TAG, "Obstacle alert: ${j.optString("type")} at ${j.optInt("distanceCm")}cm")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse obstacle alert: ${e.message}")
        }
    }

    private suspend fun handlePostEnrollFace(body: String) {
        try {
            val j = JSONObject(body)
            val name = j.optString("name").ifBlank { return }
            val profile = com.zunobotics.okellonexus.data.db.entity.FaceProfile(
                name = name,
                roleTag = j.optString("roleTag", "Visitor"),
                notes = j.optString("notes", ""),
                photoBase64 = j.optString("photoBase64", "")
            )
            faceProfileRepo.save(profile)
            Log.i(TAG, "New face enrolled from Quest: $name (${profile.roleTag})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enroll face: ${e.message}")
        }
    }

    private suspend fun handlePostKnowledge(body: String) {
        try {
            val j = JSONObject(body)
            val imageBase64 = j.optString("imageBase64").ifBlank { null }
            val entry = KnowledgeEntry(
                id = UUID.randomUUID().toString(),
                title = j.optString("title"),
                content = j.optString("content"),
                category = j.optString("category", "taught"),
                personaId = j.optString("personaId"),
                locationId = j.optString("locationId").ifBlank { null },
                source = "taught",
                imageBase64 = imageBase64
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
                put("photoBase64", p.photoBase64)
            }
        })

        return buildJsonObject {
            put("personaName", persona?.name ?: "")
            put("personaId", personaId)
            put("role", persona?.role ?: "")
            put("greeting", persona?.greeting ?: "")
            put("personality", Json.encodeToString(tags))
            put("extraInstructions", persona?.extraInstructions ?: "")
            put("languages", Json.encodeToString(languages))
            put("codeSwitching", settings.codeSwitching)
            put("locationName", location?.name ?: "")
            put("locationId", location?.id ?: "")
            put("locationDescription", location?.description ?: "")
            put("locationOpeningHours", location?.openingHours ?: "")
            put("locationSpecialInstructions", location?.specialInstructions ?: "")
            put("facts", factsArray)
            put("people", peopleArray)
            put("pendingCommand", settings.pendingCommand)
            put("learningMode", settings.learningMode)
            put("piIp", settings.piIp)
        }.toString()
    }
}
