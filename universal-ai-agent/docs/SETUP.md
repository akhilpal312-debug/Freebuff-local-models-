# Setup and deployment

## Prerequisites

- Python 3.11+
- JDK 17 and Android SDK 35 for the app
- A Postgres database for production: [Neon](https://neon.tech) works well with Vercel
- A Vercel account for hosting the backend

## 1. Run the backend locally

```bash
cd backend
python3 -m venv .venv
. .venv/bin/activate
pip install -e ".[dev]"
cp .env.example .env
```

Minimum `.env` for a local run (SQLite):

```dotenv
ENVIRONMENT=development
DATABASE_URL=sqlite+aiosqlite:///./data/dev.db
AUTO_CREATE_TABLES=true
SINGLE_USER_ID=00000000-0000-0000-0000-000000000001
SINGLE_USER_EMAIL=owner@localhost
CREDENTIAL_ENCRYPTION_KEY=<generated below>
```

```bash
python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"
uvicorn app.main:app --reload
```

Check `GET http://localhost:8000/api/v1/health` and `/api/v1/health/ready`. There is no
login: `/api/v1/me` works immediately and creates the owner profile row.

## 2. Create the Neon database

1. Sign in at [neon.tech](https://neon.tech) and create a project (choose the region
   closest to your Vercel region).
2. Copy the **pooled** connection string from the dashboard (host ends in
   `-pooler.<region>.aws.neon.tech`).
3. Change the scheme for SQLAlchemy:

```
postgresql+asyncpg://<user>:<password>@<endpoint>-pooler.<region>.aws.neon.tech/<db>?ssl=require
```

4. Apply the schema once:

```bash
psql "postgresql://<user>:<password>@<endpoint>-pooler.<region>.aws.neon.tech/<db>?sslmode=require" \
  -f db/migrations/0001_init.sql
```

If you prefer the non-pooled URL for `psql`, that is fine; use the pooled one for the
app. `AUTO_CREATE_TABLES` should be `false` once the migration has run. Re-running the
migration is safe for tables but not for triggers/indexes, so apply it once.

## 3. Deploy the backend to Vercel

The backend directory is a self-contained Vercel project:

```
backend/api/index.py     exposes the FastAPI ASGI app
backend/vercel.json      routes every path to that function
backend/requirements.txt dependencies installed by @vercel/python
```

Deploy with the CLI:

```bash
cd backend
npx vercel login          # browser flow
npx vercel                # preview deployment
npx vercel --prod         # production
```

Or import the repository in the Vercel dashboard and set **Root Directory** to
`backend`.

Environment variables to set in Vercel (Project → Settings → Environment Variables):

| Name | Value |
| --- | --- |
| `ENVIRONMENT` | `production` |
| `DATABASE_URL` | Neon pooled URL, `postgresql+asyncpg://…?ssl=require` |
| `AUTO_CREATE_TABLES` | `false` |
| `SINGLE_USER_ID` | a UUID you choose (set once, never change) |
| `SINGLE_USER_EMAIL` | any label, e.g. `owner@example.com` |
| `CREDENTIAL_ENCRYPTION_KEY` | your Fernet key |
| `WORKSPACE_ROOT` | `/tmp/workspaces` |
| `PUBLIC_BASE_URL` | your deployment URL |
| `TAVILY_API_KEY` | optional |

Then redeploy so the variables are picked up, and check:

```bash
curl https://<your-app>.vercel.app/api/v1/health
curl https://<your-app>.vercel.app/api/v1/health/ready
```

### What does not work the same on Vercel

- **No persistent disk.** Projects, cloned repos and terminal output live in `/tmp` and
  vanish between invocations. Treat project workspaces as ephemeral; use them for a
  single run rather than long-lived editing.
- **Long commands** can hit the function `maxDuration` (60 s in `vercel.json`). Increase
  it on a paid plan or run heavy coding work elsewhere.
- **Streaming** uses `StreamingResponse`; whether Vercel delivers SSE incrementally to
  the Android client has not been verified. If it buffers, chat replies arrive complete
  instead of token by token.
- **OpenClaw** connects outward over WebSockets, which a Python function may not keep
  alive long enough for long agent sessions. The native runtime is the reliable choice on
  Vercel.
- **Rate limiting** is per function instance; treat it as a soft limit.

## 4. Build and install the app

```bash
cd android
./gradlew :app:assembleDebug     # needs JDK 17 + SDK 35
```

The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

On first launch, open **Settings → Server** and set the API base URL to your Vercel
deployment (https). There is no sign-in screen; the app goes straight to Home.

If you run the backend locally, use `http://10.0.2.2:8000` from an emulator or your
machine's LAN address from a phone. Android blocks cleartext HTTP by default in release
builds; this project enables it for development, so prefer HTTPS in production.

Release bundle:

```bash
./gradlew :app:bundleRelease
```

Signing is intentionally not committed. Create a keystore, add a `signingConfigs` block
locally (or use Play App Signing), then build the AAB.

## 5. Verify

```bash
cd backend && . .venv/bin/activate && python -m pytest -q          # 53 passed
cd android && ./gradlew :app:testDebugUnitTest                     # 18 passed
```

Then, against the deployment:

1. `GET /api/v1/health/ready` reports `database: ok` and `auth: single_user`.
2. Open Model Hub in the app, add a provider key, **Test**, then **Sync**.
3. Send one chat message; confirm rows in `conversations`, `messages` and `usage_logs`.

## Troubleshooting

- **`asyncpg` connection errors** — use the pooled Neon host, keep `?ssl=require`, and
  make sure the URL scheme is `postgresql+asyncpg://`. On Vercel the app uses `NullPool`,
  so the provider's pooler does the pooling.
- **`relation "profiles" does not exist`** — the migration has not been applied to the
  database in `DATABASE_URL`.
- **Provider key save fails** — `CREDENTIAL_ENCRYPTION_KEY` is missing or invalid; it
  must be a 32-byte urlsafe base64 Fernet key.
- **App shows network errors** — the API base URL is wrong or the deployment is protected
  by Vercel Authentication, which rejects requests without a Vercel session.
- **Everything works but text arrives at once** — expected when streaming is buffered on
  the serverless platform.
