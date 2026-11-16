from __future__ import annotations

import uuid
from typing import Any

from fastapi import APIRouter
from pydantic import BaseModel, Field, field_validator
from sqlalchemy import select

from app.core.errors import not_found
from app.db.tables import Model
from app.deps import Context
from app.services.audit import record_audit
from app.services.providers.factory import SUPPORTED_COMPAT

router = APIRouter(tags=["models"])


class CustomModelInput(BaseModel):
    model_id: str = Field(min_length=1, max_length=200)
    display_name: str | None = Field(default=None, max_length=200)
    context_window: int = Field(default=8192, ge=1024, le=10_000_000)
    max_output_tokens: int = Field(default=2048, ge=64, le=1_000_000)
    supports_tools: bool = False
    supports_vision: bool = False
    supports_reasoning: bool = False


class ProviderCreate(BaseModel):
    name: str = Field(min_length=1, max_length=120)
    base_url: str = Field(min_length=8, max_length=500)
    api_compat: str = "openai"
    privacy_tier: str = "cloud"
    enabled: bool = True
    notes: str | None = None
    api_key: str | None = Field(default=None, max_length=4096)
    models: list[CustomModelInput] = Field(default_factory=list)

    @field_validator("api_compat")
    @classmethod
    def _compat(cls, value: str) -> str:
        if value not in SUPPORTED_COMPAT:
            raise ValueError(f"api_compat must be one of {list(SUPPORTED_COMPAT)}")
        return value

    @field_validator("privacy_tier")
    @classmethod
    def _privacy(cls, value: str) -> str:
        if value not in {"cloud", "local"}:
            raise ValueError("privacy_tier must be 'cloud' or 'local'")
        return value


class ProviderUpdate(BaseModel):
    name: str | None = None
    base_url: str | None = None
    api_compat: str | None = None
    privacy_tier: str | None = None
    enabled: bool | None = None
    notes: str | None = None
    extra: dict[str, Any] | None = None


class SecretInput(BaseModel):
    api_key: str = Field(min_length=1, max_length=4096)


class ModelPatch(BaseModel):
    display_name: str | None = None
    context_window: int | None = Field(default=None, ge=1024, le=10_000_000)
    max_output_tokens: int | None = Field(default=None, ge=64, le=1_000_000)
    supports_tools: bool | None = None
    supports_vision: bool | None = None
    supports_reasoning: bool | None = None


@router.get("/providers")
async def list_providers(ctx: Context) -> dict:
    providers = await ctx.container.providers.list_providers(ctx.db, ctx.user_id)
    return {"providers": providers}


@router.post("/providers", status_code=201)
async def create_provider(payload: ProviderCreate, ctx: Context) -> dict:
    provider = await ctx.container.providers.create_custom(
        ctx.db, ctx.user_id, payload.model_dump(exclude_none=True)
    )
    await record_audit(
        ctx.db,
        user_id=ctx.user_id,
        action="provider.create",
        target_type="provider",
        target_id=provider["id"],
        detail={"name": provider["name"], "api_compat": provider["api_compat"]},
    )
    return provider


@router.patch("/providers/{provider_id}")
async def update_provider(provider_id: uuid.UUID, payload: ProviderUpdate, ctx: Context) -> dict:
    provider = await ctx.container.providers.update_provider(
        ctx.db, ctx.user_id, provider_id, payload.model_dump(exclude_none=True)
    )
    await record_audit(
        ctx.db, user_id=ctx.user_id, action="provider.update", target_id=str(provider_id)
    )
    return provider


@router.delete("/providers/{provider_id}", status_code=204)
async def delete_provider(provider_id: uuid.UUID, ctx: Context) -> None:
    await ctx.container.providers.delete_provider(ctx.db, ctx.user_id, provider_id)
    await record_audit(
        ctx.db, user_id=ctx.user_id, action="provider.delete", target_id=str(provider_id)
    )


@router.put("/providers/{provider_id}/credentials")
async def set_credentials(provider_id: uuid.UUID, payload: SecretInput, ctx: Context) -> dict:
    result = await ctx.container.providers.set_secret(
        ctx.db, ctx.user_id, provider_id, payload.api_key
    )
    await record_audit(
        ctx.db,
        user_id=ctx.user_id,
        action="provider.credentials_set",
        target_id=str(provider_id),
        detail={"fingerprint": result["credential_fingerprint"]},
    )
    # The key itself is never returned.
    return result


@router.delete("/providers/{provider_id}/credentials", status_code=204)
async def delete_credentials(provider_id: uuid.UUID, ctx: Context) -> None:
    await ctx.container.providers.delete_secret(ctx.db, ctx.user_id, provider_id)
    await record_audit(
        ctx.db,
        user_id=ctx.user_id,
        action="provider.credentials_delete",
        target_id=str(provider_id),
    )


@router.post("/providers/{provider_id}/test")
async def test_provider(provider_id: uuid.UUID, ctx: Context) -> dict:
    result = await ctx.container.providers.test_connection(ctx.db, ctx.user_id, provider_id)
    await record_audit(
        ctx.db,
        user_id=ctx.user_id,
        action="provider.test",
        target_id=str(provider_id),
        success=bool(result["ok"]),
        detail={"status": result["status"]},
    )
    return result


@router.post("/providers/{provider_id}/sync")
async def sync_models(provider_id: uuid.UUID, ctx: Context) -> dict:
    result = await ctx.container.providers.sync_models(ctx.db, ctx.user_id, provider_id)
    await record_audit(
        ctx.db,
        user_id=ctx.user_id,
        action="provider.sync_models",
        target_id=str(provider_id),
        detail=result,
    )
    return result


@router.get("/models")
async def list_models(ctx: Context) -> dict:
    providers = await ctx.container.providers.list_providers(ctx.db, ctx.user_id)
    models = []
    for provider in providers:
        for model in provider["models"]:
            models.append(
                {
                    **model,
                    "provider_name": provider["name"],
                    "provider_slug": provider["slug"],
                    "provider_enabled": provider["enabled"],
                    "provider_privacy_tier": provider["privacy_tier"],
                    "has_credentials": provider["has_credentials"],
                }
            )
    models.sort(key=lambda item: (not item["is_default"], item["provider_name"], item["display_name"]))
    return {"models": models}


@router.post("/models/{model_pk}/default")
async def set_default_model(model_pk: uuid.UUID, ctx: Context) -> dict:
    model = await ctx.container.providers.set_default_model(ctx.db, ctx.user_id, model_pk)
    await record_audit(
        ctx.db, user_id=ctx.user_id, action="model.set_default", target_id=str(model_pk)
    )
    return model


@router.patch("/models/{model_pk}")
async def patch_model(model_pk: uuid.UUID, payload: ModelPatch, ctx: Context) -> dict:
    model = await ctx.container.providers.update_model(
        ctx.db, ctx.user_id, model_pk, payload.model_dump(exclude_none=True)
    )
    await record_audit(ctx.db, user_id=ctx.user_id, action="model.update", target_id=str(model_pk))
    return model


@router.get("/models/{model_pk}")
async def get_model(model_pk: uuid.UUID, ctx: Context) -> dict:
    row = await ctx.db.scalar(
        select(Model).where(Model.id == model_pk, Model.user_id == ctx.user_id)
    )
    if row is None:
        raise not_found("Model")
    return ctx.container.providers.serialize_model(row)
