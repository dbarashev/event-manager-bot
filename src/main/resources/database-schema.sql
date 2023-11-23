CREATE TABLE TgUser(
    id INT PRIMARY KEY GENERATED BY DEFAULT AS IDENTITY,
    tg_userid BIGINT UNIQUE,
    tg_username VARCHAR UNIQUE NOT NULL
);

CREATE TABLE Participant(
    id INT PRIMARY KEY GENERATED BY DEFAULT AS IDENTITY,
    user_id BIGINT REFERENCES TgUser(tg_userid),
    display_name VARCHAR NOT NULL DEFAULT '',
    age INT NOT NULL DEFAULT 22 CHECK(age > 0)
);

CREATE TABLE ParticipantTeam(
    leader_id INT NOT NULL REFERENCES Participant,
    follower_id INT NOT NULL REFERENCES Participant,
    UNIQUE(leader_id, follower_id)
);

CREATE VIEW ParticipantTeamView AS
SELECT L.user_id AS leader_user_id,
       F.id AS follower_id,
       F.display_name AS follower_display_name,
       F.age AS follower_age
FROM Participant L JOIN ParticipantTeam PT on L.id = PT.leader_id JOIN Participant F ON PT.follower_id = F.id;

CREATE TABLE Organizer(
    id INT PRIMARY KEY GENERATED BY DEFAULT AS IDENTITY,
    title VARCHAR NOT NULL
);

CREATE TABLE OrganizerManager(
    organizer_id INT NOT NULL REFERENCES Organizer,
    user_id BIGINT NOT NULL REFERENCES TgUser(tg_userid)
);

CREATE TABLE EventSeries(
    id INT PRIMARY KEY GENERATED BY DEFAULT AS IDENTITY,
    title VARCHAR NOT NULL DEFAULT '',
    organizer_id INT REFERENCES Organizer,
    is_default BOOLEAN DEFAULT false
);

CREATE TABLE Event(
    id INT PRIMARY KEY GENERATED BY DEFAULT AS IDENTITY,
    title VARCHAR NOT NULL DEFAULT '',
    start TIMESTAMP NOT NULL,
    participant_limit INT CHECK ( participant_limit >= 0 ),
    series_id INT REFERENCES EventSeries,
    is_deleted BOOLEAN DEFAULT false
);

CREATE TABLE EventSeriesSubscription(
    id INT PRIMARY KEY GENERATED BY DEFAULT AS IDENTITY,
    series_id INT NOT NULL REFERENCES EventSeries,
    participant_id INT NOT NULL REFERENCES Participant,
    default_property_values JSON NOT NULL DEFAULT '{}'::JSON,
    UNIQUE (series_id, participant_id)
);

CREATE TABLE EventRegistration(
    id INT PRIMARY KEY GENERATED BY DEFAULT AS IDENTITY,
    subscription_id INT REFERENCES EventSeriesSubscription,
    participant_id INT REFERENCES Participant NOT NULL,
    event_id INT REFERENCES Event NOT NULL,
    UNIQUE (event_id, participant_id)
);

CREATE VIEW EventView AS
SELECT E.id, E.title, E.start, E.participant_limit, T.*
FROM Event E LEFT JOIN (
    SELECT ES.id AS series_id, ES.title AS series_title, O.id AS organizer_id, O.title AS organizer_title
    FROM EventSeries ES JOIN Organizer O on ES.organizer_id = O.id
) AS T ON E.series_id = T.series_id
WHERE NOT E.is_deleted;

CREATE VIEW EventTeamRegistrationView AS
SELECT EV.*, P.id AS participant_id,
       P.display_name AS participant_name,
       P.age AS participant_age,
       P1.user_id AS registrant_tguserid,
       P1.display_name AS registrant_name,
       U1.tg_username AS registrant_username
FROM EventView EV JOIN EventRegistration ER ON EV.id = ER.event_id
    JOIN EventSeriesSubscription ESS ON ER.subscription_id = ESS.id
    JOIN Participant P1 ON ESS.participant_id = P1.id
    JOIN TgUser U1 ON P1.user_id = U1.tg_userid
JOIN Participant P on ER.participant_id = P.id;

create table DialogState(tg_id bigint primary key, state_id int, data text);
