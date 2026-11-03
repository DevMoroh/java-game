-- Статус гравця: що він зараз робить («🎧 Слухаю музику»). Порожній рядок — без статусу.
alter table guest_profile
    add column status text not null default '' check (length(status) <= 40);
