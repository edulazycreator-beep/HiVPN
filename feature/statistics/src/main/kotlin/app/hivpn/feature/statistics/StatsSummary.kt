package app.hivpn.feature.statistics

import app.hivpn.domain.model.DailyTraffic
import app.hivpn.domain.model.TunnelTraffic
import java.time.LocalDate

data class StatisticsUiState(
    val session: TunnelTraffic? = null,
    /** Last 30 days, oldest first, with missing days filled as zero. */
    val days: List<DailyTraffic> = emptyList(),
    val todayRx: Long = 0,
    val todayTx: Long = 0,
    val monthRx: Long = 0,
    val monthTx: Long = 0,
)

/** Pure aggregation, unit-tested. [rows] may be sparse and in any order. */
internal fun summarize(session: TunnelTraffic?, rows: List<DailyTraffic>, today: LocalDate): StatisticsUiState {
    val byDay = rows.associateBy { it.day }
    val days = (29 downTo 0).map { back ->
        val d = today.minusDays(back.toLong()).toString()
        byDay[d] ?: DailyTraffic(d, 0, 0)
    }
    val monthPrefix = today.toString().substring(0, 7)
    val month = rows.filter { it.day.startsWith(monthPrefix) }
    val todayRow = byDay[today.toString()]
    return StatisticsUiState(
        session = session,
        days = days,
        todayRx = todayRow?.rxBytes ?: 0,
        todayTx = todayRow?.txBytes ?: 0,
        monthRx = month.sumOf { it.rxBytes },
        monthTx = month.sumOf { it.txBytes },
    )
}
