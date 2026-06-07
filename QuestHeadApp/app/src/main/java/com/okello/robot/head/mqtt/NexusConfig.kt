package com.okello.robot.head.mqtt

data class NexusConfig(
    val personaName: String = "",
    val role: String = "",
    val greeting: String = "",
    val personalityTraits: List<String> = emptyList(),
    val extraInstructions: String = "",
    val selectedLanguages: List<String> = listOf("en"),
    val codeSwitching: Boolean = false,
    val knowledgeFacts: List<KnowledgeFact> = emptyList(),
    val locationName: String = "",
    val locationDescription: String = "",
    val locationOpeningHours: String = "",
    val locationSpecialInstructions: String = "",
    val pendingCommand: String = "",
    val enrolledPeople: List<EnrolledPerson> = emptyList(),
    val learningMode: Boolean = false,
    val personaId: String = "",
    val locationId: String = ""
)

data class EnrolledPerson(
    val id: String,
    val name: String,
    val roleTag: String,
    val isVip: Boolean,
    val notes: String
)

data class KnowledgeFact(
    val id: String,
    val title: String,
    val content: String,
    val category: String
)
