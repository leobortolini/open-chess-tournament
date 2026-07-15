package com.open.chess.tournament.domain.service;

import com.open.chess.tournament.domain.exception.NoPairingPossibleException;
import com.open.chess.tournament.domain.service.PairingPlan.Board;
import org.jgrapht.alg.matching.blossom.v5.KolmogorovWeightedPerfectMatching;
import org.jgrapht.alg.matching.blossom.v5.ObjectiveSense;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleWeightedGraph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Swiss-system pairing engine implementing the FIDE (Dutch) criteria as a
 * globally optimal minimum-weight perfect matching (Blossom V), the same
 * architecture used by FIDE-endorsed engines such as bbpPairings.

 * Players are ranked by score, then rating. Every legal round (no rematch,
 * no second bye, no bye for a forfeit winner) is a perfect matching of a
 * graph whose edge weights encode the FIDE quality criteria in strictly
 * decreasing tiers:
 *   1. C.3  — two players with the same absolute color preference (color
 *             difference of two or more, or same color twice in a row)
 *             shall not meet; violated only when no legal round avoids it.
 *   2. C.6  — minimize score differences (quadratic, so one large float is
 *             worse than several small ones); the bye is treated as a game
 *             against a virtual opponent on zero points.
 *   3. C.8  — minimize pairs where both players are due the same color.
 *   4. C.12/C.13 — avoid floating a player in the same direction (or
 *             giving the bye to a downfloater) two rounds in a row.
 *   5. D    — inside a score group, pair the top half (S1) against the
 *             bottom half (S2) in fold order (1 vs n/2+1, ...), treating
 *             same-half pairs as exchanges that rank below any S1-S2
 *             transposition; floaters meet the top of the receiving
 *             group, and the bye goes to the lowest-ranked eligible
 *             player.
 * Weights must stay below the Blossom V implementation's 1e10 feasibility
 * threshold, which bounds how far apart the tiers can sit: the ordering
 * is strictly lexicographic for fields of up to 64 players, and remains a
 * faithful weighted approximation beyond that. If the graph has no
 * perfect matching, no legal round exists and a domain error is raised.
 */
public class SwissPairingEngine {

    private static final double COST_SAME_ABSOLUTE_COLOR = 6e9;
    private static final double COST_SCORE_DIFF_UNIT = 5e6;
    private static final double COST_SAME_COLOR_PREFERENCE = 1e5;
    private static final double COST_BOTH_STRONG_PREFERENCE = 5e4;
    private static final double COST_FLOAT_REPEAT = 1200;
    private static final double COST_HALF_EXCHANGE = 20;
    private static final int SCORE_DIFF_CAP = 36;
    private static final int FOLD_DEVIATION_CAP = 10;
    private static final int UPFLOAT_POSITION_CAP = 10;

    public PairingPlan generate(List<PairingCandidate> candidates) {
        if (candidates.size() < 2) {
            throw new NoPairingPossibleException("At least two active players are required to pair a round");
        }
        //TODO: Check whether we need to sort using the tournament's tie-breaking criteria instead of rating, after sorting by score.
        //      If so, we will need to make some changes to retrieve this information, which is not accessible (so far) in PairingCandidate.
        //      I think we can just add the new attribute, but I'm not sure right now
        List<PairingCandidate> ranked = candidates.stream()
                .sorted(Comparator
                        .comparingDouble(PairingCandidate::score).reversed()
                        .thenComparing(Comparator.comparingInt(PairingCandidate::rating).reversed())
                        .thenComparing(candidate -> candidate.playerId().toString()))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);

        int size = ranked.size();
        SimpleWeightedGraph<Integer, DefaultWeightedEdge> graph = new SimpleWeightedGraph<>(DefaultWeightedEdge.class);
        for (int i = 0; i < size; i++) {
            graph.addVertex(i);
        }
        int[] groupStart = groupStarts(ranked);
        for (int i = 0; i < size; i++) {
            for (int j = i + 1; j < size; j++) {
                if (ranked.get(i).hasPlayed(ranked.get(j).playerId())) {
                    continue;
                }
                DefaultWeightedEdge edge = graph.addEdge(i, j);
                graph.setEdgeWeight(edge, pairCost(ranked, groupStart, i, j));
            }
        }
        if (size % 2 != 0) {
            graph.addVertex(size);
            for (int i = 0; i < size; i++) {
                if (!ranked.get(i).eligibleForBye()) {
                    continue;
                }
                DefaultWeightedEdge edge = graph.addEdge(i, size);
                graph.setEdgeWeight(edge, byeCost(ranked, i));
            }
        }

        List<int[]> pairs = new ArrayList<>();
        UUID byePlayerId = null;
        try {
            var matching = new KolmogorovWeightedPerfectMatching<>(graph, ObjectiveSense.MINIMIZE)
                    .getMatching();
            for (DefaultWeightedEdge edge : matching.getEdges()) {
                int a = graph.getEdgeSource(edge);
                int b = graph.getEdgeTarget(edge);
                if (a == size || b == size) {
                    byePlayerId = ranked.get(Math.min(a, b)).playerId();
                } else {
                    pairs.add(new int[]{Math.min(a, b), Math.max(a, b)});
                }
            }
        } catch (IllegalArgumentException noPerfectMatching) {
            throw new NoPairingPossibleException("Unable to generate a round without rematches or a second bye");
        }

        pairs.sort(Comparator.comparingInt(pair -> pair[0]));
        List<Board> boards = new ArrayList<>(pairs.size());
        for (int i = 0; i < pairs.size(); i++) {
            boards.add(assignColors(ranked.get(pairs.get(i)[0]), ranked.get(pairs.get(i)[1]), i));
        }
        return new PairingPlan(boards, byePlayerId);
    }

    /** For each ranked index, the index where its score group begins. */
    private int[] groupStarts(List<PairingCandidate> ranked) {
        int[] starts = new int[ranked.size()];
        int start = 0;
        for (int i = 0; i < ranked.size(); i++) {
            if (ranked.get(i).score() != ranked.get(start).score()) {
                start = i;
            }
            starts[i] = start;
        }
        return starts;
    }

    private double pairCost(List<PairingCandidate> ranked, int[] groupStart, int i, int j) {
        PairingCandidate higher = ranked.get(i);
        PairingCandidate lower = ranked.get(j);
        double cost = 0.0;
        boolean samePreference = higher.colorPreference() != PairingCandidate.NONE && higher.colorPreference() == lower.colorPreference();

        if (samePreference && higher.hasAbsoluteColorPreference() && lower.hasAbsoluteColorPreference()) {
            cost += COST_SAME_ABSOLUTE_COLOR;
        } else if (samePreference) {
            cost += COST_SAME_COLOR_PREFERENCE;
            if (higher.hasStrongColorPreference() && lower.hasStrongColorPreference()) {
                cost += COST_BOTH_STRONG_PREFERENCE;
            }
        }

        int doubledDiff = (int) Math.round((higher.score() - lower.score()) * 2);

        if (doubledDiff > 0) {
            cost += COST_SCORE_DIFF_UNIT * Math.min(doubledDiff * doubledDiff, SCORE_DIFF_CAP);
            if (higher.floatedDownLastRound()) {
                cost += COST_FLOAT_REPEAT;
            }
            if (lower.floatedUpLastRound()) {
                cost += COST_FLOAT_REPEAT;
            }
            // The floater should meet the top of the receiving group.
            cost += Math.min(j - groupStart[j], UPFLOAT_POSITION_CAP);
        } else {
            // Inside a score group the Dutch rules pair the top half (S1)
            // against the bottom half (S2); a pair within the same half is
            // an exchange, which ranks below any S1-S2 transposition.
            int half = groupSize(ranked, groupStart, i) / 2;
            int posHigher = i - groupStart[i];
            int posLower = j - groupStart[j];
            if (posLower < half || posHigher >= half) {
                cost += COST_HALF_EXCHANGE;
            } else {
                cost += Math.min(Math.abs((posLower - half) - posHigher), FOLD_DEVIATION_CAP);
            }
        }
        return cost;
    }

    /**
     * The bye is a downfloat to a virtual opponent on zero points: it costs
     * the player's own score, avoids players who floated down in the
     * previous round, and prefers the lowest-ranked eligible player.
     */
    private double byeCost(List<PairingCandidate> ranked, int i) {
        PairingCandidate candidate = ranked.get(i);
        int doubledScore = (int) Math.round(candidate.score() * 2);
        double cost = COST_SCORE_DIFF_UNIT * Math.min(doubledScore * doubledScore, SCORE_DIFF_CAP);
        if (candidate.floatedDownLastRound()) {
            cost += COST_FLOAT_REPEAT;
        }
        cost += ranked.size() - 1 - i;
        return cost;
    }

    private int groupSize(List<PairingCandidate> ranked, int[] groupStart, int memberIndex) {
        int start = groupStart[memberIndex];
        int end = start;
        while (end < ranked.size() && groupStart[end] == start) {
            end++;
        }
        return end - start;
    }

    private Board assignColors(PairingCandidate higher, PairingCandidate lower, int boardIndex) {
        int higherPref = higher.colorPreference();
        int lowerPref = lower.colorPreference();

        Board higherWhite = new Board(higher.playerId(), lower.playerId());
        Board higherBlack = new Board(lower.playerId(), higher.playerId());

        if (higher.hasAbsoluteColorPreference()) {
            return higherPref == PairingCandidate.WHITE ? higherWhite : higherBlack;
        }
        if (lower.hasAbsoluteColorPreference()) {
            return lowerPref == PairingCandidate.WHITE ? higherBlack : higherWhite;
        }
        return shouldHigherGetWhite(higher, lower, higherPref, lowerPref, boardIndex) ? higherWhite : higherBlack;
    }

    private boolean shouldHigherGetWhite(PairingCandidate higher, PairingCandidate lower,
                                          int higherPref, int lowerPref, int boardIndex) {
        int balanceDiff = higher.colorBalance() - lower.colorBalance();

        if (balanceDiff != 0) {
            return balanceDiff < 0;
        }

        int higherLast = higher.lastColor();
        int lowerLast = lower.lastColor();

        if (higherLast == PairingCandidate.BLACK && lowerLast == PairingCandidate.WHITE) return true;
        if (higherLast == PairingCandidate.WHITE && lowerLast == PairingCandidate.BLACK) return false;
        if (higherLast == PairingCandidate.NONE && lowerLast == PairingCandidate.NONE) return boardIndex % 2 == 0;
        if (higherPref == PairingCandidate.NONE) return lowerPref != PairingCandidate.WHITE;

        return higherPref == PairingCandidate.WHITE;
    }
}
