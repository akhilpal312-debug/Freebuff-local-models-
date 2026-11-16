"""Built-in agent templates.

Templates are global rows (user_id is a synthetic zero UUID) cloned into a user's
account on first access; they are never run directly.
"""

from __future__ import annotations

import uuid

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.tables import Agent

TEMPLATE_OWNER = uuid.UUID(int=0)

TEMPLATES: tuple[dict, ...] = (
    {
        "name": "Coding Agent",
        "kind": "coding",
        "description": "Reads a project, edits files and verifies with real builds/tests.",
        "instructions": (
            "Inspect before editing. Make the smallest correct change. Show diffs. "
            "Never claim a build passed without the command output proving it."
        ),
        "permission_profile": "standard",
        "enabled_tools": [
            "files.list",
            "files.read",
            "files.search",
            "files.write",
            "files.patch",
            "git.status",
            "git.diff",
            "shell.run",
            "tests.run",
            "task.complete",
        ],
        "memory_scope": "project",
    },
    {
        "name": "Research Agent",
        "kind": "research",
        "description": "Searches the web, fetches pages and cites verified sources.",
        "instructions": (
            "Search first, then fetch and read. Cite only sources you actually fetched. "
            "State clearly when evidence is insufficient."
        ),
        "permission_profile": "read_only",
        "enabled_tools": ["web.search", "web.fetch", "doc.create", "memory.write", "task.complete"],
        "memory_scope": "project",
    },
    {
        "name": "Planning Agent",
        "kind": "planning",
        "description": "Turns a vague goal into an ordered, verifiable plan.",
        "instructions": (
            "Surface assumptions and risks. Prefer 3-6 concrete steps with acceptance checks."
        ),
        "permission_profile": "read_only",
        "enabled_tools": ["doc.create", "memory.write", "task.complete"],
        "memory_scope": "agent",
    },
    {
        "name": "Document Agent",
        "kind": "document",
        "description": "Writes and restructures Markdown documents.",
        "instructions": (
            "Write in clear prose, keep structure scannable, and stay faithful to the source."
        ),
        "permission_profile": "read_only",
        "enabled_tools": ["files.read", "doc.create", "memory.write", "task.complete"],
        "memory_scope": "agent",
    },
    {
        "name": "General Assistant",
        "kind": "general",
        "description": "Everyday assistant with read access to your workspace.",
        "instructions": "Be concise and accurate. Ask when the objective is ambiguous.",
        "permission_profile": "read_only",
        "enabled_tools": [
            "files.list",
            "files.read",
            "files.search",
            "web.search",
            "web.fetch",
            "memory.write",
            "doc.create",
            "task.complete",
        ],
        "memory_scope": "global",
    },
    {
        "name": "Project Manager Agent",
        "kind": "project_manager",
        "description": "Decomposes objectives and delegates focused sub-tasks.",
        "instructions": (
            "Split work into independent sub-objectives and delegate them. Integrate the "
            "results into one answer and list what remains unresolved."
        ),
        "permission_profile": "standard",
        "enabled_tools": [
            "files.list",
            "files.read",
            "files.search",
            "web.search",
            "web.fetch",
            "doc.create",
            "memory.write",
            "task.complete",
        ],
        "memory_scope": "project",
    },
)


async def seed_templates(session: AsyncSession) -> None:
    existing = set(
        (
            await session.scalars(
                select(Agent.name).where(
                    Agent.user_id == TEMPLATE_OWNER, Agent.is_template.is_(True)
                )
            )
        ).all()
    )
    for template in TEMPLATES:
        if template["name"] in existing:
            continue
        session.add(
            Agent(
                user_id=TEMPLATE_OWNER,
                kind=template["kind"],
                name=template["name"],
                description=template["description"],
                instructions=template["instructions"],
                permission_profile=template["permission_profile"],
                enabled_tools=template["enabled_tools"],
                memory_scope=template["memory_scope"],
                runtime="native",
                is_template=True,
            )
        )
    await session.flush()
