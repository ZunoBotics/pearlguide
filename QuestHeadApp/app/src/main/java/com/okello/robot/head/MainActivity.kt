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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "MainActivity"
private const val PREFS_NAME = "nexus"
private const val KEY_BROKER_IP = "broker_ip"
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
        val current = prefs.getString(KEY_BROKER_IP, "") ?: ""

        val input = EditText(this).apply {
            hint = "e.g. 192.168.1.42"
            setText(current)
            selectAll()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val px = (20 * resources.displayMetrics.density).toInt()
            setPadding(px, px / 2, px, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("Phone IP Address")
            .setMessage("Enter the IP shown on the Connect screen in the phone app")
            .setView(container)
            .setPositiveButton("Connect") { _, _ ->
                val ip = input.text.toString().trim()
                prefs.edit().putString(KEY_BROKER_IP, ip).apply()
                Log.i(TAG, "Phone IP set to: $ip — restarting ConfigPoller + FrameStreamer")
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
            obstacleDetector = ObstacleDetector(applicationContext)
        }

        stereoCapture = StereoCameraCapture(
            context = this,
            lifecycleOwner = this,
            previewView = binding.cameraPreview,
            onFrame = { base64Jpeg ->
                geminiService?.sendVideoFrame(base64Jpeg)
                // Run detection async — don't block the camera thread
                val detector = obstacleDetector
                val streamer = frameStreamer
                if (detector != null && streamer != null) {
                    detectionScope.launch {
                        val bmp = base64ToBitmap(base64Jpeg)
                        val detections = if (bmp != null) {
                            val results = detector.detect(bmp, null)
                            bmp.recycle()
                            results.map { toDetectionInfo(it) }
                        } else emptyList()
                        streamer.sendFrame(base64Jpeg, detections)
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
}
