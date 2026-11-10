package app.hivpn.feature.profiles

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.hivpn.core.designsystem.component.HiCard
import app.hivpn.core.designsystem.component.SectionHeader
import app.hivpn.domain.model.ConnectionProfile
import app.hivpn.domain.model.DnsSettings
import app.hivpn.domain.model.SplitTunnelSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable data object ProfilesRoute
@Serializable data class ProfileEditorRoute(val id: Long)

fun NavGraphBuilder.profilesGraph(onBack: () -> Unit, onEdit: (Long) -> Unit) {
    composable<ProfilesRoute> { ProfilesEntry(onBack, onEdit) }
    composable<ProfileEditorRoute> { ProfileEditorEntry(onBack) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.prof_back)) }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

// ---------- List ----------

@Composable
private fun ProfilesEntry(onBack: () -> Unit, onEdit: (Long) -> Unit, vm: ProfilesViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importOpen by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            BackBar(stringResource(R.string.prof_title), onBack) {
                IconButton(onClick = { onEdit(0) }) { Icon(Icons.Outlined.Add, stringResource(R.string.prof_new)) }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            item {
                Text(
                    stringResource(R.string.prof_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            items(state.profiles, key = { it.id }) { p ->
                ProfileRow(
                    profile = p,
                    active = p.id == state.activeId,
                    canDelete = state.profiles.size > 1,
                    onActivate = { vm.activate(p.id) },
                    onEdit = { onEdit(p.id) },
                    onDuplicate = { vm.duplicate(p) },
                    onDelete = { vm.delete(p) },
                    onExport = {
                        val send = Intent(Intent.ACTION_SEND).setType("application/json")
                            .putExtra(Intent.EXTRA_SUBJECT, "${p.name}.hivpn-profile.json")
                            .putExtra(Intent.EXTRA_TEXT, vm.exportText(p))
                        context.startActivity(Intent.createChooser(send, null))
                    },
                )
            }
            item {
                OutlinedButton(onClick = { importOpen = true }, modifier = Modifier.padding(vertical = 16.dp)) {
                    Text(stringResource(R.string.prof_import))
                }
            }
        }
    }

    if (importOpen) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { importOpen = false },
            title = { Text(stringResource(R.string.prof_import)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { if (it.length < 16_000) text = it },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.heightIn(min = 160.dp),
                    )
                    if (importError) {
                        Text(stringResource(R.string.prof_import_error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        if (vm.importText(text)) { importOpen = false; importError = false } else importError = true
                    }
                }) { Text(stringResource(R.string.prof_import_confirm)) }
            },
            dismissButton = { TextButton(onClick = { importOpen = false }) { Text(stringResource(R.string.prof_cancel)) } },
        )
    }
}

@Composable
private fun ProfileRow(
    profile: ConnectionProfile,
    active: Boolean,
    canDelete: Boolean,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth().clickable(onClick = onActivate).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(profile.name, style = MaterialTheme.typography.titleSmall)
                Text(summary(profile), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (active) Icon(Icons.Outlined.CheckCircle, stringResource(R.string.prof_active), tint = MaterialTheme.colorScheme.primary)
            Column {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.prof_more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.prof_edit)) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.prof_duplicate)) }, onClick = { menu = false; onDuplicate() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.prof_export)) }, onClick = { menu = false; onExport() })
                    if (canDelete) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.prof_delete)) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun summary(p: ConnectionProfile): String = listOfNotNull(
    when (val d = p.dns) {
        DnsSettings.Automatic -> stringResource(R.string.prof_sum_dns_auto)
        is DnsSettings.Custom -> stringResource(R.string.prof_sum_dns_custom, d.servers.first())
    },
    p.mtu?.let { "MTU $it" },
    if (p.allowLanAccess) stringResource(R.string.prof_sum_lan) else null,
    when (val s = p.splitTunnel) {
        SplitTunnelSettings.AllApps -> null
        is SplitTunnelSettings.OnlySelected -> stringResource(R.string.prof_sum_only, s.packages.size)
        is SplitTunnelSettings.BypassSelected -> stringResource(R.string.prof_sum_bypass, s.packages.size)
    },
).joinToString(" · ")

// ---------- Editor ----------

@Composable
private fun ProfileEditorEntry(onBack: () -> Unit, vm: ProfileEditorViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var pickApps by remember { mutableStateOf(false) }
    LaunchedEffect(state.saved) { if (state.saved) onBack() }

    Scaffold(
        topBar = {
            BackBar(stringResource(R.string.prof_edit_title), onBack) {
                TextButton(onClick = vm::save, enabled = state.loaded) { Text(stringResource(R.string.prof_save)) }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (!state.loaded) return@Scaffold
        val f = state.form
        val e = state.errors
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            OutlinedTextField(
                value = f.name,
                onValueChange = { v -> vm.edit { it.copy(name = v.take(40)) } },
                label = { Text(stringResource(R.string.prof_name)) },
                isError = FormError.NAME in e,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionHeader(stringResource(R.string.prof_dns))
            HiCard {
                DnsChoice.entries.forEach { choice ->
                    RadioLine(
                        selected = f.dns == choice,
                        label = stringResource(
                            when (choice) {
                                DnsChoice.AUTOMATIC -> R.string.prof_dns_auto
                                DnsChoice.CLOUDFLARE -> R.string.prof_dns_cloudflare
                                DnsChoice.GOOGLE -> R.string.prof_dns_google
                                DnsChoice.QUAD9 -> R.string.prof_dns_quad9
                                DnsChoice.CUSTOM -> R.string.prof_dns_custom
                            },
                        ),
                        onClick = { vm.edit { it.copy(dns = choice) } },
                    )
                }
                if (f.dns == DnsChoice.CUSTOM) {
                    OutlinedTextField(
                        value = f.customDns,
                        onValueChange = { v -> vm.edit { it.copy(customDns = v.take(400)) } },
                        label = { Text(stringResource(R.string.prof_dns_custom_hint)) },
                        isError = e.keys.any { it.name.startsWith("DNS") },
                        supportingText = {
                            when {
                                FormError.DNS_EMPTY in e -> Text(stringResource(R.string.prof_err_dns_empty))
                                FormError.DNS_TOO_MANY in e -> Text(stringResource(R.string.prof_err_dns_many))
                                FormError.DNS_INVALID in e -> Text(stringResource(R.string.prof_err_dns_invalid, e[FormError.DNS_INVALID].orEmpty()))
                            }
                        },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
                Text(stringResource(R.string.prof_dns_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            SectionHeader(stringResource(R.string.prof_network))
            HiCard {
                OutlinedTextField(
                    value = f.mtu,
                    onValueChange = { v -> vm.edit { it.copy(mtu = v.filter(Char::isDigit).take(4)) } },
                    label = { Text(stringResource(R.string.prof_mtu)) },
                    placeholder = { Text(stringResource(R.string.prof_auto)) },
                    isError = FormError.MTU in e,
                    supportingText = { Text(stringResource(R.string.prof_mtu_help)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("1420", "1380", "1280").forEach { preset ->
                        OutlinedButton(onClick = { vm.edit { it.copy(mtu = preset) } }) { Text(preset) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = f.keepalive,
                    onValueChange = { v -> vm.edit { it.copy(keepalive = v.filter(Char::isDigit).take(5)) } },
                    label = { Text(stringResource(R.string.prof_keepalive)) },
                    placeholder = { Text(stringResource(R.string.prof_auto)) },
                    isError = FormError.KEEPALIVE in e,
                    supportingText = { Text(stringResource(R.string.prof_keepalive_help)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SectionHeader(stringResource(R.string.prof_routing))
            HiCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.prof_lan), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.prof_lan_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = f.allowLan, onCheckedChange = { v -> vm.edit { it.copy(allowLan = v) } })
                }
                OutlinedTextField(
                    value = f.excludedRoutes,
                    onValueChange = { v -> vm.edit { it.copy(excludedRoutes = v.take(2000)) } },
                    label = { Text(stringResource(R.string.prof_exclude)) },
                    placeholder = { Text("10.20.0.0/16") },
                    isError = FormError.ROUTE in e,
                    supportingText = {
                        Text(
                            if (FormError.ROUTE in e) stringResource(R.string.prof_err_route, e[FormError.ROUTE].orEmpty())
                            else stringResource(R.string.prof_exclude_help),
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }

            SectionHeader(stringResource(R.string.prof_split))
            HiCard {
                SplitMode.entries.forEach { mode ->
                    RadioLine(
                        selected = f.splitMode == mode,
                        label = stringResource(
                            when (mode) {
                                SplitMode.ALL -> R.string.prof_split_all
                                SplitMode.ONLY_SELECTED -> R.string.prof_split_only
                                SplitMode.BYPASS_SELECTED -> R.string.prof_split_bypass
                            },
                        ),
                        onClick = { vm.edit { it.copy(splitMode = mode) } },
                    )
                }
                if (f.splitMode != SplitMode.ALL) {
                    OutlinedButton(onClick = { vm.loadApps(); pickApps = true }, modifier = Modifier.padding(top = 8.dp)) {
                        Text(stringResource(R.string.prof_pick_apps, f.packages.size))
                    }
                    if (FormError.NO_APPS in e) {
                        Text(stringResource(R.string.prof_err_no_apps), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(stringResource(R.string.prof_split_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = vm::save, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.prof_save)) }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (pickApps) {
        AppPicker(
            apps = state.apps,
            selected = state.form.packages,
            onDone = { picked -> vm.edit { it.copy(packages = picked) }; pickApps = false },
            onDismiss = { pickApps = false },
        )
    }
}

@Composable
private fun RadioLine(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun AppPicker(
    apps: List<InstalledApp>,
    selected: Set<String>,
    onDone: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by remember { mutableStateOf(selected) }
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.prof_apps_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.prof_apps_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val visible = apps.filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(visible, key = { it.packageName }) { app ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                picked = if (app.packageName in picked) picked - app.packageName else picked + app.packageName
                            }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(app.packageName)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(app.label, style = MaterialTheme.typography.bodyMedium)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Checkbox(checked = app.packageName in picked, onCheckedChange = null)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(picked) }) { Text(stringResource(R.string.prof_done)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.prof_cancel)) } },
    )
}

@Composable
private fun AppIcon(packageName: String) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
    }
    icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(32.dp)) } ?: Spacer(Modifier.size(32.dp))
}
