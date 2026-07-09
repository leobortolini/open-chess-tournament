package com.open.chess.tournament.application.dto;

import com.open.chess.tournament.domain.model.Tournament;

import java.time.Instant;
import java.util.UUID;

public record TournamentDto(
        UUID id,
        String name,
        int totalRounds,
        int roundsGenerated,
        String status,
        int playerCount,
        Instant createdAt) {

    public static TournamentDto from(Tournament tournament) {
        return new TournamentDto(
                tournament.getId(),
                tournament.getName(),
                tournament.getTotalRounds(),
                tournament.getRounds().size(),
                tournament.getStatus().name(),
                tournament.getPlayers().size(),
                tournament.getCreatedAt());
    }
}
