package dev.universalai.agent.ui.screens.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.universalai.agent.core.AppContainer
import dev.universalai.agent.core.Formatting
import dev.universalai.agent.data.model.Approval
import dev.universalai.agent.data.model.Run
import dev.universalai.agent.data.model.RunEvent
import dev.universalai.agent.ui.components.ErrorBanner
import dev.universalai.agent.ui.components.GlassCard
import dev.universalai.agent.ui.components.LoadingState
import dev.universalai.agent.ui.components.MarkdownText
import dev.universalai.agent.ui.components.PearlBackground
import dev.universalai.agent.ui.components.Pill
import dev.universalai.agent.ui.components.PrimaryButton
import dev.universalai.agent.ui.components.ScreenTopBar
import dev.universalai.agent.ui.components.SecondaryButton
import dev.universalai.agent.ui.components.SectionHeader
import dev.universalai.agent.ui.components.StatusDot
import dev.universalai.agent.ui.components.rememberViewModel
import dev.universalai.agent.ui.theme.Palette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class RunDetailUiState(
    val loading: Boolean = true,
    val run: Run? = null,
    val approvals: List<Approval> = emptyList(),
    val events: List<RunEvent> = emptyList(),
    val error: String? = null,
    val busy: Boolean = false,
)

class RunDetailViewModel(
    private val container: AppContainer,
    private val runId: String,
) : ViewModel() {

    var state by mutableStateOf(RunDetailUiState())
        private set

    private var observer: Job? = null

    init {
        refresh()
        observe()
    }

    fun refresh() {
        viewModelScope.launch {
            val result = container.agents.run(runId)
            val detail = result.valueOrNull()
            state = state.copy(
                loading = false,
                run = detail?.run ?: state.run,
                approvals = detail?.approvals ?: state.approvals,
                error = (result as? dev.universalai.agent.core.AppResult.Failure)?.message,
            )
        }
    }

    private fun observe() {
        observer?.cancel()
        observer = viewModelScope.launch {
            var lastSeq = state.events.lastOrNull()?.seq ?: 0
            while (isActive) {
                val detail = container.agents.run(runId).valueOrNull() ?: break
                state = state.copy(
                    loading = false,
                    run = detail.run,
                    approvals = detail.approvals,
                )
                if (detail.run.isTerminal) break
                try {
                    container.agents.events(runId, lastSeq).collect { event ->
                        if (event.seq > lastSeq) {
                            lastSeq = event.seq
                            state = state.copy(events = state.events + event)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // Reconnect below; the run's own status decides whether to continue.
                }
                if (container.agents.run(runId).valueOrNull()?.run?.isTerminal == true) break
                delay(900)
            }
            refresh()
        }
    }

    fun decide(approval: Approval, approved: Boolean) {
        state = state.copy(busy = true)
        viewModelScope.launch {
            val result = container.agents.decide(approval.id, approved)
            state = state.copy(
                busy = false,
                error = (result as? dev.universalai.agent.core.AppResult.Failure)?.message,
            )
            refresh()
        }
    }

    fun cancel() {
        viewModelScope.launch {
            val result = container.agents.cancel(runId)
            state = state.copy(
                run = result.valueOrNull() ?: state.run,
                error = (result as? dev.universalai.agent.core.AppResult.Failure)?.message,
            )
            refresh()
        }
    }

    override fun onCleared() {
        observer?.cancel()
        super.onCleared()
    }
}

@Composable
fun RunDetailScreen(container: AppContainer, runId: String, onBack: () -> Unit) {
    val viewModel = rememberViewModel(key = runId) { RunDetailViewModel(container, runId) }
    val state = viewModel.state
    val run = state.run

    PearlBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenTopBar(
                title = "Run",
                subtitle = run?.status?.replace('_', ' '),
                onBack = onBack,
            )
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
                if (state.loading && run == null) {
                    item { LoadingState("Loading run") }
                }
                if (run != null) {
                    item {
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusDot(
                                    active = run.status == "succeeded",
                                    activeColor = statusColor(run.status),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = run.status.replace('_', ' '),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Palette.Ink,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = run.runtime,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Palette.Muted,
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = run.objective,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Palette.Graphite,
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Pill(text = "${run.tokensUsed} tokens")
                                Pill(text = Formatting.relativeTime(run.createdAt))
                            }
                            if (!run.isTerminal) {
                                Spacer(Modifier.height(12.dp))
                                SecondaryButton(
                                    label = "Cancel run",
                                    onClick = viewModel::cancel,
                                    enabled = !state.busy,
                                )
                            }
                            if (run.error != null) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = run.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Palette.Rose,
                                )
                            }
                        }
                    }
                    if (run.plan.isNotEmpty()) {
                        item { SectionHeader(title = "Plan", subtitle = "${run.plan.size} steps") }
                        items(run.plan, key = { it.id }) { step ->
                            GlassCard(
                                modifier = Modifier.fillMaxWidth(),
                                padding = androidx.compose.foundation.layout.PaddingValues(14.dp),
                            ) {
                                Row {
                                    Text(
                                        text = "${step.id}.",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = Palette.Faint,
                                        modifier = Modifier.width(22.dp),
                                    )
                                    Column {
                                        Text(
                                            step.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = Palette.Ink,
                                        )
                                        if (step.detail.isNotBlank()) {
                                            Text(
                                                step.detail,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Palette.Muted,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                val pending = state.approvals.filter { it.status == "pending" }
                if (pending.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = "Approval needed",
                            subtitle = "The run is paused until you decide",
                        )
                    }
                    items(pending, key = { it.id }) { approval ->
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = approval.toolName,
                                style = MaterialTheme.typography.titleMedium,
                                color = Palette.Ink,
                            )
                            Text(
                                text = approval.reason ?: "This action needs your approval.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Palette.Muted,
                            )
                            val args = approval.arguments.toString()
                            if (args.length > 4) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = args.take(400),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Palette.Graphite,
                                    fontFamily = dev.universalai.agent.ui.theme.MonoFont,
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PrimaryButton(
                                    label = "Approve",
                                    onClick = { viewModel.decide(approval, true) },
                                    enabled = !state.busy,
                                )
                                SecondaryButton(
                                    label = "Reject",
                                    onClick = { viewModel.decide(approval, false) },
                                    enabled = !state.busy,
                                )
                            }
                        }
                    }
                }

                val runResult = run?.result
                if (!runResult.isNullOrBlank()) {
                    item { SectionHeader(title = "Result") }
                    item {
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            MarkdownText(markdown = runResult)
                        }
                    }
                }

                if (state.events.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = "Activity",
                            subtitle = "${state.events.size} events",
                        )
                    }
                    items(state.events.takeLast(40).reversed(), key = { it.seq }) { event ->
                        EventRow(event)
                    }
                }
            }
        }
    }
}

@Composable
private fun EventRow(event: RunEvent) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.Pearl.copy(alpha = 0.4f))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(
                    when (event.type) {
                        "run_completed" -> Palette.Sage
                        "run_failed", "run_cancelled" -> Palette.Rose
                        "approval_required" -> Palette.Amber
                        else -> Palette.Faint
                    },
                ),
        ) {}
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = event.type.replace('_', ' '),
                style = MaterialTheme.typography.labelSmall,
                color = Palette.Graphite,
            )
            val detail = eventDetail(event)
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Muted,
                )
            }
        }
    }
}

private fun eventDetail(event: RunEvent): String {
    val payload = event.payload
    fun value(key: String): String? = payload[key]?.toString()?.trim('"')?.takeIf { it.isNotBlank() }
    return when (event.type) {
        "run_started" -> value("objective")?.let { Formatting.truncate(it, 140) } ?: ""
        "model_selected" -> listOfNotNull(value("model"), value("provider")).joinToString(" · ")
        "plan" -> "Plan created"
        "step_started" -> value("title") ?: ""
        "step_completed" -> value("summary")?.let { Formatting.truncate(it, 160) } ?: ""
        "tool_call" -> value("tool")?.let { "Tool: $it" } ?: ""
        "tool_result" -> value("tool")?.let { "Result: $it" } ?: ""
        "approval_required", "approval_decided" -> value("tool") ?: ""
        "assistant_message" -> value("text")?.let { Formatting.truncate(it, 160) } ?: ""
        "run_failed" -> value("message") ?: ""
        "run_cancelled" -> value("reason") ?: "Cancelled"
        "delegated" -> value("agent_kind")?.let { "Delegated to $it" } ?: ""
        "planning_degraded" -> value("message") ?: ""
        "retry" -> value("message") ?: ""
        else -> ""
    }
}

private fun statusColor(status: String) = when (status) {
    "succeeded" -> Palette.Sage
    "failed" -> Palette.Rose
    "awaiting_approval" -> Palette.Amber
    else -> Palette.Faint
}
