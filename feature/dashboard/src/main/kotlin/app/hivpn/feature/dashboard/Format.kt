package app.hivpn.feature.dashboard

import java.util.Locale

/** 3_661_000 → "01:01:01". Negative values clamp to zero. */
internal fun formatDuration(millis: Long): String {
    val total = (millis / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return String.format(Locale.ROOT, "%02d:%02d:%02d", h, m, s)
}
