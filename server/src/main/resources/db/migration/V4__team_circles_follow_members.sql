-- Коло команди тепер ходить за кожним її учасником, а не стоїть на місці — центр кола в БД не потрібен.
alter table team
    drop column zone_x,
    drop column zone_z;
