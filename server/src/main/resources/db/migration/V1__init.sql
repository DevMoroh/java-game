-- Профіль гостя: хто це, як виглядає, скільки очок і де стояв, коли вийшов.
-- token — секрет, що тримає браузер (sessionStorage); клієнтам інших гравців не передається.
create table guest_profile (
    token         text primary key,
    name          text             not null,
    gender        text             not null check (gender in ('MALE', 'FEMALE')),
    appearance    jsonb            not null,
    score         integer          not null default 0 check (score >= 0),
    last_x        double precision,
    last_z        double precision,
    last_rotation double precision not null default 0,
    created_at    timestamptz      not null default now(),
    last_seen_at  timestamptz      not null default now()
);

-- Команди; назви унікальні без урахування регістру («Java» і «java» — одна команда).
create table team (
    id         bigint generated always as identity primary key,
    name       text        not null,
    created_at timestamptz not null default now()
);
create unique index team_name_key on team (lower(name));

create table team_member (
    team_id   bigint      not null references team (id) on delete cascade,
    token     text        not null references guest_profile (token) on delete cascade,
    joined_at timestamptz not null default now(),
    primary key (team_id, token)
);
create index team_member_token on team_member (token);

-- Спільний чат. Автор — профіль (може зникнути), ім'я зберігаємо окремо, щоб історія лишалась читабельною.
create table chat_message (
    id           bigint generated always as identity primary key,
    author_token text        references guest_profile (token) on delete set null,
    author_name  text        not null,
    text         text        not null check (length(text) <= 300),
    created_at   timestamptz not null default now()
);
create index chat_message_created_at on chat_message (created_at);
