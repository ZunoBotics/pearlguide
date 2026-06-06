package com.zunobotics.okellonexus.ui.screens.splash

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.zunobotics.okellonexus.R
import com.zunobotics.okellonexus.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(onReady: (connected: Boolean) -> Unit) {
    val alpha by rememberInfiniteTransition(label = "fade").animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
        label = "alpha"
    )

    LaunchedEffect(Unit) {
        delay(2000)
        onReady(false) // ConnectScreen will check actual connection
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundWhite),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Image(
                painter = painterResource(R.mipmap.ic_launcher),
                contentDescription = "Okello Nexus",
                modifier = Modifier.size(140.dp)
            )
            Text("Okello Nexus", style = MaterialTheme.typography.headlineLarge, color = ElectricBlue)
            Text("Robot Control Center", style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
            Spacer(Modifier.height(32.dp))
            CircularProgressIndicator(
                modifier = Modifier
                    .size(32.dp)
                    .alpha(alpha),
                color = ElectricBlue,
                strokeWidth = 3.dp
            )
            Text("Powered by ZunoBotics", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }
    }
}
