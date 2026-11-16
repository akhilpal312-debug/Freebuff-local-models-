"""Anthropic Messages API adapter (separate protocol, not OpenAI compatible)."""

from __future__ import annotations

from collections.abc import AsyncIterator

import httpx

from app.core.errors import APIError
from app.services.providers.base import (
    ChatMessage,
    ChatRequest,
    ProviderAdapter,
    RemoteModel,
    StreamEvent,
    ToolCall,
    clean_error,
    iter_sse,
    join_url,
)


class AnthropicAdapter(ProviderAdapter):
    api_compat = "anthropic"
    api_version = "2023-06-01"

    def _headers(self) -> dict[str, str]:
        self._assert_key()
        return {
            "Content-Type": "application/json",
            "x-api-key": self.api_key or "",
            "anthropic-version": str(self.provider.extra.get("anthropic_version", self.api_version)),
        }

    @staticmethod
    def _content_blocks(message: ChatMessage) -> list[dict]:
        blocks: list[dict] = []
        if message.content:
            blocks.append({"type": "text", "text": message.content})
        for url in message.images:
            if url.startswith("data:"):
                header, _, payload = url.partition(",")
                media_type = header[5:].split(";")[0] or "image/png"
                blocks.append(
                    {
                        "type": "image",
                        "source": {
                            "type": "base64",
                            "media_type": media_type,
                            "data": payload,
                        },
                    }
                )
        return blocks or [{"type": "text", "text": ""}]

    def _payload(self, request: ChatRequest) -> dict:
        system_parts = [
            message.content for message in request.messages if message.role == "system"
        ]
        messages: list[dict] = []
        for message in request.messages:
            if message.role == "system":
                continue
            if message.role == "tool":
                messages.append(
                    {
                        "role": "user",
                        "content": [
                            {
                                "type": "tool_result",
                                "tool_use_id": message.tool_call_id,
                                "content": message.content,
                            }
                        ],
                    }
                )
                continue
            if message.role == "assistant" and message.tool_calls:
                blocks: list[dict] = []
                if message.content:
                    blocks.append({"type": "text", "text": message.content})
                for call in message.tool_calls:
                    function = call.get("function", {})
                    try:
                        import json

                        arguments = json.loads(function.get("arguments") or "{}")
                    except ValueError:
                        arguments = {}
                    blocks.append(
                        {
                            "type": "tool_use",
                            "id": call.get("id"),
                            "name": function.get("name"),
                            "input": arguments,
                        }
                    )
                messages.append({"role": "assistant", "content": blocks})
                continue
            messages.append(
                {"role": message.role, "content": self._content_blocks(message)}
            )
        payload: dict = {
            "model": request.model,
            "messages": messages,
            "max_tokens": request.max_tokens,
            "temperature": min(request.temperature, 1.0),
            "stream": True,
        }
        if system_parts:
            payload["system"] = "\n\n".join(system_parts)
        if request.tools:
            payload["tools"] = [tool.to_anthropic() for tool in request.tools]
        if request.reasoning_effort:
            budget = {"low": 1024, "medium": 4096, "high": 8192}.get(
                request.reasoning_effort, 4096
            )
            payload["thinking"] = {"type": "enabled", "budget_tokens": budget}
        return payload

    async def stream_chat(self, request: ChatRequest) -> AsyncIterator[StreamEvent]:
        url = join_url(self.provider.base_url, "v1/messages")
        usage: dict = {}
        try:
            async with self.client.stream(
                "POST",
                url,
                json=self._payload(request),
                headers=self._headers(),
                timeout=self.timeout,
            ) as response:
                if response.status_code >= 400:
                    body = (await response.aread()).decode("utf-8", "replace")
                    raise APIError(
                        502 if response.status_code >= 500 else 400,
                        "provider_error",
                        clean_error(response.status_code, body),
                    )
                current_tool: dict | None = None
                async for event_name, data in iter_sse(response):
                    chunk = self._json(data)
                    if not chunk:
                        continue
                    kind = chunk.get("type") or event_name
                    if kind == "message_start":
                        message_usage = (chunk.get("message") or {}).get("usage") or {}
                        usage.update(
                            {
                                "prompt_tokens": message_usage.get("input_tokens", 0),
                                "completion_tokens": message_usage.get("output_tokens", 0),
                            }
                        )
                    elif kind == "content_block_start":
                        block = chunk.get("content_block") or {}
                        if block.get("type") == "tool_use":
                            current_tool = {
                                "id": block.get("id", ""),
                                "name": block.get("name", ""),
                                "arguments": "",
                            }
                            usage.setdefault("_tool_index", 0)
                    elif kind == "content_block_delta":
                        delta = chunk.get("delta") or {}
                        if delta.get("type") == "text_delta" and delta.get("text"):
                            yield StreamEvent(kind="text", text=str(delta["text"]))
                        elif delta.get("type") == "thinking_delta" and delta.get("thinking"):
                            # Private reasoning traces stay server-side; only the
                            # fact that reasoning happened is surfaced.
                            yield StreamEvent(kind="reasoning", text="")
                        elif delta.get("type") == "input_json_delta" and current_tool:
                            current_tool["arguments"] += str(delta.get("partial_json") or "")
                    elif kind == "content_block_stop" and current_tool:
                        yield StreamEvent(
                            kind="tool_call",
                            tool_call=ToolCall(
                                id=current_tool["id"],
                                name=current_tool["name"],
                                arguments=current_tool["arguments"] or "{}",
                            ),
                        )
                        current_tool = None
                    elif kind == "message_delta":
                        delta = chunk.get("delta") or {}
                        delta_usage = chunk.get("usage") or {}
                        if delta_usage.get("output_tokens"):
                            usage["completion_tokens"] = delta_usage["output_tokens"]
                        if delta.get("stop_reason"):
                            yield StreamEvent(kind="done", finish_reason=str(delta["stop_reason"]))
                    elif kind == "message_stop":
                        usage.pop("_tool_index", None)
                        yield StreamEvent(kind="usage", usage=usage)
                    elif kind == "error":
                        error = chunk.get("error") or {}
                        raise APIError(
                            502,
                            "provider_error",
                            f"Provider error: {error.get('message', 'unknown')}",
                        )
        except httpx.TimeoutException as exc:
            raise APIError(504, "provider_timeout", "Anthropic did not respond in time.") from exc
        except httpx.HTTPError as exc:
            raise APIError(502, "provider_unreachable", "Could not reach Anthropic.") from exc

    async def list_models(self) -> list[RemoteModel]:
        url = join_url(self.provider.base_url, "v1/models")
        try:
            response = await self.client.get(url, headers=self._headers(), timeout=20.0)
        except httpx.HTTPError as exc:
            raise APIError(502, "provider_unreachable", "Could not reach Anthropic.") from exc
        if response.status_code == 401:
            raise APIError(400, "invalid_api_key", "Anthropic rejected the API key.")
        if response.status_code >= 400:
            raise APIError(502, "provider_error", clean_error(response.status_code, response.text))
        body = response.json()
        models = [
            RemoteModel(
                model_id=str(item.get("id")),
                display_name=str(item.get("display_name") or item.get("id")),
            )
            for item in body.get("data", [])
            if isinstance(item, dict) and item.get("id")
        ]
        return sorted(models, key=lambda model: model.model_id)
