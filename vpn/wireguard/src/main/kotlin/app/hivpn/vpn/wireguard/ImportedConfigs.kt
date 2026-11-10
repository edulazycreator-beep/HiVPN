package app.hivpn.vpn.wireguard

import app.hivpn.core.database.dao.ImportedConfigDao
import app.hivpn.core.database.entity.ImportedConfigEntity
import app.hivpn.core.security.SecretAliases
import app.hivpn.core.security.SecretStore
import app.hivpn.domain.model.ImportedConfig
import app.hivpn.domain.repository.ImportResult
import app.hivpn.domain.repository.ImportedConfigRepository
import com.wireguard.config.BadConfigException
import com.wireguard.config.Config
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.BufferedReader
import java.io.StringReader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Imports wg-quick configuration text (as exported by any WireGuard server panel).
 * Secrets are split off into SecretStore immediately; the text itself is not kept.
 */
@Singleton
internal class WgImportedConfigRepository @Inject constructor(
    private val dao: ImportedConfigDao,
    private val secrets: SecretStore,
) : ImportedConfigRepository {

    override fun observeAll(): Flow<List<ImportedConfig>> = dao.observeAll().map { it.map(::toDomain) }

    override fun observe(id: Long): Flow<ImportedConfig?> = dao.observe(id).map { it?.let(::toDomain) }

    override suspend fun import(name: String, configText: String): ImportResult {
        val config = try {
            Config.parse(BufferedReader(StringReader(configText)))
        } catch (e: BadConfigException) {
            return ImportResult.Invalid("${e.section.name} ${e.location.name}: ${e.reason.name}".lowercase())
        } catch (e: Exception) {
            return ImportResult.Invalid("not a WireGuard configuration")
        }

        val peer = when (config.peers.size) {
            0 -> return ImportResult.Invalid("no [Peer] section")
            1 -> config.peers.first()
            else -> return ImportResult.Invalid("only one [Peer] per configuration is supported")
        }
        val endpoint = peer.endpoint.orElse(null)
            ?: return ImportResult.Invalid("the peer has no Endpoint")
        val iface = config.`interface`
        if (iface.addresses.isEmpty()) return ImportResult.Invalid("the interface has no Address")

        val entity = ImportedConfigEntity(
            name = name.trim().ifEmpty { endpoint.host },
            endpoint = if (endpoint.host.contains(':')) "[${endpoint.host}]:${endpoint.port}" else "${endpoint.host}:${endpoint.port}",
            interfaceAddresses = iface.addresses.map { it.toString() },
            dnsServers = iface.dnsServers.mapNotNull { it.hostAddress },
            allowedIps = peer.allowedIps.map { it.toString() },
            mtu = iface.mtu.orElse(null),
            persistentKeepalive = peer.persistentKeepalive.orElse(null),
            peerPublicKey = peer.publicKey.toBase64(),
            hasPresharedKey = peer.preSharedKey.isPresent,
            createdAtEpochMs = System.currentTimeMillis(),
        )
        val id = dao.insert(entity)
        try {
            secrets.put(SecretAliases.importedPrivateKey(id), iface.keyPair.privateKey.toBase64())
            peer.preSharedKey.orElse(null)?.let { secrets.put(SecretAliases.importedPresharedKey(id), it.toBase64()) }
        } catch (e: Exception) {
            dao.delete(id)
            secrets.deleteWithPrefix(SecretAliases.importedPrefix(id))
            return ImportResult.Invalid("could not store the key securely")
        }
        return ImportResult.Success(id)
    }

    override suspend fun delete(id: Long) {
        secrets.deleteWithPrefix(SecretAliases.importedPrefix(id))
        dao.delete(id)
    }

    private fun toDomain(e: ImportedConfigEntity) = ImportedConfig(
        id = e.id,
        name = e.name,
        endpoint = e.endpoint,
        interfaceAddresses = e.interfaceAddresses,
        dnsServers = e.dnsServers,
        allowedIps = e.allowedIps,
        mtu = e.mtu,
        persistentKeepalive = e.persistentKeepalive,
        peerPublicKey = e.peerPublicKey,
        hasPresharedKey = e.hasPresharedKey,
    )
}

/** Generates WireGuard (Curve25519) key pairs on the device. */
class WgKeyGenerator @Inject constructor() {
    data class Generated(val privateKeyBase64: String, val publicKeyBase64: String)

    fun generate(): Generated = KeyPair().let {
        Generated(it.privateKey.toBase64(), it.publicKey.toBase64())
    }

    fun publicKeyFor(privateKeyBase64: String): String =
        KeyPair(com.wireguard.crypto.Key.fromBase64(privateKeyBase64)).publicKey.toBase64()
}
