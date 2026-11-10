package app.hivpn.vpn.service

import app.hivpn.core.network.AccountClient
import app.hivpn.core.network.ApiError
import app.hivpn.core.network.TunnelDefaults
import app.hivpn.core.security.SecretAliases
import app.hivpn.core.security.SecretStore
import app.hivpn.domain.model.ConnectionProfile
import app.hivpn.domain.model.DnsSettings
import app.hivpn.domain.model.ServerStatus
import app.hivpn.domain.model.TargetId
import app.hivpn.domain.model.TunnelError
import app.hivpn.domain.repository.ImportedConfigRepository
import app.hivpn.domain.repository.ServerRepository
import app.hivpn.domain.util.Routes
import app.hivpn.vpn.api.TunnelConfig
import app.hivpn.vpn.api.parseEndpoint
import app.hivpn.vpn.wireguard.WgKeyGenerator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Turns (profile, target) into an engine-ready [TunnelConfig], or explains why it can't. */
class TunnelConfigResolver @Inject constructor(
    private val imported: ImportedConfigRepository,
    private val servers: ServerRepository,
    private val provisioner: DeviceProvisioner,
) {
    sealed interface Resolution {
        data class Ready(val config: TunnelConfig) : Resolution
        data class Failed(val error: TunnelError) : Resolution
    }

    suspend fun resolve(profile: ConnectionProfile, target: TargetId): Resolution = when (target) {
        is TargetId.Imported -> resolveImported(profile, target)
        is TargetId.Directory -> resolveDirectory(profile, target)
    }

    private suspend fun resolveImported(profile: ConnectionProfile, target: TargetId.Imported): Resolution {
        val cfg = imported.observe(target.configId).first()
            ?: return Resolution.Failed(TunnelError.NoServerSelected)
        val (host, port) = parseEndpoint(cfg.endpoint)
            ?: return Resolution.Failed(TunnelError.InvalidConfiguration("endpoint"))
        return Resolution.Ready(
            TunnelConfig(
                targetKey = target.key,
                endpointHost = host,
                endpointPort = port,
                serverPublicKey = cfg.peerPublicKey,
                privateKeyAlias = SecretAliases.importedPrivateKey(cfg.id),
                presharedKeyAlias = if (cfg.hasPresharedKey) SecretAliases.importedPresharedKey(cfg.id) else null,
                interfaceAddresses = cfg.interfaceAddresses,
                dnsServers = dnsFor(profile, fallback = cfg.dnsServers),
                mtu = profile.mtu ?: cfg.mtu,
                persistentKeepaliveSeconds = profile.persistentKeepalive ?: cfg.persistentKeepalive,
                allowedIps = Routes.plan(cfg.allowedIps, profile.allowLanAccess, profile.excludedRoutes),
                splitTunnel = profile.splitTunnel,
            ),
        )
    }

    private suspend fun resolveDirectory(profile: ConnectionProfile, target: TargetId.Directory): Resolution {
        val server = servers.observeServer(target.serverId).first()
            ?: return Resolution.Failed(TunnelError.NoServerSelected)
        if (server.status != ServerStatus.ONLINE) {
            return Resolution.Failed(TunnelError.ApiUnavailable("server ${server.status.name.lowercase()}"))
        }
        val device = when (val d = provisioner.ensureDevice()) {
            is DeviceProvisioner.Outcome.Ready -> d
            is DeviceProvisioner.Outcome.Failed -> return Resolution.Failed(d.error)
        }
        return Resolution.Ready(
            TunnelConfig(
                targetKey = target.key,
                endpointHost = server.hostname,
                endpointPort = server.port,
                serverPublicKey = server.publicKey,
                privateKeyAlias = SecretAliases.DEVICE_PRIVATE_KEY,
                presharedKeyAlias = null,
                interfaceAddresses = listOfNotNull(device.ipv4, device.ipv6),
                dnsServers = dnsFor(profile, fallback = TunnelDefaults.NODE_DNS),
                mtu = profile.mtu,
                persistentKeepaliveSeconds = profile.persistentKeepalive ?: TunnelDefaults.KEEPALIVE_SECONDS,
                allowedIps = Routes.plan(listOf(Routes.ALL_IPV4, Routes.ALL_IPV6), profile.allowLanAccess, profile.excludedRoutes),
                splitTunnel = profile.splitTunnel,
            ),
        )
    }

    private fun dnsFor(profile: ConnectionProfile, fallback: List<String>): List<String> =
        when (val dns = profile.dns) {
            DnsSettings.Automatic -> fallback
            is DnsSettings.Custom -> dns.servers
        }
}

/**
 * Makes sure this device has a WireGuard key and is registered with the account.
 * The private key is generated here and stored only in SecretStore; the backend
 * receives the public key and returns the tunnel addresses.
 */
@Singleton
class DeviceProvisioner @Inject constructor(
    private val account: AccountClient,
    private val secrets: SecretStore,
    private val keys: WgKeyGenerator,
) {
    sealed interface Outcome {
        data class Ready(val ipv4: String, val ipv6: String?) : Outcome
        data class Failed(val error: TunnelError) : Outcome
    }

    private val lock = Mutex()

    suspend fun ensureDevice(): Outcome = lock.withLock {
        if (!account.isConfigured) return Outcome.Failed(TunnelError.ApiUnavailable("no API configured"))
        if (!account.hasAccount()) return Outcome.Failed(TunnelError.AccountRequired)

        val ipv4 = secrets.get(DEVICE_IPV4)
        if (ipv4 != null && secrets.get(SecretAliases.DEVICE_PRIVATE_KEY) != null) {
            return Outcome.Ready(ipv4, secrets.get(DEVICE_IPV6))
        }

        val generated = keys.generate()
        secrets.put(SecretAliases.DEVICE_PRIVATE_KEY, generated.privateKeyBase64)
        return account.registerDevice(generated.publicKeyBase64, android.os.Build.MODEL ?: "Android")
            .fold(
                onSuccess = { dev ->
                    secrets.put(DEVICE_ID, dev.id)
                    secrets.put(DEVICE_IPV4, dev.ipv4)
                    dev.ipv6?.let { secrets.put(DEVICE_IPV6, it) }
                    Outcome.Ready(dev.ipv4, dev.ipv6)
                },
                onFailure = { e ->
                    secrets.delete(SecretAliases.DEVICE_PRIVATE_KEY)
                    Outcome.Failed(
                        when (e) {
                            is ApiError.Unauthorized -> TunnelError.AccountRequired
                            is ApiError.DeviceLimit -> TunnelError.DeviceLimitReached
                            else -> TunnelError.ApiUnavailable(e.message ?: "error")
                        },
                    )
                },
            )
    }

    /** Revokes this device's key on the backend and forgets it locally (key rotation / sign-out). */
    suspend fun forgetDevice() = lock.withLock {
        secrets.get(DEVICE_ID)?.let { account.deleteDevice(it) }
        listOf(DEVICE_ID, DEVICE_IPV4, DEVICE_IPV6, SecretAliases.DEVICE_PRIVATE_KEY).forEach { secrets.delete(it) }
    }

    private companion object {
        const val DEVICE_ID = "device.id"
        const val DEVICE_IPV4 = "device.ipv4"
        const val DEVICE_IPV6 = "device.ipv6"
    }
}
