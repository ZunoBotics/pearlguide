package com.zunobotics.okellonexus.ui.screens.connect

import androidx.lifecycle.ViewModel
import com.zunobotics.okellonexus.data.model.RobotConnectionState
import com.zunobotics.okellonexus.data.repository.MqttRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

// The phone IS the broker — always connects to the local embedded broker.
// The "Robot IP" in Settings is reserved for the future Pi scenario.
private const val LOCAL_BROKER = "tcp://127.0.0.1:1883"

@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val mqttRepo: MqttRepository
) : ViewModel() {

    val connectionState: StateFlow<RobotConnectionState> = mqttRepo.connectionState

    // MqttService already connects on startup; this is a manual retry fallback.
    fun connect() {
        mqttRepo.connect(LOCAL_BROKER, "nexus-phone-${System.currentTimeMillis()}")
    }
}
