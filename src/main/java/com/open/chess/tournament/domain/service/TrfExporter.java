package com.open.chess.tournament.domain.service;

import com.open.chess.tournament.domain.exception.DomainException;
import com.open.chess.tournament.domain.model.GameResult;
import com.open.chess.tournament.domain.model.Pairing;
import com.open.chess.tournament.domain.model.Player;
import com.open.chess.tournament.domain.model.Round;
import com.open.chess.tournament.domain.model.Tournament;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Exports a tournament to the FIDE Tournament Report File format (TRF16,
 * FIDE Handbook C.04.A), the format consumed by FIDE-endorsed pairing
 * engines such as JaVaFo and used for rating submission.

 * Pairing numbers are assigned by rating (highest first), matching the
 * initial ranking used by {@link SwissPairingEngine}. Unplayed games use
 * the standard codes: {@code U} for the pairing-allocated bye, {@code +}
 * and {@code -} for forfeit results, with {@code -} as the color. All
 * reported rounds must be complete: pending results cannot be exported.
 */
public class TrfExporter {

    public String export(Tournament tournament) {
        List<Player> byRank = tournament.getPlayers().stream()
                .sorted(Comparator.comparingInt(Player::getRating).reversed()
                        .thenComparing(player -> player.getId().toString()))
                .toList();
        Map<UUID, Integer> startRank = new HashMap<>();
        for (int i = 0; i < byRank.size(); i++) {
            startRank.put(byRank.get(i).getId(), i + 1);
        }

        StringBuilder trf = new StringBuilder();
        trf.append("012 ").append(tournament.getName()).append('\n');
        trf.append("XXR ").append(tournament.getTotalRounds()).append('\n');
        trf.append("XXC white1").append('\n');
        for (Player player : byRank) {
            int rank = startRank.get(player.getId());
            trf.append(String.format(Locale.ROOT,
                    "001 %4d %1s%3s %-33.33s %4d %3s %11s %10s %4.1f %4d",
                    rank, "", "", player.getName(), player.getRating(),
                    "", "", "", tournament.scoreOf(player.getId()), rank));
            for (Round round : tournament.getRounds()) {
                trf.append(roundBlock(player.getId(), round, startRank));
            }
            trf.append('\n');
        }
        return trf.toString();
    }

    private String roundBlock(UUID playerId, Round round, Map<UUID, Integer> startRank) {
        Pairing pairing = round.getPairings().stream()
                .filter(p -> p.involves(playerId))
                .findFirst()
                .orElse(null);
        if (pairing == null) {
            return "  0000 - Z";
        }
        if (pairing.isBye()) {
            return "  0000 - U";
        }
        boolean isWhite = pairing.getWhitePlayerId().equals(playerId);
        int opponent = startRank.get(pairing.opponentOf(playerId));
        GameResult result = pairing.getResult();
        char color = result.isPlayedGame() ? (isWhite ? 'w' : 'b') : '-';
        char outcome = switch (result) {
            case WHITE_WINS -> isWhite ? '1' : '0';
            case BLACK_WINS -> isWhite ? '0' : '1';
            case DRAW -> '=';
            case WHITE_WINS_FORFEIT -> isWhite ? '+' : '-';
            case BLACK_WINS_FORFEIT -> isWhite ? '-' : '+';
            case DOUBLE_FORFEIT -> '-';
            case PENDING, BYE -> throw new DomainException(
                    "Cannot export a TRF with pending results in round " + round.getNumber());
        };
        return String.format(Locale.ROOT, "  %4d %c %c", opponent, color, outcome);
    }
}
