"""Coding workspace: jailed file access, allowlisted commands, git helpers.

Security model:
* every path resolves inside `<workspace_root>/<user_id>/<project_id>` (no symlink
  escape, no `..`)
* commands never run through a shell; argv is split and validated against an
  allowlist and a deny list
* output is truncated and redacted before it leaves the process
* `IMPORT` of archives rejects zip-slip paths, oversized entries and symlinks
"""

from __future__ import annotations

import asyncio
import hashlib
import os
import re
import shlex
import shutil
import uuid
import zipfile
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from urllib.parse import urlparse

from app.core.config import Settings
from app.core.errors import APIError, bad_request, forbidden
from app.core.redaction import redact

IGNORED_DIRS = {
    ".git",
    "node_modules",
    ".gradle",
    "build",
    "dist",
    ".venv",
    "__pycache__",
    ".idea",
    ".next",
    "target",
    "Pods",
}
MAX_FILE_BYTES = 512 * 1024
MAX_TREE_ENTRIES = 4000
MAX_OUTPUT_BYTES = 120_000

ALLOWED_EXECUTABLES = {
    "python",
    "python3",
    "pytest",
    "pip",
    "pip3",
    "npm",
    "npx",
    "node",
    "yarn",
    "pnpm",
    "gradle",
    "./gradlew",
    "bash",
    "sh",
    "make",
    "cargo",
    "go",
    "flutter",
    "dart",
}
ALLOWED_GIT_SUBCOMMANDS = {
    "status",
    "diff",
    "log",
    "show",
    "add",
    "commit",
    "init",
    "branch",
    "checkout",
    "rev-parse",
    "ls-files",
}
DENIED_PATTERNS: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("absolute path escape", re.compile(r"(^|\s)(/etc|/usr|/bin|/sbin|~)(/|\s|$)")),
    ("privilege escalation", re.compile(r"\b(sudo|su|doas|pkexec)\b")),
    ("recursive delete", re.compile(r"\brm\s+(-[a-z]*r[a-z]*f?|-[a-z]*f[a-z]*r)\b")),
    ("network download", re.compile(r"\b(curl|wget|nc|netcat|ssh|scp|telnet)\b")),
    ("force push / publish", re.compile(r"git\s+push|npm\s+publish|twine\s+upload")),
    ("shell interpolation", re.compile(r"[`]|\$\(|<\(|>\(|&\s*$")),
    ("package install outside sandbox", re.compile(r"\bpip\s+install\s+--user\b")),
)

SANDBOX_IMAGE_COMMANDS = {"npm", "node", "python", "python3", "pytest", "gradle"}


@dataclass
class CommandResult:
    command: str
    exit_code: int
    stdout: str
    stderr: str
    duration_ms: int
    truncated: bool = False
    sandbox: str = "local"

    @property
    def ok(self) -> bool:
        return self.exit_code == 0


@dataclass
class FileEntry:
    path: str
    kind: str
    size: int


@dataclass
class SearchHit:
    path: str
    line: int
    text: str


@dataclass
class DiffResult:
    diff: str
    files_changed: list[str] = field(default_factory=list)


class WorkspaceManager:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.root = Path(settings.workspace_root).resolve()
        self.root.mkdir(parents=True, exist_ok=True)

    # ------------------------------------------------------------------- layout
    def project_root(self, user_id: uuid.UUID, project_id: uuid.UUID) -> Path:
        path = (self.root / str(user_id) / str(project_id)).resolve()
        if self.root not in path.parents and path != self.root:
            raise forbidden("Invalid workspace path.")
        path.mkdir(parents=True, exist_ok=True)
        return path

    def resolve(self, project_root: Path, relative: str) -> Path:
        candidate = (project_root / relative.lstrip("/")).resolve()
        if candidate != project_root and project_root not in candidate.parents:
            raise APIError(400, "path_escape", "That path is outside the project workspace.")
        return candidate

    # --------------------------------------------------------------------- read
    def list_tree(self, project_root: Path, relative: str = ".") -> list[FileEntry]:
        base = self.resolve(project_root, relative)
        if not base.exists():
            raise APIError(404, "path_not_found", f"{relative} does not exist.")
        entries: list[FileEntry] = []
        if base.is_file():
            return [FileEntry(path=base.name, kind="file", size=base.stat().st_size)]
        for path in sorted(base.rglob("*")):
            if any(part in IGNORED_DIRS for part in path.parts):
                continue
            rel = path.relative_to(project_root).as_posix()
            if path.is_dir():
                entries.append(FileEntry(path=rel, kind="dir", size=0))
            else:
                try:
                    entries.append(FileEntry(path=rel, kind="file", size=path.stat().st_size))
                except OSError:
                    continue
            if len(entries) >= MAX_TREE_ENTRIES:
                break
        return entries

    def read_text(self, project_root: Path, relative: str) -> str:
        path = self.resolve(project_root, relative)
        if not path.is_file():
            raise APIError(404, "file_not_found", f"{relative} is not a file.")
        size = path.stat().st_size
        if size > MAX_FILE_BYTES:
            raise APIError(413, "file_too_large", f"{relative} is {size} bytes; the limit is "
                           f"{MAX_FILE_BYTES}.")
        data = path.read_bytes()
        if b"\x00" in data[:4096]:
            raise APIError(415, "binary_file", f"{relative} looks like a binary file.")
        return data.decode("utf-8", "replace")

    def sha256(self, path: Path) -> str:
        digest = hashlib.sha256()
        with path.open("rb") as handle:
            for block in iter(lambda: handle.read(65536), b""):
                digest.update(block)
        return digest.hexdigest()

    # -------------------------------------------------------------------- write
    def write_text(self, project_root: Path, relative: str, content: str) -> dict:
        path = self.resolve(project_root, relative)
        path.parent.mkdir(parents=True, exist_ok=True)
        existed = path.exists()
        previous = path.read_text("utf-8", "replace") if existed else ""
        path.write_text(content, encoding="utf-8")
        return {
            "path": relative,
            "created": not existed,
            "bytes": len(content.encode("utf-8")),
            "sha256": self.sha256(path),
            "previous_bytes": len(previous.encode("utf-8")),
        }

    def replace_in_file(
        self, project_root: Path, relative: str, find: str, replace: str
    ) -> dict:
        if not find:
            raise bad_request("The search text for a patch cannot be empty.")
        original = self.read_text(project_root, relative)
        count = original.count(find)
        if count == 0:
            raise APIError(409, "patch_not_applied", "The search text was not found in the file.")
        updated = original.replace(find, replace)
        path = self.resolve(project_root, relative)
        path.write_text(updated, encoding="utf-8")
        return {"path": relative, "occurrences": count, "bytes": len(updated.encode("utf-8"))}

    def search(
        self, project_root: Path, query: str, *, max_hits: int = 40, suffix: str | None = None
    ) -> list[SearchHit]:
        needle = query.lower()
        hits: list[SearchHit] = []
        for path in sorted(project_root.rglob("*")):
            if not path.is_file() or any(part in IGNORED_DIRS for part in path.parts):
                continue
            if suffix and path.suffix != suffix:
                continue
            if path.stat().st_size > MAX_FILE_BYTES:
                continue
            try:
                text = path.read_text("utf-8", "replace")
            except OSError:
                continue
            if b"\x00" in text.encode("utf-8", "replace")[:2]:
                continue
            for number, line in enumerate(text.splitlines(), start=1):
                if needle in line.lower():
                    hits.append(
                        SearchHit(
                            path=path.relative_to(project_root).as_posix(),
                            line=number,
                            text=line.strip()[:240],
                        )
                    )
                    if len(hits) >= max_hits:
                        return hits
        return hits

    # ------------------------------------------------------------------ imports
    def extract_zip(self, project_root: Path, archive: Path, max_bytes: int = 64 * 1024 * 1024) -> int:
        extracted = 0
        with zipfile.ZipFile(archive) as zf:
            total = sum(info.file_size for info in zf.infolist())
            if total > max_bytes:
                raise APIError(413, "archive_too_large", "The archive expands beyond the limit.")
            for info in zf.infolist():
                if info.is_dir():
                    continue
                target = self.resolve(project_root, info.filename)
                if info.external_attr >> 16 & 0o170000 == 0o120000:
                    raise APIError(400, "archive_symlink", "Archives containing symlinks are rejected.")
                target.parent.mkdir(parents=True, exist_ok=True)
                with zf.open(info) as source, target.open("wb") as destination:
                    shutil.copyfileobj(source, destination, 1024 * 256)
                extracted += 1
        return extracted

    async def clone_github(
        self, project_root: Path, repo_url: str, *, branch: str | None = None, token: str | None = None
    ) -> dict:
        parsed = urlparse(repo_url)
        if parsed.scheme != "https" or parsed.hostname not in {"github.com", "www.github.com"}:
            raise bad_request("Only https://github.com/<owner>/<repo> URLs can be imported.")
        args = ["git", "clone", "--depth", "1"]
        if branch:
            if not re.fullmatch(r"[A-Za-z0-9._/\-]{1,120}", branch):
                raise bad_request("Invalid branch name.")
            args += ["--branch", branch]
        args += [repo_url, str(project_root)]
        env = self._clean_env()
        if token:
            import base64

            credential = base64.b64encode(f"x-access-token:{token}".encode()).decode()
            args = [args[0], "-c", f"http.extraheader=AUTHORIZATION: basic {credential}", *args[1:]]
        if project_root.exists() and any(project_root.iterdir()):
            raise APIError(409, "workspace_not_empty", "This project already has files.")
        process = await asyncio.create_subprocess_exec(
            *args,
            cwd=str(project_root.parent),
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            env=env,
        )
        try:
            stdout, stderr = await asyncio.wait_for(process.communicate(), timeout=180)
        except TimeoutError as exc:
            process.kill()
            raise APIError(504, "clone_timeout", "The repository clone timed out.") from exc
        if process.returncode != 0:
            message = redact((stderr or b"").decode("utf-8", "replace"))[:500]
            raise APIError(400, "clone_failed", f"Git clone failed: {message}")
        return {
            "cloned": True,
            "branch": branch,
            "output": redact((stdout or b"").decode("utf-8", "replace"))[-500:],
        }

    @staticmethod
    def _clean_env() -> dict[str, str]:
        keep = {"PATH", "HOME", "LANG", "LC_ALL", "TMPDIR", "ANDROID_HOME", "JAVA_HOME"}
        env = {key: value for key, value in os.environ.items() if key in keep}
        env["GIT_TERMINAL_PROMPT"] = "0"
        env["PYTHONDONTWRITEBYTECODE"] = "1"
        return env


class CommandRunner:
    """Runs allowlisted commands without a shell."""

    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.max_output = MAX_OUTPUT_BYTES
        self.timeout = settings.command_timeout_seconds

    @staticmethod
    def validate(command: str) -> list[str]:
        command = command.strip()
        if not command:
            raise bad_request("The command is empty.")
        for label, pattern in DENIED_PATTERNS:
            if pattern.search(command):
                raise APIError(403, "command_denied", f"Blocked by policy: {label}.")
        try:
            argv = shlex.split(command)
        except ValueError as exc:
            raise bad_request(f"Could not parse the command: {exc}") from exc
        if not argv:
            raise bad_request("The command is empty.")
        executable = argv[0]
        if executable == "git":
            if len(argv) < 2 or argv[1] not in ALLOWED_GIT_SUBCOMMANDS:
                raise APIError(
                    403,
                    "command_denied",
                    "That git subcommand is not allowed. Allowed: "
                    + ", ".join(sorted(ALLOWED_GIT_SUBCOMMANDS))
                    + ".",
                )
        elif executable not in ALLOWED_EXECUTABLES:
            raise APIError(
                403,
                "command_denied",
                f"'{executable}' is not on the allowlist. Allowed: "
                + ", ".join(sorted(ALLOWED_EXECUTABLES))
                + ".",
            )
        return argv

    @staticmethod
    def risk(command: str) -> str | None:
        """Return a human-readable reason when a command needs approval."""
        lowered = command.lower()
        if "git commit" in lowered:
            return "This records a commit in your project history."
        if any(token in lowered for token in (" rm ", " mv ", ">") ):
            return "This modifies files on disk."
        if any(token in lowered for token in ("install", "build", "test", "gradle", "npm run")):
            return "This runs a build/test process that can take a while and write build output."
        return None

    async def run(
        self,
        command: str,
        cwd: Path,
        *,
        timeout: int | None = None,
        sandbox: str = "local",
    ) -> CommandResult:
        argv = self.validate(command)
        if sandbox == "docker":
            argv = self._docker_argv(argv, cwd)
        started = datetime.now(UTC)
        process = await asyncio.create_subprocess_exec(
            *argv,
            cwd=str(cwd),
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            env=WorkspaceManager._clean_env(),
        )
        try:
            stdout, stderr = await asyncio.wait_for(
                process.communicate(), timeout=timeout or self.timeout
            )
        except TimeoutError:
            process.kill()
            await process.wait()
            return CommandResult(
                command=command,
                exit_code=124,
                stdout="",
                stderr=f"Command timed out after {timeout or self.timeout}s.",
                duration_ms=int((datetime.now(UTC) - started).total_seconds() * 1000),
                sandbox=sandbox,
            )
        out = stdout.decode("utf-8", "replace")
        err = stderr.decode("utf-8", "replace")
        truncated = len(out) + len(err) > self.max_output
        if truncated:
            out = out[: self.max_output // 2]
            err = err[: self.max_output // 2]
        return CommandResult(
            command=command,
            exit_code=process.returncode or 0,
            stdout=redact(out),
            stderr=redact(err),
            duration_ms=int((datetime.now(UTC) - started).total_seconds() * 1000),
            truncated=truncated,
            sandbox=sandbox,
        )

    def _docker_argv(self, argv: list[str], cwd: Path) -> list[str]:
        if argv[0] not in SANDBOX_IMAGE_COMMANDS:
            raise APIError(
                403,
                "command_denied",
                "In docker sandbox mode only build/test commands are allowed.",
            )
        return [
            "docker",
            "run",
            "--rm",
            "--network",
            "none",
            "--memory",
            "2g",
            "--cpus",
            "2",
            "-v",
            f"{cwd}:/workspace",
            "-w",
            "/workspace",
            self.settings.sandbox_docker_image,
            *argv,
        ]


class GitService:
    def __init__(self, runner: CommandRunner) -> None:
        self.runner = runner

    async def is_repo(self, cwd: Path) -> bool:
        if not (cwd / ".git").exists():
            return False
        result = await self.runner.run("git rev-parse --is-inside-work-tree", cwd, timeout=20)
        return result.ok

    async def status(self, cwd: Path) -> str:
        result = await self.runner.run("git status --porcelain=v1 --branch", cwd, timeout=30)
        return result.stdout

    async def diff(self, cwd: Path, *, staged: bool = False) -> DiffResult:
        command = "git diff --staged" if staged else "git diff"
        result = await self.runner.run(command, cwd, timeout=30)
        files = sorted(
            {
                line.removeprefix("+++ b/").strip()
                for line in result.stdout.splitlines()
                if line.startswith("+++ b/")
            }
        )
        return DiffResult(diff=result.stdout, files_changed=files)
