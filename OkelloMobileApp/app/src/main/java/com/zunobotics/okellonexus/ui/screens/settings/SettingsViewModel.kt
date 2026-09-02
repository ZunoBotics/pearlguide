package com.zunobotics.okellonexus.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zunobotics.okellonexus.data.repository.AppPrefsKeys
import com.zunobotics.okellonexus.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val robotIp: String = "192.168.49.1",
    val mqttPort: String = "1883",
    val cameraQuality: CameraQuality = CameraQuality.MEDIUM,
    val reconnectIntervalSeconds: Float = 5f,
    val autoConnect: Boolean = true,
    val piIp: String = "100.116.191.56"
)

enum class CameraQuality(val label: String) {
    LOW("Low (360p)"),
    MEDIUM("Medium (720p)"),
    HIGH("High (1080p)")
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepo.settings.collect { appSettings ->
                _uiState.value = SettingsUiState(
                    robotIp = appSettings.robotIp,
                    mqttPort = appSettings.mqttPort.toString(),
                    cameraQuality = when (appSettings.cameraQuality) {
                        "LOW" -> CameraQuality.LOW
                        "HIGH" -> CameraQuality.HIGH
                        else -> CameraQuality.MEDIUM
                    },
                    reconnectIntervalSeconds = appSettings.reconnectInterval.toFloat(),
                    autoConnect = appSettings.autoConnect,
                    piIp = appSettings.piIp
                )
                _isLoading.value = false
            }
        }
    }

    fun updateRobotIp(ip: String) {
        _uiState.value = _uiState.value.copy(robotIp = ip)
    }

    fun updateMqttPort(port: String) {
        _uiState.value = _uiState.value.copy(mqttPort = port)
    }

    fun updateCameraQuality(quality: CameraQuality) {
        _uiState.value = _uiState.value.copy(cameraQuality = quality)
    }

    fun updateReconnectInterval(seconds: Float) {
        _uiState.value = _uiState.value.copy(reconnectIntervalSeconds = seconds)
    }

    fun updateAutoConnect(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(autoConnect = enabled)
    }

    fun updatePiIp(ip: String) {
        _uiState.value = _uiState.value.copy(piIp = ip)
    }

    fun save() = viewModelScope.launch {
        val s = _uiState.value
        settingsRepo.update {
            this[AppPrefsKeys.ROBOT_IP] = s.robotIp.trim()
            this[AppPrefsKeys.MQTT_PORT] = s.mqttPort.toIntOrNull() ?: 1883
            this[AppPrefsKeys.CAMERA_QUALITY] = s.cameraQuality.name.lowercase()
            this[AppPrefsKeys.RECONNECT_INTERVAL] = s.reconnectIntervalSeconds.toInt()
            this[AppPrefsKeys.AUTO_CONNECT] = s.autoConnect
            this[AppPrefsKeys.PI_IP] = s.piIp.trim()
        }
        _saved.value = true
    }
}
