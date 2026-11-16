from fastapi import APIRouter

from app.api.v1 import (
    account,
    agents,
    chat,
    health,
    knowledge,
    projects,
    providers,
    research,
    runtime,
)

api_router = APIRouter()
api_router.include_router(health.router)
api_router.include_router(account.router)
api_router.include_router(providers.router)
api_router.include_router(chat.router)
api_router.include_router(agents.router)
api_router.include_router(projects.router)
api_router.include_router(knowledge.router)
api_router.include_router(research.router)
api_router.include_router(runtime.router)
