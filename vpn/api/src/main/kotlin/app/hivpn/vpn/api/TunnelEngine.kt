package app.hivpn.vpn.api

import app.hivpn.domain.model.SplitTunnelSettings
import app.hivpn.domain.model.TunnelState
import app.hivpn.domain.model.TunnelTraffic
import app.hivpn.domain.model.VpnProtocol
import kotlinx.coroutines.flow.StateFlow

/**
 * Protocol-level engine contract. Each protocol lives in its own module and implements
 * this; :vpn:service picks one by [protocol].
 *
 * Implementations must report [TunnelState.Connected] only after real evidence that
 * traffic can flow (for WireGuard: a completed handshake).
 */
interface TunnelEngine {
    val protocol: VpnProtocol
    val state: StateFlow<TunnelState>
    val traffic: StateFlow<TunnelTraffic?>

    /** Brings the tunnel up (or replaces the running one). Returns when it is up or has failed. */
    suspend fun connect(config: TunnelConfig)
    suspend fun disconnect()
}

/**
 * Everything an engine needs to bring a tunnel up. Holds no key material: the engine
 * reads [privateKeyAlias] / [presharedKeyAlias] from core:security's SecretStore.
 */
data class TunnelConfig(
    val targetKey: String,
    val endpointHost: String,
    val endpointPort: Int,
    val serverPublicKey: String,
    val privateKeyAlias: String,
    val presharedKeyAlias: String?,
    /** e.g. 10.64.12.3/32 and fd64::c03/128 */
    val interfaceAddresses: List<String>,
    val dnsServers: List<String>,
    val mtu: Int?,
    val persistentKeepaliveSeconds: Int?,
    /** Final routes after LAN bypass and exclusions (see domain Routes.plan). */
    val allowedIps: List<String>,
    val splitTunnel: SplitTunnelSettings,
)

/** Host and port from "host:port" or "[v6]:port". */
fun parseEndpoint(endpoint: String): Pair<String, Int>? {
    val text = endpoint.trim()
    return if (text.startsWith("[")) {
        val close = text.indexOf(']')
        if (close < 0) return null
        val port = text.substring(close + 1).removePrefix(":").toIntOrNull() ?: return null
        text.substring(1, close) to port
    } else {
        val idx = text.lastIndexOf(':')
        if (idx <= 0) return null
        val port = text.substring(idx + 1).toIntOrNull() ?: return null
        text.substring(0, idx) to port
    }.takeIf { it.second in 1..65535 && it.first.isNotEmpty() }
}
