package com.zunobotics.okellonexus.ui.screens.monitor

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zunobotics.okellonexus.data.model.RobotConnectionState
import com.zunobotics.okellonexus.data.repository.CameraFrame
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
    val cameraFrame by viewModel.cameraFrame.collectAsState()
    val piConnected by viewModel.piConnected.collectAsState()
    val agentRunning by viewModel.agentRunning.collectAsState()
    val temperature by viewModel.temperature.collectAsState()

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

            // Live camera feed
            item {
                Text("Camera Feed", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Spacer(Modifier.height(8.dp))
                CameraFeedPanel(frame = cameraFrame)
            }

            // Agent toggle
            item {
                val agentColor = if (agentRunning) SuccessTeal else ErrorRed
                val agentLabel = if (agentRunning) "Okello is ON" else "Okello is OFF"
                Button(
                    onClick = { if (piConnected) viewModel.toggleAgent() },
                    enabled = piConnected,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = agentColor,
                        disabledContainerColor = agentColor.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Icon(
                        if (agentRunning) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(agentLabel, style = MaterialTheme.typography.titleSmall)
                }
            }

            // Drive controls
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Drive Controls", style = MaterialTheme.typography.titleMedium, color = TextPrimary, modifier = Modifier.weight(1f))
                    Surface(
                        color = if (piConnected) SuccessTeal.copy(alpha = 0.15f) else ErrorRed.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(Modifier.size(7.dp).background(if (piConnected) SuccessTeal else ErrorRed, CircleShape))
                            Text(
                                if (piConnected) "Pi Connected" else "Pi Offline",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (piConnected) SuccessTeal else ErrorRed
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                RobotDpad(
                    enabled = piConnected,
                    onForward = viewModel::moveForward,
                    onBackward = viewModel::moveBackward,
                    onLeft = viewModel::strafeLeft,
                    onRight = viewModel::strafeRight,
                    onTurnLeft = viewModel::turnLeft,
                    onTurnRight = viewModel::turnRight,
                    onStop = viewModel::stopMovement
                )
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
                        StatusRow(
                            "Patient Temp",
                            temperature?.let { "%.1f°C (Ambient %.1f°C)".format(it.objectC, it.ambientC) } ?: "—"
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
private fun CameraFeedPanel(frame: CameraFrame?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .background(Color(0xFF1A1A2E), RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (frame != null) {
            val bitmap = remember(frame.jpegBase64) {
                try {
                    val bytes = Base64.decode(frame.jpegBase64, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                } catch (_: Exception) { null }
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Robot camera",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(2.dp)
                )
            }
            // Detection overlay chips
            if (frame.detections.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    frame.detections.forEach { d ->
                        val color = if (d.distanceCm < 60) ErrorRed else AmberGold
                        Surface(
                            color = color.copy(alpha = 0.85f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                "${d.type} ${d.direction} ${d.distanceCm}cm",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            // Timestamp
            Text(
                SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(frame.receivedAt)),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Default.Videocam,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.4f),
                    modifier = Modifier.size(40.dp)
                )
                Text(
                    "Waiting for camera feed from Quest…",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
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
private fun RobotDpad(
    enabled: Boolean,
    onForward: () -> Unit,
    onBackward: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onTurnLeft: () -> Unit,
    onTurnRight: () -> Unit,
    onStop: () -> Unit
) {
    val btnColor = if (enabled) ElectricBlue else TextSecondary.copy(alpha = 0.3f)

    @Composable
    fun DriveButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onPress: () -> Unit) {
        Surface(
            color = btnColor.copy(alpha = 0.15f),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .size(60.dp)
                .pointerInput(enabled) {
                    detectTapGestures(
                        onPress = {
                            if (enabled) {
                                onPress()
                                tryAwaitRelease()
                                onStop()
                            }
                        }
                    )
                }
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(icon, contentDescription = label, tint = btnColor, modifier = Modifier.size(24.dp))
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Turn row
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                DriveButton(Icons.Default.RotateLeft, "Turn Left", onTurnLeft)
                Spacer(Modifier.width(52.dp))
                DriveButton(Icons.Default.RotateRight, "Turn Right", onTurnRight)
            }
            // Forward
            Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                DriveButton(Icons.Default.ArrowUpward, "Forward", onForward)
            }
            // Left / Stop / Right
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                DriveButton(Icons.Default.ArrowBack, "Left", onLeft)
                Surface(
                    color = ErrorRed.copy(alpha = if (enabled) 0.15f else 0.06f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .size(60.dp)
                        .pointerInput(enabled) {
                            detectTapGestures(onTap = { if (enabled) onStop() })
                        }
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Text("STOP", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            color = if (enabled) ErrorRed else ErrorRed.copy(alpha = 0.3f))
                    }
                }
                DriveButton(Icons.Default.ArrowForward, "Right", onRight)
            }
            // Backward
            Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                DriveButton(Icons.Default.ArrowDownward, "Backward", onBackward)
            }
            if (!enabled) {
                Text(
                    "Pi not connected — connect at ${10}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }
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
