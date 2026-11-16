"""Knowledge workspace: documents, search, AI summaries and export."""

from __future__ import annotations

import uuid
from typing import Any

from sqlalchemy import or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.errors import bad_request, not_found
from app.db.tables import Document, Memory
from app.services.model_gateway.gateway import ModelGateway
from app.services.prompts import SUMMARISER_PROMPT
from app.services.providers.base import ChatMessage


class KnowledgeService:
    def __init__(self, gateway: ModelGateway) -> None:
        self.gateway = gateway

    async def list_documents(
        self,
        session: AsyncSession,
        user_id: uuid.UUID,
        *,
        query: str | None = None,
        project_id: uuid.UUID | None = None,
        limit: int = 100,
    ) -> list[Document]:
        statement = select(Document).where(Document.user_id == user_id)
        if project_id is not None:
            statement = statement.where(Document.project_id == project_id)
        if query:
            pattern = f"%{query.strip()}%"
            statement = statement.where(
                or_(Document.title.ilike(pattern), Document.content_md.ilike(pattern))
            )
        statement = statement.order_by(Document.updated_at.desc()).limit(min(limit, 200))
        return list((await session.scalars(statement)).all())

    async def get_document(
        self, session: AsyncSession, user_id: uuid.UUID, document_id: uuid.UUID
    ) -> Document:
        document = await session.scalar(
            select(Document).where(Document.id == document_id, Document.user_id == user_id)
        )
        if document is None:
            raise not_found("Document")
        return document

    async def create_document(
        self,
        session: AsyncSession,
        user_id: uuid.UUID,
        payload: dict[str, Any],
    ) -> Document:
        title = str(payload.get("title") or "").strip()
        if not title:
            raise bad_request("A document title is required.")
        document = Document(
            user_id=user_id,
            project_id=_uuid_or_none(payload.get("project_id")),
            title=title[:240],
            kind=str(payload.get("kind") or "note")[:20],
            content_md=str(payload.get("content_md") or ""),
            tags=[str(tag) for tag in (payload.get("tags") or [])][:20],
            source_url=payload.get("source_url"),
        )
        session.add(document)
        await session.flush()
        return document

    async def update_document(
        self, session: AsyncSession, user_id: uuid.UUID, document_id: uuid.UUID, payload: dict
    ) -> Document:
        document = await self.get_document(session, user_id, document_id)
        for field in ("title", "content_md", "kind", "source_url"):
            if field in payload and payload[field] is not None:
                setattr(document, field, payload[field])
        if "tags" in payload and isinstance(payload["tags"], list):
            document.tags = [str(tag) for tag in payload["tags"]][:20]
        if "project_id" in payload:
            document.project_id = _uuid_or_none(payload["project_id"])
        await session.flush()
        return document

    async def delete_document(
        self, session: AsyncSession, user_id: uuid.UUID, document_id: uuid.UUID
    ) -> None:
        document = await self.get_document(session, user_id, document_id)
        await session.delete(document)
        await session.flush()

    async def summarise(
        self,
        session: AsyncSession,
        user_id: uuid.UUID,
        document_id: uuid.UUID,
        *,
        model_pk: uuid.UUID | None,
        providers,
    ) -> dict[str, Any]:
        from app.services.model_gateway.gateway import load_model_and_provider
        from app.services.model_gateway.router import select_model

        document = await self.get_document(session, user_id, document_id)
        if not document.content_md.strip():
            raise bad_request("This document is empty, so there is nothing to summarise.")
        options = await providers.model_options(session, user_id)
        decision = select_model(options, mode="chat", preferred_model_pk=model_pk)
        assert decision.option is not None
        model_row, provider = await load_model_and_provider(
            session, user_id, decision.option.model_pk
        )
        summary, usage = await self.gateway.complete(
            session,
            model_row=model_row,
            provider=provider,
            messages=[
                ChatMessage(role="system", content=SUMMARISER_PROMPT),
                ChatMessage(role="user", content=document.content_md[:24000]),
            ],
            max_tokens=800,
            temperature=0.2,
        )
        return {
            "summary_md": summary.strip(),
            "model": model_row.display_name,
            "usage": usage or {},
        }

    async def export_markdown(
        self, session: AsyncSession, user_id: uuid.UUID, document_id: uuid.UUID
    ) -> str:
        document = await self.get_document(session, user_id, document_id)
        return f"# {document.title}\n\n{document.content_md}\n"

    async def add_memory(
        self,
        session: AsyncSession,
        user_id: uuid.UUID,
        *,
        content: str,
        scope: str = "global",
        scope_id: uuid.UUID | None = None,
        kind: str = "fact",
    ) -> Memory:
        if not content.strip():
            raise bad_request("Memory content cannot be empty.")
        memory = Memory(
            user_id=user_id,
            scope=scope,
            scope_id=scope_id,
            kind=kind,
            content=content.strip()[:4000],
        )
        session.add(memory)
        await session.flush()
        return memory

    async def search_memories(
        self,
        session: AsyncSession,
        user_id: uuid.UUID,
        query: str,
        *,
        scope_id: uuid.UUID | None = None,
        limit: int = 20,
    ) -> list[Memory]:
        statement = select(Memory).where(
            Memory.user_id == user_id, Memory.content.ilike(f"%{query.strip()}%")
        )
        if scope_id is not None:
            statement = statement.where(
                or_(Memory.scope_id == scope_id, Memory.scope_id.is_(None))
            )
        statement = statement.order_by(Memory.importance.desc(), Memory.created_at.desc())
        return list((await session.scalars(statement.limit(min(limit, 100)))).all())


def _uuid_or_none(value: Any) -> uuid.UUID | None:
    if not value:
        return None
    try:
        return uuid.UUID(str(value))
    except ValueError:
        return None
