"""Typed agent tools with permission profiles.

Every tool validates its own arguments. Tools that touch the filesystem are
confined to the project workspace; tools that run commands go through
`CommandRunner` (allowlist + approval). Nothing has access to the device, the
host filesystem outside the workspace, or the backend's secrets.
"""

from __future__ import annotations

import json
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings
from app.core.errors import APIError, bad_request
from app.db.tables import Document, Memory
from app.services.coding import CommandRunner, GitService, WorkspaceManager
from app.services.providers.base import ToolSpec

PROFILE_TOOLS: dict[str, set[str]] = {
    "read_only": {
        "files.list",
        "files.read",
        "files.search",
        "git.status",
        "git.diff",
        "web.search",
        "web.fetch",
        "memory.write",
        "doc.create",
        "task.complete",
    },
    "standard": {
        "files.list",
        "files.read",
        "files.search",
        "files.write",
        "files.patch",
        "git.status",
        "git.diff",
        "web.search",
        "web.fetch",
        "shell.run",
        "tests.run",
        "memory.write",
        "doc.create",
        "task.complete",
    },
    "elevated": {
        "files.list",
        "files.read",
        "files.search",
        "files.write",
        "files.patch",
        "git.status",
        "git.diff",
        "git.commit",
        "web.search",
        "web.fetch",
        "shell.run",
        "tests.run",
        "memory.write",
        "doc.create",
        "task.complete",
    },
}

APPROVAL_REQUIRED = {"shell.run", "tests.run", "git.commit"}


@dataclass
class ToolContext:
    user_id: uuid.UUID
    session: AsyncSession
    settings: Settings
    permission_profile: str = "read_only"
    project_root: Path | None = None
    project_id: uuid.UUID | None = None
    research: Any = None  # ResearchService, injected to avoid a circular import
    agent_name: str = "Universal Agent"


@dataclass
class ToolResult:
    ok: bool
    summary: str
    data: dict[str, Any] = field(default_factory=dict)
    output: str = ""

    def to_message(self) -> str:
        payload = {"ok": self.ok, "summary": self.summary, "data": self.data}
        if self.output:
            payload["output"] = self.output[:6000]
        return json.dumps(payload, ensure_ascii=False, default=str)


TOOL_SPECS: dict[str, ToolSpec] = {
    "files.list": ToolSpec(
        name="files.list",
        description="List files in the project workspace.",
        parameters={
            "type": "object",
            "properties": {"path": {"type": "string", "description": "Relative directory"}},
            "required": [],
        },
    ),
    "files.read": ToolSpec(
        name="files.read",
        description="Read a UTF-8 text file from the project workspace.",
        parameters={
            "type": "object",
            "properties": {"path": {"type": "string"}},
            "required": ["path"],
        },
    ),
    "files.search": ToolSpec(
        name="files.search",
        description="Search project files for a case-insensitive substring.",
        parameters={
            "type": "object",
            "properties": {
                "query": {"type": "string"},
                "suffix": {"type": "string", "description": "Optional file suffix like .kt"},
            },
            "required": ["query"],
        },
    ),
    "files.write": ToolSpec(
        name="files.write",
        description="Create or overwrite a file with the given content.",
        parameters={
            "type": "object",
            "properties": {"path": {"type": "string"}, "content": {"type": "string"}},
            "required": ["path", "content"],
        },
    ),
    "files.patch": ToolSpec(
        name="files.patch",
        description="Replace an exact substring in an existing file.",
        parameters={
            "type": "object",
            "properties": {
                "path": {"type": "string"},
                "find": {"type": "string"},
                "replace": {"type": "string"},
            },
            "required": ["path", "find", "replace"],
        },
    ),
    "git.status": ToolSpec(
        name="git.status",
        description="Show the porcelain git status of the project.",
        parameters={"type": "object", "properties": {}},
    ),
    "git.diff": ToolSpec(
        name="git.diff",
        description="Show the current git diff.",
        parameters={
            "type": "object",
            "properties": {"staged": {"type": "boolean"}},
        },
    ),
    "git.commit": ToolSpec(
        name="git.commit",
        description="Stage all changes and create a commit. Requires approval.",
        parameters={
            "type": "object",
            "properties": {"message": {"type": "string"}},
            "required": ["message"],
        },
    ),
    "web.search": ToolSpec(
        name="web.search",
        description="Search the web and return titles, URLs and snippets.",
        parameters={
            "type": "object",
            "properties": {"query": {"type": "string"}},
            "required": ["query"],
        },
    ),
    "web.fetch": ToolSpec(
        name="web.fetch",
        description="Fetch a web page and return its readable text.",
        parameters={
            "type": "object",
            "properties": {"url": {"type": "string"}},
            "required": ["url"],
        },
    ),
    "shell.run": ToolSpec(
        name="shell.run",
        description=(
            "Run an allowlisted command in the project workspace. Requires user approval. "
            "Use for builds and scripts, never for network downloads."
        ),
        parameters={
            "type": "object",
            "properties": {"command": {"type": "string"}},
            "required": ["command"],
        },
    ),
    "tests.run": ToolSpec(
        name="tests.run",
        description="Run the project's test command. Requires user approval.",
        parameters={
            "type": "object",
            "properties": {"command": {"type": "string"}},
            "required": ["command"],
        },
    ),
    "memory.write": ToolSpec(
        name="memory.write",
        description="Store a durable fact for this user or project.",
        parameters={
            "type": "object",
            "properties": {
                "content": {"type": "string"},
                "kind": {"type": "string", "enum": ["fact", "preference", "decision"]},
            },
            "required": ["content"],
        },
    ),
    "doc.create": ToolSpec(
        name="doc.create",
        description="Create a knowledge-base document from Markdown.",
        parameters={
            "type": "object",
            "properties": {"title": {"type": "string"}, "content_md": {"type": "string"}},
            "required": ["title", "content_md"],
        },
    ),
    "task.complete": ToolSpec(
        name="task.complete",
        description="Mark the current step complete and provide the result.",
        parameters={
            "type": "object",
            "properties": {"summary": {"type": "string"}},
            "required": ["summary"],
        },
    ),
}


def tools_for(profile: str, enabled_tools: list[str] | None) -> list[ToolSpec]:
    allowed = PROFILE_TOOLS.get(profile, PROFILE_TOOLS["read_only"])
    names = allowed if not enabled_tools else allowed.intersection(enabled_tools)
    return [TOOL_SPECS[name] for name in sorted(names) if name in TOOL_SPECS]


def needs_approval(tool_name: str, arguments: dict[str, Any]) -> str | None:
    if tool_name not in APPROVAL_REQUIRED:
        return None
    if tool_name == "git.commit":
        return "A commit will be written to the project repository."
    command = str(arguments.get("command") or "")
    return CommandRunner.risk(command) or f"'{command}' will run in the project workspace."


class ToolExecutor:
    def __init__(self, workspace: WorkspaceManager, runner: CommandRunner, git: GitService) -> None:
        self.workspace = workspace
        self.runner = runner
        self.git = git

    def _require_project(self, ctx: ToolContext) -> Path:
        if ctx.project_root is None:
            raise bad_request("This tool needs a project. Select or create a project first.")
        return ctx.project_root

    async def execute(
        self, name: str, arguments: dict[str, Any], ctx: ToolContext
    ) -> ToolResult:
        allowed = PROFILE_TOOLS.get(ctx.permission_profile, PROFILE_TOOLS["read_only"])
        if name not in allowed:
            raise APIError(
                403,
                "tool_not_permitted",
                f"Tool '{name}' is not permitted by the {ctx.permission_profile} profile.",
            )
        handler = getattr(self, f"_tool_{name.replace('.', '_')}", None)
        if handler is None:
            raise APIError(400, "unknown_tool", f"Unknown tool '{name}'.")
        try:
            return await handler(arguments, ctx)
        except APIError:
            raise
        except (ValueError, KeyError, TypeError) as exc:
            return ToolResult(ok=False, summary=f"Invalid arguments for {name}: {exc}")

    # ------------------------------------------------------------------ files
    async def _tool_files_list(self, args: dict, ctx: ToolContext) -> ToolResult:
        root = self._require_project(ctx)
        entries = self.workspace.list_tree(root, str(args.get("path") or "."))
        listing = [f"{e.kind[0]} {e.path}" + (f" ({e.size}B)" if e.kind == "file" else "") for e in entries]
        return ToolResult(
            ok=True,
            summary=f"{len(entries)} entries.",
            data={"entries": [entry.__dict__ for entry in entries[:400]]},
            output="\n".join(listing[:400]),
        )

    async def _tool_files_read(self, args: dict, ctx: ToolContext) -> ToolResult:
        root = self._require_project(ctx)
        path = str(args["path"])
        content = self.workspace.read_text(root, path)
        return ToolResult(
            ok=True,
            summary=f"Read {path} ({len(content)} chars).",
            data={"path": path},
            output=content,
        )

    async def _tool_files_search(self, args: dict, ctx: ToolContext) -> ToolResult:
        root = self._require_project(ctx)
        hits = self.workspace.search(
            root, str(args["query"]), suffix=args.get("suffix") or None
        )
        rendered = "\n".join(f"{hit.path}:{hit.line}: {hit.text}" for hit in hits)
        return ToolResult(
            ok=True,
            summary=f"{len(hits)} matches.",
            data={"hits": [hit.__dict__ for hit in hits]},
            output=rendered,
        )

    async def _tool_files_write(self, args: dict, ctx: ToolContext) -> ToolResult:
        root = self._require_project(ctx)
        path = str(args["path"])
        content = str(args["content"])
        if len(content.encode("utf-8")) > 2 * 1024 * 1024:
            raise APIError(413, "file_too_large", "Refusing to write more than 2 MiB in one call.")
        info = self.workspace.write_text(root, path, content)
        return ToolResult(
            ok=True,
            summary=f"{'Created' if info['created'] else 'Updated'} {path}.",
            data=info,
        )

    async def _tool_files_patch(self, args: dict, ctx: ToolContext) -> ToolResult:
        root = self._require_project(ctx)
        info = self.workspace.replace_in_file(
            root, str(args["path"]), str(args["find"]), str(args.get("replace") or "")
        )
        return ToolResult(
            ok=True,
            summary=f"Patched {info['path']} ({info['occurrences']} replacement(s)).",
            data=info,
        )

    # -------------------------------------------------------------------- git
    async def _tool_git_status(self, args: dict, ctx: ToolContext) -> ToolResult:
        root = self._require_project(ctx)
        status = await self.git.status(root)
        return ToolResult(ok=True, summary="Git status.", output=status or "(clean)")

    async def _tool_git_diff(self, args: dict, ctx: ToolContext) -> ToolResult:
        root = self._require_project(ctx)
        diff = await self.git.diff(root, staged=bool(args.get("staged")))
        return ToolResult(
            ok=True,
            summary=f"{len(diff.files_changed)} file(s) changed.",
            data={"files": diff.files_changed},
            output=diff.diff or "(no changes)",
        )

    async def _tool_git_commit(self, args: dict, ctx: ToolContext) -> ToolResult:
        root = self._require_project(ctx)
        message = str(args.get("message") or "").strip()
        if not message:
            raise bad_request("A commit message is required.")
        await self.runner.run("git add -A", root, timeout=30)
        result = await self.runner.run(f'git commit -m {json.dumps(message)}', root, timeout=60)
        return ToolResult(
            ok=result.ok,
            summary="Commit created." if result.ok else "Commit failed.",
            data={"exit_code": result.exit_code},
            output=(result.stdout + result.stderr)[:4000],
        )

    # -------------------------------------------------------------------- web
    async def _tool_web_search(self, args: dict, ctx: ToolContext) -> ToolResult:
        if ctx.research is None:
            raise APIError(503, "research_unavailable", "Web search is not configured.")
        results = await ctx.research.search(str(args["query"]), limit=6)
        lines = [f"{i + 1}. {r.title} — {r.url}\n   {r.snippet}" for i, r in enumerate(results)]
        return ToolResult(
            ok=True,
            summary=f"{len(results)} results.",
            data={"results": [r.__dict__ for r in results]},
            output="\n".join(lines) or "No results.",
        )

    async def _tool_web_fetch(self, args: dict, ctx: ToolContext) -> ToolResult:
        if ctx.research is None:
            raise APIError(503, "research_unavailable", "Web fetch is not configured.")
        page = await ctx.research.fetch_page(str(args["url"]))
        return ToolResult(
            ok=page.ok,
            summary=f"Fetched {page.url} ({page.status}).",
            data={"url": page.url, "status": page.status, "title": page.title},
            output=page.text[:8000],
        )

    # ---------------------------------------------------------------- commands
    async def _tool_shell_run(self, args: dict, ctx: ToolContext) -> ToolResult:
        root = self._require_project(ctx)
        command = str(args["command"])
        result = await self.runner.run(
            command, root, sandbox=ctx.settings.sandbox_mode
        )
        return ToolResult(
            ok=result.ok,
            summary=f"'{command}' exited {result.exit_code} in {result.duration_ms}ms.",
            data={
                "exit_code": result.exit_code,
                "duration_ms": result.duration_ms,
                "truncated": result.truncated,
            },
            output=(result.stdout + ("\n" + result.stderr if result.stderr else ""))[:8000],
        )

    async def _tool_tests_run(self, args: dict, ctx: ToolContext) -> ToolResult:
        return await self._tool_shell_run(args, ctx)

    # --------------------------------------------------------------- knowledge
    async def _tool_memory_write(self, args: dict, ctx: ToolContext) -> ToolResult:
        content = str(args.get("content") or "").strip()
        if not content:
            raise bad_request("Memory content cannot be empty.")
        ctx.session.add(
            Memory(
                user_id=ctx.user_id,
                scope="project" if ctx.project_id else "global",
                scope_id=ctx.project_id,
                kind=str(args.get("kind") or "fact"),
                content=content[:4000],
            )
        )
        await ctx.session.flush()
        return ToolResult(ok=True, summary="Memory stored.")

    async def _tool_doc_create(self, args: dict, ctx: ToolContext) -> ToolResult:
        title = str(args.get("title") or "").strip()
        content = str(args.get("content_md") or "")
        if not title:
            raise bad_request("A document title is required.")
        document = Document(
            user_id=ctx.user_id,
            project_id=ctx.project_id,
            title=title[:240],
            kind="doc",
            content_md=content,
            tags=["agent"],
        )
        ctx.session.add(document)
        await ctx.session.flush()
        return ToolResult(
            ok=True, summary=f"Created document '{title}'.", data={"document_id": str(document.id)}
        )

    async def _tool_task_complete(self, args: dict, ctx: ToolContext) -> ToolResult:
        return ToolResult(ok=True, summary=str(args.get("summary") or "Step complete."))
