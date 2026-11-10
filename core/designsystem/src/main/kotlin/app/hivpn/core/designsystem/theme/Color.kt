package app.hivpn.core.designsystem.theme

import androidx.compose.ui.graphics.Color

/** Brand palette from the Hi VPN spec. */
object HiPalette {
    val DeepNavy = Color(0xFF101827)
    val PrimaryBlue = Color(0xFF2563EB)
    val AccentBlue = Color(0xFF38BDF8)
    val DarkBackground = Color(0xFF090F1A)
    val Surface = Color(0xFF172235)
    val LightBackground = Color(0xFFF5F7FB)
    val White = Color(0xFFFFFFFF)

    // Supporting neutrals derived from the brand navy (not part of the spec palette).
    val Navy70 = Color(0xFF475569)
    val Navy40 = Color(0xFF94A3B8)
    val Navy15 = Color(0xFFDDE3EC)
    val SurfaceHigh = Color(0xFF1F2C43)
    val SurfaceLight = Color(0xFFEEF2F8)
}

/**
 * Connection-state colours. Kept separate from the Material scheme so state colour is
 * consistent across light and dark themes and never doubles as decoration.
 */
data class StatusColors(
    val protected: Color,
    val inProgress: Color,
    val unprotected: Color,
    val error: Color,
)

val LightStatusColors = StatusColors(
    protected = Color(0xFF0E7C66),
    inProgress = Color(0xFFB45309),
    unprotected = HiPalette.Navy70,
    error = Color(0xFFB91C1C),
)

val DarkStatusColors = StatusColors(
    protected = Color(0xFF34D399),
    inProgress = Color(0xFFFBBF24),
    unprotected = HiPalette.Navy40,
    error = Color(0xFFF87171),
)
