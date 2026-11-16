package dev.universalai.agent.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.universalai.agent.BuildConfig
import dev.universalai.agent.core.AppContainer
import dev.universalai.agent.core.AppResult
import dev.universalai.agent.core.Formatting
import dev.universalai.agent.data.model.ConnectionTest
import dev.universalai.agent.data.model.RuntimeConnection
import dev.universalai.agent.data.model.Usage
import dev.universalai.agent.ui.components.GlassCard
import dev.universalai.agent.ui.components.PearlBackground
import dev.universalai.agent.ui.components.PearlTextField
import dev.universalai.agent.ui.components.Pill
import dev.universalai.agent.ui.components.PrimaryButton
import dev.universalai.agent.ui.components.ScreenTopBar
import dev.universalai.agent.ui.components.SecondaryButton
import dev.universalai.agent.ui.components.SectionHeader
import dev.universalai.agent.ui.components.StatusDot
import dev.universalai.agent.ui.components.rememberViewModel
import dev.universalai.agent.ui.theme.Palette
import kotlinx.coroutines.launch

data class SettingsUiState(
    val apiBaseUrl: String = "",
    val usage: Usage? = null,
    val connections: List<RuntimeConnection> = emptyList(),
    val connectionTests: Map<String, ConnectionTest> = emptyMap(),
    val pairingRequest: Pair<String, String>? = null,
    val showConnect: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    var state by mutableStateOf(SettingsUiState(apiBaseUrl = container.store.apiBaseUrl))
        private set

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val usage = container.runtime.usage()
            val connections = container.runtime.connections()
            state = state.copy(
                usage = usage.valueOrNull(),
                connections = connections.valueOrNull().orEmpty(),
                error = (usage as? AppResult.Failure)?.message
                    ?: (connections as? AppResult.Failure)?.message,
            )
        }
    }

    fun onApiBaseUrl(value: String) { state = state.copy(apiBaseUrl = value) }

    fun saveServer() {
        container.store.apiBaseUrl = state.apiBaseUrl
        state = state.copy(notice = "Server address saved.", error = null)
        refresh()
    }

    fun openConnect() { state = state.copy(showConnect = true) }
    fun closeConnect() { state = state.copy(showConnect = false) }

    fun connect(url: String, token: String) {
        state = state.copy(busy = true, showConnect = false, error = null)
        viewModelScope.launch {
            val result = container.runtime.connect(url.trim(), token.trim().ifBlank { null })
            val response = result.valueOrNull()
            val pairing = response?.probe?.pairingRequestId
            state = state.copy(
                busy = false,
                notice = when {
                    response == null -> null
                    pairing != null -> "Gateway requires pairing."
                    response.probe?.ok == true -> "Gateway connected."
                    else -> response.probe?.message ?: "Connection stored; test it again after pairing."
                },
                pairingRequest = pairing?.let { response.connection.gatewayUrl to it },
                error = (result as? AppResult.Failure)?.message,
            )
            refresh()
        }
    }

    fun test(connection: RuntimeConnection) {
        state = state.copy(busy = true)
        viewModelScope.launch {
            val result = container.runtime.test(connection.id)
            val test = result.valueOrNull()
            state = state.copy(
                busy = false,
                connectionTests = test?.let { state.connectionTests + (connection.id to it) }
                    ?: state.connectionTests,
                error = (result as? AppResult.Failure)?.message,
            )
            refresh()
        }
    }

    fun disconnect(connection: RuntimeConnection) {
        viewModelScope.launch {
            container.runtime.disconnect(connection.id)
            refresh()
        }
    }

    fun dismissNotice() { state = state.copy(notice = null) }
    fun dismissError() { state = state.copy(error = null) }
}

@Composable
fun SettingsScreen(container: AppContainer) {
    val viewModel = rememberViewModel { SettingsViewModel(container) }
    val state = viewModel.state

    PearlBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenTopBar(title = "Settings", subtitle = "Server, runtime and usage")
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 18.dp, end = 18.dp, top = 4.dp, bottom = 32.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.error != null) {
                    item {
                        Text(
                            state.error.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.Rose,
                            modifier = Modifier.clickable { viewModel.dismissError() },
                        )
                    }
                }
                if (state.notice != null) {
                    item {
                        Text(
                            state.notice.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.Sage,
                            modifier = Modifier.clickable { viewModel.dismissNotice() },
                        )
                    }
                }

                item { SectionHeader(title = "Server", subtitle = "Where the app sends requests") }
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        PearlTextField(
                            label = "API base URL",
                            value = state.apiBaseUrl,
                            onValueChange = viewModel::onApiBaseUrl,
                            placeholder = "https://your-app.vercel.app",
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "No account or sign-in: the app talks to your single-workspace " +
                                "backend, which holds the provider API keys.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Palette.Muted,
                        )
                        Spacer(Modifier.height(10.dp))
                        PrimaryButton(
                            label = "Save server address",
                            onClick = viewModel::saveServer,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                item {
                    SectionHeader(
                        title = "OpenClaw runtime",
                        subtitle = "Optional external agent gateway",
                        actionLabel = "Connect",
                        onAction = viewModel::openConnect,
                    )
                }
                items(state.connections, key = { it.id }) { connection ->
                    RuntimeCard(
                        connection = connection,
                        test = state.connectionTests[connection.id],
                        busy = state.busy,
                        onTest = { viewModel.test(connection) },
                        onDisconnect = { viewModel.disconnect(connection) },
                    )
                }
                if (state.connections.isEmpty()) {
                    item {
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "No gateway connected",
                                style = MaterialTheme.typography.titleMedium,
                                color = Palette.Ink,
                            )
                            Text(
                                text = "The native Python runtime works without OpenClaw. Connect a " +
                                    "gateway only if you run one; the app does not bundle the gateway.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Palette.Muted,
                            )
                            Spacer(Modifier.height(10.dp))
                            SecondaryButton(label = "Add gateway", onClick = viewModel::openConnect)
                        }
                    }
                }
                val pairing = state.pairingRequest
                if (pairing != null) {
                    item {
                        val (url, requestId) = pairing
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "Pairing required",
                                style = MaterialTheme.typography.titleMedium,
                                color = Palette.Amber,
                            )
                            Text(
                                text = "Approve this device in the OpenClaw gateway, then test the " +
                                    "connection again.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Palette.Muted,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "$url\nrequest: $requestId",
                                style = MaterialTheme.typography.labelSmall,
                                color = Palette.Graphite,
                            )
                        }
                    }
                }

                item { SectionHeader(title = "Usage") }
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        val usage = state.usage
                        if (usage == null) {
                            Text(
                                "Usage is unavailable until the backend is reachable.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Palette.Muted,
                            )
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Pill(text = "${Formatting.tokenCount(usage.totalTokens)} tokens")
                                Pill(text = "${usage.requests} requests")
                                Pill(text = "${usage.averageLatencyMs} ms avg")
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "Prompt ${usage.promptTokens} · completion ${usage.completionTokens}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Palette.Muted,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        SecondaryButton(label = "Refresh", onClick = viewModel::refresh)
                    }
                }

                item { SectionHeader(title = "About") }
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Universal AI Agent ${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.titleMedium,
                            color = Palette.Ink,
                        )
                        Text(
                            text = "Pearl interface · light-only by design · Kotlin + Compose",
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.Muted,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Single-user workspace: anyone who can reach the backend can use " +
                                "it and its stored provider keys. Keep the URL private or put access " +
                                "control in front of your Vercel deployment.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Palette.Faint,
                        )
                    }
                }
            }
        }
    }

    if (state.showConnect) {
        var url by remember { mutableStateOf("ws://") }
        var token by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = viewModel::closeConnect,
            title = { Text("Connect OpenClaw gateway") },
            text = {
                Column {
                    Text(
                        text = "Use the WebSocket URL of your gateway. The token is sent to your " +
                            "backend and stored encrypted with the connection.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Muted,
                    )
                    Spacer(Modifier.height(10.dp))
                    PearlTextField("Gateway URL", url, { url = it }, placeholder = "wss://gateway:18789")
                    Spacer(Modifier.height(10.dp))
                    PearlTextField("Pairing token (optional)", token, { token = it })
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.connect(url, token) },
                    enabled = url.startsWith("ws://") || url.startsWith("wss://"),
                ) { Text("Connect") }
            },
            dismissButton = { TextButton(onClick = viewModel::closeConnect) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RuntimeCard(
    connection: RuntimeConnection,
    test: ConnectionTest?,
    busy: Boolean,
    onTest: () -> Unit,
    onDisconnect: () -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(
                active = connection.status == "connected",
                activeColor = when (connection.status) {
                    "connected" -> Palette.Sage
                    "pairing_required", "error" -> Palette.Amber
                    else -> Palette.Faint
                },
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    connection.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = Palette.Ink,
                )
                Text(
                    text = "${connection.gatewayUrl} · ${connection.status.replace('_', ' ')}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.Muted,
                )
            }
            IconButton(onClick = onDisconnect) {
                Icon(Icons.Filled.Close, contentDescription = "Disconnect", tint = Palette.Faint)
            }
        }
        if (connection.lastError != null) {
            Text(
                text = connection.lastError,
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Rose,
            )
        }
        if (test != null) {
            Text(
                text = test.message + if (test.latencyMs > 0) " (${test.latencyMs} ms)" else "",
                style = MaterialTheme.typography.bodySmall,
                color = if (test.ok) Palette.Sage else Palette.Rose,
            )
        }
        Spacer(Modifier.height(8.dp))
        SecondaryButton(label = "Test connection", onClick = onTest, enabled = !busy)
    }
}
