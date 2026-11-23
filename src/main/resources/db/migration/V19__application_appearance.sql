CREATE TABLE application_appearance (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    theme VARCHAR(20) NOT NULL CHECK (theme IN ('STANDARD', 'CHRISTMAS'))
);
INSERT INTO application_appearance (id, theme) VALUES (1, 'STANDARD');
