package com.open.chess.tournament.domain.service;

import java.util.List;
import java.util.UUID;

public record PairingPlan(List<Board> boards, UUID byePlayerId) {

    public record Board(UUID whitePlayerId, UUID blackPlayerId) {
    }
}
