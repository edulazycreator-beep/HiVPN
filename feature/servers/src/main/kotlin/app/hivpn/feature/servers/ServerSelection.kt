package app.hivpn.feature.servers

import app.hivpn.domain.model.Server
import app.hivpn.domain.model.ServerStatus

/** Matches country, city, server name or hostname, case-insensitively. */
internal fun filterServers(servers: List<Server>, query: String): List<Server> {
    val q = query.trim()
    if (q.isEmpty()) return servers
    return servers.filter { s ->
        listOf(s.countryName, s.countryCode, s.city, s.name, s.hostname).any { it.contains(q, ignoreCase = true) }
    }
}

/**
 * Lowest measured latency among online servers; ties broken by lower load. Without
 * measurements, the least-loaded online server. Null when nothing is online.
 */
internal fun recommend(servers: List<Server>, latency: Map<String, Long?>): Server? {
    val online = servers.filter { it.status == ServerStatus.ONLINE }
    val measured = online.mapNotNull { s -> latency[s.id]?.let { s to it } }
    if (measured.isNotEmpty()) {
        return measured.minWith(compareBy({ it.second }, { it.first.loadPercent ?: 100 })).first
    }
    return online.minByOrNull { it.loadPercent ?: 100 }
}
