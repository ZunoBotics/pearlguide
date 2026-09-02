package com.okello.robot.head

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.okello.robot.head.camera.FrameStreamer
import com.okello.robot.head.camera.StereoCameraCapture
import com.okello.robot.head.databinding.ActivityMainBinding
import com.okello.robot.head.detection.ObstacleDetector
import com.okello.robot.head.detection.ObstacleResult
import com.okello.robot.head.gemini.GeminiLiveService
import com.okello.robot.head.mqtt.ConfigPoller
import com.okello.robot.head.mqtt.EnrolledPerson
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private const val TAG = "MainActivity"
private const val PREFS_NAME = "nexus"
private const val KEY_BROKER_IP = "broker_ip"
private const val KEY_PI_IP = "pi_ip"
private const val KEY_FACE_THRESHOLD = "face_threshold"
private const val DEFAULT_FACE_THRESHOLD = 0.65f
private val REQUIRED_PERMISSIONS = arrayOf(
    Manifest.permission.CAMERA,
    Manifest.permission.RECORD_AUDIO,
    "horizonos.permission.HEADSET_CAMERA"
)
private const val PERMISSION_REQUEST_CODE = 10

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var wakeLock: PowerManager.WakeLock? = null
    private var geminiService: GeminiLiveService? = null
    private var stereoCapture: StereoCameraCapture? = null
    private var configPoller: ConfigPoller? = null
    private var frameStreamer: FrameStreamer? = null

    private var obstacleDetector: ObstacleDetector? = null
    private val detectionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var enrolledPeople: List<EnrolledPerson> = emptyList()
    private val lastFaceNudgeAt = mutableMapOf<String, Long>()
    @Volatile private var lastLeftJpeg: String? = null

    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null
    private val proximityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            // value > 0 means sensor uncovered (headset not on face)
            // Only assert screen when it is actually off to avoid hammering the window manager
            if (event.values[0] > 0f) {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                if (!pm.isInteractive) {
                    Log.i(TAG, "Screen off detected via proximity — re-asserting screen on")
                    assertScreenOn()
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    private val serviceConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            geminiService = (binder as GeminiLiveService.LocalBinder).getService()
            geminiService?.statusCallback = { text ->
                runOnUiThread { binding.statusText.text = text }
            }
            geminiService?.onEnrollFaceCallback = { json -> handleEnrollFace(json) }
            startCamera()
            startConfigPoller()
            Log.i(TAG, "GeminiLiveService bound")
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            geminiService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        acquireWakeLock()
        tryKeepScreenOn()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        proximitySensor?.let { sensor ->
            sensorManager?.registerListener(proximityListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }

        if (permissionsGranted()) {
            startServiceAndBind()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE)
        }

        binding.brokerSettingsButton.setOnClickListener { showBrokerIpDialog() }
    }

    override fun onResume() {
        super.onResume()
        assertScreenOn()
        BootReceiver.applyScreenSettings(this)
        BootReceiver.disableProximitySensor(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) assertScreenOn()
    }

    private fun assertScreenOn() {
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        val wl = wakeLock
        if (wl != null && !wl.isHeld) {
            wl.acquire()
            Log.i(TAG, "WakeLock re-acquired")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && permissionsGranted()) {
            startServiceAndBind()
        } else {
            binding.statusText.text = "Camera & Mic permissions required"
        }
    }

    override fun onDestroy() {
        sensorManager?.unregisterListener(proximityListener)
        configPoller?.stop()
        stereoCapture?.stop()
        stereoCapture = null
        frameStreamer?.stop()
        frameStreamer = null
        obstacleDetector?.close()
        obstacleDetector = null
        try { unbindService(serviceConn) } catch (_: Exception) {}
        wakeLock?.release()
        super.onDestroy()
    }

    private fun startServiceAndBind() {
        val intent = Intent(this, GeminiLiveService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConn, Context.BIND_AUTO_CREATE)
    }

    private fun showBrokerIpDialog() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentIp = prefs.getString(KEY_BROKER_IP, "") ?: ""
        val currentPiIp = prefs.getString(KEY_PI_IP, "") ?: ""
        val currentThreshold = prefs.getFloat(KEY_FACE_THRESHOLD, DEFAULT_FACE_THRESHOLD)

        val px = (20 * resources.displayMetrics.density).toInt()

        val ipInput = EditText(this).apply {
            hint = "Phone IP e.g. 192.168.1.42"
            setText(currentIp)
            selectAll()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val piIpLabel = android.widget.TextView(this).apply {
            text = "Pi IP (nurse kiosk)"
            setPadding(0, px / 2, 0, 0)
        }
        val piIpInput = EditText(this).apply {
            hint = "e.g. 192.168.1.55"
            setText(currentPiIp)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val thresholdLabel = android.widget.TextView(this).apply {
            text = "Face match threshold (0.50 – 0.90)"
            setPadding(0, px / 2, 0, 0)
        }
        val thresholdInput = EditText(this).apply {
            hint = "e.g. 0.65"
            setText("%.2f".format(currentThreshold))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px, px / 2, px, 0)
            addView(ipInput)
            addView(piIpLabel)
            addView(piIpInput)
            addView(thresholdLabel)
            addView(thresholdInput)
        }

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setMessage("Connection settings")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val ip = ipInput.text.toString().trim()
                val piIp = piIpInput.text.toString().trim()
                val threshold = thresholdInput.text.toString().toFloatOrNull()
                    ?.coerceIn(0.50f, 0.90f) ?: DEFAULT_FACE_THRESHOLD

                prefs.edit()
                    .putString(KEY_BROKER_IP, ip)
                    .putString(KEY_PI_IP, piIp)
                    .putFloat(KEY_FACE_THRESHOLD, threshold)
                    .apply()

                if (piIp.isNotBlank()) geminiService?.setPiIp(piIp)
                obstacleDetector?.setFaceThreshold(threshold)
                Log.i(TAG, "Pi IP set to $piIp")

                configPoller?.stop()
                startConfigPoller()
                frameStreamer?.stop()
                frameStreamer = if (ip.isNotEmpty()) FrameStreamer(ip) else null
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startConfigPoller() {
        configPoller = ConfigPoller(
            context = applicationContext,
            onConfigUpdate = { config ->
                Log.i(TAG, "Config received: persona=${config.personaName} location=${config.locationName}")
                enrolledPeople = config.enrolledPeople
                geminiService?.updateConfig(config)
                obstacleDetector?.enrollFaces(config.enrolledPeople)
            },
            onStatus = { status ->
                runOnUiThread { binding.transcriptText.text = status }
            }
        )
        configPoller?.start()
    }

    private fun startCamera() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val phoneIp = prefs.getString(KEY_BROKER_IP, "") ?: ""
        frameStreamer?.stop()
        frameStreamer = if (phoneIp.isNotEmpty()) FrameStreamer(phoneIp) else null

        if (obstacleDetector == null) {
            obstacleDetector = ObstacleDetector(applicationContext).also { det ->
                val threshold = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getFloat(KEY_FACE_THRESHOLD, DEFAULT_FACE_THRESHOLD)
                det.setFaceThreshold(threshold)
            }
        }

        stereoCapture = StereoCameraCapture(
            context = this,
            lifecycleOwner = this,
            previewView = binding.cameraPreview,
            rightPreview = binding.rightCameraPreview,
            onFrame = { base64Jpeg ->
                lastLeftJpeg = base64Jpeg
                geminiService?.sendVideoFrame(base64Jpeg)
                val detector = obstacleDetector
                val streamer = frameStreamer
                if (detector != null) {
                    detectionScope.launch {
                        val leftBmp = base64ToBitmap(base64Jpeg)
                        val rightBmp = stereoCapture?.getLastRightBmp()
                        val obstacleResults = if (leftBmp != null)
                            detector.detectObstacles(leftBmp, rightBmp)
                        else emptyList()
                        leftBmp?.recycle()
                        runOnUiThread {
                            binding.detectionOverlay.update(detector.lastYoloResults)
                        }
                        // Nudge Gemini when kby-ai confirms a known face (once per minute per person)
                        val now = System.currentTimeMillis()
                        val nudgeLines = obstacleResults
                            .filter { it.className.startsWith("face:") && !it.className.contains("unknown") }
                            .mapNotNull { r ->
                                val name = r.className.removePrefix("face:")
                                if (now - (lastFaceNudgeAt[name] ?: 0L) < 60_000L) return@mapNotNull null
                                lastFaceNudgeAt[name] = now
                                val person = enrolledPeople.find { it.name == name }
                                val role = person?.roleTag ?: "Visitor"
                                val vip = if (person?.isVip == true) " ★ VIP" else ""
                                val notes = if (person?.notes?.isNotBlank() == true) " — ${person.notes}" else ""
                                "• $name [$role$vip]$notes"
                            }
                        if (nudgeLines.isNotEmpty()) {
                            geminiService?.sendFaceContext(
                                "[FACE RECOGNITION] Confirmed in camera view:\n" +
                                nudgeLines.joinToString("\n") +
                                "\nUse their name naturally when speaking to them."
                            )
                        }
                        val detectionInfos = obstacleResults.map { toDetectionInfo(it) }
                        streamer?.sendFrame(base64Jpeg, detectionInfos)
                            ?: frameStreamer?.sendFrame(base64Jpeg, emptyList())
                    }
                } else {
                    frameStreamer?.sendFrame(base64Jpeg, emptyList())
                }
            },
            onDepth = { result ->
                runOnUiThread {
                    binding.depthMapView.setImageBitmap(result.depthMap)
                    binding.depthMapView.visibility = View.VISIBLE
                    if (result.nearestCm != null) {
                        binding.nearestText.text = "Nearest: %.0f cm".format(result.nearestCm)
                        binding.nearestText.visibility = View.VISIBLE
                    } else {
                        binding.nearestText.visibility = View.GONE
                    }
                }
            },
            onNv21Frame = { nv21, w, h ->
                val detector = obstacleDetector
                if (detector != null) detectionScope.launch { detector.detectFaces(nv21, w, h) }
            }
        )
        stereoCapture?.start()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "RobotHead::AlwaysActive"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun tryKeepScreenOn() {
        if (Settings.System.canWrite(this)) {
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, Int.MAX_VALUE)
            // Quest-specific: disable proximity-based sleep
            try { Settings.System.putInt(contentResolver, "quest_proximity_sensor_enabled", 0) } catch (_: Exception) {}
            // Keep awake while plugged in (charging / USB)
            try { Settings.Global.putInt(contentResolver, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 3) } catch (_: Exception) {}
            Log.i(TAG, "Screen-off timeout set to max, proximity sleep disabled")
        } else {
            Log.w(TAG, "WRITE_SETTINGS not granted — run: adb shell settings put system screen_off_timeout 2147483647")
        }
    }

    private fun permissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun base64ToBitmap(base64: String): android.graphics.Bitmap? = try {
        val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (_: Exception) { null }

    private fun toDetectionInfo(result: ObstacleResult) =
        com.okello.robot.head.camera.DetectionInfo(
            type = result.className,
            direction = result.direction,
            distanceCm = result.distanceCm
        )

    private fun handleEnrollFace(json: String) {
        detectionScope.launch(Dispatchers.IO) {
            try {
                val j = JSONObject(json)
                val name = j.optString("name").ifBlank { return@launch }
                val roleTag = j.optString("roleTag", "Visitor")
                val notes = j.optString("notes", "")
                val cx = j.optDouble("cx", 0.5).toFloat()
                val cy = j.optDouble("cy", 0.5).toFloat()
                val fw = j.optDouble("w", 0.25).toFloat()
                val fh = j.optDouble("h", 0.4).toFloat()

                val jpeg = lastLeftJpeg
                val photoBase64 = if (jpeg != null) cropFaceFromJpeg(jpeg, cx, cy, fw, fh) else null

                postEnrollFace(name, roleTag, notes, photoBase64)
                Log.i(TAG, "Enroll face request: $name ($roleTag) photo=${photoBase64 != null}")
            } catch (e: Exception) {
                Log.e(TAG, "handleEnrollFace failed: ${e.message}")
            }
        }
    }

    private fun cropFaceFromJpeg(base64Jpeg: String, cx: Float, cy: Float, fw: Float, fh: Float): String? {
        return try {
            val bytes = android.util.Base64.decode(base64Jpeg, android.util.Base64.DEFAULT)
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val bw = bmp.width; val bh = bmp.height
            // Add 30% padding around the bounding box for head/shoulders context
            val padX = (fw * bw * 0.3f).toInt()
            val padY = (fh * bh * 0.3f).toInt()
            val left = ((cx - fw / 2f) * bw).toInt().minus(padX).coerceAtLeast(0)
            val top  = ((cy - fh / 2f) * bh).toInt().minus(padY).coerceAtLeast(0)
            val right  = ((cx + fw / 2f) * bw).toInt().plus(padX).coerceAtMost(bw)
            val bottom = ((cy + fh / 2f) * bh).toInt().plus(padY).coerceAtMost(bh)
            val cropW = (right - left).coerceAtLeast(1)
            val cropH = (bottom - top).coerceAtLeast(1)
            val cropped = android.graphics.Bitmap.createBitmap(bmp, left, top, cropW, cropH)
            bmp.recycle()
            // Scale to max 400px
            val scale = 400f / maxOf(cropped.width, cropped.height)
            val scaled = if (scale < 1f)
                android.graphics.Bitmap.createScaledBitmap(
                    cropped, (cropped.width * scale).toInt(), (cropped.height * scale).toInt(), true
                ).also { cropped.recycle() }
            else cropped
            val out = ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
            scaled.recycle()
            android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "cropFaceFromJpeg failed: ${e.message}"); null
        }
    }

    private fun postEnrollFace(name: String, roleTag: String, notes: String, photoBase64: String?) {
        try {
            val ip = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_BROKER_IP, "") ?: ""
            if (ip.isBlank()) { Log.w(TAG, "No phone IP — cannot enroll $name"); return }
            val body = JSONObject().apply {
                put("name", name)
                put("roleTag", roleTag)
                put("notes", notes)
                if (photoBase64 != null) put("photoBase64", photoBase64)
            }.toString().toRequestBody("application/json".toMediaType())
            OkHttpClient().newCall(
                Request.Builder().url("http://$ip:8080/enroll_face").post(body).build()
            ).execute().close()
            Log.i(TAG, "Posted new face to phone: $name")
        } catch (e: Exception) {
            Log.e(TAG, "postEnrollFace failed: ${e.message}")
        }
    }
}
