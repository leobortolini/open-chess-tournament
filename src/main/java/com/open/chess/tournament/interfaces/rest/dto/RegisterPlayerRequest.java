package com.open.chess.tournament.interfaces.rest.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record RegisterPlayerRequest(
        @NotBlank String name,
        @Min(0) int rating) {
}
