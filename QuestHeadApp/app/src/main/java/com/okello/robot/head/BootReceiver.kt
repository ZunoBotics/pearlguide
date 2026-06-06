package com.okello.robot.head

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.okello.robot.head.gemini.GeminiLiveService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GeminiLiveService::class.java)
            )
        }
    }
}
