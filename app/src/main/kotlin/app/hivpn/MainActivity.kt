package app.hivpn

import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.hivpn.core.designsystem.theme.HiVpnTheme
import app.hivpn.domain.model.AppSettings
import app.hivpn.domain.model.ThemeMode
import app.hivpn.domain.model.isActive
import app.hivpn.domain.repository.SettingsRepository
import app.hivpn.domain.repository.VpnConnectionRepository
import app.hivpn.domain.usecase.ConnectUseCase
import app.hivpn.navigation.HiVpnApp
import app.hivpn.navigation.LockScreen
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface MainUiState {
    data object Loading : MainUiState
    data class Ready(val settings: AppSettings, val locked: Boolean) : MainUiState
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val vpn: VpnConnectionRepository,
    private val connect: ConnectUseCase,
) : ViewModel() {

    /** Starts locked; cleared by a successful biometric / device-credential check. */
    private val locked = MutableStateFlow(true)
    private var autoConnectDone = false

    val uiState: StateFlow<MainUiState> = combine(settings.settings, locked) { s, l ->
        MainUiState.Ready(s, locked = s.biometricLock && l)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState.Loading)

    fun unlock() { locked.value = false }
    fun lock() { locked.value = true }

    /** "Connect when the app opens": only if consent was already given and nothing is running. */
    fun maybeAutoConnect(vpnConsentGranted: Boolean) {
        if (autoConnectDone) return
        autoConnectDone = true
        viewModelScope.launch {
            if (vpnConsentGranted && settings.settings.first().autoConnectOnLaunch && !vpn.state.value.isActive) {
                connect()
            }
        }
    }
}

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { viewModel.uiState.value is MainUiState.Loading }
        viewModel.maybeAutoConnect(vpnConsentGranted = VpnService.prepare(this) == null)

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val ready = state as? MainUiState.Ready ?: return@setContent
            val dark = when (ready.settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                ) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            HiVpnTheme(darkTheme = dark) {
                if (ready.locked) {
                    LockScreen(onUnlock = ::authenticate)
                } else {
                    HiVpnApp(
                        onboardingCompleted = ready.settings.onboardingCompleted,
                        versionName = BuildConfig.VERSION_NAME,
                    )
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Re-lock whenever the app leaves the screen (only matters if the lock is enabled).
        if (!isChangingConfigurations) viewModel.lock()
    }

    private fun authenticate() {
        val authenticators = if (Build.VERSION.SDK_INT >= 30) {
            Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
        } else {
            Authenticators.BIOMETRIC_WEAK
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.lock_title))
            .setAllowedAuthenticators(authenticators)
            .apply { if (Build.VERSION.SDK_INT < 30) setNegativeButtonText(getString(R.string.lock_cancel)) }
            .build()
        BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    viewModel.unlock()
                }
            },
        ).authenticate(info)
    }
}
