package app.hivpn.feature.statistics

import app.hivpn.domain.model.DailyTraffic
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class StatsSummaryTest {
    @Test
    fun `fills missing days and totals the current month only`() {
        val today = LocalDate.parse("2026-11-09")
        val rows = listOf(
            DailyTraffic("2026-11-09", 100, 10),
            DailyTraffic("2026-11-01", 50, 5),
            DailyTraffic("2026-10-31", 1_000, 1_000), // previous month
        )
        val s = summarize(null, rows, today)
        assertEquals(30, s.days.size)
        assertEquals("2026-10-11", s.days.first().day)
        assertEquals("2026-11-09", s.days.last().day)
        assertEquals(110L, s.days.last().rxBytes + s.days.last().txBytes)
        assertEquals(0L, s.days[s.days.size - 2].rxBytes) // 2026-11-08 had no traffic
        assertEquals(100L, s.todayRx)
        assertEquals(150L, s.monthRx)
        assertEquals(15L, s.monthTx)
    }
}
