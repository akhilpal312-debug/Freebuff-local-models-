from __future__ import annotations

import uuid
from collections.abc import AsyncIterator
from pathlib import Path

import pytest
from cryptography.fernet import Fernet
from httpx import ASGITransport, AsyncClient

from app.core.config import Settings
from app.main import create_app

@pytest.fixture
def settings(tmp_path: Path) -> Settings:
    # A file-backed SQLite database (not `:memory:`) so the request session and the
    # orchestrator's background sessions can hold independent connections.
    database_path = tmp_path / "test.db"
    return Settings(
        environment="test",
        database_url=f"sqlite+aiosqlite:///{database_path}",
        auto_create_tables=True,
        # Fresh owner per test. Production uses one fixed owner, but a shared id
        # would let one test's rows leak into another's assertions.
        single_user_id=uuid.uuid4(),
        single_user_email="test@owner.local",
        credential_encryption_key=Fernet.generate_key().decode(),
        workspace_root=tmp_path / "workspaces",
        rate_limit_per_minute=10_000,
        sandbox_mode="local",
    )


@pytest.fixture
async def app(settings: Settings):
    application = create_app(settings)
    async with application.router.lifespan_context(application):
        yield application


@pytest.fixture
async def client(app) -> AsyncIterator[AsyncClient]:
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as http_client:
        yield http_client


@pytest.fixture
def auth_headers() -> dict[str, str]:
    """There is no login anymore; kept so existing call sites stay readable."""
    return {}


@pytest.fixture
def owner_id(settings: Settings) -> uuid.UUID:
    return settings.single_user_id
