-- Які статті гравець уже знайшов (відкрив яйце — байдуже, зберіг чи викинув). Такі яйця йому більше не показуємо
-- й очок за них не даємо.
create table egg_seen (
    token      text        not null,
    article_id text        not null references egg_article (id) on delete cascade,
    seen_at    timestamptz not null default now(),
    primary key (token, article_id)
);

-- Збережене в колекцію — уже знайдене.
insert into egg_seen (token, article_id, seen_at)
select token, article_id, collected_at from egg_collection
on conflict do nothing;
