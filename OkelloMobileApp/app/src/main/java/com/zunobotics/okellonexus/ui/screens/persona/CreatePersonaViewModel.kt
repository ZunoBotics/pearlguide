package com.zunobotics.okellonexus.ui.screens.persona

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zunobotics.okellonexus.data.db.entity.PersonaEntity
import com.zunobotics.okellonexus.data.repository.PersonaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

@HiltViewModel
class CreatePersonaViewModel @Inject constructor(private val repo: PersonaRepository) : ViewModel() {

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    var editingId = ""
    val name = MutableStateFlow("")
    val role = MutableStateFlow("Museum Guide")
    val greeting = MutableStateFlow("")
    val personality = MutableStateFlow(setOf<String>())
    val voiceSpeed = MutableStateFlow(1.0f)
    val extraInstructions = MutableStateFlow("")
    val languageCode = MutableStateFlow("en")

    fun loadPersona(id: String) {
        if (id.isEmpty()) return
        editingId = id
        viewModelScope.launch {
            _isLoading.value = true
            val p = repo.getById(id)
            if (p != null) {
                name.value = p.name
                role.value = p.role
                greeting.value = p.greeting
                try {
                    personality.value = Json.decodeFromString<List<String>>(p.personality).toSet()
                } catch (_: Exception) {}
                voiceSpeed.value = p.voiceSpeed
                extraInstructions.value = p.extraInstructions
                languageCode.value = p.languageCode
            }
            _isLoading.value = false
        }
    }

    fun save() = viewModelScope.launch {
        val entity = PersonaEntity(
            id = editingId,
            name = name.value.trim(),
            role = role.value,
            greeting = greeting.value.trim(),
            personality = Json.encodeToString(personality.value.toList()),
            voiceSpeed = voiceSpeed.value,
            extraInstructions = extraInstructions.value.trim(),
            languageCode = languageCode.value
        )
        repo.save(entity)
        _saved.value = true
    }
}
