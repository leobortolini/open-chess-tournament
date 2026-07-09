package com.open.chess.tournament.application.dto;

import com.open.chess.tournament.domain.model.Pairing;
import com.open.chess.tournament.domain.model.Tournament;

import java.util.UUID;

public record PairingDto(
        UUID id,
        int board,
        UUID whitePlayerId,
        String whitePlayerName,
        UUID blackPlayerId,
        String blackPlayerName,
        String result,
        boolean bye) {

    public static PairingDto from(Tournament tournament, Pairing pairing) {
        return new PairingDto(
                pairing.getId(),
                pairing.getBoardNumber(),
                pairing.getWhitePlayerId(),
                playerName(tournament, pairing.getWhitePlayerId()),
                pairing.getBlackPlayerId(),
                pairing.isBye() ? null : playerName(tournament, pairing.getBlackPlayerId()),
                pairing.getResult().name(),
                pairing.isBye());
    }

    private static String playerName(Tournament tournament, UUID playerId) {
        return tournament.playerById(playerId)
                .map(player -> player.getName())
                .orElse(null);
    }
}
