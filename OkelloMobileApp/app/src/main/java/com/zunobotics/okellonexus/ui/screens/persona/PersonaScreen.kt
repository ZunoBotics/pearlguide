package com.zunobotics.okellonexus.ui.screens.persona

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zunobotics.okellonexus.data.db.entity.PersonaEntity
import com.zunobotics.okellonexus.ui.components.ConfirmDialog
import com.zunobotics.okellonexus.ui.components.EmptyState
import com.zunobotics.okellonexus.ui.components.NexusTopBar
import com.zunobotics.okellonexus.ui.theme.*

@Composable
fun PersonaScreen(
    onBack: () -> Unit,
    onCreatePersona: () -> Unit,
    onEditPersona: (String) -> Unit,
    viewModel: PersonaViewModel = hiltViewModel()
) {
    val personas by viewModel.personas.collectAsState()
    var deleteTarget by remember { mutableStateOf<PersonaEntity?>(null) }

    deleteTarget?.let { target ->
        ConfirmDialog(
            title = "Delete Persona",
            message = "Delete \"${target.name}\"? This cannot be undone.",
            onConfirm = { viewModel.delete(target); deleteTarget = null },
            onDismiss = { deleteTarget = null }
        )
    }

    Scaffold(
        topBar = { NexusTopBar(title = "Personas", showBack = true, onBack = onBack) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreatePersona,
                containerColor = ElectricBlue
            ) {
                Icon(Icons.Default.Add, contentDescription = "Create Persona", tint = SurfaceWhite)
            }
        },
        containerColor = BackgroundWhite
    ) { padding ->
        if (personas.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    icon = Icons.Default.Person,
                    message = "No personas yet.\nCreate one to get started.",
                    actionLabel = "Create Persona",
                    onAction = onCreatePersona,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                items(personas, key = { it.id }) { persona ->
                    PersonaCard(
                        persona = persona,
                        onActivate = { viewModel.activate(persona) },
                        onEdit = { onEditPersona(persona.id) },
                        onDelete = { deleteTarget = persona }
                    )
                }
            }
        }
    }
}

@Composable
private fun PersonaCard(
    persona: PersonaEntity,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (persona.isActive) ElectricBlue.copy(alpha = 0.07f) else SurfaceWhite
        ),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(if (persona.isActive) 0.dp else 2.dp),
        border = if (persona.isActive) BorderStroke(2.dp, ElectricBlue) else null
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(persona.name, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    if (persona.isActive) {
                        Surface(color = SuccessTeal, shape = RoundedCornerShape(6.dp)) {
                            Text(
                                "ACTIVE",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = SurfaceWhite
                            )
                        }
                    }
                }
                Text(persona.role, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                Text(persona.languageCode.uppercase(), style = MaterialTheme.typography.labelSmall, color = ElectricBlue)
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Options")
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    if (!persona.isActive) {
                        DropdownMenuItem(
                            text = { Text("Activate") },
                            onClick = { onActivate(); showMenu = false },
                            leadingIcon = { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SuccessTeal) }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = { onEdit(); showMenu = false },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = ErrorRed) },
                        onClick = { onDelete(); showMenu = false },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = ErrorRed) }
                    )
                }
            }
        }
    }
}
