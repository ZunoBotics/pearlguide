package com.zunobotics.okellonexus.ui.screens.connect

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zunobotics.okellonexus.R
import com.zunobotics.okellonexus.data.model.RobotConnectionState
import com.zunobotics.okellonexus.ui.theme.*
import android.content.Context
import android.net.wifi.WifiManager
import android.text.format.Formatter
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ConnectScreen(
    onConnected: () -> Unit,
    viewModel: ConnectViewModel = hiltViewModel()
) {
    val state by viewModel.connectionState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(state) {
        if (state == RobotConnectionState.CONNECTED_IDLE || state == RobotConnectionState.CONNECTED_ACTIVE) {
            onConnected()
        }
    }

    var localIp by remember { mutableStateOf("…") }
    LaunchedEffect(Unit) {
        localIp = withContext(Dispatchers.IO) {
            try {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val ip = wm.connectionInfo.ipAddress
                if (ip == 0) "Not connected to Wi-Fi" else Formatter.formatIpAddress(ip)
            } catch (_: Exception) { "Not connected to Wi-Fi" }
        }
    }

    val pulseScale by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f, targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundWhite),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Image(
                painter = painterResource(R.mipmap.ic_launcher),
                contentDescription = null,
                modifier = Modifier
                    .size(100.dp)
                    .scale(if (state == RobotConnectionState.CONNECTING) pulseScale else 1f)
            )

            Text("Okello Nexus", style = MaterialTheme.typography.headlineMedium, color = ElectricBlue)

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.Wifi,
                        contentDescription = null,
                        tint = ElectricBlue,
                        modifier = Modifier.size(40.dp)
                    )
                    Text(
                        "Starting Okello Nexus broker…",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = TextPrimary
                    )
                    Text(
                        "Connect Quest to the same Wi-Fi or phone hotspot",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = TextSecondary
                    )
                    when (state) {
                        RobotConnectionState.CONNECTING -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = AmberGold
                                )
                                Text("Starting…", color = AmberGold, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        RobotConnectionState.ERROR -> {
                            Text(
                                "Broker error — tap Retry",
                                color = ErrorRed,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )
                        }
                        else -> {
                            Text(
                                "Initialising…",
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            // Phone broker IP — user enters this in Quest app settings
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = ElectricBlue.copy(alpha = 0.08f)),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, ElectricBlue.copy(alpha = 0.35f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "Phone Broker IP",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                    Text(
                        localIp,
                        style = MaterialTheme.typography.headlineSmall,
                        color = ElectricBlue,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        "Enter this IP in Quest app settings (port 8080)",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }

            OutlinedButton(
                onClick = { viewModel.connect() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Retry Connection")
            }
        }
    }
}
