package dev.universalai.agent.ui.screens.models

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.universalai.agent.core.AppContainer
import dev.universalai.agent.core.AppResult
import dev.universalai.agent.data.model.ConnectionTest
import dev.universalai.agent.data.model.Provider
import dev.universalai.agent.data.model.ProviderModel
import dev.universalai.agent.data.remote.CustomModelInput
import dev.universalai.agent.ui.components.ErrorBanner
import dev.universalai.agent.ui.components.GlassCard
import dev.universalai.agent.ui.components.LoadingState
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

data class ModelHubUiState(
    val loading: Boolean = true,
    val providers: List<Provider> = emptyList(),
    val models: List<ProviderModel> = emptyList(),
    val error: String? = null,
    val notice: String? = null,
    val busyProviderId: String? = null,
    val testResult: ConnectionTest? = null,
    val testProviderName: String? = null,
    val keyProvider: Provider? = null,
    val showAddProvider: Boolean = false,
)

class ModelHubViewModel(private val container: AppContainer) : ViewModel() {

    var state by mutableStateOf(ModelHubUiState())
        private set

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            state = state.copy(loading = true)
            val providers = container.models.providers()
            val models = container.models.models()
            state = state.copy(
                loading = false,
                providers = providers.valueOrNull().orEmpty(),
                models = models.valueOrNull().orEmpty(),
                error = (providers as? AppResult.Failure)?.message
                    ?: (models as? AppResult.Failure)?.message,
            )
        }
    }

    fun toggle(provider: Provider, enabled: Boolean) {
        state = state.copy(busyProviderId = provider.id)
        viewModelScope.launch {
            val result = container.models.setEnabled(provider.id, enabled)
            state = state.copy(
                busyProviderId = null,
                error = (result as? AppResult.Failure)?.message,
                notice = if (result.isSuccess) {
                    "${provider.name} ${if (enabled) "enabled" else "disabled"}."
                } else {
                    state.notice
                },
            )
            refresh()
        }
    }

    fun test(provider: Provider) {
        state = state.copy(busyProviderId = provider.id, testResult = null)
        viewModelScope.launch {
            val result = container.models.test(provider.id)
            state = state.copy(
                busyProviderId = null,
                testResult = result.valueOrNull(),
                testProviderName = provider.name,
                error = (result as? AppResult.Failure)?.message,
            )
        }
    }

    fun sync(provider: Provider) {
        state = state.copy(busyProviderId = provider.id)
        viewModelScope.launch {
            val result = container.models.sync(provider.id)
            state = state.copy(
                busyProviderId = null,
                notice = result.valueOrNull()?.let { "Imported $it model(s) from ${provider.name}." }
                    ?: (result as? AppResult.Failure)?.message,
            )
            refresh()
        }
    }

    fun setDefault(model: ProviderModel) {
        viewModelScope.launch {
            val result = container.models.setDefault(model.id)
            state = state.copy(
                notice = result.valueOrNull()?.let { "${it.displayName} is now the default." }
                    ?: (result as? AppResult.Failure)?.message,
            )
            refresh()
        }
    }

    fun toggleCapability(model: ProviderModel, capability: String) {
        viewModelScope.launch {
            val result = when (capability) {
                "tools" -> container.models.updateModel(model.id, supportsTools = !model.supportsTools)
                "vision" -> container.models.updateModel(model.id, supportsVision = !model.supportsVision)
                "reasoning" -> container.models.updateModel(model.id, supportsReasoning = !model.supportsReasoning)
                else -> return@launch
            }
            state = state.copy(error = (result as? AppResult.Failure)?.message)
            refresh()
        }
    }

    fun openKeyDialog(provider: Provider?) { state = state.copy(keyProvider = provider) }
    fun openAddProvider() { state = state.copy(showAddProvider = true) }
    fun closeDialogs() { state = state.copy(keyProvider = null, showAddProvider = false) }
    fun clearTest() { state = state.copy(testResult = null, testProviderName = null) }

    fun saveKey(providerId: String, key: String, onDone: () -> Unit) {
        viewModelScope.launch {
            val result = container.models.setKey(providerId, key)
            state = state.copy(
                notice = result.valueOrNull()?.let { "Key saved for ${state.keyProvider?.name}." }
                    ?: (result as? AppResult.Failure)?.message,
                error = (result as? AppResult.Failure)?.message,
            )
            if (result.isSuccess) onDone()
            refresh()
        }
    }

    fun addProvider(
        name: String,
        baseUrl: String,
        compat: String,
        privacy: String,
        apiKey: String,
        modelIds: List<String>,
        onDone: () -> Unit,
    ) {
        viewModelScope.launch {
            val result = container.models.createCustom(
                name = name,
                baseUrl = baseUrl,
                apiCompat = compat,
                privacyTier = privacy,
                apiKey = apiKey.ifBlank { null },
                models = modelIds.filter { it.isNotBlank() }.map { CustomModelInput(modelId = it.trim()) },
            )
            state = state.copy(
                notice = result.valueOrNull()?.let { "${it.name} added. Test the connection next." }
                    ?: (result as? AppResult.Failure)?.message,
                error = (result as? AppResult.Failure)?.message,
            )
            if (result.isSuccess) onDone()
            refresh()
        }
    }
}

@Composable
fun ModelHubScreen(container: AppContainer) {
    val viewModel = rememberViewModel { ModelHubViewModel(container) }
    val state = viewModel.state

    PearlBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenTopBar(
                title = "Model Hub",
                subtitle = "Providers, capabilities and routing",
            ) {
                IconButton(onClick = viewModel::openAddProvider) {
                    Icon(Icons.Filled.Add, contentDescription = "Add provider", tint = Palette.Graphite)
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 18.dp, end = 18.dp, top = 4.dp, bottom = 28.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        text = "Keys are encrypted on the server. This screen never receives them back.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Muted,
                    )
                }
                if (state.error != null) {
                    item { ErrorBanner(state.error, onRetry = viewModel::refresh) }
                }
                if (state.notice != null) {
                    item {
                        Text(
                            text = state.notice.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.Sage,
                        )
                    }
                }
                if (state.testResult != null) {
                    item {
                        val test = state.testResult
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusDot(active = test.ok, label = null)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = state.testProviderName ?: "Connection test",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Palette.Ink,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = viewModel::clearTest) { Text("Dismiss") }
                            }
                            Text(
                                text = test.message + if (test.latencyMs > 0) " (${test.latencyMs} ms)" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (test.ok) Palette.Graphite else Palette.Rose,
                            )
                        }
                    }
                }
                if (state.loading) {
                    item { LoadingState("Loading providers") }
                }
                items(state.providers, key = { it.id }) { provider ->
                    ProviderCard(
                        provider = provider,
                        busy = state.busyProviderId == provider.id,
                        onToggle = { viewModel.toggle(provider, it) },
                        onTest = { viewModel.test(provider) },
                        onSync = { viewModel.sync(provider) },
                        onKey = { viewModel.openKeyDialog(provider) },
                        onSetDefault = viewModel::setDefault,
                        onToggleCapability = viewModel::toggleCapability,
                    )
                }
            }
        }
    }

    val keyProvider = state.keyProvider
    if (keyProvider != null) {
        KeyDialog(
            provider = keyProvider,
            onDismiss = viewModel::closeDialogs,
            onSave = { key -> viewModel.saveKey(keyProvider.id, key, viewModel::closeDialogs) },
        )
    }
    if (state.showAddProvider) {
        AddProviderDialog(
            onDismiss = viewModel::closeDialogs,
            onAdd = { name, url, compat, privacy, key, models ->
                viewModel.addProvider(name, url, compat, privacy, key, models, viewModel::closeDialogs)
            },
        )
    }
}

@Composable
private fun ProviderCard(
    provider: Provider,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onTest: () -> Unit,
    onSync: () -> Unit,
    onKey: () -> Unit,
    onSetDefault: (ProviderModel) -> Unit,
    onToggleCapability: (ProviderModel, String) -> Unit,
) {
    var expanded by remember { mutableStateOf(provider.enabled) }
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(
                active = provider.enabled,
                label = null,
                activeColor = if (provider.hasCredentials || !provider.requiresKey) Palette.Sage else Palette.Amber,
            )
            Spacer(Modifier.width(10.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { expanded = !expanded },
            ) {
                Text(
                    text = provider.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = Palette.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(provider.apiCompat)
                        append(" · ")
                        append(provider.privacyTier)
                        if (provider.requiresKey && !provider.hasCredentials) append(" · key needed")
                        if (provider.hasCredentials) append(" · key saved")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.Muted,
                )
            }
            Switch(
                checked = provider.enabled,
                onCheckedChange = onToggle,
                enabled = !busy,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Palette.Pearl,
                    checkedTrackColor = Palette.Ink,
                    uncheckedThumbColor = Palette.Faint,
                    uncheckedTrackColor = Palette.SoftGrey,
                ),
            )
        }
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            provider.notes?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Muted,
                )
                Spacer(Modifier.height(8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(label = "Test", onClick = onTest, enabled = !busy, icon = Icons.Filled.Science)
                SecondaryButton(label = "Sync", onClick = onSync, enabled = !busy, icon = Icons.Filled.Refresh)
                SecondaryButton(
                    label = if (provider.hasCredentials) "Replace key" else "Add key",
                    onClick = onKey,
                    enabled = !busy,
                    icon = Icons.Filled.Key,
                )
            }
            Spacer(Modifier.height(12.dp))
            provider.models.forEach { model ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = model.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Palette.Ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (model.isDefault) {
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "Default",
                                    tint = Palette.Sage,
                                    modifier = Modifier.size(13.dp),
                                )
                            }
                        }
                        Text(
                            text = buildString {
                                append("${model.contextWindow / 1000}k context")
                                append(" · ${model.costTier}")
                                append(" · ${model.availability}")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = Palette.Faint,
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Pill(
                                text = "tools",
                                selected = model.supportsTools,
                                onClick = { onToggleCapability(model, "tools") },
                            )
                            Pill(
                                text = "vision",
                                selected = model.supportsVision,
                                onClick = { onToggleCapability(model, "vision") },
                            )
                            Pill(
                                text = "reasoning",
                                selected = model.supportsReasoning,
                                onClick = { onToggleCapability(model, "reasoning") },
                            )
                        }
                    }
                    IconButton(onClick = { onSetDefault(model) }) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = "Set default",
                            tint = if (model.isDefault) Palette.Clay else Palette.Faint,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyDialog(provider: Provider, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("API key for ${provider.name}") },
        text = {
            Column {
                Text(
                    text = "The key is sent once to your backend and stored encrypted. " +
                        "It is never displayed again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Muted,
                )
                Spacer(Modifier.height(12.dp))
                PearlTextField(
                    label = "API key",
                    value = key,
                    onValueChange = { key = it },
                    placeholder = "sk-…",
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (key.isNotBlank()) onSave(key.trim()) },
                enabled = key.isNotBlank(),
            ) { Text("Save key") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AddProviderDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, String, String, String, List<String>) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("https://") }
    var compat by remember { mutableStateOf("openai") }
    var privacy by remember { mutableStateOf("cloud") }
    var apiKey by remember { mutableStateOf("") }
    var modelIds by remember { mutableStateOf("") }
    var compatMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom provider") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth(),
            ) {
                Text(
                    text = "Any endpoint that speaks the OpenAI, Anthropic or Gemini API. " +
                        "Model ids are comma separated.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Muted,
                )
                Spacer(Modifier.height(10.dp))
                PearlTextField("Name", name, { name = it }, placeholder = "My gateway")
                Spacer(Modifier.height(8.dp))
                PearlTextField("Base URL", baseUrl, { baseUrl = it }, placeholder = "https://host/v1")
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("API dialect", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                    Spacer(Modifier.width(10.dp))
                    Box {
                        Pill(text = compat, onClick = { compatMenu = true })
                        DropdownMenu(expanded = compatMenu, onDismissRequest = { compatMenu = false }) {
                            listOf("openai", "anthropic", "gemini").forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option) },
                                    onClick = { compat = option; compatMenu = false },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(
                        text = "Cloud",
                        selected = privacy == "cloud",
                        onClick = { privacy = "cloud" },
                    )
                    Pill(
                        text = "Local / self-hosted",
                        selected = privacy == "local",
                        onClick = { privacy = "local" },
                    )
                }
                Spacer(Modifier.height(8.dp))
                PearlTextField("API key (optional)", apiKey, { apiKey = it })
                Spacer(Modifier.height(8.dp))
                PearlTextField(
                    "Model ids",
                    modelIds,
                    { modelIds = it },
                    placeholder = "model-a, model-b",
                    singleLine = false,
                    minLines = 2,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onAdd(name.trim(), baseUrl.trim(), compat, privacy, apiKey.trim(), modelIds.split(","))
                },
                enabled = name.isNotBlank() && baseUrl.startsWith("http"),
            ) { Text("Add provider") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
