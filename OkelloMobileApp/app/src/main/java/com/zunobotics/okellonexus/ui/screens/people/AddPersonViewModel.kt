package com.zunobotics.okellonexus.ui.screens.people

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zunobotics.okellonexus.data.db.entity.FaceProfile
import com.zunobotics.okellonexus.data.repository.FaceProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class AddPersonViewModel @Inject constructor(
    private val repo: FaceProfileRepository
) : ViewModel() {

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    var editingId = ""
    val name = MutableStateFlow("")
    val roleTag = MutableStateFlow("Guest")
    val notes = MutableStateFlow("")
    val photoBase64 = MutableStateFlow("")

    fun load(id: String) {
        if (id.isEmpty()) return
        editingId = id
        viewModelScope.launch {
            repo.getById(id)?.let { p ->
                name.value = p.name
                roleTag.value = p.roleTag
                notes.value = p.notes
                photoBase64.value = p.photoBase64
            }
        }
    }

    fun save() = viewModelScope.launch {
        val profile = FaceProfile(
            id = editingId.ifEmpty { UUID.randomUUID().toString() },
            name = name.value.trim(),
            roleTag = roleTag.value,
            notes = notes.value.trim(),
            photoBase64 = photoBase64.value
        )
        repo.save(profile)
        _saved.value = true
    }
}
