package app.hivpn.vpn.wireguard

import android.content.Context
import app.hivpn.core.common.ApplicationScope
import app.hivpn.core.common.Dispatcher
import app.hivpn.core.common.HiDispatchers
import app.hivpn.core.security.HiLog
import app.hivpn.core.security.SecretStore
import app.hivpn.domain.model.SplitTunnelSettings
import app.hivpn.domain.model.TunnelError
import app.hivpn.domain.model.TunnelState
import app.hivpn.domain.model.TunnelTraffic
import app.hivpn.domain.model.VpnProtocol
import app.hivpn.vpn.api.TunnelConfig
import app.hivpn.vpn.api.TunnelEngine
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.BadConfigException
import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.crypto.Key
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WireGuard engine on the official userspace backend (GoBackend / wireguard-go).
 *
 * State rules:
 * - Connected only after the peer's first handshake (latest handshake > 0).
 * - No handshake within [HANDSHAKE_TIMEOUT_MS] → tunnel torn down, Failed(HandshakeTimeout).
 * - While connected, a handshake older than [STALE_HANDSHAKE_MS] (WireGuard re-keys every
 *   2 min) means the path is dead: the tunnel is restarted up to [MAX_RECONNECTS] times,
 *   reported as Reconnecting. During a restart the TUN interface goes down briefly;
 *   Android's "Block connections without VPN" is what closes that gap.
 */
@Singleton
class WireGuardEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secrets: SecretStore,
    @ApplicationScope private val scope: CoroutineScope,
    @Dispatcher(HiDispatchers.IO) private val io: CoroutineDispatcher,
) : TunnelEngine {

    override val protocol = VpnProtocol.WIREGUARD

    private val _state = MutableStateFlow<TunnelState>(TunnelState.Disconnected)
    override val state: StateFlow<TunnelState> = _state.asStateFlow()

    private val _traffic = MutableStateFlow<TunnelTraffic?>(null)
    override val traffic: StateFlow<TunnelTraffic?> = _traffic.asStateFlow()

    private val backend by lazy { GoBackend(context) }
    private val lock = Mutex()
    private var monitor: Job? = null
    @Volatile private var userWantsUp = false

    private val tunnel = object : Tunnel {
        override fun getName() = TUNNEL_NAME

        override fun onStateChange(newState: Tunnel.State) {
            // DOWN that we did not ask for = another VPN took over or the user revoked
            // access in system settings (GoBackend.VpnService.onRevoke/onDestroy).
            if (newState == Tunnel.State.DOWN && userWantsUp && _state.value !is TunnelState.Reconnecting) {
                userWantsUp = false
                monitor?.cancel()
                _traffic.value = null
                _state.value = TunnelState.Failed(TunnelError.RevokedBySystem)
            }
        }
    }

    override suspend fun connect(config: TunnelConfig) = lock.withLock {
        monitor?.cancel()
        userWantsUp = true
        _state.value = TunnelState.Connecting(config.targetKey)

        val wgConfig = try {
            buildConfig(config)
        } catch (e: BadConfigException) {
            fail(TunnelError.InvalidConfiguration("${e.section.name} ${e.location.name}".lowercase()))
            return@withLock
        } catch (e: IllegalArgumentException) {
            fail(TunnelError.InvalidConfiguration(e.message ?: "config"))
            return@withLock
        } catch (e: MissingKeyException) {
            fail(TunnelError.InvalidConfiguration("private key missing"))
            return@withLock
        }

        if (!bringUp(wgConfig)) return@withLock

        val peerKey = Key.fromBase64(config.serverPublicKey)
        val started = System.currentTimeMillis()
        while (userWantsUp && System.currentTimeMillis() - started < HANDSHAKE_TIMEOUT_MS) {
            val handshake = sample(peerKey)?.lastHandshakeEpochMs
            if (handshake != null && handshake > 0) {
                _state.value = TunnelState.Connected(config.targetKey, System.currentTimeMillis(), config.interfaceAddresses)
                startMonitor(config, wgConfig, peerKey)
                return@withLock
            }
            delay(POLL_MS)
        }
        if (userWantsUp) {
            HiLog.w(TAG, "No handshake within timeout")
            tearDown()
            fail(TunnelError.HandshakeTimeout)
        }
    }

    override suspend fun disconnect() {
        userWantsUp = false
        monitor?.cancel()
        lock.withLock {
            if (_state.value == TunnelState.Disconnected) return@withLock
            _state.value = TunnelState.Disconnecting
            tearDown()
            _traffic.value = null
            _state.value = TunnelState.Disconnected
        }
    }

    private suspend fun bringUp(config: Config): Boolean = withContext(io) {
        try {
            backend.setState(tunnel, Tunnel.State.UP, config)
            true
        } catch (e: BackendException) {
            HiLog.w(TAG, "Backend refused: ${e.reason}")
            fail(
                if (e.reason == BackendException.Reason.VPN_NOT_AUTHORIZED) TunnelError.PermissionDenied
                else TunnelError.Unknown(e.reason.name),
            )
            false
        } catch (e: Exception) {
            HiLog.w(TAG, "Tunnel start failed", e)
            fail(TunnelError.Unknown(e.javaClass.simpleName))
            false
        }
    }

    private suspend fun tearDown() = withContext(io) {
        runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
    }

    private fun fail(error: TunnelError) {
        userWantsUp = false
        _traffic.value = null
        _state.value = TunnelState.Failed(error)
    }

    private suspend fun sample(peerKey: Key): TunnelTraffic? = withContext(io) {
        runCatching {
            val stats = backend.getStatistics(tunnel)
            val peer = stats.peer(peerKey)
            TunnelTraffic(
                rxBytes = stats.totalRx(),
                txBytes = stats.totalTx(),
                lastHandshakeEpochMs = peer?.latestHandshakeEpochMillis()?.takeIf { it > 0 },
                sampledAtEpochMs = System.currentTimeMillis(),
            )
        }.getOrNull()?.also { _traffic.value = it }
    }

    private fun startMonitor(config: TunnelConfig, wgConfig: Config, peerKey: Key) {
        monitor = scope.launch {
            var attempts = 0
            while (isActive && userWantsUp) {
                delay(POLL_MS)
                val t = sample(peerKey) ?: continue
                val last = t.lastHandshakeEpochMs ?: continue
                val stale = System.currentTimeMillis() - last > STALE_HANDSHAKE_MS
                if (!stale) {
                    if (_state.value is TunnelState.Reconnecting) {
                        _state.value = TunnelState.Connected(config.targetKey, System.currentTimeMillis(), config.interfaceAddresses)
                    }
                    attempts = 0
                    continue
                }
                if (attempts >= MAX_RECONNECTS) {
                    tearDown()
                    fail(TunnelError.HandshakeTimeout)
                    return@launch
                }
                attempts++
                _state.value = TunnelState.Reconnecting(config.targetKey, attempts)
                // Restart re-resolves the endpoint hostname and starts a fresh handshake.
                tearDown()
                if (!bringUp(wgConfig)) return@launch
                delay(RECONNECT_GRACE_MS)
            }
        }
    }

    private suspend fun buildConfig(c: TunnelConfig): Config {
        val privateKey = secrets.get(c.privateKeyAlias) ?: throw MissingKeyException()
        val psk = c.presharedKeyAlias?.let { secrets.get(it) }

        val iface = Interface.Builder().apply {
            parsePrivateKey(privateKey)
            c.interfaceAddresses.forEach { parseAddresses(it) }
            if (c.dnsServers.isNotEmpty()) parseDnsServers(c.dnsServers.joinToString(","))
            c.mtu?.let { setMtu(it) }
            when (val split = c.splitTunnel) {
                SplitTunnelSettings.AllApps -> Unit
                is SplitTunnelSettings.OnlySelected -> includeApplications(split.packages)
                is SplitTunnelSettings.BypassSelected -> excludeApplications(split.packages)
            }
        }.build()

        val host = if (c.endpointHost.contains(':')) "[${c.endpointHost}]" else c.endpointHost
        val peer = Peer.Builder().apply {
            parsePublicKey(c.serverPublicKey)
            parseEndpoint("$host:${c.endpointPort}")
            parseAllowedIPs(c.allowedIps.joinToString(","))
            psk?.let { parsePreSharedKey(it) }
            c.persistentKeepaliveSeconds?.let { setPersistentKeepalive(it) }
        }.build()

        return Config.Builder().setInterface(iface).addPeer(peer).build()
    }

    private class MissingKeyException : Exception()

    private companion object {
        const val TAG = "WireGuardEngine"
        const val TUNNEL_NAME = "hivpn"
        const val POLL_MS = 1_000L
        const val HANDSHAKE_TIMEOUT_MS = 20_000L
        const val STALE_HANDSHAKE_MS = 180_000L
        const val RECONNECT_GRACE_MS = 10_000L
        const val MAX_RECONNECTS = 3
    }
}
