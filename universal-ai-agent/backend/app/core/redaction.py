from __future__ import annotations

import re
from typing import Any

_PATTERNS: tuple[tuple[str, re.Pattern[str]], ...] = (
    # OpenAI / OpenRouter / NVIDIA NIM style keys
    ("api_key", re.compile(r"\bsk-[A-Za-z0-9_\-]{12,}\b")),
    # Google API keys
    ("api_key", re.compile(r"\bAIza[0-9A-Za-z_\-]{20,}\b")),
    # Anthropic keys
    ("api_key", re.compile(r"\bsk-ant-[A-Za-z0-9_\-]{12,}\b")),
    # Hugging Face tokens
    ("api_key", re.compile(r"\bhf_[A-Za-z0-9]{20,}\b")),
    # GitHub tokens
    ("api_key", re.compile(r"\bgh[pousr]_[A-Za-z0-9]{20,}\b")),
    # Bearer headers
    ("authorization", re.compile(r"(?i)\b(bearer|basic)\s+[A-Za-z0-9._\-+/=]{12,}")),
    # JWT-shaped tokens
    ("token", re.compile(r"\beyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}\b")),
    # Postgres URLs with inline passwords
    ("database_url", re.compile(r"(?i)(postgres(?:ql)?(?:\+asyncpg)?://[^:\s]+:)[^@\s]+(@)")),
    # Generic key=value assignments for sensitive names
    (
        "assignment",
        re.compile(
            r"(?i)\b(api[_-]?key|secret|password|token|authorization)\b\s*[:=]\s*"
            r"[\"']?([^\s\"',}]{6,})[\"']?"
        ),
    ),
)


def redact(text: str) -> str:
    """Replace anything that looks like a credential with `[REDACTED]`."""
    if not text:
        return text
    result = text
    for kind, pattern in _PATTERNS:
        if kind == "database_url":
            result = pattern.sub(r"\1[REDACTED]\2", result)
        elif kind == "assignment":
            result = pattern.sub(r"\1=[REDACTED]", result)
        else:
            result = pattern.sub("[REDACTED]", result)
    return result


def redact_deep(value: Any) -> Any:
    """Recursively redact strings inside dicts/lists."""
    if isinstance(value, str):
        return redact(value)
    if isinstance(value, dict):
        return {key: redact_deep(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [redact_deep(item) for item in value]
    return value


_SENSITIVE_FIELDS = {
    "api_key",
    "apikey",
    "authorization",
    "password",
    "secret",
    "token",
    "access_token",
    "refresh_token",
    "device_token",
    "ciphertext",
}


def scrub_payload(value: Any) -> Any:
    """Remove sensitive field names entirely from a payload destined for a client."""
    if isinstance(value, dict):
        cleaned: dict[str, Any] = {}
        for key, item in value.items():
            if key.lower() in _SENSITIVE_FIELDS:
                continue
            cleaned[key] = scrub_payload(item)
        return cleaned
    if isinstance(value, (list, tuple)):
        return [scrub_payload(item) for item in value]
    return value
