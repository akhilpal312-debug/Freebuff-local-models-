from __future__ import annotations

import asyncio
import json
import uuid
from typing import Any

from fastapi import APIRouter, Query
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field
from sqlalchemy import select

from app.api.serializers import agent_out, approval_out, run_event_out, run_out
from app.core.errors import bad_request, not_found
from app.db.tables import Agent, AgentRun, Approval
from app.deps import Context
from app.services.agent.seed import TEMPLATE_OWNER, TEMPLATES
from app.services.agent.tools import PROFILE_TOOLS, TOOL_SPECS
from app.services.audit import record_audit

router = APIRouter(tags=["agents"])

TERMINAL_STATUSES = {"succeeded", "failed", "cancelled"}


class AgentCreate(BaseModel):
    name: str = Field(min_length=1, max_length=120)
    kind: str = "general"
    description: str | None = Field(default=None, max_length=500)
    instructions: str = Field(default="", max_length=20_000)
    model_id: uuid.UUID | None = None
    runtime: str = Field(default="native", pattern="^(native|openclaw)$")
    permission_profile: str = Field(default="read_only")
    memory_scope: str = Field(default="agent", pattern="^(global|project|agent)$")
    enabled_tools: list[str] = Field(default_factory=list)
    project_id: uuid.UUID | None = None
    template_name: str | None = None


class AgentUpdate(BaseModel):
    name: str | None = Field(default=None, max_length=120)
    description: str | None = None
    instructions: str | None = Field(default=None, max_length=20_000)
    model_id: uuid.UUID | None = None
    runtime: str | None = Field(default=None, pattern="^(native|openclaw)$")
    permission_profile: str | None = None
    memory_scope: str | None = Field(default=None, pattern="^(global|project|agent)$")
    enabled_tools: list[str] | None = None
    project_id: uuid.UUID | None = None


class RunCreate(BaseModel):
    objective: str = Field(min_length=1, max_length=20_000)
    agent_id: uuid.UUID | None = None
    project_id: uuid.UUID | None = None
    conversation_id: uuid.UUID | None = None
    runtime: str | None = Field(default=None, pattern="^(native|openclaw)$")
    model_id: uuid.UUID | None = None
    permission_profile: str | None = None
    enabled_tools: list[str] | None = None


class ApprovalDecision(BaseModel):
    approved: bool
    reason: str | None = Field(default=None, max_length=500)


def _validate_profile(profile: str) -> None:
    if profile not in PROFILE_TOOLS:
        raise bad_request(f"permission_profile must be one of {sorted(PROFILE_TOOLS)}.")


@router.get("/agents")
async def list_agents(ctx: Context) -> dict:
    rows = (
        await ctx.db.scalars(
            select(Agent)
            .where(Agent.user_id == ctx.user_id)
            .order_by(Agent.created_at.desc())
        )
    ).all()
    return {"agents": [agent_out(row) for row in rows]}


@router.get("/agents/templates")
async def list_templates(ctx: Context) -> dict:
    rows = (
        await ctx.db.scalars(
            select(Agent).where(Agent.user_id == TEMPLATE_OWNER, Agent.is_template.is_(True))
        )
    ).all()
    if not rows:
        # The app was started without seeding (AUTO_CREATE_TABLES=false and no migration run).
        return {"templates": [dict(item, id=None, is_template=True) for item in TEMPLATES]}
    return {"templates": [agent_out(row) for row in rows]}


@router.post("/agents", status_code=201)
async def create_agent(payload: AgentCreate, ctx: Context) -> dict:
    _validate_profile(payload.permission_profile)
    data: dict[str, Any] = payload.model_dump(exclude={"template_name"})
    if payload.template_name:
        template = await ctx.db.scalar(
            select(Agent).where(
                Agent.user_id == TEMPLATE_OWNER,
                Agent.is_template.is_(True),
                Agent.name == payload.template_name,
            )
        )
        if template is not None:
            data.update(
                {
                    "kind": template.kind,
                    "instructions": payload.instructions or template.instructions,
                    "permission_profile": payload.permission_profile
                    if payload.permission_profile != "read_only"
                    else template.permission_profile,
                    "enabled_tools": payload.enabled_tools or template.enabled_tools,
                    "memory_scope": template.memory_scope,
                }
            )
    agent = Agent(
        user_id=ctx.user_id,
        kind=data.get("kind") or "general",
        name=data["name"][:120],
        description=data.get("description"),
        instructions=data.get("instructions") or "",
        model_pk=data.get("model_id"),
        runtime=data.get("runtime") or "native",
        permission_profile=data.get("permission_profile") or "read_only",
        memory_scope=data.get("memory_scope") or "agent",
        enabled_tools=data.get("enabled_tools") or [],
        project_id=data.get("project_id"),
    )
    ctx.db.add(agent)
    await ctx.db.flush()
    await record_audit(ctx.db, user_id=ctx.user_id, action="agent.create", target_id=str(agent.id))
    return agent_out(agent)


@router.patch("/agents/{agent_id}")
async def update_agent(agent_id: uuid.UUID, payload: AgentUpdate, ctx: Context) -> dict:
    agent = await ctx.db.scalar(
        select(Agent).where(Agent.id == agent_id, Agent.user_id == ctx.user_id)
    )
    if agent is None:
        raise not_found("Agent")
    if payload.permission_profile is not None:
        _validate_profile(payload.permission_profile)
        agent.permission_profile = payload.permission_profile
    for field in ("name", "description", "instructions", "runtime", "memory_scope"):
        value = getattr(payload, field)
        if value is not None:
            setattr(agent, field, value)
    if payload.model_id is not None:
        agent.model_pk = payload.model_id
    if payload.project_id is not None:
        agent.project_id = payload.project_id
    if payload.enabled_tools is not None:
        unknown = [tool for tool in payload.enabled_tools if tool not in TOOL_SPECS]
        if unknown:
            raise bad_request(f"Unknown tools: {', '.join(unknown)}")
        agent.enabled_tools = payload.enabled_tools
    await ctx.db.flush()
    return agent_out(agent)


@router.delete("/agents/{agent_id}", status_code=204)
async def delete_agent(agent_id: uuid.UUID, ctx: Context) -> None:
    agent = await ctx.db.scalar(
        select(Agent).where(Agent.id == agent_id, Agent.user_id == ctx.user_id)
    )
    if agent is None:
        raise not_found("Agent")
    await ctx.db.delete(agent)


@router.get("/tools")
async def list_tools() -> dict:
    return {
        "tools": [
            {
                "name": spec.name,
                "description": spec.description,
                "parameters": spec.parameters,
                "requires_approval": spec.name in {"shell.run", "tests.run", "git.commit"},
            }
            for spec in TOOL_SPECS.values()
        ],
        "profiles": {name: sorted(tools) for name, tools in PROFILE_TOOLS.items()},
    }


@router.post("/runs", status_code=201)
async def create_run(payload: RunCreate, ctx: Context) -> dict:
    run = await ctx.container.orchestrator.create_run(
        ctx.db,
        ctx.user_id,
        objective=payload.objective,
        agent_id=payload.agent_id,
        project_id=payload.project_id,
        conversation_id=payload.conversation_id,
        runtime=payload.runtime,
        model_pk=payload.model_id,
        permission_profile=payload.permission_profile,
        enabled_tools=payload.enabled_tools,
    )
    await record_audit(
        ctx.db,
        user_id=ctx.user_id,
        action="run.create",
        target_type="run",
        target_id=str(run.id),
        detail={"runtime": run.runtime, "project_id": str(run.project_id or "")},
    )
    await ctx.db.commit()
    ctx.container.orchestrator.start(run.id)
    return run_out(run)


@router.get("/runs")
async def list_runs(ctx: Context, limit: int = 30) -> dict:
    rows = (
        await ctx.db.scalars(
            select(AgentRun)
            .where(AgentRun.user_id == ctx.user_id)
            .order_by(AgentRun.created_at.desc())
            .limit(min(limit, 100))
        )
    ).all()
    return {"runs": [run_out(row) for row in rows]}


@router.get("/runs/{run_id}")
async def get_run(run_id: uuid.UUID, ctx: Context) -> dict:
    run = await ctx.db.scalar(
        select(AgentRun).where(AgentRun.id == run_id, AgentRun.user_id == ctx.user_id)
    )
    if run is None:
        raise not_found("Run")
    approvals = (
        await ctx.db.scalars(
            select(Approval)
            .where(Approval.run_id == run.id)
            .order_by(Approval.requested_at.desc())
        )
    ).all()
    return {"run": run_out(run), "approvals": [approval_out(row) for row in approvals]}


@router.post("/runs/{run_id}/cancel")
async def cancel_run(run_id: uuid.UUID, ctx: Context) -> dict:
    run = await ctx.container.orchestrator.cancel(ctx.db, ctx.user_id, run_id)
    await record_audit(ctx.db, user_id=ctx.user_id, action="run.cancel", target_id=str(run_id))
    # Commit before responding: the orchestrator may finalise the run immediately
    # after the in-memory cancel event fires, and a later commit from this request's
    # session would otherwise overwrite "cancelled" with the stale "cancelling" state.
    await ctx.db.commit()
    return run_out(run)


@router.get("/runs/{run_id}/events")
async def stream_events(
    run_id: uuid.UUID,
    ctx: Context,
    after: int = Query(default=0, ge=0),
):
    run = await ctx.db.scalar(
        select(AgentRun).where(AgentRun.id == run_id, AgentRun.user_id == ctx.user_id)
    )
    if run is None:
        raise not_found("Run")
    container = ctx.container
    database = ctx.database

    async def generator():
        last = after
        while True:
            async with database.session() as fresh:
                rows = await container.orchestrator.events_since(fresh, run_id, last)
                status = await fresh.scalar(
                    select(AgentRun.status).where(AgentRun.id == run_id)
                )
            for row in rows:
                last = row.seq
                payload = json.dumps(run_event_out(row), ensure_ascii=False, default=str)
                yield f"id: {row.seq}\ndata: {payload}\n\n"
            if status in TERMINAL_STATUSES and not rows:
                async with database.session() as fresh:
                    final = await fresh.scalar(
                        select(AgentRun).where(AgentRun.id == run_id)
                    )
                if final is not None:
                    yield (
                        "event: end\n"
                        f"data: {json.dumps(run_out(final), ensure_ascii=False, default=str)}\n\n"
                    )
                return
            await asyncio.sleep(0.4)

    return StreamingResponse(
        generator(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache, no-transform", "X-Accel-Buffering": "no"},
    )


@router.get("/approvals")
async def list_approvals(ctx: Context, status: str = "pending") -> dict:
    statement = select(Approval).where(Approval.user_id == ctx.user_id)
    if status:
        statement = statement.where(Approval.status == status)
    rows = (
        await ctx.db.scalars(statement.order_by(Approval.requested_at.desc()).limit(50))
    ).all()
    return {"approvals": [approval_out(row) for row in rows]}


@router.post("/approvals/{approval_id}")
async def decide_approval(
    approval_id: uuid.UUID, payload: ApprovalDecision, ctx: Context
) -> dict:
    approval = await ctx.container.orchestrator.decide_approval(
        ctx.db,
        ctx.user_id,
        approval_id,
        approved=payload.approved,
        reason=payload.reason,
    )
    await record_audit(
        ctx.db,
        user_id=ctx.user_id,
        action="approval.decide",
        target_id=str(approval_id),
        detail={"approved": payload.approved, "tool": approval.tool_name},
    )
    return approval_out(approval)
