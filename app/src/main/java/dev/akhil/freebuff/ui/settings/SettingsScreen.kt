package dev.akhil.freebuff.ui.settings

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.akhil.freebuff.BuildConfig
import dev.akhil.freebuff.core.Format
import dev.akhil.freebuff.data.prefs.ThemeMode
import dev.akhil.freebuff.design.AppIcons
import dev.akhil.freebuff.design.Pill
import dev.akhil.freebuff.design.SectionHeader
import dev.akhil.freebuff.design.SurfaceCard
import dev.akhil.freebuff.models.BuiltInCatalog
import dev.akhil.freebuff.ui.AppViewModel

@Composable
fun SettingsScreen(viewModel: AppViewModel) {
    val settings by viewModel.settings.collectAsState()
    val models by viewModel.models.collectAsState()
    var showRemote by remember { mutableStateOf(false) }
    var showSearchKey by remember { mutableStateOf(false) }
    var confirmDeleteChats by remember { mutableStateOf(false) }
    val device = viewModel.device
    val container = viewModel.container

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("appearance") {
            SectionHeader("Appearance")
            Spacer(Modifier.height(8.dp))
            SurfaceCard {
                Text("Theme", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        Pill(
                            text = mode.name.lowercase().replaceFirstChar { it.uppercase() },
                            selected = settings.themeMode == mode,
                            onClick = { viewModel.setTheme(mode) },
                        )
                    }
                }
            }
        }

        item("generation") {
            Spacer(Modifier.height(4.dp))
            SectionHeader("Generation", subtitle = "Applies to local models on this device")
            Spacer(Modifier.height(8.dp))
            SurfaceCard {
                Text("Context window: ${settings.contextTokens} tokens", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = settings.contextTokens.toFloat(),
                    onValueChange = { viewModel.setContextTokens((it / 256).toInt() * 256) },
                    valueRange = 512f..4096f,
                    steps = 13,
                )
                Text(
                    "Larger context uses more RAM and slows prefill. 2048 is the safe default on 2 GB phones.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text("Max reply length: ${settings.maxOutputTokens} tokens", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = settings.maxOutputTokens.toFloat(),
                    onValueChange = { viewModel.setMaxOutput((it / 64).toInt() * 64) },
                    valueRange = 128f..1024f,
                    steps = 13,
                )
                Spacer(Modifier.height(12.dp))
                Text("Temperature: ${"%.2f".format(settings.temperature)}", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = settings.temperature,
                    onValueChange = { viewModel.setTemperature(it) },
                    valueRange = 0f..1.5f,
                )
                Spacer(Modifier.height(8.dp))
                SwitchRow(
                    title = "Auto-load last model on start",
                    subtitle = "Loads the model you used most recently when the app opens",
                    checked = settings.autoLoadLastModel,
                    onChange = { viewModel.setAutoLoad(it) },
                )
            }
        }

        item("downloads") {
            Spacer(Modifier.height(4.dp))
            SectionHeader("Downloads")
            Spacer(Modifier.height(8.dp))
            SurfaceCard {
                SwitchRow(
                    title = "Wi-Fi only",
                    subtitle = "Model downloads wait for an unmetered connection",
                    checked = settings.wifiOnlyDownloads,
                    onChange = { viewModel.setWifiOnly(it) },
                )
                SwitchRow(
                    title = "Allow mobile data",
                    subtitle = "A first-run install is about ${Format.bytes(BuiltInCatalog.totalDownloadBytes)}",
                    checked = settings.allowMeteredDownloads,
                    onChange = { viewModel.setAllowMetered(it) },
                )
                Row {
                    TextButton(onClick = { viewModel.pauseDownloads() }) { Text("Pause downloads") }
                    TextButton(onClick = { viewModel.resumeDownloads() }) { Text("Resume") }
                }
                Text(
                    "Downloads use WorkManager and continue when the app is closed. They stop if you force-stop " +
                        "the app or revoke network access — Android does not allow an app to bypass that.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item("voice") {
            Spacer(Modifier.height(4.dp))
            SectionHeader("Voice", subtitle = "Tap to talk only — no wake word, no background listening")
            Spacer(Modifier.height(8.dp))
            SurfaceCard {
                Text("Speech recognition language", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("auto" to "Auto", "hi" to "Hindi", "en" to "English").forEach { (code, label) ->
                        Pill(
                            text = label,
                            selected = settings.sttLanguage == code,
                            onClick = { container.voice.setLanguage(code) },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Speech rate: ${"%.2f".format(settings.speechRate)}×", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = settings.speechRate,
                    onValueChange = { viewModel.setSpeechRate(it) },
                    valueRange = 0.6f..1.6f,
                )
                SwitchRow(
                    title = "Keep raw recordings",
                    subtitle = "Off by default. Recordings are never sent anywhere; deleting them is permanent.",
                    checked = settings.retainAudio,
                    onChange = { viewModel.setRetainAudio(it) },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    container.voice.describeCapabilities(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Hindi text is spoken by the Piper voice when installed; English uses the platform voice. " +
                        "Hinglish is transcribed as Latin script and therefore spoken in English.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item("research") {
            Spacer(Modifier.height(4.dp))
            SectionHeader("Research providers")
            Spacer(Modifier.height(8.dp))
            SurfaceCard {
                container.searchRegistry.all().forEach { provider ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(provider.label, style = MaterialTheme.typography.titleMedium)
                            Text(
                                provider.note,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Pill(
                            text = if (settings.searchProviderId == provider.id) "Default" else "Use",
                            selected = settings.searchProviderId == provider.id,
                            onClick = { viewModel.setSearchProvider(provider.id) },
                        )
                    }
                }
                TextButton(onClick = { showSearchKey = true }) { Text("Set search API key (Tavily)") }
            }
        }

        item("remote") {
            Spacer(Modifier.height(4.dp))
            SectionHeader("Remote provider (optional)")
            Spacer(Modifier.height(8.dp))
            SurfaceCard {
                Text(
                    if (settings.remoteProviderEnabled) {
                        "Enabled: ${settings.remoteProviderName} · ${settings.remoteProviderModel}"
                    } else {
                        "Disabled. Local models are the default; nothing is ever sent to a server."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { showRemote = true }) { Text("Configure") }
            }
        }

        item("privacy") {
            Spacer(Modifier.height(4.dp))
            SectionHeader("Privacy and storage")
            Spacer(Modifier.height(8.dp))
            SurfaceCard {
                Text(
                    "This device: ${device.totalRamMb} MB RAM · ${Format.bytes(device.freeStorageBytes)} free on internal storage",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "${models.count { it.status == "INSTALLED" }} of ${models.size} components installed · " +
                        Format.bytes(models.filter { it.status == "INSTALLED" }.sumOf { it.sizeBytes }),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { confirmDeleteChats = true }) { Text("Delete all conversations") }
                Text(
                    "Backups are disabled for this app, so chats, models and keys are never copied off the device.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item("about") {
            Spacer(Modifier.height(4.dp))
            SectionHeader("About")
            Spacer(Modifier.height(8.dp))
            SurfaceCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    dev.akhil.freebuff.design.IconTile(AppIcons.Shield)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Freebuff Mobile", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · developed by ${BuildConfig.DEVELOPER_NAME}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Freebuff Mobile is developed by Akhil. It is an Android client that runs open-source " +
                        "models and runtimes locally; Akhil did not create those models or runtimes.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                Text("Open-source components", style = MaterialTheme.typography.titleMedium)
                Text(
                    "llama.cpp (MIT) · whisper.cpp (MIT) · ggml (MIT) · Piper voices (see voice cards) · " +
                        "ONNX Runtime (MIT) · Kotlin, Jetpack Compose, Room, WorkManager, OkHttp, kotlinx.serialization " +
                        "(Apache-2.0 / MIT).",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text("Model licences", style = MaterialTheme.typography.titleMedium)
                BuiltInCatalog.all.forEach { spec ->
                    Text(
                        "• ${spec.displayName} — ${spec.license}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Engine in this build: " +
                        if (BuildConfig.NATIVE_ENGINE_BUILT) "native llama.cpp + whisper.cpp included"
                        else "no native inference library (rebuild with -PwithNative=true)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item("spacer") { Spacer(Modifier.height(30.dp)) }
    }

    if (showRemote) {
        var enabled by remember { mutableStateOf(settings.remoteProviderEnabled) }
        var name by remember { mutableStateOf(settings.remoteProviderName) }
        var baseUrl by remember { mutableStateOf(settings.remoteProviderBaseUrl) }
        var model by remember { mutableStateOf(settings.remoteProviderModel) }
        var key by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showRemote = false },
            title = { Text("Remote provider") },
            text = {
                Column(Modifier.horizontalScroll(rememberScrollState())) {
                    Text(
                        "Any OpenAI-compatible endpoint. The key is encrypted with the Android Keystore. " +
                            "Remote replies are labelled as online, and project files are never attached.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("Base URL") }, singleLine = true)
                    OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("Model id") }, singleLine = true)
                    OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("API key (leave blank to keep)") }, singleLine = true)
                    SwitchRow(
                        title = "Enable",
                        subtitle = "Only used when you explicitly pick it",
                        checked = enabled,
                        onChange = { enabled = it },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setRemoteProvider(enabled, name, model, baseUrl, key)
                    showRemote = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showRemote = false }) { Text("Cancel") } },
        )
    }

    if (showSearchKey) {
        var key by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSearchKey = false },
            title = { Text("Search API key") },
            text = {
                Column {
                    Text(
                        "Only needed for providers that require a key. Stored encrypted in the Android Keystore.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("Key") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setSearchKey(key)
                    showSearchKey = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showSearchKey = false }) { Text("Cancel") } },
        )
    }

    if (confirmDeleteChats) {
        AlertDialog(
            onDismissRequest = { confirmDeleteChats = false },
            title = { Text("Delete all conversations?") },
            text = { Text("Every chat and message on this device will be removed. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAllChats()
                    confirmDeleteChats = false
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteChats = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
