INSERT INTO Organizer(title) VALUES ('Горы и Солнце');
INSERT INTO EventSeries(title, organizer_id) VALUES ('Семейные походы с ночёвкой', 1);
INSERT INto Event(title, start, series_id) VALUES ('Гергети-Арша', '2023-09-22'::TIMESTAMP, 1);
INSERT INTO Event(title, start, series_id) VALUES ('Мтацминда', '2023-09-22'::TIMESTAMP, 1);

INSERT INTO TgUser(tg_userid, tg_username) VALUES(189973428, 'dbarashev');