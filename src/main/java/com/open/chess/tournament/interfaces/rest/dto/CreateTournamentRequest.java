package com.open.chess.tournament.interfaces.rest.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CreateTournamentRequest(
        @NotBlank String name,
        @Min(1) int totalRounds) {
}
