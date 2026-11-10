package app.hivpn.domain.model

/** Counters reported by the tunnel engine for the current session. */
data class TunnelTraffic(
    val rxBytes: Long,
    val txBytes: Long,
    val lastHandshakeEpochMs: Long?,
    val sampledAtEpochMs: Long,
)

/** Bytes per second between two samples. */
data class TrafficRate(val downBytesPerSec: Long, val upBytesPerSec: Long, val atEpochMs: Long) {
    companion object {
        fun between(previous: TunnelTraffic, current: TunnelTraffic): TrafficRate {
            val dtMs = (current.sampledAtEpochMs - previous.sampledAtEpochMs).coerceAtLeast(1)
            val down = (current.rxBytes - previous.rxBytes).coerceAtLeast(0) * 1000 / dtMs
            val up = (current.txBytes - previous.txBytes).coerceAtLeast(0) * 1000 / dtMs
            return TrafficRate(down, up, current.sampledAtEpochMs)
        }
    }
}

/** Traffic attributed to the tunnel for one calendar day (local time). */
data class DailyTraffic(val day: String, val rxBytes: Long, val txBytes: Long)
