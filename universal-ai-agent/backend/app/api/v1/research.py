from __future__ import annotations

import uuid
from typing import Any

from fastapi import APIRouter
from pydantic import BaseModel, Field

from app.api.serializers import document_out
from app.core.errors import APIError
from app.deps import Context
from app.services.model_gateway.gateway import load_model_and_provider
from app.services.model_gateway.router import select_model
from app.services.prompts import system_prompt
from app.services.providers.base import ChatMessage
from app.services.research import ResearchService

router = APIRouter(tags=["research"])


class ResearchRequest(BaseModel):
    query: str = Field(min_length=2, max_length=500)
    model_id: uuid.UUID | None = None
    max_sources: int = Field(default=6, ge=1, le=12)
    save_as_document: bool = False
    project_id: uuid.UUID | None = None


@router.post("/research")
async def run_research(payload: ResearchRequest, ctx: Context) -> dict[str, Any]:
    research: ResearchService = ctx.container.research
    bundle = await research.gather(payload.query, limit=payload.max_sources)

    report = ""
    synthesis = "extractive"
    model_label: str | None = None
    citations: list[dict] = []
    synthesis_error: str | None = None

    if bundle.verified_sources:
        try:
            options = await ctx.container.providers.model_options(ctx.db, ctx.user_id)
            decision = select_model(
                options, mode="research", preferred_model_pk=payload.model_id
            )
            assert decision.option is not None
            model_row, provider = await load_model_and_provider(
                ctx.db, ctx.user_id, decision.option.model_pk
            )
            messages = [
                ChatMessage(role="system", content=system_prompt("research")),
                ChatMessage(
                    role="user",
                    content=(
                        f"Question: {payload.query}\n\n"
                        f"Fetched sources:\n{research.context_block(bundle)}\n\n"
                        "Write a research report with inline [n] citations and a final "
                        "Sources section. Only use the sources above."
                    ),
                ),
            ]
            text, _usage = await ctx.container.gateway.complete(
                ctx.db,
                model_row=model_row,
                provider=provider,
                messages=messages,
                max_tokens=2000,
                temperature=0.2,
            )
            if text.strip():
                report = text.strip()
                synthesis = "model"
                model_label = model_row.display_name
                citations = research.verify_citations(report, bundle)
        except APIError as exc:
            synthesis_error = exc.message

    if not report:
        report = research.extractive_report(bundle)

    document = None
    if payload.save_as_document and report:
        document = await ctx.container.knowledge.create_document(
            ctx.db,
            ctx.user_id,
            {
                "title": f"Research: {payload.query[:180]}",
                "content_md": report,
                "kind": "report",
                "tags": ["research"],
                "project_id": str(payload.project_id) if payload.project_id else None,
            },
        )

    return {
        "query": bundle.query,
        # `searched` is true only because a search request actually ran.
        "searched": bundle.searched,
        "providers": bundle.providers,
        "sources": [source.citation() | {"excerpt": source.excerpt[:600]} for source in bundle.sources],
        "citations": citations,
        "report_md": report,
        "synthesis": synthesis,
        "model": model_label,
        "synthesis_error": synthesis_error,
        "errors": bundle.errors,
        "duration_ms": bundle.duration_ms,
        "document": document_out(document) if document else None,
    }
