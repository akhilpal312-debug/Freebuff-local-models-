"""Google Gemini (Generative Language API) adapter."""

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


class GeminiAdapter(ProviderAdapter):
    api_compat = "gemini"

    def _headers(self) -> dict[str, str]:
        self._assert_key()
        headers = {"Content-Type": "application/json"}
        if self.api_key:
            headers["x-goog-api-key"] = self.api_key
        return headers

    @staticmethod
    def _parts(message: ChatMessage) -> list[dict]:
        parts: list[dict] = []
        if message.content:
            parts.append({"text": message.content})
        for url in message.images:
            if url.startswith("data:"):
                header, _, payload = url.partition(",")
                mime = header[5:].split(";")[0] or "image/png"
                parts.append({"inlineData": {"mimeType": mime, "data": payload}})
        return parts or [{"text": ""}]

    def _payload(self, request: ChatRequest) -> dict:
        system_parts: list[str] = []
        contents: list[dict] = []
        for message in request.messages:
            if message.role == "system":
                system_parts.append(message.content)
                continue
            if message.role == "tool":
                contents.append(
                    {
                        "role": "user",
                        "parts": [
                            {
                                "functionResponse": {
                                    "name": message.name or "tool",
                                    "response": {"result": message.content},
                                }
                            }
                        ],
                    }
                )
                continue
            if message.role == "assistant" and message.tool_calls:
                parts = []
                if message.content:
                    parts.append({"text": message.content})
                for call in message.tool_calls:
                    function = call.get("function", {})
                    try:
                        import json

                        args = json.loads(function.get("arguments") or "{}")
                    except ValueError:
                        args = {}
                    parts.append({"functionCall": {"name": function.get("name"), "args": args}})
                contents.append({"role": "model", "parts": parts})
                continue
            contents.append(
                {
                    "role": "model" if message.role == "assistant" else "user",
                    "parts": self._parts(message),
                }
            )
        generation_config: dict = {
            "temperature": request.temperature,
            "maxOutputTokens": request.max_tokens,
        }
        if request.json_mode:
            generation_config["responseMimeType"] = "application/json"
        if request.reasoning_effort:
            budget = {"low": 1024, "medium": 6144, "high": 16384}.get(request.reasoning_effort, 6144)
            generation_config["thinkingConfig"] = {"thinkingBudget": budget}
        payload: dict = {"contents": contents, "generationConfig": generation_config}
        if system_parts:
            payload["systemInstruction"] = {"parts": [{"text": "\n\n".join(system_parts)}]}
        if request.tools:
            payload["tools"] = [
                {"functionDeclarations": [tool.to_gemini() for tool in request.tools]}
            ]
        return payload

    async def stream_chat(self, request: ChatRequest) -> AsyncIterator[StreamEvent]:
        base = self.provider.base_url or "https://generativelanguage.googleapis.com/v1beta"
        url = join_url(base, f"models/{request.model}:streamGenerateContent?alt=sse")
        total_chars = 0
        call_index = -1
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
                    chunk = self._json(data)
                    if not chunk:
                        continue
                    metadata = chunk.get("usageMetadata")
                    if metadata:
                        yield StreamEvent(
                            kind="usage",
                            usage={
                                "prompt_tokens": metadata.get("promptTokenCount", 0),
                                "completion_tokens": metadata.get("candidatesTokenCount", 0),
                            },
                        )
                    if chunk.get("promptFeedback", {}).get("blockReason"):
                        raise APIError(
                            400,
                            "content_blocked",
                            "Gemini blocked this request: "
                            f"{chunk['promptFeedback']['blockReason']}",
                        )
                    for candidate in chunk.get("candidates") or []:
                        for part in (candidate.get("content") or {}).get("parts") or []:
                            if part.get("text"):
                                total_chars += len(part["text"])
                                yield StreamEvent(kind="text", text=str(part["text"]))
                            if part.get("functionCall"):
                                call = part["functionCall"]
                                import json

                                call_index += 1
                                yield StreamEvent(
                                    kind="tool_call",
                                    tool_call=ToolCall(
                                        id=f"gemini_call_{call_index}",
                                        name=str(call.get("name")),
                                        arguments=json.dumps(call.get("args") or {}),
                                    ),
                                    provider_meta={"index": call_index},
                                )
                        finish = candidate.get("finishReason")
                        if finish:
                            yield StreamEvent(kind="done", finish_reason=str(finish))
        except httpx.TimeoutException as exc:
            raise APIError(504, "provider_timeout", "Gemini did not respond in time.") from exc
        except httpx.HTTPError as exc:
            raise APIError(502, "provider_unreachable", "Could not reach Gemini.") from exc

    async def list_models(self) -> list[RemoteModel]:
        base = self.provider.base_url or "https://generativelanguage.googleapis.com/v1beta"
        url = join_url(base, "models")
        try:
            response = await self.client.get(url, headers=self._headers(), timeout=20.0)
        except httpx.HTTPError as exc:
            raise APIError(502, "provider_unreachable", "Could not reach Gemini.") from exc
        if response.status_code >= 400:
            raise APIError(502, "provider_error", clean_error(response.status_code, response.text))
        models: list[RemoteModel] = []
        for item in response.json().get("models", []):
            if "generateContent" not in (item.get("supportedGenerationMethods") or []):
                continue
            name = str(item.get("name", "")).removeprefix("models/")
            if name:
                models.append(
                    RemoteModel(
                        model_id=name,
                        display_name=str(item.get("displayName") or name),
                        context_window=item.get("inputTokenLimit"),
                    )
                )
        return sorted(models, key=lambda model: model.model_id)
