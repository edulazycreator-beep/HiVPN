package app.hivpn.vpn.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EndpointTest {
    @Test
    fun `parses hostnames, IPv4 and bracketed IPv6`() {
        assertEquals("vpn.example.com" to 51820, parseEndpoint("vpn.example.com:51820"))
        assertEquals("203.0.113.5" to 443, parseEndpoint("203.0.113.5:443"))
        assertEquals("2001:db8::1" to 51820, parseEndpoint("[2001:db8::1]:51820"))
    }

    @Test
    fun `rejects malformed endpoints`() {
        listOf("", "host", ":51820", "host:0", "host:70000", "[2001:db8::1]", "[2001:db8::1:51820", "host:abc").forEach {
            assertNull(it, parseEndpoint(it))
        }
    }
}
