-- Команди стають колами на полі зі своїм чатом; гравець — лише в одній команді; загального чату немає.
alter table team
    add column color       text,
    add column zone_x      double precision,
    add column zone_z      double precision,
    add column owner_token text references guest_profile (token) on delete set null;

-- Одна команда на гравця: лишаємо найновіше членство.
delete from team_member a
    using team_member b
where a.token = b.token
  and (a.joined_at, a.team_id) < (b.joined_at, b.team_id);
alter table team_member drop constraint team_member_pkey;
alter table team_member add primary key (token);
drop index team_member_token;
create index team_member_team on team_member (team_id, joined_at);

-- Власник наявних команд — найстаріший учасник; порожні команди прибираємо.
update team t
set owner_token = (select m.token from team_member m where m.team_id = t.id order by m.joined_at limit 1);
delete from team t
where not exists (select 1 from team_member m where m.team_id = t.id);

-- Колір має бути унікальним серед кіл на полі (null — ще не призначено, призначить сервер).
create unique index team_color_key on team (lower(color));

-- Чат — лише в командах; видалення команди видаляє її переписку.
delete from chat_message;
alter table chat_message add column team_id bigint not null references team (id) on delete cascade;
drop index chat_message_created_at;
create index chat_message_team on chat_message (team_id, id);
create index chat_message_created_at on chat_message (created_at);
