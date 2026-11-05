-- Для генератора статей: subject — конкретна тема кейсу («cache stampede», «NOT IN і NULL»), щоб не повторюватись
-- (унікальна без урахування регістру); updated_at — коли статтю востаннє редагували через адмін-API.
alter table egg_article add column subject text;
alter table egg_article add column updated_at timestamptz not null default now();
create unique index egg_article_subject_key on egg_article (lower(subject));
