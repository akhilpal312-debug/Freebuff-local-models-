"""Native agent runtime.

Execution model
---------------
1. a run row is created (`agent_runs`) and executed in a background task
2. the planner asks the selected model for a 2-6 step JSON plan; the plan is
   stored, so a run is resumable and auditable
3. each step is a bounded tool loop (max 6 rounds). Tool calls that are sensitive
   create an `approvals` row and pause the run until the user decides
4. every event is written to `run_events` with a monotonically increasing `seq`,
   so clients stream by polling and can resume after a reconnect
5. cancellation is honoured between rounds and while waiting for approval
6. a Project Manager agent may delegate a sub-objective: a child run is created
   (visible in the UI) and awaited synchronously, depth-limited

No tool can reach outside the project workspace, and no command runs without the
allowlist in `coding.CommandRunner`.
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
import time
import uuid
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings
from app.core.errors import APIError, bad_request, not_found
from app.db.session import Database
from app.db.tables import (
    Agent,
    AgentRun,
    Approval,
    Conversation,
    Project,
    RunEventRow,
    UsageLog,
)
from app.services.agent.tools import (
    APPROVAL_REQUIRED,
    TOOL_SPECS,
    ToolContext,
    ToolExecutor,
    needs_approval,
    tools_for,
)
from app.services.coding import WorkspaceManager
from app.services.model_gateway.gateway import ModelGateway, load_model_and_provider
from app.services.model_gateway.router import select_model
from app.services.openclaw import OpenClawClient, OpenClawError
from app.services.prompts import PLANNER_PROMPT, system_prompt
from app.services.providers.base import ChatMessage, StreamEvent, ToolCall, ToolSpec

logger = logging.getLogger(__name__)

MAX_STEPS = 8
MAX_TOOL_ROUNDS = 6
MAX_DEPTH = 2
APPROVAL_TIMEOUT_SECONDS = 600

SPECIALIST_PROMPTS: dict[str, str] = {
    "general": "You are a general-purpose assistant executing a task end to end.",
    "coding": (
        "You are a senior software engineer. Read the relevant files before editing, "
        "prefer minimal diffs, and verify with the project's own tooling. Never report a "
        "build or test as passing unless the command output shows it."
    ),
    "research": (
        "You are a research analyst. Use web.search and web.fetch, cite URLs you actually "
        "fetched, and distinguish evidence from inference."
    ),
    "planning": (
        "You are a planning specialist. Break objectives into verifiable steps, surface "
        "risks and unknowns, and recommend the smallest viable path."
    ),
    "document": (
        "You are a technical writer. Produce clear, well-structured Markdown documents "
        "grounded in the material provided."
    ),
    "project_manager": (
        "You are a project manager agent. Decompose the objective, delegate focused "
        "sub-tasks to specialists with the delegate tool when that shortens the critical "
        "path, then integrate their results into one answer."
    ),
}

DELEGATE_SPEC = ToolSpec(
    name="delegate",
    description=(
        "Delegate a self-contained sub-objective to a specialist agent and wait for its "
        "result. Kinds: coding, research, planning, document, general."
    ),
    parameters={
        "type": "object",
        "properties": {
            "agent_kind": {
                "type": "string",
                "enum": ["coding", "research", "planning", "document", "general"],
            },
            "objective": {"type": "string"},
        },
        "required": ["agent_kind", "objective"],
    },
)


@dataclass
class RunPlanStep:
    id: int
    title: str
    detail: str = ""
    tool: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return {"id": self.id, "title": self.title, "detail": self.detail, "tool": self.tool}


@dataclass
class RunContext:
    run: AgentRun
    agent: Agent | None
    project: Project | None
    permission_profile: str
    enabled_tools: list[str]
    agent_kind: str
    instructions: str
    depth: int = 0
    observations: list[str] = field(default_factory=list)
    tokens: int = 0
    last_text: str = ""


def parse_plan(text: str) -> list[RunPlanStep]:
    """Extract a plan from model output; returns [] when the output is unusable."""
    cleaned = text.strip()
    cleaned = re.sub(r"^```(?:json)?|```$", "", cleaned, flags=re.MULTILINE).strip()
    start, end = cleaned.find("{"), cleaned.rfind("}")
    if start == -1 or end == -1 or end <= start:
        return []
    try:
        payload = json.loads(cleaned[start : end + 1])
    except json.JSONDecodeError:
        return []
    steps: list[RunPlanStep] = []
    for index, item in enumerate(payload.get("steps") or [], start=1):
        if not isinstance(item, dict):
            continue
        title = str(item.get("title") or "").strip()
        if not title:
            continue
        steps.append(
            RunPlanStep(
                id=int(item.get("id") or index),
                title=title[:200],
                detail=str(item.get("detail") or "")[:600],
                tool=item.get("tool") if isinstance(item.get("tool"), str) else None,
            )
        )
    return steps[:MAX_STEPS]


def merge_tool_calls(events: list[StreamEvent]) -> list[ToolCall]:
    """Merge streamed tool-call fragments (OpenAI) into complete calls."""
    merged: dict[int, ToolCall] = {}
    order: list[int] = []
    for event in events:
        if event.kind != "tool_call" or event.tool_call is None:
            continue
        index = int(event.provider_meta.get("index", 0))
        call = event.tool_call
        existing = merged.get(index)
        if existing is None:
            merged[index] = ToolCall(
                id=call.id or f"call_{index}", name=call.name, arguments=call.arguments
            )
            order.append(index)
        else:
            if call.id:
                existing.id = call.id
            if call.name:
                existing.name = existing.name or call.name
            existing.arguments += call.arguments
    return [merged[index] for index in order if merged[index].name]


class AgentOrchestrator:
    def __init__(
        self,
        *,
        database: Database,
        gateway: ModelGateway,
        providers: Any,
        tools: ToolExecutor,
        workspace: WorkspaceManager,
        research: Any,
        settings: Settings,
        openclaw: OpenClawClient,
    ) -> None:
        self.database = database
        self.gateway = gateway
        self.providers = providers
        self.tools = tools
        self.workspace = workspace
        self.research = research
        self.settings = settings
        self.openclaw = openclaw
        self._tasks: dict[uuid.UUID, asyncio.Task] = {}
        self._cancel: dict[uuid.UUID, asyncio.Event] = {}

    # ------------------------------------------------------------------- creation
    async def create_run(
        self,
        session: AsyncSession,
        user_id: uuid.UUID,
        *,
        objective: str,
        agent_id: uuid.UUID | None = None,
        project_id: uuid.UUID | None = None,
        conversation_id: uuid.UUID | None = None,
        runtime: str | None = None,
        model_pk: uuid.UUID | None = None,
        permission_profile: str | None = None,
        enabled_tools: list[str] | None = None,
    ) -> AgentRun:
        objective = objective.strip()
        if not objective:
            raise bad_request("An objective is required.")
        agent: Agent | None = None
        if agent_id is not None:
            agent = await session.scalar(
                select(Agent).where(Agent.id == agent_id, Agent.user_id == user_id)
            )
            if agent is None:
                raise not_found("Agent")
        project: Project | None = None
        resolved_project = project_id or (agent.project_id if agent else None)
        if resolved_project is not None:
            project = await session.scalar(
                select(Project).where(Project.id == resolved_project, Project.user_id == user_id)
            )
            if project is None:
                raise not_found("Project")
        selected_runtime = runtime or (agent.runtime if agent else "native")
        if selected_runtime not in {"native", "openclaw"}:
            raise bad_request("runtime must be 'native' or 'openclaw'.")

        run = AgentRun(
            user_id=user_id,
            agent_id=agent.id if agent else None,
            conversation_id=conversation_id,
            project_id=project.id if project else None,
            objective=objective,
            runtime=selected_runtime,
            status="queued",
            plan=[],
            state={
                "permission_profile": permission_profile
                or (agent.permission_profile if agent else "read_only"),
                "enabled_tools": enabled_tools
                if enabled_tools is not None
                else (agent.enabled_tools if agent else []),
                "model_pk": str(model_pk or (agent.model_pk if agent else "") or ""),
                "agent_kind": agent.kind if agent else "general",
                "instructions": agent.instructions if agent else "",
                "seq": 0,
            },
        )
        session.add(run)
        await session.flush()
        return run

    def start(self, run_id: uuid.UUID) -> None:
        if run_id in self._tasks and not self._tasks[run_id].done():
            return
        # Only a queued run is started; a duplicate call cannot double-execute it.
        self._cancel[run_id] = asyncio.Event()
        task = asyncio.create_task(self._execute_run(run_id, depth=0), name=f"run-{run_id}")
        self._tasks[run_id] = task

        def _cleanup(_: asyncio.Task) -> None:
            self._tasks.pop(run_id, None)
            self._cancel.pop(run_id, None)

        task.add_done_callback(_cleanup)

    async def shutdown(self) -> None:
        tasks = list(self._tasks.values())
        for task in tasks:
            task.cancel()
        for task in tasks:
            # CancelledError is a BaseException, so `suppress(Exception)` is not
            # enough: letting it escape shutdown poisons the caller's task group.
            with __import__("contextlib").suppress(asyncio.CancelledError, Exception):
                await task
        self._tasks.clear()
        self._cancel.clear()

    # --------------------------------------------------------------------- events
    async def _emit(
        self, session: AsyncSession, run: AgentRun, event_type: str, payload: dict[str, Any]
    ) -> int:
        """Append one event with the next sequence number.

        The orchestrator task is the single writer for a run's events. The counter is
        cached in `run.state["seq"]`, and reconciled against the database when a run is
        resumed or its cached state looks stale.
        """
        state = dict(run.state or {})
        cached = int(state.get("seq", 0))
        stored = await session.scalar(
            select(func.max(RunEventRow.seq)).where(RunEventRow.run_id == run.id)
        )
        seq = max(cached, int(stored or 0)) + 1
        state["seq"] = seq
        run.state = state
        session.add(RunEventRow(run_id=run.id, seq=seq, type=event_type, payload=payload))
        await session.flush()
        return seq

    async def events_since(
        self, session: AsyncSession, run_id: uuid.UUID, after: int = 0
    ) -> list[RunEventRow]:
        return list(
            (
                await session.scalars(
                    select(RunEventRow)
                    .where(RunEventRow.run_id == run_id, RunEventRow.seq > after)
                    .order_by(RunEventRow.seq)
                    .limit(200)
                )
            ).all()
        )

    async def _cancelled(self, run_id: uuid.UUID, session: AsyncSession | None = None) -> bool:
        event = self._cancel.get(run_id)
        if event is not None and event.is_set():
            return True
        if session is not None:
            status = await session.scalar(select(AgentRun.status).where(AgentRun.id == run_id))
        else:
            async with self.database.session() as fresh:
                status = await fresh.scalar(
                    select(AgentRun.status).where(AgentRun.id == run_id)
                )
        return status in {"cancelling", "cancelled"}

    async def cancel(self, session: AsyncSession, user_id: uuid.UUID, run_id: uuid.UUID) -> AgentRun:
        run = await session.scalar(
            select(AgentRun).where(AgentRun.id == run_id, AgentRun.user_id == user_id)
        )
        if run is None:
            raise not_found("Run")
        if run.status in {"succeeded", "failed", "cancelled"}:
            return run
        run.status = "cancelling"
        await session.flush()
        event = self._cancel.get(run_id)
        if event is not None:
            event.set()
        # Deliberately no event is appended here: the run's events have exactly one
        # writer (the orchestrator task), which records `run_cancelled` itself.
        return run

    # ---------------------------------------------------------------- approvals
    async def decide_approval(
        self,
        session: AsyncSession,
        user_id: uuid.UUID,
        approval_id: uuid.UUID,
        *,
        approved: bool,
        reason: str | None = None,
    ) -> Approval:
        approval = await session.scalar(
            select(Approval).where(Approval.id == approval_id, Approval.user_id == user_id)
        )
        if approval is None:
            raise not_found("Approval")
        if approval.status != "pending":
            raise APIError(409, "approval_decided", "That approval was already decided.")
        approval.status = "approved" if approved else "rejected"
        approval.reason = reason
        approval.decided_at = datetime.now(UTC)
        await session.flush()
        return approval

    async def _await_approval(self, run: AgentRun, approval_id: uuid.UUID) -> str:
        deadline = time.monotonic() + APPROVAL_TIMEOUT_SECONDS
        while time.monotonic() < deadline:
            if await self._cancelled(run.id):
                return "cancelled"
            async with self.database.session() as fresh:
                status = await fresh.scalar(
                    select(Approval.status).where(Approval.id == approval_id)
                )
            if status in {"approved", "rejected"}:
                return status
            await asyncio.sleep(0.5)
        async with self.database.session() as fresh:
            approval = await fresh.scalar(select(Approval).where(Approval.id == approval_id))
            if approval is not None and approval.status == "pending":
                approval.status = "expired"
                await fresh.commit()
        return "expired"

    # -------------------------------------------------------------- execution
    async def _execute_run(self, run_id: uuid.UUID, *, depth: int) -> None:
        async with self.database.session() as session:
            run = await session.scalar(select(AgentRun).where(AgentRun.id == run_id))
            if run is None:
                logger.warning("run %s disappeared", run_id)
                return
            try:
                if run.runtime == "openclaw":
                    await self._execute_openclaw(session, run)
                else:
                    await self._execute_native(session, run, depth=depth)
            except asyncio.CancelledError:
                run.status = "cancelled"
                run.finished_at = datetime.now(UTC)
                await self._emit(session, run, "run_cancelled", {"reason": "shutdown"})
                await session.commit()
                raise
            except APIError as exc:
                await self._record_failure(
                    session, run, exc.message, {"code": exc.code, "details": exc.details}
                )
            except Exception as exc:  # pragma: no cover - unexpected failure
                logger.exception("run %s failed", run_id)
                await self._record_failure(
                    session, run, f"Unexpected error: {type(exc).__name__}", {}
                )

    async def _load_context(
        self, session: AsyncSession, run: AgentRun, depth: int
    ) -> RunContext:
        agent = (
            await session.scalar(select(Agent).where(Agent.id == run.agent_id))
            if run.agent_id
            else None
        )
        project = (
            await session.scalar(select(Project).where(Project.id == run.project_id))
            if run.project_id
            else None
        )
        state = run.state or {}
        return RunContext(
            run=run,
            agent=agent,
            project=project,
            permission_profile=str(state.get("permission_profile") or "read_only"),
            enabled_tools=list(state.get("enabled_tools") or []),
            agent_kind=str(state.get("agent_kind") or "general"),
            instructions=str(state.get("instructions") or ""),
            depth=depth,
        )

    def _tool_specs(self, ctx: RunContext) -> list[ToolSpec]:
        specs = tools_for(ctx.permission_profile, ctx.enabled_tools or None)
        if (
            ctx.agent_kind in {"project_manager", "general"}
            and ctx.depth < MAX_DEPTH
            and ctx.permission_profile != "read_only"
        ):
            specs = [*specs, DELEGATE_SPEC]
        return specs

    def _resolved_model_pk(self, run: AgentRun) -> uuid.UUID | None:
        raw = (run.state or {}).get("model_pk") or ""
        try:
            return uuid.UUID(str(raw)) if raw else None
        except ValueError:
            return None

    async def _select_model(self, session: AsyncSession, run: AgentRun):
        options = await self.providers.model_options(session, run.user_id)
        decision = select_model(
            options,
            mode="agent",
            requires_tools=True,
            preferred_model_pk=self._resolved_model_pk(run),
        )
        assert decision.option is not None
        model_row, provider = await load_model_and_provider(
            session, run.user_id, decision.option.model_pk
        )
        return decision, model_row, provider

    async def _plan(
        self,
        session: AsyncSession,
        ctx: RunContext,
        model_row,
        provider,
    ) -> list[RunPlanStep]:
        existing = run_plan(ctx.run)
        if existing:
            return existing
        tool_names = sorted({spec.name for spec in self._tool_specs(ctx)})
        messages = [
            ChatMessage(role="system", content=PLANNER_PROMPT),
            ChatMessage(
                role="user",
                content=(
                    f"Objective:\n{ctx.run.objective}\n\n"
                    f"Available tools: {', '.join(tool_names) or 'none'}\n"
                    f"Project: {ctx.project.name if ctx.project else 'none'}\n"
                    f"Permission profile: {ctx.permission_profile}"
                ),
            ),
        ]
        try:
            text, usage = await self.gateway.complete(
                session,
                model_row=model_row,
                provider=provider,
                messages=messages,
                max_tokens=900,
                temperature=0.2,
            )
            ctx.tokens += int(usage.get("total_tokens") or 0)
        except APIError as exc:
            await self._emit(
                session,
                ctx.run,
                "planning_degraded",
                {"message": f"Planner unavailable ({exc.message}); running step-by-step."},
            )
            return [RunPlanStep(id=1, title="Work the objective", detail=ctx.run.objective)]
        steps = parse_plan(text)
        if not steps:
            await self._emit(
                session,
                ctx.run,
                "planning_degraded",
                {"message": "The planner returned no usable plan; running step-by-step."},
            )
            steps = [RunPlanStep(id=1, title="Work the objective", detail=ctx.run.objective)]
        return steps

    async def _execute_native(self, session: AsyncSession, run: AgentRun, *, depth: int) -> None:
        ctx = await self._load_context(session, run, depth)
        run.status = "running"
        run.started_at = run.started_at or datetime.now(UTC)
        await self._emit(
            session,
            run,
            "run_started",
            {
                "objective": run.objective,
                "runtime": "native",
                "permission_profile": ctx.permission_profile,
                "agent_kind": ctx.agent_kind,
            },
        )
        await session.commit()

        decision, model_row, provider = await self._select_model(session, run)
        await self._emit(
            session,
            run,
            "model_selected",
            {
                "model": model_row.display_name,
                "model_id": model_row.model_id,
                "provider": provider.name,
                "reason": decision.reason,
            },
        )
        await session.commit()

        steps = await self._plan(session, ctx, model_row, provider)
        run.plan = [step.to_dict() for step in steps]
        await self._emit(
            session, run, "plan", {"steps": run.plan, "count": len(steps)}
        )
        await session.commit()

        specs = self._tool_specs(ctx)
        tool_ctx = ToolContext(
            user_id=run.user_id,
            session=session,
            settings=self.settings,
            permission_profile=ctx.permission_profile,
            project_root=self.workspace.project_root(run.user_id, ctx.project.id)
            if ctx.project
            else None,
            project_id=ctx.project.id if ctx.project else None,
            research=self.research,
        )
        persona = SPECIALIST_PROMPTS.get(ctx.agent_kind, SPECIALIST_PROMPTS["general"])
        base_system = (
            f"{system_prompt('agent')}\n\n{persona}\n\n"
            f"You are working through this plan:\n"
            + "\n".join(f"{step.id}. {step.title} — {step.detail}" for step in steps)
            + "\n\nCall exactly one or more tools when needed. When a step is finished, "
            "summarise what actually happened in 2-3 sentences."
        )

        transcript: list[ChatMessage] = [
            ChatMessage(role="system", content=base_system),
            ChatMessage(role="user", content=f"Objective: {run.objective}"),
        ]

        for step in steps:
            if await self._cancelled(run.id, session):
                await self._finish_cancelled(session, run)
                return
            await self._emit(
                session,
                run,
                "step_started",
                {"id": step.id, "title": step.title, "detail": step.detail},
            )
            await session.commit()
            transcript.append(
                ChatMessage(
                    role="user",
                    content=(
                        f"Step {step.id}: {step.title}\n{step.detail}\n"
                        "Do this step now. Use tools if required, then summarise the outcome."
                    ),
                )
            )
            step_text = ""
            for round_index in range(MAX_TOOL_ROUNDS):
                if await self._cancelled(run.id, session):
                    await self._finish_cancelled(session, run)
                    return
                events: list[StreamEvent] = []
                text_parts: list[str] = []
                try:
                    async for event in self.gateway.stream(
                        session,
                        model_row=model_row,
                        provider=provider,
                        messages=transcript,
                        tools=specs,
                        temperature=0.3,
                        max_tokens=min(model_row.max_output_tokens, 4096),
                    ):
                        control = self._cancel.get(run.id)
                        if control is not None and control.is_set():
                            await self._finish_cancelled(session, run)
                            return
                        if event.kind == "text":
                            text_parts.append(event.text)
                            if len(text_parts) % 8 == 0:
                                await self._emit(
                                    session,
                                    run,
                                    "delta",
                                    {"step": step.id, "text": event.text},
                                )
                                await session.commit()
                        elif event.kind == "usage" and event.usage:
                            ctx.tokens += int(event.usage.get("total_tokens") or 0)
                        elif event.kind in {"tool_call", "done"}:
                            events.append(event)
                        elif event.kind == "error":
                            raise APIError(
                                502, "provider_error", event.error or "Provider error."
                            )
                except APIError as exc:
                    if exc.code in {"provider_timeout", "provider_unreachable"} and round_index == 0:
                        await self._emit(
                            session,
                            run,
                            "retry",
                            {"step": step.id, "message": exc.message},
                        )
                        await session.commit()
                        await asyncio.sleep(1.5)
                        continue
                    raise
                text = "".join(text_parts)
                step_text = text or step_text
                calls = merge_tool_calls(events)
                if text.strip():
                    await self._emit(
                        session, run, "assistant_message", {"step": step.id, "text": text}
                    )
                    transcript.append(ChatMessage(role="assistant", content=text))
                if not calls:
                    break
                assistant_calls = [
                    {
                        "id": call.id,
                        "type": "function",
                        "function": {"name": call.name, "arguments": call.arguments or "{}"},
                    }
                    for call in calls
                ]
                transcript.append(
                    ChatMessage(
                        role="assistant", content=text, tool_calls=assistant_calls
                    )
                )
                for call in calls:
                    result_text = await self._run_tool(
                        session, run, ctx, tool_ctx, call, step
                    )
                    if result_text is None:
                        await self._finish_cancelled(session, run)
                        return
                    await self._emit(
                        session,
                        run,
                        "tool_result",
                        {
                            "step": step.id,
                            "tool": call.name,
                            "result": result_text[:2000],
                        },
                    )
                    transcript.append(
                        ChatMessage(
                            role="tool",
                            content=result_text,
                            tool_call_id=call.id,
                            name=call.name,
                        )
                    )
                    await session.commit()
            ctx.last_text = step_text.strip() or ctx.last_text
            ctx.observations.append(f"{step.id}. {step.title}: {step_text.strip()[:400]}")
            run.state = {**(run.state or {}), "current_step": step.id, "observations": ctx.observations}
            await self._emit(
                session,
                run,
                "step_completed",
                {"id": step.id, "summary": step_text.strip()[:1200]},
            )
            await session.commit()

        if await self._cancelled(run.id, session):
            await self._finish_cancelled(session, run)
            return
        result = ctx.last_text or "\n".join(ctx.observations) or "Run finished with no output."
        run.result = result[:20000]
        run.status = "succeeded"
        run.finished_at = datetime.now(UTC)
        run.tokens_used = ctx.tokens
        session.add(
            UsageLog(
                user_id=run.user_id,
                provider_id=provider.id,
                model_pk=model_row.id,
                run_id=run.id,
                feature="agent",
                total_tokens=ctx.tokens,
            )
        )
        await self._emit(
            session, run, "run_completed", {"status": "succeeded", "tokens": ctx.tokens}
        )
        await session.commit()

    async def _run_tool(
        self,
        session: AsyncSession,
        run: AgentRun,
        ctx: RunContext,
        tool_ctx: ToolContext,
        call: ToolCall,
        step: RunPlanStep,
    ) -> str | None:
        """Execute one tool call. Returns None when the run was cancelled."""
        try:
            arguments = json.loads(call.arguments or "{}")
        except json.JSONDecodeError:
            return json.dumps({"ok": False, "error": "Tool arguments were not valid JSON."})
        if not isinstance(arguments, dict):
            return json.dumps({"ok": False, "error": "Tool arguments must be an object."})

        await self._emit(
            session,
            run,
            "tool_call",
            {"step": step.id, "tool": call.name, "arguments": _safe_arguments(arguments)},
        )
        await session.commit()

        if call.name == "delegate":
            return await self._delegate(session, run, ctx, arguments)

        reason = needs_approval(call.name, arguments)
        if reason:
            approval = Approval(
                run_id=run.id,
                user_id=run.user_id,
                tool_name=call.name,
                arguments=_safe_arguments(arguments),
                status="pending",
            )
            session.add(approval)
            await session.flush()
            run.status = "awaiting_approval"
            await self._emit(
                session,
                run,
                "approval_required",
                {
                    "approval_id": str(approval.id),
                    "tool": call.name,
                    "arguments": _safe_arguments(arguments),
                    "reason": reason,
                },
            )
            await session.commit()
            decision = await self._await_approval(run, approval.id)
            if decision == "cancelled":
                return None
            run.status = "running"
            await self._emit(
                session, run, "approval_decided", {"approval_id": str(approval.id), "decision": decision}
            )
            await session.commit()
            if decision != "approved":
                return json.dumps(
                    {"ok": False, "error": f"The user did not approve this action ({decision})."}
                )

        if call.name not in TOOL_SPECS and call.name != "delegate":
            return json.dumps({"ok": False, "error": f"Unknown tool '{call.name}'."})
        try:
            result = await self.tools.execute(call.name, arguments, tool_ctx)
        except APIError as exc:
            return json.dumps({"ok": False, "error": exc.message, "code": exc.code})
        return result.to_message()

    async def _delegate(
        self, session: AsyncSession, run: AgentRun, ctx: RunContext, arguments: dict
    ) -> str:
        if ctx.depth >= MAX_DEPTH:
            return json.dumps({"ok": False, "error": "Delegation depth limit reached."})
        kind = str(arguments.get("agent_kind") or "general")
        objective = str(arguments.get("objective") or "").strip()
        if kind not in SPECIALIST_PROMPTS:
            return json.dumps({"ok": False, "error": f"Unknown agent kind '{kind}'."})
        if not objective:
            return json.dumps({"ok": False, "error": "A delegated objective is required."})
        child = AgentRun(
            user_id=run.user_id,
            agent_id=None,
            conversation_id=run.conversation_id,
            project_id=run.project_id,
            objective=f"[{kind}] {objective}",
            runtime="native",
            status="queued",
            plan=[],
            state={
                "permission_profile": ctx.permission_profile,
                "enabled_tools": ctx.enabled_tools,
                "model_pk": (run.state or {}).get("model_pk"),
                "agent_kind": kind,
                "instructions": ctx.instructions,
                "seq": 0,
                "delegated_from": str(run.id),
            },
        )
        session.add(child)
        await session.flush()
        await self._emit(
            session,
            run,
            "delegated",
            {"child_run_id": str(child.id), "agent_kind": kind, "objective": objective},
        )
        await session.commit()
        # The child run uses its own session so the parent transaction stays clean.
        async with self.database.session() as child_session:
            child_row = await child_session.scalar(select(AgentRun).where(AgentRun.id == child.id))
            assert child_row is not None
            await self._execute_native(child_session, child_row, depth=ctx.depth + 1)
        async with self.database.session() as fresh:
            final = await fresh.scalar(select(AgentRun).where(AgentRun.id == child.id))
        if final is None:
            return json.dumps({"ok": False, "error": "The delegated run disappeared."})
        return json.dumps(
            {
                "ok": final.status == "succeeded",
                "status": final.status,
                "run_id": str(final.id),
                "result": (final.result or final.error or "")[:6000],
            },
            ensure_ascii=False,
        )

    async def _record_failure(
        self,
        session: AsyncSession,
        run: AgentRun,
        message: str,
        extra: dict[str, Any],
    ) -> None:
        """Record a failed run in a clean transaction, even after a poisoned session."""
        try:
            await session.rollback()
            fresh_run = await session.scalar(select(AgentRun).where(AgentRun.id == run.id))
            if fresh_run is None:
                return
            fresh_run.status = "failed"
            fresh_run.error = message[:2000]
            fresh_run.finished_at = datetime.now(UTC)
            await self._emit(
                session,
                fresh_run,
                "run_failed",
                {"message": message, **extra},
            )
            await session.commit()
        except Exception:  # pragma: no cover - last-resort persistence failure
            logger.exception("could not record failure for run %s", run.id)
            with __import__("contextlib").suppress(Exception):
                await session.rollback()
                persisted = await session.scalar(
                    select(AgentRun).where(AgentRun.id == run.id)
                )
                if persisted is not None:
                    persisted.status = "failed"
                    persisted.error = message[:2000]
                    persisted.finished_at = datetime.now(UTC)
                    await session.commit()

    async def _finish_cancelled(self, session: AsyncSession, run: AgentRun) -> None:
        run.status = "cancelled"
        run.finished_at = datetime.now(UTC)
        await self._emit(session, run, "run_cancelled", {"reason": "user_request"})
        await session.commit()

    # ------------------------------------------------------------------ openclaw
    async def _execute_openclaw(self, session: AsyncSession, run: AgentRun) -> None:
        from app.db.tables import RuntimeConnection

        connection = await session.scalar(
            select(RuntimeConnection)
            .where(
                RuntimeConnection.user_id == run.user_id,
                RuntimeConnection.runtime == "openclaw",
                RuntimeConnection.status == "connected",
            )
            .order_by(RuntimeConnection.updated_at.desc())
        )
        if connection is None:
            raise APIError(
                409,
                "openclaw_not_connected",
                "Connect an OpenClaw gateway (Settings → Runtime) before selecting this runtime.",
            )
        token = (
            self.gateway.cipher.decrypt(connection.encrypted_token)
            if connection.encrypted_token
            else None
        )
        run.status = "running"
        run.started_at = run.started_at or datetime.now(UTC)
        await self._emit(
            session,
            run,
            "run_started",
            {
                "objective": run.objective,
                "runtime": "openclaw",
                "gateway": connection.gateway_url,
            },
        )
        await session.commit()
        session_key = f"uua-{run.conversation_id or run.id}"
        try:
            async for event in self.openclaw.run_agent(
                url=connection.gateway_url,
                token=token,
                device_id=connection.device_id,
                message=run.objective,
                session_key=session_key,
            ):
                if await self._cancelled(run.id):
                    await self._finish_cancelled(session, run)
                    return
                if event.get("type") == "delta":
                    await self._emit(session, run, "delta", {"text": event.get("text", "")})
                elif event.get("type") == "tool":
                    await self._emit(
                        session,
                        run,
                        "tool_call",
                        {"tool": event.get("name"), "status": event.get("status")},
                    )
                elif event.get("type") == "error":
                    raise APIError(
                        502, "openclaw_error", str(event.get("message") or "Gateway error")
                    )
                elif event.get("type") == "done":
                    run.result = str(event.get("summary") or "")[:20000]
                await session.commit()
        except OpenClawError as exc:
            raise APIError(exc.status_code, exc.code, exc.message, details=exc.details) from exc
        run.status = "succeeded"
        run.result = run.result or "The gateway completed the run without a text result."
        run.finished_at = datetime.now(UTC)
        await self._emit(session, run, "run_completed", {"status": "succeeded"})
        await session.commit()


def run_plan(run: AgentRun) -> list[RunPlanStep]:
    steps: list[RunPlanStep] = []
    for item in run.plan or []:
        if not isinstance(item, dict):
            continue
        steps.append(
            RunPlanStep(
                id=int(item.get("id") or len(steps) + 1),
                title=str(item.get("title") or ""),
                detail=str(item.get("detail") or ""),
                tool=item.get("tool"),
            )
        )
    return steps


def _safe_arguments(arguments: dict[str, Any]) -> dict[str, Any]:
    """Keep approval/event payloads small and free of obviously sensitive values."""
    from app.core.redaction import redact

    cleaned: dict[str, Any] = {}
    for key, value in list(arguments.items())[:20]:
        if isinstance(value, str):
            cleaned[key] = redact(value[:1200])
        elif isinstance(value, (int, float, bool)) or value is None:
            cleaned[key] = value
        else:
            cleaned[key] = str(value)[:400]
    return cleaned
