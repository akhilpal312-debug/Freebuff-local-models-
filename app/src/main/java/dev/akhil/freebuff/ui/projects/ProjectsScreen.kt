package dev.akhil.freebuff.ui.projects

import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.akhil.freebuff.core.Format
import dev.akhil.freebuff.data.db.ProjectEntity
import dev.akhil.freebuff.design.AppIcons
import dev.akhil.freebuff.design.EmptyState
import dev.akhil.freebuff.design.IconTile
import dev.akhil.freebuff.design.KeyValueRow
import dev.akhil.freebuff.design.SectionHeader
import dev.akhil.freebuff.design.StatusChip
import dev.akhil.freebuff.design.SurfaceCard
import dev.akhil.freebuff.projects.ProjectTemplates
import dev.akhil.freebuff.projects.WorkspaceEntry
import dev.akhil.freebuff.ui.AppViewModel
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(viewModel: AppViewModel) {
    val projects by viewModel.projects.collectAsState()
    var selectedId by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }

    if (selectedId != null) {
        ProjectDetail(viewModel, selectedId!!) { selectedId = null }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("header") {
            SectionHeader(
                title = "Projects",
                subtitle = "Local workspaces for code, sites and Android apps",
                trailing = { TextButton(onClick = { showCreate = true }) { Text("New") } },
            )
        }
        if (projects.isEmpty()) {
            item("empty") {
                EmptyState(
                    icon = AppIcons.Folder,
                    title = "No projects yet",
                    message = "Create a website, a Python folder or an Android app skeleton. " +
                        "Everything stays in app-private storage.",
                    action = { Button(onClick = { showCreate = true }) { Text("Create a project") } },
                )
            }
        }
        items(projects, key = { it.id }) { project ->
            SurfaceCard(onClick = { selectedId = project.id }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(AppIcons.Folder)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(project.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${project.type} · updated ${Format.relative(project.updatedAt)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(AppIcons.Code, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (showCreate) {
        CreateProjectDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, template ->
                viewModel.createProject(name, template) { id -> selectedId = id }
                showCreate = false
            },
        )
    }
}

@Composable
private fun CreateProjectDialog(
    onDismiss: () -> Unit,
    onCreate: (String, dev.akhil.freebuff.projects.ProjectTemplate) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var template by remember { mutableStateOf(ProjectTemplates.webBasic) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New project") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Project name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                ProjectTemplates.all.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { template = option }
                            .background(
                                if (option == template) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                RoundedCornerShape(12.dp),
                            )
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconTile(AppIcons.Layers, size = 30)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(option.label, style = MaterialTheme.typography.titleMedium)
                            Text(
                                option.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name, template) },
                enabled = name.isNotBlank(),
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectDetail(viewModel: AppViewModel, projectId: String, onBack: () -> Unit) {
    val projects by viewModel.projects.collectAsState()
    val project = projects.firstOrNull { it.id == projectId }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
    var entries by remember { mutableStateOf<List<WorkspaceEntry>>(emptyList()) }
    var openFile by remember { mutableStateOf<String?>(null) }
    var fileContent by remember { mutableStateOf("") }
    var buildLog by remember { mutableStateOf<String?>(null) }
    val builds by viewModel.container.builds.observeProjectBuilds(projectId)
        .collectAsState(initial = emptyList<dev.akhil.freebuff.data.db.BuildRecordEntity>())

    if (project == null) {
        EmptyState(AppIcons.Folder, "Project not found", "It may have been deleted.")
        return
    }

    androidx.compose.runtime.LaunchedEffect(projectId, tab) {
        entries = viewModel.container.workspace.listTree(projectId)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(project.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    project.type,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = {
                scope.launch {
                    viewModel.container.workspace.exportProject(projectId).fold(
                        onSuccess = { viewModel.showToast("Exported to ${it.absolutePath}") },
                        onFailure = { viewModel.showToast(it.message ?: "Something went wrong") },
                    )
                }
            }) { Text("Export") }
        }

        TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
            listOf("Files", "Editor", "Preview", "Build").forEachIndexed { index, label ->
                Tab(selected = tab == index, onClick = { tab = index }) {
                    Text(label, modifier = Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        when (tab) {
            0 -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                items(entries, key = { it.path }) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !entry.isDirectory) {
                                scope.launch {
                                    viewModel.container.workspace.readFile(projectId, entry.path).fold(
                                        onSuccess = { content ->
                                            openFile = entry.path
                                            fileContent = content
                                            tab = 1
                                        },
                                        onFailure = { viewModel.showToast(it.message ?: "Something went wrong") },
                                    )
                                }
                            }
                            .padding(vertical = 8.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (entry.isDirectory) AppIcons.Folder else AppIcons.Code,
                            null,
                            Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            entry.path,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        if (!entry.isDirectory) {
                            Text(
                                Format.bytes(entry.sizeBytes),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (entries.isEmpty()) {
                    item { EmptyState(AppIcons.Folder, "No files", "The project folder is empty.") }
                }
            }

            1 -> Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        openFile ?: "Pick a file in the Files tab",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            val path = openFile ?: return@TextButton
                            scope.launch {
                                viewModel.container.workspace.writeFile(projectId, path, fileContent).fold(
                                    onSuccess = { viewModel.showToast("Saved $path") },
                                    onFailure = { viewModel.showToast(it.message ?: "Something went wrong") },
                                )
                            }
                        },
                        enabled = openFile != null,
                    ) { Text("Save") }
                    TextButton(
                        onClick = {
                            val path = openFile ?: return@TextButton
                            scope.launch {
                                viewModel.container.workspace.undoLastWrite(projectId, path).fold(
                                    onSuccess = {
                                        viewModel.container.workspace.readFile(projectId, path)
                                            .onSuccess { fileContent = it }
                                        viewModel.showToast("Restored previous version.")
                                    },
                                    onFailure = { viewModel.showToast(it.message ?: "Something went wrong") },
                                )
                            }
                        },
                        enabled = openFile != null,
                    ) { Text("Undo") }
                }
                OutlinedTextField(
                    value = fileContent,
                    onValueChange = { fileContent = it },
                    modifier = Modifier.fillMaxSize(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    ),
                    keyboardOptions = KeyboardOptions.Default,
                )
            }

            2 -> Column(Modifier.fillMaxSize()) {
                val indexFile = remember(projectId, tab) {
                    viewModel.container.workspace.projectDir(project).resolve("index.html")
                }
                if (indexFile.exists()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            WebView(context).apply {
                                settings.javaScriptEnabled = true
                                settings.allowFileAccess = true
                                settings.domStorageEnabled = true
                                settings.blockNetworkLoads = false
                                webViewClient = WebViewClient()
                                loadUrl("file://" + indexFile.absolutePath)
                            }
                        },
                        update = { webView ->
                            val url = "file://" + indexFile.absolutePath
                            if (webView.url != url) webView.loadUrl(url)
                        },
                    )
                } else {
                    EmptyState(
                        icon = AppIcons.Globe,
                        title = "Nothing to preview",
                        message = "This project has no index.html. The preview renders local HTML only; " +
                            "it does not execute Python or Node.",
                    )
                }
            }

            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                item("caps") {
                    val caps = viewModel.container.builds.capabilities()
                    SurfaceCard {
                        Text("Build capability", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        KeyValueRow("JDK", if (caps.jdk.found) caps.jdk.detail else "Missing")
                        KeyValueRow("Android SDK", if (caps.androidSdk.found) caps.androidSdk.detail else "Missing")
                        KeyValueRow("Gradle", if (caps.gradle.found) caps.gradle.detail else "Missing")
                        KeyValueRow("Free storage", Format.bytes(caps.freeStorage))
                        Spacer(Modifier.height(8.dp))
                        if (caps.blockers.isNotEmpty()) {
                            caps.blockers.forEach { blocker ->
                                Text(
                                    "• $blocker",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                scope.launch {
                                    val outcome = viewModel.container.builds.build(project)
                                    buildLog = outcome.message + "\n\n" + outcome.log
                                    viewModel.showToast(outcome.message)
                                }
                            }) { Text("Build debug APK") }
                            TextButton(onClick = {
                                scope.launch {
                                    viewModel.container.builds.prepareGradleMemory(
                                        project,
                                        viewModel.settings.value.gradleHeapMb,
                                    )
                                    viewModel.showToast("Gradle memory set to ${viewModel.settings.value.gradleHeapMb} MB.")
                                }
                            }) { Text("Limit Gradle memory") }
                        }
                        Text(
                            "This device cannot compile Android apps: no JDK, SDK or Gradle exists in an " +
                                "Android app sandbox. Use Export and build on a computer.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                item("history") {
                    Spacer(Modifier.height(12.dp))
                    SectionHeader("Build history")
                }
                items(builds, key = { it.id }) { record ->
                    SurfaceCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(record.variant, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    Format.date(record.startedAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            StatusChip(
                                record.status,
                                if (record.status == "SUCCEEDED") MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                if (buildLog != null) {
                    item("log") {
                        Spacer(Modifier.height(12.dp))
                        SurfaceCard {
                            Text("Last build output", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                buildLog!!,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                            )
                        }
                    }
                }
            }
        }
    }
}
