package dev.akhil.freebuff.ui.research

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.akhil.freebuff.core.Format
import dev.akhil.freebuff.core.NetworkMonitor
import dev.akhil.freebuff.design.AppIcons
import dev.akhil.freebuff.design.EmptyState
import dev.akhil.freebuff.design.IconTile
import dev.akhil.freebuff.design.SectionHeader
import dev.akhil.freebuff.design.StatusChip
import dev.akhil.freebuff.design.SurfaceCard
import dev.akhil.freebuff.ui.AppViewModel
import dev.akhil.freebuff.ui.chat.MarkdownText
import kotlinx.coroutines.launch

@Composable
fun ResearchScreen(viewModel: AppViewModel) {
    val progress by viewModel.researchProgress.collectAsState()
    val sessions by viewModel.researchSessions.collectAsState()
    val settings by viewModel.settings.collectAsState()
    var question by remember { mutableStateOf("") }
    var openSession by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val online = NetworkMonitor.isOnline(viewModel.container.appContext)

    if (openSession != null) {
        val session = sessions.firstOrNull { it.id == openSession }
        val sources by remember(openSession) {
            viewModel.container.research.observeSources(openSession!!)
        }.collectAsState(initial = emptyList())
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconTile(AppIcons.Globe)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        session?.question ?: "Research",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "${sources.size} sources · ${session?.status ?: ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { openSession = null }) { Text("Back") }
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            ) {
                session?.let { s ->
                    if (s.plan.isNotBlank()) {
                        Text("Plan", style = MaterialTheme.typography.titleMedium)
                        Text(s.plan, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                    }
                    MarkdownText(s.report)
                    Spacer(Modifier.height(16.dp))
                }
                Text("Sources", style = MaterialTheme.typography.titleMedium)
                sources.forEachIndexed { index, source ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .clickable {
                                clipboard.setText(AnnotatedString(source.url))
                                viewModel.showToast("Link copied.")
                            },
                    ) {
                        Text(
                            "[${index + 1}]",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(30.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(source.title, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                source.url,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (source.note.isNotBlank()) {
                                Text(
                                    source.note,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        StatusChip(
                            if (source.verified) "Verified" else "Unchecked",
                            if (source.verified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                Spacer(Modifier.height(30.dp))
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("intro") {
            SectionHeader(
                title = "Deep research",
                subtitle = "Searches several providers, reads the pages, cites real URLs",
            )
            if (!online) {
                Spacer(Modifier.height(8.dp))
                SurfaceCard {
                    Text(
                        "You are offline. Deep Research needs the internet — your local model still answers " +
                            "questions in Chat without it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        item("input") {
            SurfaceCard {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    label = { Text("Research question") },
                    placeholder = { Text("How does GGUF quantization affect model quality?") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Provider: ${settings.searchProviderId} · up to ${settings.deepResearchMaxSources} sources",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = { viewModel.startResearch(question) },
                        enabled = question.isNotBlank() && !progress.running && online,
                    ) { Text(if (progress.running) "Researching…" else "Research") }
                }
            }
        }

        if (progress.running || progress.error != null || progress.report.isNotBlank()) {
            item("progress") {
                SurfaceCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (progress.running) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            progress.error ?: progress.step,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (progress.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (progress.stepCount > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Step ${progress.stepIndex} of ${progress.stepCount} · " +
                                "${progress.sourcesFound} sources · ${progress.pagesRead} pages read",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (progress.report.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp),
                        ) {
                            MarkdownText(progress.report)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row {
                            TextButton(onClick = {
                                clipboard.setText(AnnotatedString(progress.report))
                                viewModel.showToast("Report copied.")
                            }) { Text("Copy report") }
                            if (progress.sessionId != null) {
                                TextButton(onClick = { openSession = progress.sessionId }) { Text("Sources") }
                            }
                        }
                    }
                }
            }
        }

        item("history-header") {
            Spacer(Modifier.height(4.dp))
            SectionHeader("Previous research", subtitle = "${sessions.size} saved locally")
        }

        if (sessions.isEmpty()) {
            item("empty") {
                EmptyState(
                    icon = AppIcons.Globe,
                    title = "No research yet",
                    message = "Every report keeps its sources so you can check the evidence later.",
                )
            }
        }

        items(sessions, key = { it.id }) { session ->
            SurfaceCard(onClick = { openSession = session.id }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(AppIcons.Globe, size = 34)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(session.question, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                        Text(
                            "${Format.relative(session.createdAt)} · ${session.status.lowercase()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
