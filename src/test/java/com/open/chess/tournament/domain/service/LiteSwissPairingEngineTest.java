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

class LiteSwissPairingEngineTest {

    private final LiteSwissPairingEngine engine = new LiteSwissPairingEngine();

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
                candidate(a, 1500, 1.0, Set.of(b), 1, 0, false,
                        PairingCandidate.WHITE, 0, 0),
                candidate(b, 1400, 0.0, Set.of(a), 0, 1, false,
                        PairingCandidate.BLACK, 0, 0),
                candidate(c, 1300, 1.0, Set.of(), 0, 0, true));

        PairingPlan plan = engine.generate(candidates);

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
        List<PairingCandidate> candidates = List.of(
                candidate(a, 1800, 1.0, Set.of(b), 1, 0, false,
                        PairingCandidate.WHITE, 0, 0),
                candidate(b, 1700, 1.0, Set.of(a), 0, 1, false,
                        PairingCandidate.BLACK, 0, 0),
                candidate(c, 1600, 0.0, Set.of(d), 1, 0, false,
                        PairingCandidate.WHITE, 0, 0),
                candidate(d, 1500, 0.0, Set.of(c), 0, 1, false,
                        PairingCandidate.BLACK, 0, 0));

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
                candidate(a, 1800, 1.0, Set.of(b), 1, 0, false,
                        PairingCandidate.WHITE, 0, 0),
                candidate(b, 1700, 0.0, Set.of(a), 0, 1, false,
                        PairingCandidate.BLACK, 0, 0));

        assertThrows(NoPairingPossibleException.class, () -> engine.generate(candidates));
    }

    @Test
    void reassignsByeWhenPreferredByeWouldForceARematch() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
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
    void colorsGoToThePlayerWithFewerWhiteGames() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        List<PairingCandidate> candidates = List.of(
                candidate(a, 1800, 1.0, Set.of(), 2, 0, false,
                        PairingCandidate.WHITE, 0, 0),
                candidate(b, 1700, 1.0, Set.of(), 0, 2, false,
                        PairingCandidate.BLACK, 0, 0));

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
    void absoluteColorPreferenceTakesPriority() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        List<PairingCandidate> candidates = List.of(
                candidate(a, 1800, 1.0, Set.of(), 4, 0, false,
                        PairingCandidate.WHITE, 0, 0),
                candidate(b, 1700, 1.0, Set.of(), 1, 1, false,
                        PairingCandidate.WHITE, 0, 0));

        PairingPlan plan = engine.generate(candidates);

        PairingPlan.Board board = plan.boards().getFirst();
        assertEquals(a, board.blackPlayerId());
        assertEquals(b, board.whitePlayerId());
    }

    @Test
    void bothWithAbsolutePreferenceReceiveTheirColor() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        List<PairingCandidate> candidates = List.of(
                candidate(a, 1800, 1.0, Set.of(), 0, 3, false,
                        PairingCandidate.BLACK, 0, 0),
                candidate(b, 1700, 1.0, Set.of(), 3, 0, false,
                        PairingCandidate.WHITE, 0, 0));

        PairingPlan plan = engine.generate(candidates);

        PairingPlan.Board board = plan.boards().getFirst();
        assertEquals(a, board.whitePlayerId());
        assertEquals(b, board.blackPlayerId());
    }

    @Test
    void colorAlternationWhenBalanceEqualAndLastColorsDiffer() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        List<PairingCandidate> candidates = List.of(
                candidate(a, 1800, 1.0, Set.of(), 1, 1, false,
                        PairingCandidate.BLACK, 0, 0),
                candidate(b, 1700, 1.0, Set.of(), 1, 1, false,
                        PairingCandidate.WHITE, 0, 0));

        PairingPlan plan = engine.generate(candidates);

        PairingPlan.Board board = plan.boards().getFirst();
        assertEquals(a, board.whitePlayerId());
        assertEquals(b, board.blackPlayerId());
    }

    @Test
    void byeNeverGoesToPlayerWhoAlreadyHadOne() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        List<PairingCandidate> candidates = List.of(
                candidate(a, 1800, 2.0, Set.of(c), 1, 1, false,
                        PairingCandidate.BLACK, 0, 0),
                candidate(b, 1700, 1.0, Set.of(), 1, 0, false,
                        PairingCandidate.WHITE, 0, 0),
                candidate(c, 1600, 1.0, Set.of(a), 0, 1, true,
                        PairingCandidate.BLACK, 0, 0));

        PairingPlan plan = engine.generate(candidates);

        assertEquals(a, plan.byePlayerId());
        assertEquals(1, plan.boards().size());
        assertPaired(plan, b, c);
    }

    @Test
    void throwsWhenOnlyPairingWouldRequireASecondBye() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        List<PairingCandidate> candidates = List.of(
                candidate(a, 1800, 1.5, Set.of(b), 1, 0, true,
                        PairingCandidate.WHITE, 0, 0),
                candidate(b, 1700, 1.0, Set.of(a), 0, 1, true,
                        PairingCandidate.BLACK, 0, 0),
                candidate(c, 1600, 0.5, Set.of(), 0, 0, false));

        assertThrows(NoPairingPossibleException.class, () -> engine.generate(candidates));
    }

    @Test
    void byePrefersPlayerWhoDidNotFloatDownLastRound() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        // b and c share the lowest score; c floated down last round, so
        // the bye (itself a downfloat) goes to b even though c is ranked
        // lower.
        List<PairingCandidate> candidates = List.of(
                candidate(a, 1800, 1.0, Set.of(), 1, 0, false,
                        PairingCandidate.WHITE, PairingCandidate.NONE, 0, 0, false, false),
                candidate(b, 1700, 0.0, Set.of(), 0, 1, false,
                        PairingCandidate.BLACK, PairingCandidate.NONE, 0, 0, false, false),
                candidate(c, 1600, 0.0, Set.of(), 0, 1, false,
                        PairingCandidate.BLACK, PairingCandidate.NONE, 1, 0, true, false));

        PairingPlan plan = engine.generate(candidates);

        assertEquals(b, plan.byePlayerId());
        assertEquals(1, plan.boards().size());
        assertPaired(plan, a, c);
    }

    @Test
    void avoidsPairingTwoPlayersWithSameAbsoluteColorPreference() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        List<PairingCandidate> candidates = List.of(
                candidate(a, 2000, 1.0, Set.of(), 2, 0, false,
                        PairingCandidate.WHITE, 0, 0),
                candidate(b, 1900, 1.0, Set.of(), 0, 2, false,
                        PairingCandidate.BLACK, 0, 0),
                candidate(c, 1800, 1.0, Set.of(), 2, 0, false,
                        PairingCandidate.WHITE, 0, 0),
                candidate(d, 1700, 1.0, Set.of(), 0, 2, false,
                        PairingCandidate.BLACK, 0, 0));

        PairingPlan plan = engine.generate(candidates);

        assertEquals(2, plan.boards().size());
        // Fold order would pair a (due black) with c (also due black);
        // the color rule forces every board to mix one player due white
        // with one due black, and each receives the due color.
        for (PairingPlan.Board board : plan.boards()) {
            assertTrue(board.whitePlayerId().equals(b) || board.whitePlayerId().equals(d),
                    "Players due white must receive white: " + board);
            assertTrue(board.blackPlayerId().equals(a) || board.blackPlayerId().equals(c),
                    "Players due black must receive black: " + board);
        }
    }

    @Test
    void relaxesColorRuleOnlyWhenNoAlternativeExists() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        List<PairingCandidate> candidates = List.of(
                candidate(a, 1800, 1.0, Set.of(), 2, 0, false,
                        PairingCandidate.WHITE, 0, 0),
                candidate(b, 1700, 1.0, Set.of(), 2, 0, false,
                        PairingCandidate.WHITE, 0, 0));

        PairingPlan plan = engine.generate(candidates);

        assertEquals(1, plan.boards().size());
        PairingPlan.Board board = plan.boards().getFirst();
        // Both are due black; the higher ranked player's preference prevails.
        assertEquals(b, board.whitePlayerId());
        assertEquals(a, board.blackPlayerId());
    }

    @Test
    void sameColorInLastTwoGamesCreatesAbsolutePreference() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        List<PairingCandidate> candidates = List.of(
                candidate(a, 1800, 1.0, Set.of(), 2, 1, false,
                        PairingCandidate.WHITE, PairingCandidate.WHITE, 0, 0, false, false),
                candidate(b, 1700, 1.0, Set.of(), 2, 1, false,
                        PairingCandidate.WHITE, PairingCandidate.BLACK, 0, 0, false, false));

        PairingPlan plan = engine.generate(candidates);

        PairingPlan.Board board = plan.boards().getFirst();
        // a played white twice in a row: black is now an absolute
        // preference, beating b's equal color balance.
        assertEquals(b, board.whitePlayerId());
        assertEquals(a, board.blackPlayerId());
    }

    @Test
    void balancedPlayerWithSameLastTwoColorsIsDueTheOtherColor() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        List<PairingCandidate> candidates = List.of(
                candidate(a, 1800, 1.0, Set.of(), 2, 2, false,
                        PairingCandidate.BLACK, PairingCandidate.BLACK, 0, 0, false, false),
                candidate(b, 1700, 1.0, Set.of(), 2, 2, false,
                        PairingCandidate.WHITE, PairingCandidate.BLACK, 0, 0, false, false));

        PairingPlan plan = engine.generate(candidates);

        PairingPlan.Board board = plan.boards().getFirst();
        // a has an even balance but played black twice in a row: white is
        // an absolute preference despite the balanced color history.
        assertEquals(a, board.whitePlayerId());
        assertEquals(b, board.blackPlayerId());
    }

    @Test
    void avoidsUpfloatingSamePlayerTwiceInARow() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        // a must pair down into the 0.5 group; b upfloated last round, so
        // c is lifted instead even though b is ranked higher. All players
        // share the same color preference so only the float criterion
        // separates the alternatives.
        List<PairingCandidate> candidates = List.of(
                candidate(a, 1800, 1.0, Set.of(), 1, 1, false,
                        PairingCandidate.WHITE, PairingCandidate.NONE, 0, 0, false, false),
                candidate(b, 1700, 0.5, Set.of(), 1, 1, false,
                        PairingCandidate.WHITE, PairingCandidate.NONE, 0, 1, false, true),
                candidate(c, 1600, 0.5, Set.of(), 1, 1, false,
                        PairingCandidate.WHITE, PairingCandidate.NONE, 0, 0, false, false),
                candidate(d, 1500, 0.0, Set.of(), 1, 1, false,
                        PairingCandidate.WHITE, PairingCandidate.NONE, 0, 0, false, false));

        PairingPlan plan = engine.generate(candidates);

        assertEquals(2, plan.boards().size());
        assertPaired(plan, a, c);
        assertPaired(plan, b, d);
    }

    @Test
    void minimizesScoreDifferencesGlobally() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        // a cannot meet c again. Pairing a-b and c-d gives two one-point
        // floats; pairing a-d would concentrate a two-point float on one
        // board and must be rejected by the global optimization.
        List<PairingCandidate> candidates = List.of(
                candidate(a, 1800, 2.0, Set.of(c), 1, 1, false,
                        PairingCandidate.WHITE, PairingCandidate.NONE, 0, 0, false, false),
                candidate(b, 1700, 1.0, Set.of(), 1, 1, false,
                        PairingCandidate.BLACK, PairingCandidate.NONE, 0, 0, false, false),
                candidate(c, 1600, 1.0, Set.of(a), 1, 1, false,
                        PairingCandidate.WHITE, PairingCandidate.NONE, 0, 0, false, false),
                candidate(d, 1500, 0.0, Set.of(), 1, 1, false,
                        PairingCandidate.BLACK, PairingCandidate.NONE, 0, 0, false, false));

        PairingPlan plan = engine.generate(candidates);

        assertEquals(2, plan.boards().size());
        assertPaired(plan, a, b);
        assertPaired(plan, c, d);
    }

    @Test
    void byeNeverGoesToForfeitWinner() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        // c is the lowest ranked player, but won a game by forfeit and is
        // therefore not eligible for the bye (FIDE C.04.1.d).
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
    void lowerGroupOpponentsSortedByUpfloatCount() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        List<PairingCandidate> candidates = List.of(
                candidate(a, 1800, 1.0, Set.of(b), 1, 0, false,
                        PairingCandidate.WHITE, 0, 0),
                candidate(b, 1700, 1.0, Set.of(a), 0, 1, false,
                        PairingCandidate.BLACK, 0, 0),
                candidate(c, 1600, 0.0, Set.of(), 0, 0, false),
                candidate(d, 1500, 0.0, Set.of(), 0, 0, false));

        PairingPlan plan = engine.generate(candidates);

        assertEquals(2, plan.boards().size());
        assertNull(plan.byePlayerId());
    }

    @Test
    void byeIsPreferredForLowestScorePlayerFirst() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        UUID e = UUID.randomUUID();
        List<PairingCandidate> candidates = List.of(
                candidate(a, 2000, 2.0, Set.of(b, c), 1, 1, false,
                        PairingCandidate.BLACK, 0, 0),
                candidate(b, 1900, 1.5, Set.of(a, d), 2, 0, false,
                        PairingCandidate.WHITE, 0, 0),
                candidate(c, 1800, 1.0, Set.of(a, d), 1, 1, false,
                        PairingCandidate.BLACK, 0, 0),
                candidate(d, 1700, 0.5, Set.of(b, c), 0, 2, false,
                        PairingCandidate.BLACK, 0, 0),
                candidate(e, 1600, 0.0, Set.of(), 0, 0, false));

        PairingPlan plan = engine.generate(candidates);

        assertNotNull(plan.byePlayerId());
        assertEquals(2, plan.boards().size());
    }

    @Test
    void multipleScoreGroupsPairedCorrectlyWithSixPlayers() {
        List<PairingCandidate> candidates = List.of(
                candidate(UUID.randomUUID(), 2000, 2.0, Set.of(),
                        2, 0, false, PairingCandidate.WHITE, 0, 0),
                candidate(UUID.randomUUID(), 1900, 2.0, Set.of(),
                        0, 2, false, PairingCandidate.BLACK, 0, 0),
                candidate(UUID.randomUUID(), 1800, 1.0, Set.of(),
                        1, 1, false, PairingCandidate.BLACK, 0, 0),
                candidate(UUID.randomUUID(), 1700, 1.0, Set.of(),
                        1, 1, false, PairingCandidate.WHITE, 0, 0),
                candidate(UUID.randomUUID(), 1600, 0.0, Set.of(),
                        0, 0, false, PairingCandidate.NONE, 0, 0),
                candidate(UUID.randomUUID(), 1500, 0.0, Set.of(),
                        0, 0, false, PairingCandidate.NONE, 0, 0));

        PairingPlan plan = engine.generate(candidates);

        assertEquals(3, plan.boards().size());
        assertNull(plan.byePlayerId());
    }

    @Test
    void allPlayersSameScoreFoldPairingWorks() {
        List<PairingCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            candidates.add(candidate(UUID.randomUUID(), 2000 - i * 10, 1.0,
                    Set.of(), 0, 0, false));
        }

        PairingPlan plan = engine.generate(candidates);

        assertEquals(5, plan.boards().size());
        assertNull(plan.byePlayerId());
        Set<UUID> seen = new HashSet<>();
        for (PairingPlan.Board board : plan.boards()) {
            assertTrue(seen.add(board.whitePlayerId()));
            assertTrue(seen.add(board.blackPlayerId()));
        }
        assertEquals(10, seen.size());
    }

    @Test
    void lowerAbsoluteColorPreferenceOverridesHigherMildPreference() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        List<PairingCandidate> candidates = List.of(
                candidate(a, 1800, 1.0, Set.of(), 1, 0, false,
                        PairingCandidate.WHITE, PairingCandidate.NONE, 0, 0, false, false),
                candidate(b, 1700, 1.0, Set.of(), 0, 3, false,
                        PairingCandidate.BLACK, PairingCandidate.NONE, 0, 0, false, false));

        PairingPlan plan = engine.generate(candidates);

        PairingPlan.Board board = plan.boards().getFirst();
        assertEquals(b, board.whitePlayerId());
        assertEquals(a, board.blackPlayerId());
    }

    @Test
    void alternatesColorsWhenHigherPlayedWhiteAndLowerPlayedBlack() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        List<PairingCandidate> candidates = List.of(
                candidate(a, 1800, 1.0, Set.of(), 1, 1, false,
                        PairingCandidate.WHITE, PairingCandidate.NONE, 0, 0, false, false),
                candidate(b, 1700, 1.0, Set.of(), 1, 1, false,
                        PairingCandidate.BLACK, PairingCandidate.NONE, 0, 0, false, false));

        PairingPlan plan = engine.generate(candidates);

        PairingPlan.Board board = plan.boards().getFirst();
        assertEquals(b, board.whitePlayerId());
        assertEquals(a, board.blackPlayerId());
    }

    @Test
    void playerWithNoGamesDelegatesColorChoiceToOpponent() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        List<PairingCandidate> candidates = List.of(
                candidate(a, 1800, 1.0, Set.of(), 0, 0, false,
                        PairingCandidate.NONE, PairingCandidate.NONE, 0, 0, false, false),
                candidate(b, 1700, 1.0, Set.of(), 1, 1, false,
                        PairingCandidate.WHITE, PairingCandidate.NONE, 0, 0, false, false));

        PairingPlan plan = engine.generate(candidates);

        PairingPlan.Board board = plan.boards().getFirst();
        assertEquals(a, board.whitePlayerId());
        assertEquals(b, board.blackPlayerId());
    }

    @Test
    void sameLastColorsGiveHigherRankedPreferenceChoice() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        List<PairingCandidate> candidates = List.of(
                candidate(a, 1800, 1.0, Set.of(), 1, 1, false,
                        PairingCandidate.WHITE, PairingCandidate.NONE, 0, 0, false, false),
                candidate(b, 1700, 1.0, Set.of(), 1, 1, false,
                        PairingCandidate.WHITE, PairingCandidate.NONE, 0, 0, false, false));

        PairingPlan plan = engine.generate(candidates);

        PairingPlan.Board board = plan.boards().getFirst();
        assertEquals(b, board.whitePlayerId());
        assertEquals(a, board.blackPlayerId());
    }

    @Test
    void exchangesWithinSameHalfWhenColorConstraintsPreventFoldPairing() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        List<PairingCandidate> candidates = List.of(
                candidate(a, 2000, 0.0, Set.of(d), 3, 0, false,
                        PairingCandidate.WHITE, PairingCandidate.NONE, 0, 0, false, false),
                candidate(b, 1900, 0.0, Set.of(c), 0, 3, false,
                        PairingCandidate.BLACK, PairingCandidate.NONE, 0, 0, false, false),
                candidate(c, 1800, 0.0, Set.of(b), 3, 0, false,
                        PairingCandidate.WHITE, PairingCandidate.NONE, 0, 0, false, false),
                candidate(d, 1700, 0.0, Set.of(a), 0, 3, false,
                        PairingCandidate.BLACK, PairingCandidate.NONE, 0, 0, false, false));

        PairingPlan plan = engine.generate(candidates);

        assertEquals(2, plan.boards().size());
        assertNull(plan.byePlayerId());
        assertPaired(plan, a, b);
        assertPaired(plan, c, d);
    }

    @Test
    void firstRoundAlternatesColorsAcrossBoards() {
        List<PairingCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            candidates.add(candidate(UUID.randomUUID(), 2000 - i * 100, 0.0,
                    Set.of(), 0, 0, false));
        }

        PairingPlan plan = engine.generate(candidates);

        assertEquals(3, plan.boards().size());
        assertEquals(candidates.get(0).playerId(), plan.boards().get(0).whitePlayerId());
        assertEquals(candidates.get(1).playerId(), plan.boards().get(1).blackPlayerId());
        assertEquals(candidates.get(2).playerId(), plan.boards().get(2).whitePlayerId());
    }

    @Test
    void higherScorePlayerFloatedDownLastRoundStillGetsPaired() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        List<PairingCandidate> candidates = List.of(
                candidate(a, 1800, 1.0, Set.of(), 1, 1, false,
                        PairingCandidate.WHITE, PairingCandidate.NONE, 1, 0, true, false),
                candidate(b, 1700, 0.0, Set.of(), 0, 0, false),
                candidate(c, 1600, 0.0, Set.of(), 0, 0, false));

        PairingPlan plan = engine.generate(candidates);

        assertEquals(1, plan.boards().size());
        assertNotNull(plan.byePlayerId());
        assertPaired(plan, a, b);
    }

    @Test
    void sameMildPreferenceCostDoesNotPreventFoldOptimalPairing() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        List<PairingCandidate> candidates = List.of(
                candidate(a, 2000, 0.0, Set.of(), 1, 1, false,
                        PairingCandidate.WHITE, PairingCandidate.NONE, 0, 0, false, false),
                candidate(b, 1900, 0.0, Set.of(), 1, 1, false,
                        PairingCandidate.WHITE, PairingCandidate.NONE, 0, 0, false, false),
                candidate(c, 1800, 0.0, Set.of(), 1, 1, false,
                        PairingCandidate.BLACK, PairingCandidate.NONE, 0, 0, false, false),
                candidate(d, 1700, 0.0, Set.of(), 1, 1, false,
                        PairingCandidate.BLACK, PairingCandidate.NONE, 0, 0, false, false));

        PairingPlan plan = engine.generate(candidates);

        assertEquals(2, plan.boards().size());
        assertNull(plan.byePlayerId());
        assertPaired(plan, a, c);
        assertPaired(plan, b, d);
    }

    @Test
    void handlesExtremeRatingDisparity() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        List<PairingCandidate> candidates = List.of(
                candidate(a, 2800, 1.0, Set.of(), 0, 0, false),
                candidate(b, 1200, 1.0, Set.of(), 0, 0, false),
                candidate(c, 2600, 0.0, Set.of(), 0, 0, false),
                candidate(d, 1100, 0.0, Set.of(), 0, 0, false));

        PairingPlan plan = engine.generate(candidates);

        assertEquals(2, plan.boards().size());
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

    private boolean noRematch(List<PairingCandidate> candidates, PairingPlan.Board board) {
        return candidates.stream()
                .filter(candidate -> candidate.playerId().equals(board.whitePlayerId()))
                .noneMatch(candidate -> candidate.hasPlayed(board.blackPlayerId()));
    }
}
