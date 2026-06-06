package com.zunobotics.okellonexus.ui.screens.knowledge

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zunobotics.okellonexus.ui.components.NexusTopBar
import com.zunobotics.okellonexus.ui.theme.*

private val KNOWLEDGE_CATEGORIES = listOf(
    "General",
    "History",
    "Exhibits",
    "Services",
    "FAQ",
    "Products",
    "Events",
    "Directions",
    "Safety",
    "Other"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFactScreen(
    personaId: String,
    onBack: () -> Unit,
    viewModel: AddFactViewModel = hiltViewModel()
) {
    val saved by viewModel.saved.collectAsState()
    val title by viewModel.title.collectAsState()
    val content by viewModel.content.collectAsState()
    val category by viewModel.category.collectAsState()
    val tags by viewModel.tags.collectAsState()
    val personas by viewModel.personas.collectAsState()
    val selectedPersonaId by viewModel.personaId.collectAsState()

    var categoryExpanded by remember { mutableStateOf(false) }
    var personaExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(personaId) { viewModel.initPersona(personaId) }
    LaunchedEffect(saved) { if (saved) onBack() }

    Scaffold(
        topBar = { NexusTopBar(title = "Add Fact", showBack = true, onBack = onBack) },
        containerColor = BackgroundWhite
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // Identity picker
            val selectedPersonaName = personas.firstOrNull { it.id == selectedPersonaId }?.name ?: "Select identity"
            ExposedDropdownMenuBox(
                expanded = personaExpanded,
                onExpandedChange = { personaExpanded = it }
            ) {
                OutlinedTextField(
                    value = selectedPersonaName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Identity") },
                    trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                    isError = selectedPersonaId.isEmpty()
                )
                ExposedDropdownMenu(expanded = personaExpanded, onDismissRequest = { personaExpanded = false }) {
                    personas.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p.name) },
                            onClick = { viewModel.personaId.value = p.id; personaExpanded = false }
                        )
                    }
                }
            }
            if (selectedPersonaId.isEmpty()) {
                Text("Select an identity for this fact", style = MaterialTheme.typography.bodySmall, color = androidx.compose.ui.graphics.Color(0xFFB00020))
            }

            // Title
            OutlinedTextField(
                value = title,
                onValueChange = { viewModel.title.value = it },
                label = { Text("Title") },
                placeholder = { Text("e.g. Museum opening hours") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // Content
            OutlinedTextField(
                value = content,
                onValueChange = { viewModel.content.value = it },
                label = { Text("Content") },
                placeholder = { Text("Enter the fact or knowledge the robot should know…") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                maxLines = 10
            )

            // Category Dropdown
            ExposedDropdownMenuBox(
                expanded = categoryExpanded,
                onExpandedChange = { categoryExpanded = it }
            ) {
                OutlinedTextField(
                    value = category,
                    onValueChange = { viewModel.category.value = it },
                    label = { Text("Category") },
                    trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = categoryExpanded,
                    onDismissRequest = { categoryExpanded = false }
                ) {
                    KNOWLEDGE_CATEGORIES.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                viewModel.category.value = option
                                categoryExpanded = false
                            }
                        )
                    }
                }
            }

            // Tags
            OutlinedTextField(
                value = tags,
                onValueChange = { viewModel.tags.value = it },
                label = { Text("Tags (comma-separated)") },
                placeholder = { Text("e.g. hours, schedule, opening") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Text(
                "Tags help the robot find relevant facts more easily",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            // Save Button
            Button(
                onClick = { viewModel.save() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                enabled = title.isNotBlank() && content.isNotBlank() && selectedPersonaId.isNotBlank()
            ) {
                Text("Save Fact")
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
