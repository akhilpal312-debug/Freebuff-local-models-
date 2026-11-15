package dev.akhil.freebuff.ui.history

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.akhil.freebuff.core.Format
import dev.akhil.freebuff.data.db.ConversationEntity
import dev.akhil.freebuff.design.AppIcons
import dev.akhil.freebuff.design.EmptyState
import dev.akhil.freebuff.design.IconTile
import dev.akhil.freebuff.design.SectionHeader
import dev.akhil.freebuff.design.SurfaceCard
import dev.akhil.freebuff.ui.AppViewModel

@Composable
fun HistoryScreen(viewModel: AppViewModel, onOpenConversation: (String) -> Unit) {
    val all by viewModel.conversations.collectAsState()
    var query by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<ConversationEntity?>(null) }
    var deleting by remember { mutableStateOf<ConversationEntity?>(null) }

    val visible = if (query.isBlank()) {
        all
    } else {
        all.filter { it.title.contains(query, ignoreCase = true) }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp)) {
            SectionHeader(
                title = "History",
                subtitle = "${all.size} conversations stored on this device only",
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search conversations") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (visible.isEmpty()) {
                item("empty") {
                    EmptyState(
                        icon = AppIcons.Clock,
                        title = "No conversations",
                        message = "Chats you start appear here. They never leave the phone and are excluded from backups.",
                    )
                }
            }
            items(visible, key = { it.id }) { conversation ->
                SurfaceCard(onClick = { onOpenConversation(conversation.id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconTile(AppIcons.Clock, size = 34)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(conversation.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${conversation.mode.lowercase().replace('_', ' ')} · ${Format.relative(conversation.updatedAt)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row {
                        TextButton(onClick = { renaming = conversation }) { Text("Rename") }
                        TextButton(onClick = { deleting = conversation }) { Text("Delete") }
                    }
                }
            }
        }
    }

    renaming?.let { conversation ->
        var title by remember(conversation.id) { mutableStateOf(conversation.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename chat") },
            text = {
                OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameConversation(conversation.id, title)
                    renaming = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }

    deleting?.let { conversation ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete conversation?") },
            text = { Text("\"${conversation.title}\" and its messages will be removed from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteConversation(conversation.id)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}
