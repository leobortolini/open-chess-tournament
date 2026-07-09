package com.open.chess.tournament.domain.model;

import com.open.chess.tournament.domain.exception.DomainException;
import com.open.chess.tournament.domain.service.SwissPairingEngine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TournamentTest {

    private final SwissPairingEngine engine = new SwissPairingEngine();

    @Test
    void fullTournamentLifecycle() {
        Tournament tournament = Tournament.create("Open", 3);
        Player p1 = tournament.registerPlayer("Alice", 2000);
        Player p2 = tournament.registerPlayer("Bob", 1900);
        Player p3 = tournament.registerPlayer("Carol", 1800);
        Player p4 = tournament.registerPlayer("Dave", 1700);
        tournament.start();
        assertEquals(TournamentStatus.IN_PROGRESS, tournament.getStatus());

        for (int roundNumber = 1; roundNumber <= 3; roundNumber++) {
            Round round = tournament.generateNextRound(engine).orElseThrow();
            assertEquals(roundNumber, round.getNumber());
            assertEquals(2, round.getPairings().size());
            for (Pairing pairing : round.getPairings()) {
                tournament.reportResult(pairing.getId(), GameResult.WHITE_WINS);
            }
        }

        assertEquals(TournamentStatus.FINISHED, tournament.getStatus());
        List<PlayerStanding> standings = tournament.standings();
        assertEquals(4, standings.size());
        double totalPoints = standings.stream().mapToDouble(PlayerStanding::score).sum();
        assertEquals(6.0, totalPoints);
        assertEquals(1, standings.get(0).rank());
        assertTrue(standings.get(0).score() >= standings.get(3).score());
    }

    @Test
    void cannotRegisterPlayersAfterStart() {
        Tournament tournament = Tournament.create("Open", 1);
        tournament.registerPlayer("Alice", 2000);
        tournament.registerPlayer("Bob", 1900);
        tournament.start();
        assertThrows(DomainException.class, () -> tournament.registerPlayer("Carol", 1800));
    }

    @Test
    void cannotGenerateRoundWithPendingResults() {
        Tournament tournament = Tournament.create("Open", 2);
        tournament.registerPlayer("Alice", 2000);
        tournament.registerPlayer("Bob", 1900);
        tournament.start();
        tournament.generateNextRound(engine);
        assertThrows(DomainException.class, () -> tournament.generateNextRound(engine));
    }

    @Test
    void finishesAutomaticallyWhenNoRematchFreePairingExists() {
        Tournament tournament = Tournament.create("Open", 3);
        tournament.registerPlayer("Alice", 2000);
        tournament.registerPlayer("Bob", 1900);
        tournament.start();

        Round round1 = tournament.generateNextRound(engine).orElseThrow();
        tournament.reportResult(round1.getPairings().getFirst().getId(), GameResult.DRAW);

        // Only possible round 2 pairing is the rematch Alice-Bob.
        assertTrue(tournament.generateNextRound(engine).isEmpty());
        assertEquals(TournamentStatus.FINISHED, tournament.getStatus());
        assertEquals(1, tournament.getRounds().size());
        assertThrows(DomainException.class, () -> tournament.generateNextRound(engine));
    }

    @Test
    void oddPlayerCountAssignsByeWorthOnePoint() {
        Tournament tournament = Tournament.create("Open", 1);
        tournament.registerPlayer("Alice", 2000);
        tournament.registerPlayer("Bob", 1900);
        tournament.registerPlayer("Carol", 1800);
        tournament.start();
        Round round = tournament.generateNextRound(engine).orElseThrow();

        Pairing bye = round.getPairings().stream()
                .filter(Pairing::isBye)
                .findFirst()
                .orElseThrow();
        assertEquals(GameResult.BYE, bye.getResult());
        assertEquals(1.0, tournament.scoreOf(bye.getWhitePlayerId()));
    }
}
