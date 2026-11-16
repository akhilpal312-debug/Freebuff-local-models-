from __future__ import annotations

import uuid
from typing import Any

from fastapi import APIRouter
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field
from sqlalchemy import select

from app.api.serializers import conversation_out, message_out
from app.core.errors import bad_request, not_found
from app.db.tables import Message
from app.deps import Context
from app.services.chat_service import MODES
from app.services.prompts import system_prompt

router = APIRouter(tags=["chat"])

SSE_HEADERS = {
    "Cache-Control": "no-cache, no-transform",
    "Connection": "keep-alive",
    "X-Accel-Buffering": "no",
}


class ConversationCreate(BaseModel):
    title: str | None = Field(default=None, max_length=200)
    mode: str = "chat"
    model_id: uuid.UUID | None = None
    project_id: uuid.UUID | None = None


class ConversationUpdate(BaseModel):
    title: str | None = Field(default=None, max_length=200)
    mode: str | None = None
    model_id: uuid.UUID | None = None
    project_id: uuid.UUID | None = None
    pinned: bool | None = None


class Attachment(BaseModel):
    type: str = Field(pattern="^(image|file)$")
    url: str = Field(max_length=4000)
    name: str | None = Field(default=None, max_length=200)


class StreamRequest(BaseModel):
    content: str = Field(default="", max_length=200_000)
    mode: str | None = None
    model_id: uuid.UUID | None = None
    reasoning_effort: str | None = Field(default=None, pattern="^(low|medium|high)$")
    temperature: float = Field(default=0.7, ge=0.0, le=2.0)
    attachments: list[Attachment] = Field(default_factory=list)


class EditRequest(BaseModel):
    content: str = Field(min_length=1, max_length=200_000)
    mode: str | None = None
    model_id: uuid.UUID | None = None


@router.get("/conversations")
async def list_conversations(ctx: Context, limit: int = 50) -> dict:
    rows = await ctx.container.chat.list_conversations(ctx.db, ctx.user_id, limit=limit)
    return {"conversations": [conversation_out(row) for row in rows]}


@router.post("/conversations", status_code=201)
async def create_conversation(payload: ConversationCreate, ctx: Context) -> dict:
    conversation = await ctx.container.chat.create_conversation(
        ctx.db,
        ctx.user_id,
        title=payload.title,
        mode=payload.mode,
        model_pk=payload.model_id,
        project_id=payload.project_id,
    )
    return conversation_out(conversation)


@router.get("/conversations/{conversation_id}")
async def get_conversation(conversation_id: uuid.UUID, ctx: Context) -> dict:
    conversation = await ctx.container.chat.get_conversation(
        ctx.db, ctx.user_id, conversation_id
    )
    messages = await ctx.container.chat.messages(ctx.db, conversation.id)
    return {
        "conversation": conversation_out(conversation, message_count=len(messages)),
        "messages": [message_out(row) for row in messages],
    }


@router.patch("/conversations/{conversation_id}")
async def update_conversation(
    conversation_id: uuid.UUID, payload: ConversationUpdate, ctx: Context
) -> dict:
    conversation = await ctx.container.chat.get_conversation(
        ctx.db, ctx.user_id, conversation_id
    )
    if payload.mode is not None:
        if payload.mode not in MODES:
            raise bad_request(f"mode must be one of {sorted(MODES)}.")
        conversation.mode = payload.mode
    if payload.title is not None:
        conversation.title = payload.title[:200]
    if payload.model_id is not None:
        conversation.model_pk = payload.model_id
    if payload.project_id is not None:
        conversation.project_id = payload.project_id
    if payload.pinned is not None:
        conversation.pinned = payload.pinned
    await ctx.db.flush()
    return conversation_out(conversation)


@router.delete("/conversations/{conversation_id}", status_code=204)
async def delete_conversation(conversation_id: uuid.UUID, ctx: Context) -> None:
    conversation = await ctx.container.chat.get_conversation(
        ctx.db, ctx.user_id, conversation_id
    )
    await ctx.db.delete(conversation)


@router.get("/conversations/{conversation_id}/messages")
async def list_messages(conversation_id: uuid.UUID, ctx: Context) -> dict:
    conversation = await ctx.container.chat.get_conversation(
        ctx.db, ctx.user_id, conversation_id
    )
    rows = await ctx.container.chat.messages(ctx.db, conversation.id)
    return {"messages": [message_out(row) for row in rows]}


def _stream_response(generator) -> StreamingResponse:
    return StreamingResponse(generator, media_type="text/event-stream", headers=SSE_HEADERS)


@router.post("/conversations/{conversation_id}/stream")
async def stream_reply(conversation_id: uuid.UUID, payload: StreamRequest, ctx: Context):
    conversation = await ctx.container.chat.get_conversation(
        ctx.db, ctx.user_id, conversation_id
    )
    mode = payload.mode or conversation.mode
    if mode not in MODES:
        raise bad_request(f"mode must be one of {sorted(MODES)}.")
    # Fail with a proper 4xx before any response bytes are sent.
    await ctx.container.chat.ensure_routable(
        ctx.db,
        user_id=ctx.user_id,
        conversation=conversation,
        mode=mode,
        model_pk=payload.model_id or conversation.model_pk,
        content=payload.content,
        attachments=[item.model_dump() for item in payload.attachments],
    )
    generator = ctx.container.chat.stream_reply(
        ctx.db,
        user_id=ctx.user_id,
        conversation=conversation,
        content=payload.content,
        mode=mode,
        model_pk=payload.model_id or conversation.model_pk,
        reasoning_effort=payload.reasoning_effort,
        temperature=payload.temperature,
        attachments=[item.model_dump() for item in payload.attachments],
    )
    return _stream_response(generator)


@router.post("/conversations/{conversation_id}/regenerate")
async def regenerate(conversation_id: uuid.UUID, ctx: Context):
    conversation = await ctx.container.chat.get_conversation(
        ctx.db, ctx.user_id, conversation_id
    )
    last_assistant = await ctx.db.scalar(
        select(Message)
        .where(
            Message.conversation_id == conversation.id,
            Message.role == "assistant",
        )
        .order_by(Message.sequence.desc())
        .limit(1)
    )
    if last_assistant is None:
        raise not_found("Previous assistant message")
    await ctx.container.chat.ensure_routable(
        ctx.db,
        user_id=ctx.user_id,
        conversation=conversation,
        mode=conversation.mode,
        model_pk=conversation.model_pk,
        content=last_assistant.content,
    )
    generator = ctx.container.chat.stream_reply(
        ctx.db,
        user_id=ctx.user_id,
        conversation=conversation,
        content="",
        mode=conversation.mode,
        model_pk=conversation.model_pk,
        regenerate_message_id=last_assistant.id,
    )
    return _stream_response(generator)


@router.post("/conversations/{conversation_id}/messages/{message_id}/edit")
async def edit_message(
    conversation_id: uuid.UUID,
    message_id: uuid.UUID,
    payload: EditRequest,
    ctx: Context,
):
    conversation = await ctx.container.chat.get_conversation(
        ctx.db, ctx.user_id, conversation_id
    )
    message = await ctx.db.scalar(
        select(Message).where(
            Message.id == message_id,
            Message.conversation_id == conversation.id,
            Message.user_id == ctx.user_id,
        )
    )
    if message is None:
        raise not_found("Message")
    if message.role != "user":
        raise bad_request("Only your own messages can be edited.")
    message.content = payload.content
    await ctx.container.chat.truncate_after(ctx.db, conversation.id, message.sequence)
    mode = payload.mode or conversation.mode
    await ctx.container.chat.ensure_routable(
        ctx.db,
        user_id=ctx.user_id,
        conversation=conversation,
        mode=mode,
        model_pk=payload.model_id or conversation.model_pk,
        content=payload.content,
    )
    generator = ctx.container.chat.stream_reply(
        ctx.db,
        user_id=ctx.user_id,
        conversation=conversation,
        content="",
        mode=mode,
        model_pk=payload.model_id or conversation.model_pk,
        persist_user_message=False,
    )
    return _stream_response(generator)


@router.get("/prompts/{mode}")
async def preview_prompt(mode: str) -> dict:
    if mode not in MODES:
        raise bad_request(f"mode must be one of {sorted(MODES)}.")
    return {"mode": mode, "system_prompt": system_prompt(mode)}


@router.get("/modes")
async def list_modes() -> dict[str, Any]:
    return {
        "modes": [
            {"id": "chat", "name": "Chat", "description": "Everyday conversation and questions."},
            {
                "id": "deep",
                "name": "Deep Thinking",
                "description": "Structured multi-step analysis with a reasoning-capable model.",
            },
            {
                "id": "research",
                "name": "Web Research",
                "description": "Live web search with fetched, cited sources.",
            },
            {
                "id": "coding",
                "name": "Coding",
                "description": "Work on a project with file and command tools.",
            },
            {
                "id": "agent",
                "name": "Agent",
                "description": "Autonomous multi-step execution with approvals.",
            },
        ]
    }
