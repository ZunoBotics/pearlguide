package com.zunobotics.okellonexus.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

data class ObstacleAlert(
    val type: String,           // "staircase", "obstacle", "person", "door_closed", etc.
    val direction: String,      // "forward", "left", "right"
    val distanceCm: Int,
    val confidence: Float,
    val severity: String,       // "danger", "caution", "info"
    val timestampMs: Long = System.currentTimeMillis()
)

@Singleton
class ObstacleAlertRepository @Inject constructor() {
    private val _alerts = MutableStateFlow<List<ObstacleAlert>>(emptyList())
    val alerts: StateFlow<List<ObstacleAlert>> = _alerts.asStateFlow()

    fun push(alert: ObstacleAlert) {
        _alerts.update { current ->
            (listOf(alert) + current).take(50)
        }
    }

    fun clear() { _alerts.value = emptyList() }
}
