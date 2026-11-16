from __future__ import annotations

import io
import uuid
import zipfile
from pathlib import Path

import pytest

from app.core.errors import APIError
from app.services.coding import CommandRunner, WorkspaceManager
from app.services.model_gateway.router import ModelOption, select_model


def option(**overrides) -> ModelOption:
    base = dict(
        model_pk=uuid.uuid4(),
        provider_id=uuid.uuid4(),
        model_id="model-x",
        display_name="Model X",
        provider_name="Provider",
        provider_slug="provider",
        privacy_tier="cloud",
        has_credentials=True,
        supports_tools=True,
        supports_vision=False,
        supports_reasoning=False,
        supports_streaming=True,
        context_window=32000,
        availability="available",
        is_default=False,
        cost_tier="medium",
    )
    base.update(overrides)
    return ModelOption(**base)


def test_router_prefers_reasoning_model_for_deep_mode():
    plain = option(model_id="plain", is_default=True)
    thinker = option(model_id="thinker", supports_reasoning=True)
    decision = select_model([plain, thinker], mode="deep")
    assert decision.option is not None
    assert decision.option.model_id == "thinker"
    assert "deep" in decision.reason


def test_router_rejects_uncredentialed_and_unsupported_models():
    no_key = option(model_id="no-key", has_credentials=False)
    no_tools = option(model_id="no-tools", supports_tools=False)
    with pytest.raises(APIError) as no_key_error:
        select_model([no_key], mode="chat")
    assert no_key_error.value.code == "no_suitable_model"
    assert any("API key" in reason for reason in no_key_error.value.details["rejected"])

    with pytest.raises(APIError) as excinfo:
        select_model([no_tools], mode="agent")
    assert excinfo.value.code == "no_suitable_model"
    assert any("tool" in reason for reason in excinfo.value.details["rejected"])


def test_router_does_not_silently_replace_a_pinned_model():
    weak = option(model_id="weak", supports_reasoning=False)
    strong = option(model_id="strong", supports_reasoning=True)
    with pytest.raises(APIError) as excinfo:
        select_model(
            [weak, strong], mode="deep", preferred_model_pk=weak.model_pk, allow_fallback=False
        )
    assert excinfo.value.code == "model_capability_mismatch"
    # With fallback allowed it is explicit in the reason.
    decision = select_model([weak, strong], mode="deep", preferred_model_pk=weak.model_pk)
    assert decision.option.model_id == "strong"
    assert "could not satisfy" in decision.reason


def test_router_prefers_local_provider_when_asked():
    cloud = option(model_id="cloud", cost_tier="low")
    local = option(model_id="local", privacy_tier="local", cost_tier="free")
    decision = select_model([cloud, local], mode="chat", prefer_local=True)
    assert decision.option.model_id == "local"


@pytest.mark.parametrize(
    "command",
    [
        "rm -rf /",
        "sudo ls",
        "curl https://example.com",
        "git push origin main",
        "echo $(whoami)",
        "cat /etc/passwd",
    ],
)
def test_command_runner_denies_dangerous_commands(command):
    with pytest.raises(APIError):
        CommandRunner.validate(command)


@pytest.mark.parametrize("command", ["pytest -q", "python -m pytest", "git status", "npm test"])
def test_command_runner_allows_expected_commands(command):
    assert CommandRunner.validate(command)


def test_workspace_rejects_path_escape(settings, tmp_path):
    workspace = WorkspaceManager(settings)
    root = workspace.project_root(uuid.uuid4(), uuid.uuid4())
    with pytest.raises(APIError):
        workspace.resolve(root, "../../etc/passwd")
    (root / "safe.txt").write_text("ok")
    assert workspace.read_text(root, "safe.txt") == "ok"


def test_zip_import_rejects_traversal(settings):
    workspace = WorkspaceManager(settings)
    root = workspace.project_root(uuid.uuid4(), uuid.uuid4())
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr("../escaped.txt", "bad")
    temp = root.parent / "evil.zip"
    temp.write_bytes(buffer.getvalue())
    with pytest.raises(APIError):
        workspace.extract_zip(root, temp)
    assert not (root.parent.parent / "escaped.txt").exists()


async def test_project_zip_import_and_file_workflow(client, auth_headers):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr("README.md", "# Imported\n")
        archive.writestr("src/main.py", "print('hi')\n")
    response = await client.post(
        "/api/v1/projects/import/zip",
        data={"name": "Imported"},
        files={"file": ("project.zip", buffer.getvalue(), "application/zip")},
        headers=auth_headers,
    )
    assert response.status_code == 201, response.text
    project_id = response.json()["project"]["id"]
    assert response.json()["files_extracted"] == 2

    listing = await client.get(
        f"/api/v1/projects/{project_id}/files", headers=auth_headers
    )
    paths = {entry["path"] for entry in listing.json()["entries"]}
    assert {"README.md", "src/main.py"} <= paths

    content = await client.get(
        f"/api/v1/projects/{project_id}/file",
        params={"path": "README.md"},
        headers=auth_headers,
    )
    assert content.json()["content"].startswith("# Imported")

    # New projects are read-only by default.
    blocked = await client.put(
        f"/api/v1/projects/{project_id}/file",
        json={"path": "README.md", "content": "changed"},
        headers=auth_headers,
    )
    assert blocked.status_code == 409
    assert blocked.json()["error"]["code"] == "project_read_only"

    await client.patch(
        f"/api/v1/projects/{project_id}", json={"read_only": False}, headers=auth_headers
    )
    written = await client.put(
        f"/api/v1/projects/{project_id}/file",
        json={"path": "README.md", "content": "# Changed\n"},
        headers=auth_headers,
    )
    assert written.status_code == 200
    assert written.json()["created"] is False

    search = await client.post(
        f"/api/v1/projects/{project_id}/search",
        json={"query": "print", "suffix": ".py"},
        headers=auth_headers,
    )
    assert search.json()["hits"][0]["path"] == "src/main.py"


async def test_project_commands_run_and_are_audited(client, auth_headers):
    project = (
        await client.post("/api/v1/projects", json={"name": "Cmd"}, headers=auth_headers)
    ).json()
    await client.patch(
        f"/api/v1/projects/{project['id']}", json={"read_only": False}, headers=auth_headers
    )
    ok = await client.post(
        f"/api/v1/projects/{project['id']}/commands",
        json={"command": "python -c 'print(2+2)'"},
        headers=auth_headers,
    )
    assert ok.status_code == 200
    assert ok.json()["exit_code"] == 0
    assert "4" in ok.json()["stdout"]

    denied = await client.post(
        f"/api/v1/projects/{project['id']}/commands",
        json={"command": "curl https://example.com"},
        headers=auth_headers,
    )
    assert denied.status_code == 403
    assert denied.json()["error"]["code"] == "command_denied"

    logs = (await client.get("/api/v1/audit-logs", headers=auth_headers)).json()["logs"]
    assert any(log["action"] == "project.command" for log in logs)


async def test_project_github_import_validates_host(client, auth_headers):
    response = await client.post(
        "/api/v1/projects/import/github",
        json={"repo_url": "https://gitlab.com/some/repo"},
        headers=auth_headers,
    )
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "bad_request"


async def test_document_upload_limit_is_enforced(client, auth_headers, app):
    project = (
        await client.post("/api/v1/projects", json={"name": "Big"}, headers=auth_headers)
    ).json()
    payload = b"0" * (app.state.settings.max_upload_bytes + 1024)
    response = await client.post(
        "/api/v1/projects/import/zip",
        data={"name": "Too big"},
        files={"file": ("big.zip", payload, "application/zip")},
        headers=auth_headers,
    )
    assert response.status_code == 413
    assert response.json()["error"]["code"] == "upload_too_large"
    assert project["id"]


def test_workspace_ignores_dependency_directories(settings):
    workspace = WorkspaceManager(settings)
    root = workspace.project_root(uuid.uuid4(), uuid.uuid4())
    (root / "src").mkdir()
    (root / "src" / "app.py").write_text("x")
    (root / "node_modules").mkdir()
    (root / "node_modules" / "dep.js").write_text("y")
    paths = {entry.path for entry in workspace.list_tree(root)}
    assert "src/app.py" in paths
    assert all("node_modules" not in path for path in paths)
