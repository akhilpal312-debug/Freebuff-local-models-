"""Chat orchestration: conversation persistence plus real model streaming."""

from __future__ import annotations

import json
import logging
import time
import uuid
from collections.abc import AsyncIterator
from datetime import UTC, datetime
from typing import Any

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.errors import APIError, bad_request, not_found
from app.db.tables import Conversation, Message, Model, UsageLog
from app.services.model_gateway.gateway import ModelGateway, load_model_and_provider
from app.services.model_gateway.router import select_model
from app.services.prompts import system_prompt
from app.services.provider_service import ProviderService
from app.services.providers.base import ChatMessage

logger = logging.getLogger(__name__)

MODES = {"chat", "deep", "research", "coding", "agent"}
MAX_HISTORY_MESSAGES = 40


def sse(event: dict[str, Any]) -> str:
    """Encode one Server-Sent Event frame."""
    return f"data: {json.dumps(event, ensure_ascii=False, default=str)}\n\n"


async def next_sequence(session: AsyncSession, conversation_id: uuid.UUID) -> int:
    current = await session.scalar(
        select(func.max(Message.sequence)).where(Message.conversation_id == conversation_id)
    )
    return int(current or 0) + 1


class ChatService:
    def __init__(self, gateway: ModelGateway, providers: ProviderService) -> None:
        self.gateway = gateway
        self.providers = providers

    # ---------------------------------------------------------------- conversations
    async def create_conversation(
        self,
        session: AsyncSession,
        user_id: uuid.UUID,
        *,
        title: str | None = None,
        mode: str = "chat",
        model_pk: uuid.UUID | None = None,
        project_id: uuid.UUID | None = None,
    ) -> Conversation:
        if mode not in MODES:
            raise bad_request(f"mode must be one of {sorted(MODES)}.")
        conversation = Conversation(
            user_id=user_id,
            title=(title or "New conversation")[:200],
            mode=mode,
            model_pk=model_pk,
            project_id=project_id,
        )
        session.add(conversation)
        await session.flush()
        return conversation

    async def get_conversation(
        self, session: AsyncSession, user_id: uuid.UUID, conversation_id: uuid.UUID
    ) -> Conversation:
        conversation = await session.scalar(
            select(Conversation).where(
                Conversation.id == conversation_id, Conversation.user_id == user_id
            )
        )
        if conversation is None:
            raise not_found("Conversation")
        return conversation

    async def list_conversations(
        self, session: AsyncSession, user_id: uuid.UUID, *, limit: int = 50
    ) -> list[Conversation]:
        return list(
            (
                await session.scalars(
                    select(Conversation)
                    .where(Conversation.user_id == user_id)
                    .order_by(Conversation.pinned.desc(), Conversation.updated_at.desc())
                    .limit(min(limit, 200))
                )
            ).all()
        )

    async def messages(
        self, session: AsyncSession, conversation_id: uuid.UUID
    ) -> list[Message]:
        return list(
            (
                await session.scalars(
                    select(Message)
                    .where(Message.conversation_id == conversation_id)
                    .order_by(Message.sequence)
                )
            ).all()
        )

    async def history_for_model(
        self, session: AsyncSession, conversation: Conversation, *, mode: str
    ) -> list[ChatMessage]:
        rows = await self.messages(session, conversation.id)
        usable = [
            row
            for row in rows
            if row.status in {"complete", "cancelled"} and row.content.strip()
        ]
        trimmed = usable[-MAX_HISTORY_MESSAGES:]
        history: list[ChatMessage] = []
        for row in trimmed:
            images = [
                str(item.get("url"))
                for item in (row.attachments or [])
                if item.get("type") == "image" and item.get("url")
            ]
            history.append(
                ChatMessage(role=row.role, content=row.content, images=images)  # type: ignore[arg-type]
            )
        return [ChatMessage(role="system", content=system_prompt(mode)), *history]

    async def ensure_routable(
        self,
        session: AsyncSession,
        *,
        user_id: uuid.UUID,
        conversation: Conversation,
        mode: str,
        model_pk: uuid.UUID | None,
        content: str,
        attachments: list[dict[str, Any]] | None = None,
    ) -> None:
        """Validate that a model can serve this turn *before* the SSE stream starts.

        Raising here produces a real 4xx instead of an error frame inside a
        response that already has its headers.
        """
        if mode not in MODES:
            raise bad_request(f"mode must be one of {sorted(MODES)}.")
        requires_vision = any(
            item.get("type") == "image" for item in (attachments or [])
        )
        options = await self.providers.model_options(session, user_id)
        select_model(
            options,
            mode=mode,
            requires_vision=requires_vision,
            min_context=len(content) // 4 + 2048,
            preferred_model_pk=model_pk or conversation.model_pk,
        )

    # --------------------------------------------------------------------- streaming
    async def stream_reply(
        self,
        session: AsyncSession,
        *,
        user_id: uuid.UUID,
        conversation: Conversation,
        content: str,
        mode: str,
        model_pk: uuid.UUID | None,
        reasoning_effort: str | None = None,
        temperature: float = 0.7,
        attachments: list[dict[str, Any]] | None = None,
        regenerate_message_id: uuid.UUID | None = None,
        persist_user_message: bool = True,
    ) -> AsyncIterator[str]:
        """Yield SSE frames. Persists the assistant message even on failure."""
        started = time.perf_counter()
        attachments = attachments or []
        mode = mode or conversation.mode or "chat"
        if mode not in MODES:
            raise bad_request(f"mode must be one of {sorted(MODES)}.")

        if regenerate_message_id is not None:
            target = await session.scalar(
                select(Message).where(
                    Message.id == regenerate_message_id,
                    Message.user_id == user_id,
                    Message.conversation_id == conversation.id,
                )
            )
            if target is None:
                raise not_found("Message")
            await session.delete(target)
            await session.flush()
        elif content.strip() or attachments:
            sequence = await next_sequence(session, conversation.id)
            user_message = Message(
                conversation_id=conversation.id,
                user_id=user_id,
                role="user",
                content=content,
                sequence=sequence,
                attachments=attachments,
                status="complete",
            )
            session.add(user_message)
            if conversation.title in {"New conversation", ""} and content.strip():
                conversation.title = content.strip().splitlines()[0][:120]
            conversation.updated_at = datetime.now(UTC)
            await session.flush()
        elif not persist_user_message:
            # The caller already stored the edited user message.
            conversation.updated_at = datetime.now(UTC)
            await session.flush()
        else:
            raise bad_request("A message needs text or an attachment.")

        requires_vision = any(item.get("type") == "image" for item in attachments)
        options = await self.providers.model_options(session, user_id)
        decision = select_model(
            options,
            mode=mode,
            requires_vision=requires_vision,
            min_context=len(content) // 4 + 2048,
            preferred_model_pk=model_pk or conversation.model_pk,
        )
        option = decision.option
        assert option is not None
        model_row, provider = await load_model_and_provider(session, user_id, option.model_pk)

        sequence = await next_sequence(session, conversation.id)
        assistant = Message(
            conversation_id=conversation.id,
            user_id=user_id,
            role="assistant",
            content="",
            sequence=sequence,
            model_pk=model_row.id,
            status="streaming",
        )
        session.add(assistant)
        await session.flush()

        yield sse(
            {
                "type": "meta",
                "conversation_id": str(conversation.id),
                "message_id": str(assistant.id),
                "mode": mode,
                "model": {
                    "id": str(model_row.id),
                    "model_id": model_row.model_id,
                    "display_name": model_row.display_name,
                },
                "provider": {"slug": provider.slug, "name": provider.name},
                "routing_reason": decision.reason,
                "reasoning_effort": reasoning_effort if model_row.supports_reasoning else None,
            }
        )

        history = await self.history_for_model(session, conversation, mode=mode)
        # Drop the empty placeholder if it slipped into history.
        history = [message for message in history if message.content.strip() or message.images]
        usage: dict[str, Any] = {}
        chunks: list[str] = []
        failure: str | None = None
        cancelled = False
        try:
            async for event in self.gateway.stream(
                session,
                model_row=model_row,
                provider=provider,
                messages=history,
                temperature=temperature,
                max_tokens=model_row.max_output_tokens,
                reasoning_effort=reasoning_effort if model_row.supports_reasoning else None,
            ):
                if event.kind == "text":
                    chunks.append(event.text)
                    yield sse({"type": "delta", "text": event.text})
                elif event.kind == "reasoning":
                    # Signal only that reasoning occurred; never stream the trace.
                    yield sse({"type": "reasoning_progress"})
                elif event.kind == "usage" and event.usage:
                    usage = event.usage
                elif event.kind == "done":
                    usage.setdefault("finish_reason", event.finish_reason)
                elif event.kind == "error":
                    failure = event.error or "Provider error"
                    break
        except APIError as exc:
            failure = exc.message
        except GeneratorExit:
            cancelled = True
            raise
        finally:
            text = "".join(chunks)
            assistant.content = text
            assistant.status = (
                "cancelled" if cancelled else ("failed" if failure else "complete")
            )
            assistant.error = failure
            assistant.prompt_tokens = int(usage.get("prompt_tokens") or 0)
            assistant.completion_tokens = int(usage.get("completion_tokens") or 0)
            assistant.model_pk = model_row.id
            conversation.updated_at = datetime.now(UTC)
            conversation.model_pk = model_row.id
            if usage:
                total = assistant.prompt_tokens + assistant.completion_tokens
                session.add(
                    UsageLog(
                        user_id=user_id,
                        provider_id=provider.id,
                        model_pk=model_row.id,
                        conversation_id=conversation.id,
                        feature=mode,
                        prompt_tokens=assistant.prompt_tokens,
                        completion_tokens=assistant.completion_tokens,
                        total_tokens=total,
                        latency_ms=int((time.perf_counter() - started) * 1000),
                    )
                )
            try:
                await session.commit()
            except Exception:  # pragma: no cover - persistence failure on disconnect
                logger.exception("failed to persist assistant message")

        if failure:
            yield sse({"type": "error", "message": failure, "retryable": True})
        elif cancelled:
            yield sse({"type": "cancelled", "message_id": str(assistant.id)})
        else:
            yield sse(
                {
                    "type": "done",
                    "message_id": str(assistant.id),
                    "usage": {
                        "prompt_tokens": assistant.prompt_tokens,
                        "completion_tokens": assistant.completion_tokens,
                        "estimated": bool(usage.get("estimated")),
                    },
                    "latency_ms": int((time.perf_counter() - started) * 1000),
                }
            )

    # ------------------------------------------------------------------ editing
    async def truncate_after(
        self, session: AsyncSession, conversation_id: uuid.UUID, sequence: int
    ) -> None:
        await session.execute(
            Message.__table__.delete().where(
                Message.conversation_id == conversation_id, Message.sequence > sequence
            )
        )
        await session.flush()
