package app.hivpn.data.mapper

import app.hivpn.domain.model.ConnectionProfile
import app.hivpn.domain.model.DnsSettings
import app.hivpn.domain.model.SplitTunnelSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileMapperTest {

    @Test
    fun `default profile survives a round trip`() {
        val profile = ConnectionProfile.default(id = 3)
        assertEquals(profile, profile.toEntity(createdAtEpochMs = 1L).toDomain())
    }

    @Test
    fun `custom DNS, MTU and bypass split tunnel survive a round trip`() {
        val profile = ConnectionProfile.default(id = 7, name = "Streaming").copy(
            dns = DnsSettings.Custom(listOf("1.1.1.1", "2606:4700:4700::1111")),
            mtu = 1380,
            splitTunnel = SplitTunnelSettings.BypassSelected(setOf("com.example.bank", "com.example.tv")),
            allowLanAccess = true,
        )
        assertEquals(profile, profile.toEntity(createdAtEpochMs = 1L).toDomain())
    }

    @Test
    fun `custom DNS mode with no servers falls back to automatic`() {
        val entity = ConnectionProfile.default(id = 1)
            .toEntity(1L)
            .copy(dnsMode = "CUSTOM", dnsServers = emptyList())
        assertEquals(DnsSettings.Automatic, entity.toDomain().dns)
    }
}
