package app.hivpn.feature.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatDurationTest {
    @Test
    fun formats() {
        assertEquals("00:00:00", formatDuration(0))
        assertEquals("00:00:59", formatDuration(59_999))
        assertEquals("01:01:01", formatDuration(3_661_000))
        assertEquals("27:46:40", formatDuration(100_000_000))
    }

    @Test
    fun `negative values clamp to zero`() {
        assertEquals("00:00:00", formatDuration(-5_000))
    }
}
