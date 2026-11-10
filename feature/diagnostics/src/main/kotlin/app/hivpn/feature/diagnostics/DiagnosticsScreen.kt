package app.hivpn.feature.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.hivpn.core.designsystem.component.HiCard
import app.hivpn.core.designsystem.component.InfoRow
import app.hivpn.core.designsystem.component.SectionHeader
import app.hivpn.core.designsystem.component.StatusPill
import app.hivpn.core.designsystem.component.StatusTone
import app.hivpn.domain.model.TunnelState
import app.hivpn.domain.repository.DiagnosticsRepository
import app.hivpn.domain.repository.LatencyResult
import app.hivpn.domain.repository.NetworkSnapshot
import app.hivpn.domain.repository.PublicIp
import app.hivpn.domain.repository.SpeedResult
import app.hivpn.domain.repository.VpnConnectionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.Locale
import javax.inject.Inject

@Serializable data object DiagnosticsRoute

fun NavGraphBuilder.diagnosticsScreen(onBack: () -> Unit) {
    composable<DiagnosticsRoute> { DiagnosticsEntry(onBack) }
}

data class DiagnosticsUiState(
    val running: Boolean = false,
    val tunnel: TunnelState = TunnelState.Disconnected,
    val network: NetworkSnapshot? = null,
    val publicIp: Result<PublicIp>? = null,
    val resolve: Result<List<String>>? = null,
    val latency: LatencyResult? = null,
    val download: Result<SpeedResult>? = null,
    val upload: Result<SpeedResult>? = null,
    val findings: List<Finding> = emptyList(),
)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val diagnostics: DiagnosticsRepository,
    private val vpn: VpnConnectionRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(DiagnosticsUiState())
    val uiState: StateFlow<DiagnosticsUiState> = _state.asStateFlow()

    init { quick() }

    /** Cheap, local checks: network snapshot and privacy findings. */
    fun quick() {
        val net = diagnostics.network()
        val tunnel = vpn.state.value
        _state.update { it.copy(network = net, tunnel = tunnel, findings = privacyFindings(tunnel, net)) }
    }

    /** Full run including network calls. Speed tests transfer about 35 MB. */
    fun runAll(includeSpeed: Boolean) {
        if (_state.value.running) return
        viewModelScope.launch {
            _state.update { DiagnosticsUiState(running = true) }
            quick()
            _state.update { it.copy(publicIp = diagnostics.publicIp()) }
            _state.update { it.copy(resolve = diagnostics.resolve(PROBE_HOST)) }
            _state.update { it.copy(latency = diagnostics.apiLatency(count = 10)) }
            if (includeSpeed) {
                _state.update { it.copy(download = diagnostics.downloadTest(25L * 1024 * 1024)) }
                _state.update { it.copy(upload = diagnostics.uploadTest(10L * 1024 * 1024)) }
            }
            _state.update { it.copy(running = false) }
        }
    }

    fun report(): String = buildReport(_state.value)

    companion object {
        /** Resolved to confirm DNS works through the current network. */
        const val PROBE_HOST = "example.com"
    }
}

internal fun buildReport(s: DiagnosticsUiState): String = buildString {
    appendLine("Hi VPN diagnostics")
    appendLine("Tunnel: ${s.tunnel::class.simpleName}")
    s.network?.let { n ->
        appendLine("Network: ${n.transport} ${n.interfaceName.orEmpty()} metered=${n.metered} mtu=${n.mtu ?: "?"}")
        appendLine("Addresses: ${n.addresses.joinToString()}")
        appendLine("DNS: ${n.dnsServers.joinToString()} privateDns=${n.privateDnsActive}")
        appendLine("Default routes: v4=${n.hasIpv4Route} v6=${n.hasIpv6Route}")
    }
    s.publicIp?.let { r -> appendLine("Public IP: " + (r.getOrNull()?.address ?: "failed")) }
    s.latency?.let { l -> appendLine("Latency: min ${l.minMs} avg ${l.avgMs} max ${l.maxMs} jitter ${l.jitterMs} loss ${l.lossPercent}%") }
    s.download?.getOrNull()?.let { appendLine(String.format(Locale.ROOT, "Download: %.1f Mbps", it.megabitsPerSecond)) }
    s.upload?.getOrNull()?.let { appendLine(String.format(Locale.ROOT, "Upload: %.1f Mbps", it.megabitsPerSecond)) }
    s.findings.forEach { appendLine("Check ${it.id}: ${it.verdict}${it.detail?.let { d -> " ($d)" } ?: ""}") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticsEntry(onBack: () -> Unit, vm: DiagnosticsViewModel = hiltViewModel()) {
    val s by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diag_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.diag_back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            if (s.running) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row {
                Button(onClick = { vm.runAll(includeSpeed = false) }, enabled = !s.running) { Text(stringResource(R.string.diag_run)) }
                Spacer(Modifier.padding(4.dp))
                OutlinedButton(onClick = { vm.runAll(includeSpeed = true) }, enabled = !s.running) { Text(stringResource(R.string.diag_run_speed)) }
            }
            Text(stringResource(R.string.diag_speed_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            SectionHeader(stringResource(R.string.diag_privacy))
            HiCard {
                s.findings.forEach { f -> FindingRow(f) }
                Text(stringResource(R.string.diag_privacy_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            SectionHeader(stringResource(R.string.diag_ip))
            HiCard {
                InfoRow(stringResource(R.string.diag_public_ip), s.publicIp.text { it.address }, mono = true)
                InfoRow(stringResource(R.string.diag_country), s.publicIp.text { it.country ?: "—" })
                InfoRow(stringResource(R.string.diag_dns_resolve), s.resolve.text { it.joinToString("\n") }, mono = true)
            }

            SectionHeader(stringResource(R.string.diag_connection))
            HiCard {
                val l = s.latency
                InfoRow(stringResource(R.string.diag_latency), l?.avgMs?.let { "${l.minMs} / $it / ${l.maxMs} ms" } ?: DASH, mono = true)
                InfoRow(stringResource(R.string.diag_jitter), l?.jitterMs?.let { "$it ms" } ?: DASH, mono = true)
                InfoRow(stringResource(R.string.diag_loss), l?.let { "${it.lossPercent}%" } ?: DASH, mono = true)
                InfoRow(stringResource(R.string.diag_download), s.download.text { String.format(Locale.ROOT, "%.1f Mbps", it.megabitsPerSecond) }, mono = true)
                InfoRow(stringResource(R.string.diag_upload), s.upload.text { String.format(Locale.ROOT, "%.1f Mbps", it.megabitsPerSecond) }, mono = true)
                if (l == null && !s.running && s.publicIp != null) {
                    Text(stringResource(R.string.diag_no_api), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            SectionHeader(stringResource(R.string.diag_network))
            HiCard {
                val n = s.network
                InfoRow(stringResource(R.string.diag_transport), n?.transport ?: DASH)
                InfoRow(stringResource(R.string.diag_interface), n?.interfaceName ?: DASH, mono = true)
                InfoRow(stringResource(R.string.diag_addresses), n?.addresses?.joinToString("\n")?.ifEmpty { DASH } ?: DASH, mono = true)
                InfoRow(stringResource(R.string.diag_dns), n?.dnsServers?.joinToString("\n")?.ifEmpty { DASH } ?: DASH, mono = true)
                InfoRow(stringResource(R.string.diag_routes), n?.let { "IPv4 ${yes(it.hasIpv4Route)} · IPv6 ${yes(it.hasIpv6Route)}" } ?: DASH)
                InfoRow(stringResource(R.string.diag_mtu), n?.mtu?.toString() ?: DASH, mono = true)
                InfoRow(stringResource(R.string.diag_metered), n?.let { yes(it.metered) } ?: DASH)
            }

            OutlinedButton(
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Hi VPN diagnostics", vm.report()))
                },
                modifier = Modifier.padding(vertical = 16.dp),
            ) { Text(stringResource(R.string.diag_copy)) }
            Text(stringResource(R.string.diag_copy_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
        }
    }
}

private const val DASH = "—"

private fun yes(b: Boolean) = if (b) "yes" else "no"

private inline fun <T> Result<T>?.text(ok: (T) -> String): String = when {
    this == null -> DASH
    isSuccess -> ok(getOrThrow())
    else -> "failed"
}

@Composable
private fun FindingRow(f: Finding) {
    val (tone, label) = when (f.verdict) {
        Verdict.PASS -> StatusTone.Protected to R.string.diag_pass
        Verdict.WARN -> StatusTone.InProgress to R.string.diag_warn
        Verdict.FAIL -> StatusTone.Error to R.string.diag_fail
        Verdict.INFO -> StatusTone.Unprotected to R.string.diag_info
    }
    Column(Modifier.padding(vertical = 6.dp)) {
        Row {
            Text(
                stringResource(
                    when (f.id) {
                        CheckId.TUNNEL_ROUTE -> R.string.diag_check_route
                        CheckId.DNS_VIA_TUNNEL -> R.string.diag_check_dns
                        CheckId.PRIVATE_DNS -> R.string.diag_check_private_dns
                        CheckId.IPV6 -> R.string.diag_check_ipv6
                        CheckId.WEBRTC -> R.string.diag_check_webrtc
                    },
                ),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            StatusPill(stringResource(label), tone)
        }
        val explain = when (f.id) {
            CheckId.PRIVATE_DNS -> if (f.verdict == Verdict.WARN) R.string.diag_explain_private_dns else null
            CheckId.IPV6 -> if (f.verdict == Verdict.WARN) R.string.diag_explain_ipv6 else null
            CheckId.WEBRTC -> R.string.diag_explain_webrtc
            CheckId.TUNNEL_ROUTE -> if (f.verdict == Verdict.FAIL) R.string.diag_explain_route else null
            CheckId.DNS_VIA_TUNNEL -> null
        }
        f.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        explain?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
