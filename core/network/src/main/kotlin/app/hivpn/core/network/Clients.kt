package app.hivpn.core.network

import app.hivpn.core.security.SecretAliases
import app.hivpn.core.security.SecretStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Failures callers can show to the user. */
sealed class ApiError(message: String) : Exception(message) {
    class NotConfigured : ApiError("No Hi VPN API is configured in this build")
    class Unauthorized : ApiError("Account not recognised")
    class DeviceLimit : ApiError("Device limit reached for this plan")
    class Directory(val outcome: DirectoryVerifier.Outcome) : ApiError("Server list rejected: $outcome")
    class Http(val code: Int) : ApiError("Server error $code")
    class Network(cause: Throwable) : ApiError(cause.javaClass.simpleName)
}

/** Tunnel-side DNS resolver every node runs (Unbound on the tunnel gateway). See infra/README. */
object TunnelDefaults {
    val NODE_DNS = listOf("10.64.0.1", "fd64::1")
    const val KEEPALIVE_SECONDS = 25
}

interface DirectoryClient {
    suspend fun fetchVerified(): Result<DirectoryPayload>
}

interface AccountClient {
    val isConfigured: Boolean
    suspend fun hasAccount(): Boolean

    /** Creates an anonymous account; returns the account number to show the user once. */
    suspend fun createAccount(): Result<String>
    suspend fun signIn(accountNumber: String): Result<Unit>
    suspend fun signOut()
    suspend fun accountNumber(): String?
    suspend fun me(): Result<MeDto>
    suspend fun registerDevice(publicKey: String, name: String): Result<DeviceDto>
    suspend fun deleteDevice(id: String): Result<Unit>
}

interface PublicIpClient {
    /** Host of the configured API, or null when none is configured. */
    val apiHost: String?
    suspend fun publicIp(): Result<IpDto>
}

/** Raw transfer against the API's speed-test endpoints; returns (bytes, millis). */
interface SpeedTestClient {
    suspend fun download(maxBytes: Long): Result<Pair<Long, Long>>
    suspend fun upload(bytes: Long): Result<Pair<Long, Long>>
}

@Singleton
internal class ApiClients @Inject constructor(
    private val secrets: SecretStore,
) : DirectoryClient, AccountClient, PublicIpClient, SpeedTestClient {

    private val baseUrl = BuildConfig.API_BASE_URL.trim()
    override val isConfigured: Boolean get() = baseUrl.startsWith("https://")
    override val apiHost: String?
        get() = if (isConfigured) baseUrl.removePrefix("https://").substringBefore('/').substringBefore(':') else null

    private val verifier = DirectoryVerifier(BuildConfig.DIRECTORY_PUBLIC_KEY)
    private val tokenLock = Mutex()
    @Volatile private var accessToken: String? = null

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .apply { CertificatePins.forBaseUrl(baseUrl)?.let { certificatePinner(it) } }
            .build()
    }

    private val api: HiApi? by lazy {
        if (!isConfigured) return@lazy null
        Retrofit.Builder()
            .baseUrl(baseUrl.trimEnd('/') + "/")
            .client(http)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(HiApi::class.java)
    }

    private suspend fun <T> call(block: suspend (HiApi) -> T): Result<T> {
        val client = api ?: return Result.failure(ApiError.NotConfigured())
        return try {
            Result.success(block(client))
        } catch (e: HttpException) {
            Result.failure(
                when (e.code()) {
                    401, 403 -> ApiError.Unauthorized()
                    409 -> ApiError.DeviceLimit()
                    else -> ApiError.Http(e.code())
                },
            )
        } catch (e: java.io.IOException) {
            Result.failure(ApiError.Network(e))
        } catch (e: ApiError) {
            Result.failure(e)
        }
    }

    /** Runs [block] with a valid bearer token, refreshing once on 401. */
    private suspend fun <T> authed(block: suspend (HiApi, String) -> T): Result<T> {
        val first = call { block(it, "Bearer ${token()}") }
        if (first.exceptionOrNull() !is ApiError.Unauthorized) return first
        tokenLock.withLock { accessToken = null }
        refreshTokens()
        return call { block(it, "Bearer ${token()}") }
    }

    private suspend fun token(): String = tokenLock.withLock {
        accessToken ?: run {
            val number = secrets.get(SecretAliases.ACCOUNT_NUMBER) ?: throw ApiError.Unauthorized()
            val t = api!!.token(TokenRequestDto(number))
            store(t)
            t.accessToken
        }
    }

    private suspend fun refreshTokens() {
        val refresh = secrets.get(SecretAliases.REFRESH_TOKEN) ?: return
        call { it.refresh(RefreshRequestDto(refresh)) }.onSuccess { store(it) }
    }

    private suspend fun store(t: TokenDto) {
        accessToken = t.accessToken
        secrets.put(SecretAliases.REFRESH_TOKEN, t.refreshToken)
    }

    override suspend fun fetchVerified(): Result<DirectoryPayload> =
        authed { api, auth -> api.servers(auth) }.mapCatching { signed ->
            when (val outcome = verifier.verify(signed)) {
                is DirectoryVerifier.Outcome.Valid -> outcome.payload
                else -> throw ApiError.Directory(outcome)
            }
        }

    override suspend fun hasAccount() = secrets.get(SecretAliases.ACCOUNT_NUMBER) != null
    override suspend fun accountNumber() = secrets.get(SecretAliases.ACCOUNT_NUMBER)

    override suspend fun createAccount(): Result<String> =
        call { it.createAccount() }.mapCatching { created ->
            secrets.put(SecretAliases.ACCOUNT_NUMBER, created.accountNumber)
            created.accountNumber
        }

    override suspend fun signIn(accountNumber: String): Result<Unit> {
        val normalized = accountNumber.filter(Char::isDigit)
        return call { it.token(TokenRequestDto(normalized)) }.mapCatching { t ->
            secrets.put(SecretAliases.ACCOUNT_NUMBER, normalized)
            store(t)
        }
    }

    override suspend fun signOut() {
        tokenLock.withLock { accessToken = null }
        secrets.delete(SecretAliases.ACCOUNT_NUMBER)
        secrets.delete(SecretAliases.REFRESH_TOKEN)
    }

    override suspend fun me() = authed { api, auth -> api.me(auth) }

    override suspend fun registerDevice(publicKey: String, name: String) =
        authed { api, auth -> api.registerDevice(auth, DeviceRequestDto(publicKey, name)) }

    override suspend fun deleteDevice(id: String) = authed { api, auth -> api.deleteDevice(auth, id) }

    override suspend fun publicIp() = call { it.ip() }

    override suspend fun download(maxBytes: Long): Result<Pair<Long, Long>> = transfer {
        val req = okhttp3.Request.Builder().url("${baseUrl.trimEnd('/')}/v1/speedtest/download?bytes=$maxBytes").build()
        val start = System.nanoTime()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw ApiError.Http(res.code)
            val source = res.body!!.source()
            var total = 0L
            val buf = okio.Buffer()
            while (total < maxBytes) {
                val n = source.read(buf, 64 * 1024)
                if (n < 0) break
                total += n
                buf.clear()
            }
            total to (System.nanoTime() - start) / 1_000_000
        }
    }

    override suspend fun upload(bytes: Long): Result<Pair<Long, Long>> = transfer {
        val body = object : okhttp3.RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = bytes
            override fun writeTo(sink: okio.BufferedSink) {
                val chunk = ByteArray(64 * 1024)
                var left = bytes
                while (left > 0) {
                    val n = minOf(left, chunk.size.toLong()).toInt()
                    sink.write(chunk, 0, n)
                    left -= n
                }
            }
        }
        val req = okhttp3.Request.Builder().url("${baseUrl.trimEnd('/')}/v1/speedtest/upload").post(body).build()
        val start = System.nanoTime()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw ApiError.Http(res.code)
            bytes to (System.nanoTime() - start) / 1_000_000
        }
    }

    private suspend fun <T> transfer(block: () -> T): Result<T> {
        if (!isConfigured) return Result.failure(ApiError.NotConfigured())
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                Result.success(block())
            } catch (e: ApiError) {
                Result.failure(e)
            } catch (e: java.io.IOException) {
                Result.failure(ApiError.Network(e))
            }
        }
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    }
}

/**
 * Certificate pins for the API host: current key plus at least one backup key, so a
 * certificate rotation cannot lock installed apps out. Pins are read from the
 * `hivpn.apiPins` Gradle property (comma-separated "sha256/…" values); empty = no pinning.
 */
internal object CertificatePins {
    fun forBaseUrl(baseUrl: String): okhttp3.CertificatePinner? {
        val pins = BuildConfig.API_PINS.split(',').map(String::trim).filter(String::isNotEmpty)
        if (pins.size < 2) return null // Never ship a single pin: there would be no rotation path.
        val host = baseUrl.removePrefix("https://").substringBefore('/').substringBefore(':')
        return okhttp3.CertificatePinner.Builder().apply { pins.forEach { add(host, it) } }.build()
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class NetworkModule {
    @Binds abstract fun directory(impl: ApiClients): DirectoryClient
    @Binds abstract fun account(impl: ApiClients): AccountClient
    @Binds abstract fun publicIp(impl: ApiClients): PublicIpClient
    @Binds abstract fun speedTest(impl: ApiClients): SpeedTestClient
}
