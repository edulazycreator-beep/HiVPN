package app.hivpn.domain.model

/**
 * A saved set of connection preferences. Profiles never contain secrets, so they can be
 * exported as-is; keys live in core:security (Phase 3).
 */
data class ConnectionProfile(
    val id: Long,
    val name: String,
    val protocol: VpnProtocol,
    val preferredServerId: String?,
    val dns: DnsSettings,
    /** Null = automatic (engine default, 1420 for WireGuard). */
    val mtu: Int?,
    val splitTunnel: SplitTunnelSettings,
    val allowLanAccess: Boolean,
    /** CIDRs that always bypass the tunnel (e.g. a work subnet). */
    val excludedRoutes: List<String> = emptyList(),
    /** Seconds; null keeps the engine default (25 s for directory servers). */
    val persistentKeepalive: Int? = null,
) {
    companion object {
        const val MTU_MIN = 1280
        const val MTU_MAX = 1500

        fun default(id: Long = 0, name: String = "Default") = ConnectionProfile(
            id = id,
            name = name,
            protocol = VpnProtocol.WIREGUARD,
            preferredServerId = null,
            dns = DnsSettings.Automatic,
            mtu = null,
            splitTunnel = SplitTunnelSettings.AllApps,
            allowLanAccess = false,
        )
    }
}

sealed interface DnsSettings {
    /** Use the resolver on the VPN node (encrypted upstream, no client-side config). */
    data object Automatic : DnsSettings

    data class Custom(val servers: List<String>) : DnsSettings
}

enum class DnsPreset(val displayName: String, val servers: List<String>) {
    CLOUDFLARE("Cloudflare", listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001")),
    GOOGLE("Google Public DNS", listOf("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844")),
    QUAD9("Quad9", listOf("9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9")),
}

/**
 * Android's VpnService.Builder accepts either an allowlist or a denylist of apps per
 * tunnel, never both, which is why this is a single mode rather than two lists.
 */
sealed interface SplitTunnelSettings {
    data object AllApps : SplitTunnelSettings
    data class OnlySelected(val packages: Set<String>) : SplitTunnelSettings
    data class BypassSelected(val packages: Set<String>) : SplitTunnelSettings
}
