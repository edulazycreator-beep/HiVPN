package app.hivpn.data.mapper

import app.hivpn.core.database.entity.ProfileEntity
import app.hivpn.core.database.entity.ServerEntity
import app.hivpn.domain.model.ConnectionProfile
import app.hivpn.domain.model.DnsSettings
import app.hivpn.domain.model.Server
import app.hivpn.domain.model.ServerCategory
import app.hivpn.domain.model.ServerStatus
import app.hivpn.domain.model.ServerTier
import app.hivpn.domain.model.SplitTunnelSettings
import app.hivpn.domain.model.VpnProtocol

private inline fun <reified E : Enum<E>> String.toEnumSet(): Set<E> =
    split(',').filter(String::isNotBlank)
        .mapNotNull { name -> enumValues<E>().firstOrNull { it.name == name.trim() } }
        .toSet()

private inline fun <reified E : Enum<E>> String.toEnumOr(default: E): E =
    enumValues<E>().firstOrNull { it.name == this } ?: default

internal fun ServerEntity.toDomain(isFavorite: Boolean) = Server(
    id = id,
    name = name,
    countryCode = countryCode,
    countryName = countryName,
    city = city,
    hostname = hostname,
    port = port,
    publicKey = publicKey,
    protocols = protocols.toEnumSet<VpnProtocol>(),
    tier = tier.toEnumOr(ServerTier.PREMIUM),
    categories = categories.toEnumSet<ServerCategory>(),
    loadPercent = loadPercent,
    status = status.toEnumOr(ServerStatus.OFFLINE),
    isFavorite = isFavorite,
)

internal fun ProfileEntity.toDomain() = ConnectionProfile(
    id = id,
    name = name,
    protocol = protocol.toEnumOr(VpnProtocol.WIREGUARD),
    preferredServerId = preferredServerId,
    dns = if (dnsMode == DNS_CUSTOM && dnsServers.isNotEmpty()) {
        DnsSettings.Custom(dnsServers)
    } else {
        DnsSettings.Automatic
    },
    mtu = mtu,
    splitTunnel = when (splitTunnelMode) {
        SPLIT_ONLY -> SplitTunnelSettings.OnlySelected(splitTunnelPackages.toSet())
        SPLIT_BYPASS -> SplitTunnelSettings.BypassSelected(splitTunnelPackages.toSet())
        else -> SplitTunnelSettings.AllApps
    },
    allowLanAccess = allowLanAccess,
    excludedRoutes = excludedRoutes,
    persistentKeepalive = persistentKeepalive,
)

internal fun ConnectionProfile.toEntity(createdAtEpochMs: Long) = ProfileEntity(
    id = id,
    name = name,
    protocol = protocol.name,
    preferredServerId = preferredServerId,
    dnsMode = if (dns is DnsSettings.Custom) DNS_CUSTOM else DNS_AUTOMATIC,
    dnsServers = (dns as? DnsSettings.Custom)?.servers.orEmpty(),
    mtu = mtu,
    splitTunnelMode = when (splitTunnel) {
        SplitTunnelSettings.AllApps -> SPLIT_ALL
        is SplitTunnelSettings.OnlySelected -> SPLIT_ONLY
        is SplitTunnelSettings.BypassSelected -> SPLIT_BYPASS
    },
    splitTunnelPackages = when (val s = splitTunnel) {
        SplitTunnelSettings.AllApps -> emptyList()
        is SplitTunnelSettings.OnlySelected -> s.packages.sorted()
        is SplitTunnelSettings.BypassSelected -> s.packages.sorted()
    },
    allowLanAccess = allowLanAccess,
    createdAtEpochMs = createdAtEpochMs,
    excludedRoutes = excludedRoutes,
    persistentKeepalive = persistentKeepalive,
)

private const val DNS_AUTOMATIC = "AUTOMATIC"
private const val DNS_CUSTOM = "CUSTOM"
private const val SPLIT_ALL = "ALL"
private const val SPLIT_ONLY = "ONLY_SELECTED"
private const val SPLIT_BYPASS = "BYPASS_SELECTED"
