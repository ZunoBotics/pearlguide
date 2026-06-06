package com.zunobotics.okellonexus.data.mqtt

data class IncomingMessage(val topic: String, val payload: String)

sealed class RobotEvent {
    data class StatusUpdate(val json: String) : RobotEvent()
    data class CameraFrame(val jpegBytes: ByteArray) : RobotEvent()
    data class SpeechText(val text: String) : RobotEvent()
    data class SensorData(val json: String) : RobotEvent()
    data class TeachDescription(val text: String) : RobotEvent()
    data class TeachFrame(val jpegBytes: ByteArray) : RobotEvent()
    data class KnowledgeList(val json: String) : RobotEvent()
    data class Error(val topic: String, val message: String) : RobotEvent()
    data class Heartbeat(val sourceId: String, val timestampMs: Long) : RobotEvent()
    object Unknown : RobotEvent()
}
