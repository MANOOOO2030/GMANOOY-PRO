package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = Indigo500,
    secondary = Violet500,
    background = DarkBg,
    surface = DarkBg,
    onPrimary = Slate100,
    onBackground = Slate100,
    onSurface = Slate100,
    primaryContainer = GlassBg,
    onPrimaryContainer = Slate100,
    surfaceVariant = GlassBg,
    onSurfaceVariant = Slate400,
    outline = GlassBorder
  )

@Composable
fun MyApplicationTheme(
  content: @Composable () -> Unit,
) {
  MaterialTheme(colorScheme = DarkColorScheme, typography = Typography, content = content)
}
