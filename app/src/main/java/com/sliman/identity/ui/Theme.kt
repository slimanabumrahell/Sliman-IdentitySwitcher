package com.sliman.identity.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Scheme = darkColorScheme(
    primary = Color(0xFF8B7CFF),
    onPrimary = Color(0xFF0B0D12),
    primaryContainer = Color(0xFF2A2650),
    onPrimaryContainer = Color(0xFFD9D4FF),
    secondary = Color(0xFF2DD4BF),
    background = Color(0xFF0B0D12),
    onBackground = Color(0xFFE8EAF0),
    surface = Color(0xFF14171F),
    onSurface = Color(0xFFE8EAF0),
    surfaceVariant = Color(0xFF1A1E2A),
    onSurfaceVariant = Color(0xFF9AA3B5),
    outline = Color(0xFF2A3045),
    error = Color(0xFFFF6B6B),
)

@Composable
fun SlimanTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
