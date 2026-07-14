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
        boolean forfeitWin,
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
     * FIDE C.04.1.d: a player who has already received a pairing-allocated
     * bye, or won a game by forfeit, shall not receive the bye.
     */
    public boolean eligibleForBye() {
        return !hadBye && !forfeitWin;
    }

    /**
     * Due color: the one that restores the white/black balance, or the
     * alternate of the last color when the balance is even. Only played
     * games count: byes and forfeits leave the color history untouched,
     * and a player with no played games has no preference (NONE).
     */
    public int colorPreference() {
        if (colorBalance() > 0) {
            return BLACK;
        }
        if (colorBalance() < 0) {
            return WHITE;
        }
        if (lastColor == NONE) {
            return NONE;
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

    public boolean hasStrongColorPreference() {
        return Math.abs(colorBalance()) >= 1 || hasAbsoluteColorPreference();
    }
}
