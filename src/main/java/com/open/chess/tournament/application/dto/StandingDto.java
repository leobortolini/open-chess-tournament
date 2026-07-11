package com.open.chess.tournament.application.dto;

import com.open.chess.tournament.domain.model.PlayerStanding;

import java.util.UUID;

public record StandingDto(
        int rank,
        UUID playerId,
        String name,
        int rating,
        double score,
        double buchholz,
        double medianBuchholz,
        double sonnebornBerger,
        int wins,
        boolean active) {

    public static StandingDto from(PlayerStanding standing) {
        return new StandingDto(
                standing.rank(),
                standing.playerId(),
                standing.name(),
                standing.rating(),
                standing.score(),
                standing.buchholz(),
                standing.medianBuchholz(),
                standing.sonnebornBerger(),
                standing.wins(),
                standing.active());
    }
}
