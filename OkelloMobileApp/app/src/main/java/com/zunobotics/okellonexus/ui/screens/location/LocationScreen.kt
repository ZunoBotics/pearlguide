package com.zunobotics.okellonexus.ui.screens.location

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zunobotics.okellonexus.data.db.entity.LocationEntity
import com.zunobotics.okellonexus.ui.components.ConfirmDialog
import com.zunobotics.okellonexus.ui.components.EmptyState
import com.zunobotics.okellonexus.ui.components.NexusTopBar
import com.zunobotics.okellonexus.ui.theme.*

@Composable
fun LocationScreen(
    onBack: () -> Unit,
    onCreateLocation: (personaId: String) -> Unit,
    onEditLocation: (String) -> Unit,
    viewModel: LocationViewModel = hiltViewModel()
) {
    val locations by viewModel.locations.collectAsState()
    val personas by viewModel.personas.collectAsState()
    val selectedPersonaId by viewModel.selectedPersonaId.collectAsState()
    var deleteTarget by remember { mutableStateOf<LocationEntity?>(null) }

    deleteTarget?.let { target ->
        ConfirmDialog(
            title = "Delete Location",
            message = "Delete \"${target.name}\"? This cannot be undone.",
            onConfirm = { viewModel.delete(target); deleteTarget = null },
            onDismiss = { deleteTarget = null }
        )
    }

    Scaffold(
        topBar = { NexusTopBar(title = "Locations", showBack = true, onBack = onBack) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { onCreateLocation(selectedPersonaId ?: "") },
                containerColor = ElectricBlue
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Location", tint = SurfaceWhite)
            }
        },
        containerColor = BackgroundWhite
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Identity / persona filter chips
            if (personas.isNotEmpty()) {
                androidx.compose.foundation.lazy.LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = selectedPersonaId == null,
                            onClick = { viewModel.selectPersona(null) },
                            label = { Text("All identities") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ElectricBlue,
                                selectedLabelColor = SurfaceWhite
                            )
                        )
                    }
                    items(personas) { persona ->
                        FilterChip(
                            selected = selectedPersonaId == persona.id,
                            onClick = { viewModel.selectPersona(persona.id) },
                            label = { Text(persona.name) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ElectricBlue,
                                selectedLabelColor = SurfaceWhite
                            )
                        )
                    }
                }
            }

            if (locations.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize()) {
                EmptyState(
                    icon = Icons.Default.LocationOn,
                    message = "No locations yet.\nAdd one to get started.",
                    actionLabel = "Add Location",
                    onAction = { onCreateLocation(selectedPersonaId ?: "") },
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    items(locations, key = { it.id }) { location ->
                        LocationCard(
                            location = location,
                            onActivate = { viewModel.activate(location) },
                            onEdit = { onEditLocation(location.id) },
                            onDelete = { deleteTarget = location }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LocationCard(
    location: LocationEntity,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (location.isActive) ElectricBlue.copy(alpha = 0.07f) else SurfaceWhite
        ),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(if (location.isActive) 0.dp else 2.dp),
        border = if (location.isActive) BorderStroke(2.dp, ElectricBlue) else null
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
                    Text(location.name, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    if (location.isActive) {
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
                Text(location.type, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                if (location.address.isNotEmpty()) {
                    Text(location.address, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
                if (location.gpsLat != 0.0 && location.gpsLng != 0.0) {
                    Text(
                        "%.4f, %.4f".format(location.gpsLat, location.gpsLng),
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricBlue
                    )
                }
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Options")
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    if (!location.isActive) {
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
