from __future__ import annotations

import uuid
from pathlib import Path
from typing import Any

from fastapi import APIRouter, File, Form, UploadFile
from pydantic import BaseModel, Field
from sqlalchemy import func, select

from app.api.serializers import project_out
from app.core.errors import APIError, bad_request, not_found
from app.db.tables import Message, Project
from app.deps import Context
from app.services.audit import record_audit

router = APIRouter(tags=["projects"])

PROJECT_KINDS = {"generic", "android", "web", "python", "node"}


class ProjectCreate(BaseModel):
    name: str = Field(min_length=1, max_length=160)
    description: str | None = Field(default=None, max_length=1000)
    kind: str = "generic"
    read_only: bool = True


class ProjectUpdate(BaseModel):
    name: str | None = Field(default=None, max_length=160)
    description: str | None = None
    read_only: bool | None = None
    default_branch: str | None = None


class GithubImport(BaseModel):
    repo_url: str = Field(max_length=500)
    name: str | None = Field(default=None, max_length=160)
    branch: str | None = Field(default=None, max_length=120)
    kind: str = "generic"
    token: str | None = Field(
        default=None,
        max_length=500,
        description="Optional GitHub token for private repositories. It is used for the "
        "clone and never stored.",
    )


class FileWrite(BaseModel):
    path: str = Field(min_length=1, max_length=1000)
    content: str = Field(max_length=2 * 1024 * 1024)


class SearchRequest(BaseModel):
    query: str = Field(min_length=1, max_length=200)
    suffix: str | None = Field(default=None, max_length=20, pattern=r"^\.[A-Za-z0-9]+$")


class CommandRequest(BaseModel):
    command: str = Field(min_length=1, max_length=2000)
    timeout_seconds: int = Field(default=300, ge=5, le=1800)


async def _get_project(ctx: Context, project_id: uuid.UUID) -> Project:
    project = await ctx.db.scalar(
        select(Project).where(Project.id == project_id, Project.user_id == ctx.user_id)
    )
    if project is None:
        raise not_found("Project")
    return project


@router.get("/projects")
async def list_projects(ctx: Context) -> dict:
    rows = (
        await ctx.db.scalars(
            select(Project)
            .where(Project.user_id == ctx.user_id)
            .order_by(Project.updated_at.desc())
        )
    ).all()
    return {"projects": [project_out(row) for row in rows]}


@router.post("/projects", status_code=201)
async def create_project(payload: ProjectCreate, ctx: Context) -> dict:
    kind = payload.kind if payload.kind in PROJECT_KINDS else "generic"
    project = Project(
        user_id=ctx.user_id,
        name=payload.name[:160],
        description=payload.description,
        kind=kind,
        source="empty",
        read_only=payload.read_only,
    )
    ctx.db.add(project)
    await ctx.db.flush()
    ctx.container.workspace.project_root(ctx.user_id, project.id)
    await record_audit(
        ctx.db, user_id=ctx.user_id, action="project.create", target_id=str(project.id)
    )
    return project_out(project)


@router.get("/projects/{project_id}")
async def get_project(project_id: uuid.UUID, ctx: Context) -> dict:
    project = await _get_project(ctx, project_id)
    root = ctx.container.workspace.project_root(ctx.user_id, project.id)
    files = ctx.container.workspace.list_tree(root)
    return {"project": project_out(project), "file_count": len(files)}


@router.patch("/projects/{project_id}")
async def update_project(project_id: uuid.UUID, payload: ProjectUpdate, ctx: Context) -> dict:
    project = await _get_project(ctx, project_id)
    if payload.name is not None:
        project.name = payload.name[:160]
    if payload.description is not None:
        project.description = payload.description
    if payload.read_only is not None:
        project.read_only = payload.read_only
    if payload.default_branch is not None:
        project.default_branch = payload.default_branch
    await ctx.db.flush()
    return project_out(project)


@router.delete("/projects/{project_id}", status_code=204)
async def delete_project(project_id: uuid.UUID, ctx: Context) -> None:
    project = await _get_project(ctx, project_id)
    root = ctx.container.workspace.project_root(ctx.user_id, project.id)
    await ctx.db.delete(project)
    await ctx.db.flush()
    import shutil

    shutil.rmtree(root, ignore_errors=True)
    await record_audit(
        ctx.db, user_id=ctx.user_id, action="project.delete", target_id=str(project_id)
    )


@router.post("/projects/import/github", status_code=201)
async def import_github(payload: GithubImport, ctx: Context) -> dict:
    kind = payload.kind if payload.kind in PROJECT_KINDS else "generic"
    name = (payload.name or payload.repo_url.rstrip("/").split("/")[-1])[:160]
    project = Project(
        user_id=ctx.user_id,
        name=name,
        kind=kind,
        source="github",
        repo_url=payload.repo_url,
        default_branch=payload.branch,
        read_only=True,
    )
    ctx.db.add(project)
    await ctx.db.flush()
    root = ctx.container.workspace.project_root(ctx.user_id, project.id)
    try:
        result = await ctx.container.workspace.clone_github(
            root, payload.repo_url, branch=payload.branch, token=payload.token
        )
    except APIError:
        await ctx.db.rollback()
        import shutil

        shutil.rmtree(root, ignore_errors=True)
        raise
    await record_audit(
        ctx.db,
        user_id=ctx.user_id,
        action="project.import_github",
        target_id=str(project.id),
        detail={"repo": payload.repo_url, "branch": payload.branch},
    )
    return {"project": project_out(project), "clone": {"cloned": result["cloned"]}}


@router.post("/projects/import/zip", status_code=201)
async def import_zip(
    ctx: Context,
    name: str = Form(...),
    file: UploadFile = File(...),
) -> dict:
    filename = file.filename or "project.zip"
    if not filename.lower().endswith(".zip"):
        raise bad_request("Only .zip archives can be imported.")
    project = Project(
        user_id=ctx.user_id, name=name[:160], kind="generic", source="zip", read_only=True
    )
    ctx.db.add(project)
    await ctx.db.flush()
    root = ctx.container.workspace.project_root(ctx.user_id, project.id)
    temp_path = root.parent / f"{project.id}.zip"
    written = 0
    try:
        with temp_path.open("wb") as handle:
            while chunk := await file.read(1024 * 256):
                written += len(chunk)
                if written > ctx.container.settings.max_upload_bytes:
                    raise APIError(413, "upload_too_large", "The archive exceeds the upload limit.")
                handle.write(chunk)
        extracted = ctx.container.workspace.extract_zip(root, temp_path)
    except Exception:
        await ctx.db.rollback()
        temp_path.unlink(missing_ok=True)
        raise
    finally:
        temp_path.unlink(missing_ok=True)
    await record_audit(
        ctx.db,
        user_id=ctx.user_id,
        action="project.import_zip",
        target_id=str(project.id),
        detail={"files": extracted, "bytes": written},
    )
    return {"project": project_out(project), "files_extracted": extracted}


@router.get("/projects/{project_id}/files")
async def list_files(project_id: uuid.UUID, ctx: Context, path: str = ".") -> dict:
    project = await _get_project(ctx, project_id)
    root = ctx.container.workspace.project_root(ctx.user_id, project.id)
    entries = ctx.container.workspace.list_tree(root, path)
    return {
        "entries": [entry.__dict__ for entry in entries],
        "truncated": len(entries) >= 4000,
    }


@router.get("/projects/{project_id}/file")
async def read_file(project_id: uuid.UUID, ctx: Context, path: str) -> dict:
    project = await _get_project(ctx, project_id)
    root = ctx.container.workspace.project_root(ctx.user_id, project.id)
    content = ctx.container.workspace.read_text(root, path)
    return {"path": path, "content": content, "size": len(content.encode("utf-8"))}


@router.put("/projects/{project_id}/file")
async def write_file(project_id: uuid.UUID, payload: FileWrite, ctx: Context) -> dict:
    project = await _get_project(ctx, project_id)
    if project.read_only:
        raise APIError(
            409,
            "project_read_only",
            "This project is read-only. Disable read-only mode to edit files.",
        )
    root = ctx.container.workspace.project_root(ctx.user_id, project.id)
    result = ctx.container.workspace.write_text(root, payload.path, payload.content)
    await record_audit(
        ctx.db,
        user_id=ctx.user_id,
        action="project.write_file",
        target_id=str(project.id),
        detail={"path": payload.path, "bytes": result["bytes"]},
    )
    return result


@router.post("/projects/{project_id}/search")
async def search_project(project_id: uuid.UUID, payload: SearchRequest, ctx: Context) -> dict:
    project = await _get_project(ctx, project_id)
    root = ctx.container.workspace.project_root(ctx.user_id, project.id)
    hits = ctx.container.workspace.search(root, payload.query, suffix=payload.suffix)
    return {"hits": [hit.__dict__ for hit in hits]}


@router.get("/projects/{project_id}/git/status")
async def git_status(project_id: uuid.UUID, ctx: Context) -> dict:
    project = await _get_project(ctx, project_id)
    root = ctx.container.workspace.project_root(ctx.user_id, project.id)
    is_repo = await ctx.container.git.is_repo(root)
    if not is_repo:
        return {"is_repo": False, "status": ""}
    return {"is_repo": True, "status": await ctx.container.git.status(root)}


@router.get("/projects/{project_id}/git/diff")
async def git_diff(project_id: uuid.UUID, ctx: Context, staged: bool = False) -> dict:
    project = await _get_project(ctx, project_id)
    root = ctx.container.workspace.project_root(ctx.user_id, project.id)
    if not await ctx.container.git.is_repo(root):
        return {"is_repo": False, "diff": "", "files_changed": []}
    diff = await ctx.container.git.diff(root, staged=staged)
    return {"is_repo": True, "diff": diff.diff, "files_changed": diff.files_changed}


@router.post("/projects/{project_id}/commands")
async def run_command(project_id: uuid.UUID, payload: CommandRequest, ctx: Context) -> dict:
    project = await _get_project(ctx, project_id)
    if project.read_only:
        raise APIError(
            409,
            "project_read_only",
            "This project is read-only. Disable read-only mode to run commands.",
        )
    root = ctx.container.workspace.project_root(ctx.user_id, project.id)
    result = await ctx.container.runner.run(
        payload.command, root, timeout=payload.timeout_seconds, sandbox=ctx.container.settings.sandbox_mode
    )
    await record_audit(
        ctx.db,
        user_id=ctx.user_id,
        action="project.command",
        target_id=str(project.id),
        success=result.ok,
        detail={"command": payload.command, "exit_code": result.exit_code},
    )
    return {
        "command": result.command,
        "exit_code": result.exit_code,
        "ok": result.ok,
        "stdout": result.stdout,
        "stderr": result.stderr,
        "duration_ms": result.duration_ms,
        "truncated": result.truncated,
        "sandbox": result.sandbox,
    }


@router.get("/projects/{project_id}/summary")
async def project_summary(project_id: uuid.UUID, ctx: Context) -> dict:
    project = await _get_project(ctx, project_id)
    root = ctx.container.workspace.project_root(ctx.user_id, project.id)
    entries = ctx.container.workspace.list_tree(root)
    files = [entry for entry in entries if entry.kind == "file"]
    by_suffix: dict[str, int] = {}
    for entry in files:
        suffix = Path(entry.path).suffix or "(none)"
        by_suffix[suffix] = by_suffix.get(suffix, 0) + 1
    messages = await ctx.db.scalar(
        select(func.count(Message.id)).where(Message.user_id == ctx.user_id)
    )
    return {
        "project": project_out(project),
        "files": len(files),
        "directories": len(entries) - len(files),
        "bytes": sum(entry.size for entry in files),
        "by_suffix": dict(sorted(by_suffix.items(), key=lambda item: -item[1])[:12]),
        "has_git": (root / ".git").exists(),
        "conversation_messages": int(messages or 0),
    }
