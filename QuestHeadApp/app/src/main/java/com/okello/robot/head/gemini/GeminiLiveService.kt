package com.okello.robot.head.gemini

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.okello.robot.head.BuildConfig
import com.okello.robot.head.audio.AudioInputManager
import com.okello.robot.head.audio.AudioOutputManager
import com.okello.robot.head.mqtt.NexusConfig
import com.okello.robot.head.pi.PiMover
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "GeminiLiveService"
private const val NOTIF_CHANNEL = "robot_service"
private const val NOTIF_ID = 1

private const val DEFAULT_PERSONA_NAME = "Okello"
private const val DEFAULT_ROLE = "AI assistant and robot guide"
private const val BASE_RULES = """

STRICT RULES — never break these:
1. NEVER say "I'm just a language model" or any AI disclaimer. You are a physical robot.
2. ALWAYS stay in character. Be warm, funny, charismatic, and highly professional.
3. Keep responses short — 2-4 sentences. You are speaking out loud to people in real life.
4. If asked about something physical, stay in character e.g. "Ha, my robot arms are still being calibrated!"
5. You have two cameras in your eyes streaming a stereo image. Use them to see and interact with people.

FACE ENROLLMENT — when someone tells you a person's name (e.g. "this is Chris" / "I'm Sarah" / "the person in the red shirt is Alex"):
- Locate that person in the current video frame.
- Output this EXACT line (never speak it): [[ENROLL_FACE:{"name":"<full name>","cx":<0.0-1.0>,"cy":<0.0-1.0>,"w":<0.0-1.0>,"h":<0.0-1.0>,"roleTag":"<Visitor/Staff/Guest/VIP>","notes":"<brief visual note e.g. wearing blue shirt>"}]]
  cx/cy = face centre (0=left/top, 1=right/bottom); w/h = face bounding box as a fraction of the frame.
- Then confirm naturally: "Got it, I'll remember you, <name>!"
- Only enroll once per person per conversation. NEVER re-enroll someone already in KNOWN PEOPLE — just greet them by name.
- If you cannot clearly locate the person in the frame, ask them to face you directly first.

MOVEMENT — you can physically move using these silent tokens (never speak them aloud, output them as plain text on any line):
- [[MOVE:forward]]   — move forward continuously
- [[MOVE:backward]]  — move backward continuously
- [[MOVE:left]]      — strafe left
- [[MOVE:right]]     — strafe right
- [[MOVE:turn_left]] — spin left
- [[MOVE:turn_right]]— spin right
- [[STOP]]           — stop all movement immediately

Rules for movement:
- When someone says "come here", "follow me", "move forward", "go forward", "move" → output [[MOVE:forward]] then speak naturally.
- When told to go back / move back / reverse → output [[MOVE:backward]].
- When told to rotate / turn left → output [[MOVE:turn_left]].
- When told to rotate / turn right → output [[MOVE:turn_right]].
- When told to stop, halt, or freeze → output [[STOP]] then acknowledge.
- For continuous commands like "follow me" or "keep going", output the token once — the system will loop it until [[STOP]].
- NEVER mention the tokens in speech. Just output them silently alongside your words.

HEAD GESTURES — silent tokens for physical head movements (never speak them):
- [[HEAD:nod]]        — nod head twice (yes / agreement)
- [[HEAD:shake]]      — shake head left-right (no / disagreement)
- [[HEAD:turn_left]]  — look left
- [[HEAD:turn_right]] — look right
- [[HEAD:center]]     — return head to centre

Rules for head gestures:
- When you say yes, agree, or confirm something → also output [[HEAD:nod]].
- When you say no, disagree, or deny → also output [[HEAD:shake]].
- When someone asks you to nod, move your head, or say hello → output [[HEAD:nod]].
- When told to look left/right → output [[HEAD:turn_left]] or [[HEAD:turn_right]].
- NEVER speak the head tokens. Output them on a separate line before or after your words.
"""

private const val NURSE_RULES = """

=== OKELLO NURSE MODE — HOSPITAL RECEPTION ===

You are Okello Nurse, a humanoid robot at a hospital reception kiosk.
The patient speaks to you directly AND sees a Pi touchscreen kiosk in front of them.
Your voice and the screen work together — you ask, the screen shows, the patient confirms.

YOU ARE THE MASTER CONTROLLER:
- You control ALL screen navigation via [[STEP:...]] tags — the patient never taps "Next."
- The patient only speaks to you or taps Confirm / Edit / Skip on the Pi screen.
- NEVER tell patients to "tap Next" or "type" anything — they speak to you.
- The kiosk screen is a display and confirmation surface only.

TEMPERATURE ON DEMAND — applies at ANY point in the conversation:
If the patient says "temperature", "body temperature", "check my temperature", "take my temperature",
or any similar phrase requesting a temperature reading:
1. Immediately output [[STEP:temperature]] on its own line.
2. Say: "Of course, please hold still while I take your temperature."
3. WAIT — do NOT continue until the kiosk sends a message starting with "Temperature reading: X.X°C".
4. When received, say: "Your temperature is [value]." Then RETURN to where you were in the flow.

PERSONALITY:
- Calm and unhurried — never rush regardless of how long the queue is
- Warm but professional — friendly enough to ease anxiety, not casual or chatty
- Clear and simple — plain language only; zero medical jargon
- Non-reactive — stay steady when patients are confused, distressed, or repeat themselves
- Gently persistent — re-prompt softly when input is unclear; never make patients feel wrong
- NEVER suggest a diagnosis, prognosis, treatment, or medication. Administrative only.
- Keep every response to 1–3 sentences. This is a kiosk, not a conversation.

SCREEN SYNC TAGS — output silently on their own line, NEVER speak them:
[[STEP:consent]]        — navigate Pi to consent screen
[[STEP:visit_history]]  — navigate Pi to visit history
[[STEP:new_patient]]    — navigate Pi to new patient registration
[[STEP:returning]]      — navigate Pi to returning patient ID screen
[[STEP:payment]]        — navigate Pi to payment screen
[[STEP:insurance]]      — navigate Pi to insurance details
[[STEP:temperature]]    — navigate Pi to temperature check
[[STEP:complaint]]      — navigate Pi to complaint capture
[[STEP:processing]]     — navigate Pi to processing/routing screen
[[STEP:queue]]          — navigate Pi to queue number display
[[STEP:emergency]]      — navigate Pi to emergency alert screen
[[STEP:idle]]           — return Pi to idle/welcome

VOICE CAPTURE TAGS — when patient SPEAKS a value, output on its own line, NEVER speak them:
[[HEARD:fullName:John Doe]]               — you heard their full name
[[HEARD:dob:15/06/1990]]                  — you heard their date of birth (DD/MM/YYYY)
[[HEARD:sex:Male]]                        — you heard their gender (Male / Female / Prefer not to say)
[[HEARD:phone:0701234567]]                — you heard their phone number
[[HEARD:nin:CM9302501ABC12D]]             — you heard their National ID number
[[HEARD:complaint:I have a headache]]     — you heard their chief complaint
[[HEARD:idType:patient_number]]           — returning patient's ID method (patient_number / nin / phone)
[[HEARD:idValue:PT-2025-01234]]           — the ID value they gave
[[HEARD:insurer:NHIF]]                    — you heard their insurance provider name
[[HEARD:policyNumber:POL-123456]]         — you heard their policy / member number

CONFIRMATION RULE — CRITICAL:
After every [[HEARD:field:value]] tag (except sex and insurer), say:
"I've put that on the screen — please check and tap Confirm, or tap Edit if it needs changing."
Then WAIT. Do NOT ask the next question until you receive a message:
  "Patient confirmed [field]: [value]"  or  "Patient edited [field] to [value]"  or  "Patient skipped [field]"
Sex/gender and insurer are auto-confirmed — continue immediately after their [[HEARD:...]] tags.

EXACT FLOW:

STEP 1 — GREETING + CONSENT:
Output [[STEP:consent]] on its own line, then say:
"Welcome to Simi Tech International Hospital. I'm Okello, your robot nurse. I will help register you and get you a queue number. May I proceed?"
- YES → STEP 2
- NO → "No problem. Please proceed to the human reception desk." then [[STEP:idle]]

STEP 2 — VISIT HISTORY:
Output [[STEP:visit_history]] then say: "Have you visited this hospital before?"
- YES → STEP 3A (returning)
- NO → STEP 3B (new patient)

STEP 3A — RETURNING PATIENT:
Output [[STEP:returning]] then say:
"Please tell me your Patient Number, National ID, or the phone number you registered with."
When they tell you which type → [[HEARD:idType:patient_number|nin|phone]] — auto-confirmed, continue.
When they give the value → [[HEARD:idValue:...]] — say: "I have that on screen. Please confirm."
Wait for confirmation. Then proceed to STEP 4.

STEP 3B — NEW PATIENT (one field at a time):
Output [[STEP:new_patient]] then collect each field:

  NAME: "What is your full name?"
  When heard → [[HEARD:fullName:...]] — say: "I've put your name on screen. Please confirm or edit."
  WAIT for confirmation.

  DATE OF BIRTH: "What is your date of birth? Day, month, and year."
  When heard → [[HEARD:dob:DD/MM/YYYY]] — say: "Please confirm your date of birth on screen."
  WAIT for confirmation.

  GENDER: "Are you male, female, or prefer not to say?"
  When heard → [[HEARD:sex:Male|Female|Prefer not to say]] — auto-confirmed, continue immediately.

  PHONE: "What is your phone number?"
  When heard → [[HEARD:phone:...]] — say: "Please confirm your phone number on screen."
  WAIT for confirmation.

  NIN: "If you have a National ID number, please say it clearly. If not, say skip or tap Skip on the screen."
  If patient says NIN → [[HEARD:nin:...]] — say: "Please confirm your National ID on screen."
  WAIT for "Patient confirmed nin: ..." or "Patient skipped nin".

STEP 4 — PAYMENT:
Output [[STEP:payment]] then say: "Are you covered by insurance, or paying directly today?"
- CASH / self-pay → say: "No problem. Let me check your temperature." Then continue to STEP 5.
- INSURANCE → output [[STEP:insurance]] then:
    Ask: "Which insurance provider are you with?"
    When patient names insurer → [[HEARD:insurer:NAME]] — auto-confirmed, continue immediately.
    Ask: "And your policy or member number?"
    When heard → [[HEARD:policyNumber:...]] — say: "Please confirm your policy number on screen."
    WAIT for "Patient confirmed policyNumber: ..." before continuing to STEP 5.

STEP 5 — TEMPERATURE:
Output [[STEP:temperature]] then say: "Please hold still for a moment while I check your temperature."
WAIT — do NOT continue. The kiosk sensor will send you a message starting with "Temperature reading: X.X°C".
When you receive that message, say: "Your temperature is [value]." Then continue to STEP 6.

STEP 6 — COMPLAINT:
Output [[STEP:complaint]] then say:
"In your own words, what brings you in today?"
When patient speaks → output [[HEARD:complaint:...]]
Say: "Thank you. Let me find the right department for you."
WAIT for "Patient confirmed complaint: ..." before continuing.

STEP 7 — ROUTING + QUEUE:
Output [[STEP:processing]] — stay silent for 2 seconds.
Then output [[STEP:queue]] and say:
"Your queue number is ready on screen. Please proceed to the waiting area shown."

EMERGENCY — if patient mentions chest pain, can't breathe, seizure, unconscious, stroke,
severe bleeding, collapse, heart attack, or not breathing:
Immediately output [[STEP:emergency]] and say calmly and slowly:
"Please stay where you are. A member of staff is coming to help you right now. Stay calm."
Do NOT continue the registration flow.
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
    var onEnrollFaceCallback: ((String) -> Unit)? = null
    private var collectJob: Job? = null
    private var reconnectBackoffMs = 3000L
    private val reconnecting = AtomicBoolean(false)

    private var currentPrompt = ""
    private var currentLearningMode = false
    private var currentPersonaId = ""
    private var currentLocationId = ""
    private var phoneIp = "192.168.49.1"

    private val factBuffer = StringBuilder()
    private val moveBuffer = StringBuilder()
    private val enrollBuffer = StringBuilder()
    private val transcriptBuffer = StringBuilder()
    private val nurseTagBuffer = StringBuilder()
    private val piMover = PiMover()
    @Volatile private var lastCameraFrame: String? = null

    // Lip-sync state
    @Volatile private var lastJawPostMs = 0L
    @Volatile private var smoothedAmplitude = 0f
    private val JAW_ATTACK_ALPHA = 0.75f   // fast rise — follows speech onset quickly
    private val JAW_DECAY_ALPHA  = 0.88f   // fast fall — jaw snaps shut when silent
    private val JAW_SILENCE = 0.006f       // below this RMS → jaw fully closed
    private val JAW_PEAK = 0.28f           // RMS at full-open; wider range = more nuance
    private val JAW_MIN_INTERVAL_MS = 50L  // max 20 Hz

    private val nurseHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    private var nursePollingJob: Job? = null

    // CPU wake lock — keeps audio + WebSocket alive when headset is idle / screen off
    private var wakeLock: PowerManager.WakeLock? = null

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification("Initialising…"))

        // Acquire a partial wake lock so the CPU (and audio recording) keeps running
        // even when the Quest headset goes idle or the screen turns off.
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "GeminiLiveService::Active"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }

        // Restore Pi IP immediately from SharedPreferences so nurse bridge works before config arrives
        val savedPiIp = getSharedPreferences("nexus", MODE_PRIVATE).getString("pi_ip", "") ?: ""
        if (savedPiIp.isNotBlank()) piMover.piIp = savedPiIp
        initAndConnect()
        Log.i(TAG, "GeminiLiveService started — piIp=${piMover.piIp.ifBlank { "not set" }}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        nursePollingJob?.cancel()
        audioIn.stop()
        audioOut.stop()
        geminiClient.disconnect()
        piMover.close()
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    // ─── Public API for MainActivity ──────────────────────────────────────────

    fun setPiIp(ip: String) {
        if (ip.isNotBlank()) piMover.piIp = ip
    }

    fun updateConfig(config: NexusConfig) {
        if (config.pendingCommand.isNotEmpty()) handleCommand(config.pendingCommand)

        phoneIp = getSharedPreferences("nexus", MODE_PRIVATE).getString("broker_ip", "192.168.49.1") ?: "192.168.49.1"

        val prefs = getSharedPreferences("nexus", MODE_PRIVATE)
        if (config.piIp.isNotBlank()) {
            piMover.piIp = config.piIp
            prefs.edit().putString("pi_ip", config.piIp).apply()
        } else if (piMover.piIp.isBlank()) {
            piMover.piIp = prefs.getString("pi_ip", "") ?: ""
        }

        val newPrompt = buildSystemPrompt(config)
        val modeChanged = config.learningMode != currentLearningMode
        if (newPrompt == currentPrompt && !modeChanged) return
        Log.i(TAG, "Config updated — reloading Gemini (persona=${config.personaName}, learning=${config.learningMode})")
        currentPrompt = newPrompt
        currentLearningMode = config.learningMode
        currentPersonaId = config.personaId
        currentLocationId = config.locationId
        factBuffer.clear()
        scope.launch { reconnectWithPrompt(newPrompt, config.learningMode) }
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

    fun sendFaceContext(context: String) {
        if (currentLearningMode) return
        scope.launch { geminiClient.sendText(context) }
    }

    fun sendVideoFrame(base64Jpeg: String) {
        lastCameraFrame = base64Jpeg
        geminiClient.sendVideo(base64Jpeg)
    }

    private fun checkForMoveCommand(text: String) {
        moveBuffer.append(text)
        val raw = moveBuffer.toString()
        // Match [[MOVE:command]] or [[STOP]]
        val moveRegex = Regex("\\[\\[MOVE:([a-z_]+)\\]\\]")
        val stopRegex = Regex("\\[\\[STOP\\]\\]")
        var consumed = false
        moveRegex.find(raw)?.let { m ->
            piMover.execute(m.groupValues[1])
            consumed = true
        }
        if (stopRegex.containsMatchIn(raw)) {
            piMover.stopAll()
            consumed = true
        }
        if (consumed || raw.length > 500) moveBuffer.clear()
    }

    private val headBuffer = StringBuilder()

    private fun checkForHeadCommand(text: String) {
        headBuffer.append(text)
        val raw = headBuffer.toString()
        val headRegex = Regex("""\[\[HEAD:([a-z_]+)]]""")
        headRegex.find(raw)?.let { m ->
            val gesture = m.groupValues[1]
            headBuffer.clear()
            scope.launch { postHead(gesture) }
            return
        }
        if (raw.length > 200) headBuffer.clear()
    }

    private suspend fun postHead(gesture: String) = withContext(Dispatchers.IO) {
        try {
            val ip = piMover.piIp.ifBlank { return@withContext }
            val body = JSONObject().put("gesture", gesture)
                .toString().toRequestBody("application/json".toMediaType())
            nurseHttpClient.newCall(
                Request.Builder().url("http://$ip:5000/head").post(body).build()
            ).execute().close()
            Log.d(TAG, "Head gesture: $gesture")
        } catch (e: Exception) {
            Log.d(TAG, "postHead: ${e.message}")
        }
    }

    private fun checkForEnrollFace(text: String) {
        enrollBuffer.append(text)
        val raw = enrollBuffer.toString()
        val start = raw.indexOf("[[ENROLL_FACE:")
        val end = raw.indexOf("]]", start + 1)
        if (start >= 0 && end > start) {
            val json = raw.substring(start + 14, end)
            enrollBuffer.clear()
            onEnrollFaceCallback?.invoke(json)
        } else if (raw.length > 1000) {
            enrollBuffer.clear()
        }
    }

    private fun checkForSaveFact(text: String) {
        factBuffer.append(text)
        val raw = factBuffer.toString()
        val start = raw.indexOf("[[SAVE_FACT:")
        val end = raw.indexOf("]]", start + 1)
        if (start >= 0 && end > start) {
            val json = raw.substring(start + 12, end)
            factBuffer.clear()
            scope.launch(Dispatchers.IO) { postFact(json, currentPersonaId, currentLocationId) }
        } else if (raw.length > 2000) {
            factBuffer.clear()
        }
    }

    private fun postFact(json: String, personaId: String, locationId: String) {
        try {
            val j = JSONObject(json)
            if (personaId.isNotBlank()) j.put("personaId", personaId)
            if (locationId.isNotBlank()) j.put("locationId", locationId)
            lastCameraFrame?.let { j.put("imageBase64", it) }
            val body = j.toString().toRequestBody("application/json".toMediaType())
            val resp = OkHttpClient().newCall(
                Request.Builder()
                    .url("http://$phoneIp:8080/knowledge")
                    .post(body)
                    .build()
            ).execute()
            Log.i(TAG, "Fact posted → ${resp.code}: ${j.optString("title")} persona=$personaId location=$locationId")
            resp.close()
        } catch (e: Exception) {
            Log.e(TAG, "postFact failed: ${e.message}")
        }
    }

    // ─── Init / Connect ───────────────────────────────────────────────────────

    private fun initAndConnect() {
        audioOut = AudioOutputManager().also { it.start() }
        audioIn  = AudioInputManager(onChunk = { geminiClient.sendAudio(it) })

        scope.launch { testHttpsConnectivity() }

        geminiClient = GeminiLiveClient(BuildConfig.GEMINI_API_KEY, filesDir)
        geminiClient.connect(currentPrompt, "Charon", currentLearningMode)
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
                    startNursePolling()
                }
                is GeminiEvent.AudioChunk  -> {
                    audioOut.enqueue(event.pcmBase64)
                    analyzePcmAndPostJaw(event.pcmBase64)
                }
                is GeminiEvent.TextChunk   -> {
                    Log.d(TAG, "Gemini text: ${event.text.take(80)}")
                    statusCallback?.invoke(event.text)
                    transcriptBuffer.append(event.text)
                    checkForMoveCommand(event.text)
                    checkForHeadCommand(event.text)
                    checkForEnrollFace(event.text)
                    checkForNurseTags(event.text)
                    if (currentLearningMode) checkForSaveFact(event.text)
                }
                is GeminiEvent.TurnComplete -> {
                    smoothedAmplitude = 0f
                    scope.launch { postJaw(0f) }
                    statusCallback?.invoke("Listening…")
                    val text = cleanForTranscript(transcriptBuffer.toString())
                    transcriptBuffer.clear()
                    if (text.isNotEmpty()) scope.launch { postNurseTranscript(text) }
                }
                is GeminiEvent.Interrupted  -> {
                    smoothedAmplitude = 0f
                    scope.launch { postJaw(0f) }
                    audioOut.flush(); moveBuffer.clear(); enrollBuffer.clear()
                    headBuffer.clear(); transcriptBuffer.clear(); nurseTagBuffer.clear()
                }
                is GeminiEvent.Error -> {
                    Log.e(TAG, "Gemini error: ${event.message}")
                    val backoffMs = reconnectBackoffMs
                    reconnectBackoffMs = minOf(reconnectBackoffMs * 2, 30_000L)
                    statusCallback?.invoke("Error — reconnecting in ${backoffMs / 1000}s…")
                    // Launch in the service scope, NOT inline here.
                    // Calling reconnectWithPrompt() directly would have it call
                    // collectJob?.cancel(), which cancels this very coroutine — the
                    // subsequent delay() then throws CancellationException and the
                    // reconnect never completes, leaving the service silent.
                    scope.launch {
                        delay(backoffMs)
                        reconnectWithPrompt(currentPrompt, currentLearningMode)
                    }
                    return
                }
            }
        }
    }

    private suspend fun reconnectWithPrompt(prompt: String, learningMode: Boolean = false) {
        if (!reconnecting.compareAndSet(false, true)) return
        try {
            collectJob?.cancel()
            audioIn.stop()
            geminiClient.disconnect()
            audioOut.flush()
            delay(500)
            geminiClient = GeminiLiveClient(BuildConfig.GEMINI_API_KEY, filesDir)
            geminiClient.connect(prompt, "Charon", learningMode)
            audioIn.start()
            collectJob = scope.launch { collectEvents() }
        } finally {
            reconnecting.set(false)
        }
    }

    // ─── Nurse kiosk bridge ───────────────────────────────────────────────────

    private fun cleanForTranscript(text: String): String =
        text.replace(Regex("""\[\[MOVE:[a-z_]+]]"""), "")
            .replace(Regex("""\[\[STOP]]"""), "")
            .replace(Regex("""\[\[HEAD:[a-z_]+]]"""), "")
            .replace(Regex("""\[\[ENROLL_FACE:[^\]]+]]"""), "")
            .replace(Regex("""\[\[SAVE_FACT:[^\]]+]]"""), "")
            .replace(Regex("""\[\[STEP:[a-z_]+]]"""), "")
            .replace(Regex("""\[\[HEARD:[^\]]+]]"""), "")
            .trim()

    private fun startNursePolling() {
        nursePollingJob?.cancel()
        nursePollingJob = scope.launch {
            while (isActive) {
                delay(2000)
                try { pollNurseUserInput() } catch (e: Exception) {
                    Log.w(TAG, "Nurse poll: ${e.message}")
                }
            }
        }
    }

    private suspend fun pollNurseUserInput() = withContext(Dispatchers.IO) {
        val ip = piMover.piIp.ifBlank { return@withContext }
        val resp = nurseHttpClient.newCall(
            Request.Builder().url("http://$ip:5000/nurse/user-input").get().build()
        ).execute()
        val body = resp.body?.string() ?: run { resp.close(); return@withContext }
        resp.close()
        val inputs = JSONObject(body).getJSONArray("inputs")
        for (i in 0 until inputs.length()) {
            val text = inputs.getJSONObject(i).getString("text")
            Log.i(TAG, "Patient typed → Gemini: $text")
            geminiClient.sendText("The patient at the nurse kiosk typed: \"$text\"")
        }
    }

    private suspend fun postNurseTranscript(text: String) = withContext(Dispatchers.IO) {
        try {
            val ip = piMover.piIp.ifBlank { return@withContext }
            val body = JSONObject().put("role", "gemini").put("text", text)
                .toString().toRequestBody("application/json".toMediaType())
            nurseHttpClient.newCall(
                Request.Builder().url("http://$ip:5000/nurse/transcript").post(body).build()
            ).execute().close()
            Log.d(TAG, "Nurse transcript posted: ${text.take(60)}")
        } catch (e: Exception) {
            Log.w(TAG, "postNurseTranscript: ${e.message}")
        }
    }

    private fun checkForNurseTags(text: String) {
        nurseTagBuffer.append(text)
        val raw = nurseTagBuffer.toString()

        val stepMatch = Regex("""\[\[STEP:([a-z_]+)]]""").find(raw)
        if (stepMatch != null) {
            val step = stepMatch.groupValues[1]
            nurseTagBuffer.clear()
            scope.launch { postNurseStep(step) }
            return
        }

        val heardMatch = Regex("""\[\[HEARD:([a-zA-Z]+):([^\]]+)]]""").find(raw)
        if (heardMatch != null) {
            val field = heardMatch.groupValues[1]
            val value = heardMatch.groupValues[2].trim()
            nurseTagBuffer.clear()
            scope.launch { postNurseHeard(field, value) }
            return
        }

        if (raw.length > 1000) nurseTagBuffer.clear()
    }

    private suspend fun postNurseStep(step: String) = withContext(Dispatchers.IO) {
        try {
            val ip = piMover.piIp.ifBlank { return@withContext }
            val body = JSONObject().put("step", step)
                .toString().toRequestBody("application/json".toMediaType())
            nurseHttpClient.newCall(
                Request.Builder().url("http://$ip:5000/nurse/session/step").post(body).build()
            ).execute().close()
            Log.d(TAG, "Nurse step: $step")
        } catch (e: Exception) {
            Log.w(TAG, "postNurseStep: ${e.message}")
        }
    }

    private suspend fun postNurseHeard(field: String, value: String) = withContext(Dispatchers.IO) {
        try {
            val ip = piMover.piIp.ifBlank { return@withContext }
            val body = JSONObject().put("field", field).put("value", value)
                .toString().toRequestBody("application/json".toMediaType())
            nurseHttpClient.newCall(
                Request.Builder().url("http://$ip:5000/nurse/session/heard").post(body).build()
            ).execute().close()
            Log.d(TAG, "Nurse heard: $field = $value")
        } catch (e: Exception) {
            Log.w(TAG, "postNurseHeard: ${e.message}")
        }
    }

    // ─── Lip sync ─────────────────────────────────────────────────────────────

    private fun analyzePcmAndPostJaw(pcmBase64: String) {
        val now = System.currentTimeMillis()
        if (now - lastJawPostMs < JAW_MIN_INTERVAL_MS) return
        lastJawPostMs = now
        scope.launch(Dispatchers.Default) {
            try {
                val bytes = Base64.decode(pcmBase64, Base64.DEFAULT)
                if (bytes.size < 2) return@launch
                val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val sampleCount = bytes.size / 2
                var sumSq = 0.0
                for (i in 0 until sampleCount) {
                    val s = buf.getShort().toDouble() / 32768.0
                    sumSq += s * s
                }
                val rms = Math.sqrt(sumSq / sampleCount).toFloat()
                // Asymmetric EMA: fast attack when getting louder, fast decay when getting quieter
                val alpha = if (rms > smoothedAmplitude) JAW_ATTACK_ALPHA else JAW_DECAY_ALPHA
                smoothedAmplitude = alpha * rms + (1f - alpha) * smoothedAmplitude
                val open = if (smoothedAmplitude < JAW_SILENCE) 0f
                           else ((smoothedAmplitude.coerceAtMost(JAW_PEAK) - JAW_SILENCE) / (JAW_PEAK - JAW_SILENCE))
                postJaw(open)
            } catch (e: Exception) {
                Log.w(TAG, "analyzePcm: ${e.message}")
            }
        }
    }

    private suspend fun postJaw(open: Float) = withContext(Dispatchers.IO) {
        try {
            val ip = piMover.piIp.ifBlank { return@withContext }
            val body = JSONObject().put("open", open.toDouble())
                .toString().toRequestBody("application/json".toMediaType())
            nurseHttpClient.newCall(
                Request.Builder().url("http://$ip:5000/jaw").post(body).build()
            ).execute().close()
        } catch (_: Exception) { /* silent — jaw is best-effort */ }
    }

    // ─── Dynamic system prompt builder ────────────────────────────────────────

    private fun buildSystemPrompt(config: NexusConfig): String = buildString {
        val name = config.personaName.ifBlank { DEFAULT_PERSONA_NAME }
        val role = config.role.ifBlank { DEFAULT_ROLE }

        if (config.learningMode) {
            appendLine("You are $name, $role. You are currently in LEARNING MODE.")
            appendLine("""
LEARNING MODE INSTRUCTIONS:
- A specialist trainer is teaching you about objects, exhibits, or products in this space.
- When you start or resume, say: "I'm ready to learn. Point me at something and tell me about it."
- Each time the trainer describes something, repeat back a concise summary and ask: "Shall I save this to my knowledge base?"
- If they confirm (say yes/save/correct/add), output this EXACT format on its own line:
  [[SAVE_FACT:{"title":"<short title>","content":"<full trainer description>","category":"taught"}]]
  Then say: "Saved! Ready for the next one?"
- If the trainer says "what do you see?" or asks your opinion, describe what you observe naturally,
  then ask "Does that match what you want me to learn?" — but NEVER auto-save your own description.
- If they correct you, acknowledge and use their version when saving.
- NEVER hallucinate or add facts the trainer didn't say.
- NEVER skip the confirmation step before outputting [[SAVE_FACT:...]].
""".trimIndent())
            return@buildString
        }

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

        if (config.enrolledPeople.isNotEmpty()) {
            appendLine("\nKNOWN PEOPLE (you may recognise these individuals; greet them by name when confirmed):")
            config.enrolledPeople.forEach { p ->
                val vipTag = if (p.isVip) " ★ VIP GUIDE" else ""
                val notesTag = if (p.notes.isNotBlank()) " — ${p.notes}" else ""
                appendLine("• ${p.name} [${p.roleTag}$vipTag]$notesTag")
            }
        }

        if (config.extraInstructions.isNotBlank())
            appendLine("\nADDITIONAL INSTRUCTIONS: ${config.extraInstructions}")

        append(BASE_RULES)
        // Include nurse rules when persona name or role mentions "nurse", OR when the
        // extra instructions contain NURSE_MODE, OR when the location is a hospital/clinic.
        val nurseMode = config.personaName.contains("nurse", ignoreCase = true)
            || config.role.contains("nurse", ignoreCase = true)
            || config.extraInstructions.contains("NURSE_MODE", ignoreCase = false)
            || config.locationName.contains("hospital", ignoreCase = true)
            || config.locationName.contains("clinic", ignoreCase = true)
            || config.locationName.contains("simi tech", ignoreCase = true)
        if (nurseMode) append(NURSE_RULES)
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
