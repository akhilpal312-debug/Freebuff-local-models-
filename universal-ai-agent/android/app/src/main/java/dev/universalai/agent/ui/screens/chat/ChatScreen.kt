@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.universalai.agent.ui.screens.chat

import android.content.Intent
import android.speech.RecognizerIntent
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.universalai.agent.core.AppContainer
import dev.universalai.agent.core.Formatting
import dev.universalai.agent.data.model.Attachment
import dev.universalai.agent.data.model.ChatMessage
import dev.universalai.agent.data.model.ProviderModel
import dev.universalai.agent.ui.components.ErrorBanner
import dev.universalai.agent.ui.components.MarkdownText
import dev.universalai.agent.ui.components.PearlBackground
import dev.universalai.agent.ui.components.PearlTextField
import dev.universalai.agent.ui.components.Pill
import dev.universalai.agent.ui.components.ScreenTopBar
import dev.universalai.agent.ui.components.rememberViewModel
import dev.universalai.agent.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val MODES = listOf("chat", "deep", "research", "coding", "agent")

@Composable
fun ChatScreen(
    container: AppContainer,
    conversationId: String,
    onBack: () -> Unit,
) {
    val viewModel = rememberViewModel(key = conversationId) {
        ChatViewModel(container, conversationId)
    }
    val state = viewModel.state
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.messages.size, state.streamingText) {
        val target = state.messages.size + if (state.streaming) 1 else 0
        if (target > 0 && listState.layoutInfo.totalItemsCount > 0) {
            listState.animateScrollToItem((target - 1).coerceAtLeast(0))
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val attachment = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: return@runCatching null
                    if (bytes.size > 4 * 1024 * 1024) return@runCatching null
                    val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                    Attachment(
                        type = "image",
                        url = "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP),
                        name = "image-${System.currentTimeMillis()}.jpg",
                    )
                }.getOrNull()
            }
            if (attachment == null) {
                viewModel.reportAttachmentError("Could not attach that image (max 4 MB).")
            } else {
                viewModel.addAttachment(attachment)
            }
        }
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            viewModel.onInputChange(listOf(state.input, spoken).filter { it.isNotBlank() }.joinToString(" "))
        }
    }

    PearlBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .navigationBarsPadding(),
        ) {
            ScreenTopBar(
                title = state.conversation?.title ?: "Conversation",
                subtitle = state.routingNote ?: (state.mode.replaceFirstChar { it.uppercase() } + " mode"),
                onBack = onBack,
            ) {
                ModeAndModelSelector(
                    mode = state.mode,
                    models = state.models,
                    selectedModelId = state.modelId,
                    onModeSelected = viewModel::selectMode,
                    onModelSelected = viewModel::selectModel,
                )
            }

            if (state.mode == "deep") {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Reasoning effort",
                        style = MaterialTheme.typography.labelSmall,
                        color = Palette.Faint,
                    )
                    listOf("low", "medium", "high").forEach { effort ->
                        Pill(
                            text = effort.replaceFirstChar { it.uppercase() },
                            selected = state.reasoningEffort == effort,
                            onClick = { viewModel.setReasoningEffort(effort) },
                        )
                    }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 18.dp, end = 18.dp, top = 8.dp, bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (state.loading) {
                    item { dev.universalai.agent.ui.components.LoadingState() }
                }
                if (state.messages.isEmpty() && !state.loading) {
                    item {
                        dev.universalai.agent.ui.components.EmptyState(
                            title = "Say hello",
                            body = when (state.mode) {
                                "deep" -> "Ask a hard question. Deep mode plans before it answers."
                                "research" -> "Ask something that needs current sources, then switch to Web Research in Knowledge."
                                "coding" -> "Describe the change you want. Add a project in Projects for file access."
                                else -> "Ask anything. Responses stream as they are generated."
                            },
                        )
                    }
                }
                items(state.messages, key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        isLastAssistant = message.role == "assistant" &&
                            message.id == state.messages.lastOrNull { it.role == "assistant" }?.id,
                        onCopy = {
                            clipboard.setText(AnnotatedString(message.content))
                        },
                        onRegenerate = viewModel::regenerate,
                        onEdit = { viewModel.beginEdit(message) },
                        onOpenUrl = { uriHandler.openUri(it) },
                    )
                }
                if (state.streaming) {
                    item(key = "streaming") {
                        StreamingBubble(text = state.streamingText, onOpenUrl = { uriHandler.openUri(it) })
                    }
                }
            }

            if (state.error != null) {
                Box(modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
                    ErrorBanner(message = state.error, onRetry = viewModel::dismissError)
                }
            }
            if (state.notice != null) {
                Text(
                    text = state.notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Muted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }

            Composer(
                state = state,
                onInputChange = viewModel::onInputChange,
                onSend = viewModel::send,
                onStop = viewModel::stop,
                onAttach = { imagePicker.launch("image/*") },
                onVoice = {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                        )
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your message")
                    }
                    runCatching { voiceLauncher.launch(intent) }
                        .onFailure { viewModel.reportAttachmentError("No speech recognizer is available.") }
                },
                onRemoveAttachment = viewModel::removeAttachment,
                onCancelEdit = viewModel::cancelEdit,
            )
        }
    }
}

@Composable
private fun ModeAndModelSelector(
    mode: String,
    models: List<ProviderModel>,
    selectedModelId: String?,
    onModeSelected: (String) -> Unit,
    onModelSelected: (String?) -> Unit,
) {
    var modeMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    val selectedModel = models.firstOrNull { it.id == selectedModelId }

    Box {
        IconButton(onClick = { modeMenu = true }) {
            Icon(Icons.Filled.Tune, contentDescription = "Mode", tint = Palette.Graphite)
        }
        DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
            MODES.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            option.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    onClick = {
                        onModeSelected(option)
                        modeMenu = false
                    },
                )
            }
        }
    }
    Box {
        Pill(
            text = selectedModel?.displayName ?: "Auto model",
            onClick = { modelMenu = true },
        )
        DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
            DropdownMenuItem(
                text = { Text("Auto (recommended)", style = MaterialTheme.typography.bodyMedium) },
                onClick = {
                    onModelSelected(null)
                    modelMenu = false
                },
            )
            models.forEach { model ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(model.displayName, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = buildString {
                                    append(model.providerName ?: "provider")
                                    if (model.supportsReasoning) append(" · reasoning")
                                    if (model.supportsVision) append(" · vision")
                                    if (model.supportsTools) append(" · tools")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = Palette.Muted,
                            )
                        }
                    },
                    onClick = {
                        onModelSelected(model.id)
                        modelMenu = false
                    },
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    isLastAssistant: Boolean,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val isUser = message.role == "user"
    Column(modifier = Modifier.fillMaxWidth()) {
        if (isUser) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.86f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Palette.Mist)
                        .padding(14.dp),
                ) {
                    if (message.content.isNotBlank()) {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Palette.Ink,
                        )
                    }
                    message.attachments.forEach { attachment ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "🖼 ${attachment.name ?: "image"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Palette.Muted,
                        )
                    }
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (message.content.isBlank() && message.error == null) {
                    Text(
                        text = "…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Palette.Faint,
                    )
                } else {
                    MarkdownText(markdown = message.content)
                }
                if (message.status == "failed") {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = message.error ?: "This response failed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Rose,
                    )
                }
                if (message.status == "cancelled") {
                    Text(
                        text = "Stopped",
                        style = MaterialTheme.typography.labelSmall,
                        color = Palette.Faint,
                    )
                }
                if (message.citations.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        message.citations.forEach { citation ->
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onOpenUrl(citation.url) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "[${citation.id}]",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Palette.Clay,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = citation.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Palette.Graphite,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = if (citation.verified) "verified" else "unverified",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (citation.verified) Palette.Sage else Palette.Amber,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = Formatting.clockTime(message.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = Palette.Faint,
                    )
                    Spacer(Modifier.width(6.dp))
                    IconButton(onClick = onCopy, modifier = Modifier.size(30.dp)) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = "Copy",
                            tint = Palette.Faint,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    if (isLastAssistant) {
                        IconButton(onClick = onRegenerate, modifier = Modifier.size(30.dp)) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = "Regenerate",
                                tint = Palette.Faint,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                    if (message.role == "user") {
                        IconButton(onClick = onEdit, modifier = Modifier.size(30.dp)) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = "Edit",
                                tint = Palette.Faint,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamingBubble(text: String, onOpenUrl: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (text.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(Palette.Clay),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Thinking",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Muted,
                )
            }
        } else {
            MarkdownText(markdown = text)
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Palette.Clay),
            )
        }
    }
}

@Composable
private fun Composer(
    state: ChatUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    onVoice: () -> Unit,
    onRemoveAttachment: (String?) -> Unit,
    onCancelEdit: () -> Unit,
) {
    Surface(
        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.92f),
        shadowElevation = 12.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            if (state.editingMessageId != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Editing your message",
                        style = MaterialTheme.typography.labelSmall,
                        color = Palette.Clay,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onCancelEdit, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Cancel edit",
                            tint = Palette.Muted,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
            if (state.pendingAttachments.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.pendingAttachments.forEach { attachment ->
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Palette.SoftGrey)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = attachment.name ?: "image",
                                style = MaterialTheme.typography.labelSmall,
                                color = Palette.Graphite,
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Remove",
                                tint = Palette.Muted,
                                modifier = Modifier
                                    .size(13.dp)
                                    .clickable { onRemoveAttachment(attachment.name) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            Row(verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = onAttach, enabled = !state.streaming) {
                    Icon(Icons.Filled.Image, contentDescription = "Attach image", tint = Palette.Muted)
                }
                PearlTextField(
                    label = "Message",
                    value = state.input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    singleLine = false,
                    minLines = 1,
                )
                IconButton(onClick = onVoice, enabled = !state.streaming) {
                    Icon(Icons.Filled.Mic, contentDescription = "Voice input", tint = Palette.Muted)
                }
                Spacer(Modifier.width(2.dp))
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Palette.Ink)
                        .clickable { if (state.streaming) onStop() else onSend() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (state.streaming) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                        contentDescription = if (state.streaming) "Stop" else "Send",
                        tint = Palette.Pearl,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }
    }
}
