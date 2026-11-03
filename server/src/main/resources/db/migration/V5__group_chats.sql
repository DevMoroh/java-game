-- Команд з назвами більше немає. У кожного гравця свій колір кола; хто підійшов до іншого й приєднався —
-- у його груповому чаті (таблиця team тепер — групи чату, name — непрозорий id групи). Хто ні в якій групі —
-- у загальному чаті (повідомлення з team_id = null).
alter table guest_profile add column color text;
alter table chat_message alter column team_id drop not null;

-- Групи з одного учасника не потрібні (разом з їхнім чатом).
delete from team t
where (select count(*) from team_member m where m.team_id = t.id) < 2;
