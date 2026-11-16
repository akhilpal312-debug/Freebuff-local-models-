# Architecture

## Shape of the system

```
Android app (Kotlin/Compose)
  │  HTTPS, no login (single workspace)
  ▼
FastAPI backend (local or Vercel)
  ├── identity: fixed owner from SINGLE_USER_ID, no tokens
  ├── services: chat, providers, model gateway, agents, projects, research, knowledge
  ├── adapters: openai-compatible / anthropic / gemini
  ├── runtime: native Python engine, OpenClaw WS adapter
  └── Postgres (Neon or any Postgres) via SQLAlchemy 2 async
        ├── every row carries user_id = the owner id
        ├── provider_secrets (Fernet ciphertext)
        └── run_events (append-only, monotonic seq per run)
```

The backend is the only component that holds provider credentials. The app knows only
the backend URL, stored in local preferences; provider keys never leave the server.
Because there is no authentication, the deployment URL is the access control — see
[SECURITY.md](SECURITY.md).

## Provider adapters

Providers do not share one API shape, so there are three adapters behind one interface:

- `openai_compat` — OpenAI, NVIDIA NIM, OpenRouter, Ollama, and custom gateways that
  speak `/chat/completions`. Streaming uses SSE deltas; tool calls use the OpenAI
  function schema.
- `anthropic` — `/v1/messages`, `system` as a separate field, tool_use/tool_result
  content blocks, `input_schema` tools.
- `gemini` — `:generateContent` / `:streamGenerateContent`, `contents` with parts,
  `functionDeclarations`, and `systemInstruction`.

Each adapter normalises to the same internal request/response types, including tool
calls, usage counters and finish reasons. Attachment support is declared per model
(`supports_vision`) and checked before routing.

## Model routing

Routing is deterministic and explainable:

1. A pinned model is honoured if it satisfies the request; otherwise the request fails
   with `409 model_capability_mismatch` instead of silently switching.
2. Otherwise candidates are filtered by required capabilities (tools, vision, reasoning,
   context length), privacy tier and provider state.
3. Remaining candidates are ordered by cost tier, then by availability, then by a stable
   name ordering, so the same inputs always select the same model.
4. The chosen model and a human-readable reason are returned in the SSE `meta` frame and
   logged with the message.

Deep mode additionally requires `supports_reasoning` and passes a reasoning effort hint.
Providers that ignore it still answer; the routing decision remains visible.

## Chat streaming

`POST /conversations/{id}/stream` returns `text/event-stream`:

```
event: meta     data: {model, provider, routing_reason, …}
event: delta    data: {"text": "…"}          (repeated)
event: reasoning_progress                    (deep mode, optional)
event: done     data: {message_id, latency_ms}
event: error    data: {message, code}
```

The client parses frames with `SseParser` and renders deltas as they arrive. The user
message is persisted before generation starts; the assistant message is persisted with
its final text, usage and citations when the stream completes. A disconnected client
causes the generator to be cancelled, and the assistant message is marked `cancelled`
rather than left `streaming`.

Regenerate deletes the last assistant message and re-runs it against the same user
input. Edit replaces the content of a user message, drops later messages and regenerate
from there. Both reuse the same streaming contract.

## Agent runtime

A run is a row plus an ordered event log.

```
queued → running → (awaiting_approval → running)* → succeeded | failed | cancelled
```

1. The orchestrator writes `run_started`.
2. A planning call produces steps (title, detail, optional tool). Planning is best-effort:
   if the model cannot produce a valid plan, a single-step plan is used and a
   `planning_degraded` event records why.
3. For each step the orchestrator runs a bounded tool loop. The model may call tools; each
   call is executed through the registry, which enforces the agent's enabled tools and the
   permission profile.
4. Destructive or sensitive tools (`shell.run`, file writes outside read-only projects,
   git operations) create a pending approval row and the run moves to `awaiting_approval`.
   The app shows the tool, arguments and reason; approving resumes the loop, rejecting
   records a refusal the model can see.
5. Every event is appended with `seq = max(seq)+1` by the single writer (the orchestrator).
   The client polls `GET /runs/{id}/events?after=n`, so a reconnect never duplicates or
   skips events.
6. Cancellation updates the run row explicitly and stops further tool calls.

The OpenClaw runtime implements the same run lifecycle from the client side: connect,
optional challenge echo, `connect` frame with client/role/scopes/device, then
`req`/`res` frames. `PAIRING_REQUIRED` responses surface a request id for the operator to
approve; `agent` calls carry an idempotency key so retries cannot double-execute.

## Projects and the sandbox

A project is a directory under `WORKSPACE_ROOT`. Every path is resolved and checked to
stay inside that directory (`../` escapes, absolute paths and symlink traversal are
rejected). Imports are limited to public GitHub clones and zips; zip entries are checked
for zip-slip before extraction, and uploads are capped by `MAX_UPLOAD_BYTES`.

Commands run through a fixed allowlist (`git`, `ls`, `cat`, `grep`, `rg`, `python`,
`pytest`, `npm`, `node`, …), with a timeout and output truncation. `SANDBOX_MODE=local`
runs them in the jailed directory; `docker` mode runs them in a throwaway container when
a Docker daemon is available. New projects are `read_only = true`; writes and commands
then require approval.

## Research

Research is a pipeline, not a single prompt:

1. Query expansion is optional; searches go to Tavily when a key is configured, and to
   DuckDuckGo HTML and Wikipedia otherwise.
2. Candidate pages are fetched with limits, text is extracted, and each source is marked
   `verified` only when it was actually retrieved and its content matched the query.
3. A synthesis call writes the report. Citations come from the verified source list, not
   from the model's imagination.
4. If no search ran, the API returns `searched: false` with an explanation, and the client
   labels the report as having no web evidence.

## Data model

| Table | Purpose |
| --- | --- |
| `profiles` | One row for the workspace owner, default model, display name |
| `conversations`, `messages` | Chat history, mode, citations, usage per message |
| `providers` | Provider configuration, capability/privacy metadata |
| `provider_secrets` | Encrypted API keys, never returned by the API |
| `models` | Catalog with capability flags, cost tier, default marker |
| `agents`, `agent_tools` | Agent definitions, tool permissions |
| `projects`, `project_files` | Workspace metadata and tracked files |
| `tasks`, `memories` | Durable work items and agent memory |
| `documents` | Knowledge notes and saved research reports |
| `runtime_connections` | OpenClaw gateway endpoints, status, encrypted token |
| `usage_logs` | Per-request tokens, latency, model, provider |
| `audit_logs` | Security-relevant actions, append-only for users |
| `runs`, `run_events`, `approvals` | Agent execution, event log, approval queue |

## Android layers

```
ui/screens/...      Compose screens, one view model each
ui/components/      design system: cards, pills, markdown renderer, top bar
ui/navigation/      bottom tabs + detail routes
data/repository/    typed AppResult wrappers over the API
data/remote/        OkHttp clients, serialization models, SSE parser
data/local/         EncryptedSharedPreferences session store
core/               AppContainer (manual DI), formatting, result types
```

Screens never touch OkHttp directly. A repository returns `AppResult<T>`, so every screen
has one error path and one retry path.
