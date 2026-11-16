from __future__ import annotations

import time
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import ORJSONResponse

from app.api.v1.router import api_router
from app.core.config import Settings, get_settings
from app.core.container import ServiceContainer
from app.core.errors import install_error_handlers
from app.core.logging import configure_logging, new_request_id, request_id_var
from app.db.session import Database
from app.services.agent.seed import seed_templates


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or get_settings()
    configure_logging(settings.log_level)

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        database = Database(settings)
        if settings.auto_create_tables:
            await database.create_all()
            async with database.session() as session:
                await seed_templates(session)
        container = await ServiceContainer.create(settings, database)
        app.state.settings = settings
        app.state.database = database
        app.state.container = container
        try:
            yield
        finally:
            await container.close()

    app = FastAPI(
        title="Universal AI Agent API",
        version="0.1.0",
        description=(
            "Multi-model gateway, native agent runtime, research, coding workspaces "
            "and knowledge management."
        ),
        default_response_class=ORJSONResponse,
        lifespan=lifespan,
        docs_url="/docs" if not settings.is_production else "/docs",
    )
    install_error_handlers(app)

    if settings.cors_origins:
        app.add_middleware(
            CORSMiddleware,
            allow_origins=settings.cors_origins,
            allow_credentials=True,
            allow_methods=["*"],
            allow_headers=["*"],
            expose_headers=["x-request-id"],
        )

    @app.middleware("http")
    async def request_context(request: Request, call_next):
        request_id = request.headers.get("x-request-id") or new_request_id()
        token = request_id_var.set(request_id)
        started = time.perf_counter()
        try:
            response = await call_next(request)
        finally:
            request_id_var.reset(token)
        response.headers["x-request-id"] = request_id
        response.headers["x-response-time-ms"] = str(int((time.perf_counter() - started) * 1000))
        return response

    app.include_router(api_router, prefix=settings.api_prefix)
    return app


app = create_app()
