"""Seed catalogue of built-in providers and their model capability metadata.

These are *seeds*: model ids, context limits and capability flags are stored in
the `models` table and can be refreshed from the provider's own `/models`
endpoint ("Sync models" in the Model Hub). Nothing here is advertised as
available until a connection test succeeds.
"""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(frozen=True)
class ModelSeed:
    model_id: str
    display_name: str
    context_window: int
    max_output_tokens: int = 4096
    supports_tools: bool = True
    supports_vision: bool = False
    supports_reasoning: bool = False
    cost_tier: str = "medium"


@dataclass(frozen=True)
class ProviderSeed:
    key: str
    name: str
    kind: str
    api_compat: str
    base_url: str | None
    privacy_tier: str = "cloud"
    requires_key: bool = True
    notes: str = ""
    extra: dict = field(default_factory=dict)
    models: tuple[ModelSeed, ...] = ()


PROVIDER_SEEDS: tuple[ProviderSeed, ...] = (
    ProviderSeed(
        key="nvidia",
        name="NVIDIA NIM",
        kind="nvidia",
        api_compat="openai",
        base_url="https://integrate.api.nvidia.com/v1",
        notes="NVIDIA hosted NIM endpoints. Uses the OpenAI-compatible dialect.",
        extra={"stream_options": True},
        models=(
            ModelSeed("meta/llama-3.3-70b-instruct", "Llama 3.3 70B Instruct", 131072),
            ModelSeed(
                "nvidia/llama-3.1-nemotron-70b-instruct",
                "Nemotron 70B Instruct",
                131072,
            ),
            ModelSeed(
                "deepseek-ai/deepseek-r1",
                "DeepSeek R1 (reasoning)",
                65536,
                supports_tools=False,
                supports_reasoning=True,
                cost_tier="low",
            ),
            ModelSeed(
                "microsoft/phi-3.5-vision-instruct",
                "Phi 3.5 Vision",
                131072,
                supports_tools=False,
                supports_vision=True,
                cost_tier="low",
            ),
        ),
    ),
    ProviderSeed(
        key="gemini",
        name="Google Gemini",
        kind="gemini",
        api_compat="gemini",
        base_url="https://generativelanguage.googleapis.com/v1beta",
        notes="Native Generative Language API (not OpenAI compatible).",
        models=(
            ModelSeed(
                "gemini-2.0-flash",
                "Gemini 2.0 Flash",
                1048576,
                8192,
                supports_vision=True,
                supports_reasoning=True,
                cost_tier="low",
            ),
            ModelSeed(
                "gemini-1.5-pro",
                "Gemini 1.5 Pro",
                2097152,
                8192,
                supports_vision=True,
                supports_reasoning=True,
            ),
        ),
    ),
    ProviderSeed(
        key="openai",
        name="OpenAI",
        kind="openai",
        api_compat="openai",
        base_url="https://api.openai.com/v1",
        models=(
            ModelSeed("gpt-4o-mini", "GPT-4o mini", 128000, 16384, supports_vision=True),
            ModelSeed("gpt-4o", "GPT-4o", 128000, 16384, supports_vision=True),
            ModelSeed(
                "o3-mini",
                "o3-mini (reasoning)",
                200000,
                100000,
                supports_vision=False,
                supports_reasoning=True,
            ),
        ),
    ),
    ProviderSeed(
        key="anthropic",
        name="Anthropic Claude",
        kind="anthropic",
        api_compat="anthropic",
        base_url="https://api.anthropic.com",
        models=(
            ModelSeed(
                "claude-sonnet-4-5",
                "Claude Sonnet 4.5",
                200000,
                8192,
                supports_vision=True,
                supports_reasoning=True,
            ),
            ModelSeed(
                "claude-haiku-4-5",
                "Claude Haiku 4.5",
                200000,
                8192,
                supports_vision=True,
                supports_reasoning=True,
                cost_tier="low",
            ),
        ),
    ),
    ProviderSeed(
        key="openrouter",
        name="OpenRouter",
        kind="openrouter",
        api_compat="openai",
        base_url="https://openrouter.ai/api/v1",
        notes="Aggregator. Model ids are namespaced as vendor/model.",
        extra={"headers": {"HTTP-Referer": "https://universal-ai-agent.app"}},
        models=(
            ModelSeed(
                "anthropic/claude-sonnet-4.5",
                "Claude Sonnet 4.5 (OpenRouter)",
                200000,
                supports_vision=True,
                supports_reasoning=True,
            ),
            ModelSeed(
                "google/gemini-2.0-flash-001",
                "Gemini 2.0 Flash (OpenRouter)",
                1048576,
                supports_vision=True,
                cost_tier="low",
            ),
            ModelSeed(
                "meta-llama/llama-3.3-70b-instruct",
                "Llama 3.3 70B (OpenRouter)",
                131072,
                cost_tier="low",
            ),
        ),
    ),
    ProviderSeed(
        key="ollama",
        name="Ollama (local)",
        kind="ollama",
        api_compat="openai",
        base_url="http://127.0.0.1:11434/v1",
        privacy_tier="local",
        requires_key=False,
        notes=(
            "Runs on a machine you control. Point the base URL at that machine; "
            "127.0.0.1 only works when the backend runs on the same host."
        ),
        extra={"stream_options": False},
        models=(
            ModelSeed(
                "llama3.2",
                "Llama 3.2 (Ollama)",
                131072,
                supports_tools=True,
                cost_tier="free",
            ),
            ModelSeed(
                "qwen2.5-coder:7b",
                "Qwen2.5 Coder 7B (Ollama)",
                32768,
                supports_tools=True,
                cost_tier="free",
            ),
        ),
    ),
)

SEED_BY_KEY: dict[str, ProviderSeed] = {seed.key: seed for seed in PROVIDER_SEEDS}

DEFAULT_CUSTOM_MODELS: tuple[ModelSeed, ...] = (
    ModelSeed("custom-model", "Custom model", 8192, 2048, supports_tools=False),
)


def seed_for(key: str) -> ProviderSeed | None:
    return SEED_BY_KEY.get(key)
