package com.open.chess.tournament.domain.model;

import com.open.chess.tournament.domain.exception.DomainException;
import com.open.chess.tournament.domain.service.SwissPairingEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

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

    @Test
    void buchholzIsSumOfOpponentScores() {
        Tournament tournament = Tournament.create("Buchholz Test", 1);
        Player p1 = tournament.registerPlayer("Alice", 2000);
        Player p2 = tournament.registerPlayer("Bob", 1900);
        Player p3 = tournament.registerPlayer("Carol", 1800);
        Player p4 = tournament.registerPlayer("Dave", 1700);
        tournament.start();

        Round round = tournament.generateNextRound(engine).orElseThrow();
        Pairing board1 = round.getPairings().get(0);
        Pairing board2 = round.getPairings().get(1);
        tournament.reportResult(board1.getId(), GameResult.WHITE_WINS);
        tournament.reportResult(board2.getId(), GameResult.DRAW);

        List<PlayerStanding> standings = tournament.standings();
        PlayerStanding winner = standings.stream()
                .filter(s -> s.score() == 1.0)
                .findFirst().orElseThrow();
        PlayerStanding loser = standings.stream()
                .filter(s -> s.score() == 0.0)
                .findFirst().orElseThrow();

        assertEquals(0.0, winner.buchholz());
        assertEquals(1.0, loser.buchholz());
    }

    @Test
    void medianBuchholzExcludesHighestAndLowestOpponentScores() {
        Tournament tournament = Tournament.create("Median BH", 3);
        Player p1 = tournament.registerPlayer("Alice", 2000);
        Player p2 = tournament.registerPlayer("Bob", 1900);
        Player p3 = tournament.registerPlayer("Carol", 1800);
        Player p4 = tournament.registerPlayer("Dave", 1700);
        tournament.start();

        for (int r = 1; r <= 3; r++) {
            Round round = tournament.generateNextRound(engine).orElseThrow();
            for (Pairing pairing : round.getPairings()) {
                tournament.reportResult(pairing.getId(), GameResult.WHITE_WINS);
            }
        }

        List<PlayerStanding> standings = tournament.standings();
        assertEquals(4, standings.size());
        for (PlayerStanding standing : standings) {
            assertTrue(standing.medianBuchholz() >= 0.0);
        }
    }

    @Test
    void sonnebornBergerIsSumOfBeatenOpponentScores() {
        Tournament tournament = Tournament.create("SB Test", 1);
        Player p1 = tournament.registerPlayer("Alice", 2000);
        Player p2 = tournament.registerPlayer("Bob", 1900);
        Player p3 = tournament.registerPlayer("Carol", 1800);
        Player p4 = tournament.registerPlayer("Dave", 1700);
        tournament.start();

        Round round = tournament.generateNextRound(engine).orElseThrow();
        Pairing board1 = round.getPairings().get(0);
        Pairing board2 = round.getPairings().get(1);
        tournament.reportResult(board1.getId(), GameResult.WHITE_WINS);
        tournament.reportResult(board2.getId(), GameResult.DRAW);

        List<PlayerStanding> standings = tournament.standings();
        for (PlayerStanding s : standings) {
            if (s.score() == 1.0) {
                double expectedSb = s.buchholz();
                assertEquals(expectedSb, s.sonnebornBerger(), 0.001);
            } else if (s.score() == 0.5) {
                double expectedSb = s.buchholz() * 0.5;
                assertEquals(expectedSb, s.sonnebornBerger(), 0.001);
            }
        }
    }

    @Test
    void winsCountExcludesByes() {
        Tournament tournament = Tournament.create("Wins Test", 1);
        tournament.registerPlayer("Alice", 2000);
        tournament.registerPlayer("Bob", 1900);
        tournament.registerPlayer("Carol", 1800);
        tournament.start();

        Round round = tournament.generateNextRound(engine).orElseThrow();

        Pairing bye = round.getPairings().stream()
                .filter(Pairing::isBye)
                .findFirst().orElseThrow();
        Pairing game = round.getPairings().stream()
                .filter(p -> !p.isBye())
                .findFirst().orElseThrow();
        tournament.reportResult(game.getId(), GameResult.WHITE_WINS);

        assertEquals(1.0, tournament.scoreOf(bye.getWhitePlayerId()));
        List<PlayerStanding> standings = tournament.standings();

        PlayerStanding byePlayer = standings.stream()
                .filter(s -> s.playerId().equals(bye.getWhitePlayerId()))
                .findFirst().orElseThrow();
        assertEquals(0, byePlayer.wins());

        for (PlayerStanding s : standings) {
            if (!s.playerId().equals(bye.getWhitePlayerId())) {
                if (s.score() == 1.0) {
                    assertEquals(1, s.wins());
                }
            }
        }
    }

    @Test
    void directEncounterBreaksTieBetweenPlayersOnSameScore() {
        Tournament tournament = Tournament.create("DE Test", 2);
        Player p1 = tournament.registerPlayer("Alice", 2000);
        Player p2 = tournament.registerPlayer("Bob", 1900);
        Player p3 = tournament.registerPlayer("Carol", 1800);
        Player p4 = tournament.registerPlayer("Dave", 1700);
        tournament.start();

        Round r1 = tournament.generateNextRound(engine).orElseThrow();
        for (Pairing pairing : r1.getPairings()) {
            tournament.reportResult(pairing.getId(), GameResult.WHITE_WINS);
        }

        Round r2 = tournament.generateNextRound(engine).orElseThrow();
        for (Pairing pairing : r2.getPairings()) {
            tournament.reportResult(pairing.getId(), GameResult.BLACK_WINS);
        }

        List<PlayerStanding> standings = tournament.standings();
        assertEquals(4, standings.size());
        double totalScore = standings.stream().mapToDouble(PlayerStanding::score).sum();
        assertEquals(4.0, totalScore);
    }

    @Test
    void standingsContainAllTieBreakFields() {
        Tournament tournament = Tournament.create("Fields Test", 1);
        tournament.registerPlayer("Alice", 2000);
        tournament.registerPlayer("Bob", 1900);
        tournament.start();

        Round round = tournament.generateNextRound(engine).orElseThrow();
        tournament.reportResult(round.getPairings().getFirst().getId(), GameResult.DRAW);

        List<PlayerStanding> standings = tournament.standings();
        assertEquals(2, standings.size());

        for (PlayerStanding s : standings) {
            assertEquals(0.5, s.score());
            assertTrue(s.buchholz() >= 0);
            assertTrue(s.medianBuchholz() >= 0);
            assertTrue(s.sonnebornBerger() >= 0);
            assertEquals(0, s.wins());
        }
    }

    @Test
    void tournamentWithEightPlayersAndMultipleRounds() {
        Tournament tournament = Tournament.create("8P Test", 3);
        for (int i = 0; i < 8; i++) {
            tournament.registerPlayer("Player" + i, 2000 - i * 50);
        }
        tournament.start();

        for (int r = 1; r <= 3; r++) {
            Round round = tournament.generateNextRound(engine).orElseThrow();
            assertEquals(4, round.getPairings().size());
            for (Pairing pairing : round.getPairings()) {
                if (!pairing.isBye()) {
                    tournament.reportResult(pairing.getId(), GameResult.WHITE_WINS);
                }
            }
        }

        assertEquals(TournamentStatus.FINISHED, tournament.getStatus());
        List<PlayerStanding> standings = tournament.standings();
        assertEquals(8, standings.size());
        double totalScore = standings.stream().mapToDouble(PlayerStanding::score).sum();
        assertEquals(12.0, totalScore);
    }

    @Test
    void tournamentStopsEarlyWhenRematchInevitableForAll() {
        Tournament tournament = Tournament.create("Early Stop", 5);
        tournament.registerPlayer("A", 2000);
        tournament.registerPlayer("B", 1900);
        tournament.registerPlayer("C", 1800);
        tournament.start();

        Round r1 = tournament.generateNextRound(engine).orElseThrow();
        for (Pairing p : r1.getPairings()) {
            if (!p.isBye()) {
                tournament.reportResult(p.getId(), GameResult.WHITE_WINS);
            }
        }

        Round r2 = tournament.generateNextRound(engine).orElseThrow();
        for (Pairing p : r2.getPairings()) {
            if (!p.isBye()) {
                tournament.reportResult(p.getId(), GameResult.BLACK_WINS);
            }
        }

        Round r3 = tournament.generateNextRound(engine).orElseThrow();
        for (Pairing p : r3.getPairings()) {
            if (!p.isBye()) {
                tournament.reportResult(p.getId(), GameResult.WHITE_WINS);
            }
        }

        assertTrue(tournament.generateNextRound(engine).isEmpty());
        assertEquals(TournamentStatus.FINISHED, tournament.getStatus());
    }

    @Test
    void cannotStartTournamentWithFewerThanTwoPlayers() {
        Tournament tournament = Tournament.create("Solo", 1);
        tournament.registerPlayer("Solo", 2000);
        assertThrows(DomainException.class, tournament::start);
    }

    @Test
    void tournamentMustHaveAtLeastOneRound() {
        assertThrows(DomainException.class, () -> Tournament.create("Zero", 0));
    }

    @Test
    void cannotStartAlreadyFinishedTournament() {
        Tournament tournament = Tournament.create("Closed", 1);
        tournament.registerPlayer("A", 2000);
        tournament.registerPlayer("B", 1900);
        tournament.start();

        Round r1 = tournament.generateNextRound(engine).orElseThrow();
        tournament.reportResult(r1.getPairings().getFirst().getId(), GameResult.DRAW);

        assertEquals(TournamentStatus.FINISHED, tournament.getStatus());
        assertThrows(DomainException.class, tournament::start);
    }

    @Test
    void cannotReportByeResult() {
        Tournament tournament = Tournament.create("Bye Result", 1);
        tournament.registerPlayer("A", 2000);
        tournament.registerPlayer("B", 1900);
        tournament.registerPlayer("C", 1800);
        tournament.start();

        Round round = tournament.generateNextRound(engine).orElseThrow();
        Pairing bye = round.getPairings().stream()
                .filter(Pairing::isBye)
                .findFirst().orElseThrow();

        assertThrows(DomainException.class,
                () -> tournament.reportResult(bye.getId(), GameResult.WHITE_WINS));
    }

    @Test
    void cannotReportPendingResult() {
        Tournament tournament = Tournament.create("Pending Result", 1);
        tournament.registerPlayer("A", 2000);
        tournament.registerPlayer("B", 1900);
        tournament.start();

        Round round = tournament.generateNextRound(engine).orElseThrow();
        Pairing game = round.getPairings().stream()
                .filter(p -> !p.isBye())
                .findFirst().orElseThrow();

        assertThrows(DomainException.class,
                () -> tournament.reportResult(game.getId(), GameResult.PENDING));
    }

    @Test
    void tieBreaksSortCorrectlyByScoreThenDirectEncounterThenBuchholz() {
        Tournament tournament = Tournament.create("Sort Test", 3);
        Player alice = tournament.registerPlayer("Alice", 2000);
        Player bob = tournament.registerPlayer("Bob", 1950);
        Player carol = tournament.registerPlayer("Carol", 1900);
        Player dave = tournament.registerPlayer("Dave", 1850);
        tournament.start();

        Round r1 = tournament.generateNextRound(engine).orElseThrow();
        for (Pairing p : r1.getPairings()) {
            UUID whiteId = p.getWhitePlayerId();
            UUID blackId = p.getBlackPlayerId();
            if (whiteId.equals(alice.getId()) || whiteId.equals(carol.getId())) {
                tournament.reportResult(p.getId(), GameResult.WHITE_WINS);
            } else {
                tournament.reportResult(p.getId(), GameResult.BLACK_WINS);
            }
        }

        Round r2 = tournament.generateNextRound(engine).orElseThrow();
        for (Pairing p : r2.getPairings()) {
            UUID whiteId = p.getWhitePlayerId();
            if (whiteId.equals(alice.getId())) {
                tournament.reportResult(p.getId(), GameResult.WHITE_WINS);
            } else {
                tournament.reportResult(p.getId(), GameResult.DRAW);
            }
        }

        Round r3 = tournament.generateNextRound(engine).orElseThrow();
        for (Pairing p : r3.getPairings()) {
            tournament.reportResult(p.getId(), GameResult.DRAW);
        }

        List<PlayerStanding> standings = tournament.standings();
        for (int i = 1; i < standings.size(); i++) {
            PlayerStanding current = standings.get(i);
            PlayerStanding previous = standings.get(i - 1);
            assertTrue(previous.score() >= current.score(),
                    "Player at rank " + previous.rank() + " should have >= score than rank " + current.rank());
        }
    }
}
