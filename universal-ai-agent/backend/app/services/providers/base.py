"""Provider adapter contracts.

Providers do not share an API. Each adapter translates the neutral types in this
module to its own wire format and normalises the response back to `StreamEvent`s.
"""

from __future__ import annotations

import json
from abc import ABC, abstractmethod
from collections.abc import AsyncIterator
from dataclasses import dataclass, field
from typing import Any, Literal

import httpx

from app.core.errors import APIError

Role = Literal["system", "user", "assistant", "tool"]


@dataclass
class ChatMessage:
    role: Role
    content: str = ""
    images: list[str] = field(default_factory=list)  # data: or https: URLs
    tool_calls: list[dict[str, Any]] = field(default_factory=list)
    tool_call_id: str | None = None
    name: str | None = None

    def to_openai(self) -> dict[str, Any]:
        if self.role == "tool":
            return {"role": "tool", "content": self.content, "tool_call_id": self.tool_call_id}
        if self.images:
            parts: list[dict[str, Any]] = []
            if self.content:
                parts.append({"type": "text", "text": self.content})
            for url in self.images:
                parts.append({"type": "image_url", "image_url": {"url": url}})
            return {"role": self.role, "content": parts}
        payload: dict[str, Any] = {"role": self.role, "content": self.content}
        if self.tool_calls:
            payload["tool_calls"] = self.tool_calls
        return payload


@dataclass
class ToolSpec:
    name: str
    description: str
    parameters: dict[str, Any]

    def to_openai(self) -> dict[str, Any]:
        return {
            "type": "function",
            "function": {
                "name": self.name,
                "description": self.description,
                "parameters": self.parameters,
            },
        }

    def to_anthropic(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "description": self.description,
            "input_schema": self.parameters,
        }

    def to_gemini(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "description": self.description,
            "parameters": self.parameters,
        }


@dataclass
class ChatRequest:
    model: str
    messages: list[ChatMessage]
    tools: list[ToolSpec] = field(default_factory=list)
    temperature: float = 0.7
    max_tokens: int = 2048
    reasoning_effort: str | None = None
    json_mode: bool = False


@dataclass
class ToolCall:
    id: str
    name: str
    arguments: str


@dataclass
class StreamEvent:
    kind: Literal["text", "reasoning", "tool_call", "usage", "done", "error"]
    text: str = ""
    tool_call: ToolCall | None = None
    usage: dict[str, Any] | None = None
    finish_reason: str | None = None
    error: str | None = None
    provider_meta: dict[str, Any] = field(default_factory=dict)


@dataclass
class RemoteModel:
    model_id: str
    display_name: str
    context_window: int | None = None


@dataclass
class ProviderHealth:
    ok: bool
    status: str
    latency_ms: int
    message: str
    model_count: int | None = None


@dataclass
class ProviderView:
    """Detached, secret-free view of a provider row."""

    id: Any
    slug: str
    name: str
    kind: str
    api_compat: str
    base_url: str | None
    privacy_tier: str = "cloud"
    extra: dict[str, Any] = field(default_factory=dict)


def join_url(base: str | None, path: str) -> str:
    if not base:
        raise APIError(400, "provider_misconfigured", "This provider has no base URL configured.")
    return base.rstrip("/") + "/" + path.lstrip("/")


def clean_error(status_code: int, body: str) -> str:
    """Turn a provider error body into a short, secret-free message."""
    from app.core.redaction import redact

    text = redact(body.strip())
    if len(text) > 500:
        text = text[:500] + "…"
    if status_code in (401, 403):
        return f"Provider rejected the credentials (HTTP {status_code}). Check the API key."
    if status_code == 429:
        return "Provider rate limit reached. Try again shortly or pick another model."
    if status_code >= 500:
        return f"Provider is unavailable (HTTP {status_code})."
    return f"Provider error (HTTP {status_code}): {text or 'no details returned'}"


async def iter_sse(response: httpx.Response) -> AsyncIterator[tuple[str | None, str]]:
    """Yield (event_name, data) pairs from a text/event-stream response."""
    event: str | None = None
    data_lines: list[str] = []
    async for raw in response.aiter_lines():
        line = raw.rstrip("\r")
        if not line:
            if data_lines:
                yield event, "\n".join(data_lines)
            event, data_lines = None, []
            continue
        if line.startswith(":"):
            continue
        if line.startswith("event:"):
            event = line[6:].strip()
        elif line.startswith("data:"):
            data_lines.append(line[5:].lstrip())
    if data_lines:
        yield event, "\n".join(data_lines)


def estimate_tokens(text: str) -> int:
    """Rough token estimate used only when a provider omits usage data."""
    return max(1, len(text) // 4)


class ProviderAdapter(ABC):
    api_compat: str = "openai"

    def __init__(
        self,
        provider: ProviderView,
        api_key: str | None,
        client: httpx.AsyncClient,
        timeout: float = 120.0,
    ) -> None:
        self.provider = provider
        self.api_key = api_key
        self.client = client
        self.timeout = timeout

    @abstractmethod
    def stream_chat(self, request: ChatRequest) -> AsyncIterator[StreamEvent]:
        """Stream a completion. Must raise APIError on transport/auth failures."""

    @abstractmethod
    async def list_models(self) -> list[RemoteModel]:
        """Return what the provider actually reports as available."""

    async def health(self) -> ProviderHealth:
        import time

        started = time.perf_counter()
        try:
            models = await self.list_models()
        except APIError as exc:
            return ProviderHealth(
                ok=False,
                status="error",
                latency_ms=int((time.perf_counter() - started) * 1000),
                message=exc.message,
            )
        return ProviderHealth(
            ok=True,
            status="connected",
            latency_ms=int((time.perf_counter() - started) * 1000),
            message=f"Connected. {len(models)} models available.",
            model_count=len(models),
        )

    def _requires_key(self) -> bool:
        return self.provider.privacy_tier != "local"

    def _assert_key(self) -> None:
        if self._requires_key() and not self.api_key:
            raise APIError(
                400,
                "missing_api_key",
                f"No API key stored for {self.provider.name}. Add one in the Model Hub.",
            )

    @staticmethod
    def _json(payload: str) -> dict[str, Any] | None:
        try:
            value = json.loads(payload)
        except json.JSONDecodeError:
            return None
        return value if isinstance(value, dict) else None
