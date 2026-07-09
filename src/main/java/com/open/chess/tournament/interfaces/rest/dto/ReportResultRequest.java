package com.open.chess.tournament.interfaces.rest.dto;

import com.open.chess.tournament.domain.model.GameResult;
import jakarta.validation.constraints.NotNull;

public record ReportResultRequest(@NotNull GameResult result) {
}
