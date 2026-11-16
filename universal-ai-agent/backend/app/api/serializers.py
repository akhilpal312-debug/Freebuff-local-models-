from __future__ import annotations

from datetime import datetime
from typing import Any

from app.db.tables import (
    Agent,
    AgentRun,
    Approval,
    Conversation,
    Document,
    Memory,
    Message,
    Project,
    RunEventRow,
    RuntimeConnection,
)


def _iso(value: datetime | None) -> str | None:
    return value.isoformat() if value else None


def conversation_out(row: Conversation, *, message_count: int | None = None) -> dict[str, Any]:
    payload = {
        "id": str(row.id),
        "title": row.title,
        "mode": row.mode,
        "model_id": str(row.model_pk) if row.model_pk else None,
        "project_id": str(row.project_id) if row.project_id else None,
        "pinned": row.pinned,
        "created_at": _iso(row.created_at),
        "updated_at": _iso(row.updated_at),
    }
    if message_count is not None:
        payload["message_count"] = message_count
    return payload


def message_out(row: Message) -> dict[str, Any]:
    return {
        "id": str(row.id),
        "conversation_id": str(row.conversation_id),
        "role": row.role,
        "content": row.content,
        "sequence": row.sequence,
        "status": row.status,
        "error": row.error,
        "model_id": str(row.model_pk) if row.model_pk else None,
        "attachments": row.attachments or [],
        "citations": row.citations or [],
        "parts": row.parts or [],
        "usage": {
            "prompt_tokens": row.prompt_tokens,
            "completion_tokens": row.completion_tokens,
        },
        "created_at": _iso(row.created_at),
    }


def agent_out(row: Agent) -> dict[str, Any]:
    return {
        "id": str(row.id),
        "kind": row.kind,
        "name": row.name,
        "description": row.description,
        "instructions": row.instructions,
        "model_id": str(row.model_pk) if row.model_pk else None,
        "runtime": row.runtime,
        "permission_profile": row.permission_profile,
        "memory_scope": row.memory_scope,
        "enabled_tools": row.enabled_tools or [],
        "project_id": str(row.project_id) if row.project_id else None,
        "is_template": row.is_template,
        "created_at": _iso(row.created_at),
        "updated_at": _iso(row.updated_at),
    }


def run_out(row: AgentRun) -> dict[str, Any]:
    return {
        "id": str(row.id),
        "objective": row.objective,
        "status": row.status,
        "runtime": row.runtime,
        "agent_id": str(row.agent_id) if row.agent_id else None,
        "project_id": str(row.project_id) if row.project_id else None,
        "conversation_id": str(row.conversation_id) if row.conversation_id else None,
        "plan": row.plan or [],
        "state": {
            key: value
            for key, value in (row.state or {}).items()
            if key in {"current_step", "observations", "permission_profile", "agent_kind", "delegated_from"}
        },
        "result": row.result,
        "error": row.error,
        "tokens_used": row.tokens_used,
        "started_at": _iso(row.started_at),
        "finished_at": _iso(row.finished_at),
        "created_at": _iso(row.created_at),
    }


def run_event_out(row: RunEventRow) -> dict[str, Any]:
    return {
        "seq": row.seq,
        "type": row.type,
        "payload": row.payload or {},
        "created_at": _iso(row.created_at),
    }


def approval_out(row: Approval) -> dict[str, Any]:
    return {
        "id": str(row.id),
        "run_id": str(row.run_id),
        "tool_name": row.tool_name,
        "arguments": row.arguments or {},
        "status": row.status,
        "reason": row.reason,
        "requested_at": _iso(row.requested_at),
        "decided_at": _iso(row.decided_at),
    }


def project_out(row: Project) -> dict[str, Any]:
    return {
        "id": str(row.id),
        "name": row.name,
        "description": row.description,
        "kind": row.kind,
        "source": row.source,
        "repo_url": row.repo_url,
        "default_branch": row.default_branch,
        "read_only": row.read_only,
        "created_at": _iso(row.created_at),
        "updated_at": _iso(row.updated_at),
    }


def document_out(row: Document) -> dict[str, Any]:
    return {
        "id": str(row.id),
        "title": row.title,
        "kind": row.kind,
        "content_md": row.content_md,
        "tags": row.tags or [],
        "project_id": str(row.project_id) if row.project_id else None,
        "source_url": row.source_url,
        "created_at": _iso(row.created_at),
        "updated_at": _iso(row.updated_at),
    }


def memory_out(row: Memory) -> dict[str, Any]:
    return {
        "id": str(row.id),
        "scope": row.scope,
        "scope_id": str(row.scope_id) if row.scope_id else None,
        "kind": row.kind,
        "content": row.content,
        "importance": row.importance,
        "created_at": _iso(row.created_at),
    }


def connection_out(row: RuntimeConnection, *, has_token: bool) -> dict[str, Any]:
    return {
        "id": str(row.id),
        "runtime": row.runtime,
        "label": row.label,
        "gateway_url": row.gateway_url,
        "device_id": row.device_id,
        "status": row.status,
        "protocol_version": row.protocol_version,
        "has_token": has_token,
        "last_checked_at": _iso(row.last_checked_at),
        "last_error": row.last_error,
        "created_at": _iso(row.created_at),
    }
