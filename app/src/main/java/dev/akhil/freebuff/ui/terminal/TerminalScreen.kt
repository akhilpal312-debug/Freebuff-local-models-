package dev.akhil.freebuff.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.akhil.freebuff.design.AppIcons
import dev.akhil.freebuff.design.EmptyState
import dev.akhil.freebuff.design.LocalExtendedColors
import dev.akhil.freebuff.design.Pill
import dev.akhil.freebuff.ui.AppViewModel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(viewModel: AppViewModel) {
    val projects by viewModel.projects.collectAsState()
    val selected by viewModel.settings.collectAsState()
    var projectId by remember { mutableStateOf(projects.firstOrNull()?.id) }
    val sessionId = remember { dev.akhil.freebuff.core.Ids.new() }
    var command by remember { mutableStateOf("") }
    var pendingConfirm by remember { mutableStateOf<String?>(null) }
    val extended = LocalExtendedColors.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(projects) {
        if (projectId == null) projectId = projects.firstOrNull()?.id
    }

    val entries by remember(projectId, sessionId) {
        projectId?.let { viewModel.container.terminal.sessionEntries(sessionId) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())

    if (projects.isEmpty()) {
        EmptyState(
            icon = AppIcons.Terminal,
            title = "No project selected",
            message = "The terminal runs allowlisted commands inside a project workspace. " +
                "Create a project first — it never touches the rest of your phone.",
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            projects.forEach { project ->
                Pill(
                    text = project.name,
                    selected = project.id == projectId,
                    onClick = { projectId = project.id },
                )
            }
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
            Text(
                "Allowlisted commands only: ls, cat, head, tail, wc, grep, find, du, mkdir, touch, cp, mv, rm, echo, pwd, help, clear.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "No shell, no absolute paths, no access outside the project. Destructive commands ask first.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (entries.isEmpty()) {
                item("hint") {
                    Text(
                        "Type 'help' to see the command list.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            items(entries.asReversed(), key = { it.id }) { entry ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(extended.codeBackground)
                        .padding(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "$ ",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            entry.command,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "exit ${entry.exitCode}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (entry.exitCode == 0) extended.success else MaterialTheme.colorScheme.error,
                        )
                    }
                    if (entry.output.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            entry.output,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(8.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = command,
                onValueChange = { command = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("ls", style = MaterialTheme.typography.bodySmall) },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    val id = projectId ?: return@KeyboardActions
                    if (command.isNotBlank()) {
                        val text = command
                        command = ""
                        scope.launch {
                            val result = viewModel.container.terminal.run(
                                viewModel.container.workspace.getProject(id)!!,
                                sessionId,
                                text,
                                confirmed = false,
                            )
                            when {
                                result.needsConfirmation -> pendingConfirm = text
                                result.deniedReason != null -> viewModel.showToast(result.deniedReason)
                            }
                            listState.animateScrollToItem(0)
                        }
                    }
                }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            IconButton(onClick = { viewModel.container.terminal.cancelActive() }) {
                Icon(AppIcons.Stop, "Stop command", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
            }
            IconButton(onClick = {
                val id = projectId ?: return@IconButton
                if (command.isBlank()) return@IconButton
                val text = command
                command = ""
                scope.launch {
                    val result = viewModel.container.terminal.run(
                        viewModel.container.workspace.getProject(id)!!,
                        sessionId,
                        text,
                        confirmed = false,
                    )
                    when {
                        result.needsConfirmation -> pendingConfirm = text
                        result.deniedReason != null -> viewModel.showToast(result.deniedReason)
                    }
                    listState.animateScrollToItem(0)
                }
            }) {
                Icon(androidx.compose.material.icons.Icons.Filled.Send, "Run command", Modifier.size(18.dp))
            }
        }
    }

    pendingConfirm?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingConfirm = null },
            title = { Text("Confirm command") },
            text = { Text("\"$pending\" can delete or move files inside this project. Run it?") },
            confirmButton = {
                TextButton(onClick = {
                    val id = projectId
                    pendingConfirm = null
                    if (id != null) {
                        scope.launch {
                            viewModel.container.terminal.run(
                                viewModel.container.workspace.getProject(id)!!,
                                sessionId,
                                pending,
                                confirmed = true,
                            )
                        }
                    }
                }) { Text("Run") }
            },
            dismissButton = { TextButton(onClick = { pendingConfirm = null }) { Text("Cancel") } },
        )
    }
}
