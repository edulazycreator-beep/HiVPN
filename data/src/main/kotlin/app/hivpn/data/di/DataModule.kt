package app.hivpn.data.di

import app.hivpn.data.repository.DataStoreSettingsRepository
import app.hivpn.data.repository.OfflineFirstServerRepository
import app.hivpn.data.repository.RoomProfileRepository
import app.hivpn.data.repository.RoomTrafficHistoryRepository
import app.hivpn.domain.repository.ProfileRepository
import app.hivpn.domain.repository.ServerRepository
import app.hivpn.domain.repository.SettingsRepository
import app.hivpn.domain.repository.TrafficHistoryRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal abstract class DataModule {
    @Binds abstract fun serverRepository(impl: OfflineFirstServerRepository): ServerRepository
    @Binds abstract fun profileRepository(impl: RoomProfileRepository): ProfileRepository
    @Binds abstract fun settingsRepository(impl: DataStoreSettingsRepository): SettingsRepository
    @Binds abstract fun trafficHistory(impl: RoomTrafficHistoryRepository): TrafficHistoryRepository
    @Binds abstract fun diagnostics(impl: app.hivpn.data.repository.DiagnosticsRepositoryImpl): app.hivpn.domain.repository.DiagnosticsRepository
}
