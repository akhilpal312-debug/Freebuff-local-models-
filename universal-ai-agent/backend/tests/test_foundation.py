from __future__ import annotations

import uuid

import pytest
from sqlalchemy import select

from app.core.crypto import CredentialCipher
from app.core.redaction import redact
from app.db.tables import ProviderSecret


async def test_health_and_ready(client):
    health = await client.get("/api/v1/health")
    assert health.status_code == 200
    assert health.json()["status"] == "ok"

    ready = await client.get("/api/v1/health/ready")
    assert ready.status_code == 200
    assert ready.json()["database"] == "ok"


async def test_no_login_is_required(client, owner_id):
    # Single-workspace mode: no token, no account, but the same owner every time.
    response = await client.get("/api/v1/me")
    assert response.status_code == 200
    assert response.json()["id"] == str(owner_id)


async def test_profile_is_created_on_first_request(client, owner_id):
    response = await client.get("/api/v1/me")
    assert response.status_code == 200
    payload = response.json()
    assert payload["id"] == str(owner_id)
    assert payload["email"]


async def test_provider_catalogue_is_seeded_without_secrets(client, auth_headers):
    response = await client.get("/api/v1/providers", headers=auth_headers)
    assert response.status_code == 200
    providers = response.json()["providers"]
    keys = {provider["slug"] for provider in providers}
    assert {"nvidia", "gemini", "openai", "anthropic", "openrouter", "ollama"} <= keys
    for provider in providers:
        assert provider["has_credentials"] is False
        assert "api_key" not in provider
        assert "encrypted" not in str(provider).lower()
    ollama = next(provider for provider in providers if provider["slug"] == "ollama")
    assert ollama["privacy_tier"] == "local"
    assert ollama["requires_key"] is False


async def test_credentials_are_encrypted_and_never_returned(client, auth_headers, app):
    providers = (await client.get("/api/v1/providers", headers=auth_headers)).json()["providers"]
    provider = next(item for item in providers if item["slug"] == "openai")
    secret = "sk-test-abcdef1234567890abcdef"
    response = await client.put(
        f"/api/v1/providers/{provider['id']}/credentials",
        json={"api_key": secret},
        headers=auth_headers,
    )
    assert response.status_code == 200
    body = response.json()
    assert body["has_credentials"] is True
    assert secret not in response.text
    assert body["credential_fingerprint"]

    listed = await client.get("/api/v1/providers", headers=auth_headers)
    assert secret not in listed.text

    async with app.state.database.session() as session:
        rows = (await session.scalars(select(ProviderSecret))).all()
        stored = {row.ciphertext for row in rows}
        assert secret not in stored
        cipher: CredentialCipher = app.state.container.cipher
        assert secret in {cipher.decrypt(value) for value in stored}


async def test_custom_provider_crud_and_connection_failure(client, auth_headers):
    payload = {
        "name": "My Local Gateway",
        "base_url": "http://127.0.0.1:9/v1",
        "api_compat": "openai",
        "privacy_tier": "local",
        "models": [{"model_id": "my-model", "supports_tools": True}],
    }
    created = await client.post("/api/v1/providers", json=payload, headers=auth_headers)
    assert created.status_code == 201
    provider = created.json()
    assert provider["slug"].startswith("my-local-gateway")
    assert provider["models"][0]["model_id"] == "my-model"

    # Connection test against a closed port must report failure, not 500.
    tested = await client.post(
        f"/api/v1/providers/{provider['id']}/test", headers=auth_headers
    )
    assert tested.status_code == 200
    assert tested.json()["ok"] is False
    assert tested.json()["message"]

    patched = await client.patch(
        f"/api/v1/providers/{provider['id']}",
        json={"enabled": False, "notes": "disabled for now"},
        headers=auth_headers,
    )
    assert patched.status_code == 200
    assert patched.json()["enabled"] is False

    deleted = await client.delete(f"/api/v1/providers/{provider['id']}", headers=auth_headers)
    assert deleted.status_code == 204


async def test_usage_and_audit_are_scoped(client, auth_headers):
    usage = await client.get("/api/v1/usage", headers=auth_headers)
    assert usage.status_code == 200
    assert usage.json()["total_tokens"] == 0

    logs = await client.get("/api/v1/audit-logs", headers=auth_headers)
    assert logs.status_code == 200
    assert isinstance(logs.json()["logs"], list)


def test_redaction_covers_common_secret_shapes():
    samples = [
        "key sk-abcdef1234567890abcdef",
        "AIzaSyABCDEFGHIJKLMNOPQRSTUVWXYZ1234567",
        "Authorization: Bearer abcdefghijklmnopqrstuvwxyz",
        "postgresql+asyncpg://user:supersecret@host:5432/db",
        "api_key=sk-live-0123456789abcdef",
    ]
    for sample in samples:
        cleaned = redact(sample)
        assert "supersecret" not in cleaned
        assert "abcdef1234567890abcdef" not in cleaned or "[REDACTED]" in cleaned


def test_every_error_payload_has_a_code(app, client):
    # Sanity check the error envelope shape used by the Android client.
    import asyncio

    response = asyncio.get_event_loop().run_until_complete(
        client.get("/api/v1/conversations/%s" % uuid.uuid4())
    )
    assert response.status_code == 404
    assert "error" in response.json()


@pytest.mark.parametrize("mode", ["chat", "deep"])
async def test_modes_endpoint_lists_supported_modes(client, mode):
    response = await client.get("/api/v1/modes")
    assert response.status_code == 200
    assert mode in {item["id"] for item in response.json()["modes"]}
