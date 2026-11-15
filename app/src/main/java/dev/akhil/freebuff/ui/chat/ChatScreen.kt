package dev.akhil.freebuff.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.akhil.freebuff.ai.AiMode
import dev.akhil.freebuff.data.db.MessageEntity
import dev.akhil.freebuff.design.AppIcons
import dev.akhil.freebuff.design.EmptyState
import dev.akhil.freebuff.design.IconTile
import dev.akhil.freebuff.design.LocalExtendedColors
import dev.akhil.freebuff.design.Pill
import dev.akhil.freebuff.design.StatusChip
import dev.akhil.freebuff.design.StatusDot
import dev.akhil.freebuff.design.SurfaceCard
import dev.akhil.freebuff.ui.AppViewModel
import dev.akhil.freebuff.voice.VoiceState
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: AppViewModel, onNavigate: (String) -> Unit) {
    val state by viewModel.chatState.collectAsState()
    val voice by viewModel.voiceState.collectAsState()
    val models by viewModel.models.collectAsState()
    val active by viewModel.activeModel.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val engineState by viewModel.engineState.collectAsState()

    var input by remember { mutableStateOf("") }
    var showModes by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<MessageEntity?>(null) }
    var pendingAttachment by remember { mutableStateOf<Pair<String, String>?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.voice.startTurn(state.mode)
        else viewModel.showToast("Microphone permission denied. Voice input stays off until you allow it.")
    }

    val attachLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val resolver = context.contentResolver
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
        val text = runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                val bytes = stream.readNBytes(64 * 1024)
                String(bytes, Charsets.UTF_8)
            }
        }.getOrNull()
        pendingAttachment = if (text != null && text.none { it.code == 0 }) {
            name to text
        } else {
            name to ""
        }
    }

    LaunchedEffect(state.messages.size, state.streamingText) {
        if (state.messages.isNotEmpty() || state.streamingText.isNotEmpty()) {
            listState.animateScrollToItem(maxOf(0, state.messages.size - 1))
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Mode + model row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill(
                text = state.mode.label,
                selected = true,
                onClick = { showModes = true },
                leading = when (state.mode) {
                    AiMode.DEEP_THINKING -> AppIcons.Think
                    AiMode.CODING_AGENT, AiMode.BUILD_MODE -> AppIcons.Code
                    AiMode.WEB_SEARCH, AiMode.DEEP_RESEARCH -> AppIcons.Globe
                    AiMode.PLAN_MODE -> AppIcons.Layers
                    AiMode.FAST_CHAT -> AppIcons.Sparkle
                },
            )
            Pill(
                text = active?.displayName ?: "Load a model",
                selected = false,
                onClick = { showModels = true },
                leading = AppIcons.Cpu,
            )
            Spacer(Modifier.weight(1f))
            if (state.isGenerating) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }

        AnimatedVisibility(visible = state.error != null) {
            state.error?.let { error ->
                SurfaceCard(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(androidx.compose.material.icons.Icons.Filled.Refresh, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Could not answer", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(4.dp))
                            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { viewModel.chat.close(); viewModel.chat.clearError() }) {
                            Icon(androidx.compose.material.icons.Icons.Filled.Close, "Dismiss", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

        Box(Modifier.weight(1f)) {
            if (state.messages.isEmpty() && state.streamingText.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    EmptyState(
                        icon = AppIcons.Sparkle,
                        title = "A private AI workstation",
                        message = if (active == null) {
                            "Download a model in Models, load it, then ask anything. Everything runs on this phone."
                        } else {
                            "${active?.displayName} is loaded. Ask a question, dictate with the microphone, or switch modes."
                        },
                    )
                    val suggestions = listOf(
                        "Explain what a GGUF file is",
                        "Create a website for a bakery",
                        "Write a Kotlin function to format bytes",
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        suggestions.forEach { suggestion ->
                            SurfaceCard(onClick = { input = suggestion }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(AppIcons.Sparkle, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(10.dp))
                                    Text(suggestion, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.messages, key = { it.id }) { message ->
                        MessageRow(
                            message = message,
                            onCopy = { text ->
                                val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Freebuff", text))
                                viewModel.showToast("Copied.")
                            },
                            onRegenerate = if (message.role == "ASSISTANT") {
                                { viewModel.chat.regenerate() }
                            } else null,
                            onEdit = if (message.role == "USER") {
                                { editing = message }
                            } else null,
                            onSpeak = { viewModel.voice.speakText(it) },
                        )
                    }
                    if (state.isGenerating && state.streamingText.isNotBlank()) {
                        item("streaming") {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
                                    .padding(14.dp),
                            ) {
                                MarkdownText(state.streamingText)
                                Spacer(Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    StatusDot(MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Generating…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (voice.state == VoiceState.RECORDING || voice.state.isBusy) {
            VoiceBanner(viewModel, voice.state, voice.heardText, voice.error)
        }

        Composer(
            value = input,
            onValueChange = { input = it },
            attachmentName = pendingAttachment?.first,
            onClearAttachment = { pendingAttachment = null },
            onAttach = {
                runCatching { attachLauncher.launch(arrayOf("text/*", "application/json", "application/xml", "*/*")) }
            },
            onMic = {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (!granted) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                else if (voice.state.isBusy) viewModel.voice.stopEverything()
                else viewModel.voice.startTurn(state.mode)
            },
            isRecording = voice.state == VoiceState.RECORDING,
            isGenerating = state.isGenerating,
            onSend = {
                val attachment = pendingAttachment
                val body = buildString {
                    if (attachment != null && attachment.second.isNotBlank()) {
                        append("File: ").append(attachment.first).append("\n```\n")
                            .append(attachment.second.take(8000)).append("\n```\n\n")
                    }
                    append(input.trim())
                }
                if (body.isNotBlank()) {
                    viewModel.chat.send(body)
                    input = ""
                    pendingAttachment = null
                    scope.launch { listState.animateScrollToItem(0) }
                }
            },
            onStop = { viewModel.chat.stopGeneration() },
        )
    }

    editing?.let { message ->
        var text by remember(message.id) { mutableStateOf(message.content) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit message") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.heightIn(min = 90.dp, max = 240.dp),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.chat.editUserMessage(message.id, text)
                    editing = null
                }) { Text("Resend") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }

    if (showModes) {
        ModalBottomSheet(onDismissRequest = { showModes = false }, sheetState = rememberModalBottomSheetState()) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Text("Modes", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Each mode changes the prompt and what the app will do. Nothing switches automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                AiMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (mode == state.mode) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            )
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(mode.label, style = MaterialTheme.typography.titleMedium)
                            Text(
                                mode.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (mode.needsNetwork) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Spacer(Modifier.height(2.dp))
                                    StatusChip("Needs internet", MaterialTheme.colorScheme.tertiary)
                                }
                            }
                        }
                        TextButton(onClick = {
                            viewModel.setActiveMode(mode)
                            showModes = false
                        }) { Text(if (mode == state.mode) "Selected" else "Use") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }

    if (showModels) {
        ModalBottomSheet(onDismissRequest = { showModels = false }, sheetState = rememberModalBottomSheetState()) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Text("Model", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Switching unloads the current model first, so only one is ever in memory.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                val installed = models.filter { it.status == "INSTALLED" }
                if (installed.isEmpty()) {
                    Text("No models installed yet.", style = MaterialTheme.typography.bodyMedium)
                }
                installed.forEach { record ->
                    val selected = active?.recordId == record.id
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(record.displayName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${record.quantization} · ${dev.akhil.freebuff.core.Format.bytes(record.sizeBytes)} · ${record.architecture}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = {
                            if (selected) viewModel.unloadModel() else viewModel.loadModel(record.id)
                            showModels = false
                        }) { Text(if (selected) "Unload" else "Load") }
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = {
                    showModels = false
                    onNavigate(dev.akhil.freebuff.ui.shell.Routes.MODELS)
                }) { Text("Manage models") }
                if (engineState is dev.akhil.freebuff.inference.LoadState.Failed) {
                    Text(
                        (engineState as dev.akhil.freebuff.inference.LoadState.Failed).message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageRow(
    message: MessageEntity,
    onCopy: (String) -> Unit,
    onRegenerate: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    onSpeak: (String) -> Unit,
) {
    val isUser = message.role == "USER"
    val extended = LocalExtendedColors.current
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(if (isUser) 0.92f else 1f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (isUser) extended.userBubble else MaterialTheme.colorScheme.surface)
                    .then(
                        if (isUser) Modifier
                        else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp)),
                    )
                    .padding(horizontal = 15.dp, vertical = 12.dp),
            ) {
                if (isUser) {
                    Text(
                        message.content,
                        style = MaterialTheme.typography.bodyLarge,
                        color = extended.onUserBubble,
                    )
                } else {
                    if (message.content.isBlank() && message.status == "ERROR") {
                        Text(
                            message.error ?: "No answer was produced.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        MarkdownText(message.content)
                    }
                }
                if (message.status == "STOPPED") {
                    Spacer(Modifier.height(6.dp))
                    Text("Stopped", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (!isUser || onEdit != null) {
            Row(
                modifier = Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!isUser) {
                    TextButton(onClick = { onCopy(message.content) }) {
                        Text("Copy", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { onSpeak(message.content) }) {
                        Text("Speak", style = MaterialTheme.typography.labelSmall)
                    }
                    if (onRegenerate != null) {
                        TextButton(onClick = onRegenerate) {
                            Text("Regenerate", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (message.fromRemote) {
                        StatusChip("Online reply", MaterialTheme.colorScheme.tertiary)
                    }
                    if (message.tokenCount > 0) {
                        Text(
                            " · ${message.tokenCount} tokens · ${dev.akhil.freebuff.core.Format.tokensPerSecond(message.tokenCount, message.elapsedMs)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (onEdit != null) {
                    TextButton(onClick = onEdit) {
                        Text("Edit", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceBanner(viewModel: AppViewModel, state: VoiceState, heard: String, error: String?) {
    SurfaceCard(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(AppIcons.Waveform, size = 32)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.label, style = MaterialTheme.typography.titleMedium)
                    if (heard.isNotBlank()) {
                        Text(heard, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (error != null) {
                        Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = { viewModel.voice.stopEverything() }) { Text("Stop") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    attachmentName: String?,
    onClearAttachment: () -> Unit,
    onAttach: () -> Unit,
    onMic: () -> Unit,
    isRecording: Boolean,
    isGenerating: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (attachmentName != null) {
            Row(
                modifier = Modifier
                    .padding(bottom = 6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(attachmentName, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onClearAttachment, modifier = Modifier.size(18.dp)) {
                    Icon(androidx.compose.material.icons.Icons.Filled.Close, "Remove attachment", Modifier.size(14.dp))
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(26.dp))
                .padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            IconButton(onClick = onAttach) {
                Icon(androidx.compose.material.icons.Icons.Filled.Add, "Attach a file", Modifier.size(20.dp))
            }
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 150.dp),
                placeholder = { Text("Message Freebuff Mobile", style = MaterialTheme.typography.bodyMedium) },
                textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            IconButton(onClick = onMic) {
                Icon(
                    if (isRecording) AppIcons.Stop else AppIcons.Mic,
                    contentDescription = if (isRecording) "Stop recording" else "Tap to talk",
                    modifier = Modifier.size(21.dp),
                    tint = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(
                modifier = Modifier
                    .padding(6.dp)
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(
                        if (value.isBlank() && !isGenerating) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.primary,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = { if (isGenerating) onStop() else onSend() }) {
                    Icon(
                        if (isGenerating) AppIcons.Stop else Icons.Filled.Send,
                        contentDescription = if (isGenerating) "Stop generating" else "Send",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
        Text(
            text = "Runs on this device. Tap to talk only — the microphone never listens in the background.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, start = 10.dp),
        )
    }
}
