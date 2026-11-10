package app.hivpn.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hivpn.domain.model.TargetId
import app.hivpn.domain.model.TargetSummary
import app.hivpn.domain.model.TrafficRate
import app.hivpn.domain.model.TunnelState
import app.hivpn.domain.model.TunnelTraffic
import app.hivpn.domain.model.isActive
import app.hivpn.domain.repository.DiagnosticsRepository
import app.hivpn.domain.repository.ImportedConfigRepository
import app.hivpn.domain.repository.LatencyResult
import app.hivpn.domain.repository.PublicIp
import app.hivpn.domain.repository.ServerRepository
import app.hivpn.domain.repository.SettingsRepository
import app.hivpn.domain.repository.VpnConnectionRepository
import app.hivpn.domain.usecase.ConnectUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Measured network facts; null = not measured (shown as a dash, never guessed). */
data class NetworkFacts(
    val publicIp: PublicIp? = null,
    val latency: LatencyResult? = null,
    val measuring: Boolean = false,
)

data class DashboardUiState(
    val tunnel: TunnelState = TunnelState.Disconnected,
    val target: TargetSummary? = null,
    val hasSelectedTarget: Boolean = false,
    val traffic: TunnelTraffic? = null,
    val downHistory: List<Long> = emptyList(),
    val upHistory: List<Long> = emptyList(),
    val facts: NetworkFacts = NetworkFacts(),
    val notice: DashboardNotice? = null,
    val permissionDenied: Boolean = false,
)

enum class DashboardNotice { ChooseServerFirst }

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val vpn: VpnConnectionRepository,
    private val connect: ConnectUseCase,
    private val diagnostics: DiagnosticsRepository,
    settingsRepository: SettingsRepository,
    serverRepository: ServerRepository,
    importedRepository: ImportedConfigRepository,
) : ViewModel() {

    private val notice = MutableStateFlow<DashboardNotice?>(null)
    private val facts = MutableStateFlow(NetworkFacts())
    private val permissionDenied = MutableStateFlow(false)

    private val selectedTarget = settingsRepository.settings.map { it.selectedTarget }.distinctUntilChanged()

    private val targetSummary: Flow<TargetSummary?> = selectedTarget.flatMapLatest { target ->
        when (target) {
            null -> flowOf(null)
            is TargetId.Directory -> serverRepository.observeServer(target.serverId).map { s ->
                s?.let { TargetSummary(target, "${it.city}, ${it.countryName}", it.name) }
            }
            is TargetId.Imported -> importedRepository.observe(target.configId).map { c ->
                c?.let { TargetSummary(target, it.name, it.endpoint) }
            }
        }
    }

    /** Last 60 one-second rate samples; reset whenever the tunnel goes down. */
    private val rates: Flow<List<TrafficRate>> = vpn.traffic
        .runningFold(Pair<TunnelTraffic?, List<TrafficRate>>(null, emptyList())) { (prev, list), t ->
            when {
                t == null -> null to emptyList()
                prev == null -> t to list
                else -> t to (list + TrafficRate.between(prev, t)).takeLast(HISTORY)
            }
        }
        .map { it.second }

    private val core = combine(vpn.state, targetSummary, selectedTarget, vpn.traffic, rates) { s, target, selected, traffic, r ->
        DashboardUiState(
            tunnel = s,
            target = target,
            hasSelectedTarget = selected != null,
            traffic = traffic,
            downHistory = r.map { it.downBytesPerSec },
            upHistory = r.map { it.upBytesPerSec },
        )
    }

    val uiState: StateFlow<DashboardUiState> = combine(core, facts, notice, permissionDenied) { c, f, n, denied ->
        c.copy(facts = f, notice = n, permissionDenied = denied)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    init {
        // Re-measure the public IP and latency whenever the tunnel settles in a new state.
        viewModelScope.launch {
            vpn.state
                .map { it is TunnelState.Connected || it == TunnelState.Disconnected }
                .distinctUntilChanged()
                .collect { settled -> if (settled) refreshFacts() }
        }
    }

    fun refreshFacts() {
        viewModelScope.launch {
            facts.update { it.copy(measuring = true) }
            val ip = diagnostics.publicIp().getOrNull()
            val latency = diagnostics.apiLatency(count = 3)
            facts.value = NetworkFacts(ip, latency, measuring = false)
        }
    }

    /** Called only after Android's VPN consent has been confirmed by the screen. */
    fun onConnectToggle() {
        permissionDenied.value = false
        viewModelScope.launch {
            if (vpn.state.value.isActive) {
                vpn.disconnect()
            } else if (!connect()) {
                notice.value = DashboardNotice.ChooseServerFirst
            }
        }
    }

    /** The user declined Android's VPN consent dialog; shown until the next attempt. */
    fun onConsentDenied() {
        permissionDenied.value = true
    }

    fun onNoticeShown() = notice.update { null }

    val isActive: Boolean get() = vpn.state.value.isActive

    private companion object {
        const val HISTORY = 60
    }
}
