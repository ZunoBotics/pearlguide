package com.zunobotics.okellonexus.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zunobotics.okellonexus.data.model.RobotConnectionState
import com.zunobotics.okellonexus.data.model.RobotStatus
import com.zunobotics.okellonexus.data.mqtt.IncomingMessage
import com.zunobotics.okellonexus.data.repository.KnowledgeRepository
import com.zunobotics.okellonexus.data.repository.LocationRepository
import com.zunobotics.okellonexus.data.repository.MqttRepository
import com.zunobotics.okellonexus.data.repository.PersonaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val mqttRepo: MqttRepository,
    private val knowledgeRepo: KnowledgeRepository,
    private val personaRepo: PersonaRepository,
    private val locationRepo: LocationRepository
) : ViewModel() {

    val connectionState: StateFlow<RobotConnectionState> = mqttRepo.connectionState

    private val _robotStatus = MutableStateFlow(RobotStatus())
    val robotStatus: StateFlow<RobotStatus> = _robotStatus.asStateFlow()

    val knowledgeCount: StateFlow<Int> = knowledgeRepo.count
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), 0)

    val activePersonaName: StateFlow<String> = personaRepo.activePersona
        .map { it?.name ?: "" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val activeLocationName: StateFlow<String> = locationRepo.activeLocation
        .map { it?.name ?: "" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    private val _activityLog = MutableStateFlow<List<String>>(emptyList())
    val activityLog: StateFlow<List<String>> = _activityLog.asStateFlow()

    init {
        viewModelScope.launch {
            mqttRepo.incomingMessages.collect { msg -> handleMessage(msg) }
        }
    }

    private fun handleMessage(msg: IncomingMessage) {
        when {
            msg.topic == "robot/status" -> parseStatus(msg.payload)
            msg.topic == "robot/heartbeat" -> addLog("Heartbeat from robot")
            msg.topic == "robot/error" -> addLog("Error: ${msg.payload.take(60)}")
        }
    }

    private fun parseStatus(json: String) {
        try {
            val obj = Json.parseToJsonElement(json).jsonObject
            _robotStatus.value = RobotStatus(
                personaName = obj["persona_name"]?.jsonPrimitive?.content ?: "",
                locationName = obj["location_name"]?.jsonPrimitive?.content ?: "",
                activeLanguage = obj["language"]?.jsonPrimitive?.content ?: "en",
                mode = obj["mode"]?.jsonPrimitive?.content ?: "IDLE",
                batteryPercent = obj["battery_pct"]?.jsonPrimitive?.int ?: -1,
                piUptimeSeconds = obj["pi_uptime_sec"]?.jsonPrimitive?.long ?: 0,
                timestampMs = obj["timestamp_ms"]?.jsonPrimitive?.long ?: 0
            )
        } catch (_: Exception) {}
    }

    private fun addLog(entry: String) {
        _activityLog.value = (listOf(entry) + _activityLog.value).take(10)
    }

    fun sendEmergencyStop() {
        mqttRepo.sendEmergencyStop()
        addLog("Emergency stop sent")
    }
}
