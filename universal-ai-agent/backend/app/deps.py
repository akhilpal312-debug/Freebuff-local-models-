from __future__ import annotations

import uuid
from dataclasses import dataclass
from typing import Annotated

from fastapi import Depends, Request
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.container import ServiceContainer
from app.core.security import AuthUser
from app.db.session import Database
from app.db.tables import Profile


@dataclass
class RequestContext:
    user: AuthUser
    db: AsyncSession
    database: Database
    container: ServiceContainer
    request: Request

    @property
    def user_id(self) -> uuid.UUID:
        return self.user.id


def get_database(request: Request) -> Database:
    return request.app.state.database


def get_container(request: Request) -> ServiceContainer:
    return request.app.state.container


async def get_session(request: Request) -> AsyncSession:
    database: Database = request.app.state.database
    async with database.session() as session:
        yield session


async def _ensure_profile(session: AsyncSession, user: AuthUser) -> None:
    existing = await session.scalar(select(Profile).where(Profile.id == user.id))
    if existing is None:
        session.add(Profile(id=user.id, email=user.email, display_name=user.email))
        await session.flush()
    elif user.email and existing.email != user.email:
        existing.email = user.email


async def get_current_user(request: Request) -> AuthUser:
    """Single-user mode: no token is read or required."""
    container: ServiceContainer = request.app.state.container
    return await container.identity.verify()


async def get_context(
    request: Request,
    user: Annotated[AuthUser, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> RequestContext:
    container: ServiceContainer = request.app.state.container
    database: Database = request.app.state.database
    await _ensure_profile(session, user)
    await session.commit()
    await container.rate_limiter.enforce(f"user:{user.id}")
    return RequestContext(
        user=user, db=session, database=database, container=container, request=request
    )


Context = Annotated[RequestContext, Depends(get_context)]
DbSession = Annotated[AsyncSession, Depends(get_session)]
CurrentUser = Annotated[AuthUser, Depends(get_current_user)]
