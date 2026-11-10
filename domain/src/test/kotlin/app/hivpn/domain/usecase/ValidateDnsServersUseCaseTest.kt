package app.hivpn.domain.usecase

import app.hivpn.domain.model.DnsPreset
import app.hivpn.domain.usecase.ValidateDnsServersUseCase.Result
import org.junit.Assert.assertEquals
import org.junit.Test

class ValidateDnsServersUseCaseTest {

    private val validate = ValidateDnsServersUseCase()

    @Test
    fun `trims, drops blanks and de-duplicates`() {
        assertEquals(
            Result.Valid(listOf("1.1.1.1", "9.9.9.9")),
            validate(listOf(" 1.1.1.1 ", "", "9.9.9.9", "1.1.1.1")),
        )
    }

    @Test
    fun `empty input is rejected`() {
        assertEquals(Result.Empty, validate(listOf("", "  ")))
    }

    @Test
    fun `more than four servers is rejected`() {
        assertEquals(Result.TooMany, validate(listOf("1.1.1.1", "1.0.0.1", "8.8.8.8", "8.8.4.4", "9.9.9.9")))
    }

    @Test
    fun `hostnames are rejected because the tunnel needs IP literals`() {
        assertEquals(Result.InvalidAddress("dns.google"), validate(listOf("8.8.8.8", "dns.google")))
    }

    @Test
    fun `every preset is valid`() {
        DnsPreset.entries.forEach { preset ->
            assertEquals(preset.name, Result.Valid(preset.servers), validate(preset.servers))
        }
    }
}
