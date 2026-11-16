from __future__ import annotations

import asyncio
import json
from pathlib import Path

import pytest

from tests.fakes import FakeAdapter, default_responder, shell_responder, slow_responder


@pytest.fixture
def fake_gateway(monkeypatch):
    state = {"responder": default_responder}

    def factory(provider, api_key, client, timeout=120.0):
        return FakeAdapter(
            provider, api_key, client, timeout, responder=lambda req, idx: state["responder"](req, idx)
        )

    monkeypatch.setattr("app.services.model_gateway.gateway.build_adapter", factory)
    return state


async def setup_project_and_agent(client, headers, *, permission="standard", tools=None) -> dict:
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
                        "context_window": 128000,
                        "supports_tools": True,
                        "supports_reasoning": True,
                    }
                ],
            },
            headers=headers,
        )
    ).json()
    await client.put(
        f"/api/v1/providers/{provider['id']}/credentials",
        json={"api_key": "sk-fake-1234567890abcdefghij"},
        headers=headers,
    )
    project = (
        await client.post(
            "/api/v1/projects", json={"name": "Agent Project"}, headers=headers
        )
    ).json()
    await client.patch(
        f"/api/v1/projects/{project['id']}", json={"read_only": False}, headers=headers
    )
    agent = (
        await client.post(
            "/api/v1/agents",
            json={
                "name": "Test Agent",
                "kind": "coding",
                "permission_profile": permission,
                "enabled_tools": tools
                or ["files.write", "files.read", "shell.run", "tests.run"],
                "project_id": project["id"],
            },
            headers=headers,
        )
    ).json()
    return {"project": project, "agent": agent}


async def wait_for_run(client, headers, run_id: str, statuses: set[str], timeout=15.0) -> dict:
    deadline = asyncio.get_event_loop().time() + timeout
    last = None
    while asyncio.get_event_loop().time() < deadline:
        response = await client.get(f"/api/v1/runs/{run_id}", headers=headers)
        last = response.json()["run"]
        if last["status"] in statuses:
            return last
        await asyncio.sleep(0.1)
    raise AssertionError(f"run did not reach {statuses}; last={last}")


async def events_for(client, headers, run_id: str) -> list[dict]:
    response = await client.get(f"/api/v1/runs/{run_id}/events", headers=headers)
    events = []
    for block in response.text.split("\n\n"):
        for line in block.splitlines():
            if line.startswith("data: "):
                payload = line[6:]
                try:
                    parsed = json.loads(payload)
                except json.JSONDecodeError:
                    continue
                if "type" in parsed and "seq" in parsed:
                    events.append(parsed)
    return events


async def test_native_run_executes_plan_and_writes_a_file(
    client, auth_headers, fake_gateway, app
):
    setup = await setup_project_and_agent(client, auth_headers)
    created = await client.post(
        "/api/v1/runs",
        json={
            "objective": "Create notes.txt describing the project",
            "agent_id": setup["agent"]["id"],
        },
        headers=auth_headers,
    )
    assert created.status_code == 201, created.text
    run_id = created.json()["id"]

    run = await wait_for_run(client, auth_headers, run_id, {"succeeded", "failed"})
    assert run["status"] == "succeeded", run
    assert run["plan"][0]["title"] == "Create note"
    assert "notes.txt" in (run["result"] or "")

    events = await events_for(client, auth_headers, run_id)
    types = [event["type"] for event in events]
    assert "run_started" in types
    assert "plan" in types
    assert "tool_call" in types
    assert "tool_result" in types
    assert "run_completed" in types

    matches = list(Path(app.state.settings.workspace_root).rglob("notes.txt"))
    assert matches, "expected notes.txt under the workspace"
    assert "hello from the agent" in matches[0].read_text()


async def test_shell_command_requires_approval_then_runs(
    client, auth_headers, fake_gateway, app
):
    fake_gateway["responder"] = shell_responder
    setup = await setup_project_and_agent(client, auth_headers)
    created = await client.post(
        "/api/v1/runs",
        json={"objective": "Print 2+2", "agent_id": setup["agent"]["id"]},
        headers=auth_headers,
    )
    run_id = created.json()["id"]

    pending = await wait_for_run(client, auth_headers, run_id, {"awaiting_approval", "failed"})
    assert pending["status"] == "awaiting_approval", pending

    approvals = (await client.get("/api/v1/approvals", headers=auth_headers)).json()["approvals"]
    assert len(approvals) == 1
    approval = approvals[0]
    assert approval["tool_name"] == "shell.run"
    assert approval["status"] == "pending"

    decided = await client.post(
        f"/api/v1/approvals/{approval['id']}",
        json={"approved": True, "reason": "safe"},
        headers=auth_headers,
    )
    assert decided.status_code == 200
    assert decided.json()["status"] == "approved"

    finished = await wait_for_run(client, auth_headers, run_id, {"succeeded", "failed"})
    assert finished["status"] == "succeeded", finished
    events = await events_for(client, auth_headers, run_id)
    tool_results = [event for event in events if event["type"] == "tool_result"]
    assert tool_results
    assert "4" in tool_results[-1]["payload"]["result"]


async def test_run_can_be_cancelled(client, auth_headers, fake_gateway):
    fake_gateway["responder"] = slow_responder
    setup = await setup_project_and_agent(client, auth_headers)
    created = await client.post(
        "/api/v1/runs",
        json={"objective": "Do something long", "agent_id": setup["agent"]["id"]},
        headers=auth_headers,
    )
    run_id = created.json()["id"]
    await wait_for_run(client, auth_headers, run_id, {"running", "awaiting_approval"})
    await asyncio.sleep(0.2)

    cancelled = await client.post(f"/api/v1/runs/{run_id}/cancel", headers=auth_headers)
    assert cancelled.status_code == 200
    final = await wait_for_run(client, auth_headers, run_id, {"cancelled", "failed", "succeeded"})
    assert final["status"] == "cancelled", final
    events = await events_for(client, auth_headers, run_id)
    assert any(event["type"] == "run_cancelled" for event in events)


async def test_read_only_profile_cannot_write(client, auth_headers, fake_gateway, app):
    setup = await setup_project_and_agent(client, auth_headers, permission="read_only", tools=[])
    created = await client.post(
        "/api/v1/runs",
        json={"objective": "Write a file", "agent_id": setup["agent"]["id"]},
        headers=auth_headers,
    )
    run = await wait_for_run(client, auth_headers, created.json()["id"], {"succeeded", "failed"})
    # No write tool was offered, so nothing may be written.
    matches = list(Path(app.state.settings.workspace_root).rglob("notes.txt"))
    assert matches == []
    assert run["status"] in {"succeeded", "failed"}


async def test_unknown_run_is_not_found(client, auth_headers):
    missing = "00000000-0000-0000-0000-0000000000ff"
    assert (
        await client.get(f"/api/v1/runs/{missing}", headers=auth_headers)
    ).status_code == 404
    assert (
        await client.post(f"/api/v1/runs/{missing}/cancel", headers=auth_headers)
    ).status_code == 404

    # No model connected: creating a run is allowed, execution fails honestly.
    created = await client.post("/api/v1/runs", json={"objective": "x"}, headers=auth_headers)
    assert created.status_code == 201


async def test_templates_are_available(client, auth_headers):
    templates = (await client.get("/api/v1/agents/templates", headers=auth_headers)).json()[
        "templates"
    ]
    names = {template["name"] for template in templates}
    assert {"Coding Agent", "Research Agent", "Project Manager Agent"} <= names
    coding = next(template for template in templates if template["name"] == "Coding Agent")
    assert coding["permission_profile"] == "standard"
