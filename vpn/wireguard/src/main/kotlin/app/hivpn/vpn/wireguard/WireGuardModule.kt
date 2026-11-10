package app.hivpn.vpn.wireguard

import app.hivpn.domain.repository.ImportedConfigRepository
import app.hivpn.vpn.api.TunnelEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
internal abstract class WireGuardModule {
    @Binds @IntoSet abstract fun engine(impl: WireGuardEngine): TunnelEngine
    @Binds abstract fun importedConfigs(impl: WgImportedConfigRepository): ImportedConfigRepository
}
