package com.zunobotics.okellonexus.ui.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

val ElectricBlue = Color(0xFF00BFFF)
val DeepBlue = Color(0xFF0072B8)
val AmberGold = Color(0xFFF5A623)
val OrangeAccent = Color(0xFFE8622A)
val BackgroundWhite = Color(0xFFF4F6FB)
val SurfaceWhite = Color(0xFFFFFFFF)
val SurfaceElevated = Color(0xFFEBF0FA)
val BorderColor = Color(0xFFD8E0EE)
val TextPrimary = Color(0xFF0F1923)
val TextSecondary = Color(0xFF6B7A90)
val SuccessTeal = Color(0xFF00C896)
val ErrorRed = Color(0xFFE63946)

val NexusLightColorScheme = lightColorScheme(
    primary = ElectricBlue,
    onPrimary = Color.White,
    primaryContainer = SurfaceElevated,
    secondary = AmberGold,
    onSecondary = Color.White,
    tertiary = OrangeAccent,
    background = BackgroundWhite,
    surface = SurfaceWhite,
    surfaceVariant = SurfaceElevated,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    outline = BorderColor,
    error = ErrorRed
)
