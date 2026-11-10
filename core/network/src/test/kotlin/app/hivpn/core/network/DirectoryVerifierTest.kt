package app.hivpn.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The fixture was produced by the Go backend (internal/directory, ECDSA P-256) with a
 * throwaway key. If the app and backend ever disagree on the directory format or
 * signature scheme, this test fails.
 */
class DirectoryVerifierTest {

    private fun resource(name: String) =
        javaClass.classLoader!!.getResource(name)!!.readText().trim()

    private val publicKey = resource("directory-fixture.pub")
    private val signed = DirectoryVerifier.json.decodeFromString(SignedDirectoryDto.serializer(), resource("directory-fixture.json"))

    @Test
    fun `backend-signed directory verifies and parses`() {
        val outcome = DirectoryVerifier(publicKey).verify(signed)
        assertTrue(outcome.toString(), outcome is DirectoryVerifier.Outcome.Valid)
        val server = (outcome as DirectoryVerifier.Outcome.Valid).payload.servers.single()
        assertEquals("sg-sin-01", server.id)
        assertEquals(51820, server.port)
        assertEquals(37, server.loadPercent)
        assertEquals(listOf("WIREGUARD"), server.protocols)
    }

    @Test
    fun `tampered payload is rejected`() {
        val body = Base64.getDecoder().decode(signed.payload)
        val i = body.indexOf('5'.code.toByte()) // part of "51820"
        body[i] = '6'.code.toByte()
        val tampered = signed.copy(payload = Base64.getEncoder().encodeToString(body))
        assertEquals(DirectoryVerifier.Outcome.BadSignature, DirectoryVerifier(publicKey).verify(tampered))
    }

    @Test
    fun `wrong key is rejected`() {
        val other = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE" + "A".repeat(86) + "=="
        val outcome = runCatching { DirectoryVerifier(other).verify(signed) }.getOrNull()
        assertTrue(outcome !is DirectoryVerifier.Outcome.Valid)
    }

    @Test
    fun `expired directory is rejected`() {
        val farFuture = { Long.MAX_VALUE / 2 }
        assertEquals(DirectoryVerifier.Outcome.Expired, DirectoryVerifier(publicKey, farFuture).verify(signed))
    }

    @Test
    fun `no key configured means nothing is trusted`() {
        assertEquals(DirectoryVerifier.Outcome.NoKeyConfigured, DirectoryVerifier("").verify(signed))
    }
}
