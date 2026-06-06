package com.zunobotics.okellonexus.ui.screens.knowledge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zunobotics.okellonexus.data.db.entity.KnowledgeEntry
import com.zunobotics.okellonexus.data.db.entity.PersonaEntity
import com.zunobotics.okellonexus.data.repository.KnowledgeRepository
import com.zunobotics.okellonexus.data.repository.PersonaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class KnowledgeViewModel @Inject constructor(
    private val repo: KnowledgeRepository,
    private val personaRepo: PersonaRepository
) : ViewModel() {

    val personas: StateFlow<List<PersonaEntity>> = personaRepo.personas
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedPersonaId = MutableStateFlow<String?>(null)
    val selectedPersonaId: StateFlow<String?> = _selectedPersonaId.asStateFlow()

    private val _selectedCategory = MutableStateFlow<String?>(null)
    val selectedCategory: StateFlow<String?> = _selectedCategory.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val allEntries: StateFlow<List<KnowledgeEntry>> = _selectedPersonaId
        .flatMapLatest { pid ->
            if (pid == null) repo.entries else repo.entriesForPersona(pid)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val categories: StateFlow<List<String>> = allEntries
        .map { entries -> entries.map { it.category }.distinct().sorted() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredEntries: StateFlow<List<KnowledgeEntry>> = combine(allEntries, selectedCategory) { entries, cat ->
        if (cat == null) entries else entries.filter { it.category == cat }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun selectPersona(personaId: String?) {
        _selectedPersonaId.value = if (_selectedPersonaId.value == personaId) null else personaId
        _selectedCategory.value = null
    }

    fun selectCategory(category: String?) {
        _selectedCategory.value = if (_selectedCategory.value == category) null else category
    }

    fun delete(entry: KnowledgeEntry) = viewModelScope.launch {
        repo.delete(entry.id)
    }
}
