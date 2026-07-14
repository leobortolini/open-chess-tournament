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
        int previousColor,
        int downFloats,
        int upFloats,
        boolean floatedDownLastRound,
        boolean floatedUpLastRound) {

    public static final int WHITE = 1;
    public static final int BLACK = -1;
    public static final int NONE = 0;

    public int colorBalance() {
        return whiteGames - blackGames;
    }

    public boolean hasPlayed(UUID opponentId) {
        return previousOpponents.contains(opponentId);
    }

    /**
     * Due color: the one that restores the white/black balance, or the
     * alternate of the last color when the balance is even.
     */
    public int colorPreference() {
        if (colorBalance() > 0) {
            return BLACK;
        }
        if (colorBalance() < 0) {
            return WHITE;
        }
        return lastColor == WHITE ? BLACK : WHITE;
    }

    /**
     * FIDE absolute color preference: color difference of two or more, or
     * the same color in the last two played games.
     */
    public boolean hasAbsoluteColorPreference() {
        return Math.abs(colorBalance()) >= 2
                || (lastColor != NONE && lastColor == previousColor);
    }
}
