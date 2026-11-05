-- Скриньки на полі: тематичні дошки з постами й коментарями до них.
-- Пост і коментар — один запис (post_id = null — пост); спільна послідовність id дозволяє рахувати
-- непрочитане одним числом «що прочитав до id включно».
-- author_token без зовнішнього ключа: профіль новачка пишеться в БД асинхронно й може з'явитися
-- на мить пізніше за його перший пост. Токен клієнтам не передається (у анонімних скриньках — тим паче).
create table board_entry (
    id           bigint generated always as identity primary key,
    board        text        not null,
    post_id      bigint      references board_entry (id) on delete cascade,
    author_token text,
    author_name  text,       -- null — анонімно
    text         text        not null check (length(text) <= 2000),
    created_at   timestamptz not null default now()
);
create index board_entry_board on board_entry (board, id);
create index board_entry_post on board_entry (post_id, id);

-- Хто «приєднався» до скриньки (бачить над нею лічильник непрочитаного) і що вже прочитав.
create table board_member (
    token        text        not null,
    board        text        not null,
    last_read_id bigint      not null default 0,
    joined_at    timestamptz not null default now(),
    primary key (token, board)
);

-- Статті золотих яєць (нові знання про розробку). Заповнюються окремо (міграцією / генератором);
-- поки таблиця порожня, яйця на полі не з'являються. color — колір яйця; null — за темою (Articles).
-- body — текст: абзаци, списки «- », блоки коду ```, `код`, **жирний**.
create table egg_article (
    id         text primary key,
    topic      text        not null,
    color      text,
    title      text        not null,
    body       text        not null,
    created_at timestamptz not null default now()
);

-- Колекція золотих яєць: яку статтю гравець зберіг собі.
create table egg_collection (
    token        text        not null,
    article_id   text        not null references egg_article (id) on delete cascade,
    collected_at timestamptz not null default now(),
    primary key (token, article_id)
);
