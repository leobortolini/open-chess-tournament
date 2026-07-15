package com.open.chess.tournament.domain.service;

import com.open.chess.tournament.domain.exception.NoPairingPossibleException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                        PairingCandidate.NONE, PairingCandidate.NONE, 0, 0, false, false));

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

    @Test
    void sixtyFourPlayersFirstRoundUsesExactFoldPairing() {
        List<UUID> byRank = new ArrayList<>();
        List<PairingCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            UUID id = UUID.randomUUID();
            byRank.add(id);
            candidates.add(candidate(id, 2000 - i, 0.0, Set.of(), 0, 0, false));
        }
        // The engine ranks its input itself; feeding it shuffled proves the
        // fold below comes from the ranking, not from the insertion order.
        Collections.shuffle(candidates, new Random(64));

        PairingPlan plan = engine.generate(candidates);

        assertEquals(32, plan.boards().size());
        assertNull(plan.byePlayerId());
        // 64 is the largest field with a strictly lexicographic tier
        // separation, and the fold (1v33, 2v34, ... 32v64) is the unique
        // zero-cost matching, so the whole round is pinned exactly.
        for (int board = 0; board < 32; board++) {
            UUID top = byRank.get(board);
            UUID bottom = byRank.get(board + 32);
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
    void absoluteColorRuleOutranksScoreDifferencesAcrossSixtyFourPlayers() {
        List<PairingCandidate> candidates = new ArrayList<>();
        Set<UUID> dueWhite = new HashSet<>();
        Set<UUID> dueBlack = new HashSet<>();
        for (int i = 0; i < 32; i++) {
            UUID white = UUID.randomUUID();
            dueWhite.add(white);
            candidates.add(candidate(white, 2000 - i, 1.0, Set.of(), 0, 2, false));
            UUID black = UUID.randomUUID();
            dueBlack.add(black);
            candidates.add(candidate(black, 1900 - i, 0.0, Set.of(), 2, 0, false));
        }

        PairingPlan plan = engine.generate(candidates);

        // Pairing inside the score groups would cost nothing in score
        // difference but would put two players due the same absolute color
        // on all 32 boards. The engine must instead float every board by a
        // full point, because one absolute color violation outweighs every
        // score difference a 32-board round can accumulate.
        assertEquals(32, plan.boards().size());
        for (PairingPlan.Board board : plan.boards()) {
            assertTrue(dueWhite.contains(board.whitePlayerId()),
                    "Player due white did not get white: " + board);
            assertTrue(dueBlack.contains(board.blackPlayerId()),
                    "Player due black did not get black: " + board);
        }
    }

    @Test
    void sixtyFourPlayersMinimizeUnavoidableColorConflicts() {
        List<PairingCandidate> candidates = new ArrayList<>();
        Set<UUID> dueWhite = new HashSet<>();
        Set<UUID> dueBlack = new HashSet<>();
        // 34 players due white against 30 due black, all on the same score:
        // at least two boards must pair two players due the same absolute
        // color, so the relaxation path is forced at full scale. Several
        // relaxed boards push the chosen matching's total weight past the
        // Blossom V 1e10 threshold, which must stay a per-edge bound.
        for (int i = 0; i < 34; i++) {
            UUID id = UUID.randomUUID();
            dueWhite.add(id);
            candidates.add(candidate(id, 2000 - i, 1.0, Set.of(), 0, 2, false));
        }
        for (int i = 0; i < 30; i++) {
            UUID id = UUID.randomUUID();
            dueBlack.add(id);
            candidates.add(candidate(id, 1900 - i, 1.0, Set.of(), 2, 0, false));
        }

        PairingPlan plan = engine.generate(candidates);

        assertEquals(32, plan.boards().size());
        int conflicted = 0;
        for (PairingPlan.Board board : plan.boards()) {
            boolean bothDueWhite = dueWhite.contains(board.whitePlayerId())
                    && dueWhite.contains(board.blackPlayerId());
            boolean bothDueBlack = dueBlack.contains(board.whitePlayerId())
                    && dueBlack.contains(board.blackPlayerId());
            assertFalse(bothDueBlack, "Two players due black were paired while due-white players were spare");
            if (bothDueWhite) {
                conflicted++;
            }
        }
        assertEquals(2, conflicted, "Engine must relax the color rule on the fewest boards possible");
    }

    @Test
    void sixtyFourPlayersPairEveryRoundWithinTimeBudget() {
        Map<UUID, PlayerState> states = new LinkedHashMap<>();
        for (int i = 0; i < 64; i++) {
            UUID id = UUID.randomUUID();
            states.put(id, new PlayerState(id, 2400 - i * 10));
        }
        Random random = new Random(64);

        System.out.println("Pairing time for 64 players — one shot per round, indicative only "
                + "(round 1 carries the JIT warm-up when this test runs on its own)");
        for (int round = 1; round <= 7; round++) {
            List<PairingCandidate> candidates = states.values().stream()
                    .map(PlayerState::toCandidate)
                    .toList();

            long startedAt = System.nanoTime();
            PairingPlan plan = engine.generate(candidates);
            long elapsedNanos = System.nanoTime() - startedAt;

            System.out.printf("  round %d: %4d edges, %7.2f ms%n",
                    round, countEdges(candidates), elapsedNanos / 1_000_000.0);

            assertEquals(32, plan.boards().size(), "round " + round);
            assertNull(plan.byePlayerId(), "an even field never takes a bye");
            Set<UUID> seen = new HashSet<>();
            for (PairingPlan.Board board : plan.boards()) {
                assertTrue(seen.add(board.whitePlayerId()), "round " + round);
                assertTrue(seen.add(board.blackPlayerId()), "round " + round);
                assertFalse(states.get(board.whitePlayerId()).opponents.contains(board.blackPlayerId()),
                        "rematch in round " + round);
            }
            // Blossom V on 64 vertices is milliseconds' work; this budget only
            // fires on an algorithmic regression, never on a loaded machine.
            assertTrue(elapsedNanos < TimeUnit.SECONDS.toNanos(5),
                    "round " + round + " took " + elapsedNanos / 1_000_000 + " ms");

            applyRound(states, plan, random);
        }
    }

    private int countEdges(List<PairingCandidate> candidates) {
        int edges = 0;
        for (int i = 0; i < candidates.size(); i++) {
            for (int j = i + 1; j < candidates.size(); j++) {
                if (!candidates.get(i).hasPlayed(candidates.get(j).playerId())) {
                    edges++;
                }
            }
        }
        return edges;
    }

    /** Plays out a round with random results and rolls the player states forward. */
    private void applyRound(Map<UUID, PlayerState> states, PairingPlan plan, Random random) {
        Map<UUID, Double> scoreBefore = new HashMap<>();
        states.values().forEach(state -> {
            scoreBefore.put(state.id, state.score);
            state.floatedDownLastRound = false;
            state.floatedUpLastRound = false;
        });

        for (PairingPlan.Board board : plan.boards()) {
            PlayerState white = states.get(board.whitePlayerId());
            PlayerState black = states.get(board.blackPlayerId());

            white.opponents.add(black.id);
            black.opponents.add(white.id);

            double whiteBefore = scoreBefore.get(white.id);
            double blackBefore = scoreBefore.get(black.id);
            if (whiteBefore > blackBefore + 0.01) {
                white.downFloats++;
                white.floatedDownLastRound = true;
                black.upFloats++;
                black.floatedUpLastRound = true;
            } else if (blackBefore > whiteBefore + 0.01) {
                black.downFloats++;
                black.floatedDownLastRound = true;
                white.upFloats++;
                white.floatedUpLastRound = true;
            }

            int roll = random.nextInt(100);
            if (roll < 30) {
                white.score += 0.5;
                black.score += 0.5;
            } else if (roll < 70) {
                white.score += 1.0;
            } else {
                black.score += 1.0;
            }

            white.previousColor = white.lastColor;
            white.lastColor = PairingCandidate.WHITE;
            white.whiteGames++;
            black.previousColor = black.lastColor;
            black.lastColor = PairingCandidate.BLACK;
            black.blackGames++;
        }
    }

    /**
     * Mutable mirror of what {@code Tournament} derives from its rounds, so
     * the engine can be driven across rounds without the aggregate.
     */
    private static final class PlayerState {
        private final UUID id;
        private final int rating;
        private final Set<UUID> opponents = new HashSet<>();
        private double score;
        private int whiteGames;
        private int blackGames;
        private int lastColor = PairingCandidate.NONE;
        private int previousColor = PairingCandidate.NONE;
        private int downFloats;
        private int upFloats;
        private boolean floatedDownLastRound;
        private boolean floatedUpLastRound;

        private PlayerState(UUID id, int rating) {
            this.id = id;
            this.rating = rating;
        }

        private PairingCandidate toCandidate() {
            return new PairingCandidate(id, rating, score, Set.copyOf(opponents),
                    whiteGames, blackGames, false, false, lastColor, previousColor,
                    downFloats, upFloats, floatedDownLastRound, floatedUpLastRound);
        }
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
                false, lastColor, PairingCandidate.NONE, downFloats, upFloats, false, false);
    }

    private PairingCandidate candidate(UUID id, int rating, double score, Set<UUID> opponents,
                                       int whiteGames, int blackGames, boolean hadBye,
                                       int lastColor, int previousColor, int downFloats, int upFloats,
                                       boolean floatedDownLastRound, boolean floatedUpLastRound) {
        return new PairingCandidate(id, rating, score, opponents, whiteGames, blackGames, hadBye,
                false, lastColor, previousColor, downFloats, upFloats,
                floatedDownLastRound, floatedUpLastRound);
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
