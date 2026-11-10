package app.hivpn.feature.dashboard

import android.Manifest
import android.app.Activity
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.hivpn.core.designsystem.component.ConnectButton
import app.hivpn.core.designsystem.component.HiCard
import app.hivpn.core.designsystem.component.InfoRow
import app.hivpn.core.designsystem.component.SectionHeader
import app.hivpn.core.designsystem.component.StatusPill
import app.hivpn.core.designsystem.component.StatusTone
import app.hivpn.core.designsystem.component.TrafficGraph
import app.hivpn.core.designsystem.component.formatBytes
import app.hivpn.core.designsystem.component.formatRate
import app.hivpn.domain.model.TunnelError
import app.hivpn.domain.model.TunnelState
import app.hivpn.domain.model.VpnProtocol
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

@Serializable
data object DashboardRoute

fun NavGraphBuilder.dashboardScreen(onChooseServer: () -> Unit) {
    composable<DashboardRoute> { DashboardEntry(onChooseServer) }
}

@Composable
internal fun DashboardEntry(
    onChooseServer: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Android 13+: the tunnel notification needs this runtime permission. Declining it
    // does not block the VPN; it only hides the notification.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // Android's VPN consent dialog. The tunnel is started only after RESULT_OK.
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.onConnectToggle() else viewModel.onConsentDenied()
    }

    DashboardScreen(
        state = state,
        onConnectToggle = {
            if (viewModel.isActive) {
                viewModel.onConnectToggle()
            } else {
                if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                val intent = VpnService.prepare(context)
                if (intent == null) viewModel.onConnectToggle() else consent.launch(intent)
            }
        },
        onChooseServer = onChooseServer,
        onRefreshFacts = viewModel::refreshFacts,
        onNoticeShown = viewModel::onNoticeShown,
    )
}

@Composable
internal fun DashboardScreen(
    state: DashboardUiState,
    onConnectToggle: () -> Unit,
    onChooseServer: () -> Unit,
    onRefreshFacts: () -> Unit,
    onNoticeShown: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val chooseServerText = stringResource(R.string.dash_choose_server_first)
    LaunchedEffect(state.notice) {
        if (state.notice == DashboardNotice.ChooseServerFirst) {
            snackbar.showSnackbar(chooseServerText)
            onNoticeShown()
        }
    }

    val view = state.tunnel.toView()
    val connected = state.tunnel as? TunnelState.Connected
    val lastDown = state.downHistory.lastOrNull()
    val lastUp = state.upHistory.lastOrNull()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.dash_brand), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.dash_tagline),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(stringResource(view.pill), view.tone)
            }

            Spacer(Modifier.height(32.dp))

            ConnectButton(
                label = stringResource(view.action),
                stateDescription = stringResource(view.headline),
                tone = view.tone,
                enabled = state.tunnel !is TunnelState.Disconnecting,
                onClick = onConnectToggle,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(view.headline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            val errorText = when {
                state.permissionDenied -> stringResource(R.string.err_permission)
                state.tunnel is TunnelState.Failed -> stringResource(state.tunnel.error.messageRes())
                else -> null
            }
            errorText?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(horizontal = 16.dp),
                )
            }

            SectionHeader(stringResource(R.string.dash_section_connection))
            HiCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.dash_server),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = state.target?.title ?: stringResource(
                                if (state.hasSelectedTarget) R.string.dash_server_unavailable else R.string.dash_no_server,
                            ),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        state.target?.subtitle?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    TextButton(onClick = onChooseServer) { Text(stringResource(R.string.dash_change)) }
                }
                InfoRow(stringResource(R.string.dash_protocol), VpnProtocol.WIREGUARD.displayName)
                InfoRow(stringResource(R.string.dash_encryption), stringResource(R.string.dash_encryption_value))
                InfoRow(
                    stringResource(R.string.dash_tunnel_address),
                    connected?.tunnelAddresses?.joinToString("\n") ?: DASH,
                    mono = true,
                )
                InfoRow(stringResource(R.string.dash_duration), rememberDuration(state.tunnel), mono = true)
            }

            SectionHeader(stringResource(R.string.dash_section_traffic))
            HiCard {
                if (connected != null && state.downHistory.size >= 2) {
                    TrafficGraph(
                        down = state.downHistory,
                        up = state.upHistory,
                        description = stringResource(R.string.dash_graph_description),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                InfoRow(stringResource(R.string.dash_download), lastDown?.let(::formatRate) ?: DASH, mono = true)
                InfoRow(stringResource(R.string.dash_upload), lastUp?.let(::formatRate) ?: DASH, mono = true)
                InfoRow(
                    stringResource(R.string.dash_session_data),
                    state.traffic?.let { "↓ ${formatBytes(it.rxBytes)}  ↑ ${formatBytes(it.txBytes)}" } ?: DASH,
                    mono = true,
                )
            }

            SectionHeader(stringResource(R.string.dash_section_network))
            HiCard {
                InfoRow(
                    stringResource(R.string.dash_public_ip),
                    state.facts.publicIp?.let { ip -> ip.address + (ip.country?.let { " · $it" } ?: "") } ?: DASH,
                    mono = true,
                )
                InfoRow(
                    stringResource(R.string.dash_latency),
                    state.facts.latency?.avgMs?.let { "$it ms" } ?: DASH,
                    mono = true,
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(if (connected != null) R.string.dash_facts_via_vpn else R.string.dash_facts_direct),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRefreshFacts, enabled = !state.facts.measuring) {
                        Text(stringResource(R.string.dash_refresh))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private const val DASH = "—"

/** Ticks once per second, only while connected. */
@Composable
private fun rememberDuration(tunnel: TunnelState): String {
    val since = (tunnel as? TunnelState.Connected)?.connectedAtEpochMs
    val text by produceState(initialValue = DASH, since) {
        if (since == null) {
            value = DASH
            return@produceState
        }
        while (true) {
            value = formatDuration(System.currentTimeMillis() - since)
            delay(1_000)
        }
    }
    return text
}


private data class TunnelView(val tone: StatusTone, val pill: Int, val headline: Int, val action: Int)

private fun TunnelState.toView(): TunnelView = when (this) {
    TunnelState.Disconnected -> TunnelView(StatusTone.Unprotected, R.string.pill_unprotected, R.string.state_disconnected, R.string.action_connect)
    is TunnelState.Connecting -> TunnelView(StatusTone.InProgress, R.string.pill_connecting, R.string.state_connecting, R.string.action_cancel)
    is TunnelState.Connected -> TunnelView(StatusTone.Protected, R.string.pill_protected, R.string.state_connected, R.string.action_disconnect)
    is TunnelState.Reconnecting -> TunnelView(StatusTone.InProgress, R.string.pill_connecting, R.string.state_reconnecting, R.string.action_disconnect)
    TunnelState.Disconnecting -> TunnelView(StatusTone.InProgress, R.string.pill_unprotected, R.string.state_disconnecting, R.string.action_disconnect)
    is TunnelState.Failed -> TunnelView(StatusTone.Error, R.string.pill_unprotected, R.string.state_failed, R.string.action_retry)
}

private fun TunnelError.messageRes(): Int = when (this) {
    TunnelError.PermissionDenied -> R.string.err_permission
    TunnelError.EngineUnavailable -> R.string.err_engine_unavailable
    TunnelError.RevokedBySystem -> R.string.err_revoked
    TunnelError.NoServerSelected -> R.string.dash_choose_server_first
    TunnelError.HandshakeTimeout -> R.string.err_handshake
    TunnelError.AccountRequired -> R.string.err_account
    TunnelError.DeviceLimitReached -> R.string.err_device_limit
    is TunnelError.ApiUnavailable -> R.string.err_api
    is TunnelError.InvalidConfiguration -> R.string.err_config
    is TunnelError.Unknown -> R.string.err_unknown
}
