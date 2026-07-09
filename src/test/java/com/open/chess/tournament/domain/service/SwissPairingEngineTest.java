package com.open.chess.tournament.domain.service;

import com.open.chess.tournament.domain.exception.NoPairingPossibleException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwissPairingEngineTest {

    private final SwissPairingEngine engine = new SwissPairingEngine();

    @Test
    void firstRoundUsesFoldPairingTopHalfAgainstBottomHalf() {
        List<PairingCandidate> candidates = new ArrayList<>();
        Map<Integer, UUID> byRating = new HashMap<>();
        for (int rating = 1; rating <= 8; rating++) {
            UUID id = UUID.randomUUID();
            byRating.put(rating, id);
            candidates.add(candidate(id, rating * 100, 0.0, Set.of(), 0, 0, false));
        }

        PairingPlan plan = engine.generate(candidates);

        assertEquals(4, plan.boards().size());
        assertNull(plan.byePlayerId());
        // Ranked by rating desc: 800 must meet 400, 700 meets 300, etc.
        assertPaired(plan, byRating.get(8), byRating.get(4));
        assertPaired(plan, byRating.get(7), byRating.get(3));
        assertPaired(plan, byRating.get(6), byRating.get(2));
        assertPaired(plan, byRating.get(5), byRating.get(1));
    }

    @Test
    void oddNumberOfPlayersGivesByeToLowestRankedWithoutPreviousBye() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        List<PairingCandidate> candidates = List.of(
                candidate(a, 1500, 1.0, Set.of(b), 1, 0, false),
                candidate(b, 1400, 0.0, Set.of(a), 0, 1, false),
                candidate(c, 1300, 1.0, Set.of(), 0, 0, true));

        PairingPlan plan = engine.generate(candidates);

        // c is bottom-ranked... actually b has the lowest score, no previous bye.
        assertEquals(b, plan.byePlayerId());
        assertEquals(1, plan.boards().size());
        assertPaired(plan, a, c);
    }

    @Test
    void avoidsRematchesWhenPossible() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        // a-b and c-d already played; the only rematch-free perfect matching
        // pairs a with c or d, even though b has the same score as a.
        List<PairingCandidate> candidates = List.of(
                candidate(a, 1800, 1.0, Set.of(b), 1, 0, false),
                candidate(b, 1700, 1.0, Set.of(a), 0, 1, false),
                candidate(c, 1600, 0.0, Set.of(d), 1, 0, false),
                candidate(d, 1500, 0.0, Set.of(c), 0, 1, false));

        PairingPlan plan = engine.generate(candidates);

        assertEquals(2, plan.boards().size());
        for (PairingPlan.Board board : plan.boards()) {
            assertTrue(noRematch(candidates, board), "Board repeats a previous matchup: " + board);
        }
    }

    @Test
    void neverAllowsRematchesEvenWhenNoOtherPairingExists() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        List<PairingCandidate> candidates = List.of(
                candidate(a, 1800, 1.0, Set.of(b), 1, 0, false),
                candidate(b, 1700, 0.0, Set.of(a), 0, 1, false));

        assertThrows(NoPairingPossibleException.class, () -> engine.generate(candidates));
    }

    @Test
    void reassignsByeWhenPreferredByeWouldForceARematch() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        // a and b already played; giving the bye to c (bottom-ranked, no
        // previous bye) would force the rematch a-b, so b takes the bye.
        List<PairingCandidate> candidates = List.of(
                candidate(a, 1800, 1.0, Set.of(b), 1, 0, false),
                candidate(b, 1700, 0.5, Set.of(a), 0, 1, false),
                candidate(c, 1600, 0.0, Set.of(), 0, 0, false));

        PairingPlan plan = engine.generate(candidates);

        assertEquals(b, plan.byePlayerId());
        assertEquals(1, plan.boards().size());
        assertPaired(plan, a, c);
    }

    @Test
    void colorsGoToThePlayerWithFewerWhiteGames() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        List<PairingCandidate> candidates = List.of(
                candidate(a, 1800, 1.0, Set.of(), 2, 0, false),
                candidate(b, 1700, 1.0, Set.of(), 0, 2, false));

        PairingPlan plan = engine.generate(candidates);

        PairingPlan.Board board = plan.boards().getFirst();
        assertEquals(b, board.whitePlayerId());
        assertEquals(a, board.blackPlayerId());
    }

    @Test
    void rejectsFewerThanTwoPlayers() {
        List<PairingCandidate> candidates = List.of(
                candidate(UUID.randomUUID(), 1500, 0.0, Set.of(), 0, 0, false));
        assertThrows(NoPairingPossibleException.class, () -> engine.generate(candidates));
    }

    @Test
    void everyPlayerIsPairedExactlyOnce() {
        List<PairingCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            candidates.add(candidate(UUID.randomUUID(), 1000 + i * 10, i % 3, Set.of(), 0, 0, false));
        }

        PairingPlan plan = engine.generate(candidates);

        Set<UUID> seen = new HashSet<>();
        for (PairingPlan.Board board : plan.boards()) {
            assertTrue(seen.add(board.whitePlayerId()));
            assertTrue(seen.add(board.blackPlayerId()));
        }
        assertNotNull(plan.byePlayerId());
        assertTrue(seen.add(plan.byePlayerId()));
        assertEquals(9, seen.size());
    }

    private PairingCandidate candidate(UUID id, int rating, double score, Set<UUID> opponents,
                                       int whiteGames, int blackGames, boolean hadBye) {
        return new PairingCandidate(id, rating, score, opponents, whiteGames, blackGames, hadBye);
    }

    private void assertPaired(PairingPlan plan, UUID one, UUID other) {
        boolean found = plan.boards().stream().anyMatch(board ->
                (board.whitePlayerId().equals(one) && board.blackPlayerId().equals(other))
                        || (board.whitePlayerId().equals(other) && board.blackPlayerId().equals(one)));
        assertTrue(found, "Expected " + one + " vs " + other + " in " + plan.boards());
    }

    private boolean noRematch(List<PairingCandidate> candidates, PairingPlan.Board board) {
        return candidates.stream()
                .filter(candidate -> candidate.playerId().equals(board.whitePlayerId()))
                .noneMatch(candidate -> candidate.hasPlayed(board.blackPlayerId()));
    }
}
