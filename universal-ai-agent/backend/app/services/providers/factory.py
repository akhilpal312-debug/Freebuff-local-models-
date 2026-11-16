"""Adapter factory: picks the wire protocol, not the vendor."""

from __future__ import annotations

import httpx

from app.core.errors import APIError
from app.services.providers.anthropic import AnthropicAdapter
from app.services.providers.base import ProviderAdapter, ProviderView
from app.services.providers.gemini import GeminiAdapter
from app.services.providers.openai_compat import OpenAICompatibleAdapter

_ADAPTERS: dict[str, type[ProviderAdapter]] = {
    "openai": OpenAICompatibleAdapter,
    "anthropic": AnthropicAdapter,
    "gemini": GeminiAdapter,
}

SUPPORTED_COMPAT = tuple(_ADAPTERS)


def build_adapter(
    provider: ProviderView,
    api_key: str | None,
    client: httpx.AsyncClient,
    timeout: float = 120.0,
) -> ProviderAdapter:
    compat = (provider.api_compat or "openai").lower()
    adapter_class = _ADAPTERS.get(compat)
    if adapter_class is None:
        raise APIError(
            400,
            "unsupported_provider_api",
            f"API compatibility '{compat}' is not supported. Use one of: "
            + ", ".join(SUPPORTED_COMPAT),
        )
    return adapter_class(provider, api_key, client, timeout)
