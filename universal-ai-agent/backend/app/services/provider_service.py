"""Provider registry: seeding, CRUD, credential storage, connection tests, sync."""

from __future__ import annotations

import re
import uuid
from typing import Any

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.crypto import CredentialCipher
from app.core.errors import APIError, bad_request, conflict, not_found
from app.db.tables import Model, Provider, ProviderSecret
from app.services.model_gateway.catalog import PROVIDER_SEEDS, seed_for
from app.services.model_gateway.gateway import ModelGateway
from app.services.model_gateway.router import ModelOption

VALID_COMPAT = {"openai", "anthropic", "gemini"}
VALID_PRIVACY = {"cloud", "local"}


def _slugify(value: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", value.lower()).strip("-")
    return (slug or "provider")[:60]


class ProviderService:
    def __init__(self, cipher: CredentialCipher, gateway: ModelGateway) -> None:
        self.cipher = cipher
        self.gateway = gateway

    # ------------------------------------------------------------------- seeding
    async def ensure_seeded(self, session: AsyncSession, user_id: uuid.UUID) -> None:
        existing = set(
            (
                await session.scalars(
                    select(Provider.catalog_key).where(
                        Provider.user_id == user_id, Provider.catalog_key.is_not(None)
                    )
                )
            ).all()
        )
        created = False
        for seed in PROVIDER_SEEDS:
            if seed.key in existing:
                continue
            provider = Provider(
                user_id=user_id,
                slug=seed.key,
                catalog_key=seed.key,
                name=seed.name,
                kind=seed.kind,
                api_compat=seed.api_compat,
                base_url=seed.base_url,
                privacy_tier=seed.privacy_tier,
                enabled=False,
                is_builtin=True,
                notes=seed.notes,
                extra=dict(seed.extra),
            )
            session.add(provider)
            await session.flush()
            for model_seed in seed.models:
                session.add(
                    Model(
                        user_id=user_id,
                        provider_id=provider.id,
                        model_id=model_seed.model_id,
                        display_name=model_seed.display_name,
                        context_window=model_seed.context_window,
                        max_output_tokens=model_seed.max_output_tokens,
                        supports_tools=model_seed.supports_tools,
                        supports_vision=model_seed.supports_vision,
                        supports_reasoning=model_seed.supports_reasoning,
                        availability="unknown",
                        metadata_json={"cost_tier": model_seed.cost_tier, "seeded": True},
                    )
                )
            created = True
        if created:
            await session.flush()

    # ------------------------------------------------------------------ retrieval
    async def get_provider(
        self, session: AsyncSession, user_id: uuid.UUID, provider_id: uuid.UUID
    ) -> Provider:
        provider = await session.scalar(
            select(Provider).where(Provider.id == provider_id, Provider.user_id == user_id)
        )
        if provider is None:
            raise not_found("Provider")
        return provider

    async def secret_fingerprint(
        self, session: AsyncSession, provider_id: uuid.UUID
    ) -> str | None:
        secret = await session.scalar(
            select(ProviderSecret).where(ProviderSecret.provider_id == provider_id)
        )
        return secret.fingerprint if secret else None

    async def serialize(self, session: AsyncSession, provider: Provider) -> dict[str, Any]:
        models = (
            await session.scalars(
                select(Model)
                .where(Model.provider_id == provider.id)
                .order_by(Model.display_name)
            )
        ).all()
        fingerprint = await self.secret_fingerprint(session, provider.id)
        seed = seed_for(provider.catalog_key) if provider.catalog_key else None
        return {
            "id": str(provider.id),
            "slug": provider.slug,
            "name": provider.name,
            "kind": provider.kind,
            "api_compat": provider.api_compat,
            "base_url": provider.base_url,
            "privacy_tier": provider.privacy_tier,
            "enabled": provider.enabled,
            "is_builtin": provider.is_builtin,
            "notes": provider.notes,
            "requires_key": seed.requires_key if seed else True,
            "has_credentials": fingerprint is not None,
            "credential_fingerprint": fingerprint,
            "models": [self.serialize_model(model) for model in models],
        }

    @staticmethod
    def serialize_model(model: Model) -> dict[str, Any]:
        return {
            "id": str(model.id),
            "model_id": model.model_id,
            "display_name": model.display_name,
            "provider_id": str(model.provider_id),
            "context_window": model.context_window,
            "max_output_tokens": model.max_output_tokens,
            "supports_streaming": model.supports_streaming,
            "supports_tools": model.supports_tools,
            "supports_vision": model.supports_vision,
            "supports_reasoning": model.supports_reasoning,
            "input_modalities": model.input_modalities,
            "output_modalities": model.output_modalities,
            "availability": model.availability,
            "is_default": model.is_default,
            "cost_tier": (model.metadata_json or {}).get("cost_tier", "medium"),
        }

    async def list_providers(
        self, session: AsyncSession, user_id: uuid.UUID
    ) -> list[dict[str, Any]]:
        await self.ensure_seeded(session, user_id)
        providers = (
            await session.scalars(
                select(Provider).where(Provider.user_id == user_id).order_by(Provider.name)
            )
        ).all()
        return [await self.serialize(session, provider) for provider in providers]

    async def model_options(
        self, session: AsyncSession, user_id: uuid.UUID
    ) -> list[ModelOption]:
        await self.ensure_seeded(session, user_id)
        rows = (
            await session.execute(
                select(Model, Provider)
                .join(Provider, Provider.id == Model.provider_id)
                .where(Model.user_id == user_id, Provider.user_id == user_id)
            )
        ).all()
        fingerprints = {
            provider_id
            for provider_id in (
                await session.scalars(
                    select(ProviderSecret.provider_id).where(ProviderSecret.user_id == user_id)
                )
            ).all()
        }
        options: list[ModelOption] = []
        for model, provider in rows:
            options.append(
                ModelOption(
                    model_pk=model.id,
                    provider_id=provider.id,
                    model_id=model.model_id,
                    display_name=model.display_name,
                    provider_name=provider.name,
                    provider_slug=provider.slug,
                    privacy_tier=provider.privacy_tier,
                    has_credentials=provider.id in fingerprints,
                    supports_tools=model.supports_tools,
                    supports_vision=model.supports_vision,
                    supports_reasoning=model.supports_reasoning,
                    supports_streaming=model.supports_streaming,
                    context_window=model.context_window,
                    availability=model.availability if provider.enabled else "unavailable",
                    is_default=model.is_default,
                    cost_tier=(model.metadata_json or {}).get("cost_tier", "medium"),
                )
            )
        return options

    # --------------------------------------------------------------------- CRUD
    async def create_custom(
        self, session: AsyncSession, user_id: uuid.UUID, payload: dict[str, Any]
    ) -> dict[str, Any]:
        name = str(payload.get("name") or "").strip()
        if not name:
            raise bad_request("A provider name is required.")
        compat = str(payload.get("api_compat") or "openai").lower()
        if compat not in VALID_COMPAT:
            raise bad_request(f"api_compat must be one of {sorted(VALID_COMPAT)}.")
        base_url = str(payload.get("base_url") or "").strip()
        if not base_url.startswith(("http://", "https://")):
            raise bad_request("base_url must be an http(s) URL.")
        privacy = str(payload.get("privacy_tier") or "cloud").lower()
        if privacy not in VALID_PRIVACY:
            raise bad_request("privacy_tier must be 'cloud' or 'local'.")
        slug = _slugify(name)
        clash = await session.scalar(
            select(Provider).where(Provider.user_id == user_id, Provider.slug == slug)
        )
        if clash is not None:
            slug = f"{slug}-{uuid.uuid4().hex[:4]}"
        provider = Provider(
            user_id=user_id,
            slug=slug,
            catalog_key=None,
            name=name[:120],
            kind="custom",
            api_compat=compat,
            base_url=base_url,
            privacy_tier=privacy,
            enabled=bool(payload.get("enabled", True)),
            is_builtin=False,
            notes=str(payload.get("notes") or "")[:500] or None,
            extra=dict(payload.get("extra") or {}),
        )
        session.add(provider)
        await session.flush()
        models = payload.get("models") or []
        for item in models:
            model_id = str(item.get("model_id") or "").strip()
            if not model_id:
                continue
            session.add(
                Model(
                    user_id=user_id,
                    provider_id=provider.id,
                    model_id=model_id[:200],
                    display_name=str(item.get("display_name") or model_id)[:200],
                    context_window=int(item.get("context_window") or 8192),
                    max_output_tokens=int(item.get("max_output_tokens") or 2048),
                    supports_tools=bool(item.get("supports_tools", False)),
                    supports_vision=bool(item.get("supports_vision", False)),
                    supports_reasoning=bool(item.get("supports_reasoning", False)),
                    availability="unknown",
                    metadata_json={"cost_tier": item.get("cost_tier", "medium"), "custom": True},
                )
            )
        api_key = payload.get("api_key")
        if api_key:
            await self.set_secret(session, user_id, provider.id, str(api_key))
        await session.flush()
        return await self.serialize(session, provider)

    async def update_provider(
        self,
        session: AsyncSession,
        user_id: uuid.UUID,
        provider_id: uuid.UUID,
        payload: dict[str, Any],
    ) -> dict[str, Any]:
        provider = await self.get_provider(session, user_id, provider_id)
        if "name" in payload and str(payload["name"]).strip():
            provider.name = str(payload["name"]).strip()[:120]
        if "base_url" in payload:
            base_url = str(payload["base_url"] or "").strip()
            if base_url and not base_url.startswith(("http://", "https://")):
                raise bad_request("base_url must be an http(s) URL.")
            if provider.is_builtin and not base_url:
                raise bad_request("Built-in providers need a base URL.")
            provider.base_url = base_url or provider.base_url
        if "api_compat" in payload:
            compat = str(payload["api_compat"]).lower()
            if compat not in VALID_COMPAT:
                raise bad_request(f"api_compat must be one of {sorted(VALID_COMPAT)}.")
            provider.api_compat = compat
        if "privacy_tier" in payload:
            privacy = str(payload["privacy_tier"]).lower()
            if privacy not in VALID_PRIVACY:
                raise bad_request("privacy_tier must be 'cloud' or 'local'.")
            provider.privacy_tier = privacy
        if "enabled" in payload:
            provider.enabled = bool(payload["enabled"])
        if "notes" in payload:
            provider.notes = str(payload["notes"] or "")[:500] or None
        if "extra" in payload and isinstance(payload["extra"], dict):
            provider.extra = payload["extra"]
        await session.flush()
        return await self.serialize(session, provider)

    async def delete_provider(
        self, session: AsyncSession, user_id: uuid.UUID, provider_id: uuid.UUID
    ) -> None:
        provider = await self.get_provider(session, user_id, provider_id)
        await session.delete(provider)
        await session.flush()

    async def set_secret(
        self, session: AsyncSession, user_id: uuid.UUID, provider_id: uuid.UUID, api_key: str
    ) -> dict[str, Any]:
        api_key = api_key.strip()
        if not api_key:
            raise bad_request("The API key is empty.")
        if len(api_key) > 4096:
            raise bad_request("That API key is too long.")
        await self.get_provider(session, user_id, provider_id)
        secret = await session.scalar(
            select(ProviderSecret).where(ProviderSecret.provider_id == provider_id)
        )
        ciphertext = self.cipher.encrypt(api_key)
        fingerprint = self.cipher.fingerprint(api_key)
        if secret is None:
            secret = ProviderSecret(
                user_id=user_id,
                provider_id=provider_id,
                ciphertext=ciphertext,
                fingerprint=fingerprint,
            )
            session.add(secret)
        else:
            secret.ciphertext = ciphertext
            secret.fingerprint = fingerprint
            from datetime import UTC, datetime

            secret.rotated_at = datetime.now(UTC)
        provider = await self.get_provider(session, user_id, provider_id)
        provider.enabled = True
        await session.flush()
        return {"has_credentials": True, "credential_fingerprint": fingerprint}

    async def delete_secret(
        self, session: AsyncSession, user_id: uuid.UUID, provider_id: uuid.UUID
    ) -> None:
        await self.get_provider(session, user_id, provider_id)
        secret = await session.scalar(
            select(ProviderSecret).where(ProviderSecret.provider_id == provider_id)
        )
        if secret is not None:
            await session.delete(secret)
            await session.flush()

    # -------------------------------------------------------------- operations
    async def test_connection(
        self, session: AsyncSession, user_id: uuid.UUID, provider_id: uuid.UUID
    ) -> dict[str, Any]:
        provider = await self.get_provider(session, user_id, provider_id)
        seed = seed_for(provider.catalog_key) if provider.catalog_key else None
        needs_key = seed.requires_key if seed else provider.privacy_tier != "local"
        has_key = await self.secret_fingerprint(session, provider.id) is not None
        if needs_key and not has_key:
            return {
                "ok": False,
                "status": "missing_credentials",
                "message": "Add an API key for this provider before testing.",
                "latency_ms": 0,
                "model_count": None,
            }
        health = await self.gateway.health(session, provider)
        await session.flush()
        return {
            "ok": health.ok,
            "status": health.status,
            "message": health.message,
            "latency_ms": health.latency_ms,
            "model_count": health.model_count,
        }

    async def sync_models(
        self, session: AsyncSession, user_id: uuid.UUID, provider_id: uuid.UUID
    ) -> dict[str, Any]:
        provider = await self.get_provider(session, user_id, provider_id)
        remote = await self.gateway.remote_models(session, provider)
        if not remote:
            raise APIError(
                502, "provider_error", "The provider returned an empty model list."
            )
        existing = {
            model.model_id: model
            for model in (
                await session.scalars(select(Model).where(Model.provider_id == provider.id))
            ).all()
        }
        added, updated = 0, 0
        seen: set[str] = set()
        for item in remote:
            seen.add(item.model_id)
            model = existing.get(item.model_id)
            if model is None:
                session.add(
                    Model(
                        user_id=user_id,
                        provider_id=provider.id,
                        model_id=item.model_id,
                        display_name=item.display_name[:200],
                        context_window=item.context_window or 8192,
                        max_output_tokens=min(8192, item.context_window or 8192),
                        supports_tools=False,
                        supports_vision=False,
                        availability="available",
                        metadata_json={"cost_tier": "medium", "synced": True},
                    )
                )
                added += 1
            else:
                model.availability = "available"
                model.display_name = item.display_name[:200]
                if item.context_window:
                    model.context_window = item.context_window
                updated += 1
        for model_id, model in existing.items():
            if model_id not in seen:
                model.availability = "unavailable"
        provider.enabled = True
        await session.flush()
        return {"added": added, "updated": updated, "total": len(seen)}

    # ------------------------------------------------------------------ defaults
    async def set_default_model(
        self, session: AsyncSession, user_id: uuid.UUID, model_pk: uuid.UUID
    ) -> dict[str, Any]:
        model = await session.scalar(
            select(Model).where(Model.id == model_pk, Model.user_id == user_id)
        )
        if model is None:
            raise not_found("Model")
        await session.execute(
            Model.__table__.update().where(Model.user_id == user_id).values(is_default=False)
        )
        model.is_default = True
        provider = await self.get_provider(session, user_id, model.provider_id)
        provider.enabled = True
        await session.flush()
        return self.serialize_model(model)

    async def update_model(
        self,
        session: AsyncSession,
        user_id: uuid.UUID,
        model_pk: uuid.UUID,
        payload: dict[str, Any],
    ) -> dict[str, Any]:
        model = await session.scalar(
            select(Model).where(Model.id == model_pk, Model.user_id == user_id)
        )
        if model is None:
            raise not_found("Model")
        for field in ("display_name", "context_window", "max_output_tokens"):
            if field in payload and payload[field] is not None:
                setattr(model, field, payload[field])
        for field in ("supports_tools", "supports_vision", "supports_reasoning"):
            if field in payload and payload[field] is not None:
                setattr(model, field, bool(payload[field]))
        if model.context_window < 1 or model.max_output_tokens < 1:
            raise conflict("Context window and max output tokens must be positive.")
        await session.flush()
        return self.serialize_model(model)
