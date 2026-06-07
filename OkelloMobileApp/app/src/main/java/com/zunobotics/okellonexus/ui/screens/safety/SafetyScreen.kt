package com.zunobotics.okellonexus.ui.screens.safety

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zunobotics.okellonexus.data.repository.ObstacleAlert
import com.zunobotics.okellonexus.ui.components.NexusTopBar
import com.zunobotics.okellonexus.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SafetyScreen(
    onBack: () -> Unit,
    viewModel: SafetyViewModel = hiltViewModel()
) {
    val activeCommand by viewModel.activeCommand.collectAsState()
    val isEmergencyStopped = activeCommand == "emergency_stop"
    val isCallingHelp = activeCommand == "call_human"
    val obstacleAlerts by viewModel.obstacleAlerts.collectAsState()

    Scaffold(
        topBar = {
            NexusTopBar(
                title = "Safety & Emergency",
                showBack = true,
                onBack = onBack
            )
        },
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

            // Emergency Stop
            Button(
                onClick = {
                    if (isEmergencyStopped) viewModel.resume() else viewModel.emergencyStop()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isEmergencyStopped) AmberGold else ErrorRed
                )
            ) {
                Icon(
                    if (isEmergencyStopped) Icons.Default.PlayArrow else Icons.Default.Stop,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    if (isEmergencyStopped) "RESUME ROBOT" else "EMERGENCY STOP",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            if (isEmergencyStopped) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = ErrorRed.copy(alpha = 0.10f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = ErrorRed)
                        Text(
                            "Emergency stop sent — robot is announcing stop. Tap RESUME when clear.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ErrorRed
                        )
                    }
                }
            }

            // Call for Human Help
            OutlinedButton(
                onClick = { if (!isCallingHelp) viewModel.callForHelp() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = !isCallingHelp,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AmberGold)
            ) {
                Icon(Icons.Default.RecordVoiceOver, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(
                    if (isCallingHelp) "Calling for help…" else "Call for Human Help",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            if (isCallingHelp) {
                Text(
                    "Robot is announcing: \"Excuse me, could a staff member please come to assist?\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            HorizontalDivider()

            // Detection Status
            Text("Detection Status", style = MaterialTheme.typography.titleMedium, color = TextPrimary)

            StatusCard(
                title = "Staircase Detection",
                value = "ACTIVE",
                valueColor = SuccessTeal,
                subtitle = "Quest 3 depth sensor — real-time floor drop detection",
                icon = Icons.Default.Warning
            )

            StatusCard(
                title = "Obstacle Detection",
                value = "ACTIVE",
                valueColor = SuccessTeal,
                subtitle = "Quest 3 stereo cameras — persons, walls, furniture",
                icon = Icons.Default.Visibility
            )

            HorizontalDivider()

            // Safe Zone Enforcement
            Text("Safe Zone Enforcement", style = MaterialTheme.typography.titleMedium, color = TextPrimary)

            SafeZoneCard(
                label = "Danger Zone (< 60 cm)",
                enforcement = "Motor stop — requires Pi 5",
                detectionActive = true
            )
            SafeZoneCard(
                label = "Caution Zone (60–100 cm)",
                enforcement = "Motor slow — requires Pi 5",
                detectionActive = true
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = ElectricBlue.copy(alpha = 0.07f)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = ElectricBlue, modifier = Modifier.size(18.dp))
                    Text(
                        "Motor enforcement is pending Raspberry Pi 5 integration. Detection is active now; physical stop/slow actions will be enabled after Pi integration.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ElectricBlue
                    )
                }
            }

            HorizontalDivider()

            Text("Recent Alerts", style = MaterialTheme.typography.titleMedium, color = TextPrimary)

            if (obstacleAlerts.isEmpty()) {
                Text(
                    "No alerts received in this session.\nStaircase and obstacle alerts from the Quest will appear here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            } else {
                obstacleAlerts.take(10).forEach { alert ->
                    ObstacleAlertCard(alert)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ObstacleAlertCard(alert: ObstacleAlert) {
    val bg = when (alert.severity) {
        "danger" -> ErrorRed.copy(alpha = 0.08f)
        "caution" -> AmberGold.copy(alpha = 0.08f)
        else -> SurfaceWhite
    }
    val accent = when (alert.severity) {
        "danger" -> ErrorRed
        "caution" -> AmberGold
        else -> ElectricBlue
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bg),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (alert.type == "staircase") Icons.Default.Warning else Icons.Default.Report,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${alert.type.replaceFirstChar { it.uppercase() }} — ${alert.direction}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "${alert.distanceCm}cm  ·  ${(alert.confidence * 100).toInt()}% confidence",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            Text(
                SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(alert.timestampMs)),
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun StatusCard(
    title: String,
    value: String,
    valueColor: Color,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, contentDescription = null, tint = valueColor, modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            Surface(color = valueColor, shape = RoundedCornerShape(6.dp)) {
                Text(
                    value,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun SafeZoneCard(label: String, enforcement: String, detectionActive: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = if (detectionActive) SuccessTeal else ErrorRed,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            if (detectionActive) "DETECTING" else "OFF",
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White
                        )
                    }
                    Text("Enforcement: $enforcement", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }
            Icon(Icons.Default.Schedule, contentDescription = null, tint = AmberGold, modifier = Modifier.size(20.dp))
        }
    }
}
