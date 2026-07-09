package com.open.chess.tournament.domain.model;

public enum GameResult {
    PENDING,
    WHITE_WINS,
    BLACK_WINS,
    DRAW,
    BYE;

    public boolean isDecided() {
        return this != PENDING;
    }
}
