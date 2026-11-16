"""Audit trail and usage reporting."""

from __future__ import annotations

import uuid
from typing import Any

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.redaction import scrub_payload
from app.db.tables import AuditLog, UsageLog


async def record_audit(
    session: AsyncSession,
    *,
    user_id: uuid.UUID | None,
    action: str,
    target_type: str | None = None,
    target_id: str | None = None,
    success: bool = True,
    ip: str | None = None,
    user_agent: str | None = None,
    detail: dict[str, Any] | None = None,
) -> None:
    session.add(
        AuditLog(
            user_id=user_id,
            action=action[:80],
            target_type=target_type,
            target_id=target_id,
            success=success,
            ip=ip,
            user_agent=(user_agent or "")[:400] or None,
            detail=scrub_payload(detail or {}),
        )
    )
    await session.flush()


async def usage_summary(session: AsyncSession, user_id: uuid.UUID) -> dict[str, Any]:
    totals = await session.execute(
        select(
            func.coalesce(func.sum(UsageLog.prompt_tokens), 0),
            func.coalesce(func.sum(UsageLog.completion_tokens), 0),
            func.coalesce(func.sum(UsageLog.total_tokens), 0),
            func.count(UsageLog.id),
            func.coalesce(func.avg(UsageLog.latency_ms), 0),
        ).where(UsageLog.user_id == user_id)
    )
    prompt, completion, total, count, latency = totals.one()
    by_feature = await session.execute(
        select(
            UsageLog.feature,
            func.coalesce(func.sum(UsageLog.total_tokens), 0),
            func.count(UsageLog.id),
        )
        .where(UsageLog.user_id == user_id)
        .group_by(UsageLog.feature)
    )
    return {
        "prompt_tokens": int(prompt),
        "completion_tokens": int(completion),
        "total_tokens": int(total),
        "requests": int(count),
        "average_latency_ms": int(latency or 0),
        "by_feature": [
            {"feature": feature, "tokens": int(tokens), "requests": int(requests)}
            for feature, tokens, requests in by_feature.all()
        ],
    }


async def list_audit_logs(
    session: AsyncSession, user_id: uuid.UUID, *, limit: int = 100
) -> list[AuditLog]:
    return list(
        (
            await session.scalars(
                select(AuditLog)
                .where(AuditLog.user_id == user_id)
                .order_by(AuditLog.created_at.desc())
                .limit(min(limit, 200))
            )
        ).all()
    )
