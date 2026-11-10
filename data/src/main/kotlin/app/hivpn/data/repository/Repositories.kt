package app.hivpn.data.repository

import app.hivpn.core.database.dao.FavoriteServerDao
import app.hivpn.core.database.dao.ProfileDao
import app.hivpn.core.database.dao.ServerDao
import app.hivpn.core.database.dao.TrafficDao
import app.hivpn.core.database.entity.FavoriteServerEntity
import app.hivpn.core.database.entity.ServerEntity
import app.hivpn.core.datastore.AppPreferencesDataSource
import app.hivpn.core.network.DirectoryClient
import app.hivpn.data.mapper.toDomain
import app.hivpn.data.mapper.toEntity
import app.hivpn.domain.model.AppSettings
import app.hivpn.domain.model.ConnectionProfile
import app.hivpn.domain.model.DailyTraffic
import app.hivpn.domain.model.Server
import app.hivpn.domain.model.TargetId
import app.hivpn.domain.model.ThemeMode
import app.hivpn.domain.repository.ProfileRepository
import app.hivpn.domain.repository.ServerRepository
import app.hivpn.domain.repository.SettingsRepository
import app.hivpn.domain.repository.TrafficHistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline-first server list: the UI reads the Room cache; [refresh] replaces the cache
 * only after the directory's signature has been verified by [DirectoryClient].
 * No server is ever shipped with the app.
 */
@Singleton
internal class OfflineFirstServerRepository @Inject constructor(
    private val serverDao: ServerDao,
    private val favoriteDao: FavoriteServerDao,
    private val directory: DirectoryClient,
) : ServerRepository {

    override fun observeServers(): Flow<List<Server>> =
        combine(serverDao.observeAll(), favoriteDao.observeIds()) { servers, favorites ->
            val favoriteSet = favorites.toSet()
            servers.map { it.toDomain(isFavorite = it.id in favoriteSet) }
        }

    override fun observeServer(id: String): Flow<Server?> =
        combine(serverDao.observe(id), favoriteDao.observeIds()) { server, favorites ->
            server?.toDomain(isFavorite = server.id in favorites)
        }

    override suspend fun setFavorite(id: String, favorite: Boolean) {
        if (favorite) {
            favoriteDao.add(FavoriteServerEntity(id, System.currentTimeMillis()))
        } else {
            favoriteDao.remove(id)
        }
    }

    override suspend fun refresh(): Result<Unit> = directory.fetchVerified().map { dir ->
        serverDao.replaceAll(
            dir.servers.map { s ->
                ServerEntity(
                    id = s.id,
                    name = s.name,
                    countryCode = s.countryCode,
                    countryName = s.countryName,
                    city = s.city,
                    hostname = s.hostname,
                    port = s.port,
                    publicKey = s.publicKey,
                    protocols = s.protocols.joinToString(","),
                    tier = s.tier,
                    categories = s.categories.joinToString(","),
                    loadPercent = s.loadPercent,
                    status = s.status,
                )
            },
        )
    }
}

@Singleton
internal class RoomProfileRepository @Inject constructor(
    private val profileDao: ProfileDao,
) : ProfileRepository {

    private val defaultProfileLock = Mutex()

    override fun observeProfiles(): Flow<List<ConnectionProfile>> =
        profileDao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeProfile(id: Long): Flow<ConnectionProfile?> =
        profileDao.observe(id).map { it?.toDomain() }

    override suspend fun upsert(profile: ConnectionProfile): Long {
        val now = System.currentTimeMillis()
        return if (profile.id == 0L) {
            profileDao.insert(profile.toEntity(createdAtEpochMs = now))
        } else {
            val existing = profileDao.observe(profile.id).first()
            profileDao.upsert(profile.toEntity(createdAtEpochMs = existing?.createdAtEpochMs ?: now))
            profile.id
        }
    }

    override suspend fun delete(id: Long) = profileDao.delete(id)

    override suspend fun ensureDefaultProfile(): Long = defaultProfileLock.withLock {
        profileDao.firstId() ?: profileDao.insert(
            ConnectionProfile.default().toEntity(createdAtEpochMs = System.currentTimeMillis()),
        )
    }
}

@Singleton
internal class DataStoreSettingsRepository @Inject constructor(
    private val source: AppPreferencesDataSource,
) : SettingsRepository {

    override val settings: Flow<AppSettings> = source.preferences.map { p ->
        AppSettings(
            onboardingCompleted = p.onboardingCompleted,
            themeMode = ThemeMode.entries.firstOrNull { it.name == p.themeMode } ?: ThemeMode.SYSTEM,
            selectedTarget = TargetId.parse(p.selectedTarget),
            activeProfileId = p.activeProfileId,
            autoConnectOnLaunch = p.autoConnectOnLaunch,
            biometricLock = p.biometricLock,
        )
    }

    override suspend fun setOnboardingCompleted(completed: Boolean) = source.setOnboardingCompleted(completed)
    override suspend fun setThemeMode(mode: ThemeMode) = source.setThemeMode(mode.name)
    override suspend fun setSelectedTarget(target: TargetId?) = source.setSelectedTarget(target?.key)
    override suspend fun setActiveProfile(id: Long?) = source.setActiveProfile(id)
    override suspend fun setAutoConnectOnLaunch(enabled: Boolean) = source.setAutoConnectOnLaunch(enabled)
    override suspend fun setBiometricLock(enabled: Boolean) = source.setBiometricLock(enabled)
}

@Singleton
internal class RoomTrafficHistoryRepository @Inject constructor(
    private val trafficDao: TrafficDao,
) : TrafficHistoryRepository {
    override fun observeDaily(days: Int): Flow<List<DailyTraffic>> =
        trafficDao.observeRecent(days).map { rows -> rows.map { DailyTraffic(it.day, it.rxBytes, it.txBytes) } }

    override suspend fun clear() = trafficDao.clear()
}
