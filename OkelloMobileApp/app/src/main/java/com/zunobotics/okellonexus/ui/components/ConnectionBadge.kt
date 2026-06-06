package com.zunobotics.okellonexus.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zunobotics.okellonexus.data.model.RobotConnectionState
import com.zunobotics.okellonexus.ui.theme.*

@Composable
fun ConnectionBadge(state: RobotConnectionState, modifier: Modifier = Modifier) {
    val (color, label) = when (state) {
        RobotConnectionState.CONNECTED_IDLE, RobotConnectionState.CONNECTED_ACTIVE -> SuccessTeal to "Connected"
        RobotConnectionState.CONNECTING -> AmberGold to "Connecting"
        RobotConnectionState.QUEST_OFFLINE -> AmberGold to "Quest Offline"
        RobotConnectionState.ERROR -> ErrorRed to "Error"
        RobotConnectionState.DISCONNECTED -> ErrorRed to "Disconnected"
    }

    val isConnecting = state == RobotConnectionState.CONNECTING
    val scale by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f, targetValue = if (isConnecting) 1.4f else 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "scale"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(color)
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}
