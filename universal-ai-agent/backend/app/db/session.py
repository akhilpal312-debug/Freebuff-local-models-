from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.pool import NullPool, StaticPool

from app.core.config import Settings
from app.db.tables import Base


class Database:
    """Owns the async engine and session factory."""

    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        url = settings.database_url
        kwargs: dict[str, object] = {"echo": False, "future": True}
        if url.startswith("sqlite"):
            kwargs["connect_args"] = {"check_same_thread": False}
            if ":memory:" in url:
                kwargs["poolclass"] = StaticPool
        elif settings.is_serverless:
            # Vercel: one short-lived invocation per request. Let the provider's
            # pooler (Neon) hold connections instead of the function's process.
            kwargs["poolclass"] = NullPool
            kwargs["pool_pre_ping"] = True
        else:
            kwargs["pool_size"] = 5
            kwargs["max_overflow"] = 10
            kwargs["pool_pre_ping"] = True
        self.engine: AsyncEngine = create_async_engine(url, **kwargs)
        self.session_factory = async_sessionmaker(self.engine, expire_on_commit=False)

    async def create_all(self) -> None:
        async with self.engine.begin() as connection:
            await connection.run_sync(Base.metadata.create_all)

    @asynccontextmanager
    async def session(self) -> AsyncIterator[AsyncSession]:
        async with self.session_factory() as session:
            try:
                yield session
                await session.commit()
            except Exception:
                await session.rollback()
                raise

    async def dispose(self) -> None:
        await self.engine.dispose()
