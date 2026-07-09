package com.open.chess.tournament.domain.model;

import java.util.UUID;

public record PlayerStanding(
        int rank,
        UUID playerId,
        String name,
        int rating,
        double score,
        double buchholz,
        boolean active) {

    public PlayerStanding withRank(int newRank) {
        return new PlayerStanding(newRank, playerId, name, rating, score, buchholz, active);
    }
}
