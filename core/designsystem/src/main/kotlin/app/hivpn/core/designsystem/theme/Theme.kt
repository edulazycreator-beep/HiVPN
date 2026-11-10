package app.hivpn.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightScheme = lightColorScheme(
    primary = HiPalette.PrimaryBlue,
    onPrimary = HiPalette.White,
    primaryContainer = Color(0xFFDCE7FE),
    onPrimaryContainer = HiPalette.DeepNavy,
    secondary = HiPalette.AccentBlue,
    onSecondary = HiPalette.DeepNavy,
    background = HiPalette.LightBackground,
    onBackground = HiPalette.DeepNavy,
    surface = HiPalette.White,
    onSurface = HiPalette.DeepNavy,
    surfaceVariant = HiPalette.SurfaceLight,
    onSurfaceVariant = HiPalette.Navy70,
    surfaceContainer = HiPalette.White,
    surfaceContainerHigh = HiPalette.SurfaceLight,
    outline = HiPalette.Navy15,
    outlineVariant = HiPalette.Navy15,
)

private val DarkScheme = darkColorScheme(
    primary = HiPalette.PrimaryBlue,
    onPrimary = HiPalette.White,
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = HiPalette.White,
    secondary = HiPalette.AccentBlue,
    onSecondary = HiPalette.DeepNavy,
    background = HiPalette.DarkBackground,
    onBackground = HiPalette.White,
    surface = HiPalette.Surface,
    onSurface = HiPalette.White,
    surfaceVariant = HiPalette.SurfaceHigh,
    onSurfaceVariant = HiPalette.Navy40,
    surfaceContainer = HiPalette.Surface,
    surfaceContainerHigh = HiPalette.SurfaceHigh,
    outline = Color(0xFF2A3A55),
    outlineVariant = Color(0xFF2A3A55),
)

private val HiShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }

@Composable
fun HiVpnTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalStatusColors provides if (darkTheme) DarkStatusColors else LightStatusColors,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = HiTypography,
            shapes = HiShapes,
            content = content,
        )
    }
}

/** Shorthand: `HiTheme.status.protected`. */
object HiTheme {
    val status: StatusColors
        @Composable get() = LocalStatusColors.current
}
