package com.zerovpn.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val ZeroVpnColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Bg,
    secondary = Accent,
    onSecondary = Bg,
    tertiary = Accent,
    onTertiary = Bg,
    background = Bg,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = Surface,
    onSurfaceVariant = TextDim,
    error = Danger,
    onError = Bg,
    outline = Border,
    outlineVariant = Border,
)

@Composable
fun ZeroVpnTheme(
    content: @Composable () -> Unit
) {
    // Dark theme only — ignore system theme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Bg.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            window.navigationBarColor = Bg.toArgb()
        }
    }

    MaterialTheme(
        colorScheme = ZeroVpnColorScheme,
        typography = ZeroVpnTypography,
        content = content
    )
}