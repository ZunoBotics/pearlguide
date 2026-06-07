package com.zunobotics.okellonexus.ui.screens.knowledge

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zunobotics.okellonexus.data.db.entity.KnowledgeEntry
import com.zunobotics.okellonexus.ui.components.ConfirmDialog
import com.zunobotics.okellonexus.ui.components.EmptyState
import com.zunobotics.okellonexus.ui.components.NexusTopBar
import com.zunobotics.okellonexus.ui.theme.*

@Composable
fun KnowledgeScreen(
    onBack: () -> Unit,
    onAddFact: (personaId: String) -> Unit,
    viewModel: KnowledgeViewModel = hiltViewModel()
) {
    val filteredEntries by viewModel.filteredEntries.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val personas by viewModel.personas.collectAsState()
    val selectedPersonaId by viewModel.selectedPersonaId.collectAsState()
    var deleteTarget by remember { mutableStateOf<KnowledgeEntry?>(null) }

    deleteTarget?.let { target ->
        ConfirmDialog(
            title = "Delete Fact",
            message = "Delete \"${target.title}\"? This cannot be undone.",
            onConfirm = { viewModel.delete(target); deleteTarget = null },
            onDismiss = { deleteTarget = null }
        )
    }

    Scaffold(
        topBar = { NexusTopBar(title = "Knowledge Base", showBack = true, onBack = onBack) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { onAddFact(selectedPersonaId ?: "") },
                containerColor = ElectricBlue
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Fact", tint = SurfaceWhite)
            }
        },
        containerColor = BackgroundWhite
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Identity / persona filter chips
            if (personas.isNotEmpty()) {
                LazyRow(
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

            // Category filter chips
            if (categories.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = selectedCategory == null,
                            onClick = { viewModel.selectCategory(null) },
                            label = { Text("All") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ElectricBlue,
                                selectedLabelColor = SurfaceWhite
                            )
                        )
                    }
                    items(categories) { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { viewModel.selectCategory(cat) },
                            label = { Text(cat) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ElectricBlue,
                                selectedLabelColor = SurfaceWhite
                            )
                        )
                    }
                }
            }

            if (filteredEntries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize()) {
                    EmptyState(
                        icon = Icons.Default.MenuBook,
                        message = if (selectedCategory != null)
                            "No facts in \"$selectedCategory\"."
                        else
                            "No knowledge entries yet.\nAdd facts the robot should know.",
                        actionLabel = if (selectedCategory == null) "Add Fact" else null,
                        onAction = if (selectedCategory == null) { { onAddFact(selectedPersonaId ?: "") } } else null,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(filteredEntries, key = { it.id }) { entry ->
                        KnowledgeCard(
                            entry = entry,
                            onDelete = { deleteTarget = entry }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KnowledgeCard(
    entry: KnowledgeEntry,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(1.dp),
        onClick = { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(entry.title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Surface(
                            color = ElectricBlue.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                entry.category,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = ElectricBlue
                            )
                        }
                        if (entry.tags.isNotEmpty() && entry.tags != "[]") {
                            val tagList = try {
                                kotlinx.serialization.json.Json.decodeFromString<List<String>>(entry.tags)
                            } catch (_: Exception) {
                                entry.tags.removeSurrounding("[", "]").split(",").map { it.trim().removeSurrounding("\"") }
                            }
                            tagList.take(3).forEach { tag ->
                                val t = tag.trim()
                                if (t.isNotEmpty()) {
                                    Surface(
                                        color = SurfaceElevated,
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            t,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Options")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Delete", color = ErrorRed) },
                            onClick = { onDelete(); showMenu = false },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = ErrorRed) }
                        )
                    }
                }
            }

            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Divider(color = BorderColor)
                Spacer(Modifier.height(8.dp))
                entry.imageBase64?.let { b64 ->
                    val bitmap = remember(b64) {
                        runCatching {
                            val bytes = Base64.decode(b64, Base64.DEFAULT)
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                        }.getOrNull()
                    }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = "Snapshot",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .padding(bottom = 8.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
                Text(
                    entry.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            } else {
                Spacer(Modifier.height(4.dp))
                Text(
                    entry.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
    }
}
