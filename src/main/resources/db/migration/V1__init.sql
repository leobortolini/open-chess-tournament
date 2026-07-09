CREATE TABLE tournaments (
    id           UUID PRIMARY KEY,
    name         VARCHAR(255) NOT NULL,
    total_rounds INT          NOT NULL CHECK (total_rounds >= 1),
    status       VARCHAR(20)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL
);

CREATE TABLE players (
    id            UUID PRIMARY KEY,
    tournament_id UUID         NOT NULL REFERENCES tournaments (id) ON DELETE CASCADE,
    name          VARCHAR(255) NOT NULL,
    rating        INT          NOT NULL DEFAULT 0,
    active        BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE INDEX idx_players_tournament ON players (tournament_id);

CREATE TABLE rounds (
    id            UUID PRIMARY KEY,
    tournament_id UUID NOT NULL REFERENCES tournaments (id) ON DELETE CASCADE,
    round_number  INT  NOT NULL,
    UNIQUE (tournament_id, round_number)
);

CREATE INDEX idx_rounds_tournament ON rounds (tournament_id);

CREATE TABLE pairings (
    id              UUID PRIMARY KEY,
    round_id        UUID        NOT NULL REFERENCES rounds (id) ON DELETE CASCADE,
    board_number    INT         NOT NULL,
    white_player_id UUID        NOT NULL REFERENCES players (id),
    black_player_id UUID        REFERENCES players (id),
    result          VARCHAR(20) NOT NULL,
    UNIQUE (round_id, board_number)
);

CREATE INDEX idx_pairings_round ON pairings (round_id);
