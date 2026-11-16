package dev.universalai.agent.ui.screens.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.universalai.agent.core.AppContainer
import dev.universalai.agent.core.AppResult
import dev.universalai.agent.core.Formatting
import dev.universalai.agent.data.model.DiffResult
import dev.universalai.agent.data.model.FileEntry
import dev.universalai.agent.data.model.Project
import dev.universalai.agent.data.model.SearchHit
import dev.universalai.agent.ui.components.ErrorBanner
import dev.universalai.agent.ui.components.GlassCard
import dev.universalai.agent.ui.components.LoadingState
import dev.universalai.agent.ui.components.PearlBackground
import dev.universalai.agent.ui.components.PearlTextField
import dev.universalai.agent.ui.components.Pill
import dev.universalai.agent.ui.components.PrimaryButton
import dev.universalai.agent.ui.components.ScreenTopBar
import dev.universalai.agent.ui.components.SecondaryButton
import dev.universalai.agent.ui.components.rememberViewModel
import dev.universalai.agent.ui.theme.Palette
import kotlinx.coroutines.launch

data class WorkspaceUiState(
    val loading: Boolean = true,
    val project: Project? = null,
    val readOnly: Boolean = true,
    val tab: String = "files",
    val path: String = ".",
    val entries: List<FileEntry> = emptyList(),
    val openPath: String? = null,
    val editorText: String = "",
    val originalText: String = "",
    val fileLoading: Boolean = false,
    val saving: Boolean = false,
    val command: String = "",
    val log: List<String> = emptyList(),
    val running: Boolean = false,
    val isRepo: Boolean = false,
    val gitStatus: String = "",
    val diff: DiffResult? = null,
    val searchQuery: String = "",
    val hits: List<SearchHit> = emptyList(),
    val objective: String = "",
    val startingRun: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
) {
    val dirty: Boolean get() = openPath != null && editorText != originalText
}

class ProjectWorkspaceViewModel(
    private val container: AppContainer,
    private val projectId: String,
) : ViewModel() {

    var state by mutableStateOf(WorkspaceUiState())
        private set

    init { load() }

    fun load() {
        viewModelScope.launch {
            val projects = container.projects.list()
            val project = projects.valueOrNull()?.firstOrNull { it.id == projectId }
            state = state.copy(
                loading = false,
                project = project,
                readOnly = project?.readOnly ?: true,
                error = if (project == null) {
                    (projects as? AppResult.Failure)?.message ?: "Project not found."
                } else {
                    null
                },
            )
            loadFiles(state.path)
            loadGit()
        }
    }

    fun selectTab(tab: String) { state = state.copy(tab = tab) }

    fun loadFiles(path: String) {
        viewModelScope.launch {
            val result = container.projects.files(projectId, path)
            state = state.copy(
                path = path,
                entries = result.valueOrNull().orEmpty(),
                error = (result as? AppResult.Failure)?.message,
            )
        }
    }

    fun openEntry(entry: FileEntry) {
        if (entry.isDirectory) {
            loadFiles(entry.path)
            state = state.copy(openPath = null)
        } else {
            openFile(entry.path)
        }
    }

    fun up() {
        val parent = state.path.trimEnd('/').substringBeforeLast('/', missingDelimiterValue = ".")
        loadFiles(parent.ifBlank { "." })
    }

    fun openFile(path: String) {
        state = state.copy(fileLoading = true, openPath = path)
        viewModelScope.launch {
            val result = container.projects.readFile(projectId, path)
            state = state.copy(
                fileLoading = false,
                openPath = path,
                editorText = result.valueOrNull()?.content.orEmpty(),
                originalText = result.valueOrNull()?.content.orEmpty(),
                error = (result as? AppResult.Failure)?.message,
            )
        }
    }

    fun closeFile() { state = state.copy(openPath = null, editorText = "", originalText = "") }

    fun onEditorChange(value: String) { state = state.copy(editorText = value) }

    fun save() {
        val path = state.openPath ?: return
        if (state.readOnly) {
            state = state.copy(error = "This project is read-only. Enable editing first.")
            return
        }
        state = state.copy(saving = true)
        viewModelScope.launch {
            val result = container.projects.writeFile(projectId, path, state.editorText)
            state = state.copy(
                saving = false,
                originalText = if (result.isSuccess) state.editorText else state.originalText,
                notice = if (result.isSuccess) "Saved $path." else null,
                error = (result as? AppResult.Failure)?.message,
            )
        }
    }

    fun toggleReadOnly() {
        val next = !state.readOnly
        viewModelScope.launch {
            val result = container.projects.setReadOnly(projectId, !next)
            state = state.copy(
                readOnly = result.valueOrNull()?.readOnly ?: state.readOnly,
                error = (result as? AppResult.Failure)?.message,
                notice = (result.valueOrNull())?.let {
                    if (it.readOnly) "Project is read-only." else "Editing enabled for this project."
                },
            )
        }
    }

    fun onCommandChange(value: String) { state = state.copy(command = value) }

    fun runCommand() {
        val command = state.command.trim()
        if (command.isEmpty() || state.running) return
        state = state.copy(running = true, command = "", log = state.log + "› $command")
        viewModelScope.launch {
            val result = container.projects.runCommand(projectId, command)
            val output = result.valueOrNull()
            val lines = when {
                output == null -> listOf((result as AppResult.Failure).message)
                else -> buildList {
                    if (output.stdout.isNotBlank()) addAll(output.stdout.trimEnd().lines())
                    if (output.stderr.isNotBlank()) addAll(output.stderr.trimEnd().lines())
                    add(
                        "[exit ${output.exitCode} · ${output.durationMs} ms" +
                            (if (output.truncated) " · truncated" else "") + "]",
                    )
                }
            }
            state = state.copy(
                running = false,
                log = (state.log + lines).takeLast(300),
                error = (result as? AppResult.Failure)?.message,
            )
            loadGit()
        }
    }

    fun loadGit() {
        viewModelScope.launch {
            val status = container.projects.gitStatus(projectId)
            val pair = status.valueOrNull()
            state = state.copy(
                isRepo = pair?.first ?: false,
                gitStatus = pair?.second.orEmpty(),
            )
            if (pair?.first == true) {
                val diff = container.projects.gitDiff(projectId)
                state = state.copy(diff = diff.valueOrNull())
            }
        }
    }

    fun onSearchChange(value: String) { state = state.copy(searchQuery = value) }

    fun search() {
        val query = state.searchQuery.trim()
        if (query.isEmpty()) return
        viewModelScope.launch {
            val result = container.projects.search(projectId, query)
            state = state.copy(
                hits = result.valueOrNull().orEmpty(),
                error = (result as? AppResult.Failure)?.message,
            )
        }
    }

    fun onObjectiveChange(value: String) { state = state.copy(objective = value) }

    fun startAgentRun(onStarted: (String) -> Unit) {
        val objective = state.objective.trim()
        if (objective.isEmpty() || state.startingRun) return
        state = state.copy(startingRun = true, error = null)
        viewModelScope.launch {
            val result = container.agents.start(
                objective = objective,
                agentId = null,
                projectId = projectId,
                runtime = "native",
                modelId = null,
                permissionProfile = if (state.readOnly) "read_only" else "standard",
                tools = null,
            )
            state = state.copy(
                startingRun = false,
                objective = "",
                notice = result.valueOrNull()?.let { "Run started." }
                    ?: (result as? AppResult.Failure)?.message,
                error = (result as? AppResult.Failure)?.message,
            )
            result.valueOrNull()?.let { onStarted(it.id) }
        }
    }
}

@Composable
fun ProjectWorkspaceScreen(
    container: AppContainer,
    projectId: String,
    onBack: () -> Unit,
    onOpenRun: (String) -> Unit,
) {
    val viewModel = rememberViewModel(key = projectId) {
        ProjectWorkspaceViewModel(container, projectId)
    }
    val state = viewModel.state

    PearlBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenTopBar(
                title = state.project?.name ?: "Project",
                subtitle = "/${state.path.trimStart('.').trimStart('/')}",
                onBack = onBack,
            ) {
                Pill(
                    text = if (state.readOnly) "read-only" else "editable",
                    selected = !state.readOnly,
                    onClick = viewModel::toggleReadOnly,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("files" to "Files", "changes" to "Changes", "terminal" to "Terminal", "agent" to "Agent")
                    .forEach { (key, label) ->
                        Pill(label, state.tab == key, { viewModel.selectTab(key) })
                    }
            }
            if (state.error != null) {
                Box(modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
                    ErrorBanner(state.error, onRetry = viewModel::load)
                }
            }

            when {
                state.loading -> LoadingState("Loading workspace")
                state.tab == "files" -> FilesTab(viewModel, state)
                state.tab == "changes" -> ChangesTab(state, viewModel::loadGit)
                state.tab == "terminal" -> TerminalTab(state, viewModel)
                else -> AgentTab(state, viewModel, onOpenRun)
            }
        }
    }
}

@Composable
private fun FilesTab(viewModel: ProjectWorkspaceViewModel, state: WorkspaceUiState) {
    val openPath = state.openPath
    if (openPath != null) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = openPath,
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = viewModel::closeFile) {
                    Icon(Icons.Filled.Close, contentDescription = "Close file", tint = Palette.Muted)
                }
            }
            if (state.fileLoading) {
                LoadingState("Reading file")
            } else {
                androidx.compose.foundation.text.BasicTextField(
                    value = state.editorText,
                    onValueChange = viewModel::onEditorChange,
                    readOnly = state.readOnly,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = Palette.Ink,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Palette.CodeBackground)
                        .padding(12.dp),
                )
            }
            Row(
                modifier = Modifier.padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.readOnly) {
                    Text(
                        "Read-only. Editing saves files on the server sandbox.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Palette.Muted,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Text(
                        if (state.dirty) "Unsaved changes" else "Saved",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.dirty) Palette.Amber else Palette.Sage,
                        modifier = Modifier.weight(1f),
                    )
                }
                PrimaryButton(
                    label = "Save",
                    onClick = viewModel::save,
                    enabled = !state.readOnly && state.dirty,
                    loading = state.saving,
                )
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.path != ".") {
                IconButton(onClick = viewModel::up) {
                    Icon(Icons.Filled.ArrowUpward, contentDescription = "Up", tint = Palette.Graphite)
                }
            }
            PearlTextField(
                label = "Search in project",
                value = state.searchQuery,
                onValueChange = viewModel::onSearchChange,
                modifier = Modifier.weight(1f),
                trailing = {
                    IconButton(onClick = viewModel::search) {
                        Icon(Icons.Filled.Search, contentDescription = "Search", tint = Palette.Muted)
                    }
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            if (state.hits.isNotEmpty()) {
                state.hits.take(20).forEach { hit ->
                    SearchHitRow(hit) { viewModel.openFile(hit.path) }
                }
                Spacer(Modifier.height(10.dp))
            }
            state.entries.forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { viewModel.openEntry(entry) }
                        .padding(vertical = 9.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.Description,
                        contentDescription = null,
                        tint = Palette.Muted,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = entry.path.substringAfterLast('/'),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.Ink,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!entry.isDirectory) {
                        Text(
                            text = Formatting.fileSize(entry.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = Palette.Faint,
                        )
                    }
                }
            }
            if (state.entries.isEmpty()) {
                Text(
                    "This directory is empty.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Muted,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun SearchHitRow(hit: SearchHit, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.Pearl.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${hit.path}:${hit.line}",
            style = MaterialTheme.typography.labelSmall,
            color = Palette.Clay,
            modifier = Modifier.width(110.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = hit.text,
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Graphite,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ChangesTab(state: WorkspaceUiState, onRefresh: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (state.isRepo) "Git repository" else "Not a git repository",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Ink,
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(label = "Refresh", onClick = onRefresh)
        }
        Spacer(Modifier.height(10.dp))
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = state.gitStatus.ifBlank { "Working tree is clean." },
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                ),
                color = Palette.Graphite,
            )
        }
        val diff = state.diff
        if (diff != null && diff.filesChanged.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Changed files",
                style = MaterialTheme.typography.titleMedium,
                color = Palette.Ink,
            )
            diff.filesChanged.forEach { file ->
                Text(
                    text = "· $file",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Muted,
                )
            }
        }
        if (diff != null && diff.diff.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .clip(RoundedCornerShape(14.dp))
                    .background(Palette.CodeBackground)
                    .padding(12.dp),
            ) {
                Text(
                    text = diff.diff.take(20000),
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                    ),
                    color = Palette.Graphite,
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun TerminalTab(state: WorkspaceUiState, viewModel: ProjectWorkspaceViewModel) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.CodeBackground)
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (state.log.isEmpty()) {
                Text(
                    text = "Commands run in the project sandbox. Only allowlisted binaries " +
                        "are accepted and every command is logged.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Muted,
                )
            }
            state.log.forEach { line ->
                Text(
                    text = line,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    ),
                    color = Palette.Graphite,
                )
            }
        }
        Row(
            modifier = Modifier.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PearlTextField(
                label = "Command",
                value = state.command,
                onValueChange = viewModel::onCommandChange,
                modifier = Modifier.weight(1f),
                placeholder = "git status",
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = viewModel::runCommand, enabled = !state.running) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Run command",
                    tint = if (state.running) Palette.Faint else Palette.Ink,
                )
            }
        }
    }
}

@Composable
private fun AgentTab(
    state: WorkspaceUiState,
    viewModel: ProjectWorkspaceViewModel,
    onOpenRun: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
    ) {
        Text(
            text = "Give a coding agent an objective for this project. The agent sees project " +
                "files through tools and asks before writes or commands when the project is read-only.",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Muted,
        )
        Spacer(Modifier.height(12.dp))
        PearlTextField(
            label = "Objective",
            value = state.objective,
            onValueChange = viewModel::onObjectiveChange,
            singleLine = false,
            minLines = 4,
            placeholder = "e.g. add a health endpoint and a test for it",
        )
        Spacer(Modifier.height(12.dp))
        PrimaryButton(
            label = "Start run",
            onClick = { viewModel.startAgentRun(onOpenRun) },
            enabled = state.objective.isNotBlank(),
            loading = state.startingRun,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.notice != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                state.notice,
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Sage,
            )
        }
        Spacer(Modifier.height(14.dp))
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Project policy",
                style = MaterialTheme.typography.titleMedium,
                color = Palette.Ink,
            )
            Text(
                text = if (state.readOnly) {
                    "Read-only: writes, shell commands and git operations require approval."
                } else {
                    "Editable: file writes are allowed inside the sandbox; shell commands still require approval."
                },
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Muted,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(text = if (state.isRepo) "git" else "plain files")
                Pill(text = "sandboxed")
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}
