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
import java.util.UUID
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

    private val _lastImportCount = MutableStateFlow<Int?>(null)
    val lastImportCount: StateFlow<Int?> = _lastImportCount.asStateFlow()

    // CSV format: title,category,tags,content
    // Tags are semicolon-separated within the CSV cell. Content is last so commas inside it are safe.
    fun importFromCsv(lines: List<String>) = viewModelScope.launch {
        var count = 0
        val dataLines = if (lines.firstOrNull()?.trimStart()?.lowercase()?.startsWith("title") == true)
            lines.drop(1) else lines
        for (line in dataLines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val cols = trimmed.split(",", limit = 4)
            val title = cols.getOrElse(0) { "" }.trim()
            if (title.isEmpty()) continue
            val category = cols.getOrElse(1) { "" }.trim().ifBlank { "General" }
            val tagStr = cols.getOrElse(2) { "" }.trim()
            val content = cols.getOrElse(3) { "" }.trim()
            if (content.isEmpty()) continue
            val tagsJson = if (tagStr.isEmpty()) "[]"
            else "[${tagStr.split(";").map { "\"${it.trim()}\"" }.joinToString(",")}]"
            val entry = KnowledgeEntry(
                id = UUID.randomUUID().toString(),
                personaId = _selectedPersonaId.value ?: "",
                title = title,
                content = content,
                category = category,
                tags = tagsJson,
                source = "document"
            )
            repo.add(entry)
            count++
        }
        _lastImportCount.value = count
    }

    fun clearImportCount() { _lastImportCount.value = null }
}
