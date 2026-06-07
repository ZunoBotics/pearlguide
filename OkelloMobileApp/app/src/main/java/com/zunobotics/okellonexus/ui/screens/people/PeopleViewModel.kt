package com.zunobotics.okellonexus.ui.screens.people

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zunobotics.okellonexus.data.db.entity.FaceProfile
import com.zunobotics.okellonexus.data.repository.FaceProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PeopleViewModel @Inject constructor(
    private val repo: FaceProfileRepository
) : ViewModel() {

    val profiles: StateFlow<List<FaceProfile>> = repo.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(profile: FaceProfile) = viewModelScope.launch { repo.delete(profile) }

    fun assignVip(id: String) = viewModelScope.launch { repo.assignVip(id) }

    fun clearVip() = viewModelScope.launch { repo.clearVip() }
}
