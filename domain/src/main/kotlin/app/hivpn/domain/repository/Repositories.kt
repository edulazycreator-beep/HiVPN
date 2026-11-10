package app.hivpn.domain.repository

import app.hivpn.domain.model.AppSettings
import app.hivpn.domain.model.ConnectionProfile
import app.hivpn.domain.model.DailyTraffic
import app.hivpn.domain.model.ImportedConfig
import app.hivpn.domain.model.Server
import app.hivpn.domain.model.TargetId
import app.hivpn.domain.model.ThemeMode
import app.hivpn.domain.model.TunnelState
import app.hivpn.domain.model.TunnelTraffic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface ServerRepository {
    fun observeServers(): Flow<List<Server>>
    fun observeServer(id: String): Flow<Server?>
    suspend fun setFavorite(id: String, favorite: Boolean)

    /** Fetches and verifies the signed directory. Fails without changing the cache. */
    suspend fun refresh(): Result<Unit>
}

interface ProfileRepository {
    fun observeProfiles(): Flow<List<ConnectionProfile>>
    fun observeProfile(id: Long): Flow<ConnectionProfile?>

    /** Inserts when id == 0, otherwise updates. Returns the profile id. */
    suspend fun upsert(profile: ConnectionProfile): Long
    suspend fun delete(id: Long)

    /** Creates the "Default" profile on first launch; no-op afterwards. */
    suspend fun ensureDefaultProfile(): Long
}

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun setOnboardingCompleted(completed: Boolean)
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setSelectedTarget(target: TargetId?)
    suspend fun setActiveProfile(id: Long?)
    suspend fun setAutoConnectOnLaunch(enabled: Boolean)
    suspend fun setBiometricLock(enabled: Boolean)
}

/** WireGuard configurations the user imported for their own servers. */
interface ImportedConfigRepository {
    fun observeAll(): Flow<List<ImportedConfig>>
    fun observe(id: Long): Flow<ImportedConfig?>

    /**
     * Parses and stores a wg-quick style configuration. The private key and any preshared
     * key go to encrypted storage; only non-secret fields are kept in the database.
     */
    suspend fun import(name: String, configText: String): ImportResult
    suspend fun delete(id: Long)
}

sealed interface ImportResult {
    data class Success(val id: Long) : ImportResult
    data class Invalid(val reason: String) : ImportResult
}

/**
 * Controls the real VPN tunnel. Implemented by :vpn:service; the UI only reads [state]
 * and [traffic] and sends intents through [connect] / [disconnect].
 */
interface VpnConnectionRepository {
    val state: StateFlow<TunnelState>

    /** Counters for the current session; null while no tunnel is up. */
    val traffic: StateFlow<TunnelTraffic?>

    suspend fun connect(profile: ConnectionProfile, target: TargetId)

    suspend fun disconnect()
}

/** Daily tunnel traffic history, kept only on the device. */
interface TrafficHistoryRepository {
    fun observeDaily(days: Int): Flow<List<DailyTraffic>>
    suspend fun clear()
}
