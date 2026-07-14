package com.open.chess.tournament.domain.model;

public enum GameResult {
    PENDING,
    WHITE_WINS,
    BLACK_WINS,
    DRAW,
    BYE,
    WHITE_WINS_FORFEIT,
    BLACK_WINS_FORFEIT,
    DOUBLE_FORFEIT;

    public boolean isDecided() {
        return this != PENDING;
    }

    /**
     * A game that was actually played over the board. Byes, forfeits and
     * pending games are unplayed: they do not count for color history and
     * are replaced by a virtual opponent in tie-break calculations.
     */
    public boolean isPlayedGame() {
        return this == WHITE_WINS || this == BLACK_WINS || this == DRAW;
    }
}
