from __future__ import annotations

import logging
from typing import Any

from fastapi import FastAPI, Request
from fastapi.responses import ORJSONResponse

logger = logging.getLogger(__name__)


class APIError(Exception):
    """An error that is safe to render to an API client."""

    def __init__(
        self,
        status_code: int,
        code: str,
        message: str,
        *,
        details: dict[str, Any] | None = None,
    ) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.code = code
        self.message = message
        self.details = details or {}


def not_found(what: str) -> APIError:
    return APIError(404, "not_found", f"{what} was not found.")


def forbidden(message: str = "You do not have access to this resource.") -> APIError:
    return APIError(403, "forbidden", message)


def bad_request(message: str, *, details: dict[str, Any] | None = None) -> APIError:
    return APIError(400, "bad_request", message, details=details)


def conflict(message: str) -> APIError:
    return APIError(409, "conflict", message)


def unavailable(message: str, *, details: dict[str, Any] | None = None) -> APIError:
    return APIError(503, "unavailable", message, details=details)


def install_error_handlers(app: FastAPI) -> None:
    @app.exception_handler(APIError)
    async def _api_error(_: Request, exc: APIError) -> ORJSONResponse:
        return ORJSONResponse(
            status_code=exc.status_code,
            content={"error": {"code": exc.code, "message": exc.message, "details": exc.details}},
        )

    @app.exception_handler(Exception)
    async def _unhandled(request: Request, exc: Exception) -> ORJSONResponse:
        # Log the failure server-side; never leak stack traces or secrets to clients.
        logger.exception("unhandled error on %s %s", request.method, request.url.path)
        return ORJSONResponse(
            status_code=500,
            content={"error": {"code": "internal_error", "message": "Something went wrong."}},
        )
