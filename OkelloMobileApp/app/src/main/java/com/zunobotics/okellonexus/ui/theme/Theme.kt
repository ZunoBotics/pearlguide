package com.zunobotics.okellonexus.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

@Composable
fun NexusTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NexusLightColorScheme,
        typography = NexusTypography,
        content = content
    )
}
