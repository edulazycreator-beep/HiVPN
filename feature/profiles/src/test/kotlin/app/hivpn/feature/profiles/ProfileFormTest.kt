package app.hivpn.feature.profiles

import app.hivpn.domain.model.ConnectionProfile
import app.hivpn.domain.model.DnsPreset
import app.hivpn.domain.model.DnsSettings
import app.hivpn.domain.model.SplitTunnelSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileFormTest {

    private val base = ProfileForm(name = "Work")

    @Test
    fun `defaults validate to an automatic full-tunnel profile`() {
        val p = base.validate().profile!!
        assertEquals(DnsSettings.Automatic, p.dns)
        assertNull(p.mtu)
        assertEquals(SplitTunnelSettings.AllApps, p.splitTunnel)
    }

    @Test
    fun `preset DNS maps to the preset servers and back`() {
        val p = base.copy(dns = DnsChoice.QUAD9).validate().profile!!
        assertEquals(DnsSettings.Custom(DnsPreset.QUAD9.servers), p.dns)
        assertEquals(DnsChoice.QUAD9, ProfileForm.from(p).dns)
    }

    @Test
    fun `custom DNS accepts commas, spaces and newlines`() {
        val p = base.copy(dns = DnsChoice.CUSTOM, customDns = "1.1.1.1, 9.9.9.9\n2620:fe::fe").validate().profile!!
        assertEquals(DnsSettings.Custom(listOf("1.1.1.1", "9.9.9.9", "2620:fe::fe")), p.dns)
    }

    @Test
    fun `invalid inputs produce field errors and no profile`() {
        val v = base.copy(
            name = " ",
            dns = DnsChoice.CUSTOM,
            customDns = "dns.google",
            mtu = "9000",
            keepalive = "70000",
            excludedRoutes = "10.0.0.0/33",
            splitMode = SplitMode.ONLY_SELECTED,
        ).validate()
        assertNull(v.profile)
        assertEquals(
            setOf(FormError.NAME, FormError.DNS_INVALID, FormError.MTU, FormError.KEEPALIVE, FormError.ROUTE, FormError.NO_APPS),
            v.errors.keys,
        )
        assertEquals("dns.google", v.errors[FormError.DNS_INVALID])
    }

    @Test
    fun `mtu bounds and keepalive zero means off`() {
        assertNotNull(base.copy(mtu = "1280").validate().profile)
        assertNotNull(base.copy(mtu = "1500").validate().profile)
        assertNull(base.copy(mtu = "1279").validate().profile)
        assertNull(base.copy(keepalive = "0").validate().profile!!.persistentKeepalive)
        assertEquals(25, base.copy(keepalive = "25").validate().profile!!.persistentKeepalive)
    }

    @Test
    fun `excluded routes are normalised`() {
        val p = base.copy(excludedRoutes = "10.20.1.5/16\n192.168.50.0/24").validate().profile!!
        assertEquals(listOf("10.20.0.0/16", "192.168.50.0/24"), p.excludedRoutes)
    }

    @Test
    fun `form round trip keeps every field`() {
        val original = ConnectionProfile.default(id = 9, name = "Streaming").copy(
            dns = DnsSettings.Custom(listOf("1.1.1.1")),
            mtu = 1380,
            splitTunnel = SplitTunnelSettings.BypassSelected(setOf("com.example.bank")),
            allowLanAccess = true,
            excludedRoutes = listOf("10.20.0.0/16"),
            persistentKeepalive = 15,
            preferredServerId = "sg-1",
        )
        assertEquals(original, ProfileForm.from(original).validate().profile)
    }
}
