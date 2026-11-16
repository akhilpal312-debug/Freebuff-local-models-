package dev.universalai.agent.ui.screens.projects

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.universalai.agent.core.AppContainer
import dev.universalai.agent.core.AppResult
import dev.universalai.agent.core.Formatting
import dev.universalai.agent.data.model.Project
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
import dev.universalai.agent.ui.components.rememberViewModel
import dev.universalai.agent.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ProjectsUiState(
    val loading: Boolean = true,
    val projects: List<Project> = emptyList(),
    val error: String? = null,
    val notice: String? = null,
    val busy: Boolean = false,
    val showCreate: Boolean = false,
    val showGithub: Boolean = false,
)

class ProjectsViewModel(private val container: AppContainer) : ViewModel() {

    var state by mutableStateOf(ProjectsUiState())
        private set

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            state = state.copy(loading = true)
            val result = container.projects.list()
            state = state.copy(
                loading = false,
                projects = result.valueOrNull().orEmpty(),
                error = (result as? AppResult.Failure)?.message,
            )
        }
    }

    fun openCreate() { state = state.copy(showCreate = true) }
    fun openGithub() { state = state.copy(showGithub = true) }
    fun closeDialogs() { state = state.copy(showCreate = false, showGithub = false) }

    fun create(name: String) {
        state = state.copy(busy = true, showCreate = false)
        viewModelScope.launch {
            val result = container.projects.create(name.trim(), "generic")
            state = state.copy(
                busy = false,
                notice = result.valueOrNull()?.let { "${it.name} created." }
                    ?: (result as? AppResult.Failure)?.message,
                error = (result as? AppResult.Failure)?.message,
            )
            refresh()
        }
    }

    fun importGithub(url: String) {
        state = state.copy(busy = true, showGithub = false)
        viewModelScope.launch {
            val result = container.projects.importGithub(url.trim())
            state = state.copy(
                busy = false,
                notice = result.valueOrNull()?.let { "${it.name} imported." }
                    ?: (result as? AppResult.Failure)?.message,
                error = (result as? AppResult.Failure)?.message,
            )
            refresh()
        }
    }

    fun importZip(name: String, fileName: String, bytes: ByteArray) {
        state = state.copy(busy = true)
        viewModelScope.launch {
            val result = container.projects.importZip(name, fileName, bytes)
            state = state.copy(
                busy = false,
                notice = result.valueOrNull()?.let { "${it.name} uploaded." }
                    ?: (result as? AppResult.Failure)?.message,
                error = (result as? AppResult.Failure)?.message,
            )
            refresh()
        }
    }

    fun delete(project: Project) {
        viewModelScope.launch {
            container.projects.delete(project.id)
            refresh()
        }
    }
}

@Composable
fun ProjectsScreen(container: AppContainer, onOpenProject: (String) -> Unit) {
    val viewModel = rememberViewModel { ProjectsViewModel(container) }
    val state = viewModel.state
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val zipPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    var displayName = "project.zip"
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0 && cursor.moveToFirst()) {
                            displayName = cursor.getString(index) ?: displayName
                        }
                    }
                    val bytes = context.contentResolver.openInputStream(uri)
                        ?.use { it.readBytes() } ?: return@runCatching null
                    if (bytes.size > 20 * 1024 * 1024) return@runCatching null
                    displayName to bytes
                }.getOrNull()
            }
            if (loaded == null) {
                return@launch
            }
            viewModel.importZip(
                name = loaded.first.removeSuffix(".zip"),
                fileName = loaded.first,
                bytes = loaded.second,
            )
        }
    }

    PearlBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenTopBar(
                title = "Projects",
                subtitle = "Bounded workspaces for coding agents",
            ) {
                IconButton(onClick = viewModel::openCreate) {
                    Icon(Icons.Filled.Add, contentDescription = "New project", tint = Palette.Graphite)
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 18.dp, end = 18.dp, top = 4.dp, bottom = 30.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton(
                            label = "New project",
                            onClick = viewModel::openCreate,
                            modifier = Modifier.weight(1f),
                            loading = state.busy,
                        )
                        SecondaryButton(
                            label = "GitHub",
                            onClick = viewModel::openGithub,
                            enabled = !state.busy,
                        )
                        SecondaryButton(
                            label = "Upload zip",
                            onClick = { zipPicker.launch(arrayOf("application/zip", "application/octet-stream")) },
                            enabled = !state.busy,
                        )
                    }
                }
                item {
                    Text(
                        text = "New projects are read-only by default. Agents can read files; " +
                            "writes and commands need your approval.",
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
                            state.notice.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.Sage,
                        )
                    }
                }
                if (state.loading) {
                    item { LoadingState("Loading projects") }
                } else if (state.projects.isEmpty()) {
                    item {
                        EmptyState(
                            title = "No projects",
                            body = "Import a GitHub repository or upload a zip to give coding agents " +
                                "a sandboxed workspace.",
                        )
                    }
                }
                items(state.projects, key = { it.id }) { project ->
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onOpenProject(project.id) },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    project.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Palette.Ink,
                                )
                                Text(
                                    text = buildString {
                                        append(project.kind)
                                        append(" · ")
                                        append(project.source)
                                        project.updatedAt?.let { append(" · " + Formatting.relativeTime(it)) }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Palette.Muted,
                                )
                            }
                            Pill(
                                text = if (project.readOnly) "read-only" else "editable",
                                selected = project.readOnly,
                            )
                            IconButton(onClick = { viewModel.delete(project) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Delete project",
                                    tint = Palette.Faint,
                                )
                            }
                        }
                        project.description?.takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                        }
                        project.repoUrl?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                color = Palette.Clay,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }

    if (state.showCreate) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = viewModel::closeDialogs,
            title = { Text("New project") },
            text = {
                Column {
                    Text(
                        "Creates an empty workspace inside the server's sandbox root.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Muted,
                    )
                    Spacer(Modifier.height(10.dp))
                    PearlTextField("Name", name, { name = it }, placeholder = "my-project")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.create(name) },
                    enabled = name.isNotBlank(),
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = viewModel::closeDialogs) { Text("Cancel") } },
        )
    }

    if (state.showGithub) {
        var url by remember { mutableStateOf("https://github.com/") }
        AlertDialog(
            onDismissRequest = viewModel::closeDialogs,
            title = { Text("Import from GitHub") },
            text = {
                Column {
                    Text(
                        "The server clones the public repository into a sandboxed workspace. " +
                            "Private repositories need a server-side token.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Muted,
                    )
                    Spacer(Modifier.height(10.dp))
                    PearlTextField("Repository URL", url, { url = it })
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.importGithub(url) },
                    enabled = url.startsWith("https://"),
                ) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = viewModel::closeDialogs) { Text("Cancel") } },
        )
    }
}
