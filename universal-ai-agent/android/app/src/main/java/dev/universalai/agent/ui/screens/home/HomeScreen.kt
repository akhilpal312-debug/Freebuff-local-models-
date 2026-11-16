package dev.universalai.agent.ui.screens.home

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.universalai.agent.core.AppContainer
import dev.universalai.agent.core.Formatting
import dev.universalai.agent.data.model.Agent
import dev.universalai.agent.data.model.Conversation
import dev.universalai.agent.data.model.Project
import dev.universalai.agent.data.model.Provider
import dev.universalai.agent.data.model.ProviderModel
import dev.universalai.agent.ui.components.EmptyState
import dev.universalai.agent.ui.components.ErrorBanner
import dev.universalai.agent.ui.components.GlassCard
import dev.universalai.agent.ui.components.LoadingState
import dev.universalai.agent.ui.components.PearlBackground
import dev.universalai.agent.ui.components.Pill
import dev.universalai.agent.ui.components.PrimaryButton
import dev.universalai.agent.ui.components.SectionHeader
import dev.universalai.agent.ui.components.StatusDot
import dev.universalai.agent.ui.components.rememberViewModel
import dev.universalai.agent.ui.theme.Palette
import kotlinx.coroutines.launch

data class HomeUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val conversations: List<Conversation> = emptyList(),
    val projects: List<Project> = emptyList(),
    val models: List<ProviderModel> = emptyList(),
    val providers: List<Provider> = emptyList(),
    val agents: List<Agent> = emptyList(),
    val creatingMode: String? = null,
    val greeting: String = Formatting.greeting(),
    val date: String = Formatting.today(),
)

class HomeViewModel(private val container: AppContainer) : ViewModel() {

    var state by mutableStateOf(HomeUiState())
        private set

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            state = state.copy(loading = true, error = null)
            val conversations = container.chat.conversations()
            val projects = container.projects.list()
            val providers = container.models.providers()
            val models = container.models.models()
            val agents = container.agents.agents()
            val failure = listOf(conversations, projects, providers, models, agents)
                .filterIsInstance<dev.universalai.agent.core.AppResult.Failure>()
                .firstOrNull()
            state = state.copy(
                loading = false,
                error = failure?.message,
                conversations = conversations.valueOrNull().orEmpty(),
                projects = projects.valueOrNull().orEmpty(),
                providers = providers.valueOrNull().orEmpty(),
                models = models.valueOrNull().orEmpty(),
                agents = agents.valueOrNull().orEmpty(),
            )
        }
    }

    fun startConversation(mode: String, onCreated: (String) -> Unit) {
        if (state.creatingMode != null) return
        state = state.copy(creatingMode = mode)
        viewModelScope.launch {
            val result = container.chat.create(mode)
            state = state.copy(creatingMode = null)
            result.valueOrNull()?.let { onCreated(it.id) }
                ?: run { state = state.copy(error = (result as? dev.universalai.agent.core.AppResult.Failure)?.message) }
        }
    }

    val connectedProviders: Int
        get() = state.providers.count { it.enabled && (it.hasCredentials || !it.requiresKey) }
}

private data class ModeShortcut(
    val mode: String,
    val label: String,
    val icon: ImageVector,
)

private val MODE_SHORTCUTS = listOf(
    ModeShortcut("chat", "Chat", Icons.Filled.AutoAwesome),
    ModeShortcut("deep", "Deep Thinking", Icons.Filled.Insights),
    ModeShortcut("research", "Web Research", Icons.Filled.Public),
    ModeShortcut("coding", "Coding", Icons.Filled.Code),
)

@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenConversation: (String) -> Unit,
    onOpenAgents: () -> Unit,
    onOpenProjects: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenKnowledge: () -> Unit,
) {
    val viewModel = rememberViewModel { HomeViewModel(container) }
    val state = viewModel.state

    PearlBackground {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp, end = 20.dp, top = 18.dp, bottom = 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                Column {
                    Text(
                        text = state.date,
                        style = MaterialTheme.typography.labelSmall,
                        color = Palette.Faint,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = state.greeting,
                        style = MaterialTheme.typography.displaySmall,
                        color = Palette.Ink,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "What should we work on?",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.Muted,
                    )
                }
            }

            if (state.error != null) {
                item { ErrorBanner(state.error, onRetry = viewModel::refresh) }
            }

            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "New conversation",
                                style = MaterialTheme.typography.titleMedium,
                                color = Palette.Ink,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = if (state.models.isEmpty()) {
                                    "Connect a provider to start"
                                } else {
                                    "${state.models.size} models available"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = Palette.Muted,
                            )
                        }
                        StatusDot(
                            active = viewModel.connectedProviders > 0,
                            label = "${viewModel.connectedProviders} connected",
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MODE_SHORTCUTS.take(2).forEach { shortcut ->
                            PrimaryButton(
                                label = shortcut.label,
                                onClick = { viewModel.startConversation(shortcut.mode, onOpenConversation) },
                                modifier = Modifier.weight(1f),
                                loading = state.creatingMode == shortcut.mode,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MODE_SHORTCUTS.drop(2).forEach { shortcut ->
                            Pill(
                                text = shortcut.label,
                                onClick = { viewModel.startConversation(shortcut.mode, onOpenConversation) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            item {
                SectionHeader(
                    title = "Jump back in",
                    subtitle = "Recent conversations",
                    actionLabel = if (state.conversations.isNotEmpty()) "Refresh" else null,
                    onAction = viewModel::refresh,
                )
            }

            if (state.loading) {
                item { LoadingState("Loading your workspace") }
            } else if (state.conversations.isEmpty()) {
                item {
                    EmptyState(
                        title = "No conversations yet",
                        body = "Start with Chat for a quick question, or Deep Thinking for a harder problem.",
                    )
                }
            } else {
                items(state.conversations.take(4), key = { it.id }) { conversation ->
                    ConversationRow(conversation) { onOpenConversation(conversation.id) }
                }
            }

            item {
                SectionHeader(
                    title = "Projects",
                    subtitle = if (state.projects.isEmpty()) "Import a repository to start" else
                        "${state.projects.size} workspace(s)",
                    actionLabel = "Open",
                    onAction = onOpenProjects,
                )
            }
            items(state.projects.take(2), key = { it.id }) { project ->
                GlassCard(modifier = Modifier.fillMaxWidth(), onClick = onOpenProjects) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = project.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = Palette.Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = if (project.readOnly) "read-only" else "editable",
                            style = MaterialTheme.typography.labelSmall,
                            color = Palette.Muted,
                        )
                    }
                }
            }

            item {
                SectionHeader(
                    title = "Agents",
                    subtitle = "Specialists you can run on a plan",
                    actionLabel = "Studio",
                    onAction = onOpenAgents,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val specialists = state.agents.filter { it.kind in setOf("coding", "research", "project_manager") }
                        .ifEmpty { emptyList() }
                    if (specialists.isEmpty()) {
                        Text(
                            text = "Open Agent Studio to add a coding, research or project manager agent.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.Muted,
                        )
                    } else {
                        specialists.take(3).forEach { agent ->
                            AgentShortcut(agent = agent, onClick = onOpenAgents, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            item {
                GlassCard(modifier = Modifier.fillMaxWidth(), onClick = onOpenModels) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Model Hub",
                                style = MaterialTheme.typography.titleMedium,
                                color = Palette.Ink,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = if (state.providers.isEmpty()) {
                                    "No providers configured yet"
                                } else {
                                    "${state.providers.count { it.enabled }} active · " +
                                        "${state.models.count { it.supportsReasoning }} reasoning-capable"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = Palette.Muted,
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = Palette.Faint,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            item {
                GlassCard(modifier = Modifier.fillMaxWidth(), onClick = onOpenKnowledge) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Description,
                            contentDescription = null,
                            tint = Palette.Graphite,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Knowledge & research",
                                style = MaterialTheme.typography.titleMedium,
                                color = Palette.Ink,
                            )
                            Text(
                                text = "Notes, AI summaries and cited web research",
                                style = MaterialTheme.typography.bodySmall,
                                color = Palette.Muted,
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = Palette.Faint,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(conversation: Conversation, onClick: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth(), onClick = onClick, padding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Palette.Mist),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = conversation.title.take(1).uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = Palette.Graphite,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = conversation.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Palette.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${conversation.mode.replaceFirstChar { it.uppercase() }} · " +
                        Formatting.relativeTime(conversation.updatedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Muted,
                )
            }
        }
    }
}

@Composable
private fun AgentShortcut(agent: Agent, onClick: () -> Unit, modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier, onClick = onClick, padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
        Icon(
            imageVector = when (agent.kind) {
                "coding" -> Icons.Filled.Code
                "research" -> Icons.Filled.Public
                "document" -> Icons.Filled.Description
                else -> Icons.Filled.AutoAwesome
            },
            contentDescription = null,
            tint = Palette.Graphite,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = agent.name,
            style = MaterialTheme.typography.titleMedium,
            color = Palette.Ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = agent.permissionProfile.replace('_', ' '),
            style = MaterialTheme.typography.labelSmall,
            color = Palette.Faint,
            maxLines = 1,
        )
    }
}
