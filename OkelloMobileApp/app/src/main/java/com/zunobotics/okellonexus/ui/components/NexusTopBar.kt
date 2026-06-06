package com.zunobotics.okellonexus.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zunobotics.okellonexus.data.model.RobotConnectionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NexusTopBar(
    title: String,
    showBack: Boolean = false,
    onBack: () -> Unit = {},
    connectionState: RobotConnectionState? = null,
    modifier: Modifier = Modifier
) {
    TopAppBar(
        title = { Text(title) },
        modifier = modifier,
        navigationIcon = {
            if (showBack) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        },
        actions = {
            if (connectionState != null) {
                ConnectionBadge(state = connectionState)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface
        )
    )
}
