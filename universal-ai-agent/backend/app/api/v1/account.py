from __future__ import annotations

import uuid
from typing import Any

from fastapi import APIRouter
from pydantic import BaseModel, Field
from sqlalchemy import select

from app.api.serializers import memory_out
from app.core.errors import not_found
from app.db.tables import Memory, Profile
from app.deps import Context
from app.services.audit import list_audit_logs, record_audit, usage_summary

router = APIRouter(tags=["account"])


class ProfileUpdate(BaseModel):
    display_name: str | None = Field(default=None, max_length=120)
    default_model_id: uuid.UUID | None = None
    settings: dict[str, Any] | None = None


class MemoryCreate(BaseModel):
    content: str = Field(min_length=1, max_length=4000)
    scope: str = "global"
    scope_id: uuid.UUID | None = None
    kind: str = "fact"


@router.get("/me")
async def me(ctx: Context) -> dict:
    profile = await ctx.db.scalar(select(Profile).where(Profile.id == ctx.user_id))
    if profile is None:
        raise not_found("Profile")
    return {
        "id": str(profile.id),
        "email": profile.email,
        "display_name": profile.display_name,
        "avatar_url": profile.avatar_url,
        "default_model_id": str(profile.default_model_pk) if profile.default_model_pk else None,
        "settings": profile.settings or {},
        "created_at": profile.created_at.isoformat() if profile.created_at else None,
    }


@router.patch("/me")
async def update_me(payload: ProfileUpdate, ctx: Context) -> dict:
    profile = await ctx.db.scalar(select(Profile).where(Profile.id == ctx.user_id))
    if profile is None:
        raise not_found("Profile")
    if payload.display_name is not None:
        profile.display_name = payload.display_name
    if payload.default_model_id is not None:
        profile.default_model_pk = payload.default_model_id
    if payload.settings is not None:
        profile.settings = payload.settings
    await record_audit(
        ctx.db, user_id=ctx.user_id, action="profile.update", target_type="profile"
    )
    return {"ok": True}


@router.get("/usage")
async def usage(ctx: Context) -> dict:
    return await usage_summary(ctx.db, ctx.user_id)


@router.get("/audit-logs")
async def audit_logs(ctx: Context, limit: int = 100) -> dict:
    rows = await list_audit_logs(ctx.db, ctx.user_id, limit=limit)
    return {
        "logs": [
            {
                "id": str(row.id),
                "action": row.action,
                "target_type": row.target_type,
                "target_id": row.target_id,
                "success": row.success,
                "detail": row.detail or {},
                "created_at": row.created_at.isoformat() if row.created_at else None,
            }
            for row in rows
        ]
    }


@router.get("/memories")
async def list_memories(ctx: Context, query: str = "", limit: int = 50) -> dict:
    statement = select(Memory).where(Memory.user_id == ctx.user_id)
    if query:
        statement = statement.where(Memory.content.ilike(f"%{query}%"))
    rows = (
        await ctx.db.scalars(
            statement.order_by(Memory.created_at.desc()).limit(min(limit, 200))
        )
    ).all()
    return {"memories": [memory_out(row) for row in rows]}


@router.post("/memories", status_code=201)
async def create_memory(payload: MemoryCreate, ctx: Context) -> dict:
    memory = await ctx.container.knowledge.add_memory(
        ctx.db,
        ctx.user_id,
        content=payload.content,
        scope=payload.scope,
        scope_id=payload.scope_id,
        kind=payload.kind,
    )
    return memory_out(memory)


@router.delete("/memories/{memory_id}", status_code=204)
async def delete_memory(memory_id: uuid.UUID, ctx: Context) -> None:
    memory = await ctx.db.scalar(
        select(Memory).where(Memory.id == memory_id, Memory.user_id == ctx.user_id)
    )
    if memory is None:
        raise not_found("Memory")
    await ctx.db.delete(memory)
