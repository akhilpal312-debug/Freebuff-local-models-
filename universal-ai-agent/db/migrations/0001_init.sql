-- Universal AI Agent - initial schema (single-user workspace)
-- Mirrors backend/app/db/tables.py. Apply once with:
--   psql "$DATABASE_URL" -f db/migrations/0001_init.sql
--
-- There is no Supabase Auth and no row-level security: the backend is the only
-- client of this database and every row belongs to the one configured owner.

-- bypasses RLS, so it also filters by user_id in every query (defence in depth).

create extension if not exists "pgcrypto";

-- ---------------------------------------------------------------- profiles
create table if not exists public.profiles (
    id uuid primary key,
    email text,
    display_name text,
    avatar_url text,
    default_model_pk uuid,
    settings jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

-- ---------------------------------------------------------------- providers
create table if not exists public.providers (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references public.profiles(id) on delete cascade,
    slug text not null,
    catalog_key text,
    name text not null,
    kind text not null,
    api_compat text not null default 'openai'
        check (api_compat in ('openai', 'anthropic', 'gemini')),
    base_url text,
    privacy_tier text not null default 'cloud' check (privacy_tier in ('cloud', 'local')),
    enabled boolean not null default false,
    is_builtin boolean not null default false,
    notes text,
    extra jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_providers_user_slug unique (user_id, slug)
);
create index if not exists ix_providers_user on public.providers(user_id);

-- Ciphertext only. There is deliberately no plaintext column and no default.
create table if not exists public.provider_secrets (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references public.profiles(id) on delete cascade,
    provider_id uuid not null references public.providers(id) on delete cascade,
    ciphertext text not null,
    fingerprint text not null,
    created_at timestamptz not null default now(),
    rotated_at timestamptz,
    constraint uq_provider_secrets_provider unique (provider_id)
);

-- ------------------------------------------------------------------- models
create table if not exists public.models (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references public.profiles(id) on delete cascade,
    provider_id uuid not null references public.providers(id) on delete cascade,
    model_id text not null,
    display_name text not null,
    context_window integer not null default 8192,
    max_output_tokens integer not null default 1024,
    supports_streaming boolean not null default true,
    supports_tools boolean not null default false,
    supports_vision boolean not null default false,
    supports_reasoning boolean not null default false,
    input_modalities jsonb not null default '["text"]'::jsonb,
    output_modalities jsonb not null default '["text"]'::jsonb,
    availability text not null default 'unknown'
        check (availability in ('available', 'degraded', 'unavailable', 'unknown')),
    is_default boolean not null default false,
    metadata jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_models_provider_model unique (provider_id, model_id)
);
create index if not exists ix_models_user on public.models(user_id);

-- ----------------------------------------------------------------- projects
create table if not exists public.projects (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references public.profiles(id) on delete cascade,
    name text not null,
    description text,
    kind text not null default 'generic',
    source text not null default 'empty',
    repo_url text,
    default_branch text,
    root_path text,
    read_only boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
create index if not exists ix_projects_user on public.projects(user_id);

create table if not exists public.project_files (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references public.profiles(id) on delete cascade,
    project_id uuid not null references public.projects(id) on delete cascade,
    path text not null,
    kind text not null default 'file',
    size_bytes integer not null default 0,
    sha256 text,
    updated_at timestamptz not null default now(),
    constraint uq_project_files_path unique (project_id, path)
);
create index if not exists ix_project_files_project on public.project_files(project_id);

-- ------------------------------------------------------------- conversations
create table if not exists public.conversations (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references public.profiles(id) on delete cascade,
    project_id uuid references public.projects(id) on delete set null,
    title text not null default 'New conversation',
    mode text not null default 'chat'
        check (mode in ('chat', 'deep', 'research', 'coding', 'agent')),
    model_pk uuid references public.models(id) on delete set null,
    pinned boolean not null default false,
    archived_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
create index if not exists ix_conversations_user_updated
    on public.conversations(user_id, updated_at desc);

create table if not exists public.messages (
    id uuid primary key default gen_random_uuid(),
    conversation_id uuid not null references public.conversations(id) on delete cascade,
    user_id uuid not null references public.profiles(id) on delete cascade,
    role text not null check (role in ('system', 'user', 'assistant', 'tool')),
    content text not null default '',
    sequence integer not null default 0,
    model_pk uuid,
    parts jsonb not null default '[]'::jsonb,
    citations jsonb not null default '[]'::jsonb,
    attachments jsonb not null default '[]'::jsonb,
    prompt_tokens integer not null default 0,
    completion_tokens integer not null default 0,
    status text not null default 'complete'
        check (status in ('streaming', 'complete', 'cancelled', 'failed')),
    error text,
    created_at timestamptz not null default now()
);
create index if not exists ix_messages_conversation_seq
    on public.messages(conversation_id, sequence);
create index if not exists ix_messages_user on public.messages(user_id);

-- ------------------------------------------------------------------- agents
create table if not exists public.agents (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references public.profiles(id) on delete cascade,
    project_id uuid references public.projects(id) on delete set null,
    kind text not null default 'general',
    name text not null,
    description text,
    instructions text not null default '',
    model_pk uuid references public.models(id) on delete set null,
    runtime text not null default 'native' check (runtime in ('native', 'openclaw')),
    permission_profile text not null default 'read_only'
        check (permission_profile in ('read_only', 'standard', 'elevated')),
    memory_scope text not null default 'agent'
        check (memory_scope in ('global', 'project', 'agent')),
    enabled_tools jsonb not null default '[]'::jsonb,
    is_template boolean not null default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
create index if not exists ix_agents_user on public.agents(user_id);

create table if not exists public.agent_tools (
    id uuid primary key default gen_random_uuid(),
    agent_id uuid not null references public.agents(id) on delete cascade,
    user_id uuid not null references public.profiles(id) on delete cascade,
    tool_name text not null,
    enabled boolean not null default true,
    config jsonb not null default '{}'::jsonb,
    constraint uq_agent_tools unique (agent_id, tool_name)
);

create table if not exists public.agent_runs (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references public.profiles(id) on delete cascade,
    agent_id uuid references public.agents(id) on delete set null,
    conversation_id uuid references public.conversations(id) on delete set null,
    project_id uuid references public.projects(id) on delete set null,
    objective text not null,
    runtime text not null default 'native' check (runtime in ('native', 'openclaw')),
    status text not null default 'queued'
        check (status in ('queued', 'running', 'awaiting_approval', 'cancelling',
                          'cancelled', 'succeeded', 'failed')),
    plan jsonb not null default '[]'::jsonb,
    state jsonb not null default '{}'::jsonb,
    result text,
    error text,
    tokens_used integer not null default 0,
    started_at timestamptz,
    finished_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
create index if not exists ix_agent_runs_user_created
    on public.agent_runs(user_id, created_at desc);

create table if not exists public.run_events (
    id uuid primary key default gen_random_uuid(),
    run_id uuid not null references public.agent_runs(id) on delete cascade,
    seq integer not null,
    type text not null,
    payload jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint uq_run_events_seq unique (run_id, seq)
);

create table if not exists public.approvals (
    id uuid primary key default gen_random_uuid(),
    run_id uuid not null references public.agent_runs(id) on delete cascade,
    user_id uuid not null references public.profiles(id) on delete cascade,
    tool_name text not null,
    arguments jsonb not null default '{}'::jsonb,
    status text not null default 'pending'
        check (status in ('pending', 'approved', 'rejected', 'expired')),
    reason text,
    requested_at timestamptz not null default now(),
    decided_at timestamptz
);
create index if not exists ix_approvals_run on public.approvals(run_id, status);

-- --------------------------------------------------------------- knowledge
create table if not exists public.tasks (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references public.profiles(id) on delete cascade,
    project_id uuid references public.projects(id) on delete cascade,
    agent_id uuid,
    run_id uuid,
    title text not null,
    status text not null default 'todo'
        check (status in ('todo', 'in_progress', 'blocked', 'done', 'cancelled')),
    priority integer not null default 0,
    params jsonb not null default '{}'::jsonb,
    result jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
create index if not exists ix_tasks_user_status on public.tasks(user_id, status);

create table if not exists public.memories (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references public.profiles(id) on delete cascade,
    scope text not null default 'global' check (scope in ('global', 'project', 'agent')),
    scope_id uuid,
    kind text not null default 'fact',
    content text not null,
    importance double precision not null default 0.5,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
create index if not exists ix_memories_scope on public.memories(user_id, scope, scope_id);

create table if not exists public.documents (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references public.profiles(id) on delete cascade,
    project_id uuid references public.projects(id) on delete set null,
    title text not null,
    kind text not null default 'note',
    content_md text not null default '',
    tags jsonb not null default '[]'::jsonb,
    source_url text,
    storage_path text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
create index if not exists ix_documents_user_updated
    on public.documents(user_id, updated_at desc);

-- --------------------------------------------------------- runtime + logs
create table if not exists public.runtime_connections (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references public.profiles(id) on delete cascade,
    runtime text not null default 'openclaw' check (runtime in ('openclaw')),
    label text not null default 'OpenClaw',
    gateway_url text not null,
    device_id text not null,
    encrypted_token text,
    token_fingerprint text,
    status text not null default 'unverified',
    protocol_version integer,
    last_checked_at timestamptz,
    last_error text,
    meta jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
create index if not exists ix_runtime_connections_user
    on public.runtime_connections(user_id, runtime);

create table if not exists public.usage_logs (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references public.profiles(id) on delete cascade,
    provider_id uuid,
    model_pk uuid,
    conversation_id uuid,
    run_id uuid,
    feature text not null default 'chat',
    prompt_tokens integer not null default 0,
    completion_tokens integer not null default 0,
    total_tokens integer not null default 0,
    cost_usd numeric(12, 6) not null default 0,
    latency_ms integer not null default 0,
    created_at timestamptz not null default now()
);
create index if not exists ix_usage_logs_user_created
    on public.usage_logs(user_id, created_at desc);

create table if not exists public.audit_logs (
    id uuid primary key default gen_random_uuid(),
    user_id uuid,
    action text not null,
    target_type text,
    target_id text,
    success boolean not null default true,
    ip text,
    user_agent text,
    detail jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);
create index if not exists ix_audit_logs_user_created
    on public.audit_logs(user_id, created_at desc);

-- ------------------------------------------------------------ updated_at
create or replace function public.touch_updated_at()
returns trigger language plpgsql as $$
begin
    new.updated_at = now();
    return new;
end;
$$;

do $$
declare
    table_name text;
begin
    foreach table_name in array array[
        'profiles', 'providers', 'models', 'projects', 'conversations', 'agents',
        'agent_runs', 'tasks', 'memories', 'documents', 'runtime_connections'
    ] loop
        execute format(
            'drop trigger if exists trg_touch_%1$s on public.%1$s', table_name
        );
        execute format(
            'create trigger trg_touch_%1$s before update on public.%1$s '
            'for each row execute function public.touch_updated_at()', table_name
        );
    end loop;
end;
$$;
