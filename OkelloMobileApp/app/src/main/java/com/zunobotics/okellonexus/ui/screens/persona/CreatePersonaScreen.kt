package com.zunobotics.okellonexus.ui.screens.persona

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zunobotics.okellonexus.ui.components.NexusTopBar
import com.zunobotics.okellonexus.ui.theme.*

private val ROLE_OPTIONS = listOf(
    "Museum Guide",
    "Marketing Agent",
    "Receptionist",
    "Tour Guide",
    "Event Host",
    "Custom"
)

private val PERSONALITY_TRAITS = listOf(
    "Friendly", "Professional", "Formal", "Energetic", "Calm", "Humorous"
)

private val LANGUAGE_OPTIONS = listOf(
    "en" to "English",
    "sw" to "Swahili",
    "fr" to "French",
    "ar" to "Arabic"
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CreatePersonaScreen(
    personaId: String,
    onBack: () -> Unit,
    viewModel: CreatePersonaViewModel = hiltViewModel()
) {
    val saved by viewModel.saved.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    val name by viewModel.name.collectAsState()
    val role by viewModel.role.collectAsState()
    val greeting by viewModel.greeting.collectAsState()
    val personality by viewModel.personality.collectAsState()
    val voiceSpeed by viewModel.voiceSpeed.collectAsState()
    val extraInstructions by viewModel.extraInstructions.collectAsState()
    val languageCode by viewModel.languageCode.collectAsState()

    var roleExpanded by remember { mutableStateOf(false) }
    var languageExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(personaId) {
        viewModel.loadPersona(personaId)
    }

    LaunchedEffect(saved) {
        if (saved) onBack()
    }

    val isEdit = personaId.isNotEmpty()
    val title = if (isEdit) "Edit Persona" else "New Persona"

    Scaffold(
        topBar = {
            NexusTopBar(
                title = title,
                showBack = true,
                onBack = onBack
            )
        },
        containerColor = BackgroundWhite
    ) { padding ->
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = ElectricBlue)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(Modifier.height(8.dp))

                // Robot Name
                OutlinedTextField(
                    value = name,
                    onValueChange = { viewModel.name.value = it },
                    label = { Text("Robot Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Role Dropdown
                ExposedDropdownMenuBox(
                    expanded = roleExpanded,
                    onExpandedChange = { roleExpanded = it }
                ) {
                    OutlinedTextField(
                        value = role,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Role") },
                        trailingIcon = {
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = roleExpanded,
                        onDismissRequest = { roleExpanded = false }
                    ) {
                        ROLE_OPTIONS.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    viewModel.role.value = option
                                    roleExpanded = false
                                }
                            )
                        }
                    }
                }

                // Greeting
                OutlinedTextField(
                    value = greeting,
                    onValueChange = { viewModel.greeting.value = it },
                    label = { Text("Greeting") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4
                )

                // Personality Traits
                Text(
                    "Personality Traits",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PERSONALITY_TRAITS.forEach { trait ->
                        val selected = trait in personality
                        FilterChip(
                            selected = selected,
                            onClick = {
                                viewModel.personality.value = if (selected) {
                                    personality - trait
                                } else {
                                    personality + trait
                                }
                            },
                            label = { Text(trait) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ElectricBlue,
                                selectedLabelColor = SurfaceWhite
                            )
                        )
                    }
                }

                // Voice Speed
                Column {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Voice Speed", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                        Text(
                            String.format("%.1fx", voiceSpeed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ElectricBlue
                        )
                    }
                    Slider(
                        value = voiceSpeed,
                        onValueChange = { viewModel.voiceSpeed.value = it },
                        valueRange = 0.5f..2.0f,
                        steps = 14,
                        colors = SliderDefaults.colors(thumbColor = ElectricBlue, activeTrackColor = ElectricBlue)
                    )
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("0.5x", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        Text("2.0x", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    }
                }

                // Extra Instructions
                OutlinedTextField(
                    value = extraInstructions,
                    onValueChange = { viewModel.extraInstructions.value = it },
                    label = { Text("Extra Instructions") },
                    placeholder = { Text("Additional behaviour instructions for the AI…") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6
                )

                // Language Dropdown
                ExposedDropdownMenuBox(
                    expanded = languageExpanded,
                    onExpandedChange = { languageExpanded = it }
                ) {
                    OutlinedTextField(
                        value = LANGUAGE_OPTIONS.find { it.first == languageCode }?.second ?: languageCode,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Language") },
                        trailingIcon = {
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = languageExpanded,
                        onDismissRequest = { languageExpanded = false }
                    ) {
                        LANGUAGE_OPTIONS.forEach { (code, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    viewModel.languageCode.value = code
                                    languageExpanded = false
                                }
                            )
                        }
                    }
                }

                // Save Button
                Button(
                    onClick = { viewModel.save() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                    enabled = name.isNotBlank()
                ) {
                    Text(if (isEdit) "Update Persona" else "Save Persona")
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
