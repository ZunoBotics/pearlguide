package com.zunobotics.okellonexus.ui.screens.location

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zunobotics.okellonexus.ui.components.NexusTopBar
import com.zunobotics.okellonexus.ui.theme.*

private val LOCATION_TYPES = listOf(
    "Museum",
    "Shopping Mall",
    "Hotel",
    "Hospital",
    "Airport",
    "Office Building",
    "Exhibition Hall",
    "Conference Centre",
    "University",
    "Other"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateLocationScreen(
    locationId: String,
    personaId: String = "",
    onBack: () -> Unit,
    viewModel: CreateLocationViewModel = hiltViewModel()
) {
    val saved by viewModel.saved.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    val name by viewModel.name.collectAsState()
    val type by viewModel.type.collectAsState()
    val description by viewModel.description.collectAsState()
    val address by viewModel.address.collectAsState()
    val gpsLat by viewModel.gpsLat.collectAsState()
    val gpsLng by viewModel.gpsLng.collectAsState()
    val openingHours by viewModel.openingHours.collectAsState()
    val notes by viewModel.notes.collectAsState()
    val specialInstructions by viewModel.specialInstructions.collectAsState()

    var typeExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(locationId) {
        viewModel.loadLocation(locationId, personaId)
    }

    LaunchedEffect(saved) {
        if (saved) onBack()
    }

    val isEdit = locationId.isNotEmpty()

    Scaffold(
        topBar = {
            NexusTopBar(
                title = if (isEdit) "Edit Location" else "New Location",
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

                // Name
                OutlinedTextField(
                    value = name,
                    onValueChange = { viewModel.name.value = it },
                    label = { Text("Location Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Type dropdown
                ExposedDropdownMenuBox(
                    expanded = typeExpanded,
                    onExpandedChange = { typeExpanded = it }
                ) {
                    OutlinedTextField(
                        value = type,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Location Type") },
                        trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = typeExpanded,
                        onDismissRequest = { typeExpanded = false }
                    ) {
                        LOCATION_TYPES.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    viewModel.type.value = option
                                    typeExpanded = false
                                }
                            )
                        }
                    }
                }

                // Description
                OutlinedTextField(
                    value = description,
                    onValueChange = { viewModel.description.value = it },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4
                )

                // Address
                OutlinedTextField(
                    value = address,
                    onValueChange = { viewModel.address.value = it },
                    label = { Text("Address") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 1,
                    maxLines = 3
                )

                // GPS Coordinates
                Text("GPS Coordinates (optional)", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = gpsLat,
                        onValueChange = { viewModel.gpsLat.value = it },
                        label = { Text("Latitude") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = gpsLat.isNotEmpty() && gpsLat.toDoubleOrNull() == null
                    )
                    OutlinedTextField(
                        value = gpsLng,
                        onValueChange = { viewModel.gpsLng.value = it },
                        label = { Text("Longitude") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = gpsLng.isNotEmpty() && gpsLng.toDoubleOrNull() == null
                    )
                }

                // Opening Hours
                OutlinedTextField(
                    value = openingHours,
                    onValueChange = { viewModel.openingHours.value = it },
                    label = { Text("Opening Hours") },
                    placeholder = { Text("e.g. Mon–Fri 9am–5pm") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Notes
                OutlinedTextField(
                    value = notes,
                    onValueChange = { viewModel.notes.value = it },
                    label = { Text("Notes") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4
                )

                // Special Instructions
                OutlinedTextField(
                    value = specialInstructions,
                    onValueChange = { viewModel.specialInstructions.value = it },
                    label = { Text("Special Instructions") },
                    placeholder = { Text("Robot behaviour instructions for this location…") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 5
                )

                // Save Button
                Button(
                    onClick = { viewModel.save() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                    enabled = name.isNotBlank()
                ) {
                    Text(if (isEdit) "Update Location" else "Save Location")
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
