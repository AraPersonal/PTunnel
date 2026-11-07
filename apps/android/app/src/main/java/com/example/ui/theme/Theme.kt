package com.example.ui.theme

import android.app.Activity
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.WindowCompat

private val ShieldColorScheme = darkColorScheme(
    primary = LightMintGlow,
    onPrimary = DeepForestBg,
    primaryContainer = AccentActive,
    onPrimaryContainer = LightMintGlow,
    secondary = MutedSageText,
    onSecondary = DeepForestBg,
    secondaryContainer = MidDarkElements,
    onSecondaryContainer = LightMintGlow,
    tertiary = AccentActive,
    onTertiary = LightMintGlow,
    background = DeepForestBg,
    onBackground = LightMintGlow,
    surface = TrackDarkBg,
    onSurface = LightMintGlow,
    surfaceVariant = MidDarkElements,
    onSurfaceVariant = MutedSageText,
    outline = MutedSageText
)

@Composable
fun ShieldVpnTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            view.layoutDirection = View.LAYOUT_DIRECTION_LTR
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.decorView.layoutDirection = View.LAYOUT_DIRECTION_LTR
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        MaterialTheme(
            colorScheme = ShieldColorScheme,
            typography = Typography,
            content = content
        )
    }
}
