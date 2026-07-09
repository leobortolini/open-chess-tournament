package com.open.chess.tournament.application.dto;

import com.open.chess.tournament.domain.model.Round;
import com.open.chess.tournament.domain.model.Tournament;

import java.util.List;
import java.util.UUID;

public record RoundDto(UUID id, int number, boolean complete, List<PairingDto> pairings) {

    public static RoundDto from(Tournament tournament, Round round) {
        List<PairingDto> pairings = round.getPairings().stream()
                .map(pairing -> PairingDto.from(tournament, pairing))
                .toList();
        return new RoundDto(round.getId(), round.getNumber(), round.isComplete(), pairings);
    }
}
