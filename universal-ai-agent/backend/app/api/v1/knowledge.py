from __future__ import annotations

import uuid
from typing import Any

from fastapi import APIRouter, Response
from pydantic import BaseModel, Field

from app.api.serializers import document_out
from app.deps import Context

router = APIRouter(tags=["knowledge"])


class DocumentCreate(BaseModel):
    title: str = Field(min_length=1, max_length=240)
    content_md: str = Field(default="", max_length=500_000)
    kind: str = "note"
    tags: list[str] = Field(default_factory=list, max_length=20)
    project_id: uuid.UUID | None = None
    source_url: str | None = None


class DocumentUpdate(BaseModel):
    title: str | None = Field(default=None, max_length=240)
    content_md: str | None = Field(default=None, max_length=500_000)
    kind: str | None = None
    tags: list[str] | None = None
    project_id: uuid.UUID | None = None


class SummariseRequest(BaseModel):
    model_id: uuid.UUID | None = None


@router.get("/documents")
async def list_documents(
    ctx: Context,
    query: str | None = None,
    project_id: uuid.UUID | None = None,
    limit: int = 100,
) -> dict:
    rows = await ctx.container.knowledge.list_documents(
        ctx.db, ctx.user_id, query=query, project_id=project_id, limit=limit
    )
    return {
        "documents": [
            {
                "id": str(row.id),
                "title": row.title,
                "kind": row.kind,
                "tags": row.tags or [],
                "project_id": str(row.project_id) if row.project_id else None,
                "updated_at": row.updated_at.isoformat() if row.updated_at else None,
                "excerpt": row.content_md[:200],
            }
            for row in rows
        ]
    }


@router.post("/documents", status_code=201)
async def create_document(payload: DocumentCreate, ctx: Context) -> dict:
    document = await ctx.container.knowledge.create_document(
        ctx.db, ctx.user_id, payload.model_dump(exclude_none=True)
    )
    return document_out(document)


@router.get("/documents/{document_id}")
async def get_document(document_id: uuid.UUID, ctx: Context) -> dict:
    document = await ctx.container.knowledge.get_document(ctx.db, ctx.user_id, document_id)
    return document_out(document)


@router.patch("/documents/{document_id}")
async def update_document(
    document_id: uuid.UUID, payload: DocumentUpdate, ctx: Context
) -> dict:
    document = await ctx.container.knowledge.update_document(
        ctx.db, ctx.user_id, document_id, payload.model_dump(exclude_none=True)
    )
    return document_out(document)


@router.delete("/documents/{document_id}", status_code=204)
async def delete_document(document_id: uuid.UUID, ctx: Context) -> None:
    await ctx.container.knowledge.delete_document(ctx.db, ctx.user_id, document_id)


@router.post("/documents/{document_id}/summarise")
async def summarise_document(
    document_id: uuid.UUID, payload: SummariseRequest, ctx: Context
) -> dict:
    result: dict[str, Any] = await ctx.container.knowledge.summarise(
        ctx.db,
        ctx.user_id,
        document_id,
        model_pk=payload.model_id,
        providers=ctx.container.providers,
    )
    return result


@router.get("/documents/{document_id}/export")
async def export_document(document_id: uuid.UUID, ctx: Context) -> Response:
    markdown = await ctx.container.knowledge.export_markdown(ctx.db, ctx.user_id, document_id)
    return Response(
        content=markdown,
        media_type="text/markdown; charset=utf-8",
        headers={"Content-Disposition": 'attachment; filename="document.md"'},
    )
