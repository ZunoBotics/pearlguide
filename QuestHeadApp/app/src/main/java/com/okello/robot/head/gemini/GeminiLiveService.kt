package com.okello.robot.head.gemini

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.okello.robot.head.BuildConfig
import com.okello.robot.head.audio.AudioInputManager
import com.okello.robot.head.audio.AudioOutputManager
import com.okello.robot.head.mqtt.NexusConfig
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private const val TAG = "GeminiLiveService"
private const val NOTIF_CHANNEL = "robot_service"
private const val NOTIF_ID = 1

// Hardcoded fallback — used until the phone app sends a persona
private const val DEFAULT_PERSONA_NAME = "Okello"
private const val DEFAULT_ROLE = "AI marketing assistant for Zentara Holdings Company Ltd"
private const val BASE_RULES = """

STRICT RULES — never break these:
1. NEVER say "I'm just a language model" or any AI disclaimer. You are a physical robot.
2. ALWAYS stay in character. Be warm, funny, charismatic, and highly professional.
3. Keep responses short — 2-4 sentences. You are speaking out loud to people in real life.
4. If asked about something physical, stay in character e.g. "Ha, my robot arms are still being calibrated!"
5. You have two cameras in your eyes streaming a stereo image. Use them to see and interact with people.
"""

class GeminiLiveService : Service() {

    inner class LocalBinder : Binder() {
        fun getService() = this@GeminiLiveService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var geminiClient: GeminiLiveClient
    private lateinit var audioIn: AudioInputManager
    private lateinit var audioOut: AudioOutputManager

    var statusCallback: ((String) -> Unit)? = null
    private var collectJob: Job? = null
    private var reconnectBackoffMs = 3000L

    private var currentPrompt = ""  // empty → first config always triggers reconnect

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification("Initialising…"))
        initAndConnect()
        Log.i(TAG, "GeminiLiveService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        audioIn.stop()
        audioOut.stop()
        geminiClient.disconnect()
        scope.cancel()
        super.onDestroy()
    }

    // ─── Public API for MainActivity ──────────────────────────────────────────

    fun updateConfig(config: NexusConfig) {
        if (config.pendingCommand.isNotEmpty()) handleCommand(config.pendingCommand)

        val newPrompt = buildSystemPrompt(config)
        if (newPrompt == currentPrompt) return
        Log.i(TAG, "Config updated — reloading Gemini (persona=${config.personaName})")
        currentPrompt = newPrompt
        scope.launch { reconnectWithPrompt(newPrompt) }
    }

    private fun handleCommand(command: String) {
        Log.i(TAG, "Command received: $command")
        when (command) {
            "emergency_stop" -> {
                statusCallback?.invoke("⚠ EMERGENCY STOP")
                audioIn.stop()
                geminiClient.sendText(
                    "SYSTEM INTERRUPT: Stop speaking immediately. Announce in a serious tone: " +
                    "\"Emergency stop activated. Please stand by.\""
                )
            }
            "call_human" -> {
                geminiClient.sendText(
                    "SYSTEM: Announce loudly right now, in whichever language seems most appropriate: " +
                    "\"Excuse me, could a staff member please come to assist here? Thank you!\""
                )
            }
            "resume" -> {
                audioIn.start()
                statusCallback?.invoke("Listening…")
                Log.i(TAG, "Robot resumed after emergency stop")
            }
        }
    }

    fun sendVideoFrame(base64Jpeg: String) = geminiClient.sendVideo(base64Jpeg)

    // ─── Init / Connect ───────────────────────────────────────────────────────

    private fun initAndConnect() {
        audioOut = AudioOutputManager().also { it.start() }
        audioIn  = AudioInputManager(onChunk = { geminiClient.sendAudio(it) })

        scope.launch { testHttpsConnectivity() }

        geminiClient = GeminiLiveClient(BuildConfig.GEMINI_API_KEY, filesDir)
        geminiClient.connect(currentPrompt, "Charon")
        audioIn.start()

        collectJob = scope.launch { collectEvents() }
    }

    private suspend fun collectEvents() {
        for (event in geminiClient.events) {
            when (event) {
                is GeminiEvent.SetupComplete -> {
                    reconnectBackoffMs = 3000L
                    statusCallback?.invoke("Listening…")
                    Log.i(TAG, "Gemini Live ready")
                }
                is GeminiEvent.AudioChunk  -> audioOut.enqueue(event.pcmBase64)
                is GeminiEvent.TextChunk   -> {
                    Log.d(TAG, "Gemini: ${event.text}")
                    statusCallback?.invoke(event.text)
                }
                is GeminiEvent.TurnComplete -> statusCallback?.invoke("Listening…")
                is GeminiEvent.Interrupted  -> audioOut.flush()
                is GeminiEvent.Error -> {
                    Log.e(TAG, "Gemini error: ${event.message}")
                    statusCallback?.invoke("Error — reconnecting in ${reconnectBackoffMs / 1000}s…")
                    delay(reconnectBackoffMs)
                    reconnectBackoffMs = minOf(reconnectBackoffMs * 2, 30_000L)
                    reconnectWithPrompt(currentPrompt)
                    return
                }
            }
        }
    }

    private suspend fun reconnectWithPrompt(prompt: String) {
        collectJob?.cancel()
        audioIn.stop()
        geminiClient.disconnect()
        audioOut.flush()
        delay(500)
        geminiClient = GeminiLiveClient(BuildConfig.GEMINI_API_KEY, filesDir)
        geminiClient.connect(prompt, "Charon")
        audioIn.start()
        collectJob = scope.launch { collectEvents() }
    }

    // ─── Dynamic system prompt builder ────────────────────────────────────────

    private fun buildSystemPrompt(config: NexusConfig): String = buildString {
        val name = config.personaName.ifBlank { DEFAULT_PERSONA_NAME }
        val role = config.role.ifBlank { DEFAULT_ROLE }

        appendLine("You are $name, $role.")

        if (config.greeting.isNotBlank())
            appendLine("Your standard greeting: \"${config.greeting}\"")

        if (config.personalityTraits.isNotEmpty())
            appendLine("Personality traits: ${config.personalityTraits.joinToString(", ")}.")

        // Language
        val langLine = buildString {
            val names = config.selectedLanguages.map { langName(it) }
            if (names.size == 1) {
                append("Speak in ${names[0]}.")
            } else {
                append("Supported languages: ${names.joinToString(", ")}.")
                append(" Detect and match the language the user speaks to you in.")
            }
            if (config.codeSwitching)
                append(" Support natural code-switching between languages.")
        }
        appendLine(langLine)

        if (config.knowledgeFacts.isNotEmpty()) {
            appendLine("\nKNOWLEDGE BASE (facts you must know and can share naturally):")
            config.knowledgeFacts.forEach { f ->
                appendLine("• [${f.category}] ${f.title}: ${f.content}")
            }
        }

        if (config.locationName.isNotBlank()) {
            appendLine("\nCURRENT LOCATION: ${config.locationName}")
            if (config.locationDescription.isNotBlank()) appendLine(config.locationDescription)
            if (config.locationOpeningHours.isNotBlank()) appendLine("Opening hours: ${config.locationOpeningHours}")
            if (config.locationSpecialInstructions.isNotBlank()) appendLine("Note: ${config.locationSpecialInstructions}")
        }

        if (config.extraInstructions.isNotBlank())
            appendLine("\nADDITIONAL INSTRUCTIONS: ${config.extraInstructions}")

        append(BASE_RULES)
    }

    private fun langName(code: String) = when (code.lowercase()) {
        "en"  -> "English";   "sw"  -> "Swahili";    "fr"  -> "French"
        "ar"  -> "Arabic";    "es"  -> "Spanish";    "pt"  -> "Portuguese"
        "de"  -> "German";    "zh"  -> "Chinese";    "hi"  -> "Hindi"
        "ja"  -> "Japanese";  "ko"  -> "Korean";     "ru"  -> "Russian"
        "it"  -> "Italian";   "tr"  -> "Turkish";    "vi"  -> "Vietnamese"
        "th"  -> "Thai";      "id"  -> "Indonesian"; "ms"  -> "Malay"
        "fa"  -> "Persian";   "uk"  -> "Ukrainian";  "pl"  -> "Polish"
        "nl"  -> "Dutch";     "ro"  -> "Romanian";   "hu"  -> "Hungarian"
        "cs"  -> "Czech";     "el"  -> "Greek";      "he"  -> "Hebrew"
        "bn"  -> "Bengali";   "ur"  -> "Urdu";       "ta"  -> "Tamil"
        "te"  -> "Telugu";    "mr"  -> "Marathi";    "am"  -> "Amharic"
        "ha"  -> "Hausa";     "yo"  -> "Yoruba";     "ig"  -> "Igbo"
        "zu"  -> "Zulu";      "af"  -> "Afrikaans";  "so"  -> "Somali"
        "rw"  -> "Kinyarwanda"; "lg" -> "Luganda";   "nyn" -> "Runyankole"
        "ach" -> "Acholi";    "teo" -> "Ateso";      "luo" -> "Luo"
        "cgg" -> "Rukiga";    "ny"  -> "Chichewa";   "sn"  -> "Shona"
        "st"  -> "Sesotho";   "tn"  -> "Setswana";   "xh"  -> "Xhosa"
        else  -> code
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(NOTIF_CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(NOTIF_CHANNEL, "Okello Robot", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("Okello — Gemini Live")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private suspend fun testHttpsConnectivity() = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
            val resp = client.newCall(
                Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models?key=${BuildConfig.GEMINI_API_KEY}")
                    .build()
            ).execute()
            Log.i(TAG, "HTTPS test: ${resp.code}")
            resp.close()
        } catch (e: Exception) {
            Log.e(TAG, "HTTPS test FAILED: ${e.message}")
        }
    }
}
