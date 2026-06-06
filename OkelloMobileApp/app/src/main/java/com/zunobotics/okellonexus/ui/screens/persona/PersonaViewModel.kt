package com.zunobotics.okellonexus.ui.screens.persona

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zunobotics.okellonexus.data.db.entity.PersonaEntity
import com.zunobotics.okellonexus.data.repository.PersonaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PersonaViewModel @Inject constructor(
    private val repo: PersonaRepository
) : ViewModel() {

    val personas: StateFlow<List<PersonaEntity>> = repo.personas
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun activate(persona: PersonaEntity) = viewModelScope.launch { repo.activate(persona) }
    fun delete(persona: PersonaEntity) = viewModelScope.launch { repo.delete(persona) }
}
