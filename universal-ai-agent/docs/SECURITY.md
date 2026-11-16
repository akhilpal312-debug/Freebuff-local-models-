# Security

## The main risk: there is no login

This build is a **single-user workspace**. The backend accepts every request and runs it
as one configured owner (`SINGLE_USER_ID`). There are no accounts, sessions, tokens or
row-level security. Consequences:

- Anyone who can reach the backend URL can read and write the workspace, use the stored
  provider keys, and spend your provider credits.
- Provider keys are encrypted at rest, but the API can use them on request — encryption
  protects the database, not the endpoint.
- Treat the deployment URL as a secret. Do not publish it in a repository, a screenshot
  or an app store listing.
- If the URL leaks: rotate every provider key in Model Hub, rotate
  `CREDENTIAL_ENCRYPTION_KEY` (you will re-enter keys), and rotate the Neon database
  password.

Recommended mitigations:

- Vercel **Deployment Protection** (password or Vercel Authentication) in front of the
  deployment, with the same protection applied to the API routes the app calls.
- Or host the backend on a private network / VPN and expose it only to your devices.
- Rotate keys on any suspicion of exposure; there is no audit trail tied to identities,
  only timestamps and actions.

If you later need multiple users, reintroduce an auth layer rather than widening this
model: the code already filters every query by `user_id`, which is the seam to build on.

## Trust boundaries

- **Device** — stores only the backend URL. Provider keys never reach it.
- **Backend** — trusted. Holds provider credentials, the Fernet key and the database URL.
- **Postgres** — authoritative for data. No RLS: the backend is the only client and
  filters by the owner id on every query.
- **Providers and gateways** — untrusted third parties. Their responses are validated,
  bounded and never executed as code.

## Provider credentials

- Stored as Fernet ciphertext in `provider_secrets`, keyed by
  `CREDENTIAL_ENCRYPTION_KEY` (32-byte urlsafe base64).
- The API returns only `has_credentials` and a short fingerprint, never the key.
- Rotating the encryption key means re-entering provider keys; old ciphertext is not
  readable with a new key.
- Back the key up in a secret manager, never in the repository. Losing it is an outage,
  not a breach.
- Logs run through a redaction filter that removes `sk-…`-style tokens, bearer headers and
  configured key values before a line is written.

## Agent permissions

- Three profiles: `read_only` (default), `standard`, `elevated`.
- Every tool declares whether it needs approval. Shell commands, writes to read-only
  projects and git mutations always do.
- Approval is a database row tied to the run: the orchestrator blocks until it is
  approved or rejected, and the decision and arguments are written to `run_events` and
  `audit_logs`.
- Agents can only use tools enabled on their `agent_tools` rows; the runtime re-checks at
  call time rather than trusting the prompt.

## Sandbox

- Project paths are resolved and compared against the workspace root; `..`, absolute
  paths and symlinks escaping the root are rejected.
- Zip imports are checked for zip-slip before extraction.
- Commands are matched against an allowlist, run with a timeout, and their output is
  truncated.
- `SANDBOX_MODE=docker` isolates commands in a throwaway container when a Docker daemon is
  available; Vercel has neither Docker nor a persistent filesystem. `local` mode is a
  filesystem jail, not a kernel sandbox: a crafted command could still affect the host
  process owner. Run the backend under a dedicated user locally.
- Uploads are capped by `MAX_UPLOAD_BYTES` (default 10 MiB) and rejected when over.

## Rate limiting and abuse

- Requests are limited per minute (`RATE_LIMIT_PER_MINUTE`). The limiter is in-process;
  on Vercel each function instance has its own window, so treat it as a soft limit, not a
  billing control.
- Provider calls have their own timeout (`PROVIDER_TIMEOUT_SECONDS`).
- Usage is logged per request (tokens, latency, model, provider) for cost tracking.

## Audit logging

- `audit_logs` records security-relevant actions: provider key set/deleted, provider
  tests, runtime connections, approvals, cancellations.
- With a single owner, "who" is always the workspace owner; the value is in *what* and
  *when*.

## Client hardening

- The app refuses to store provider keys at all; Model Hub asks the backend to store them.
- Attachment uploads are size-limited and encoded as data URLs for the backend; nothing is
  written to shared storage.
- The release build enables R8 shrinking. Signing keys are not committed.

## Reporting

If you find a vulnerability, open a private security advisory on the repository rather
than a public issue, and include reproduction steps and the affected commit.
