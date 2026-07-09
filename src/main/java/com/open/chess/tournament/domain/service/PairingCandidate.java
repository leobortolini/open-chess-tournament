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
        boolean hadBye) {

    public int colorBalance() {
        return whiteGames - blackGames;
    }

    public boolean hasPlayed(UUID opponentId) {
        return previousOpponents.contains(opponentId);
    }
}
