package dev.universalai.agent.ui.screens.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.universalai.agent.core.AppContainer
import dev.universalai.agent.core.AppResult
import dev.universalai.agent.core.Formatting
import dev.universalai.agent.data.model.Conversation
import dev.universalai.agent.ui.components.EmptyState
import dev.universalai.agent.ui.components.ErrorBanner
import dev.universalai.agent.ui.components.GlassCard
import dev.universalai.agent.ui.components.LoadingState
import dev.universalai.agent.ui.components.PearlBackground
import dev.universalai.agent.ui.components.Pill
import dev.universalai.agent.ui.components.PrimaryButton
import dev.universalai.agent.ui.components.ScreenTopBar
import dev.universalai.agent.ui.components.rememberViewModel
import dev.universalai.agent.ui.theme.Palette
import kotlinx.coroutines.launch

data class ConversationsUiState(
    val loading: Boolean = true,
    val conversations: List<Conversation> = emptyList(),
    val mode: String = "chat",
    val creating: Boolean = false,
    val error: String? = null,
)

class ConversationsViewModel(private val container: AppContainer) : ViewModel() {

    var state by mutableStateOf(ConversationsUiState())
        private set

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            state = state.copy(loading = true)
            val result = container.chat.conversations()
            state = state.copy(
                loading = false,
                conversations = result.valueOrNull().orEmpty(),
                error = (result as? AppResult.Failure)?.message,
            )
        }
    }

    fun selectMode(mode: String) { state = state.copy(mode = mode) }

    fun create(onCreated: (String) -> Unit) {
        if (state.creating) return
        state = state.copy(creating = true)
        viewModelScope.launch {
            val result = container.chat.create(state.mode)
            state = state.copy(
                creating = false,
                error = (result as? AppResult.Failure)?.message,
            )
            result.valueOrNull()?.let { onCreated(it.id) }
        }
    }

    fun delete(conversation: Conversation) {
        viewModelScope.launch {
            container.chat.delete(conversation.id)
            refresh()
        }
    }
}

@Composable
fun ConversationsScreen(container: AppContainer, onOpenConversation: (String) -> Unit) {
    val viewModel = rememberViewModel { ConversationsViewModel(container) }
    val state = viewModel.state

    PearlBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenTopBar(title = "Chat", subtitle = "Every mode, one history")
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 18.dp, end = 18.dp, top = 4.dp, bottom = 30.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("chat", "deep", "research", "coding").forEach { mode ->
                            Pill(
                                text = mode.replaceFirstChar { it.uppercase() },
                                selected = state.mode == mode,
                                onClick = { viewModel.selectMode(mode) },
                            )
                        }
                    }
                }
                item {
                    PrimaryButton(
                        label = "New ${state.mode} conversation",
                        onClick = { viewModel.create(onOpenConversation) },
                        modifier = Modifier.fillMaxWidth(),
                        loading = state.creating,
                        icon = Icons.Filled.Add,
                    )
                }
                if (state.error != null) {
                    item { ErrorBanner(state.error, onRetry = viewModel::refresh) }
                }
                if (state.loading) {
                    item { LoadingState("Loading conversations") }
                } else if (state.conversations.isEmpty()) {
                    item {
                        EmptyState(
                            title = "No conversations",
                            body = "Start one above. Deep mode plans before answering; " +
                                "research mode cites retrieved pages.",
                        )
                    }
                }
                items(state.conversations, key = { it.id }) { conversation ->
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onOpenConversation(conversation.id) },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = conversation.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Palette.Ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${conversation.mode} · " +
                                        Formatting.relativeTime(conversation.updatedAt) +
                                        (conversation.messageCount?.let { " · $it messages" } ?: ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Palette.Muted,
                                )
                            }
                            IconButton(onClick = { viewModel.delete(conversation) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Delete conversation",
                                    tint = Palette.Faint,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
