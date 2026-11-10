package app.hivpn.core.network

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/** REST contract; mirrors backend/API_DOCUMENTATION.md. */
internal interface HiApi {
    @POST("v1/accounts")
    suspend fun createAccount(): AccountCreatedDto

    @POST("v1/auth/token")
    suspend fun token(@Body body: TokenRequestDto): TokenDto

    @POST("v1/auth/refresh")
    suspend fun refresh(@Body body: RefreshRequestDto): TokenDto

    @GET("v1/servers")
    suspend fun servers(@Header("Authorization") auth: String): SignedDirectoryDto

    @POST("v1/devices")
    suspend fun registerDevice(@Header("Authorization") auth: String, @Body body: DeviceRequestDto): DeviceDto

    @DELETE("v1/devices/{id}")
    suspend fun deleteDevice(@Header("Authorization") auth: String, @Path("id") id: String)

    @GET("v1/me")
    suspend fun me(@Header("Authorization") auth: String): MeDto

    @GET("v1/ip")
    suspend fun ip(): IpDto
}
