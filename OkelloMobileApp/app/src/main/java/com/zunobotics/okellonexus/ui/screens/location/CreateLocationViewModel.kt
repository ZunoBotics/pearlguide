package com.zunobotics.okellonexus.ui.screens.location

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zunobotics.okellonexus.data.db.entity.LocationEntity
import com.zunobotics.okellonexus.data.repository.LocationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CreateLocationViewModel @Inject constructor(
    private val repo: LocationRepository
) : ViewModel() {

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    var editingId = ""
    var linkedPersonaId = ""  // set from nav arg before save
    val name = MutableStateFlow("")
    val type = MutableStateFlow("Museum")
    val description = MutableStateFlow("")
    val address = MutableStateFlow("")
    val gpsLat = MutableStateFlow("")
    val gpsLng = MutableStateFlow("")
    val openingHours = MutableStateFlow("")
    val notes = MutableStateFlow("")
    val specialInstructions = MutableStateFlow("")

    fun loadLocation(id: String, incomingPersonaId: String) {
        linkedPersonaId = incomingPersonaId
        if (id.isEmpty()) return
        editingId = id
        viewModelScope.launch {
            _isLoading.value = true
            val loc = repo.getById(id)
            if (loc != null) {
                name.value = loc.name
                type.value = loc.type
                description.value = loc.description
                address.value = loc.address
                gpsLat.value = if (loc.gpsLat != 0.0) loc.gpsLat.toString() else ""
                gpsLng.value = if (loc.gpsLng != 0.0) loc.gpsLng.toString() else ""
                openingHours.value = loc.openingHours
                notes.value = loc.notes
                specialInstructions.value = loc.specialInstructions
                if (linkedPersonaId.isEmpty()) linkedPersonaId = loc.personaId
            }
            _isLoading.value = false
        }
    }

    fun save() = viewModelScope.launch {
        val entity = LocationEntity(
            id = editingId,
            personaId = linkedPersonaId,
            name = name.value.trim(),
            type = type.value,
            description = description.value.trim(),
            address = address.value.trim(),
            gpsLat = gpsLat.value.toDoubleOrNull() ?: 0.0,
            gpsLng = gpsLng.value.toDoubleOrNull() ?: 0.0,
            openingHours = openingHours.value.trim(),
            notes = notes.value.trim(),
            specialInstructions = specialInstructions.value.trim()
        )
        repo.save(entity)
        _saved.value = true
    }
}
