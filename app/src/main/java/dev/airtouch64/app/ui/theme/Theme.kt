package dev.airtouch64.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = BlueLight,
    secondary = SecondaryLight,
    tertiary = GreenLight,
)

private val DarkColors = darkColorScheme(
    primary = BlueDark,
    secondary = SecondaryDark,
    tertiary = GreenDark,
)

@Composable
fun AirTouchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
