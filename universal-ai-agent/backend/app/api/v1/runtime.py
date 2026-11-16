from __future__ import annotations

import uuid
from datetime import UTC, datetime

from fastapi import APIRouter
from pydantic import BaseModel, Field
from sqlalchemy import select

from app.api.serializers import connection_out, run_out
from app.core.errors import bad_request, not_found
from app.db.tables import RuntimeConnection
from app.deps import Context
from app.services.audit import record_audit
from app.services.openclaw import OpenClawError

router = APIRouter(tags=["runtime"])


class ConnectionCreate(BaseModel):
    gateway_url: str = Field(min_length=6, max_length=500)
    token: str | None = Field(default=None, max_length=4096)
    device_id: str | None = Field(default=None, max_length=120)
    label: str = Field(default="OpenClaw Gateway", max_length=120)


class OpenClawRun(BaseModel):
    objective: str = Field(min_length=1, max_length=20_000)
    conversation_id: uuid.UUID | None = None
    agent_id: uuid.UUID | None = None


@router.get("/runtime/connections")
async def list_connections(ctx: Context) -> dict:
    rows = (
        await ctx.db.scalars(
            select(RuntimeConnection)
            .where(RuntimeConnection.user_id == ctx.user_id)
            .order_by(RuntimeConnection.created_at.desc())
        )
    ).all()
    return {
        "connections": [
            connection_out(row, has_token=bool(row.encrypted_token)) for row in rows
        ]
    }


@router.post("/runtime/connections", status_code=201)
async def create_connection(payload: ConnectionCreate, ctx: Context) -> dict:
    device_id = payload.device_id or f"uua-{uuid.uuid4().hex[:12]}"
    probe = await ctx.container.openclaw.probe(payload.gateway_url, payload.token, device_id)
    connection = RuntimeConnection(
        user_id=ctx.user_id,
        runtime="openclaw",
        label=payload.label[:120],
        gateway_url=payload.gateway_url,
        device_id=device_id,
        encrypted_token=ctx.container.cipher.encrypt(payload.token) if payload.token else None,
        token_fingerprint=ctx.container.cipher.fingerprint(payload.token)
        if payload.token
        else None,
        status="connected" if probe.ok else ("pairing_required" if probe.pairing_request_id else "error"),
        protocol_version=probe.protocol_version,
        last_checked_at=datetime.now(UTC),
        last_error=None if probe.ok else probe.message,
        meta={"scopes": probe.scopes, "methods": probe.methods[:50]},
    )
    ctx.db.add(connection)
    await ctx.db.flush()
    await record_audit(
        ctx.db,
        user_id=ctx.user_id,
        action="openclaw.connect",
        target_id=str(connection.id),
        success=probe.ok,
        detail={"status": connection.status},
    )
    return {
        "connection": connection_out(connection, has_token=bool(connection.encrypted_token)),
        "probe": {
            "ok": probe.ok,
            "message": probe.message,
            "protocol_version": probe.protocol_version,
            "scopes": probe.scopes,
            "latency_ms": probe.latency_ms,
            "pairing_request_id": probe.pairing_request_id,
        },
    }


@router.post("/runtime/connections/{connection_id}/test")
async def test_connection(connection_id: uuid.UUID, ctx: Context) -> dict:
    connection = await ctx.db.scalar(
        select(RuntimeConnection).where(
            RuntimeConnection.id == connection_id, RuntimeConnection.user_id == ctx.user_id
        )
    )
    if connection is None:
        raise not_found("Connection")
    token = (
        ctx.container.cipher.decrypt(connection.encrypted_token)
        if connection.encrypted_token
        else None
    )
    probe = await ctx.container.openclaw.probe(
        connection.gateway_url, token, connection.device_id
    )
    connection.status = (
        "connected" if probe.ok else ("pairing_required" if probe.pairing_request_id else "error")
    )
    connection.last_checked_at = datetime.now(UTC)
    connection.last_error = None if probe.ok else probe.message
    connection.protocol_version = probe.protocol_version or connection.protocol_version
    connection.meta = {**(connection.meta or {}), "scopes": probe.scopes}
    await ctx.db.flush()
    return {
        "ok": probe.ok,
        "status": connection.status,
        "message": probe.message,
        "protocol_version": probe.protocol_version,
        "scopes": probe.scopes,
        "latency_ms": probe.latency_ms,
        "pairing_request_id": probe.pairing_request_id,
    }


@router.delete("/runtime/connections/{connection_id}", status_code=204)
async def delete_connection(connection_id: uuid.UUID, ctx: Context) -> None:
    connection = await ctx.db.scalar(
        select(RuntimeConnection).where(
            RuntimeConnection.id == connection_id, RuntimeConnection.user_id == ctx.user_id
        )
    )
    if connection is None:
        raise not_found("Connection")
    await ctx.db.delete(connection)
    await record_audit(
        ctx.db, user_id=ctx.user_id, action="openclaw.disconnect", target_id=str(connection_id)
    )


@router.post("/runtime/openclaw/runs", status_code=201)
async def run_on_openclaw(payload: OpenClawRun, ctx: Context) -> dict:
    connection = await ctx.db.scalar(
        select(RuntimeConnection)
        .where(
            RuntimeConnection.user_id == ctx.user_id,
            RuntimeConnection.runtime == "openclaw",
            RuntimeConnection.status == "connected",
        )
        .order_by(RuntimeConnection.updated_at.desc())
    )
    if connection is None:
        raise bad_request("Connect and verify an OpenClaw gateway before using this runtime.")
    run = await ctx.container.orchestrator.create_run(
        ctx.db,
        ctx.user_id,
        objective=payload.objective,
        agent_id=payload.agent_id,
        conversation_id=payload.conversation_id,
        runtime="openclaw",
    )
    await ctx.db.commit()
    ctx.container.orchestrator.start(run.id)
    return run_out(run)


@router.get("/runtime/status")
async def runtime_status(ctx: Context) -> dict:
    connections = (
        await ctx.db.scalars(
            select(RuntimeConnection).where(RuntimeConnection.user_id == ctx.user_id)
        )
    ).all()
    connected = [row for row in connections if row.status == "connected"]
    return {
        "native": {"available": True, "runs_active": len(ctx.container.orchestrator._tasks)},
        "openclaw": {
            "available": bool(connected),
            "connections": len(connections),
            "connected": len(connected),
            "last_error": next((row.last_error for row in connections if row.last_error), None),
        },
    }
