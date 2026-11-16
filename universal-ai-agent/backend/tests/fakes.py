"""Test-only provider adapter.

This is the *only* mock provider in the project. It is never registered in a
running deployment; tests patch the gateway's adapter factory to use it.
"""

from __future__ import annotations

import json
from collections.abc import AsyncIterator, Callable
from typing import Any

from app.services.providers.base import (
    ChatRequest,
    ProviderAdapter,
    ProviderHealth,
    RemoteModel,
    StreamEvent,
    ToolCall,
)

Responder = Callable[[ChatRequest, int], AsyncIterator[StreamEvent]]


async def default_responder(request: ChatRequest, call_index: int) -> AsyncIterator[StreamEvent]:
    system = "\n".join(message.content for message in request.messages if message.role == "system")
    last = request.messages[-1].content if request.messages else ""
    saw_tool_result = any(message.role == "tool" for message in request.messages)
    if "planning component" in system:
        payload = {
            "summary": "Write a note",
            "steps": [{"id": 1, "title": "Create note", "detail": "Write notes.txt"}],
        }
        yield StreamEvent(kind="text", text=json.dumps(payload))
    elif request.tools and "Step 1:" in last and not saw_tool_result:
        yield StreamEvent(kind="text", text="Creating the file now.")
        yield StreamEvent(
            kind="tool_call",
            tool_call=ToolCall(
                id="call_1",
                name="files.write",
                arguments=json.dumps({"path": "notes.txt", "content": "hello from the agent"}),
            ),
        )
    elif request.tools and saw_tool_result:
        yield StreamEvent(kind="text", text="Step complete: notes.txt was written.")
    elif request.reasoning_effort:
        yield StreamEvent(kind="reasoning", text="internal")
        yield StreamEvent(kind="text", text="Deep answer with trade-offs.")
    else:
        yield StreamEvent(kind="text", text="Hello from the fake provider.")
    yield StreamEvent(
        kind="usage",
        usage={"prompt_tokens": 12, "completion_tokens": 7, "total_tokens": 19},
    )
    yield StreamEvent(kind="done", finish_reason="stop")


class FakeAdapter(ProviderAdapter):
    api_compat = "openai"
    calls: list[ChatRequest] = []

    def __init__(self, *args: Any, responder: Responder | None = None, **kwargs: Any) -> None:
        super().__init__(*args, **kwargs)
        self.responder = responder or default_responder
        self.call_index = 0

    async def stream_chat(self, request: ChatRequest) -> AsyncIterator[StreamEvent]:
        FakeAdapter.calls.append(request)
        index = self.call_index
        self.call_index += 1
        async for event in self.responder(request, index):
            yield event

    async def list_models(self) -> list[RemoteModel]:
        return [RemoteModel(model_id="fake-1", display_name="Fake Model", context_window=128000)]

    async def health(self) -> ProviderHealth:
        return ProviderHealth(
            ok=True, status="connected", latency_ms=5, message="Connected. 1 models available.", model_count=1
        )


async def slow_responder(request: ChatRequest, call_index: int) -> AsyncIterator[StreamEvent]:
    import asyncio

    for index in range(40):
        await asyncio.sleep(0.05)
        yield StreamEvent(kind="text", text=f"chunk{index} ")
    yield StreamEvent(kind="usage", usage={"prompt_tokens": 1, "completion_tokens": 1})
    yield StreamEvent(kind="done", finish_reason="stop")


async def shell_responder(request: ChatRequest, call_index: int) -> AsyncIterator[StreamEvent]:
    system = "\n".join(message.content for message in request.messages if message.role == "system")
    last = request.messages[-1].content if request.messages else ""
    saw_tool_result = any(message.role == "tool" for message in request.messages)
    if "planning component" in system:
        yield StreamEvent(
            kind="text",
            text=json.dumps(
                {
                    "summary": "Run a command",
                    "steps": [{"id": 1, "title": "Run echo", "detail": "echo hello"}],
                }
            ),
        )
    elif request.tools and "Step 1:" in last and not saw_tool_result:
        yield StreamEvent(kind="text", text="Requesting a command run.")
        yield StreamEvent(
            kind="tool_call",
            tool_call=ToolCall(
                id="call_shell",
                name="shell.run",
                arguments=json.dumps({"command": "python -c 'print(2+2)'"}),
            ),
        )
    elif saw_tool_result:
        yield StreamEvent(kind="text", text="The command ran; see the tool output.")
    else:
        yield StreamEvent(kind="text", text="ok")
    yield StreamEvent(kind="usage", usage={"prompt_tokens": 3, "completion_tokens": 3})
    yield StreamEvent(kind="done", finish_reason="stop")


async def failing_responder(request: ChatRequest, call_index: int) -> AsyncIterator[StreamEvent]:
    yield StreamEvent(kind="text", text="partial")
    raise __import__("app.core.errors", fromlist=["APIError"]).APIError(
        502, "provider_error", "Upstream exploded."
    )
