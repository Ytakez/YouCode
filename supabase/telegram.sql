-- YouCode 1.3.8: вход через Telegram. Выполните в Supabase Dashboard -> SQL Editor.
create table if not exists public.telegram_login_requests (
  token text primary key,
  telegram_id bigint,
  first_name text,
  username text,
  linked_at timestamptz,
  created_at timestamptz not null default now()
);
create table if not exists public.telegram_accounts (
  telegram_id bigint primary key,
  user_id uuid not null unique references auth.users(id) on delete cascade,
  created_at timestamptz not null default now()
);
-- RLS включён и политик нет: приложение к этим таблицам доступа не имеет, работает только Edge Function с секретным ключом.
alter table public.telegram_login_requests enable row level security;
alter table public.telegram_accounts enable row level security;
