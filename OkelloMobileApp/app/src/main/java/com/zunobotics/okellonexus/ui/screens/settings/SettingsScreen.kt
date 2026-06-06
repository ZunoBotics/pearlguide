package com.zunobotics.okellonexus.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zunobotics.okellonexus.ui.components.NexusTopBar
import com.zunobotics.okellonexus.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val saved by viewModel.saved.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(saved) {
        if (saved) {
            snackbarHostState.showSnackbar("Settings saved", duration = SnackbarDuration.Short)
        }
    }

    Scaffold(
        topBar = { NexusTopBar(title = "Settings", showBack = true, onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Spacer(Modifier.height(8.dp))

                // Robot Connection Section
                SectionHeader(icon = Icons.Default.Wifi, title = "Robot Connection")

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                    shape = RoundedCornerShape(14.dp),
                    elevation = CardDefaults.cardElevation(1.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        OutlinedTextField(
                            value = uiState.robotIp,
                            onValueChange = { viewModel.updateRobotIp(it) },
                            label = { Text("Robot IP Address") },
                            placeholder = { Text("192.168.1.100") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            leadingIcon = { Icon(Icons.Default.Router, contentDescription = null, tint = TextSecondary) }
                        )

                        OutlinedTextField(
                            value = uiState.mqttPort,
                            onValueChange = { viewModel.updateMqttPort(it) },
                            label = { Text("MQTT Port") },
                            placeholder = { Text("1883") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            leadingIcon = { Icon(Icons.Default.Cable, contentDescription = null, tint = TextSecondary) },
                            isError = uiState.mqttPort.toIntOrNull() == null && uiState.mqttPort.isNotEmpty()
                        )
                        if (uiState.mqttPort.toIntOrNull() == null && uiState.mqttPort.isNotEmpty()) {
                            Text(
                                "Enter a valid port number (1–65535)",
                                style = MaterialTheme.typography.labelSmall,
                                color = ErrorRed
                            )
                        }

                        // Auto Connect Toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Auto-Connect", style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                                Text(
                                    "Automatically connect on app launch",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                            Switch(
                                checked = uiState.autoConnect,
                                onCheckedChange = { viewModel.updateAutoConnect(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = ElectricBlue
                                )
                            )
                        }
                    }
                }

                // Reconnect Interval
                SectionHeader(icon = Icons.Default.Refresh, title = "Reconnect Interval")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                    shape = RoundedCornerShape(14.dp),
                    elevation = CardDefaults.cardElevation(1.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "Retry interval",
                                style = MaterialTheme.typography.bodyLarge,
                                color = TextPrimary
                            )
                            Text(
                                "${uiState.reconnectIntervalSeconds.toInt()}s",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ElectricBlue
                            )
                        }
                        Slider(
                            value = uiState.reconnectIntervalSeconds,
                            onValueChange = { viewModel.updateReconnectInterval(it) },
                            valueRange = 5f..60f,
                            steps = 10,
                            colors = SliderDefaults.colors(
                                thumbColor = ElectricBlue,
                                activeTrackColor = ElectricBlue
                            )
                        )
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("5s", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            Text("60s", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        }
                    }
                }

                // Camera Quality Section
                SectionHeader(icon = Icons.Default.Videocam, title = "Camera Quality")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                    shape = RoundedCornerShape(14.dp),
                    elevation = CardDefaults.cardElevation(1.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        CameraQuality.values().forEach { quality ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                RadioButton(
                                    selected = uiState.cameraQuality == quality,
                                    onClick = { viewModel.updateCameraQuality(quality) },
                                    colors = RadioButtonDefaults.colors(selectedColor = ElectricBlue)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(quality.label, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                            }
                        }
                    }
                }

                // About Section
                SectionHeader(icon = Icons.Default.Info, title = "About")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                    shape = RoundedCornerShape(14.dp),
                    elevation = CardDefaults.cardElevation(1.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AboutRow("App", "Okello Nexus")
                        AboutRow("Developer", "ZunoBotics")
                        AboutRow("Version", "1.0.0")
                        AboutRow("Platform", "Android")
                    }
                }

                // Save Button
                Button(
                    onClick = { viewModel.save() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                    enabled = uiState.robotIp.isNotBlank() &&
                            (uiState.mqttPort.toIntOrNull() ?: 0) in 1..65535
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Save Settings")
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = ElectricBlue, modifier = Modifier.size(20.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
    }
}
