package com.ps2mc.manager.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Fixed branded dark theme matching the mockups — no dynamic/system color,
// since the design calls for a specific navy/slate palette rather than
// per-device Material You colors.
private val AppDarkColorScheme = darkColorScheme(
    primary = AccentBlueLight,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    secondary = AccentBlueDark,
    background = SlateBackground,
    onBackground = TextPrimary,
    surface = SlateCard,
    onSurface = TextSecondary,
    surfaceVariant = SlateCardBorder,
    onSurfaceVariant = TextMuted
)

@Composable
fun PS2MCManagerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppDarkColorScheme,
        typography = Typography,
        content = content
    )
}
