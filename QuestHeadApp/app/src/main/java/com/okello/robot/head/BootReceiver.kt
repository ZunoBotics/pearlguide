package com.okello.robot.head

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.okello.robot.head.gemini.GeminiLiveService

private const val TAG = "BootReceiver"

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.i(TAG, "Boot completed — applying persistent screen settings")

        applyScreenSettings(context)
        disableProximitySensor(context)

        // Launch the app
        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(launch)

        ContextCompat.startForegroundService(
            context,
            Intent(context, GeminiLiveService::class.java)
        )
    }

    companion object {
        fun applyScreenSettings(context: Context) {
            try {
                if (Settings.System.canWrite(context)) {
                    Settings.System.putInt(
                        context.contentResolver,
                        Settings.System.SCREEN_OFF_TIMEOUT,
                        Int.MAX_VALUE
                    )
                    // Quest-specific: disable proximity-based sleep
                    try {
                        Settings.System.putInt(
                            context.contentResolver,
                            "quest_proximity_sensor_enabled", 0
                        )
                    } catch (_: Exception) {}
                    Log.i(TAG, "Screen timeout set to max")
                }
                Settings.Global.putInt(
                    context.contentResolver,
                    Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 3
                )
            } catch (e: Exception) {
                Log.w(TAG, "applyScreenSettings: ${e.message}")
            }
        }

        fun disableProximitySensor(context: Context) {
            try {
                // Tell VR power manager the headset is on face — keeps screen alive
                context.sendBroadcast(
                    Intent("com.oculus.vrpowermanager.prox_close")
                )
                Log.i(TAG, "Proximity sensor suppressed")
            } catch (e: Exception) {
                Log.w(TAG, "disableProximitySensor: ${e.message}")
            }
        }
    }
}
