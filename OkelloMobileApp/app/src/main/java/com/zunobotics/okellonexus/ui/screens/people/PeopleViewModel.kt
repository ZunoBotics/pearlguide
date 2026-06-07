package com.zunobotics.okellonexus.ui.screens.people

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zunobotics.okellonexus.data.db.entity.FaceProfile
import com.zunobotics.okellonexus.data.repository.FaceProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class PeopleViewModel @Inject constructor(
    private val repo: FaceProfileRepository
) : ViewModel() {

    val profiles: StateFlow<List<FaceProfile>> = repo.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _lastImportCount = MutableStateFlow<Int?>(null)
    val lastImportCount: StateFlow<Int?> = _lastImportCount.asStateFlow()

    fun delete(profile: FaceProfile) = viewModelScope.launch { repo.delete(profile) }

    fun assignVip(id: String) = viewModelScope.launch { repo.assignVip(id) }

    fun clearVip() = viewModelScope.launch { repo.clearVip() }

    fun importFromCsv(lines: List<String>) = viewModelScope.launch {
        var count = 0
        val dataLines = if (lines.firstOrNull()?.trimStart()?.lowercase()?.startsWith("name") == true)
            lines.drop(1) else lines
        for (line in dataLines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val cols = trimmed.split(",", limit = 4)
            val name = cols.getOrElse(0) { "" }.trim()
            if (name.isEmpty()) continue
            val roleTag = cols.getOrElse(1) { "" }.trim().ifBlank { "Guest" }
            val notes = cols.getOrElse(2) { "" }.trim()
            val photoBase64 = cols.getOrElse(3) { "" }.trim()
            val profile = FaceProfile(
                id = UUID.randomUUID().toString(),
                name = name,
                roleTag = roleTag,
                notes = notes,
                photoBase64 = photoBase64
            )
            repo.save(profile)
            count++
        }
        _lastImportCount.value = count
    }

    fun clearImportCount() { _lastImportCount.value = null }
}
