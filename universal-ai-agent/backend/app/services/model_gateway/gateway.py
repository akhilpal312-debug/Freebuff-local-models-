"""Model Gateway: the only place that talks to model providers.

It resolves a model + provider + decrypted key, builds the right adapter and
normalises streaming events. Callers never see provider response bodies.
"""

from __future__ import annotations

import uuid
from collections.abc import AsyncIterator

import httpx
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings
from app.core.crypto import CredentialCipher
from app.core.errors import APIError
from app.db.tables import Model, Provider, ProviderSecret
from app.services.providers.base import (
    ChatMessage,
    ChatRequest,
    ProviderHealth,
    ProviderView,
    RemoteModel,
    StreamEvent,
    ToolSpec,
)
from app.services.providers.factory import build_adapter

MAX_TOOL_ROUNDS_DEFAULT = 8


def provider_view(provider: Provider) -> ProviderView:
    return ProviderView(
        id=provider.id,
        slug=provider.slug,
        name=provider.name,
        kind=provider.kind,
        api_compat=provider.api_compat,
        base_url=provider.base_url,
        privacy_tier=provider.privacy_tier,
        extra=provider.extra or {},
    )


class ModelGateway:
    def __init__(self, settings: Settings, cipher: CredentialCipher) -> None:
        self.settings = settings
        self.cipher = cipher
        self._client = httpx.AsyncClient(
            timeout=httpx.Timeout(settings.provider_timeout_seconds, connect=15.0),
            follow_redirects=False,
        )

    async def close(self) -> None:
        await self._client.aclose()

    # ------------------------------------------------------------------ secrets
    async def api_key_for(self, session: AsyncSession, provider: Provider) -> str | None:
        secret = await session.scalar(
            select(ProviderSecret).where(ProviderSecret.provider_id == provider.id)
        )
        if secret is None:
            return None
        return self.cipher.decrypt(secret.ciphertext)

    # ------------------------------------------------------------------ adapters
    async def adapter_for(self, session: AsyncSession, provider: Provider):
        api_key = await self.api_key_for(session, provider)
        return build_adapter(
            provider_view(provider), api_key, self._client, self.settings.provider_timeout_seconds
        )

    async def health(self, session: AsyncSession, provider: Provider) -> ProviderHealth:
        adapter = await self.adapter_for(session, provider)
        return await adapter.health()

    async def remote_models(self, session: AsyncSession, provider: Provider) -> list[RemoteModel]:
        adapter = await self.adapter_for(session, provider)
        return await adapter.list_models()

    # ------------------------------------------------------------------ streaming
    async def stream(
        self,
        session: AsyncSession,
        *,
        model_row: Model,
        provider: Provider,
        messages: list[ChatMessage],
        tools: list[ToolSpec] | None = None,
        temperature: float = 0.7,
        max_tokens: int = 2048,
        reasoning_effort: str | None = None,
    ) -> AsyncIterator[StreamEvent]:
        if not provider.enabled:
            raise APIError(
                409,
                "provider_disabled",
                f"{provider.name} is disabled. Enable it in the Model Hub first.",
            )
        adapter = await self.adapter_for(session, provider)
        if reasoning_effort and not model_row.supports_reasoning:
            reasoning_effort = None
        request = ChatRequest(
            model=model_row.model_id,
            messages=messages,
            tools=tools or [],
            temperature=temperature,
            max_tokens=min(max_tokens, model_row.max_output_tokens or max_tokens),
            reasoning_effort=reasoning_effort,
        )
        if tools and not model_row.supports_tools:
            raise APIError(
                409,
                "model_capability_mismatch",
                f"{model_row.display_name} does not support tool calling.",
            )
        async for event in adapter.stream_chat(request):
            yield event

    async def complete(
        self,
        session: AsyncSession,
        *,
        model_row: Model,
        provider: Provider,
        messages: list[ChatMessage],
        max_tokens: int = 1024,
        temperature: float = 0.3,
    ) -> tuple[str, dict]:
        """Non-streaming convenience used by planning/summary steps."""
        text_parts: list[str] = []
        usage: dict = {}
        async for event in self.stream(
            session,
            model_row=model_row,
            provider=provider,
            messages=messages,
            temperature=temperature,
            max_tokens=max_tokens,
        ):
            if event.kind == "text":
                text_parts.append(event.text)
            elif event.kind == "usage" and event.usage:
                usage = event.usage
            elif event.kind == "error":
                raise APIError(502, "provider_error", event.error or "Provider error.")
        return "".join(text_parts), usage


async def load_model_and_provider(
    session: AsyncSession, user_id: uuid.UUID, model_pk: uuid.UUID
) -> tuple[Model, Provider]:
    row = await session.execute(
        select(Model, Provider)
        .join(Provider, Provider.id == Model.provider_id)
        .where(Model.id == model_pk, Model.user_id == user_id, Provider.user_id == user_id)
    )
    result = row.first()
    if result is None:
        raise APIError(404, "model_not_found", "That model is not connected to your account.")
    return result[0], result[1]
