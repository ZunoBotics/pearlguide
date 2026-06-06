package com.zunobotics.okellonexus.ui.screens.monitor

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zunobotics.okellonexus.data.model.RobotConnectionState
import com.zunobotics.okellonexus.ui.components.ConnectionBadge
import com.zunobotics.okellonexus.ui.components.NexusTopBar
import com.zunobotics.okellonexus.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorScreen(
    onBack: () -> Unit,
    viewModel: MonitorViewModel = hiltViewModel()
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val monitorState by viewModel.monitorState.collectAsState()
    val eventLog by viewModel.eventLog.collectAsState()

    Scaffold(
        topBar = {
            NexusTopBar(
                title = "Monitor",
                showBack = true,
                onBack = onBack,
                connectionState = connectionState
            )
        },
        containerColor = BackgroundWhite
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // Connection state banner
            item {
                val (bannerColor, bannerText) = when (connectionState) {
                    RobotConnectionState.CONNECTED_IDLE, RobotConnectionState.CONNECTED_ACTIVE ->
                        SuccessTeal to "Robot Connected — Live Data"
                    RobotConnectionState.CONNECTING -> AmberGold to "Connecting to Robot…"
                    RobotConnectionState.QUEST_OFFLINE -> AmberGold to "Pico 4 Headset Offline"
                    RobotConnectionState.ERROR -> ErrorRed to "Connection Error"
                    RobotConnectionState.DISCONNECTED -> ErrorRed to "Disconnected"
                }
                Surface(
                    color = bannerColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            when (connectionState) {
                                RobotConnectionState.CONNECTED_IDLE, RobotConnectionState.CONNECTED_ACTIVE ->
                                    Icons.Default.CheckCircle
                                RobotConnectionState.CONNECTING -> Icons.Default.Sync
                                else -> Icons.Default.ErrorOutline
                            },
                            contentDescription = null,
                            tint = bannerColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(bannerText, style = MaterialTheme.typography.bodyMedium, color = bannerColor, fontWeight = FontWeight.Medium)
                    }
                }
            }

            // Camera feed placeholder
            item {
                Text("Camera Feed", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .background(Color(0xFF1A1A2E), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.Videocam,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.4f),
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            if (connectionState == RobotConnectionState.CONNECTED_IDLE ||
                                connectionState == RobotConnectionState.CONNECTED_ACTIVE)
                                "Camera feed not available in this build"
                            else
                                "Connect to robot to view camera",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.5f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                }
            }

            // Status Panel
            item {
                Text("Robot Status", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                    shape = RoundedCornerShape(14.dp),
                    elevation = CardDefaults.cardElevation(2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        StatusRow("Mode", monitorState.robotStatus.mode.ifEmpty { "IDLE" })
                        StatusRow("Persona", monitorState.robotStatus.personaName.ifEmpty { "—" })
                        StatusRow("Location", monitorState.robotStatus.locationName.ifEmpty { "—" })
                        StatusRow("Language", monitorState.robotStatus.activeLanguage.uppercase())
                        StatusRow(
                            "Battery",
                            if (monitorState.robotStatus.batteryPercent >= 0)
                                "${monitorState.robotStatus.batteryPercent}%"
                            else "—"
                        )

                        Divider(color = BorderColor)

                        // Sensor indicators
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            SensorChip(
                                label = "Person Detected",
                                active = monitorState.personDetected,
                                activeColor = SuccessTeal,
                                modifier = Modifier.weight(1f)
                            )
                            SensorChip(
                                label = "Obstacle",
                                active = monitorState.obstacleWarning,
                                activeColor = ErrorRed,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        if (monitorState.lastUpdatedMs > 0) {
                            Text(
                                "Updated: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(monitorState.lastUpdatedMs))}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }

            // Current Speech
            if (monitorState.currentSpeech.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = ElectricBlue.copy(alpha = 0.08f)),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, ElectricBlue.copy(alpha = 0.3f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.RecordVoiceOver, contentDescription = null, tint = ElectricBlue, modifier = Modifier.size(18.dp))
                                Text("Currently Speaking", style = MaterialTheme.typography.labelLarge, color = ElectricBlue)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                monitorState.currentSpeech,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary
                            )
                        }
                    }
                }
            }

            // Event Log
            if (eventLog.isNotEmpty()) {
                item {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Event Log", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                        TextButton(onClick = { viewModel.clearEventLog() }) {
                            Text("Clear", color = TextSecondary)
                        }
                    }
                }
                items(eventLog) { event ->
                    Text(
                        event,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SensorChip(
    label: String,
    active: Boolean,
    activeColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = if (active) activeColor.copy(alpha = 0.15f) else SurfaceElevated,
        shape = RoundedCornerShape(10.dp),
        border = if (active) androidx.compose.foundation.BorderStroke(1.dp, activeColor) else null
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        if (active) activeColor else TextSecondary.copy(alpha = 0.3f),
                        androidx.compose.foundation.shape.CircleShape
                    )
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (active) activeColor else TextSecondary
            )
        }
    }
}
