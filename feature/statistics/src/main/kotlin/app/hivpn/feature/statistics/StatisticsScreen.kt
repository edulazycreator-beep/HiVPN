package app.hivpn.feature.statistics

import android.net.TrafficStats
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.hivpn.core.designsystem.component.BarChart
import app.hivpn.core.designsystem.component.HiCard
import app.hivpn.core.designsystem.component.InfoRow
import app.hivpn.core.designsystem.component.SectionHeader
import app.hivpn.core.designsystem.component.formatBytes
import app.hivpn.domain.model.DailyTraffic
import app.hivpn.domain.model.TunnelTraffic
import app.hivpn.domain.repository.TrafficHistoryRepository
import app.hivpn.domain.repository.VpnConnectionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.LocalDate
import javax.inject.Inject

@Serializable data object StatisticsRoute

fun NavGraphBuilder.statisticsScreen() {
    composable<StatisticsRoute> { StatisticsEntry() }
}

@HiltViewModel
class StatisticsViewModel @Inject constructor(
    private val history: TrafficHistoryRepository,
    vpn: VpnConnectionRepository,
) : ViewModel() {

    val uiState: StateFlow<StatisticsUiState> = combine(vpn.traffic, history.observeDaily(31)) { session, rows ->
        summarize(session, rows, LocalDate.now())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatisticsUiState())

    fun clear() = viewModelScope.launch { history.clear() }
}

@Composable
private fun StatisticsEntry(vm: StatisticsViewModel = hiltViewModel()) {
    val s by vm.uiState.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(stringResource(R.string.stats_title), style = MaterialTheme.typography.headlineSmall)

            SectionHeader(stringResource(R.string.stats_tunnel))
            HiCard {
                InfoRow(stringResource(R.string.stats_session), s.session?.let { "↓ ${formatBytes(it.rxBytes)}  ↑ ${formatBytes(it.txBytes)}" } ?: "—", mono = true)
                InfoRow(stringResource(R.string.stats_today), "↓ ${formatBytes(s.todayRx)}  ↑ ${formatBytes(s.todayTx)}", mono = true)
                InfoRow(stringResource(R.string.stats_month), "↓ ${formatBytes(s.monthRx)}  ↑ ${formatBytes(s.monthTx)}", mono = true)
            }

            SectionHeader(stringResource(R.string.stats_30_days))
            HiCard {
                BarChart(
                    values = s.days.map { it.rxBytes + it.txBytes },
                    description = stringResource(R.string.stats_chart_description),
                )
                Text(
                    stringResource(R.string.stats_chart_caption),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            SectionHeader(stringResource(R.string.stats_device))
            HiCard {
                val rx = TrafficStats.getTotalRxBytes().takeIf { it != TrafficStats.UNSUPPORTED.toLong() }
                val tx = TrafficStats.getTotalTxBytes().takeIf { it != TrafficStats.UNSUPPORTED.toLong() }
                InfoRow(stringResource(R.string.stats_device_since_boot), if (rx != null && tx != null) "↓ ${formatBytes(rx)}  ↑ ${formatBytes(tx)}" else "—", mono = true)
                Text(stringResource(R.string.stats_device_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            TextButton(onClick = { confirmClear = true }, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.stats_clear)) }
            Text(stringResource(R.string.stats_local_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.stats_clear_title)) },
            text = { Text(stringResource(R.string.stats_clear_body)) },
            confirmButton = { TextButton(onClick = { confirmClear = false; vm.clear() }) { Text(stringResource(R.string.stats_clear)) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.stats_cancel)) } },
        )
    }
}
