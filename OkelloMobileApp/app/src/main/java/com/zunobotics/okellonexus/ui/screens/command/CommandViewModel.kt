package com.zunobotics.okellonexus.ui.screens.command

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zunobotics.okellonexus.data.model.RobotConnectionState
import com.zunobotics.okellonexus.data.repository.MqttRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RecentCommand(val label: String, val payload: String, val timestampMs: Long)

@HiltViewModel
class CommandViewModel @Inject constructor(
    private val mqttRepo: MqttRepository
) : ViewModel() {

    val connectionState: StateFlow<RobotConnectionState> = mqttRepo.connectionState

    val customInstruction = MutableStateFlow("")

    private val _recentCommands = MutableStateFlow<List<RecentCommand>>(emptyList())
    val recentCommands: StateFlow<List<RecentCommand>> = _recentCommands.asStateFlow()

    private val _snackMessage = MutableStateFlow<String?>(null)
    val snackMessage: StateFlow<String?> = _snackMessage.asStateFlow()

    fun sendQuickCommand(command: String, label: String) = viewModelScope.launch {
        mqttRepo.sendQuickCommand(command)
        addRecent(label, command)
        _snackMessage.value = "Sent: $label"
    }

    fun sendEmergencyStop() = viewModelScope.launch {
        mqttRepo.sendEmergencyStop()
        addRecent("Emergency Stop", "EMERGENCY_STOP")
        _snackMessage.value = "Emergency stop sent"
    }

    fun sendCustomInstruction() = viewModelScope.launch {
        val instruction = customInstruction.value.trim()
        if (instruction.isEmpty()) return@launch
        mqttRepo.sendCustomInstruction(instruction)
        addRecent("Custom: ${instruction.take(30)}…", instruction)
        customInstruction.value = ""
        _snackMessage.value = "Instruction sent"
    }

    private fun addRecent(label: String, payload: String) {
        _recentCommands.value = (
            listOf(RecentCommand(label, payload, System.currentTimeMillis())) +
            _recentCommands.value
        ).take(8)
    }

    fun clearSnack() {
        _snackMessage.value = null
    }
}
