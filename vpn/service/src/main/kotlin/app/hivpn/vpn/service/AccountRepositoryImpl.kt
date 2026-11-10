package app.hivpn.vpn.service

import app.hivpn.core.network.AccountClient
import app.hivpn.domain.repository.AccountRepository
import app.hivpn.domain.repository.AccountState
import app.hivpn.domain.repository.ServerRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class AccountRepositoryImpl @Inject constructor(
    private val client: AccountClient,
    private val provisioner: DeviceProvisioner,
    private val servers: ServerRepository,
) : AccountRepository {

    private val _state = MutableStateFlow<AccountState>(AccountState.SignedOut)
    override val state: Flow<AccountState> = _state.onStart { refresh() }

    override suspend fun refresh() {
        _state.value = when {
            !client.isConfigured -> AccountState.Unavailable
            !client.hasAccount() -> AccountState.SignedOut
            else -> {
                val number = client.accountNumber().orEmpty()
                val me = client.me().getOrNull()
                AccountState.SignedIn(
                    accountNumber = number.chunked(4).joinToString(" "),
                    plan = me?.plan,
                    expiresAtEpochSec = me?.expiresAt,
                    devicesInUse = me?.devicesInUse,
                    deviceLimit = me?.deviceLimit,
                )
            }
        }
    }

    override suspend fun create(): Result<String> = client.createAccount()
        .mapCatching { number ->
            client.signIn(number).getOrThrow()
            number.chunked(4).joinToString(" ")
        }
        .also { if (it.isSuccess) afterSignIn() }

    override suspend fun signIn(accountNumber: String): Result<Unit> =
        client.signIn(accountNumber).also { if (it.isSuccess) afterSignIn() }

    override suspend fun signOut() {
        provisioner.forgetDevice()
        client.signOut()
        refresh()
    }

    override suspend fun rotateDeviceKey(): Result<Unit> = runCatching {
        provisioner.forgetDevice()
        when (val r = provisioner.ensureDevice()) {
            is DeviceProvisioner.Outcome.Ready -> Unit
            is DeviceProvisioner.Outcome.Failed -> error(r.error.toString())
        }
    }

    private suspend fun afterSignIn() {
        refresh()
        servers.refresh()
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AccountModule {
    @Binds abstract fun account(impl: AccountRepositoryImpl): AccountRepository
}
