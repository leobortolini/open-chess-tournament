package com.open.chess.tournament.domain.service;

import java.util.Set;
import java.util.UUID;

public record PairingCandidate(
        UUID playerId,
        int rating,
        double score,
        Set<UUID> previousOpponents,
        int whiteGames,
        int blackGames,
        boolean hadBye,
        int lastColor,
        int downFloats,
        int upFloats) {

    public static final int WHITE = 1;
    public static final int BLACK = -1;
    public static final int NONE = 0;

    public int colorBalance() {
        return whiteGames - blackGames;
    }

    public boolean hasPlayed(UUID opponentId) {
        return previousOpponents.contains(opponentId);
    }

    public int colorPreference() {
        return whiteGames > blackGames ? BLACK : WHITE;
    }

    public boolean hasAbsoluteColorPreference() {
        return Math.abs(colorBalance()) >= 2;
    }

    public boolean hasStrongColorPreference() {
        return Math.abs(colorBalance()) >= 1;
    }
}
