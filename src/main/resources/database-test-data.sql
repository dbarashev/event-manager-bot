INSERT INTO Organizer(id, title) VALUES (1, 'Горы и Солнце');
INSERT INTO EventSeries(title, organizer_id) VALUES ('Семейные походы с ночёвкой', 1);
INSERT INto Event(id, title, start, series_id) VALUES (1, 'Гергети-Арша', '2023-09-22'::TIMESTAMP, 1);
INSERT INTO Event(id, title, start, series_id) VALUES (2, 'Мтацминда', '2023-09-22'::TIMESTAMP, 1);

INSERT INTO TgUser(id, tg_userid, tg_username) VALUES(1, 189973428, 'dbarashev');
INSERT INTO TgUser(id, tg_username) VALUES(2, 'Safe_Ex');
INSERT INTO TgUser(id, tg_username) VALUES(3, 'lizonantsiferova');

INSERT INTO OrganizerManager(organizer_id, user_id) VALUES (1, 2), (1, 3), (1,189973428);
