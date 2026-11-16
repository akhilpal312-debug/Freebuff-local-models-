"""OpenAI-compatible adapter.

Covers OpenAI, NVIDIA NIM, OpenRouter, Ollama (`/v1`) and user-defined providers
that speak the `/chat/completions` dialect. Feature differences are driven by
provider metadata rather than assumptions.
"""

from __future__ import annotations

from collections.abc import AsyncIterator

import httpx

from app.core.errors import APIError
from app.services.providers.base import (
    ChatRequest,
    ProviderAdapter,
    ProviderHealth,
    RemoteModel,
    StreamEvent,
    ToolCall,
    clean_error,
    estimate_tokens,
    iter_sse,
    join_url,
)


class OpenAICompatibleAdapter(ProviderAdapter):
    api_compat = "openai"

    @property
    def _supports_stream_options(self) -> bool:
        return bool(self.provider.extra.get("stream_options", True))

    def _headers(self) -> dict[str, str]:
        headers = {"Content-Type": "application/json"}
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"
        for key, value in (self.provider.extra.get("headers") or {}).items():
            headers[str(key)] = str(value)
        return headers

    def _payload(self, request: ChatRequest) -> dict:
        payload: dict = {
            "model": request.model,
            "messages": [message.to_openai() for message in request.messages],
            "stream": True,
            "temperature": request.temperature,
            "max_tokens": request.max_tokens,
        }
        if self._supports_stream_options:
            payload["stream_options"] = {"include_usage": True}
        if request.tools:
            payload["tools"] = [tool.to_openai() for tool in request.tools]
            payload["tool_choice"] = "auto"
        if request.json_mode:
            payload["response_format"] = {"type": "json_object"}
        if request.reasoning_effort:
            payload["reasoning_effort"] = request.reasoning_effort
        return payload

    async def stream_chat(self, request: ChatRequest) -> AsyncIterator[StreamEvent]:
        self._assert_key()
        url = join_url(self.provider.base_url, "chat/completions")
        saw_usage = False
        prompt_chars = 0
        completion_chars = 0
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
                async for _, data in iter_sse(response):
                    if data.strip() == "[DONE]":
                        break
                    chunk = self._json(data)
                    if not chunk:
                        continue
                    if chunk.get("error"):
                        error = chunk["error"]
                        message = error.get("message") if isinstance(error, dict) else str(error)
                        raise APIError(502, "provider_error", f"Provider error: {message}")
                    usage = chunk.get("usage")
                    if usage:
                        saw_usage = True
                        yield StreamEvent(kind="usage", usage=usage)
                    for choice in chunk.get("choices") or []:
                        delta = choice.get("delta") or {}
                        reasoning = delta.get("reasoning_content") or delta.get("reasoning")
                        if reasoning:
                            yield StreamEvent(kind="reasoning", text=str(reasoning))
                        text = delta.get("content")
                        if text:
                            completion_chars += len(str(text))
                            yield StreamEvent(kind="text", text=str(text))
                        for call in delta.get("tool_calls") or []:
                            function = call.get("function") or {}
                            yield StreamEvent(
                                kind="tool_call",
                                tool_call=ToolCall(
                                    id=str(call.get("id") or ""),
                                    name=str(function.get("name") or ""),
                                    arguments=str(function.get("arguments") or ""),
                                ),
                                provider_meta={"index": call.get("index", 0)},
                            )
                        finish = choice.get("finish_reason")
                        if finish:
                            yield StreamEvent(kind="done", finish_reason=str(finish))
                if not saw_usage:
                    prompt_chars = sum(len(message.content) for message in request.messages)
                    yield StreamEvent(
                        kind="usage",
                        usage={
                            "prompt_tokens": estimate_tokens("x" * prompt_chars),
                            "completion_tokens": estimate_tokens("x" * completion_chars),
                            "estimated": True,
                        },
                    )
        except httpx.TimeoutException as exc:
            raise APIError(504, "provider_timeout", "The provider did not respond in time.") from exc
        except httpx.HTTPError as exc:
            raise APIError(
                502, "provider_unreachable", f"Could not reach {self.provider.name}."
            ) from exc

    async def list_models(self) -> list[RemoteModel]:
        self._assert_key()
        url = join_url(self.provider.base_url, "models")
        try:
            response = await self.client.get(url, headers=self._headers(), timeout=20.0)
        except httpx.HTTPError as exc:
            raise APIError(
                502, "provider_unreachable", f"Could not reach {self.provider.name}."
            ) from exc
        if response.status_code == 401:
            raise APIError(400, "invalid_api_key", "The provider rejected the API key.")
        if response.status_code >= 400:
            raise APIError(
                502,
                "provider_error",
                clean_error(response.status_code, response.text),
            )
        body = response.json()
        items = body.get("data") if isinstance(body, dict) else body
        if not isinstance(items, list):
            raise APIError(502, "provider_error", "The provider returned an unexpected model list.")
        models: list[RemoteModel] = []
        for item in items:
            if isinstance(item, dict):
                model_id = item.get("id") or item.get("name")
                if model_id:
                    models.append(
                        RemoteModel(
                            model_id=str(model_id),
                            display_name=str(item.get("name") or model_id),
                            context_window=item.get("context_length")
                            or (item.get("top_provider") or {}).get("context_length"),
                        )
                    )
            elif isinstance(item, str):
                models.append(RemoteModel(model_id=item, display_name=item))
        return sorted(models, key=lambda model: model.model_id)

    async def health(self) -> ProviderHealth:
        return await super().health()
