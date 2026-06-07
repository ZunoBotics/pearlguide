package com.zunobotics.okellonexus.ui.screens.home

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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zunobotics.okellonexus.ui.components.ConnectionBadge
import com.zunobotics.okellonexus.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigatePersona: () -> Unit,
    onNavigateLocation: () -> Unit,
    onNavigateKnowledge: () -> Unit,
    onNavigateCommand: () -> Unit,
    onNavigateMonitor: () -> Unit,
    onNavigateLanguage: () -> Unit,
    onNavigateSettings: () -> Unit,
    onNavigateSafety: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.connectionState.collectAsState()
    val status by viewModel.robotStatus.collectAsState()
    val knowledgeCount by viewModel.knowledgeCount.collectAsState()
    val activityLog by viewModel.activityLog.collectAsState()
    val activePersonaName by viewModel.activePersonaName.collectAsState()
    val activeLocationName by viewModel.activeLocationName.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nexus", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    ConnectionBadge(state = state, modifier = Modifier.padding(end = 8.dp))
                    IconButton(onClick = onNavigateSafety) {
                        Icon(Icons.Default.Shield, contentDescription = "Safety", tint = ErrorRed)
                    }
                    IconButton(onClick = onNavigateSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceWhite)
            )
        },
        bottomBar = {
            NavigationBar(containerColor = SurfaceWhite) {
                NavigationBarItem(
                    selected = true,
                    onClick = {},
                    icon = { Icon(Icons.Default.Dashboard, contentDescription = null) },
                    label = { Text("Home") }
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onNavigatePersona,
                    icon = { Icon(Icons.Default.Person, contentDescription = null) },
                    label = { Text("Identity") }
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onNavigateLocation,
                    icon = { Icon(Icons.Default.LocationOn, contentDescription = null) },
                    label = { Text("Location") }
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onNavigateKnowledge,
                    icon = { Icon(Icons.Default.MenuBook, contentDescription = null) },
                    label = { Text("Knowledge") }
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onNavigateMonitor,
                    icon = { Icon(Icons.Default.Videocam, contentDescription = null) },
                    label = { Text("Monitor") }
                )
            }
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
            // Status card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Robot Status",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                            val modeBg = when (status.mode) {
                                "ACTIVE" -> SuccessTeal
                                "TEACHING" -> AmberGold
                                else -> ElectricBlue.copy(alpha = 0.15f)
                            }
                            val modeTextColor = when (status.mode) {
                                "ACTIVE", "TEACHING" -> Color.White
                                else -> ElectricBlue
                            }
                            Surface(color = modeBg, shape = RoundedCornerShape(8.dp)) {
                                Text(
                                    status.mode.ifEmpty { "IDLE" },
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = modeTextColor
                                )
                            }
                        }
                        StatusRow("Persona", activePersonaName.ifEmpty { "None set" })
                        StatusRow("Location", activeLocationName.ifEmpty { "None set" })
                        StatusRow("Language", status.activeLanguage.uppercase())
                    }
                }
            }

            // Quick stats
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatChip(
                        modifier = Modifier.weight(1f),
                        label = "Battery",
                        value = if (status.batteryPercent >= 0) "${status.batteryPercent}%" else "—"
                    )
                    StatChip(
                        modifier = Modifier.weight(1f),
                        label = "Knowledge",
                        value = "$knowledgeCount entries"
                    )
                }
            }

            // Quick actions header
            item {
                Text("Quick Actions", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            }

            // Quick action buttons
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onNavigateCommand,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Send Command")
                    }
                    Button(
                        onClick = onNavigateSafety,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = ErrorRed)
                    ) {
                        Icon(Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Safety")
                    }
                }
            }

            // Secondary actions row
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = onNavigateLanguage,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Language")
                    }
                    OutlinedButton(
                        onClick = onNavigateMonitor,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Videocam, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Monitor")
                    }
                }
            }

            // Activity log header
            if (activityLog.isNotEmpty()) {
                item {
                    Text("Recent Activity", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                }
                items(activityLog) { entry ->
                    Text(
                        entry,
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
private fun RowScope.StatChip(modifier: Modifier, label: String, value: String) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Text(value, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        }
    }
}
