package app.hivpn.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Two-line live traffic graph (download = primary, upload = secondary).
 * Values are bytes/second samples, oldest first. Draws nothing for empty input.
 */
@Composable
fun TrafficGraph(
    down: List<Long>,
    up: List<Long>,
    description: String,
    modifier: Modifier = Modifier,
) {
    val downColor = MaterialTheme.colorScheme.primary
    val upColor = MaterialTheme.colorScheme.secondary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        modifier
            .fillMaxWidth()
            .height(96.dp)
            .semantics { contentDescription = description },
    ) {
        val max = (down + up).maxOrNull()?.coerceAtLeast(1) ?: return@Canvas
        listOf(0f, 0.5f, 1f).forEach { f ->
            val y = size.height * f
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        fun series(values: List<Long>, color: androidx.compose.ui.graphics.Color) {
            if (values.size < 2) return
            val step = size.width / (values.size - 1)
            val path = Path()
            values.forEachIndexed { i, v ->
                val x = i * step
                val y = size.height - (v.toFloat() / max) * size.height
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        series(down, downColor)
        series(up, upColor)
    }
}

/** Simple vertical bars, e.g. daily traffic. One bar per value, oldest first. */
@Composable
fun BarChart(values: List<Long>, description: String, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = description }) {
        if (values.isEmpty()) return@Canvas
        val max = values.max().coerceAtLeast(1)
        val gap = 4.dp.toPx()
        val w = (size.width - gap * (values.size - 1)) / values.size
        values.forEachIndexed { i, v ->
            val h = if (v == 0L) 2.dp.toPx() else (v.toFloat() / max) * size.height
            drawRoundRect(
                color = if (v == 0L) empty else color,
                topLeft = Offset(i * (w + gap), size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
        }
    }
}

/** 1536 → "1.5 KB"; binary units, one decimal. */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var i = 0
    while (value >= 1024 && i < units.lastIndex) {
        value /= 1024
        i++
    }
    return String.format(Locale.ROOT, "%.1f %s", value, units[i])
}

/** Bytes/second shown as bits/second, the way connection speeds are usually quoted. */
fun formatRate(bytesPerSec: Long): String {
    val bits = bytesPerSec * 8.0
    return when {
        bits >= 1_000_000_000 -> String.format(Locale.ROOT, "%.1f Gbps", bits / 1e9)
        bits >= 1_000_000 -> String.format(Locale.ROOT, "%.1f Mbps", bits / 1e6)
        bits >= 1_000 -> String.format(Locale.ROOT, "%.0f kbps", bits / 1e3)
        else -> String.format(Locale.ROOT, "%.0f bps", bits)
    }
}
