package dev.universalai.agent.ui.screens.agents

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.universalai.agent.core.AppContainer
import dev.universalai.agent.core.AppResult
import dev.universalai.agent.core.Formatting
import dev.universalai.agent.data.model.Agent
import dev.universalai.agent.data.model.Project
import dev.universalai.agent.data.model.ProviderModel
import dev.universalai.agent.data.model.Run
import dev.universalai.agent.data.model.ToolInfo
import dev.universalai.agent.ui.components.EmptyState
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

data class AgentStudioUiState(
    val loading: Boolean = true,
    val agents: List<Agent> = emptyList(),
    val templates: List<Agent> = emptyList(),
    val runs: List<Run> = emptyList(),
    val tools: List<ToolInfo> = emptyList(),
    val models: List<ProviderModel> = emptyList(),
    val projects: List<Project> = emptyList(),
    val error: String? = null,
    val notice: String? = null,
    val starting: Boolean = false,
    val showCreate: Boolean = false,
    val runAgent: Agent? = null,
)

class AgentStudioViewModel(private val container: AppContainer) : ViewModel() {

    var state by mutableStateOf(AgentStudioUiState())
        private set

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            state = state.copy(loading = true)
            val agents = container.agents.agents()
            val templates = container.agents.templates()
            val runs = container.agents.runs()
            val tools = container.agents.tools()
            val models = container.models.models()
            val projects = container.projects.list()
            val failure = listOf(agents, templates, runs, models)
                .filterIsInstance<AppResult.Failure>().firstOrNull()
            state = state.copy(
                loading = false,
                agents = agents.valueOrNull().orEmpty(),
                templates = templates.valueOrNull().orEmpty(),
                runs = runs.valueOrNull().orEmpty(),
                tools = tools.valueOrNull().orEmpty(),
                models = models.valueOrNull().orEmpty(),
                projects = projects.valueOrNull().orEmpty(),
                error = failure?.message,
            )
        }
    }

    fun openCreate() { state = state.copy(showCreate = true) }
    fun closeDialogs() { state = state.copy(showCreate = false, runAgent = null) }
    fun openRun(agent: Agent?) { state = state.copy(runAgent = agent) }

    fun createFromTemplate(template: Agent) {
        viewModelScope.launch {
            val result = container.agents.create(
                name = template.name,
                kind = template.kind,
                instructions = template.instructions,
                modelId = template.modelId,
                runtime = template.runtime,
                permissionProfile = template.permissionProfile,
                memoryScope = template.memoryScope,
                tools = template.enabledTools,
                projectId = template.projectId,
                templateName = template.name,
            )
            state = state.copy(
                notice = result.valueOrNull()?.let { "${it.name} added to your agents." }
                    ?: (result as? AppResult.Failure)?.message,
                error = (result as? AppResult.Failure)?.message,
            )
            refresh()
        }
    }

    fun createAgent(
        name: String,
        kind: String,
        instructions: String,
        modelId: String?,
        runtime: String,
        permission: String,
        memory: String,
        tools: List<String>,
        projectId: String?,
        onDone: () -> Unit,
    ) {
        viewModelScope.launch {
            val result = container.agents.create(
                name = name,
                kind = kind,
                instructions = instructions,
                modelId = modelId,
                runtime = runtime,
                permissionProfile = permission,
                memoryScope = memory,
                tools = tools,
                projectId = projectId,
            )
            state = state.copy(
                notice = result.valueOrNull()?.let { "${it.name} created." }
                    ?: (result as? AppResult.Failure)?.message,
                error = (result as? AppResult.Failure)?.message,
            )
            if (result.isSuccess) onDone()
            refresh()
        }
    }

    fun delete(agent: Agent) {
        viewModelScope.launch {
            container.agents.delete(agent.id)
            refresh()
        }
    }

    fun startRun(
        objective: String,
        agent: Agent?,
        runtime: String?,
        onStarted: (String) -> Unit,
    ) {
        if (state.starting) return
        state = state.copy(starting = true, error = null)
        viewModelScope.launch {
            val result = container.agents.start(
                objective = objective,
                agentId = agent?.id,
                projectId = agent?.projectId,
                runtime = runtime ?: agent?.runtime,
                modelId = agent?.modelId,
                permissionProfile = agent?.permissionProfile,
                tools = agent?.enabledTools,
            )
            state = state.copy(
                starting = false,
                error = (result as? AppResult.Failure)?.message,
                runAgent = null,
            )
            result.valueOrNull()?.let { onStarted(it.id) }
            refresh()
        }
    }
}

@Composable
fun AgentStudioScreen(container: AppContainer, onOpenRun: (String) -> Unit) {
    val viewModel = rememberViewModel { AgentStudioViewModel(container) }
    val state = viewModel.state

    PearlBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenTopBar(
                title = "Agent Studio",
                subtitle = "Plans, tools, permissions and runtime",
            ) {
                IconButton(onClick = viewModel::openCreate) {
                    Icon(Icons.Filled.Add, contentDescription = "New agent", tint = Palette.Graphite)
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 18.dp, end = 18.dp, top = 4.dp, bottom = 30.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.error != null) {
                    item { ErrorBanner(state.error, onRetry = viewModel::refresh) }
                }
                if (state.notice != null) {
                    item {
                        Text(
                            state.notice.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.Sage,
                        )
                    }
                }
                item {
                    PrimaryButton(
                        label = "Start a run",
                        onClick = { viewModel.openRun(state.agents.firstOrNull()) },
                        modifier = Modifier.fillMaxWidth(),
                        icon = Icons.Filled.PlayArrow,
                    )
                }
                item {
                    SectionHeader(
                        title = "Your agents",
                        subtitle = "${state.agents.size} configured",
                        actionLabel = "Refresh",
                        onAction = viewModel::refresh,
                    )
                }
                if (state.loading) {
                    item { LoadingState("Loading agents") }
                } else if (state.agents.isEmpty()) {
                    item {
                        EmptyState(
                            title = "No agents yet",
                            body = "Add one from a template below. Coding and research templates " +
                                "come with sensible tools and permissions.",
                        )
                    }
                }
                items(state.agents, key = { it.id }) { agent ->
                    AgentCard(
                        agent = agent,
                        onRun = { viewModel.openRun(agent) },
                        onDelete = { viewModel.delete(agent) },
                    )
                }

                item {
                    SectionHeader(
                        title = "Templates",
                        subtitle = "Start from a specialist",
                    )
                }
                items(state.templates, key = { it.name }) { template ->
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { viewModel.createFromTemplate(template) },
                    ) {
                        Text(
                            template.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = Palette.Ink,
                        )
                        Text(
                            template.description.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.Muted,
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Pill(text = template.permissionProfile.replace('_', ' '))
                            Pill(text = "${template.enabledTools.size} tools")
                        }
                    }
                }

                item {
                    SectionHeader(
                        title = "Runs",
                        subtitle = "Progress and approvals",
                    )
                }
                if (state.runs.isEmpty()) {
                    item {
                        Text(
                            "No runs yet. Start one to watch the plan execute.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.Muted,
                        )
                    }
                }
                items(state.runs, key = { it.id }) { run ->
                    GlassCard(modifier = Modifier.fillMaxWidth(), onClick = { onOpenRun(run.id) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(
                                active = run.status == "succeeded",
                                label = null,
                                activeColor = when (run.status) {
                                    "succeeded" -> Palette.Sage
                                    "failed" -> Palette.Rose
                                    "awaiting_approval" -> Palette.Amber
                                    else -> Palette.Faint
                                },
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = Formatting.truncate(run.objective, 90),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Palette.Ink,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${run.status.replace('_', ' ')} · ${run.runtime} · " +
                                        Formatting.relativeTime(run.createdAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Palette.Muted,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (state.showCreate) {
        CreateAgentDialog(
            state = state,
            onDismiss = viewModel::closeDialogs,
            onCreate = viewModel::createAgent,
        )
    }
    val runAgent = state.runAgent
    if (runAgent != null) {
        StartRunDialog(
            state = state,
            initialAgent = runAgent,
            onDismiss = viewModel::closeDialogs,
            onStart = { objective, agent, runtime ->
                viewModel.startRun(objective, agent, runtime, onOpenRun)
            },
        )
    }
}

@Composable
private fun AgentCard(agent: Agent, onRun: () -> Unit, onDelete: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(agent.name, style = MaterialTheme.typography.titleMedium, color = Palette.Ink)
                Text(
                    text = "${agent.kind.replace('_', ' ')} · ${agent.runtime} · " +
                        agent.permissionProfile.replace('_', ' '),
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.Muted,
                )
            }
            IconButton(onClick = onRun) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Run", tint = Palette.Graphite)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Palette.Faint)
            }
        }
        if (agent.instructions.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = Formatting.truncate(agent.instructions, 160),
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Muted,
            )
        }
        if (agent.enabledTools.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = agent.enabledTools.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = Palette.Faint,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CreateAgentDialog(
    state: AgentStudioUiState,
    onDismiss: () -> Unit,
    onCreate: (String, String, String, String?, String, String, String, List<String>, String?, () -> Unit) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("general") }
    var instructions by remember { mutableStateOf("") }
    var modelId by remember { mutableStateOf<String?>(null) }
    var runtime by remember { mutableStateOf("native") }
    var permission by remember { mutableStateOf("read_only") }
    var memory by remember { mutableStateOf("agent") }
    var tools by remember { mutableStateOf(setOf<String>()) }
    var projectId by remember { mutableStateOf<String?>(null) }
    var kindMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New agent") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth(),
            ) {
                PearlTextField("Name", name, { name = it })
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Kind", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                    Spacer(Modifier.width(10.dp))
                    androidx.compose.foundation.layout.Box {
                        Pill(text = kind.replace('_', ' '), onClick = { kindMenu = true })
                        DropdownMenu(expanded = kindMenu, onDismissRequest = { kindMenu = false }) {
                            listOf("general", "coding", "research", "planning", "document", "project_manager")
                                .forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.replace('_', ' ')) },
                                        onClick = { kind = option; kindMenu = false },
                                    )
                                }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                PearlTextField(
                    "Instructions",
                    instructions,
                    { instructions = it },
                    singleLine = false,
                    minLines = 3,
                )
                Spacer(Modifier.height(10.dp))
                Text("Runtime", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Native engine", runtime == "native", { runtime = "native" })
                    Pill("OpenClaw", runtime == "openclaw", { runtime = "openclaw" })
                }
                Spacer(Modifier.height(10.dp))
                Text("Permission profile", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("read_only", "standard", "elevated").forEach { profile ->
                        Pill(
                            text = profile.replace('_', ' '),
                            selected = permission == profile,
                            onClick = { permission = profile },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Memory scope", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("global", "project", "agent").forEach { scope ->
                        Pill(scope, memory == scope, { memory = scope })
                    }
                }
                if (state.models.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Pinned model (optional)", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill("Auto", modelId == null, { modelId = null })
                        state.models.take(2).forEach { model ->
                            Pill(model.displayName, modelId == model.id, { modelId = model.id })
                        }
                    }
                }
                if (state.projects.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Project", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill("None", projectId == null, { projectId = null })
                        state.projects.take(2).forEach { project ->
                            Pill(project.name, projectId == project.id, { projectId = project.id })
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Tools", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                Spacer(Modifier.height(6.dp))
                Column {
                    state.tools.chunked(2).forEach { rowTools ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            rowTools.forEach { tool ->
                                Pill(
                                    text = tool.name,
                                    selected = tool.name in tools,
                                    onClick = {
                                        tools = if (tool.name in tools) tools - tool.name else tools + tool.name
                                    },
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onCreate(
                        name.trim(), kind, instructions, modelId, runtime, permission, memory,
                        tools.toList(), projectId, onDismiss,
                    )
                },
                enabled = name.isNotBlank(),
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun StartRunDialog(
    state: AgentStudioUiState,
    initialAgent: Agent?,
    onDismiss: () -> Unit,
    onStart: (String, Agent?, String?) -> Unit,
) {
    var objective by remember { mutableStateOf("") }
    var agent by remember { mutableStateOf(initialAgent) }
    var runtime by remember { mutableStateOf(initialAgent?.runtime ?: "native") }
    var agentMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start a run") },
        text = {
            Column {
                Text(
                    text = "The agent plans, then works through the steps with its permitted tools. " +
                        "Sensitive steps wait for your approval.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Muted,
                )
                Spacer(Modifier.height(10.dp))
                PearlTextField(
                    "Objective",
                    objective,
                    { objective = it },
                    singleLine = false,
                    minLines = 3,
                    placeholder = "Describe the outcome you want",
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Agent", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                    Spacer(Modifier.width(10.dp))
                    androidx.compose.foundation.layout.Box {
                        Pill(text = agent?.name ?: "General (no agent)", onClick = { agentMenu = true })
                        DropdownMenu(expanded = agentMenu, onDismissRequest = { agentMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("General (no agent)") },
                                onClick = { agent = null; agentMenu = false },
                            )
                            state.agents.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.name) },
                                    onClick = {
                                        agent = option
                                        runtime = option.runtime
                                        agentMenu = false
                                    },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Native engine", runtime == "native", { runtime = "native" })
                    Pill("OpenClaw", runtime == "openclaw", { runtime = "openclaw" })
                }
                if (runtime == "openclaw") {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Requires a connected OpenClaw gateway in Settings → Runtime.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Palette.Amber,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onStart(objective.trim(), agent, runtime) },
                enabled = objective.isNotBlank() && !state.starting,
            ) { Text(if (state.starting) "Starting…" else "Start") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
