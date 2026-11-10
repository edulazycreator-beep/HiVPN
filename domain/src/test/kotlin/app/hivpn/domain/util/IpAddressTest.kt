package app.hivpn.domain.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IpAddressTest {

    @Test
    fun `valid IPv4 addresses`() {
        listOf("1.1.1.1", "0.0.0.0", "255.255.255.255", "192.168.0.10").forEach {
            assertTrue(it, IpAddress.isValidIpv4(it))
        }
    }

    @Test
    fun `invalid IPv4 addresses`() {
        listOf("", "1.1.1", "1.1.1.1.1", "256.1.1.1", "01.1.1.1", "a.b.c.d", "1..1.1", " 1.1.1.1").forEach {
            assertFalse(it, IpAddress.isValidIpv4(it))
        }
    }

    @Test
    fun `valid IPv6 addresses`() {
        listOf(
            "2606:4700:4700::1111",
            "2001:4860:4860:0:0:0:0:8888",
            "::1",
            "::",
            "fe80::",
            "2620:fe::fe",
            "::ffff:192.0.2.1",
        ).forEach { assertTrue(it, IpAddress.isValidIpv6(it)) }
    }

    @Test
    fun `invalid IPv6 addresses`() {
        listOf(
            "",
            "1::2::3",
            "2001:db8:::1",
            "12345::1",
            "2001:db8:0:0:0:0:0:0:1",
            "gggg::1",
            "fe80::1%wlan0",
            "::ffff:999.0.2.1",
        ).forEach { assertFalse(it, IpAddress.isValidIpv6(it)) }
    }
}
