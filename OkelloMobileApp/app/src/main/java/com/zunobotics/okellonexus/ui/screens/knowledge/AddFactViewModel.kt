package com.zunobotics.okellonexus.ui.screens.knowledge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zunobotics.okellonexus.data.db.entity.KnowledgeEntry
import com.zunobotics.okellonexus.data.db.entity.PersonaEntity
import com.zunobotics.okellonexus.data.repository.KnowledgeRepository
import com.zunobotics.okellonexus.data.repository.PersonaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AddFactViewModel @Inject constructor(
    private val repo: KnowledgeRepository,
    private val personaRepo: PersonaRepository
) : ViewModel() {

    val personas: StateFlow<List<PersonaEntity>> = personaRepo.personas
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val personaId = MutableStateFlow("")
    val title = MutableStateFlow("")
    val content = MutableStateFlow("")
    val category = MutableStateFlow("General")
    val tags = MutableStateFlow("")

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    fun initPersona(id: String) {
        if (personaId.value.isEmpty()) personaId.value = id
    }

    fun save() = viewModelScope.launch {
        val entry = KnowledgeEntry(
            id = "",
            personaId = personaId.value,
            title = title.value.trim(),
            content = content.value.trim(),
            category = category.value.trim(),
            tags = tags.value.trim().let { t ->
                if (t.isEmpty()) "[]"
                else "[${t.split(",").map { "\"${it.trim()}\"" }.joinToString(",")}]"
            }
        )
        repo.add(entry)
        _saved.value = true
    }
}
