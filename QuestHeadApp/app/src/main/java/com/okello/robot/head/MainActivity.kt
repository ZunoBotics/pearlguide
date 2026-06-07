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
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
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
import com.okello.robot.head.gemini.GeminiLiveService
import com.okello.robot.head.mqtt.ConfigPoller

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

    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null
    private val proximityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            // value > 0 means headset removed / sensor uncovered
            if (event.values[0] > 0f) {
                Log.i(TAG, "Proximity sensor: headset removed — re-asserting screen on")
                assertScreenOn()
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

        stereoCapture = StereoCameraCapture(
            context = this,
            lifecycleOwner = this,
            previewView = binding.cameraPreview,
            onFrame = { base64Jpeg ->
                geminiService?.sendVideoFrame(base64Jpeg)
                frameStreamer?.sendFrame(base64Jpeg, emptyList())
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
        ).apply { acquire() }
    }

    private fun permissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}
