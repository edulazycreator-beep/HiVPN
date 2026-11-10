package app.hivpn.domain.model

/**
 * What a connection points at: a server from the signed directory, or a WireGuard
 * configuration the user imported for their own server.
 */
sealed interface TargetId {
    val key: String

    data class Directory(val serverId: String) : TargetId {
        override val key get() = "dir:$serverId"
    }

    data class Imported(val configId: Long) : TargetId {
        override val key get() = "imp:$configId"
    }

    companion object {
        fun parse(key: String?): TargetId? = when {
            key == null -> null
            key.startsWith("dir:") -> key.removePrefix("dir:").takeIf { it.isNotEmpty() }?.let(::Directory)
            key.startsWith("imp:") -> key.removePrefix("imp:").toLongOrNull()?.let(::Imported)
            else -> null
        }
    }
}

/**
 * Non-secret description of an imported WireGuard configuration. The private key and
 * any preshared key live only in encrypted storage, never in this object.
 */
data class ImportedConfig(
    val id: Long,
    val name: String,
    val endpoint: String,
    val interfaceAddresses: List<String>,
    val dnsServers: List<String>,
    val allowedIps: List<String>,
    val mtu: Int?,
    val persistentKeepalive: Int?,
    val peerPublicKey: String,
    val hasPresharedKey: Boolean,
)

/** Human-readable info for the dashboard, regardless of target type. */
data class TargetSummary(
    val target: TargetId,
    val title: String,
    val subtitle: String,
)
