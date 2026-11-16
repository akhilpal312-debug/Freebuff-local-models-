from __future__ import annotations

import uuid
from functools import lru_cache
from pathlib import Path

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """All runtime configuration. Values come from the environment or `.env`."""

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    environment: str = "development"
    log_level: str = "INFO"
    api_prefix: str = "/api/v1"
    public_base_url: str = "http://localhost:8000"
    cors_origins: list[str] = Field(default_factory=list)

    database_url: str = "sqlite+aiosqlite:///./data/dev.db"
    auto_create_tables: bool = True

    # Single-workspace mode: the app has no login, so every request belongs to
    # this one owner. Change the id only before the first run.
    single_user_id: uuid.UUID = uuid.UUID("00000000-0000-0000-0000-000000000001")
    single_user_email: str = "owner@localhost"
    single_user_name: str = "Owner"

    credential_encryption_key: str | None = None

    rate_limit_per_minute: int = 120
    max_upload_bytes: int = 10 * 1024 * 1024
    provider_timeout_seconds: float = 120.0
    workspace_root: Path = Path("./data/workspaces")
    command_timeout_seconds: int = 300

    tavily_api_key: str | None = None
    research_max_sources: int = 6
    research_fetch_timeout_seconds: float = 20.0

    sandbox_mode: str = "local"
    sandbox_docker_image: str = "ghcr.io/universal-ai-agent/sandbox:latest"

    openclaw_default_url: str | None = None
    openclaw_protocol_min: int = 4
    openclaw_protocol_max: int = 4
    openclaw_client_id: str = "universal-ai-agent-backend"
    openclaw_client_version: str = "0.1.0"

    redis_url: str | None = None

    @field_validator("cors_origins", mode="before")
    @classmethod
    def _split_origins(cls, value: object) -> object:
        if isinstance(value, str):
            return [item.strip() for item in value.split(",") if item.strip()]
        return value

    @property
    def is_production(self) -> bool:
        return self.environment.lower() in {"production", "prod"}

    @property
    def is_serverless(self) -> bool:
        return bool(__import__("os").environ.get("VERCEL"))


@lru_cache
def get_settings() -> Settings:
    return Settings()


def reset_settings_cache() -> None:
    """Used by tests that patch the environment between cases."""
    get_settings.cache_clear()
