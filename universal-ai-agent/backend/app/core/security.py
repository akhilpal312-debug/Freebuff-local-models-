from __future__ import annotations

import uuid
from dataclasses import dataclass, field
from typing import Any

from app.core.config import Settings


@dataclass(frozen=True)
class AuthUser:
    """The owner of this deployment's single workspace."""

    id: uuid.UUID
    email: str | None = None
    role: str = "owner"
    claims: dict[str, Any] = field(default_factory=dict)

    @property
    def is_service(self) -> bool:
        return True


class SingleUserIdentity:
    """No accounts or tokens: every request acts as the configured owner.

    The Android app no longer signs in, so the API has no session concept. The
    owner id comes from ``SINGLE_USER_ID`` (with a stable default) and is used for
    every row the backend writes or reads.
    """

    def __init__(self, settings: Settings) -> None:
        self._user = AuthUser(
            id=settings.single_user_id,
            email=settings.single_user_email,
            role="owner",
        )

    @property
    def user(self) -> AuthUser:
        return self._user

    async def verify(self, token: str | None = None) -> AuthUser:
        """Kept as an async method so call sites read the same as before."""
        return self._user
