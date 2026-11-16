package dev.universalai.agent.ui.screens.knowledge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.universalai.agent.core.AppContainer
import dev.universalai.agent.core.AppResult
import dev.universalai.agent.data.model.Doc
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
import dev.universalai.agent.ui.components.rememberViewModel
import dev.universalai.agent.ui.theme.Palette
import kotlinx.coroutines.launch

data class DocumentUiState(
    val loading: Boolean = true,
    val doc: Doc? = null,
    val title: String = "",
    val content: String = "",
    val editing: Boolean = false,
    val saving: Boolean = false,
    val summarising: Boolean = false,
    val summary: String? = null,
    val summaryError: String? = null,
    val error: String? = null,
    val notice: String? = null,
)

class DocumentViewModel(
    private val container: AppContainer,
    private val documentId: String,
) : ViewModel() {

    var state by mutableStateOf(DocumentUiState())
        private set

    init { load() }

    fun load() {
        viewModelScope.launch {
            val result = container.knowledge.document(documentId)
            val doc = result.valueOrNull()
            state = state.copy(
                loading = false,
                doc = doc,
                title = doc?.title ?: "",
                content = doc?.contentMd ?: "",
                error = (result as? AppResult.Failure)?.message,
            )
        }
    }

    fun onTitleChange(value: String) { state = state.copy(title = value, editing = true) }
    fun onContentChange(value: String) { state = state.copy(content = value, editing = true) }
    fun toggleEditing() { state = state.copy(editing = !state.editing) }

    fun save() {
        state = state.copy(saving = true)
        viewModelScope.launch {
            val result = container.knowledge.update(
                documentId,
                title = state.title.trim().ifBlank { null },
                content = state.content,
            )
            state = state.copy(
                saving = false,
                doc = result.valueOrNull() ?: state.doc,
                editing = false,
                notice = if (result.isSuccess) "Saved." else null,
                error = (result as? AppResult.Failure)?.message,
            )
        }
    }

    fun summarise() {
        state = state.copy(summarising = true, summaryError = null)
        viewModelScope.launch {
            val result = container.knowledge.summarise(documentId)
            state = state.copy(
                summarising = false,
                summary = result.valueOrNull(),
                summaryError = (result as? AppResult.Failure)?.message
                    ?: if (result.valueOrNull().isNullOrBlank()) {
                        "The model returned an empty summary."
                    } else {
                        null
                    },
            )
        }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            val result = container.knowledge.delete(documentId)
            if (result.isSuccess) onDeleted()
            else state = state.copy(error = (result as? AppResult.Failure)?.message)
        }
    }
}

@Composable
fun DocumentScreen(
    container: AppContainer,
    documentId: String,
    onBack: () -> Unit,
) {
    val viewModel = rememberViewModel(key = documentId) {
        DocumentViewModel(container, documentId)
    }
    val state = viewModel.state
    val clipboard = LocalClipboardManager.current

    PearlBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenTopBar(
                title = state.doc?.title ?: "Document",
                subtitle = state.doc?.kind,
                onBack = onBack,
            ) {
                IconButton(onClick = { clipboard.setText(AnnotatedString(state.content)) }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy", tint = Palette.Graphite)
                }
                IconButton(onClick = { viewModel.delete(onBack) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Palette.Faint)
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp),
            ) {
                if (state.error != null) {
                    ErrorBanner(state.error, onRetry = viewModel::load)
                    Spacer(Modifier.height(8.dp))
                }
                if (state.loading) {
                    LoadingState("Loading document")
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Pill(
                            text = if (state.editing) "Editing" else "Reading",
                            selected = state.editing,
                            onClick = viewModel::toggleEditing,
                        )
                        SecondaryButton(
                            label = if (state.summarising) "Summarising…" else "AI summary",
                            onClick = viewModel::summarise,
                            enabled = !state.summarising,
                        )
                        if (state.editing) {
                            PrimaryButton(
                                label = "Save",
                                onClick = viewModel::save,
                                loading = state.saving,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (state.editing) {
                        PearlTextField(
                            label = "Title",
                            value = state.title,
                            onValueChange = viewModel::onTitleChange,
                        )
                        Spacer(Modifier.height(10.dp))
                        PearlTextField(
                            label = "Markdown",
                            value = state.content,
                            onValueChange = viewModel::onContentChange,
                            singleLine = false,
                            minLines = 14,
                        )
                    } else {
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            MarkdownText(markdown = state.content.ifBlank { "_Empty document._" })
                        }
                    }

                    if (state.summary != null) {
                        Spacer(Modifier.height(14.dp))
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "Summary",
                                style = MaterialTheme.typography.titleMedium,
                                color = Palette.Ink,
                            )
                            Spacer(Modifier.height(6.dp))
                            MarkdownText(markdown = state.summary)
                        }
                    }
                    if (state.summaryError != null) {
                        Spacer(Modifier.height(10.dp))
                        Box {
                            ErrorBanner(state.summaryError)
                        }
                    }
                    if (state.notice != null) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            state.notice,
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.Sage,
                        )
                    }
                }
                Spacer(Modifier.height(26.dp))
                Text(
                    text = "Documents are stored in this workspace's database.",
                    style = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.SansSerif),
                    color = Palette.Faint,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
