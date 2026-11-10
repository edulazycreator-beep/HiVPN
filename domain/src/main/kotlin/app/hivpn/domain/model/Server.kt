package app.hivpn.domain.model

/**
 * A VPN server as delivered by the signed server directory (Phase 5).
 * Hi VPN never ships built-in servers: until a directory is configured, the list is empty.
 */
data class Server(
    val id: String,
    val name: String,
    val countryCode: String,
    val countryName: String,
    val city: String,
    val hostname: String,
    val port: Int,
    val publicKey: String,
    val protocols: Set<VpnProtocol>,
    val tier: ServerTier,
    val categories: Set<ServerCategory>,
    /** Load in percent as reported by the node, or null when unknown. */
    val loadPercent: Int?,
    val status: ServerStatus,
    val isFavorite: Boolean,
)

enum class VpnProtocol(val displayName: String) {
    WIREGUARD("WireGuard"),
}

enum class ServerTier { FREE, PREMIUM, PRO }

enum class ServerCategory { STANDARD, HIGH_SPEED, LOW_LATENCY, STREAMING, P2P, DEDICATED_IP }

enum class ServerStatus { ONLINE, MAINTENANCE, OFFLINE }
