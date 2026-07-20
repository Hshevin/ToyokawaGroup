package com.example.skyedge.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val SkyEdgeColorScheme = lightColorScheme(
    primary = SkyEdgeColors.Green,
    onPrimary = Color.White,
    primaryContainer = SkyEdgeColors.Field,
    onPrimaryContainer = SkyEdgeColors.GreenDark,
    secondary = SkyEdgeColors.Cyan,
    onSecondary = Color.White,
    secondaryContainer = SkyEdgeColors.IconBg,
    onSecondaryContainer = SkyEdgeColors.Ink,
    tertiary = SkyEdgeColors.Amber,
    error = SkyEdgeColors.Red,
    onError = Color.White,
    background = SkyEdgeColors.Paper,
    onBackground = SkyEdgeColors.Ink,
    surface = SkyEdgeColors.Surface,
    onSurface = SkyEdgeColors.Ink,
    surfaceVariant = SkyEdgeColors.Field,
    onSurfaceVariant = SkyEdgeColors.Muted,
    outline = SkyEdgeColors.Line,
    outlineVariant = SkyEdgeColors.Line,
)

private val SkyEdgeShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

@Composable
fun SkyEdgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SkyEdgeColorScheme,
        typography = SkyEdgeTypography,
        shapes = SkyEdgeShapes,
        content = content,
    )
}
