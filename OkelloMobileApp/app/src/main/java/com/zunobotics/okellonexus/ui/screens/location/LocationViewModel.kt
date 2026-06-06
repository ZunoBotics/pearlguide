package com.zunobotics.okellonexus.ui.screens.location

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zunobotics.okellonexus.data.db.entity.LocationEntity
import com.zunobotics.okellonexus.data.db.entity.PersonaEntity
import com.zunobotics.okellonexus.data.repository.LocationRepository
import com.zunobotics.okellonexus.data.repository.PersonaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LocationViewModel @Inject constructor(
    private val repo: LocationRepository,
    private val personaRepo: PersonaRepository
) : ViewModel() {

    val personas: StateFlow<List<PersonaEntity>> = personaRepo.personas
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedPersonaId = MutableStateFlow<String?>(null)
    val selectedPersonaId: StateFlow<String?> = _selectedPersonaId.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val locations: StateFlow<List<LocationEntity>> = _selectedPersonaId
        .flatMapLatest { pid ->
            if (pid == null) repo.locations else repo.locationsForPersona(pid)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun selectPersona(personaId: String?) {
        _selectedPersonaId.value = if (_selectedPersonaId.value == personaId) null else personaId
    }

    fun activate(location: LocationEntity) = viewModelScope.launch { repo.activate(location) }
    fun delete(location: LocationEntity) = viewModelScope.launch { repo.delete(location) }
}
