-- Гравець може вимкнути для себе звук нових повідомлень у скриньці (лічильник і анімація лишаються).
alter table board_member add column muted boolean not null default false;
