from __future__ import annotations

import asyncio
import json

import pytest
import websockets

from app.core.errors import APIError
from app.services.openclaw import OpenClawClient, normalise_url
from app.services.research import FetchedPage, SearchResult
from tests.fakes import FakeAdapter, default_responder


# --------------------------------------------------------------------- OpenClaw


def test_gateway_url_normalisation():
    assert normalise_url("https://gw.example.com") == "wss://gw.example.com"
    assert normalise_url("http://127.0.0.1:8787") == "ws://127.0.0.1:8787"
    assert normalise_url("ws://127.0.0.1:1/rpc") == "ws://127.0.0.1:1/rpc"
    with pytest.raises(APIError):
        normalise_url("ftp://gateway")
    with pytest.raises(APIError):
        normalise_url("")


async def _serve(handler):
    server = await websockets.serve(handler, "127.0.0.1", 0)
    port = server.sockets[0].getsockname()[1]
    return server, port


async def test_openclaw_handshake_against_a_real_websocket_gateway(settings):
    seen: dict = {}

    async def handler(websocket):
        await websocket.send(
            json.dumps(
                {
                    "type": "event",
                    "event": "connect.challenge",
                    "payload": {"nonce": "abc", "ts": 1737264000000},
                }
            )
        )
        frame = json.loads(await websocket.recv())
        seen.update(frame)
        await websocket.send(
            json.dumps(
                {
                    "type": "res",
                    "id": frame["id"],
                    "ok": True,
                    "payload": {
                        "protocolVersion": 4,
                        "server": {"version": "2026.1.0"},
                        "auth": {"scopes": ["operator.read", "operator.write"]},
                        "features": {"methods": ["agent", "sessions.list"]},
                    },
                }
            )
        )
        await websocket.wait_closed()

    server, port = await _serve(handler)
    try:
        client = OpenClawClient(settings)
        info = await client.probe(f"ws://127.0.0.1:{port}", "gateway-token", "device-1")
        assert info.ok is True
        assert info.protocol_version == 4
        assert "operator.write" in info.scopes
        assert info.methods == ["agent", "sessions.list"]
        # The client must echo the challenge timestamp and request operator scope.
        assert seen["method"] == "connect"
        params = seen["params"]
        assert params["role"] == "operator"
        assert params["auth"]["token"] == "gateway-token"
        assert params["device"]["signedAt"] == 1737264000000
        assert params["client"]["mode"] == "operator"
    finally:
        server.close()
        await server.wait_closed()


async def test_openclaw_pairing_required_is_surfaced(settings):
    async def handler(websocket):
        await websocket.send(
            json.dumps({"type": "event", "event": "connect.challenge", "payload": {"ts": 1}})
        )
        frame = json.loads(await websocket.recv())
        await websocket.send(
            json.dumps(
                {
                    "type": "res",
                    "id": frame["id"],
                    "ok": False,
                    "error": {
                        "code": "PAIRING_REQUIRED",
                        "message": "Approve this device",
                        "details": {"requestId": "pair_123"},
                    },
                }
            )
        )
        await websocket.wait_closed()

    server, port = await _serve(handler)
    try:
        info = await OpenClawClient(settings).probe(f"ws://127.0.0.1:{port}", None, "device-2")
        assert info.ok is False
        assert info.pairing_request_id == "pair_123"
        assert "pairing" in info.message.lower()
    finally:
        server.close()
        await server.wait_closed()


async def test_openclaw_unreachable_gateway_reports_error(settings):
    info = await OpenClawClient(settings).probe("ws://127.0.0.1:1", "token", "device-3")
    assert info.ok is False
    assert info.status == "error"
    assert info.message


async def test_openclaw_connection_endpoint_requires_reachable_gateway(client, auth_headers):
    response = await client.post(
        "/api/v1/runtime/connections",
        json={"gateway_url": "ws://127.0.0.1:1", "token": "tok"},
        headers=auth_headers,
    )
    assert response.status_code == 201
    payload = response.json()
    assert payload["probe"]["ok"] is False
    assert payload["connection"]["status"] in {"error", "pairing_required"}
    assert payload["connection"]["has_token"] is True
    # The gateway token itself is never returned.
    assert "tok" not in response.text.replace("token", "")

    runs = await client.post(
        "/api/v1/runtime/openclaw/runs",
        json={"objective": "hello"},
        headers=auth_headers,
    )
    assert runs.status_code == 400
    assert runs.json()["error"]["code"] == "bad_request"


# --------------------------------------------------------------------- research


@pytest.fixture
def fake_gateway(monkeypatch):
    def factory(provider, api_key, client, timeout=120.0):
        return FakeAdapter(provider, api_key, client, timeout, responder=default_responder)

    monkeypatch.setattr("app.services.model_gateway.gateway.build_adapter", factory)


async def test_research_reports_unverified_sources_honestly(
    client, auth_headers, app, monkeypatch
):
    service = app.state.container.research

    async def fake_search(query, limit=6):
        return [
            SearchResult(
                title="Example",
                url="https://example.com/a",
                snippet="snippet",
                provider="duckduckgo",
            )
        ]

    async def fake_fetch(url):
        return FetchedPage(url=url, ok=False, status=403, error="HTTP 403")

    monkeypatch.setattr(service, "search", fake_search)
    monkeypatch.setattr(service, "fetch_page", fake_fetch)

    response = await client.post(
        "/api/v1/research", json={"query": "state space models"}, headers=auth_headers
    )
    assert response.status_code == 200
    payload = response.json()
    assert payload["searched"] is True
    assert payload["providers"] == ["duckduckgo"]
    assert payload["sources"][0]["verified"] is False
    assert payload["synthesis"] == "extractive"
    assert payload["citations"] == []
    assert "not verified" in payload["report_md"]
    assert payload["errors"]


async def test_research_uses_the_model_when_sources_are_verified(
    client, auth_headers, app, monkeypatch, fake_gateway
):
    provider = (
        await client.post(
            "/api/v1/providers",
            json={
                "name": "Fake Lab",
                "base_url": "https://fake.invalid/v1",
                "api_compat": "openai",
                "models": [
                    {
                        "model_id": "fake-1",
                        "display_name": "Fake Model",
                        "supports_tools": True,
                        "context_window": 128000,
                    }
                ],
            },
            headers=auth_headers,
        )
    ).json()
    await client.put(
        f"/api/v1/providers/{provider['id']}/credentials",
        json={"api_key": "sk-fake-1234567890abcdefghij"},
        headers=auth_headers,
    )
    service = app.state.container.research

    async def fake_search(query, limit=6):
        return [
            SearchResult(
                title="Verified Source",
                url="https://example.com/paper",
                snippet="Snippet",
                provider="duckduckgo",
            )
        ]

    async def fake_fetch(url):
        return FetchedPage(
            url=url,
            ok=True,
            status=200,
            title="Verified Source",
            text="A long body of evidence about the topic. " * 10,
            published_at="2026-01-02",
        )

    monkeypatch.setattr(service, "search", fake_search)
    monkeypatch.setattr(service, "fetch_page", fake_fetch)

    response = await client.post(
        "/api/v1/research",
        json={"query": "evidence", "save_as_document": True},
        headers=auth_headers,
    )
    assert response.status_code == 200
    payload = response.json()
    assert payload["synthesis"] == "model"
    assert payload["model"] == "Fake Model"
    assert payload["sources"][0]["verified"] is True
    assert payload["document"]["kind"] == "report"

    documents = (await client.get("/api/v1/documents", headers=auth_headers)).json()["documents"]
    assert len(documents) == 1


async def test_research_blocks_private_hosts(app):
    page = await app.state.container.research.fetch_page("http://127.0.0.1:8000/internal")
    assert page.ok is False
    assert "not reachable" in (page.error or "")


async def test_openclaw_client_can_run_agent_events(settings):
    async def handler(websocket):
        await websocket.send(
            json.dumps({"type": "event", "event": "connect.challenge", "payload": {"ts": 5}})
        )
        connect = json.loads(await websocket.recv())
        await websocket.send(
            json.dumps({"type": "res", "id": connect["id"], "ok": True, "payload": {"protocolVersion": 4}})
        )
        request = json.loads(await websocket.recv())
        assert request["method"] == "agent"
        assert request["params"]["idempotencyKey"]
        await websocket.send(
            json.dumps(
                {
                    "type": "res",
                    "id": request["id"],
                    "ok": True,
                    "payload": {"status": "accepted", "runId": "run_1"},
                }
            )
        )
        await websocket.send(
            json.dumps(
                {
                    "type": "event",
                    "event": "agent",
                    "payload": {"delta": "Hello from the gateway"},
                }
            )
        )
        await websocket.send(
            json.dumps(
                {
                    "type": "res",
                    "id": request["id"],
                    "ok": True,
                    "payload": {"status": "completed", "summary": "done", "runId": "run_1"},
                }
            )
        )
        await websocket.wait_closed()

    server, port = await _serve(handler)
    try:
        client = OpenClawClient(settings)
        events = []
        async with asyncio.timeout(15):
            async for event in client.run_agent(
                url=f"ws://127.0.0.1:{port}",
                token="t",
                device_id="d",
                message="say hello",
                session_key="s1",
            ):
                events.append(event)
        kinds = [event["type"] for event in events]
        assert "connected" in kinds
        assert "delta" in kinds
        assert events[-1]["type"] == "done"
        assert events[-1]["summary"] == "done"
    finally:
        server.close()
        await server.wait_closed()
