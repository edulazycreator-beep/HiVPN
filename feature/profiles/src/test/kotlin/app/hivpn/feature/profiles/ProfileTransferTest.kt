package app.hivpn.feature.profiles

import app.hivpn.domain.model.ConnectionProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileTransferTest {
    @Test
    fun `export contains no secrets and imports back`() {
        val p = ConnectionProfile.default(id = 3, name = "Travel").copy(allowLanAccess = true, mtu = 1280)
        val text = ProfileTransfer.export(p)
        listOf("privateKey", "PrivateKey", "account", "token").forEach { assertTrue(!text.contains(it)) }
        val back = ProfileTransfer.import(text)!!.validate().profile!!
        assertEquals(p.copy(id = 0), back)
        assertNull(ProfileTransfer.import("""{"format":"something-else","version":1,"name":"x","dns":"AUTOMATIC"}"""))
        assertNull(ProfileTransfer.import("not json"))
    }
}
