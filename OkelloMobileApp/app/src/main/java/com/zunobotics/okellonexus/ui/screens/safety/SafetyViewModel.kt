package com.zunobotics.okellonexus.ui.screens.safety

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zunobotics.okellonexus.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SafetyViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository
) : ViewModel() {

    private val _activeCommand = MutableStateFlow<String?>(null)
    val activeCommand: StateFlow<String?> = _activeCommand.asStateFlow()

    fun emergencyStop() = viewModelScope.launch {
        _activeCommand.value = "emergency_stop"
        settingsRepo.setCommand("emergency_stop")
        delay(8_000)
        settingsRepo.setCommand("")
        _activeCommand.value = null
    }

    fun callForHelp() = viewModelScope.launch {
        _activeCommand.value = "call_human"
        settingsRepo.setCommand("call_human")
        delay(8_000)
        settingsRepo.setCommand("")
        _activeCommand.value = null
    }

    fun resume() = viewModelScope.launch {
        settingsRepo.setCommand("resume")
        delay(3_000)
        settingsRepo.setCommand("")
        _activeCommand.value = null
    }
}
