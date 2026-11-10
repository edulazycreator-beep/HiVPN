package app.hivpn.feature.diagnostics

import app.hivpn.domain.model.TunnelState
import app.hivpn.domain.repository.NetworkSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class PrivacyChecksTest {

    private val goodVpn = NetworkSnapshot(
        vpnActive = true, transport = "vpn", interfaceName = "tun0",
        addresses = listOf("10.64.1.1/32", "fd64::101/128"), dnsServers = listOf("10.64.0.1"),
        privateDnsActive = false, privateDnsServer = null,
        hasIpv4Route = true, hasIpv6Route = true, mtu = 1420, metered = false,
    )
    private val connected = TunnelState.Connected("dir:sg-1", 0, emptyList())

    private fun verdicts(t: TunnelState, n: NetworkSnapshot) =
        privacyFindings(t, n).associate { it.id to it.verdict }

    @Test
    fun `healthy tunnel passes every check`() {
        assertEquals(
            mapOf(
                CheckId.TUNNEL_ROUTE to Verdict.PASS,
                CheckId.DNS_VIA_TUNNEL to Verdict.PASS,
                CheckId.PRIVATE_DNS to Verdict.PASS,
                CheckId.IPV6 to Verdict.PASS,
                CheckId.WEBRTC to Verdict.INFO,
            ),
            verdicts(connected, goodVpn),
        )
    }

    @Test
    fun `not connected is a single failure, never a pass`() {
        val f = privacyFindings(TunnelState.Disconnected, goodVpn)
        assertEquals(1, f.size)
        assertEquals(Verdict.FAIL, f.single().verdict)
    }

    @Test
    fun `traffic on wifi while tunnel claims connected fails route and dns`() {
        val v = verdicts(connected, goodVpn.copy(vpnActive = false, transport = "wifi"))
        assertEquals(Verdict.FAIL, v[CheckId.TUNNEL_ROUTE])
        assertEquals(Verdict.FAIL, v[CheckId.DNS_VIA_TUNNEL])
    }

    @Test
    fun `private dns and missing ipv6 route are warnings`() {
        val v = verdicts(connected, goodVpn.copy(privateDnsActive = true, privateDnsServer = "dns.example", hasIpv6Route = false))
        assertEquals(Verdict.WARN, v[CheckId.PRIVATE_DNS])
        assertEquals(Verdict.WARN, v[CheckId.IPV6])
    }
}
