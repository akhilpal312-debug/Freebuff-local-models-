"""Smart model routing.

Routing is deterministic and explainable. A user-pinned model is never silently
replaced: if it cannot satisfy the request the API returns an explicit
`model_capability_mismatch` error so the client can ask the user instead.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass, field
from typing import Literal

from app.core.errors import APIError

Mode = Literal["chat", "deep", "research", "coding", "agent"]

MODE_REQUIREMENTS: dict[str, dict[str, bool]] = {
    "chat": {},
    "deep": {"reasoning": True},
    "research": {"tools": True},
    "coding": {"tools": True},
    "agent": {"tools": True},
}

# Modes whose cost/privacy profile is significant enough that an automatic
# switch must be reported to the client.
_REPORT_SWITCH_MODES = {"deep", "coding", "agent"}


@dataclass
class ModelOption:
    """A routing candidate. Plain data so routing is unit-testable."""

    model_pk: uuid.UUID
    provider_id: uuid.UUID
    model_id: str
    display_name: str
    provider_name: str
    provider_slug: str
    privacy_tier: str
    has_credentials: bool
    supports_tools: bool
    supports_vision: bool
    supports_reasoning: bool
    supports_streaming: bool
    context_window: int
    availability: str
    is_default: bool = False
    cost_tier: str = "medium"
    score: float = field(default=0.0, compare=False)


@dataclass
class RoutingDecision:
    option: ModelOption | None
    reason: str
    considered: int
    rejected: list[str] = field(default_factory=list)

    @property
    def switched(self) -> bool:
        return self.option is not None


def _eligible(option: ModelOption) -> tuple[bool, str]:
    if option.availability == "unavailable":
        return False, "marked unavailable"
    if not option.has_credentials and option.privacy_tier != "local":
        return False, "no API key stored"
    if not option.supports_streaming:
        return False, "does not support streaming"
    return True, ""


def _matches_mode(option: ModelOption, mode: str, requires_tools, requires_vision, min_context) -> tuple[bool, str]:
    needs = MODE_REQUIREMENTS.get(mode, {})
    if needs.get("reasoning") and not option.supports_reasoning:
        return False, f"{mode} mode needs a reasoning-capable model"
    if (needs.get("tools") or requires_tools) and not option.supports_tools:
        return False, "no tool calling"
    if requires_vision and not option.supports_vision:
        return False, "no vision support"
    if min_context and option.context_window < min_context:
        return False, f"context window {option.context_window} < {min_context}"
    return True, ""


def _score(option: ModelOption, *, prefer_local: bool) -> float:
    score = 0.0
    if option.is_default:
        score += 30
    if prefer_local and option.privacy_tier == "local":
        score += 25
    if option.privacy_tier == "local":
        score += 5
    score += {"free": 12, "low": 10, "medium": 5, "high": 0}.get(option.cost_tier, 5)
    if option.supports_reasoning:
        score += 4
    if option.supports_vision:
        score += 2
    # Prefer the smallest context that still fits; huge windows cost more.
    score += min(option.context_window / 1_000_000, 2.0)
    if option.availability == "available":
        score += 3
    return score


def select_model(
    options: list[ModelOption],
    *,
    mode: str = "chat",
    requires_tools: bool = False,
    requires_vision: bool = False,
    min_context: int = 0,
    preferred_model_pk: uuid.UUID | None = None,
    prefer_local: bool = False,
    allow_fallback: bool = True,
) -> RoutingDecision:
    rejected: list[str] = []
    usable: list[ModelOption] = []
    for option in options:
        ok, reason = _eligible(option)
        if ok:
            ok, reason = _matches_mode(option, mode, requires_tools, requires_vision, min_context)
        if ok:
            usable.append(option)
        else:
            rejected.append(f"{option.provider_slug}/{option.model_id}: {reason}")

    if preferred_model_pk is not None:
        preferred = next((o for o in options if o.model_pk == preferred_model_pk), None)
        if preferred is None:
            if not allow_fallback:
                raise APIError(404, "model_not_found", "The selected model is not available.")
        else:
            ok, reason = _eligible(preferred)
            if ok:
                ok, reason = _matches_mode(
                    preferred, mode, requires_tools, requires_vision, min_context
                )
            if ok:
                return RoutingDecision(
                    option=preferred,
                    reason="Using the model you selected.",
                    considered=len(options),
                    rejected=rejected,
                )
            if not allow_fallback:
                raise APIError(
                    409,
                    "model_capability_mismatch",
                    f"{preferred.display_name} cannot handle this request: {reason}.",
                    details={
                        "model": preferred.model_id,
                        "mode": mode,
                        "suggestion": "Pick another model or switch mode.",
                    },
                )
            rejected.append(f"pinned model unusable: {reason}")

    if not usable:
        raise APIError(
            409,
            "no_suitable_model",
            "No connected model can handle this request. Connect a provider or choose "
            "a model with the required capabilities.",
            details={"mode": mode, "rejected": rejected[:12]},
        )

    for option in usable:
        option.score = _score(option, prefer_local=prefer_local)
    usable.sort(key=lambda option: (option.score, option.context_window), reverse=True)
    best = usable[0]
    reason_parts = [f"Selected {best.display_name} via {best.provider_name}"]
    if mode != "chat":
        reason_parts.append(f"for {mode} mode")
    if best.privacy_tier == "local":
        reason_parts.append("(local provider, data stays on your network)")
    if preferred_model_pk is not None:
        reason_parts.append("because your selected model could not satisfy the request")
    return RoutingDecision(
        option=best,
        reason=" ".join(reason_parts) + ".",
        considered=len(options),
        rejected=rejected,
    )
