package com.zunobotics.okellonexus.data.model

data class RobotStatus(
    val personaName: String = "",
    val locationName: String = "",
    val activeLanguage: String = "en",
    val mode: String = "IDLE",          // ACTIVE, IDLE, NAVIGATING, TEACHING
    val batteryPercent: Int = -1,
    val piUptimeSeconds: Long = 0,
    val activeKnowledgeCount: Int = 0,
    val lastCommandAt: Long = 0,
    val personDetected: Boolean = false,
    val nearestObstacleCm: Float? = null,
    val currentSpeechText: String = "",
    val geminiSessionId: String = "",
    val timestampMs: Long = 0
)
