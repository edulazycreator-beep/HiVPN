package app.hivpn.vpn.service

import app.hivpn.core.common.ApplicationScope
import app.hivpn.core.database.dao.TrafficDao
import app.hivpn.domain.model.ConnectionProfile
import app.hivpn.domain.model.TargetId
import app.hivpn.domain.model.TunnelState
import app.hivpn.domain.model.TunnelTraffic
import app.hivpn.domain.repository.VpnConnectionRepository
import app.hivpn.domain.usecase.ConnectUseCase
import app.hivpn.vpn.api.TunnelEngine
import com.wireguard.android.backend.GoBackend
import dagger.Binds
import dagger.Lazy
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's single [VpnConnectionRepository]. Resolves what to connect to, hands it to
 * the right engine, mirrors the engine's state, and records per-day tunnel traffic.
 */
@Singleton
internal class TunnelManager @Inject constructor(
    engines: Set<@JvmSuppressWildcards TunnelEngine>,
    private val resolver: TunnelConfigResolver,
    private val trafficDao: TrafficDao,
    @ApplicationScope private val scope: CoroutineScope,
) : VpnConnectionRepository {

    private val engine: TunnelEngine = engines.single() // WireGuard only; see architecture doc.

    private val _state = MutableStateFlow<TunnelState>(TunnelState.Disconnected)
    override val state: StateFlow<TunnelState> = _state.asStateFlow()
    override val traffic: StateFlow<TunnelTraffic?> = engine.traffic

    private var connectJob: Job? = null

    init {
        scope.launch { engine.state.collect { _state.value = it } }
        scope.launch { recordTraffic() }
    }

    override suspend fun connect(profile: ConnectionProfile, target: TargetId) {
        connectJob?.cancel()
        _state.value = TunnelState.Connecting(target.key)
        connectJob = scope.launch {
            when (val r = resolver.resolve(profile, target)) {
                is TunnelConfigResolver.Resolution.Failed -> _state.value = TunnelState.Failed(r.error)
                is TunnelConfigResolver.Resolution.Ready -> engine.connect(r.config)
            }
        }
    }

    override suspend fun disconnect() {
        connectJob?.cancel()
        engine.disconnect()
        if (_state.value is TunnelState.Connecting || _state.value is TunnelState.Failed) {
            _state.value = TunnelState.Disconnected
        }
    }

    /** Adds session deltas to today's row; counters reset per tunnel, so negatives start a new baseline. */
    private suspend fun recordTraffic() {
        var last: TunnelTraffic? = null
        engine.traffic.collect { t ->
            if (t == null) {
                last = null
                return@collect
            }
            val prev = last
            last = t
            if (prev == null) return@collect
            val rx = t.rxBytes - prev.rxBytes
            val tx = t.txBytes - prev.txBytes
            if (rx < 0 || tx < 0 || (rx == 0L && tx == 0L)) return@collect
            trafficDao.add(LocalDate.now().toString(), rx, tx)
        }
    }
}

/**
 * When the user enables Always-on VPN for Hi VPN, Android starts GoBackend's VpnService
 * itself (at boot or after a crash). This hook turns that into a normal connect to the
 * last selected target.
 */
@Singleton
class AlwaysOnHook @Inject constructor(
    // Lazy: ConnectUseCase depends on VpnConnectionRepository, which is TunnelManager.
    private val connect: Lazy<ConnectUseCase>,
    @ApplicationScope private val scope: CoroutineScope,
) {
    fun install() {
        GoBackend.setAlwaysOnCallback {
            // Returns false when nothing is selected yet; the tunnel then stays down.
            scope.launch { connect.get().invoke() }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class VpnServiceModule {
    @Binds abstract fun vpnConnection(impl: TunnelManager): VpnConnectionRepository
}
