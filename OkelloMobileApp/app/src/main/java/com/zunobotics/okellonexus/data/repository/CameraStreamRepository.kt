package com.zunobotics.okellonexus.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class CameraDetection(
    val type: String,
    val direction: String,
    val distanceCm: Int
)

data class CameraFrame(
    val jpegBase64: String,
    val detections: List<CameraDetection>,
    val receivedAt: Long = System.currentTimeMillis()
)

@Singleton
class CameraStreamRepository @Inject constructor() {
    private val _frame = MutableStateFlow<CameraFrame?>(null)
    val frame: StateFlow<CameraFrame?> = _frame.asStateFlow()

    fun push(frame: CameraFrame) { _frame.value = frame }
    fun clear() { _frame.value = null }
}
