# Universal AI Agent

A multi-model, multi-agent AI workspace: a native Kotlin/Jetpack Compose Android app,
a Python/FastAPI backend that deploys to Vercel, and a Postgres schema (Neon or any
Postgres).

There is **no login**. The app and backend form a single-user workspace: every request
runs as one configured owner. That is deliberate and simple, and it means the backend URL
must be treated as a secret.

```
android/    Kotlin + Compose app (pearl-white UI, five tabs)
backend/    FastAPI service, Vercel entrypoint in api/index.py
db/         Postgres migration with tables, indexes and triggers
docs/       Architecture, security and setup notes
```

## What is implemented

| Area | Status |
| --- | --- |
| Provider adapters (OpenAI-compatible, Anthropic, Gemini) | Implemented; OpenAI-compatible covers OpenAI, NVIDIA NIM, OpenRouter, Ollama and custom gateways |
| Encrypted provider credentials (Fernet, server-side) | Implemented; keys are never returned by the API |
| Model catalog, capability flags, default model, deterministic routing | Implemented |
| Streaming chat with persistence, usage logs, edit and regenerate | Implemented |
| Deep-thinking mode with reasoning-capability routing | Implemented |
| Web research with source verification and honest "no sources" reporting | Implemented |
| Project workspaces: files, search, git status/diff, allowlisted commands | Implemented |
| Agent runtime: plan, per-step tool loop, approvals, cancellation, event stream | Implemented |
| OpenClaw gateway adapter (WS protocol, pairing, idempotent agent calls) | Implemented and tested against an in-process fake gateway |
| Knowledge workspace (documents, AI summary) | Implemented |
| Postgres schema | Implemented in `db/migrations/0001_init.sql` |
| Backend test suite | 53 tests passing (`pytest -q`) |
| Android app | Source complete; debug APK in `dist/` |
| Android unit tests | 18 tests passing (`:app:testDebugUnitTest`) |
| Vercel deployment | Config and entrypoint included; **not deployed** — needs a Neon `DATABASE_URL` |
| Android release AAB | Not produced — requires your own signing keystore |

## Quick start (local)

```bash
cd backend
python3 -m venv .venv
. .venv/bin/activate
pip install -e ".[dev]"
cp .env.example .env
# generate the credential key and paste it into .env
python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"
uvicorn app.main:app --reload
```

SQLite is the default local database (`AUTO_CREATE_TABLES=true` creates the tables).
For Postgres, set `DATABASE_URL`, apply `db/migrations/0001_init.sql` and set
`AUTO_CREATE_TABLES=false`. Full steps, including Neon and Vercel, are in
[docs/SETUP.md](docs/SETUP.md).

## The five tabs

- **Home** — greeting, recent conversations, quick modes, projects, agents, model status.
- **Chat** — every mode with streaming output, stop, edit, regenerate, citations, image
  attachments, voice dictation and a per-request model picker.
- **Agents** — agent templates and custom agents, tools, permission profiles, runs with
  plan, activity timeline, approvals and cancellation.
- **Projects** — sandboxed workspaces: file browser, editor, search, git status/diff and
  an allowlisted terminal. New projects are read-only until you opt in.
- **Settings** — backend address, OpenClaw gateway, usage totals.

Knowledge lives one tap from Home: notes, AI summaries and cited web research.

## Configuration

All backend settings live in `backend/.env` locally, or in Vercel's environment variables
in production. Template: `backend/.env.example`. The important ones:

- `DATABASE_URL` — `postgresql+asyncpg://…` for Neon (pooled endpoint advised), SQLite
  locally.
- `SINGLE_USER_ID`, `SINGLE_USER_EMAIL`, `SINGLE_USER_NAME` — the workspace owner. Set
  them before the first run; changing the id later orphans existing rows.
- `CREDENTIAL_ENCRYPTION_KEY` — Fernet key for provider secrets. Losing it makes stored
  provider keys unreadable (you would re-enter them).
- `WORKSPACE_ROOT` — project files. On Vercel use `/tmp/workspaces` and expect them to
  disappear between invocations.
- `TAVILY_API_KEY` — optional; DuckDuckGo HTML and Wikipedia work without a key.

Provider API keys are added from the app's Model Hub and stored encrypted in Postgres.
They are not part of `.env` and never reach the device.

## Tests

```bash
cd backend && . .venv/bin/activate && python -m pytest -q
cd android && ./gradlew :app:testDebugUnitTest   # requires JDK 17 + Android SDK 35
```

## Honest boundaries

- The backend is **unauthenticated by design**. Anyone with the URL can use it and spend
  your provider credits. Keep the URL private, rotate keys if it leaks, or put Vercel
  Deployment Protection / a private network in front of it.
- Vercel functions are ephemeral: project files, cloned repos and command output do not
  persist, long commands can hit the function time limit, and SSE streaming has not been
  verified there. Chat still works, but streaming may arrive as one block.
- No live Neon, Vercel or provider traffic was exercised while building this. The backend
  suite uses SQLite and fake provider adapters.
- The APK is a debug build (`dev.universalai.agent.debug`) and was never launched on a
  device or emulator here.

More detail: [architecture](docs/ARCHITECTURE.md), [security](docs/SECURITY.md),
[setup and deployment](docs/SETUP.md).
