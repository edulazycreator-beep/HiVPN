package app.hivpn.domain.usecase

import app.hivpn.domain.util.IpAddress
import javax.inject.Inject

/** Checks a list of custom DNS servers before it is saved to a profile. */
class ValidateDnsServersUseCase @Inject constructor() {

    sealed interface Result {
        data class Valid(val servers: List<String>) : Result
        data object Empty : Result
        data object TooMany : Result
        data class InvalidAddress(val value: String) : Result
    }

    operator fun invoke(input: List<String>): Result {
        val servers = input.map(String::trim).filter(String::isNotEmpty).distinct()
        return when {
            servers.isEmpty() -> Result.Empty
            servers.size > MAX_SERVERS -> Result.TooMany
            else -> servers.firstOrNull { !IpAddress.isValid(it) }
                ?.let(Result::InvalidAddress)
                ?: Result.Valid(servers)
        }
    }

    companion object {
        /** Primary + secondary for IPv4 and IPv6. */
        const val MAX_SERVERS = 4
    }
}
