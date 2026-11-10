package app.hivpn.feature.servers

import app.hivpn.domain.model.Server
import app.hivpn.domain.model.ServerCategory
import app.hivpn.domain.model.ServerStatus
import app.hivpn.domain.model.ServerTier
import app.hivpn.domain.model.VpnProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FilterServersTest {

    // Test fixtures only: never used as real infrastructure.
    private fun server(id: String, country: String, code: String, city: String, load: Int? = null, status: ServerStatus = ServerStatus.ONLINE) = Server(
        id = id, name = "test-$id", countryCode = code, countryName = country, city = city,
        hostname = "$id.test.invalid", port = 51820, publicKey = "test",
        protocols = setOf(VpnProtocol.WIREGUARD), tier = ServerTier.FREE,
        categories = setOf(ServerCategory.STANDARD), loadPercent = load,
        status = status, isFavorite = false,
    )

    private val servers = listOf(
        server("sg1", "Singapore", "SG", "Singapore", load = 70),
        server("jp1", "Japan", "JP", "Tokyo", load = 20),
        server("jp2", "Japan", "JP", "Osaka", load = 5, status = ServerStatus.MAINTENANCE),
    )

    @Test
    fun `blank query returns everything`() {
        assertEquals(servers, filterServers(servers, "  "))
    }

    @Test
    fun `matches country, city and code case-insensitively`() {
        assertEquals(listOf("jp1", "jp2"), filterServers(servers, "japan").map { it.id })
        assertEquals(listOf("jp2"), filterServers(servers, "OSA").map { it.id })
        assertEquals(listOf("sg1"), filterServers(servers, "sg").map { it.id })
    }

    @Test
    fun `recommendation prefers measured latency, then load, and skips offline servers`() {
        assertEquals("jp1", recommend(servers, emptyMap())?.id) // least loaded online
        assertEquals("sg1", recommend(servers, mapOf("sg1" to 12L, "jp1" to 80L))?.id)
        assertEquals("jp1", recommend(servers, mapOf("sg1" to null, "jp1" to 80L))?.id) // unreachable ignored
        assertEquals("jp1", recommend(servers, mapOf("jp2" to 1L))?.id) // maintenance never recommended
        assertNull(recommend(listOf(servers[2]), emptyMap()))
    }
}
