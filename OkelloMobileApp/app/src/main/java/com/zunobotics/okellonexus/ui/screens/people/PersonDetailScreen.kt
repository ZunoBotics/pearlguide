package com.zunobotics.okellonexus.ui.screens.people

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zunobotics.okellonexus.data.db.entity.FaceProfile
import com.zunobotics.okellonexus.data.repository.FaceProfileRepository
import com.zunobotics.okellonexus.ui.components.ConfirmDialog
import com.zunobotics.okellonexus.ui.components.NexusTopBar
import com.zunobotics.okellonexus.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class PersonDetailViewModel @Inject constructor(
    private val repo: FaceProfileRepository
) : ViewModel() {
    private val _profile = MutableStateFlow<FaceProfile?>(null)
    val profile: StateFlow<FaceProfile?> = _profile.asStateFlow()

    fun load(id: String) = viewModelScope.launch {
        _profile.value = repo.getById(id)
    }

    fun assignVip(id: String) = viewModelScope.launch {
        repo.assignVip(id)
        _profile.value = _profile.value?.copy(isCurrentVip = true)
    }

    fun clearVip() = viewModelScope.launch {
        repo.clearVip()
        _profile.value = _profile.value?.copy(isCurrentVip = false)
    }

    fun delete(profile: FaceProfile, onDone: () -> Unit) = viewModelScope.launch {
        repo.delete(profile)
        onDone()
    }
}

@Composable
fun PersonDetailScreen(
    personId: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    viewModel: PersonDetailViewModel = hiltViewModel()
) {
    val profile by viewModel.profile.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(personId) { viewModel.load(personId) }

    if (showDeleteDialog) {
        profile?.let { p ->
            ConfirmDialog(
                title = "Delete Face Data",
                message = "Remove all data for \"${p.name}\"? This cannot be undone.",
                onConfirm = { viewModel.delete(p) { onBack() }; showDeleteDialog = false },
                onDismiss = { showDeleteDialog = false }
            )
        }
    }

    Scaffold(
        topBar = {
            NexusTopBar(
                title = "Person Profile",
                showBack = true,
                onBack = onBack,
                actions = {
                    profile?.let { p ->
                        IconButton(onClick = { onEdit(p.id) }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit")
                        }
                    }
                }
            )
        },
        containerColor = BackgroundWhite
    ) { padding ->
        profile?.let { p ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(Modifier.height(8.dp))

                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    PersonAvatar(photoBase64 = p.photoBase64, name = p.name, size = 80)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(p.name, style = MaterialTheme.typography.headlineSmall, color = TextPrimary, fontWeight = FontWeight.Bold)
                        RoleChip(p.roleTag)
                        if (p.isCurrentVip) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Default.Star, contentDescription = null, tint = AmberGold, modifier = Modifier.size(16.dp))
                                Text("Assigned VIP Guide", style = MaterialTheme.typography.bodySmall, color = AmberGold, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }

                // Stats
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(modifier = Modifier.weight(1f), label = "Interactions", value = "${p.totalInteractions}")
                    StatCard(modifier = Modifier.weight(1f), label = "Enrolled", value = formatDate(p.enrolledAt))
                }

                if (p.lastSeenAt != null) {
                    StatCard(modifier = Modifier.fillMaxWidth(), label = "Last Seen", value = formatDate(p.lastSeenAt))
                }

                if (p.notes.isNotEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                        shape = RoundedCornerShape(12.dp),
                        elevation = CardDefaults.cardElevation(1.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Notes", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                            Text(p.notes, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                        }
                    }
                }

                // Interaction history placeholder
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                    shape = RoundedCornerShape(12.dp),
                    elevation = CardDefaults.cardElevation(1.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Interaction History", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                        Text(
                            "Detailed session history will appear here once face recognition is active on the Quest 3.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }

                // VIP assignment
                if (!p.isCurrentVip) {
                    Button(
                        onClick = { viewModel.assignVip(p.id) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AmberGold)
                    ) {
                        Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Assign as Current VIP Guide")
                    }
                } else {
                    OutlinedButton(
                        onClick = { viewModel.clearVip() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.StarBorder, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Remove VIP Assignment")
                    }
                }

                // Delete
                OutlinedButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed)
                ) {
                    Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Delete Face Data")
                }

                Spacer(Modifier.height(16.dp))
            }
        } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = ElectricBlue)
        }
    }
}

@Composable
private fun RowScope.StatCard(modifier: Modifier, label: String, value: String) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        shape = RoundedCornerShape(10.dp),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun StatCard(modifier: Modifier, label: String, value: String) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        shape = RoundedCornerShape(10.dp),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, fontWeight = FontWeight.Medium)
        }
    }
}

private fun formatDate(ts: Long): String =
    SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(ts))
