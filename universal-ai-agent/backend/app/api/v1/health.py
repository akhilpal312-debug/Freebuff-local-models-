from __future__ import annotations

from datetime import UTC, datetime

from fastapi import APIRouter, Request
from sqlalchemy import text

router = APIRouter(tags=["health"])


@router.get("/health")
async def health() -> dict:
    return {
        "status": "ok",
        "service": "universal-ai-agent",
        "version": "0.1.0",
        "time": datetime.now(UTC).isoformat(),
    }


@router.get("/health/ready")
async def ready(request: Request) -> dict:
    database = request.app.state.database
    async with database.session() as session:
        await session.execute(text("SELECT 1"))
    settings = request.app.state.settings
    return {
        "status": "ready",
        "database": "ok",
        "auth": "single_user",
        "user_id": str(settings.single_user_id),
        "credential_encryption": bool(settings.credential_encryption_key),
        "web_search": "tavily" if settings.tavily_api_key else "duckduckgo+wikipedia",
    }
