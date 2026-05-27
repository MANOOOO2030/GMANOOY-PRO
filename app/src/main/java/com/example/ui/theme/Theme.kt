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
import androidx.compose.ui.graphics.Color

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

private val LightColorScheme =
  lightColorScheme(
    primary = Indigo500,
    secondary = Violet500,
    background = Color(0xFFF1F5F9), // Slate100
    surface = Color(0xFFF8FAFC), // soft, eye-soothing off-white
    onPrimary = Color.White,
    onBackground = Color(0xFF0F172A),
    onSurface = Color(0xFF0F172A),
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Color(0xFF312E81),
    surfaceVariant = Color(0xFFCBD5E1),
    onSurfaceVariant = Color(0xFF334155),
    outline = Color(0xFF94A3B8)
  )

@Composable
fun MyApplicationTheme(
  isDarkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  MaterialTheme(
      colorScheme = if (isDarkTheme) DarkColorScheme else LightColorScheme,
      typography = Typography, 
      content = content
  )
}
