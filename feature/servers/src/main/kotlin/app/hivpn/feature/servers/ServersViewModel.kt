package app.hivpn.feature.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hivpn.domain.model.ImportedConfig
import app.hivpn.domain.model.Server
import app.hivpn.domain.model.ServerStatus
import app.hivpn.domain.model.TargetId
import app.hivpn.domain.repository.AccountRepository
import app.hivpn.domain.repository.AccountState
import app.hivpn.domain.repository.DiagnosticsRepository
import app.hivpn.domain.repository.ImportResult
import app.hivpn.domain.repository.ImportedConfigRepository
import app.hivpn.domain.repository.ServerRepository
import app.hivpn.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

data class CountryGroup(val countryName: String, val countryCode: String, val servers: List<Server>)

data class ServersUiState(
    val loading: Boolean = true,
    val query: String = "",
    val selected: TargetId? = null,
    val imported: List<ImportedConfig> = emptyList(),
    val favorites: List<Server> = emptyList(),
    val recommended: Server? = null,
    val countries: List<CountryGroup> = emptyList(),
    val directoryEmpty: Boolean = false,
    val account: AccountState = AccountState.Unavailable,
    /** Server id → measured TCP latency in ms (null = unreachable). Absent = not measured. */
    val latency: Map<String, Long?> = emptyMap(),
    val busy: Boolean = false,
    val message: ServersMessage? = null,
)

sealed interface ServersMessage {
    data class Imported(val name: String) : ServersMessage
    data class ImportFailed(val reason: String) : ServersMessage
    data object RefreshFailed : ServersMessage
}

@HiltViewModel
class ServersViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    private val settingsRepository: SettingsRepository,
    private val importedRepository: ImportedConfigRepository,
    private val diagnostics: DiagnosticsRepository,
    accountRepository: AccountRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val transient = MutableStateFlow(Transient())

    private data class Transient(
        val latency: Map<String, Long?> = emptyMap(),
        val busy: Boolean = false,
        val message: ServersMessage? = null,
    )

    private val base = combine(
        serverRepository.observeServers(),
        importedRepository.observeAll(),
        settingsRepository.settings,
        accountRepository.state,
        query,
    ) { servers, imported, settings, account, q ->
        val filtered = filterServers(servers, q)
        ServersUiState(
            loading = false,
            query = q,
            selected = settings.selectedTarget,
            imported = imported.filter { q.isBlank() || it.name.contains(q.trim(), ignoreCase = true) },
            favorites = filtered.filter { it.isFavorite },
            countries = filtered
                .groupBy { it.countryCode }
                .map { (code, list) -> CountryGroup(list.first().countryName, code, list.sortedBy { it.city }) }
                .sortedBy { it.countryName },
            directoryEmpty = servers.isEmpty(),
            account = account,
        )
    }

    val uiState: StateFlow<ServersUiState> = combine(base, transient) { b, t ->
        b.copy(
            latency = t.latency,
            busy = t.busy,
            message = t.message,
            recommended = recommend(b.countries.flatMap { it.servers }, t.latency),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ServersUiState())

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun onSelect(target: TargetId, then: () -> Unit) {
        viewModelScope.launch {
            settingsRepository.setSelectedTarget(target)
            then()
        }
    }

    fun onToggleFavorite(server: Server) {
        viewModelScope.launch { serverRepository.setFavorite(server.id, !server.isFavorite) }
    }

    fun onImport(name: String, text: String) {
        viewModelScope.launch {
            transient.update { it.copy(busy = true) }
            val msg = when (val r = importedRepository.import(name, text)) {
                is ImportResult.Success -> {
                    settingsRepository.setSelectedTarget(TargetId.Imported(r.id))
                    ServersMessage.Imported(name.ifBlank { "configuration" })
                }
                is ImportResult.Invalid -> ServersMessage.ImportFailed(r.reason)
            }
            transient.update { it.copy(busy = false, message = msg) }
        }
    }

    fun onDeleteImported(config: ImportedConfig) {
        viewModelScope.launch {
            if (uiState.value.selected == TargetId.Imported(config.id)) settingsRepository.setSelectedTarget(null)
            importedRepository.delete(config.id)
        }
    }

    fun onRefresh() {
        viewModelScope.launch {
            transient.update { it.copy(busy = true) }
            val ok = serverRepository.refresh().isSuccess
            transient.update { it.copy(busy = false, message = if (ok) null else ServersMessage.RefreshFailed) }
        }
    }

    /**
     * Measures TCP connect time to each online server's port 443, where every node runs a
     * small probe listener (WireGuard itself is UDP and does not answer TCP).
     */
    fun onMeasureLatency() {
        viewModelScope.launch {
            transient.update { it.copy(busy = true) }
            val servers = uiState.value.countries.flatMap { it.servers }.filter { it.status == ServerStatus.ONLINE }
            val limit = Semaphore(8)
            val results = servers.map { s ->
                async { limit.withPermit { s.id to diagnostics.tcpLatency(s.hostname, 443, count = 2).minMs } }
            }.awaitAll().toMap()
            transient.update { it.copy(busy = false, latency = results) }
        }
    }

    fun onMessageShown() = transient.update { it.copy(message = null) }
}
