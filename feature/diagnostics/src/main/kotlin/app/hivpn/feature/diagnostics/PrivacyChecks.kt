package app.hivpn.feature.diagnostics

import app.hivpn.domain.model.TunnelState
import app.hivpn.domain.repository.NetworkSnapshot

enum class Verdict { PASS, WARN, FAIL, INFO }

enum class CheckId { TUNNEL_ROUTE, DNS_VIA_TUNNEL, PRIVATE_DNS, IPV6, WEBRTC }

data class Finding(val id: CheckId, val verdict: Verdict, val detail: String? = null)

/**
 * Turns what Android reports about the active network into leak findings. These checks
 * read routing state on the device; they do not replace an external leak test, and the
 * UI says so.
 */
fun privacyFindings(tunnel: TunnelState, net: NetworkSnapshot): List<Finding> {
    val connected = tunnel is TunnelState.Connected
    if (!connected) {
        return listOf(Finding(CheckId.TUNNEL_ROUTE, Verdict.FAIL, "not connected"))
    }
    val findings = mutableListOf<Finding>()

    findings += if (net.vpnActive && net.transport == "vpn") {
        Finding(CheckId.TUNNEL_ROUTE, Verdict.PASS, net.interfaceName)
    } else {
        Finding(CheckId.TUNNEL_ROUTE, Verdict.FAIL, net.transport)
    }

    findings += when {
        net.transport != "vpn" -> Finding(CheckId.DNS_VIA_TUNNEL, Verdict.FAIL)
        net.dnsServers.isEmpty() -> Finding(CheckId.DNS_VIA_TUNNEL, Verdict.WARN, "no DNS server set on the tunnel")
        else -> Finding(CheckId.DNS_VIA_TUNNEL, Verdict.PASS, net.dnsServers.joinToString())
    }

    // Private DNS (DoT) still travels inside the tunnel when all traffic is routed, but the
    // Private DNS provider, not the VPN's resolver, then answers the queries.
    findings += if (net.privateDnsActive) {
        Finding(CheckId.PRIVATE_DNS, Verdict.WARN, net.privateDnsServer ?: "automatic")
    } else {
        Finding(CheckId.PRIVATE_DNS, Verdict.PASS)
    }

    findings += if (net.hasIpv6Route) {
        Finding(CheckId.IPV6, Verdict.PASS)
    } else {
        Finding(CheckId.IPV6, Verdict.WARN, "no IPv6 route on the tunnel")
    }

    findings += Finding(CheckId.WEBRTC, Verdict.INFO)
    return findings
}
