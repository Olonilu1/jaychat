-- Yarn schema
create table if not exists public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  username text unique not null,
  display_name text not null,
  created_at timestamptz default now()
);

create table if not exists public.chats (
  id uuid primary key default gen_random_uuid(),
  kind text not null check (kind in ('dm','group')),
  name text,
  created_by uuid references auth.users(id),
  created_at timestamptz default now()
);

create table if not exists public.chat_members (
  chat_id uuid references public.chats(id) on delete cascade,
  user_id uuid references auth.users(id) on delete cascade,
  joined_at timestamptz default now(),
  primary key (chat_id, user_id)
);

create table if not exists public.messages (
  id uuid primary key default gen_random_uuid(),
  chat_id uuid references public.chats(id) on delete cascade,
  sender_id uuid references auth.users(id),
  sender_username text,
  sender_display text,
  body text not null,
  created_at timestamptz default now()
);

create index if not exists messages_chat_idx on public.messages (chat_id, created_at);
create index if not exists members_user_idx on public.chat_members (user_id);

alter table public.profiles enable row level security;
alter table public.chats enable row level security;
alter table public.chat_members enable row level security;
alter table public.messages enable row level security;

create policy "profiles readable" on public.profiles for select to authenticated using (true);
create policy "insert own profile" on public.profiles for insert to authenticated with check (id = auth.uid());
create policy "update own profile" on public.profiles for update to authenticated using (id = auth.uid());

create policy "member can read chats" on public.chats for select to authenticated
  using (exists (select 1 from public.chat_members m where m.chat_id = chats.id and m.user_id = auth.uid()));
create policy "anyone creates chat" on public.chats for insert to authenticated with check (created_by = auth.uid());

create policy "member reads members" on public.chat_members for select to authenticated
  using (exists (select 1 from public.chat_members m2 where m2.chat_id = chat_members.chat_id and m2.user_id = auth.uid()));
create policy "creator or self adds member" on public.chat_members for insert to authenticated
  with check (
    exists (select 1 from public.chats c where c.id = chat_members.chat_id and c.created_by = auth.uid())
    or chat_members.user_id = auth.uid()
  );

create policy "member reads messages" on public.messages for select to authenticated
  using (exists (select 1 from public.chat_members m where m.chat_id = messages.chat_id and m.user_id = auth.uid()));
create policy "member sends messages" on public.messages for insert to authenticated
  with check (
    sender_id = auth.uid()
    and exists (select 1 from public.chat_members m where m.chat_id = messages.chat_id and m.user_id = auth.uid())
  );
