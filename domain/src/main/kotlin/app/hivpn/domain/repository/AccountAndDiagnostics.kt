package app.hivpn.domain.repository

import kotlinx.coroutines.flow.Flow

sealed interface AccountState {
    /** This build has no API configured: only imported configurations can be used. */
    data object Unavailable : AccountState
    data object SignedOut : AccountState
    data class SignedIn(
        /** Shown grouped, e.g. "1234 5678 9012 3456". Treat like a password. */
        val accountNumber: String,
        val plan: String?,
        val expiresAtEpochSec: Long?,
        val devicesInUse: Int?,
        val deviceLimit: Int?,
    ) : AccountState
}

interface AccountRepository {
    val state: Flow<AccountState>

    /** Creates an anonymous account and signs in; returns the number to show once. */
    suspend fun create(): Result<String>
    suspend fun signIn(accountNumber: String): Result<Unit>

    /** Revokes this device's key on the server and forgets all account secrets. */
    suspend fun signOut()

    /** New WireGuard key for this device; the old public key is revoked on the server. */
    suspend fun rotateDeviceKey(): Result<Unit>

    suspend fun refresh()
}

/** Snapshot of the device's current network, read from ConnectivityManager. */
data class NetworkSnapshot(
    val vpnActive: Boolean,
    val transport: String,
    val interfaceName: String?,
    val addresses: List<String>,
    val dnsServers: List<String>,
    val privateDnsActive: Boolean,
    val privateDnsServer: String?,
    val hasIpv4Route: Boolean,
    val hasIpv6Route: Boolean,
    val mtu: Int?,
    val metered: Boolean,
)

data class LatencyResult(
    val samples: List<Long?>,
) {
    private val ok get() = samples.filterNotNull()
    val minMs: Long? get() = ok.minOrNull()
    val avgMs: Long? get() = ok.takeIf { it.isNotEmpty() }?.average()?.toLong()
    val maxMs: Long? get() = ok.maxOrNull()

    /** Mean absolute difference between consecutive successful samples. */
    val jitterMs: Long? get() = ok.zipWithNext { a, b -> kotlin.math.abs(a - b) }
        .takeIf { it.isNotEmpty() }?.average()?.toLong()

    val lossPercent: Int get() = if (samples.isEmpty()) 0 else (samples.count { it == null } * 100 / samples.size)
}

data class PublicIp(val address: String, val country: String?)

data class SpeedResult(val bytes: Long, val millis: Long) {
    val megabitsPerSecond: Double get() = if (millis <= 0) 0.0 else bytes * 8.0 / millis / 1000.0
}

interface DiagnosticsRepository {
    fun network(): NetworkSnapshot

    /** Asks the Hi VPN API which address it sees. Needs a configured API. */
    suspend fun publicIp(): Result<PublicIp>

    /**
     * TCP connect time to [host]:[port], [count] times. TCP is used because unprivileged
     * apps cannot send ICMP reliably on every Android version.
     */
    suspend fun tcpLatency(host: String, port: Int, count: Int): LatencyResult

    /** TCP latency to the Hi VPN API host (goes through the tunnel when connected); null if no API. */
    suspend fun apiLatency(count: Int): LatencyResult?

    /** Resolves [hostname] and reports which addresses came back (DNS sanity check). */
    suspend fun resolve(hostname: String): Result<List<String>>

    /** Downloads up to [maxBytes] from the API's speed-test endpoint. */
    suspend fun downloadTest(maxBytes: Long): Result<SpeedResult>

    /** Uploads [bytes] to the API's speed-test endpoint. */
    suspend fun uploadTest(bytes: Long): Result<SpeedResult>
}
