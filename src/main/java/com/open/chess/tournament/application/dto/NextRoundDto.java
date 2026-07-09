package com.open.chess.tournament.application.dto;

public record NextRoundDto(String tournamentStatus, RoundDto round, String message) {

    public static NextRoundDto generated(String tournamentStatus, RoundDto round) {
        return new NextRoundDto(tournamentStatus, round, null);
    }

    public static NextRoundDto tournamentFinished(String tournamentStatus) {
        return new NextRoundDto(tournamentStatus, null,
                "Tournament finished automatically: no rematch-free pairing is possible");
    }

    public boolean roundGenerated() {
        return round != null;
    }
}
