package app.hivpn.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import app.hivpn.core.common.Dispatcher
import app.hivpn.core.common.HiDispatchers
import app.hivpn.core.network.PublicIpClient
import app.hivpn.core.network.SpeedTestClient
import app.hivpn.domain.repository.DiagnosticsRepository
import app.hivpn.domain.repository.LatencyResult
import app.hivpn.domain.repository.NetworkSnapshot
import app.hivpn.domain.repository.PublicIp
import app.hivpn.domain.repository.SpeedResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class DiagnosticsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val publicIpClient: PublicIpClient,
    private val speedTest: SpeedTestClient,
    @Dispatcher(HiDispatchers.IO) private val io: CoroutineDispatcher,
) : DiagnosticsRepository {

    private val cm get() = context.getSystemService(ConnectivityManager::class.java)

    override fun network(): NetworkSnapshot {
        val network = cm.activeNetwork
        val caps = network?.let(cm::getNetworkCapabilities)
        val link = network?.let(cm::getLinkProperties)
        val transport = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        val routes = link?.routes.orEmpty()
        return NetworkSnapshot(
            vpnActive = cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true },
            transport = transport,
            interfaceName = link?.interfaceName,
            addresses = link?.linkAddresses?.map { it.toString() }.orEmpty(),
            dnsServers = link?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty(),
            privateDnsActive = Build.VERSION.SDK_INT >= 28 && link?.isPrivateDnsActive == true,
            privateDnsServer = if (Build.VERSION.SDK_INT >= 28) link?.privateDnsServerName else null,
            hasIpv4Route = routes.any { it.isDefaultRoute && it.destination.address is Inet4Address },
            hasIpv6Route = routes.any { it.isDefaultRoute && it.destination.address is Inet6Address },
            mtu = if (Build.VERSION.SDK_INT >= 29) link?.mtu?.takeIf { it > 0 } else null,
            metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false,
        )
    }

    override suspend fun publicIp(): Result<PublicIp> =
        publicIpClient.publicIp().map { PublicIp(it.ip, it.country) }

    override suspend fun tcpLatency(host: String, port: Int, count: Int): LatencyResult = withContext(io) {
        val address = runCatching { InetAddress.getByName(host) }.getOrNull()
            ?: return@withContext LatencyResult(List(count) { null })
        LatencyResult(
            List(count) { i ->
                if (i > 0) delay(200)
                runCatching {
                    Socket().use { s ->
                        val start = System.nanoTime()
                        s.connect(InetSocketAddress(address, port), TIMEOUT_MS)
                        (System.nanoTime() - start) / 1_000_000
                    }
                }.getOrNull()
            },
        )
    }

    override suspend fun apiLatency(count: Int): LatencyResult? =
        publicIpClient.apiHost?.let { tcpLatency(it, 443, count) }

    override suspend fun resolve(hostname: String): Result<List<String>> = withContext(io) {
        runCatching { InetAddress.getAllByName(hostname).mapNotNull { it.hostAddress } }
    }

    override suspend fun downloadTest(maxBytes: Long): Result<SpeedResult> =
        speedTest.download(maxBytes).map { (b, ms) -> SpeedResult(b, ms) }

    override suspend fun uploadTest(bytes: Long): Result<SpeedResult> =
        speedTest.upload(bytes).map { (b, ms) -> SpeedResult(b, ms) }

    private companion object {
        const val TIMEOUT_MS = 3_000
    }
}
