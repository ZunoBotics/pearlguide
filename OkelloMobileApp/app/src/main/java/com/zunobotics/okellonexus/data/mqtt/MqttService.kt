package com.zunobotics.okellonexus.data.mqtt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.zunobotics.okellonexus.data.model.RobotConnectionState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject

private const val TAG = "MqttService"
private const val NOTIF_CHANNEL = "nexus_connection"
private const val NOTIF_ID = 42

@AndroidEntryPoint
class MqttService : Service() {

    @Inject lateinit var mqttClient: MqttClientWrapper
    @Inject lateinit var broker: MinimalMqttBroker
    @Inject lateinit var configServer: ConfigHttpServer
    @Inject lateinit var personaRepo: com.zunobotics.okellonexus.data.repository.PersonaRepository
    @Inject lateinit var locationRepo: com.zunobotics.okellonexus.data.repository.LocationRepository
    @Inject lateinit var settingsRepo: com.zunobotics.okellonexus.data.repository.SettingsRepository
    @Inject lateinit var knowledgeRepo: com.zunobotics.okellonexus.data.repository.KnowledgeRepository
    @Inject lateinit var mqttRepo: com.zunobotics.okellonexus.data.repository.MqttRepository

    inner class LocalBinder : Binder() {
        fun getService() = this@MqttService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification("Starting broker…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, buildNotification("Starting broker…"))
        }

        // Start the embedded broker, then connect the phone's own Paho client to it
        broker.start(port = 1883)
        configServer.start(port = 8080)
        scope.launch {
            delay(300) // give broker a moment to bind the port
            mqttClient.connect("tcp://127.0.0.1:1883", "nexus-phone-${UUID.randomUUID()}")
        }

        scope.launch {
            mqttClient.connectionState.collectLatest { state ->
                val text = when (state) {
                    RobotConnectionState.CONNECTED_IDLE,
                    RobotConnectionState.CONNECTED_ACTIVE -> "Broker ready — waiting for Quest"
                    RobotConnectionState.CONNECTING -> "Starting…"
                    RobotConnectionState.ERROR -> "Broker error — retrying"
                    else -> "Starting broker…"
                }
                updateNotification(text)
                if (state == RobotConnectionState.CONNECTED_IDLE) scope.launch { republishActiveSettings() }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        broker.stop()
        configServer.stop()
        super.onDestroy()
    }

    private suspend fun republishActiveSettings() {
        delay(300) // let broker settle
        try {
            val persona = personaRepo.getActive()
            val location = locationRepo.getActive()
            val settings = settingsRepo.settings.first()

            if (persona != null) {
                val tags = try { kotlinx.serialization.json.Json.decodeFromString<List<String>>(persona.personality) } catch (_: Exception) { emptyList() }
                mqttRepo.sendPersona(persona.name, persona.role, persona.greeting, tags, persona.voiceSpeed, persona.extraInstructions, persona.languageCode)
            }
            if (location != null) {
                mqttRepo.sendLocation(location.name, location.type, location.description, location.gpsLat, location.gpsLng, location.openingHours, location.notes, location.specialInstructions)
            }
            val langCodes = settings.selectedLanguages.split(",").filter { it.isNotBlank() }
            mqttRepo.sendLanguage(langCodes, settings.codeSwitching)

            val allKnowledge = knowledgeRepo.getAll()
            if (allKnowledge.isNotEmpty()) mqttRepo.sendKnowledgeSnapshot(allKnowledge)

            Log.i(TAG, "Republished: persona=${persona?.name} location=${location?.name} knowledge=${allKnowledge.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Republish failed: ${e.message}")
        }
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(NOTIF_CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(NOTIF_CHANNEL, "Nexus Connection", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("Okello Nexus")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }
}
