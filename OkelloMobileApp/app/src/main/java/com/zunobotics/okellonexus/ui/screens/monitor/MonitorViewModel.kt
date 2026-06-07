package com.zunobotics.okellonexus.ui.screens.monitor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zunobotics.okellonexus.data.model.RobotConnectionState
import com.zunobotics.okellonexus.data.model.RobotStatus
import com.zunobotics.okellonexus.data.mqtt.IncomingMessage
import com.zunobotics.okellonexus.data.repository.CameraFrame
import com.zunobotics.okellonexus.data.repository.CameraStreamRepository
import com.zunobotics.okellonexus.data.repository.MqttRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import javax.inject.Inject

data class MonitorState(
    val robotStatus: RobotStatus = RobotStatus(),
    val currentSpeech: String = "",
    val personDetected: Boolean = false,
    val obstacleWarning: Boolean = false,
    val lastUpdatedMs: Long = 0
)

@HiltViewModel
class MonitorViewModel @Inject constructor(
    private val mqttRepo: MqttRepository,
    private val cameraStreamRepo: CameraStreamRepository
) : ViewModel() {

    val connectionState: StateFlow<RobotConnectionState> = mqttRepo.connectionState

    val cameraFrame: StateFlow<CameraFrame?> = cameraStreamRepo.frame
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _monitorState = MutableStateFlow(MonitorState())
    val monitorState: StateFlow<MonitorState> = _monitorState.asStateFlow()

    private val _eventLog = MutableStateFlow<List<String>>(emptyList())
    val eventLog: StateFlow<List<String>> = _eventLog.asStateFlow()

    init {
        viewModelScope.launch {
            mqttRepo.incomingMessages.collect { msg -> handleMessage(msg) }
        }
    }

    private fun handleMessage(msg: IncomingMessage) {
        when (msg.topic) {
            "robot/status" -> parseStatus(msg.payload)
            "robot/speech/current" -> {
                _monitorState.value = _monitorState.value.copy(currentSpeech = msg.payload)
                addEvent("Speech: ${msg.payload.take(60)}")
            }
            "robot/sensor/person" -> {
                val detected = msg.payload.trim().lowercase() == "true"
                _monitorState.value = _monitorState.value.copy(personDetected = detected)
                if (detected) addEvent("Person detected")
            }
            "robot/sensor/obstacle" -> {
                val warning = msg.payload.trim().lowercase() == "true"
                _monitorState.value = _monitorState.value.copy(obstacleWarning = warning)
                if (warning) addEvent("Obstacle warning!")
            }
            "robot/error" -> addEvent("Error: ${msg.payload.take(60)}")
            "robot/heartbeat" -> addEvent("Heartbeat")
        }
    }

    private fun parseStatus(json: String) {
        try {
            val obj = Json.parseToJsonElement(json).jsonObject
            val status = RobotStatus(
                personaName = obj["persona_name"]?.jsonPrimitive?.content ?: "",
                locationName = obj["location_name"]?.jsonPrimitive?.content ?: "",
                activeLanguage = obj["language"]?.jsonPrimitive?.content ?: "en",
                mode = obj["mode"]?.jsonPrimitive?.content ?: "IDLE",
                batteryPercent = obj["battery_pct"]?.jsonPrimitive?.int ?: -1,
                piUptimeSeconds = obj["pi_uptime_sec"]?.jsonPrimitive?.long ?: 0,
                timestampMs = obj["timestamp_ms"]?.jsonPrimitive?.long ?: 0
            )
            _monitorState.value = _monitorState.value.copy(
                robotStatus = status,
                lastUpdatedMs = System.currentTimeMillis()
            )
        } catch (_: Exception) {}
    }

    private fun addEvent(entry: String) {
        _eventLog.value = (listOf(entry) + _eventLog.value).take(20)
    }

    fun clearEventLog() {
        _eventLog.value = emptyList()
    }
}
