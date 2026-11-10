package app.hivpn.feature.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.hivpn.core.designsystem.component.HiCard
import app.hivpn.core.designsystem.component.InfoRow
import app.hivpn.core.designsystem.component.SectionHeader
import app.hivpn.domain.model.AppSettings
import app.hivpn.domain.model.ThemeMode
import app.hivpn.domain.repository.AccountRepository
import app.hivpn.domain.repository.AccountState
import app.hivpn.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@Serializable
data object SettingsRoute

fun NavGraphBuilder.settingsScreen(versionName: String, onOpenProfiles: () -> Unit, onOpenDiagnostics: () -> Unit) {
    composable<SettingsRoute> { SettingsEntry(versionName, onOpenProfiles, onOpenDiagnostics) }
}

sealed interface AccountEvent {
    data class Created(val number: String) : AccountEvent
    data object SignInFailed : AccountEvent
    data object CreateFailed : AccountEvent
    data object KeyRotated : AccountEvent
    data object KeyRotateFailed : AccountEvent
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val account: AccountRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings.Default)

    val accountState: StateFlow<AccountState> = account.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountState.Unavailable)

    val event = MutableStateFlow<AccountEvent?>(null)
    val busy = MutableStateFlow(false)

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { repository.setThemeMode(mode) }
    fun setAutoConnect(on: Boolean) = viewModelScope.launch { repository.setAutoConnectOnLaunch(on) }
    fun setBiometric(on: Boolean) = viewModelScope.launch { repository.setBiometricLock(on) }

    fun createAccount() = work {
        event.value = account.create().fold({ AccountEvent.Created(it) }, { AccountEvent.CreateFailed })
    }

    fun signIn(number: String) = work {
        if (account.signIn(number).isFailure) event.value = AccountEvent.SignInFailed
    }

    fun signOut() = work { account.signOut() }

    fun rotateKey() = work {
        event.value = if (account.rotateDeviceKey().isSuccess) AccountEvent.KeyRotated else AccountEvent.KeyRotateFailed
    }

    private fun work(block: suspend () -> Unit) = viewModelScope.launch {
        busy.value = true
        try { block() } finally { busy.value = false }
    }
}

@Composable
internal fun SettingsEntry(
    versionName: String,
    onOpenProfiles: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val account by viewModel.accountState.collectAsStateWithLifecycle()
    val event by viewModel.event.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var signInOpen by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)

            SectionHeader(stringResource(R.string.settings_account))
            HiCard {
                when (val a = account) {
                    AccountState.Unavailable -> Text(stringResource(R.string.account_unavailable), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AccountState.SignedOut -> {
                        Text(stringResource(R.string.account_signed_out), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = viewModel::createAccount, enabled = !busy) { Text(stringResource(R.string.account_create)) }
                            OutlinedButton(onClick = { signInOpen = true }, enabled = !busy) { Text(stringResource(R.string.account_sign_in)) }
                        }
                    }
                    is AccountState.SignedIn -> {
                        var reveal by remember { mutableStateOf(false) }
                        InfoRow(
                            stringResource(R.string.account_number),
                            if (reveal) a.accountNumber else "•••• •••• •••• " + a.accountNumber.takeLast(4),
                            mono = true,
                        )
                        InfoRow(stringResource(R.string.account_plan), a.plan ?: "—")
                        a.expiresAtEpochSec?.let {
                            InfoRow(stringResource(R.string.account_expires), DateFormat.getDateInstance().format(Date(it * 1000)))
                        }
                        if (a.devicesInUse != null && a.deviceLimit != null) {
                            InfoRow(stringResource(R.string.account_devices), "${a.devicesInUse} / ${a.deviceLimit}")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { reveal = !reveal }) { Text(stringResource(if (reveal) R.string.account_hide else R.string.account_show)) }
                            TextButton(onClick = viewModel::rotateKey, enabled = !busy) { Text(stringResource(R.string.account_rotate)) }
                            TextButton(onClick = { confirmSignOut = true }, enabled = !busy) { Text(stringResource(R.string.account_sign_out)) }
                        }
                    }
                }
            }

            SectionHeader(stringResource(R.string.settings_connection))
            HiCard {
                NavRow(stringResource(R.string.settings_profiles), stringResource(R.string.settings_profiles_body), onOpenProfiles)
                ToggleRow(
                    stringResource(R.string.settings_auto_connect),
                    stringResource(R.string.settings_auto_connect_body),
                    settings.autoConnectOnLaunch,
                ) { viewModel.setAutoConnect(it) }
            }

            SectionHeader(stringResource(R.string.settings_protection))
            HiCard {
                Row(
                    Modifier.fillMaxWidth().clickable {
                        // Always-on VPN and "Block connections without VPN" can only be switched
                        // on by the user here; apps cannot enable them programmatically.
                        try {
                            context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                        } catch (_: ActivityNotFoundException) {
                            context.startActivity(Intent(Settings.ACTION_SETTINGS))
                        }
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_always_on_title), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.settings_always_on_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null)
                }
                ToggleRow(
                    stringResource(R.string.settings_biometric),
                    stringResource(R.string.settings_biometric_body),
                    settings.biometricLock,
                ) { viewModel.setBiometric(it) }
            }

            SectionHeader(stringResource(R.string.settings_tools))
            HiCard {
                NavRow(stringResource(R.string.settings_diagnostics), stringResource(R.string.settings_diagnostics_body), onOpenDiagnostics)
            }

            SectionHeader(stringResource(R.string.settings_appearance))
            HiCard {
                Column(Modifier.selectableGroup()) {
                    ThemeMode.entries.forEach { mode ->
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(selected = settings.themeMode == mode, onClick = { viewModel.setTheme(mode) }, role = Role.RadioButton)
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = settings.themeMode == mode, onClick = null)
                            Text(
                                stringResource(
                                    when (mode) {
                                        ThemeMode.SYSTEM -> R.string.theme_system
                                        ThemeMode.LIGHT -> R.string.theme_light
                                        ThemeMode.DARK -> R.string.theme_dark
                                    },
                                ),
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
            }

            SectionHeader(stringResource(R.string.settings_privacy))
            HiCard {
                Text(stringResource(R.string.settings_privacy_device_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.settings_privacy_device_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                Text(stringResource(R.string.settings_privacy_server_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.settings_privacy_server_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            SectionHeader(stringResource(R.string.settings_about))
            HiCard { InfoRow(stringResource(R.string.settings_version), versionName, mono = true) }
        }
    }

    if (signInOpen) {
        var number by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { signInOpen = false },
            title = { Text(stringResource(R.string.account_sign_in)) },
            text = {
                OutlinedTextField(
                    value = number,
                    onValueChange = { v -> number = v.filter(Char::isDigit).take(16) },
                    label = { Text(stringResource(R.string.account_number)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = { signInOpen = false; viewModel.signIn(number) }, enabled = number.length == 16) {
                    Text(stringResource(R.string.account_sign_in))
                }
            },
            dismissButton = { TextButton(onClick = { signInOpen = false }) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.account_sign_out_title)) },
            text = { Text(stringResource(R.string.account_sign_out_body)) },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; viewModel.signOut() }) { Text(stringResource(R.string.account_sign_out)) } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }

    event?.let { e ->
        AlertDialog(
            onDismissRequest = { viewModel.event.value = null },
            title = {
                Text(
                    stringResource(
                        when (e) {
                            is AccountEvent.Created -> R.string.account_created_title
                            AccountEvent.KeyRotated -> R.string.account_rotated
                            else -> R.string.account_error_title
                        },
                    ),
                )
            },
            text = {
                when (e) {
                    is AccountEvent.Created -> Column {
                        Text(stringResource(R.string.account_created_body))
                        Text(e.number, style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.padding(top = 12.dp))
                    }
                    AccountEvent.SignInFailed -> Text(stringResource(R.string.account_sign_in_failed))
                    AccountEvent.CreateFailed -> Text(stringResource(R.string.account_create_failed))
                    AccountEvent.KeyRotated -> Text(stringResource(R.string.account_rotated_body))
                    AccountEvent.KeyRotateFailed -> Text(stringResource(R.string.account_rotate_failed))
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.event.value = null }) { Text(stringResource(R.string.settings_ok)) } },
        )
    }
}

@Composable
private fun NavRow(title: String, body: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
    }
}

@Composable
private fun ToggleRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
