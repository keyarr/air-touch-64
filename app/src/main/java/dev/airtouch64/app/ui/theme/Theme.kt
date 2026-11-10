package dev.airtouch64.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkroomColorScheme = darkColorScheme(
    primary = AdobeCyan,
    onPrimary = DarkroomVoid,
    primaryContainer = AdobeCyanMuted,
    onPrimaryContainer = AdobeCyan,
    secondary = TextSecondary,
    onSecondary = DarkroomVoid,
    surface = DarkroomVoid,
    onSurface = TextPrimary,
    surfaceContainer = DarkroomPanel,
    surfaceVariant = DarkroomCard,
    onSurfaceVariant = TextSecondary,
    outline = DarkroomBorder,
    outlineVariant = DarkroomBorderHigh,
    error = StatusError,
    onError = DarkroomVoid,
    errorContainer = StatusErrorBg,
    onErrorContainer = StatusError,
    tertiary = StatusSuccess,
    onTertiary = DarkroomVoid,
)

@Composable
fun AirTouchTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkroomColorScheme, content = content)
}
