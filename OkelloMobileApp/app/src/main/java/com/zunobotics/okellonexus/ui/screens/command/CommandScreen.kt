package com.zunobotics.okellonexus.ui.screens.command

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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zunobotics.okellonexus.ui.components.NexusTopBar
import com.zunobotics.okellonexus.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

private data class QuickCommandDef(
    val label: String,
    val command: String,
    val icon: ImageVector,
    val color: Color,
    val isDestructive: Boolean = false
)

private val QUICK_COMMANDS = listOf(
    QuickCommandDef("Come Here", "COME_HERE", Icons.Default.DirectionsWalk, ElectricBlue),
    QuickCommandDef("Stand By", "STAND_BY", Icons.Default.PauseCircle, AmberGold),
    QuickCommandDef("Greet Next", "GREET_NEXT_PERSON", Icons.Default.WavingHand, SuccessTeal),
    QuickCommandDef("Start Patrol", "START_PATROL", Icons.Default.Route, DeepBlue),
    QuickCommandDef("Return to Base", "RETURN_TO_BASE", Icons.Default.Home, OrangeAccent),
    QuickCommandDef("Emergency Stop", "EMERGENCY_STOP", Icons.Default.Stop, ErrorRed, isDestructive = true)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandScreen(
    onBack: () -> Unit,
    viewModel: CommandViewModel = hiltViewModel()
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val customInstruction by viewModel.customInstruction.collectAsState()
    val recentCommands by viewModel.recentCommands.collectAsState()
    val snackMessage by viewModel.snackMessage.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(snackMessage) {
        snackMessage?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            viewModel.clearSnack()
        }
    }

    Scaffold(
        topBar = {
            NexusTopBar(
                title = "Commands",
                showBack = true,
                onBack = onBack,
                connectionState = connectionState
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
            // Quick Commands Grid
            item {
                Text("Quick Commands", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    QUICK_COMMANDS.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { cmd ->
                                QuickCommandButton(
                                    def = cmd,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        if (cmd.isDestructive) {
                                            viewModel.sendEmergencyStop()
                                        } else {
                                            viewModel.sendQuickCommand(cmd.command, cmd.label)
                                        }
                                    }
                                )
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            // Custom Instruction
            item {
                Divider(color = BorderColor)
                Spacer(Modifier.height(4.dp))
                Text("Custom Instruction", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = customInstruction,
                        onValueChange = { viewModel.customInstruction.value = it },
                        label = { Text("Instruction") },
                        placeholder = { Text("e.g. Walk to the entrance and greet visitors…") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 5
                    )
                    Button(
                        onClick = { viewModel.sendCustomInstruction() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                        enabled = customInstruction.isNotBlank()
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Send Instruction")
                    }
                }
            }

            // Recent commands
            if (recentCommands.isNotEmpty()) {
                item {
                    Divider(color = BorderColor)
                    Spacer(Modifier.height(4.dp))
                    Text("Recent Commands", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                }
                items(recentCommands) { cmd ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            cmd.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(cmd.timestampMs)),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickCommandButton(
    def: QuickCommandDef,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = def.color.copy(alpha = if (def.isDestructive) 1f else 0.1f)),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(0.dp),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                def.icon,
                contentDescription = def.label,
                tint = if (def.isDestructive) Color.White else def.color,
                modifier = Modifier.size(28.dp)
            )
            Text(
                def.label,
                style = MaterialTheme.typography.labelLarge,
                color = if (def.isDestructive) Color.White else def.color,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
