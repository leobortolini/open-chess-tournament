package com.open.chess.tournament.domain.service;

import com.open.chess.tournament.domain.exception.NoPairingPossibleException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the sequential Dutch procedure of {@link BracketSwissPairingEngine}:
 * bracket flow, the literal candidate ordering (transpositions Art. 4.2,
 * exchanges Art. 4.3, MDP pairing Art. 3.3.3), the criteria evaluation
 * (C1-C5, C12-C15) and the matching fallback for C3 relaxation.
 */
class BracketSwissPairingEngineTest {

    private final BracketSwissPairingEngine engine = new BracketSwissPairingEngine();

    @Test
    void rejectsFewerThanTwoPlayers() {
        List<PairingCandidate> candidates = List.of(
                candidate(UUID.randomUUID(), 1500, 0.0, Set.of(), 0, 0, false));

        assertThrows(NoPairingPossibleException.class, () -> engine.generate(candidates));
    }

    @Test
    void neverAllowsRematchesEvenWhenNoOtherPairingExists() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        List<PairingCandidate> candidates = List.of(
                candidate(a, 1800, 1.0, Set.of(b), 1, 0, false,
                        PairingCandidate.WHITE, 0, 0),
                candidate(b, 1700, 0.0, Set.of(a), 0, 1, false,
                        PairingCandidate.BLACK, 0, 0));

        assertThrows(NoPairingPossibleException.class, () -> engine.generate(candidates));
    }

    @Test
    void firstRoundUsesFoldPairingWithAlternatingColors() {
        List<UUID> byRank = new ArrayList<>();
        List<PairingCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            UUID id = UUID.randomUUID();
            byRank.add(id);
            candidates.add(candidate(id, 2000 - i * 10, 0.0, Set.of(), 0, 0, false));
        }

        PairingPlan plan = engine.generate(candidates);

        assertEquals(4, plan.boards().size());
        assertNull(plan.byePlayerId());
        for (int board = 0; board < 4; board++) {
            UUID top = byRank.get(board);
            UUID bottom = byRank.get(board + 4);
            PairingPlan.Board actual = plan.boards().get(board);
            if (board % 2 == 0) {
                assertEquals(top, actual.whitePlayerId(), "board " + board);
                assertEquals(bottom, actual.blackPlayerId(), "board " + board);
            } else {
                assertEquals(bottom, actual.whitePlayerId(), "board " + board);
                assertEquals(top, actual.blackPlayerId(), "board " + board);
            }
        }
    }

    @Test
    void oddFieldGivesByeToLowestRankedPlayer() {
        List<UUID> byRank = new ArrayList<>();
        List<PairingCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID id = UUID.randomUUID();
            byRank.add(id);
            candidates.add(candidate(id, 2000 - i * 10, 0.0, Set.of(), 0, 0, false));
        }

        PairingPlan plan = engine.generate(candidates);

        assertEquals(2, plan.boards().size());
        assertPaired(plan, byRank.get(0), byRank.get(2));
        assertPaired(plan, byRank.get(1), byRank.get(3));
        assertEquals(byRank.get(4), plan.byePlayerId());
    }

    @Test
    void transpositionSkipsARematch() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID p3 = UUID.randomUUID();
        UUID p4 = UUID.randomUUID();
        // Fold order would pair 1-3 and 2-4, but 1 already played 3: the
        // next transposition of S2 pairs 1-4 and 2-3.
        List<PairingCandidate> candidates = List.of(
                candidate(p1, 1800, 1.0, Set.of(p3), 1, 0, false,
                        PairingCandidate.WHITE, 0, 0),
                candidate(p2, 1700, 1.0, Set.of(), 1, 0, false,
                        PairingCandidate.WHITE, 0, 0),
                candidate(p3, 1600, 1.0, Set.of(p1), 0, 1, false,
                        PairingCandidate.BLACK, 0, 0),
                candidate(p4, 1500, 1.0, Set.of(), 0, 1, false,
                        PairingCandidate.BLACK, 0, 0));

        PairingPlan plan = engine.generate(candidates);

        assertEquals(2, plan.boards().size());
        assertPaired(plan, p1, p4);
        assertPaired(plan, p2, p3);
    }

    @Test
    void exchangeUsedWhenNoTranspositionIsLegal() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID p3 = UUID.randomUUID();
        UUID p4 = UUID.randomUUID();
        // 1 already played the whole of S2 ({3, 4}), so no transposition
        // can pair it: the first exchange (Art. 4.3) moves 2 into S2 and
        // pairs 1-2, leaving 3-4.
        List<PairingCandidate> candidates = List.of(
                candidate(p1, 1800, 1.0, Set.of(p3, p4), 1, 1, false,
                        PairingCandidate.WHITE, PairingCandidate.BLACK, 0, 0, false, false),
                candidate(p2, 1700, 1.0, Set.of(), 0, 0, false),
                candidate(p3, 1600, 1.0, Set.of(p1), 0, 1, false,
                        PairingCandidate.BLACK, 0, 0),
                candidate(p4, 1500, 1.0, Set.of(p1), 1, 0, false,
                        PairingCandidate.WHITE, 0, 0));

        PairingPlan plan = engine.generate(candidates);

        assertEquals(2, plan.boards().size());
        assertPaired(plan, p1, p2);
        assertPaired(plan, p3, p4);
    }

    @Test
    void mdpsPairTheTopOfTheReceivingGroup() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        UUID e = UUID.randomUUID();
        UUID f = UUID.randomUUID();
        // a and b already met, so both float into the 0.0 group: as MDPs
        // they take the top residents (c and d) and the remainder pairs
        // among itself.
        List<PairingCandidate> candidates = List.of(
                candidate(a, 2000, 1.0, Set.of(b), 1, 0, false,
                        PairingCandidate.WHITE, 0, 0),
                candidate(b, 1900, 1.0, Set.of(a), 0, 1, false,
                        PairingCandidate.BLACK, 0, 0),
                candidate(c, 1800, 0.0, Set.of(), 0, 0, false),
                candidate(d, 1700, 0.0, Set.of(), 0, 0, false),
                candidate(e, 1600, 0.0, Set.of(), 0, 0, false),
                candidate(f, 1500, 0.0, Set.of(), 0, 0, false));

        PairingPlan plan = engine.generate(candidates);

        assertEquals(3, plan.boards().size());
        assertPaired(plan, a, c);
        assertPaired(plan, b, d);
        assertPaired(plan, e, f);
    }

    @Test
    void floatersCascadeThroughBrackets() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        // C7: the MDP (a) pairs inside the 1.0 bracket and the resident c
        // floats on to d — floating the lowest score, never the MDP again.
        List<PairingCandidate> candidates = List.of(
                candidate(a, 1800, 2.0, Set.of(), 1, 1, false,
                        PairingCandidate.WHITE, PairingCandidate.BLACK, 0, 0, false, false),
                candidate(b, 1700, 1.0, Set.of(), 1, 1, false,
                        PairingCandidate.BLACK, PairingCandidate.WHITE, 0, 0, false, false),
                candidate(c, 1600, 1.0, Set.of(), 1, 1, false,
                        PairingCandidate.WHITE, PairingCandidate.BLACK, 0, 0, false, false),
                candidate(d, 1500, 0.0, Set.of(), 0, 0, false));

        PairingPlan plan = engine.generate(candidates);

        assertEquals(2, plan.boards().size());
        assertPaired(plan, a, b);
        assertPaired(plan, c, d);
    }

    @Test
    void byeGoesToTheLowestScoringPlayer() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        List<PairingCandidate> candidates = List.of(
                candidate(a, 1800, 1.0, Set.of(), 1, 0, false,
                        PairingCandidate.WHITE, 0, 0),
                candidate(b, 1700, 0.5, Set.of(), 0, 1, false,
                        PairingCandidate.BLACK, 0, 0),
                candidate(c, 1600, 0.0, Set.of(), 0, 1, false,
                        PairingCandidate.BLACK, 0, 0));

        PairingPlan plan = engine.generate(candidates);

        assertEquals(c, plan.byePlayerId());
        assertEquals(1, plan.boards().size());
        assertPaired(plan, a, b);
    }

    @Test
    void byeSkipsForfeitWinnerViaTransposition() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        // The natural candidate leaves c (lowest ranked) unpaired, but c
        // won by forfeit and cannot take the bye (C2): the transposition
        // leaves b instead.
        List<PairingCandidate> candidates = List.of(
                candidate(a, 1800, 0.5, Set.of(), 1, 0, false,
                        PairingCandidate.WHITE, 0, 0),
                candidate(b, 1700, 0.5, Set.of(), 0, 1, false,
                        PairingCandidate.BLACK, 0, 0),
                new PairingCandidate(c, 1600, 0.5, Set.of(), 0, 0, false, true,
                        PairingCandidate.NONE, PairingCandidate.NONE, 0, 0, false, false, false, false));

        PairingPlan plan = engine.generate(candidates);

        assertEquals(b, plan.byePlayerId());
        assertEquals(1, plan.boards().size());
        assertPaired(plan, a, c);
    }

    @Test
    void completionCriterionReassignsTheBye() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        // Giving the bye to c would force the rematch a-b (C4): the bye
        // goes to b even though c has the lowest score.
        List<PairingCandidate> candidates = List.of(
                candidate(a, 1800, 1.0, Set.of(b), 1, 0, false,
                        PairingCandidate.WHITE, 0, 0),
                candidate(b, 1700, 0.5, Set.of(a), 0, 1, false,
                        PairingCandidate.BLACK, 0, 0),
                candidate(c, 1600, 0.0, Set.of(), 0, 0, false));

        PairingPlan plan = engine.generate(candidates);

        assertEquals(b, plan.byePlayerId());
        assertEquals(1, plan.boards().size());
        assertPaired(plan, a, c);
    }

    @Test
    void avoidsFloaterWhoFloatedDownLastRound() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        // The natural candidate floats c, but c already floated down last
        // round (C14): the transposition floats b instead.
        List<PairingCandidate> candidates = List.of(
                candidate(a, 1800, 1.0, Set.of(), 1, 0, false,
                        PairingCandidate.WHITE, PairingCandidate.NONE, 0, 0, false, false),
                candidate(b, 1700, 1.0, Set.of(), 0, 1, false,
                        PairingCandidate.BLACK, PairingCandidate.NONE, 0, 0, false, false),
                candidate(c, 1600, 1.0, Set.of(), 0, 1, false,
                        PairingCandidate.BLACK, PairingCandidate.NONE, 1, 0, true, false),
                candidate(d, 1500, 0.0, Set.of(), 0, 0, false));

        PairingPlan plan = engine.generate(candidates);

        assertEquals(2, plan.boards().size());
        assertPaired(plan, a, c);
        assertPaired(plan, b, d);
    }

    @Test
    void colorPreferenceOutranksFloatRepeats() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        // a and c are both due black and b-c already met: pairing a-b and
        // floating c again (C14 defect) still beats pairing a-c (C12
        // defect), because colors outrank float repeats.
        List<PairingCandidate> candidates = List.of(
                candidate(a, 1800, 1.0, Set.of(), 1, 0, false,
                        PairingCandidate.WHITE, PairingCandidate.NONE, 0, 0, false, false),
                candidate(b, 1700, 1.0, Set.of(c), 0, 1, false,
                        PairingCandidate.BLACK, PairingCandidate.NONE, 0, 0, false, false),
                candidate(c, 1600, 1.0, Set.of(b), 1, 0, false,
                        PairingCandidate.WHITE, PairingCandidate.NONE, 1, 0, true, false),
                candidate(d, 1500, 0.0, Set.of(), 0, 0, false));

        PairingPlan plan = engine.generate(candidates);

        assertEquals(2, plan.boards().size());
        assertPaired(plan, a, b);
        assertPaired(plan, c, d);
    }

    @Test
    void avoidsUpfloatRepeatOpponentForTheMdp() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        // The MDP would take the top resident b, but b upfloated last
        // round (C15): the next arrangement pairs the MDP with c.
        List<PairingCandidate> candidates = List.of(
                candidate(a, 1800, 1.0, Set.of(), 1, 0, false,
                        PairingCandidate.WHITE, 0, 0),
                candidate(b, 1700, 0.0, Set.of(), 0, 1, false,
                        PairingCandidate.BLACK, PairingCandidate.NONE, 0, 1, false, true),
                candidate(c, 1600, 0.0, Set.of(), 0, 1, false,
                        PairingCandidate.BLACK, PairingCandidate.NONE, 0, 0, false, false));

        PairingPlan plan = engine.generate(candidates);

        assertEquals(1, plan.boards().size());
        assertPaired(plan, a, c);
        assertEquals(b, plan.byePlayerId());
    }

    @Test
    void sameAbsolutePreferencePairFallsBackToRelaxedColors() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        // Both are due black absolutely: the literal procedure rejects the
        // pair (C3) and the matching fallback pairs them anyway, granting
        // the higher ranked player's preference.
        List<PairingCandidate> candidates = List.of(
                candidate(a, 1800, 1.0, Set.of(), 2, 0, false,
                        PairingCandidate.WHITE, 0, 0),
                candidate(b, 1700, 1.0, Set.of(), 2, 0, false,
                        PairingCandidate.WHITE, 0, 0));

        PairingPlan plan = engine.generate(candidates);

        assertEquals(1, plan.boards().size());
        PairingPlan.Board board = plan.boards().getFirst();
        assertEquals(b, board.whitePlayerId());
        assertEquals(a, board.blackPlayerId());
    }

    @Test
    void playerWithoutGamesYieldsTheOpponentsDueColor() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        // a only had a bye (no color history); b played black and is due
        // white, so b receives white.
        List<PairingCandidate> candidates = List.of(
                candidate(a, 1800, 1.0, Set.of(), 0, 0, true),
                candidate(b, 1700, 1.0, Set.of(), 0, 1, false,
                        PairingCandidate.BLACK, 0, 0));

        PairingPlan plan = engine.generate(candidates);

        PairingPlan.Board board = plan.boards().getFirst();
        assertEquals(b, board.whitePlayerId());
        assertEquals(a, board.blackPlayerId());
    }

    @Test
    void everyPlayerIsPairedExactlyOnce() {
        List<PairingCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            candidates.add(candidate(UUID.randomUUID(), 1000 + i * 10, i % 3,
                    Set.of(), 0, 0, false));
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

    @Test
    void boardsAreOrderedTopBracketFirst() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        List<PairingCandidate> candidates = List.of(
                candidate(c, 1600, 0.0, Set.of(), 0, 0, false),
                candidate(a, 1800, 1.0, Set.of(), 1, 0, false,
                        PairingCandidate.WHITE, 0, 0),
                candidate(d, 1500, 0.0, Set.of(), 0, 0, false),
                candidate(b, 1700, 1.0, Set.of(), 0, 1, false,
                        PairingCandidate.BLACK, 0, 0));

        PairingPlan plan = engine.generate(candidates);

        assertEquals(2, plan.boards().size());
        PairingPlan.Board first = plan.boards().getFirst();
        assertTrue(first.whitePlayerId().equals(a) || first.blackPlayerId().equals(a),
                "The 1.0 bracket must come before the 0.0 bracket");
        assertPaired(plan, a, b);
        assertPaired(plan, c, d);
    }

    private PairingCandidate candidate(UUID id, int rating, double score, Set<UUID> opponents,
                                       int whiteGames, int blackGames, boolean hadBye) {
        return candidate(id, rating, score, opponents, whiteGames, blackGames, hadBye,
                PairingCandidate.NONE, 0, 0);
    }

    private PairingCandidate candidate(UUID id, int rating, double score, Set<UUID> opponents,
                                       int whiteGames, int blackGames, boolean hadBye,
                                       int lastColor, int downFloats, int upFloats) {
        return new PairingCandidate(id, rating, score, opponents, whiteGames, blackGames, hadBye,
                false, lastColor, PairingCandidate.NONE, downFloats, upFloats, false, false, false, false);
    }

    private PairingCandidate candidate(UUID id, int rating, double score, Set<UUID> opponents,
                                       int whiteGames, int blackGames, boolean hadBye,
                                       int lastColor, int previousColor, int downFloats, int upFloats,
                                       boolean floatedDownLastRound, boolean floatedUpLastRound) {
        return new PairingCandidate(id, rating, score, opponents, whiteGames, blackGames, hadBye,
                false, lastColor, previousColor, downFloats, upFloats,
                floatedDownLastRound, floatedUpLastRound, false, false);
    }

    private void assertPaired(PairingPlan plan, UUID one, UUID other) {
        boolean found = plan.boards().stream().anyMatch(board ->
                (board.whitePlayerId().equals(one) && board.blackPlayerId().equals(other))
                        || (board.whitePlayerId().equals(other) && board.blackPlayerId().equals(one)));
        assertTrue(found, "Expected " + one + " vs " + other + " in " + plan.boards());
    }
}
