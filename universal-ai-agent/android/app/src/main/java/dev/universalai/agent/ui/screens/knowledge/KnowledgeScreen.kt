package dev.universalai.agent.ui.screens.knowledge

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.universalai.agent.core.AppContainer
import dev.universalai.agent.core.AppResult
import dev.universalai.agent.core.Formatting
import dev.universalai.agent.data.model.Doc
import dev.universalai.agent.data.model.ResearchResult
import dev.universalai.agent.ui.components.EmptyState
import dev.universalai.agent.ui.components.ErrorBanner
import dev.universalai.agent.ui.components.GlassCard
import dev.universalai.agent.ui.components.LoadingState
import dev.universalai.agent.ui.components.MarkdownText
import dev.universalai.agent.ui.components.PearlBackground
import dev.universalai.agent.ui.components.PearlTextField
import dev.universalai.agent.ui.components.Pill
import dev.universalai.agent.ui.components.PrimaryButton
import dev.universalai.agent.ui.components.ScreenTopBar
import dev.universalai.agent.ui.components.SecondaryButton
import dev.universalai.agent.ui.components.SectionHeader
import dev.universalai.agent.ui.components.rememberViewModel
import dev.universalai.agent.ui.theme.Palette
import kotlinx.coroutines.launch

data class KnowledgeUiState(
    val loading: Boolean = true,
    val tab: String = "library",
    val documents: List<Doc> = emptyList(),
    val query: String = "",
    val error: String? = null,
    val notice: String? = null,
    val showCreate: Boolean = false,
    val creating: Boolean = false,
    val researchQuery: String = "",
    val researching: Boolean = false,
    val research: ResearchResult? = null,
    val saveResearch: Boolean = true,
)

class KnowledgeViewModel(private val container: AppContainer) : ViewModel() {

    var state by mutableStateOf(KnowledgeUiState())
        private set

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            state = state.copy(loading = true)
            val result = container.knowledge.documents(state.query.takeIf { it.isNotBlank() })
            state = state.copy(
                loading = false,
                documents = result.valueOrNull().orEmpty(),
                error = (result as? AppResult.Failure)?.message,
            )
        }
    }

    fun selectTab(tab: String) { state = state.copy(tab = tab) }
    fun onQueryChange(value: String) { state = state.copy(query = value) }
    fun onResearchQueryChange(value: String) { state = state.copy(researchQuery = value) }
    fun toggleSaveResearch(value: Boolean) { state = state.copy(saveResearch = value) }
    fun openCreate() { state = state.copy(showCreate = true) }
    fun closeCreate() { state = state.copy(showCreate = false) }

    fun create(title: String, content: String) {
        state = state.copy(creating = true, showCreate = false)
        viewModelScope.launch {
            val result = container.knowledge.create(title.trim(), content)
            state = state.copy(
                creating = false,
                notice = result.valueOrNull()?.let { "${it.title} created." }
                    ?: (result as? AppResult.Failure)?.message,
                error = (result as? AppResult.Failure)?.message,
            )
            refresh()
        }
    }

    fun research() {
        val query = state.researchQuery.trim()
        if (query.isEmpty() || state.researching) return
        state = state.copy(researching = true, error = null, research = null)
        viewModelScope.launch {
            val result = container.knowledge.research(query, state.saveResearch)
            state = state.copy(
                researching = false,
                research = result.valueOrNull(),
                error = (result as? AppResult.Failure)?.message,
            )
            if (result.isSuccess) refresh()
        }
    }
}

@Composable
fun KnowledgeScreen(container: AppContainer, onOpenDocument: (String) -> Unit) {
    val viewModel = rememberViewModel { KnowledgeViewModel(container) }
    val state = viewModel.state
    val uriHandler = LocalUriHandler.current

    PearlBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenTopBar(
                title = "Knowledge",
                subtitle = "Notes, documents and verified research",
            ) {
                IconButton(onClick = viewModel::openCreate) {
                    Icon(Icons.Filled.Add, contentDescription = "New note", tint = Palette.Graphite)
                }
            }
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Pill("Library", state.tab == "library", { viewModel.selectTab("library") })
                Pill("Web research", state.tab == "research", { viewModel.selectTab("research") })
            }
            if (state.error != null) {
                androidx.compose.foundation.layout.Box(
                    Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                ) { ErrorBanner(state.error, onRetry = viewModel::refresh) }
            }

            if (state.tab == "library") {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 18.dp, end = 18.dp, top = 6.dp, bottom = 30.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PearlTextField(
                                label = "Search notes",
                                value = state.query,
                                onValueChange = viewModel::onQueryChange,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            SecondaryButton(label = "Search", onClick = viewModel::refresh)
                        }
                    }
                    if (state.loading) {
                        item { LoadingState("Loading documents") }
                    } else if (state.documents.isEmpty()) {
                        item {
                            EmptyState(
                                title = "Nothing saved yet",
                                body = "Create a note or run web research and save the report here.",
                                actionLabel = "New note",
                                onAction = viewModel::openCreate,
                            )
                        }
                    }
                    items(state.documents, key = { it.id }) { doc ->
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onOpenDocument(doc.id) },
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = doc.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Palette.Ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = doc.kind,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Palette.Faint,
                                )
                            }
                            Text(
                                text = doc.excerpt ?: Formatting.truncate(doc.contentMd, 140),
                                style = MaterialTheme.typography.bodySmall,
                                color = Palette.Muted,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = Formatting.relativeTime(doc.updatedAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = Palette.Faint,
                            )
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp),
                ) {
                    Text(
                        text = "Research fetches public pages, extracts sources and only cites pages " +
                            "it actually retrieved. Unverified results are labelled.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Muted,
                    )
                    Spacer(Modifier.height(10.dp))
                    PearlTextField(
                        label = "Research question",
                        value = state.researchQuery,
                        onValueChange = viewModel::onResearchQueryChange,
                        singleLine = false,
                        minLines = 2,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Pill(
                            text = if (state.saveResearch) "Save to library" else "Don't save",
                            selected = state.saveResearch,
                            onClick = { viewModel.toggleSaveResearch(!state.saveResearch) },
                        )
                        Spacer(Modifier.width(10.dp))
                        PrimaryButton(
                            label = "Research",
                            onClick = viewModel::research,
                            enabled = state.researchQuery.isNotBlank(),
                            loading = state.researching,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    val research = state.research
                    if (research != null) {
                        if (!research.searched) {
                            GlassCard(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    "No live search ran for this query",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Palette.Ink,
                                )
                                Text(
                                    text = research.errors.firstOrNull()
                                        ?: "No search provider is configured on the server, so there " +
                                        "are no sources to cite. The report below is generated without " +
                                        "web evidence.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Palette.Muted,
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                        if (research.sources.isNotEmpty()) {
                            SectionHeader(
                                title = "Sources",
                                subtitle = "${research.sources.count { it.verified }} verified · " +
                                    "${research.sources.count { !it.verified }} unverified",
                            )
                            research.sources.forEach { source ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { uriHandler.openUri(source.url) }
                                        .padding(vertical = 7.dp),
                                ) {
                                    Text(
                                        text = "[${source.id}]",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Palette.Clay,
                                        modifier = Modifier.width(28.dp),
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            source.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = Palette.Ink,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "${source.url} · " +
                                                if (source.verified) "verified" else "unverified",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (source.verified) Palette.Sage else Palette.Amber,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(14.dp))
                        }
                        if (research.synthesisError != null) {
                            Text(
                                text = "Synthesis model unavailable: ${research.synthesisError}. " +
                                    "The report is extractive.",
                                style = MaterialTheme.typography.labelSmall,
                                color = Palette.Amber,
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            MarkdownText(markdown = research.reportMd)
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "Synthesis: ${research.synthesis} · ${research.durationMs} ms" +
                                (research.document?.let { " · saved as ${it.title}" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = Palette.Faint,
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    if (state.showCreate) {
        var title by remember { mutableStateOf("") }
        var content by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = viewModel::closeCreate,
            title = { Text("New note") },
            text = {
                Column {
                    PearlTextField("Title", title, { title = it })
                    Spacer(Modifier.height(10.dp))
                    PearlTextField(
                        "Content (Markdown)",
                        content,
                        { content = it },
                        singleLine = false,
                        minLines = 6,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.create(title, content) },
                    enabled = title.isNotBlank(),
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = viewModel::closeCreate) { Text("Cancel") } },
        )
    }
}
