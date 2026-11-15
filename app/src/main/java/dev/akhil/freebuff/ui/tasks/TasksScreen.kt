package dev.akhil.freebuff.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.akhil.freebuff.ai.AiMode
import dev.akhil.freebuff.core.Format
import dev.akhil.freebuff.data.db.TaskEntity
import dev.akhil.freebuff.design.AppIcons
import dev.akhil.freebuff.design.EmptyState
import dev.akhil.freebuff.design.IconTile
import dev.akhil.freebuff.design.KeyValueRow
import dev.akhil.freebuff.design.Pill
import dev.akhil.freebuff.design.SectionHeader
import dev.akhil.freebuff.design.StatusChip
import dev.akhil.freebuff.design.SurfaceCard
import dev.akhil.freebuff.ui.AppViewModel

private val statusFilters = listOf(
    "RUNNING" to "Running",
    "QUEUED" to "Queued",
    "AWAITING_APPROVAL" to "Approval",
    "COMPLETED" to "Completed",
    "FAILED" to "Failed",
    "CANCELLED" to "Cancelled",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(viewModel: AppViewModel) {
    val tasks by viewModel.tasks.collectAsState()
    val projects by viewModel.projects.collectAsState()
    var filter by remember { mutableStateOf("COMPLETED") }
    var detail by remember { mutableStateOf<TaskEntity?>(null) }
    var showNew by remember { mutableStateOf(false) }

    val visible = tasks.filter { it.status == filter }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeader(
                title = "Tasks",
                subtitle = "${tasks.count { it.status == "COMPLETED" }} completed · ${tasks.size} total",
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { showNew = true }) { Text("New task") }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            statusFilters.forEach { (status, label) ->
                Pill(text = label, selected = filter == status, onClick = { filter = status })
            }
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (visible.isEmpty()) {
                item("empty") {
                    EmptyState(
                        icon = AppIcons.Layers,
                        title = "Nothing here",
                        message = "Tasks are multi-step jobs the agent ran for you. Every completed task keeps " +
                            "its files, checks and report.",
                    )
                }
            }
            items(visible, key = { it.id }) { task ->
                SurfaceCard(onClick = { detail = task }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconTile(
                            icon = when (task.status) {
                                "COMPLETED" -> AppIcons.Sparkle
                                "FAILED" -> AppIcons.Stop
                                else -> AppIcons.Layers
                            },
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(task.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${Format.relative(task.createdAt)} · ${task.mode}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        StatusChip(
                            task.status.lowercase().replaceFirstChar { it.uppercase() },
                            when (task.status) {
                                "COMPLETED" -> MaterialTheme.colorScheme.primary
                                "FAILED" -> MaterialTheme.colorScheme.error
                                "RUNNING" -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    if (task.status == "COMPLETED") {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            task.verification,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    detail?.let { task ->
        TaskDetailSheet(
            viewModel = viewModel,
            task = task,
            onDismiss = { detail = null },
        )
    }

    if (showNew) {
        var request by remember { mutableStateOf("") }
        var mode by remember { mutableStateOf(AiMode.CODING_AGENT) }
        var projectId by remember { mutableStateOf(projects.firstOrNull()?.id) }
        ModalBottomSheet(onDismissRequest = { showNew = false }, sheetState = rememberModalBottomSheetState()) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Text("New task", style = MaterialTheme.typography.titleLarge)
                Text(
                    "The agent plans, writes files and then checks them on disk. It cannot compile " +
                        "Android code on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = request,
                    onValueChange = { request = it },
                    label = { Text("What should it do?") },
                    placeholder = { Text("Create a website for a bakery with opening hours") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Coding agent", mode == AiMode.CODING_AGENT, { mode = AiMode.CODING_AGENT })
                    Pill("Build mode", mode == AiMode.BUILD_MODE, { mode = AiMode.BUILD_MODE })
                }
                Spacer(Modifier.height(10.dp))
                Text("Project", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    projects.forEach { project ->
                        Pill(project.name, projectId == project.id, { projectId = project.id })
                    }
                }
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = {
                        viewModel.submitTask(request, mode, projectId)
                        showNew = false
                    },
                    enabled = request.isNotBlank(),
                ) { Text("Queue task") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskDetailSheet(viewModel: AppViewModel, task: TaskEntity, onDismiss: () -> Unit) {
    val steps by remember(task.id) {
        viewModel.container.tasks.observeSteps(task.id)
    }.collectAsState(initial = emptyList())
    val current by remember(task.id) {
        viewModel.container.tasks.observeTask(task.id)
    }.collectAsState(initial = task)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(current?.title ?: task.title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(10.dp))
            KeyValueRow("Status", current?.status ?: task.status)
            KeyValueRow("Request", current?.request ?: task.request)
            KeyValueRow("Model", current?.modelId ?: task.modelId)
            KeyValueRow("Created", Format.date(task.createdAt))
            if ((current?.finishedAt ?: 0) > 0) {
                KeyValueRow("Duration", Format.duration((current!!.finishedAt - current!!.startedAt).coerceAtLeast(0)))
            }
            KeyValueRow("Verification", current?.verification ?: task.verification)

            Spacer(Modifier.height(12.dp))
            Text("Steps", style = MaterialTheme.typography.titleMedium)
            steps.forEach { step ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                    Text(
                        when (step.status) {
                            "DONE" -> "✓"
                            "FAILED" -> "✗"
                            "RUNNING" -> "…"
                            else -> "•"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = when (step.status) {
                            "DONE" -> MaterialTheme.colorScheme.primary
                            "FAILED" -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.width(20.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(step.title, style = MaterialTheme.typography.bodyMedium)
                        if (step.detail.isNotBlank()) {
                            Text(
                                step.detail,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            val created = (current?.filesCreated ?: "").lines().filter { it.isNotBlank() }
            if (created.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Files created", style = MaterialTheme.typography.titleMedium)
                created.take(20).forEach { file ->
                    Text("• $file", style = MaterialTheme.typography.bodySmall)
                }
            }

            val report = current?.report.orEmpty()
            if (report.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text("Report", style = MaterialTheme.typography.titleMedium)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(12.dp),
                ) {
                    dev.akhil.freebuff.ui.chat.MarkdownText(report)
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (current?.status) {
                    "RUNNING", "QUEUED" -> TextButton(onClick = { viewModel.cancelTask(task.id) }) { Text("Cancel task") }
                    "COMPLETED", "FAILED", "CANCELLED" -> Button(onClick = {
                        viewModel.retryTask(current ?: task)
                        onDismiss()
                    }) { Text("Run again") }
                }
                TextButton(onClick = {
                    viewModel.deleteTask(task.id)
                    onDismiss()
                }) { Text("Delete") }
                TextButton(onClick = {
                    val clipboard = viewModel.container.appContext
                        .getSystemService(android.content.ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(
                        android.content.ClipData.newPlainText("Task report", report),
                    )
                    viewModel.showToast("Report copied.")
                }) { Text("Copy report") }
            }
        }
    }
}
