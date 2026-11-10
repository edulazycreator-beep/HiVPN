package app.hivpn.domain.util

import java.math.BigInteger
import java.net.InetAddress

/**
 * An IPv4 or IPv6 network in CIDR form. Parsing accepts only IP literals, so it never
 * triggers a DNS lookup.
 */
data class Cidr(val address: BigInteger, val prefix: Int, val isIpv6: Boolean) {

    val bits: Int get() = if (isIpv6) 128 else 32

    val first: BigInteger get() = address
    val last: BigInteger get() = address + BigInteger.ONE.shiftLeft(bits - prefix) - BigInteger.ONE

    override fun toString(): String {
        val bytes = address.toByteArray().let { raw ->
            val size = bits / 8
            ByteArray(size) { i ->
                val src = raw.size - size + i
                if (src >= 0) raw[src] else 0
            }
        }
        val host = InetAddress.getByAddress(bytes).hostAddress!!
            .let { if (isIpv6) compressIpv6(it) else it }
        return "$host/$prefix"
    }

    companion object {
        fun parse(text: String): Cidr? {
            val parts = text.trim().split('/')
            if (parts.size > 2) return null
            val host = parts[0]
            val v6 = IpAddress.isValidIpv6(host)
            if (!v6 && !IpAddress.isValidIpv4(host)) return null
            val max = if (v6) 128 else 32
            val prefix = if (parts.size == 2) parts[1].toIntOrNull() ?: return null else max
            if (prefix !in 0..max) return null
            val value = BigInteger(1, InetAddress.getByName(host).address)
            // Normalise: clear host bits so "10.1.2.3/8" becomes "10.0.0.0/8".
            val mask = BigInteger.ONE.shiftLeft(max).subtract(BigInteger.ONE)
                .xor(BigInteger.ONE.shiftLeft(max - prefix).subtract(BigInteger.ONE))
            return Cidr(value.and(mask), prefix, v6)
        }

        /** Smallest list of CIDRs covering exactly [start]..[end]. */
        fun fromRange(start: BigInteger, end: BigInteger, isIpv6: Boolean): List<Cidr> {
            val bits = if (isIpv6) 128 else 32
            val out = mutableListOf<Cidr>()
            var cur = start
            while (cur <= end) {
                // Largest block aligned at cur...
                var size = if (cur.signum() == 0) bits else cur.lowestSetBit.coerceAtMost(bits)
                // ...that does not run past end.
                while (size > 0 && cur + BigInteger.ONE.shiftLeft(size) - BigInteger.ONE > end) size--
                out += Cidr(cur, bits - size, isIpv6)
                cur += BigInteger.ONE.shiftLeft(size)
            }
            return out
        }

        private fun compressIpv6(full: String): String {
            // Java prints 0:0:0:0:0:0:0:1; compress the longest zero run to "::".
            val groups = full.split(':').map { it.trimStart('0').ifEmpty { "0" } }
            var bestStart = -1
            var bestLen = 0
            var i = 0
            while (i < groups.size) {
                if (groups[i] == "0") {
                    var j = i
                    while (j < groups.size && groups[j] == "0") j++
                    if (j - i > bestLen && j - i > 1) { bestStart = i; bestLen = j - i }
                    i = j
                } else i++
            }
            if (bestStart < 0) return groups.joinToString(":")
            val head = groups.subList(0, bestStart).joinToString(":")
            val tail = groups.subList(bestStart + bestLen, groups.size).joinToString(":")
            return "$head::$tail"
        }
    }
}

object Routes {
    /** Private and link-local ranges excluded from the tunnel when LAN access is on. */
    val LOCAL_NETWORKS: List<String> = listOf(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16",
        "fc00::/7", "fe80::/10",
    )

    const val ALL_IPV4 = "0.0.0.0/0"
    const val ALL_IPV6 = "::/0"

    /**
     * Returns [allowed] minus [excluded], as a minimal CIDR list per address family.
     * Used on every Android version so exclusions work even where VpnService.Builder
     * has no excludeRoute() (API < 33).
     */
    fun subtract(allowed: List<Cidr>, excluded: List<Cidr>): List<Cidr> =
        listOf(false, true).flatMap { v6 ->
            var ranges = merge(allowed.filter { it.isIpv6 == v6 }.map { it.first to it.last })
            excluded.filter { it.isIpv6 == v6 }.forEach { ex ->
                ranges = ranges.flatMap { (s, e) ->
                    when {
                        ex.last < s || ex.first > e -> listOf(s to e)
                        else -> listOfNotNull(
                            if (ex.first > s) s to ex.first - BigInteger.ONE else null,
                            if (ex.last < e) ex.last + BigInteger.ONE to e else null,
                        )
                    }
                }
            }
            ranges.flatMap { (s, e) -> Cidr.fromRange(s, e, v6) }
        }

    private fun merge(ranges: List<Pair<BigInteger, BigInteger>>): List<Pair<BigInteger, BigInteger>> {
        val sorted = ranges.sortedBy { it.first }
        val out = mutableListOf<Pair<BigInteger, BigInteger>>()
        for (r in sorted) {
            val last = out.lastOrNull()
            if (last != null && r.first <= last.second + BigInteger.ONE) {
                out[out.lastIndex] = last.first to maxOf(last.second, r.second)
            } else out += r
        }
        return out
    }

    /**
     * Final AllowedIPs for a tunnel:
     * - a full IPv4 tunnel always also routes ::/0, so IPv6 is blocked rather than leaked
     *   when the server has no IPv6;
     * - LAN ranges and user exclusions are subtracted.
     */
    fun plan(base: List<String>, allowLan: Boolean, userExcluded: List<String>): List<String> {
        val allowed = base.mapNotNull(Cidr::parse).toMutableList()
        if (allowed.any { !it.isIpv6 && it.prefix == 0 } && allowed.none { it.isIpv6 && it.prefix == 0 }) {
            allowed += Cidr.parse(ALL_IPV6)!!
        }
        val excluded = buildList {
            if (allowLan) addAll(LOCAL_NETWORKS.mapNotNull(Cidr::parse))
            addAll(userExcluded.mapNotNull(Cidr::parse))
        }
        if (excluded.isEmpty()) return allowed.map(Cidr::toString)
        return subtract(allowed, excluded).map(Cidr::toString)
    }
}
