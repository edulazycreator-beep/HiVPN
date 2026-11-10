package app.hivpn.domain.util

import app.hivpn.domain.model.TargetId
import app.hivpn.domain.model.TrafficRate
import app.hivpn.domain.model.TunnelTraffic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class RoutesTest {

    @Test
    fun `parse normalises host bits`() {
        assertEquals("10.0.0.0/8", Cidr.parse("10.1.2.3/8").toString())
        assertEquals("192.168.1.5/32", Cidr.parse("192.168.1.5").toString())
        assertEquals("fd00::/8", Cidr.parse("fd12:3456::1/8").toString())
        assertEquals("::/0", Cidr.parse("::/0").toString())
        assertEquals("2001:db8::1/128", Cidr.parse("2001:db8::1").toString())
    }

    @Test
    fun `parse rejects garbage`() {
        listOf("", "10.0.0.0/33", "::/129", "example.com/24", "10.0.0.0/x", "1.2.3.4/8/9").forEach {
            assertNull(it, Cidr.parse(it))
        }
    }

    @Test
    fun `full tunnel without LAN is unchanged but gains IPv6 blackhole`() {
        assertEquals(listOf("0.0.0.0/0", "::/0"), Routes.plan(listOf("0.0.0.0/0"), allowLan = false, userExcluded = emptyList()))
    }

    @Test
    fun `split tunnel without default route does not add IPv6`() {
        assertEquals(listOf("10.8.0.0/24"), Routes.plan(listOf("10.8.0.0/24"), allowLan = false, userExcluded = emptyList()))
    }

    @Test
    fun `LAN bypass excludes private ranges and covers everything else exactly`() {
        val planned = Routes.plan(listOf("0.0.0.0/0", "::/0"), allowLan = true, userExcluded = emptyList())
            .map { Cidr.parse(it)!! }
        val local = Routes.LOCAL_NETWORKS.map { Cidr.parse(it)!! }

        // No planned route overlaps a local network.
        planned.forEach { p ->
            local.filter { it.isIpv6 == p.isIpv6 }.forEach { l ->
                assertTrue("$p overlaps $l", p.last < l.first || p.first > l.last)
            }
        }
        // Planned + local together cover each family exactly once.
        listOf(false to 32, true to 128).forEach { (v6, bits) ->
            val total = (planned + local).filter { it.isIpv6 == v6 }
                .fold(BigInteger.ZERO) { acc, c -> acc + BigInteger.ONE.shiftLeft(c.bits - c.prefix) }
            assertEquals(BigInteger.ONE.shiftLeft(bits), total)
        }
        assertTrue(planned.any { it.toString() == "11.0.0.0/8" })
        assertTrue(planned.any { it.toString() == "0.0.0.0/5" })
    }

    @Test
    fun `user exclusion splits a range`() {
        assertEquals(
            listOf("10.0.0.0/25", "10.0.0.192/26"),
            Routes.plan(listOf("10.0.0.0/24"), allowLan = false, userExcluded = listOf("10.0.0.128/26")),
        )
    }

    @Test
    fun `fromRange produces minimal blocks`() {
        val start = Cidr.parse("10.0.0.1")!!.first
        val end = Cidr.parse("10.0.0.6")!!.first
        assertEquals(
            listOf("10.0.0.1/32", "10.0.0.2/31", "10.0.0.4/31", "10.0.0.6/32"),
            Cidr.fromRange(start, end, isIpv6 = false).map(Cidr::toString),
        )
    }

    @Test
    fun `target ids round trip`() {
        listOf(TargetId.Directory("sg-1"), TargetId.Imported(42)).forEach {
            assertEquals(it, TargetId.parse(it.key))
        }
        assertNull(TargetId.parse("imp:abc"))
        assertNull(TargetId.parse("dir:"))
        assertNull(TargetId.parse("other"))
    }

    @Test
    fun `traffic rate per second`() {
        val a = TunnelTraffic(rxBytes = 1_000, txBytes = 500, lastHandshakeEpochMs = 1, sampledAtEpochMs = 10_000)
        val b = a.copy(rxBytes = 3_000, txBytes = 1_500, sampledAtEpochMs = 12_000)
        assertEquals(TrafficRate(1_000, 500, 12_000), TrafficRate.between(a, b))
        // Counter reset (new session) never yields negative speed.
        assertEquals(0, TrafficRate.between(b, a.copy(sampledAtEpochMs = 13_000)).downBytesPerSec)
    }
}
