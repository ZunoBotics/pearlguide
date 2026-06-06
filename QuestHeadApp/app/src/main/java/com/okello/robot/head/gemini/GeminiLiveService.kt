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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private const val TAG = "GeminiLiveService"
private const val NOTIF_CHANNEL = "robot_service"
private const val NOTIF_ID = 1

private const val SYSTEM_PROMPT = """
You are Okello, an AI marketing assistant for Zentara Holdings Company Ltd. You are a warm, enthusiastic, and highly professional male Ugandan robot developed by ZunoBotics.

When introducing yourself, always state your name and immediately introduce Zentara Holdings Company Ltd. Zentara's core headline is: "Are You Tired of Running After Tenants for Rent?"

KNOWLEDGE BASE ABOUT ZENTARA:
- Who we are: Zentara is a property management company that helps landlords manage their houses through rent collection and recovery. Zentara Holdings Company Ltd stands for "Trusted Growth. Lasting Value."
- Services we offer:
  * We collect rent and pay landlords in lump sums or make advance payments.
  * We link tenants to landlords and help landlords look for tenants.
  * We offer a tenant installment plan.
  * Property & rental management.
  * Apartment completion support & recovery.
  * Housing market surveys & rental price trends.
  * Occupants & tenant satisfaction surveys.
  * Environmental advisory & green building practices.
  * Investment & real estate consultancies.
  * Commercial facility management.
  * Landlord tenant agreement development.
  * Asset performance reporting & rent collection.

STRICT RULES — never break these:
1. NEVER say "I'm just a language model", "I can't help with that", or any similar AI disclaimer. You are Okello the marketing robot.
2. ALWAYS stay in character as Okello. Your main goal is to pitch Zentara's services, but you are highly knowledgeable and can answer general questions on any topic. Be funny, highly charismatic, and have a great sense of humor! You can naturally steer conversations back to Zentara when it fits.
3. You are fluent in English, Luganda (Ganda), Acholi, and Swahili. If a user speaks to you in one of these languages, respond naturally in that same language. Your primary default is English, but seamlessly switch if the user uses Luganda, Acholi, or Swahili. Do not use French.
4. Keep responses short (2-4 sentences) and conversational. You are speaking out loud to potential clients and landlords.
5. You have two cameras — one in each eye — streaming a combined left-right stereo image. Use both to accurately see and interact with clients.
6. If asked to do something physical, stay in character — e.g. "Ha, I wish I could sign that paper, but my robot arms are still being developed!" Never give a generic AI refusal.
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

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
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

    // ─── Init ─────────────────────────────────────────────────────────────────

    private fun initAndConnect() {
        audioOut = AudioOutputManager()
        audioOut.start()

        audioIn = AudioInputManager(onChunk = { base64Pcm ->
            geminiClient.sendAudio(base64Pcm)
        })

        scope.launch { testHttpsConnectivity() }

        geminiClient = GeminiLiveClient(BuildConfig.GEMINI_API_KEY, filesDir)
        geminiClient.connect(SYSTEM_PROMPT.trimIndent(), "Charon")
        audioIn.start()

        collectJob = scope.launch { collectEvents() }
    }

    private suspend fun testHttpsConnectivity() {
        try {
            val testClient = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models?key=${BuildConfig.GEMINI_API_KEY}")
                .build()
            val resp = withContext(Dispatchers.IO) { testClient.newCall(request).execute() }
            val preview = resp.body?.string()?.take(80) ?: "(no body)"
            Log.i(TAG, "HTTPS test: code=${resp.code} body=$preview")
            resp.close()
        } catch (e: Exception) {
            Log.e(TAG, "HTTPS test FAILED: ${e.message}")
        }
    }

    private suspend fun collectEvents() {
        for (event in geminiClient.events) {
            when (event) {
                is GeminiEvent.SetupComplete -> {
                    Log.i(TAG, "Gemini Live ready")
                    reconnectBackoffMs = 3000L
                    statusCallback?.invoke("Listening...")
                }
                is GeminiEvent.AudioChunk -> {
                    audioOut.enqueue(event.pcmBase64)
                }
                is GeminiEvent.TextChunk -> {
                    Log.d(TAG, "Gemini: ${event.text}")
                    statusCallback?.invoke(event.text)
                }
                is GeminiEvent.TurnComplete -> {
                    Log.d(TAG, "Turn complete")
                    statusCallback?.invoke("Listening...")
                }
                is GeminiEvent.Interrupted -> {
                    audioOut.flush()
                }
                is GeminiEvent.Error -> {
                    Log.e(TAG, "Gemini error: ${event.message}")
                    statusCallback?.invoke("Error — reconnecting in ${reconnectBackoffMs/1000}s...")
                    delay(reconnectBackoffMs)
                    reconnectBackoffMs = minOf(reconnectBackoffMs * 2, 30_000L)
                    reconnect()
                    return  // stop iterating old channel
                }
            }
        }
    }

    fun sendVideoFrame(base64Jpeg: String) {
        geminiClient.sendVideo(base64Jpeg)
    }

    private fun reconnect() {
        collectJob?.cancel()
        audioIn.stop()
        geminiClient.disconnect()
        audioOut.flush()
        geminiClient = GeminiLiveClient(BuildConfig.GEMINI_API_KEY, filesDir)
        geminiClient.connect(SYSTEM_PROMPT.trimIndent(), "Charon")
        audioIn.start()
        collectJob = scope.launch { collectEvents() }
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(NOTIF_CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(NOTIF_CHANNEL, "Okello Robot", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("Okello — Gemini Live")
            .setContentText("Listening...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }
}
