package app.hivpn.feature.servers

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.hivpn.core.designsystem.component.HiCard
import app.hivpn.core.designsystem.component.SectionHeader
import app.hivpn.domain.model.ImportedConfig
import app.hivpn.domain.model.Server
import app.hivpn.domain.model.ServerStatus
import app.hivpn.domain.model.TargetId
import app.hivpn.domain.repository.AccountState
import kotlinx.serialization.Serializable

@Serializable
data object ServersRoute

fun NavGraphBuilder.serversScreen(onServerSelected: () -> Unit) {
    composable<ServersRoute> { ServersEntry(onServerSelected) }
}

/** Largest .conf we accept; real configs are well under 2 KB. */
private const val MAX_CONF_BYTES = 64 * 1024

@Composable
internal fun ServersEntry(
    onServerSelected: () -> Unit,
    viewModel: ServersViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pasteOpen by remember { mutableStateOf(false) }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }?.removeSuffix(".conf").orEmpty()
        val text = resolver.openInputStream(uri)?.use { input ->
            val bytes = input.readNBytesCompat(MAX_CONF_BYTES + 1)
            if (bytes.size > MAX_CONF_BYTES) null else bytes.decodeToString()
        }
        if (text == null) viewModel.onImport(name, "") else viewModel.onImport(name, text)
    }

    ServersScreen(
        state = state,
        onQueryChange = viewModel::onQueryChange,
        onSelect = { viewModel.onSelect(it, onServerSelected) },
        onToggleFavorite = viewModel::onToggleFavorite,
        onImportFile = { pickFile.launch(arrayOf("*/*")) },
        onImportPaste = { pasteOpen = true },
        onDeleteImported = viewModel::onDeleteImported,
        onRefresh = viewModel::onRefresh,
        onMeasure = viewModel::onMeasureLatency,
        onMessageShown = viewModel::onMessageShown,
    )

    if (pasteOpen) {
        PasteConfigDialog(
            onDismiss = { pasteOpen = false },
            onImport = { name, text ->
                pasteOpen = false
                viewModel.onImport(name, text)
            },
        )
    }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8 * 1024)
    while (out.size() < limit) {
        val n = read(buf, 0, minOf(buf.size, limit - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

@Composable
internal fun ServersScreen(
    state: ServersUiState,
    onQueryChange: (String) -> Unit,
    onSelect: (TargetId) -> Unit,
    onToggleFavorite: (Server) -> Unit,
    onImportFile: () -> Unit,
    onImportPaste: () -> Unit,
    onDeleteImported: (ImportedConfig) -> Unit,
    onRefresh: () -> Unit,
    onMeasure: () -> Unit,
    onMessageShown: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val messageText = state.message?.let {
        when (it) {
            is ServersMessage.Imported -> stringResource(R.string.servers_imported, it.name)
            is ServersMessage.ImportFailed -> stringResource(R.string.servers_import_failed, it.reason)
            ServersMessage.RefreshFailed -> stringResource(R.string.servers_refresh_failed)
        }
    }
    LaunchedEffect(messageText) {
        if (messageText != null) {
            snackbar.showSnackbar(messageText)
            onMessageShown()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                stringResource(R.string.servers_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp),
            )
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            if (!state.loading) {
                ServerList(state, onQueryChange, onSelect, onToggleFavorite, onImportFile, onImportPaste, onDeleteImported, onRefresh, onMeasure)
            }
        }
    }
}

@Composable
private fun ServerList(
    state: ServersUiState,
    onQueryChange: (String) -> Unit,
    onSelect: (TargetId) -> Unit,
    onToggleFavorite: (Server) -> Unit,
    onImportFile: () -> Unit,
    onImportPaste: () -> Unit,
    onDeleteImported: (ImportedConfig) -> Unit,
    onRefresh: () -> Unit,
    onMeasure: () -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) {
        item {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.servers_search_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Own WireGuard servers (works without any Hi VPN backend).
        item { SectionHeader(stringResource(R.string.servers_my_configs)) }
        items(state.imported, key = { "imp-" + it.id }) { cfg ->
            ImportedRow(cfg, state.selected == TargetId.Imported(cfg.id), onSelect, onDeleteImported)
        }
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onImportFile) { Text(stringResource(R.string.servers_import_file)) }
                OutlinedButton(onClick = onImportPaste) { Text(stringResource(R.string.servers_import_paste)) }
            }
        }

        // Hi VPN directory.
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionHeader(stringResource(R.string.servers_directory), Modifier.weight(1f))
                if (state.account is AccountState.SignedIn) {
                    TextButton(onClick = onMeasure, enabled = !state.busy && !state.directoryEmpty) {
                        Text(stringResource(R.string.servers_measure))
                    }
                    TextButton(onClick = onRefresh, enabled = !state.busy) { Text(stringResource(R.string.servers_refresh)) }
                }
            }
        }
        if (state.directoryEmpty) {
            item {
                HiCard {
                    Text(
                        stringResource(
                            when (state.account) {
                                AccountState.Unavailable -> R.string.servers_no_api
                                AccountState.SignedOut -> R.string.servers_sign_in
                                is AccountState.SignedIn -> R.string.servers_empty_refresh
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@LazyColumn
        }
        state.recommended?.let { rec ->
            item { SectionHeader(stringResource(R.string.servers_recommended)) }
            item(key = "rec-" + rec.id) {
                ServerRow(rec, state.selected == TargetId.Directory(rec.id), state.latency, onSelect, onToggleFavorite)
            }
        }
        if (state.favorites.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.servers_favorites)) }
            items(state.favorites, key = { "fav-" + it.id }) { server ->
                ServerRow(server, state.selected == TargetId.Directory(server.id), state.latency, onSelect, onToggleFavorite)
            }
        }
        if (state.countries.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.servers_no_match),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        state.countries.forEach { group ->
            item(key = "country-" + group.countryCode) { SectionHeader(group.countryName) }
            items(group.servers, key = { it.id }) { server ->
                ServerRow(server, state.selected == TargetId.Directory(server.id), state.latency, onSelect, onToggleFavorite)
            }
        }
    }
}

@Composable
private fun ImportedRow(
    cfg: ImportedConfig,
    selected: Boolean,
    onSelect: (TargetId) -> Unit,
    onDelete: (ImportedConfig) -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    Column {
        Row(
            Modifier.fillMaxWidth().clickable { onSelect(TargetId.Imported(cfg.id)) }.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(cfg.name, style = MaterialTheme.typography.titleSmall)
                Text(cfg.endpoint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) Icon(Icons.Outlined.CheckCircle, stringResource(R.string.servers_selected), tint = MaterialTheme.colorScheme.primary)
            IconButton(onClick = { confirm = true }) {
                Icon(Icons.Outlined.Delete, stringResource(R.string.servers_delete_config))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.servers_delete_title, cfg.name)) },
            text = { Text(stringResource(R.string.servers_delete_body)) },
            confirmButton = { TextButton(onClick = { confirm = false; onDelete(cfg) }) { Text(stringResource(R.string.servers_delete_config)) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.servers_cancel)) } },
        )
    }
}

@Composable
private fun ServerRow(
    server: Server,
    selected: Boolean,
    latency: Map<String, Long?>,
    onSelect: (TargetId) -> Unit,
    onToggleFavorite: (Server) -> Unit,
) {
    val available = server.status == ServerStatus.ONLINE
    val loadText = server.loadPercent?.let { stringResource(R.string.servers_load, it) }
    val pingText = if (latency.containsKey(server.id)) {
        latency[server.id]?.let { stringResource(R.string.servers_ping, it) } ?: stringResource(R.string.servers_unreachable)
    } else null
    val statusText = when (server.status) {
        ServerStatus.ONLINE -> null
        ServerStatus.MAINTENANCE -> stringResource(R.string.servers_status_maintenance)
        ServerStatus.OFFLINE -> stringResource(R.string.servers_status_offline)
    }
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = available) { onSelect(TargetId.Directory(server.id)) }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(server.city, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = listOfNotNull(server.name, pingText, loadText, statusText).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Icon(Icons.Outlined.CheckCircle, stringResource(R.string.servers_selected), tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = { onToggleFavorite(server) }) {
                Icon(
                    if (server.isFavorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    contentDescription = stringResource(
                        if (server.isFavorite) R.string.servers_unfavorite else R.string.servers_favorite,
                    ),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun PasteConfigDialog(onDismiss: () -> Unit, onImport: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.servers_paste_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text(stringResource(R.string.servers_paste_name)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= MAX_CONF_BYTES) text = it },
                    label = { Text(stringResource(R.string.servers_paste_config)) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.heightIn(min = 160.dp),
                )
                Text(
                    stringResource(R.string.servers_paste_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onImport(name, text) }, enabled = text.contains("[Interface]")) {
                Text(stringResource(R.string.servers_import))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.servers_cancel)) } },
    )
}
