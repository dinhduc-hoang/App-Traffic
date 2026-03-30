package com.example.utt_trafficjams.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ==============================
// Dark Color Scheme cho Material3
// ==============================
private val DarkColorScheme = darkColorScheme(
    primary = PrimaryAmber,
    onPrimary = Color.Black,
    secondary = CardDarkLight,
    onSecondary = TextWhite,
    tertiary = StatusGreen,
    background = DarkBackground,
    onBackground = TextWhite,
    surface = CardDark,
    onSurface = TextWhite,
    surfaceVariant = CardDarkLight,
    onSurfaceVariant = TextSecondary,
    outline = CardDarkLighter,
    error = StatusRed
)

@Composable
fun UTTTrafficTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = DarkBackground.toArgb()
            window.navigationBarColor = NavBarBg.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = UTTTypography,
        content = content
    )
}
