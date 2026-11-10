package app.hivpn.core.network

import kotlinx.serialization.Serializable

/** Wire format of GET /v1/servers. [payload] is base64 JSON; [signature] is base64 DER ECDSA P-256/SHA-256 over the decoded payload bytes. */
@Serializable
data class SignedDirectoryDto(val payload: String, val signature: String, val keyId: String)

@Serializable
data class DirectoryPayload(
    val version: Int,
    val issuedAt: Long,
    val expiresAt: Long,
    val servers: List<ServerDto>,
)

@Serializable
data class ServerDto(
    val id: String,
    val name: String,
    val countryCode: String,
    val countryName: String,
    val city: String,
    val hostname: String,
    val port: Int,
    val publicKey: String,
    val protocols: List<String>,
    val tier: String,
    val categories: List<String>,
    val loadPercent: Int? = null,
    val status: String,
)

@Serializable data class AccountCreatedDto(val accountNumber: String)
@Serializable data class TokenRequestDto(val accountNumber: String)
@Serializable data class RefreshRequestDto(val refreshToken: String)
@Serializable data class TokenDto(val accessToken: String, val refreshToken: String, val expiresIn: Long)

@Serializable data class DeviceRequestDto(val publicKey: String, val name: String)
@Serializable data class DeviceDto(val id: String, val ipv4: String, val ipv6: String? = null)

@Serializable
data class MeDto(
    val plan: String,
    val expiresAt: Long? = null,
    val deviceLimit: Int,
    val devicesInUse: Int,
)

@Serializable data class IpDto(val ip: String, val country: String? = null)

@Serializable data class ErrorDto(val error: String, val message: String? = null)
