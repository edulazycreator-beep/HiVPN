package app.hivpn.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached copy of the signed server directory. Rows are replaced wholesale on each
 * directory refresh (Phase 5); nothing here is ever invented locally.
 */
@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val countryCode: String,
    val countryName: String,
    val city: String,
    val hostname: String,
    val port: Int,
    val publicKey: String,
    /** Comma-separated VpnProtocol names. */
    val protocols: String,
    val tier: String,
    /** Comma-separated ServerCategory names. */
    val categories: String,
    val loadPercent: Int?,
    val status: String,
)

/** Kept separate from [ServerEntity] so favourites survive directory refreshes. */
@Entity(tableName = "favorite_servers")
data class FavoriteServerEntity(
    @PrimaryKey val serverId: String,
    val addedAtEpochMs: Long,
)

/** Non-secret connection preferences. */
@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val protocol: String,
    val preferredServerId: String?,
    /** "AUTOMATIC" or "CUSTOM". */
    val dnsMode: String,
    val dnsServers: List<String>,
    val mtu: Int?,
    /** "ALL", "ONLY_SELECTED" or "BYPASS_SELECTED". */
    val splitTunnelMode: String,
    val splitTunnelPackages: List<String>,
    val allowLanAccess: Boolean,
    val createdAtEpochMs: Long,
    /** Added in schema v2. The default must match MIGRATION_1_2 for Room's schema check. */
    @ColumnInfo(defaultValue = "")
    val excludedRoutes: List<String> = emptyList(),
    /** Added in schema v2. */
    val persistentKeepalive: Int? = null,
)

/**
 * Non-secret part of an imported WireGuard config. Private and preshared keys are in
 * core:security's SecretStore, keyed by this row's id.
 */
@Entity(tableName = "imported_configs")
data class ImportedConfigEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val endpoint: String,
    val interfaceAddresses: List<String>,
    val dnsServers: List<String>,
    val allowedIps: List<String>,
    val mtu: Int?,
    val persistentKeepalive: Int?,
    val peerPublicKey: String,
    val hasPresharedKey: Boolean,
    val createdAtEpochMs: Long,
)

/** Tunnel bytes per local calendar day ("2026-11-09"). Never leaves the device. */
@Entity(tableName = "daily_traffic")
data class DailyTrafficEntity(
    @PrimaryKey val day: String,
    val rxBytes: Long,
    val txBytes: Long,
)
