from __future__ import annotations

import json

import pytest

from tests.fakes import FakeAdapter, default_responder, failing_responder


def parse_sse(text: str) -> list[dict]:
    events = []
    for block in text.split("\n\n"):
        for line in block.splitlines():
            if line.startswith("data: "):
                events.append(json.loads(line[6:]))
    return events


@pytest.fixture
def fake_gateway(monkeypatch):
    state = {"responder": default_responder, "adapters": []}

    def factory(provider, api_key, client, timeout=120.0):
        adapter = FakeAdapter(
            provider, api_key, client, timeout, responder=lambda req, idx: state["responder"](req, idx)
        )
        state["adapters"].append(adapter)
        return adapter

    monkeypatch.setattr("app.services.model_gateway.gateway.build_adapter", factory)
    return state


async def setup_fake_provider(client, headers) -> dict:
    created = await client.post(
        "/api/v1/providers",
        json={
            "name": "Fake Lab",
            "base_url": "https://fake.invalid/v1",
            "api_compat": "openai",
            "models": [
                {
                    "model_id": "fake-1",
                    "display_name": "Fake Model",
                    "context_window": 128000,
                    "max_output_tokens": 4096,
                    "supports_tools": True,
                    "supports_vision": True,
                    "supports_reasoning": True,
                }
            ],
        },
        headers=headers,
    )
    assert created.status_code == 201, created.text
    provider = created.json()
    await client.put(
        f"/api/v1/providers/{provider['id']}/credentials",
        json={"api_key": "sk-fake-1234567890abcdefghij"},
        headers=headers,
    )
    models = (await client.get("/api/v1/models", headers=headers)).json()["models"]
    model = next(item for item in models if item["provider_slug"] == provider["slug"])
    return {"provider": provider, "model": model}


async def test_chat_streams_persists_and_logs_usage(client, auth_headers, fake_gateway):
    await setup_fake_provider(client, auth_headers)
    conversation = await client.post(
        "/api/v1/conversations", json={"mode": "chat"}, headers=auth_headers
    )
    conversation_id = conversation.json()["id"]

    response = await client.post(
        f"/api/v1/conversations/{conversation_id}/stream",
        json={"content": "Hi there", "mode": "chat"},
        headers=auth_headers,
    )
    assert response.status_code == 200
    events = parse_sse(response.text)
    kinds = [event["type"] for event in events]
    assert kinds[0] == "meta"
    assert "delta" in kinds
    assert kinds[-1] == "done"
    meta = events[0]
    assert meta["provider"]["slug"] == "fake-lab" or meta["provider"]["name"] == "Fake Lab"
    assert meta["model"]["display_name"] == "Fake Model"
    assert meta["routing_reason"]
    assert "api_key" not in response.text

    detail = await client.get(f"/api/v1/conversations/{conversation_id}", headers=auth_headers)
    payload = detail.json()
    assert payload["conversation"]["title"] == "Hi there"
    assert [message["role"] for message in payload["messages"]] == ["user", "assistant"]
    assistant = payload["messages"][1]
    assert assistant["content"] == "Hello from the fake provider."
    assert assistant["status"] == "complete"

    usage = (await client.get("/api/v1/usage", headers=auth_headers)).json()
    assert usage["total_tokens"] == 19
    assert usage["by_feature"][0]["feature"] == "chat"

    # The stored history is what the next turn sends to the provider.
    second = await client.post(
        f"/api/v1/conversations/{conversation_id}/stream",
        json={"content": "And again"},
        headers=auth_headers,
    )
    assert "done" in [event["type"] for event in parse_sse(second.text)]
    assert len(fake_gateway["adapters"]) >= 1
    last_request = FakeAdapter.calls[-1]
    assert any("Hi there" in message.content for message in last_request.messages)


async def test_deep_mode_selects_a_reasoning_model(client, auth_headers, fake_gateway):
    await setup_fake_provider(client, auth_headers)
    conversation = await client.post(
        "/api/v1/conversations", json={"mode": "deep"}, headers=auth_headers
    )
    response = await client.post(
        f"/api/v1/conversations/{conversation.json()['id']}/stream",
        json={"content": "Compare two designs", "mode": "deep", "reasoning_effort": "high"},
        headers=auth_headers,
    )
    events = parse_sse(response.text)
    meta = events[0]
    assert meta["reasoning_effort"] == "high"
    assert any(event["type"] == "reasoning_progress" for event in events)
    # Raw reasoning text is never forwarded.
    assert all("internal" not in event.get("text", "") for event in events)
    assert events[-1]["type"] == "done"


async def test_pinned_model_without_capability_is_reported_not_switched(
    client, auth_headers, fake_gateway
):
    setup = await setup_fake_provider(client, auth_headers)
    model_id = setup["model"]["id"]
    # Turn off tools: deep mode does not need them, agent mode does.
    await client.patch(
        f"/api/v1/models/{model_id}", json={"supports_reasoning": False}, headers=auth_headers
    )
    conversation = await client.post(
        "/api/v1/conversations",
        json={"mode": "deep", "model_id": model_id},
        headers=auth_headers,
    )
    response = await client.post(
        f"/api/v1/conversations/{conversation.json()['id']}/stream",
        json={"content": "Think hard"},
        headers=auth_headers,
    )
    # The pinned model cannot satisfy deep mode and there is no fallback: 409, not a
    # silent substitution.
    assert response.status_code == 409
    assert response.json()["error"]["code"] in {"model_capability_mismatch", "no_suitable_model"}


async def test_edit_message_truncates_history(client, auth_headers, fake_gateway):
    await setup_fake_provider(client, auth_headers)
    conversation = (
        await client.post("/api/v1/conversations", json={}, headers=auth_headers)
    ).json()
    await client.post(
        f"/api/v1/conversations/{conversation['id']}/stream",
        json={"content": "original question"},
        headers=auth_headers,
    )
    messages = (
        await client.get(
            f"/api/v1/conversations/{conversation['id']}/messages", headers=auth_headers
        )
    ).json()["messages"]
    user_message = messages[0]
    edited = await client.post(
        f"/api/v1/conversations/{conversation['id']}/messages/{user_message['id']}/edit",
        json={"content": "edited question"},
        headers=auth_headers,
    )
    assert edited.status_code == 200
    after = (
        await client.get(
            f"/api/v1/conversations/{conversation['id']}/messages", headers=auth_headers
        )
    ).json()["messages"]
    assert len(after) == 2
    assert after[0]["content"] == "edited question"


async def test_provider_failure_is_reported_and_message_marked_failed(
    client, auth_headers, fake_gateway
):
    fake_gateway["responder"] = failing_responder
    await setup_fake_provider(client, auth_headers)
    conversation = (
        await client.post("/api/v1/conversations", json={}, headers=auth_headers)
    ).json()
    response = await client.post(
        f"/api/v1/conversations/{conversation['id']}/stream",
        json={"content": "will fail"},
        headers=auth_headers,
    )
    events = parse_sse(response.text)
    assert events[-1]["type"] == "error"
    assert "Upstream exploded" in events[-1]["message"]
    messages = (
        await client.get(
            f"/api/v1/conversations/{conversation['id']}/messages", headers=auth_headers
        )
    ).json()["messages"]
    assert messages[-1]["status"] == "failed"
    assert messages[-1]["content"] == "partial"


async def test_stream_requires_a_connected_model(client, auth_headers):
    conversation = (
        await client.post("/api/v1/conversations", json={}, headers=auth_headers)
    ).json()
    response = await client.post(
        f"/api/v1/conversations/{conversation['id']}/stream",
        json={"content": "hello"},
        headers=auth_headers,
    )
    assert response.status_code == 409
    assert response.json()["error"]["code"] == "no_suitable_model"
