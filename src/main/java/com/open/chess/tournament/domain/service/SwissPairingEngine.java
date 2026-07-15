package com.open.chess.tournament.domain.service;

import com.open.chess.tournament.domain.exception.NoPairingPossibleException;
import com.open.chess.tournament.domain.service.PairingPlan.Board;
import org.jgrapht.alg.interfaces.MatchingAlgorithm.Matching;
import org.jgrapht.alg.matching.blossom.v5.KolmogorovWeightedPerfectMatching;
import org.jgrapht.alg.matching.blossom.v5.ObjectiveSense;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleWeightedGraph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Swiss-system pairing engine implementing the FIDE (Dutch) criteria:
 * score brackets are resolved from
 * the top down, each as a maximum-weight perfect matching (Blossom V)
 * over the whole remaining field, whose weights encode the quality
 * criteria of the current bracket plus a one-bracket look-ahead in
 * strictly decreasing tiers:
 *   1. maximize the number of pairs in the current bracket (C.5/C.6);
 *   2. pair the moved-down players rather than letting them float again
 *      (C.7);
 *   3. maximize the number of pairs the next bracket will be able to
 *      make, preferring moved-down players to be the ones pairable there;
 *   4. minimize color preference violations, then violations where both
 *      players hold a strong preference (C.8/C.9), counted only inside
 *      the current bracket;
 *   5. pair players who floated down in the previous round inside the
 *      bracket, and avoid repeated upfloats (C.12/C.13).
 * Everything further down only enters the matching as a legality witness,
 * so a defect in a higher bracket is never traded for fewer defects
 * further below — the top-down priority the Dutch procedure mandates.

 * Before any bracket is paired, a feasibility matching pins the score of
 * the pairing-allocated bye to the lowest level a complete legal round
 * allows (C.2): every bracket matching afterwards only lets the bye fall
 * on an eligible player of that level. Rematches are forbidden edges; a
 * pair of players sharing the same absolute color preference (C.3) is
 * kept at zero weight, so it loses to any float and is only used when no
 * legal alternative exists.

 * Among the matchings that reach the optimal weight, boards are fixed
 * greedily in the canonical Dutch order: the highest unfixed player takes
 * the first opponent, in Art. 4.2 preference order (a moved-down player
 * tries the residents from the top; inside a group the S2 candidates of
 * the fold split come first, then the S1 exchanges; the bye is the last
 * resort), that still lets the rest of the field reach the optimum.
 * Fixing greedily against the preserved optimum is exactly the
 * lexicographic transposition rule. Unfixed bracket members float into the next
 * bracket. If the feasibility matching fails, no legal round exists and
 * a domain error is raised.
 */
public class SwissPairingEngine implements PairingEngine {

    // Reward tiers for the bracket matchings (maximized). Each tier
    // strictly dominates the sum of every tier below it over a full
    // matching, and the largest possible edge weight stays below the
    // 1e10 feasibility threshold of the Blossom V implementation.
    private static final double REWARD_PAIR_IN_BRACKET = 7e9;
    private static final double REWARD_MDP_PAIRED = 1.5e9;
    private static final double REWARD_MDP_SCORE = 5e7;
    private static final double REWARD_PAIR_IN_NEXT = 1.1e8;
    private static final double REWARD_NEXT_SCORE = 4e6;
    private static final double REWARD_COLOR_IMBALANCE_COMPAT = 1.05e7;
    private static final double REWARD_COLOR_ABSOLUTE_COMPAT = 1.2e6;
    private static final double REWARD_COLOR_MILD_COMPAT = 1.15e5;
    private static final double REWARD_NOT_BOTH_STRONG = 1.1e4;
    private static final double REWARD_BYE_LEVEL_FEWER_PLAYED = 5e3;
    private static final double REWARD_DOWNFLOAT_REPEAT_PAIRED = 2500;
    private static final double REWARD_NO_UPFLOAT_REPEAT = 240;
    private static final double REWARD_DOWNFLOAT_REPEAT_2_PAIRED = 12;
    private static final double REWARD_NO_UPFLOAT_REPEAT_2 = 1;
    // Art. 4.3 exchange minimization: a bracket pair whose smaller-indexed
    // member sits in S1 (the top slice of residents the optimum can pair)
    // earns this bonus, so S2-S2 pairs only appear when a real exchange is
    // due. Further, a BSN-dependent bonus encodes the order of S1 members.
    private static final double REWARD_S1_MEMBER_PAIRED = 0.0625;
    private static final double REWARD_BSN_PRIORITY = 0.00390625; // = 1.0 / 256.0
    // Bye pinning: minimize forced same-absolute-preference pairs first,
    // then the score level of the bye.
    private static final double COST_SAME_ABSOLUTE_COLOR = 1e9;
    // Every reward is an integer, so optimal weights are exact in double
    // arithmetic; the tolerance only guards the comparison.
    private static final double WEIGHT_TOLERANCE = 1e-3;

    @Override
    public PairingPlan generate(List<PairingCandidate> candidates) {
        if (candidates.size() < 2) {
            throw new NoPairingPossibleException("At least two active players are required to pair a round");
        }
        //TODO: Check whether we need to sort using the tournament's tie-breaking criteria instead of rating, after sorting by score.
        //      If so, we will need to make some changes to retrieve this information, which is not accessible (so far) in PairingCandidate.
        //      I think we can just add the new attribute, but I'm not sure right now
        List<PairingCandidate> ranked = candidates.stream()
                .sorted(ranking())
                .toList();
        boolean oddField = ranked.size() % 2 != 0;
        List<List<PairingCandidate>> groups = scoreGroups(ranked);
        double byeScore = oddField ? pinByeScore(ranked, groups) : Double.NEGATIVE_INFINITY;

        List<PairingCandidate[]> pairs = new ArrayList<>();
        List<PairingCandidate> carried = new ArrayList<>();
        UUID byePlayerId = null;
        for (int g = 0; g < groups.size(); g++) {
            List<PairingCandidate> bracket = new ArrayList<>(carried);
            bracket.addAll(groups.get(g));
            bracket.sort(ranking());
            List<PairingCandidate> next = g + 1 < groups.size() ? groups.get(g + 1) : List.of();
            List<PairingCandidate> below = new ArrayList<>();
            for (int h = g + 2; h < groups.size(); h++) {
                below.addAll(groups.get(h));
            }
            BracketResult result = fixBracket(bracket, next, below,
                    oddField && byePlayerId == null, byeScore);
            pairs.addAll(result.pairs());
            carried = result.floaters();
            if (result.byePlayerId() != null) {
                byePlayerId = result.byePlayerId();
            }
        }

        List<Board> boards = new ArrayList<>(pairs.size());
        for (int i = 0; i < pairs.size(); i++) {
            boards.add(assignColors(pairs.get(i)[0], pairs.get(i)[1], i));
        }
        return new PairingPlan(boards, byePlayerId);
    }

    private Comparator<PairingCandidate> ranking() {
        return Comparator
                .comparingDouble(PairingCandidate::score).reversed()
                .thenComparing(Comparator.comparingInt(PairingCandidate::rating).reversed())
                .thenComparing(candidate -> candidate.playerId().toString());
    }

    private List<List<PairingCandidate>> scoreGroups(List<PairingCandidate> ranked) {
        List<List<PairingCandidate>> groups = new ArrayList<>();
        List<PairingCandidate> current = new ArrayList<>();
        for (PairingCandidate candidate : ranked) {
            if (!current.isEmpty() && current.getFirst().score() != candidate.score()) {
                groups.add(current);
                current = new ArrayList<>();
            }
            current.add(candidate);
        }
        groups.add(current);
        return groups;
    }

    /**
     * C.2: a feasibility matching over the whole field determines the
     * lowest score level on which the pairing-allocated bye can fall in a
     * complete legal round; every bracket matching afterwards only allows
     * the bye there. Also raises the domain error when no legal round
     * exists at all.
     * if that fails, they are relaxed with a high cost (C.3 relaxation).
     */
    private double pinByeScore(List<PairingCandidate> ranked, List<List<PairingCandidate>> groups) {
        int size = ranked.size();
        double result = tryPinByeScore(ranked, groups, size, true);
        if (Double.isNaN(result)) {
            result = tryPinByeScore(ranked, groups, size, false);
        }
        return result;
    }

    private double tryPinByeScore(List<PairingCandidate> ranked, List<List<PairingCandidate>> groups,
                                   int size, boolean hardConstraint) {
        SimpleWeightedGraph<Integer, DefaultWeightedEdge> graph =
                new SimpleWeightedGraph<>(DefaultWeightedEdge.class);
        for (int v = 0; v <= size; v++) {
            graph.addVertex(v);
        }
        for (int i = 0; i < size; i++) {
            for (int j = i + 1; j < size; j++) {
                if (ranked.get(i).hasPlayed(ranked.get(j).playerId())) {
                    continue;
                }
                if (hardConstraint && bothAbsoluteSamePreference(ranked.get(i), ranked.get(j))) {
                    continue;
                }
                double cost = !hardConstraint && bothAbsoluteSamePreference(ranked.get(i), ranked.get(j))
                        ? COST_SAME_ABSOLUTE_COLOR
                        : 0.0;
                graph.setEdgeWeight(graph.addEdge(i, j), cost);
            }
        }
        int groupRankFromBottom = groups.size();
        int rankIndex = 0;
        for (List<PairingCandidate> group : groups) {
            groupRankFromBottom--;
            for (int k = 0; k < group.size(); k++, rankIndex++) {
                if (ranked.get(rankIndex).eligibleForBye()) {
                    graph.setEdgeWeight(graph.addEdge(rankIndex, size),
                            Math.pow(4, groupRankFromBottom));
                }
            }
        }
        try {
            Matching<Integer, DefaultWeightedEdge> matching =
                    new KolmogorovWeightedPerfectMatching<>(graph, ObjectiveSense.MINIMIZE).getMatching();
            for (DefaultWeightedEdge edge : matching.getEdges()) {
                int low = Math.min(graph.getEdgeSource(edge), graph.getEdgeTarget(edge));
                int high = Math.max(graph.getEdgeSource(edge), graph.getEdgeTarget(edge));
                if (high == size) {
                    return ranked.get(low).score();
                }
            }
            throw new NoPairingPossibleException("Unable to generate a round without rematches or a second bye");
        } catch (IllegalArgumentException noPerfectMatching) {
            if (hardConstraint) {
                return Double.NaN;
            }
            throw new NoPairingPossibleException("Unable to generate a round without rematches or a second bye");
        }
    }

    private record BracketResult(List<PairingCandidate[]> pairs,
                                 List<PairingCandidate> floaters,
                                 UUID byePlayerId) {
    }

    /**
     * Pairs one bracket: builds the reward graph over the bracket, the
     * next score group, the rest of the field and the pinned bye,
     * computes the optimal weight, and fixes boards in canonical order
     * while that optimum stays reachable. Unfixed members float down.
     */
    private BracketResult fixBracket(List<PairingCandidate> bracket, List<PairingCandidate> next,
                                     List<PairingCandidate> below, boolean byeOpen, double byeScore) {
        int bracketSize = bracket.size();
        int nextEnd = bracketSize + next.size();
        List<PairingCandidate> remaining = new ArrayList<>(bracket);
        remaining.addAll(next);
        remaining.addAll(below);
        int total = remaining.size();
        int byeVertex = total;
        double residentScore = bracket.getLast().score();
        // C.2/C.9: among the players on the pinned bye level, the one with
        // the most played games takes the bye, so pairing the others earns
        // a reward and the greedy leftover is the most-played one.
        int maxPlayedAtByeLevel = 0;
        java.util.Map<Integer, Integer> unplayedGameRanks = new java.util.HashMap<>();
        if (byeOpen) {
            java.util.List<Integer> playedCounts = new java.util.ArrayList<>();
            for (PairingCandidate candidate : remaining) {
                if (candidate.score() == byeScore) {
                    int played = candidate.whiteGames() + candidate.blackGames();
                    if (!playedCounts.contains(played)) {
                        playedCounts.add(played);
                    }
                }
            }
            playedCounts.sort(java.util.Comparator.reverseOrder());
            for (int rank = 0; rank < playedCounts.size(); rank++) {
                unplayedGameRanks.put(playedCounts.get(rank), rank);
            }
        }

        SimpleWeightedGraph<Integer, DefaultWeightedEdge> graph =
                new SimpleWeightedGraph<>(DefaultWeightedEdge.class);
        for (int v = 0; v < total; v++) {
            graph.addVertex(v);
        }
        for (int i = 0; i < total; i++) {
            for (int j = i + 1; j < total; j++) {
                PairingCandidate higher = remaining.get(i);
                PairingCandidate lower = remaining.get(j);
                if (higher.hasPlayed(lower.playerId())) {
                    continue;
                }
                double reward = 0.0;
                if (!bothAbsoluteSamePreference(higher, lower)) {
                    if (j < bracketSize) {
                        reward = bracketPairReward(higher, lower, residentScore);
                    } else if (j < nextEnd) {
                        reward = REWARD_PAIR_IN_NEXT;
                        if (i < bracketSize) {
                            int mdpLevels = (int) Math.round((higher.score() - residentScore) * 2);
                            reward += mdpLevels * REWARD_NEXT_SCORE;
                        }
                    }
                    if (byeOpen && reward > 0) {
                        reward += byeLevelReward(higher, byeScore, unplayedGameRanks);
                        reward += byeLevelReward(lower, byeScore, unplayedGameRanks);
                    }
                }
                graph.setEdgeWeight(graph.addEdge(i, j), reward);
            }
        }
        if (byeOpen) {
            graph.addVertex(byeVertex);
            for (int i = 0; i < total; i++) {
                if (remaining.get(i).eligibleForBye() && remaining.get(i).score() <= byeScore + 0.01) {
                    graph.setEdgeWeight(graph.addEdge(i, byeVertex), 0.0);
                }
            }
        }

        Set<Integer> removed = new HashSet<>();
        MatchingResult current = maximumWeight(graph, removed);
        if (Double.isInfinite(current.weight())) {
            throw new NoPairingPossibleException("Unable to generate a round without rematches or a second bye");
        }

        double residual = current.weight();
        List<int[]> fixedPairs = new ArrayList<>();
        List<Integer> leftovers = new ArrayList<>();
        UUID byePlayerId = null;
        boolean exchangeBitsApplied = false;
        for (int i = 0; i < bracketSize; i++) {
            if (removed.contains(i)) {
                continue;
            }
            if (!exchangeBitsApplied && bracket.get(i).score() == residentScore) {
                applyExchangeBits(graph, bracket, removed, current, residentScore);
                current = maximumWeight(graph, removed);
                residual = current.weight();
                exchangeBitsApplied = true;
            }
            List<Integer> order = preferenceOrder(bracket, removed, leftovers, current,
                    residentScore, i);
            if (byeOpen) {
                order.add(byeVertex);
            }
            boolean fixed = false;
            for (int j : order) {
                DefaultWeightedEdge edge = graph.getEdge(i, j);
                if (edge == null) {
                    continue;
                }
                double weight = graph.getEdgeWeight(edge);
                Set<Integer> without = new HashSet<>(removed);
                without.add(i);
                without.add(j);
                MatchingResult rest = maximumWeight(graph, without);
                if (weight + rest.weight() >= residual - WEIGHT_TOLERANCE) {
                    if (j == byeVertex) {
                        byePlayerId = bracket.get(i).playerId();
                    } else {
                        fixedPairs.add(new int[]{i, j});
                    }
                    removed = without;
                    residual = rest.weight();
                    current = rest;
                    fixed = true;
                    break;
                }
            }
            if (!fixed) {
                leftovers.add(i);
            }
        }

        List<PairingCandidate[]> pairs = new ArrayList<>(fixedPairs.size());
        for (int[] pair : fixedPairs) {
            pairs.add(new PairingCandidate[]{bracket.get(pair[0]), bracket.get(pair[1])});
        }
        List<PairingCandidate> floaters = new ArrayList<>(leftovers.size());
        for (int index : leftovers) {
            floaters.add(bracket.get(index));
        }
        return new BracketResult(pairs, floaters, byePlayerId);
    }

    /**
     * Reward of a pair inside the current bracket: the pair itself, the
     * moved-down player it satisfies with score-dependent priority,
     * 4-tier color compatibility,
     * and float-repeat protection (C.12/C.13).
     */
    private double bracketPairReward(PairingCandidate higher, PairingCandidate lower,
                                     double residentScore) {
        double reward = REWARD_PAIR_IN_BRACKET;
        if (higher.score() > residentScore) {
            reward += REWARD_MDP_PAIRED;
            int mdpLevels = (int) Math.round((higher.score() - residentScore) * 2);
            reward += mdpLevels * REWARD_MDP_SCORE;
        }
        boolean samePreference = higher.colorPreference() != PairingCandidate.NONE
                && higher.colorPreference() == lower.colorPreference();
        boolean higherAbsolute = higher.hasAbsoluteColorPreference();
        boolean lowerAbsolute = lower.hasAbsoluteColorPreference();
        if (!higherAbsolute || !lowerAbsolute || !samePreference) {
            reward += REWARD_COLOR_IMBALANCE_COMPAT;
        }
        if (!higherAbsolute || !lowerAbsolute || !samePreference
                || (higher.colorBalance() == lower.colorBalance()
                      ? (higher.lastColor() == PairingCandidate.NONE
                          || higher.lastColor() != lower.lastColor())
                      : (Math.abs(higher.colorBalance()) > Math.abs(lower.colorBalance())
                            ? lower.lastColor()
                            : higher.lastColor()
                          ) != (higher.colorPreference() == PairingCandidate.WHITE
                              ? PairingCandidate.BLACK : PairingCandidate.WHITE))) {
            reward += REWARD_COLOR_ABSOLUTE_COMPAT;
        }
        if (!samePreference || higher.colorPreference() == PairingCandidate.NONE
                || lower.colorPreference() == PairingCandidate.NONE) {
            reward += REWARD_COLOR_MILD_COMPAT;
        }
        if ((!higher.hasStrongColorPreference() && !higherAbsolute)
                || (!lower.hasStrongColorPreference() && !lowerAbsolute)
                || (higherAbsolute && lowerAbsolute)
                || !samePreference) {
            reward += REWARD_NOT_BOTH_STRONG;
        }
        if (lower.floatedDownLastRound()) {
            reward += REWARD_DOWNFLOAT_REPEAT_PAIRED;
        }
        if (higher.score() == lower.score() && higher.floatedDownLastRound()) {
            reward += REWARD_DOWNFLOAT_REPEAT_PAIRED;
        }
        if (!(higher.score() > lower.score() && lower.floatedUpLastRound())) {
            reward += REWARD_NO_UPFLOAT_REPEAT;
        }
        if (lower.floatedDownTwoRoundsAgo()) {
            reward += REWARD_DOWNFLOAT_REPEAT_2_PAIRED;
        }
        if (higher.score() == lower.score() && higher.floatedDownTwoRoundsAgo()) {
            reward += REWARD_DOWNFLOAT_REPEAT_2_PAIRED;
        }
        if (!(higher.score() > lower.score() && lower.floatedUpTwoRoundsAgo())) {
            reward += REWARD_NO_UPFLOAT_REPEAT_2;
        }
        return reward;
    }

    /**
     * Adds the exchange tier to the resident-resident edges among the
     * unfixed members: S1 is the top slice of residents the optimum can
     * still pair inside the bracket.
     * The first bit rewards any S1-member pairing, the second (within-tier)
     * encodes the negative BSN so lower-indexed S1 players get higher reward.
     * This makes S1-S1 and S1-S2 edges strictly preferred over S2-S2 edges.
     */
    private void applyExchangeBits(SimpleWeightedGraph<Integer, DefaultWeightedEdge> graph,
                                   List<PairingCandidate> bracket, Set<Integer> removed,
                                   MatchingResult current, double residentScore) {
        int bracketSize = bracket.size();
        int residentPairs = 0;
        for (int[] pair : current.pairs()) {
            if (pair[1] < bracketSize && bracket.get(pair[0]).score() == residentScore) {
                residentPairs++;
            }
        }
        List<Integer> s1 = new ArrayList<>();
        for (int m = 0; m < bracketSize && s1.size() < residentPairs; m++) {
            if (!removed.contains(m) && bracket.get(m).score() == residentScore) {
                s1.add(m);
            }
        }
        int s1Size = s1.size();
        for (int s1Pos = 0; s1Pos < s1Size; s1Pos++) {
            int i = s1.get(s1Pos);
            double bonus = REWARD_S1_MEMBER_PAIRED + (s1Size - 1 - s1Pos) * REWARD_BSN_PRIORITY;
            for (int j = i + 1; j < bracketSize; j++) {
                if (removed.contains(j)) {
                    continue;
                }
                DefaultWeightedEdge edge = graph.getEdge(i, j);
                if (edge != null && graph.getEdgeWeight(edge) > 0) {
                    graph.setEdgeWeight(edge, graph.getEdgeWeight(edge) + bonus);
                }
            }
        }
    }

    /** Reward for pairing a bye-level player: graduated by unplayed-game rank. */
    private double byeLevelReward(PairingCandidate candidate, double byeScore,
                                   java.util.Map<Integer, Integer> unplayedGameRanks) {
        if (candidate.score() != byeScore) {
            return 0.0;
        }
        int played = candidate.whiteGames() + candidate.blackGames();
        Integer rank = unplayedGameRanks.get(played);
        return rank != null ? rank * REWARD_BYE_LEVEL_FEWER_PLAYED : 0.0;
    }

    private boolean bothAbsoluteSamePreference(PairingCandidate higher, PairingCandidate lower) {
        return higher.colorPreference() != PairingCandidate.NONE
                && higher.colorPreference() == lower.colorPreference()
                && higher.hasAbsoluteColorPreference()
                && lower.hasAbsoluteColorPreference();
    }

    /**
     * Art. 4.2 candidate order for the highest unfixed bracket member. A
     * moved-down player tries every lower-scored member from the top (its
     * S2 is the whole resident list); a resident folds the remaining
     * members of its level around the number of pairs the optimum still
     * makes, trying its S2 from the fold partner upward and only then the
     * S1 exchanges.
     */
    private List<Integer> preferenceOrder(List<PairingCandidate> bracket,
                                          Set<Integer> removed, List<Integer> leftovers,
                                          MatchingResult current, double residentScore, int i) {
        int bracketSize = bracket.size();
        List<Integer> unfixed = new ArrayList<>();
        for (int m = i + 1; m < bracketSize; m++) {
            if (!removed.contains(m) && !leftovers.contains(m)) {
                unfixed.add(m);
            }
        }
        List<Integer> order = new ArrayList<>(unfixed.size() + 1);
        if (bracket.get(i).score() > residentScore) {
            for (int m : unfixed) {
                if (bracket.get(m).score() < bracket.get(i).score()) {
                    order.add(m);
                }
            }
            for (int m : unfixed) {
                if (bracket.get(m).score() == bracket.get(i).score()) {
                    order.add(m);
                }
            }
        } else {
            int memberPairs = 0;
            for (int[] pair : current.pairs()) {
                if (pair[1] < bracketSize && !removed.contains(pair[0])
                        && bracket.get(pair[0]).score() == residentScore) {
                    memberPairs++;
                }
            }
            int s2From = Math.min(Math.max(memberPairs - 1, 0), unfixed.size());
            order.addAll(unfixed.subList(s2From, unfixed.size()));
            // S1 exchanges (Art. 4.3): the lowest S1 member leaves first
            // and becomes the top of the reordered S2, so exchange
            // candidates are tried from the bottom of S1 upward.
            List<Integer> exchanges = new ArrayList<>(unfixed.subList(0, s2From));
            java.util.Collections.reverse(exchanges);
            order.addAll(exchanges);
        }
        return order;
    }

    private record MatchingResult(double weight, List<int[]> pairs) {
    }

    /**
     * Maximum-weight perfect matching on the graph restricted to the
     * vertices not in {@code excluded}; -infinity when none exists.
     */
    private MatchingResult maximumWeight(SimpleWeightedGraph<Integer, DefaultWeightedEdge> graph,
                                         Set<Integer> excluded) {
        List<Integer> remaining = new ArrayList<>();
        for (Integer vertex : graph.vertexSet()) {
            if (!excluded.contains(vertex)) {
                remaining.add(vertex);
            }
        }
        if (remaining.isEmpty()) {
            return new MatchingResult(0.0, List.of());
        }
        SimpleWeightedGraph<Integer, DefaultWeightedEdge> subgraph =
                new SimpleWeightedGraph<>(DefaultWeightedEdge.class);
        for (Integer vertex : remaining) {
            subgraph.addVertex(vertex);
        }
        for (int a = 0; a < remaining.size(); a++) {
            for (int b = a + 1; b < remaining.size(); b++) {
                DefaultWeightedEdge edge = graph.getEdge(remaining.get(a), remaining.get(b));
                if (edge != null) {
                    subgraph.setEdgeWeight(subgraph.addEdge(remaining.get(a), remaining.get(b)),
                            graph.getEdgeWeight(edge));
                }
            }
        }
        try {
            Matching<Integer, DefaultWeightedEdge> matching =
                    new KolmogorovWeightedPerfectMatching<>(subgraph, ObjectiveSense.MAXIMIZE).getMatching();
            List<int[]> pairs = new ArrayList<>();
            for (DefaultWeightedEdge edge : matching.getEdges()) {
                int low = Math.min(subgraph.getEdgeSource(edge), subgraph.getEdgeTarget(edge));
                int high = Math.max(subgraph.getEdgeSource(edge), subgraph.getEdgeTarget(edge));
                pairs.add(new int[]{low, high});
            }
            return new MatchingResult(matching.getWeight(), pairs);
        } catch (IllegalArgumentException noPerfectMatching) {
            return new MatchingResult(Double.NEGATIVE_INFINITY, List.of());
        }
    }

    private Board assignColors(PairingCandidate higher, PairingCandidate lower, int boardIndex) {
        int neutral = choosePlayerNeutralColor(higher, lower);
        if (neutral == PairingCandidate.WHITE) {
            return new Board(higher.playerId(), lower.playerId());
        }
        if (neutral == PairingCandidate.BLACK) {
            return new Board(lower.playerId(), higher.playerId());
        }
        if (higher.colorPreference() != PairingCandidate.NONE) {
            return higher.colorPreference() == PairingCandidate.WHITE
                    ? new Board(higher.playerId(), lower.playerId())
                    : new Board(lower.playerId(), higher.playerId());
        }
        return boardIndex % 2 == 0
                ? new Board(higher.playerId(), lower.playerId())
                : new Board(lower.playerId(), higher.playerId());
    }

    /**
     * determines the color
     * for the first player using a 3-tier priority chain. Returns NONE
     * when no decision can be made (then the caller falls back to ranking
     * parity or preference).
     */
    private int choosePlayerNeutralColor(PairingCandidate a, PairingCandidate b) {
        int prefA = a.colorPreference();
        int prefB = b.colorPreference();
        if (!samePreference(a, b)) {
            if (prefA != PairingCandidate.NONE) {
                return prefA;
            }
            if (prefB != PairingCandidate.NONE) {
                return prefB == PairingCandidate.WHITE ? PairingCandidate.BLACK : PairingCandidate.WHITE;
            }
            return PairingCandidate.NONE;
        }
        boolean absA = a.hasAbsoluteColorPreference();
        boolean absB = b.hasAbsoluteColorPreference();
        int imbA = Math.abs(a.colorBalance());
        int imbB = Math.abs(b.colorBalance());
        if (absA && (imbA > imbB || !absB)) {
            return prefA;
        }
        if (absB && (imbB > imbA || !absA)) {
            return prefB == PairingCandidate.WHITE ? PairingCandidate.BLACK : PairingCandidate.WHITE;
        }
        boolean strongA = a.hasStrongColorPreference();
        boolean strongB = b.hasStrongColorPreference();
        if (strongA && !strongB) {
            return prefA;
        }
        if (strongB && !strongA) {
            return prefB == PairingCandidate.WHITE ? PairingCandidate.BLACK : PairingCandidate.WHITE;
        }
        int[] diff = findFirstColorDifference(a, b);
        if (diff[0] != PairingCandidate.NONE && diff[1] != PairingCandidate.NONE) {
            return diff[1];
        }
        return PairingCandidate.NONE;
    }

    private boolean samePreference(PairingCandidate a, PairingCandidate b) {
        return a.colorPreference() != PairingCandidate.NONE
                && a.colorPreference() == b.colorPreference();
    }

    private int[] findFirstColorDifference(PairingCandidate a, PairingCandidate b) {
        int aLast = a.lastColor();
        int bLast = b.lastColor();
        if (aLast != PairingCandidate.NONE && bLast != PairingCandidate.NONE && aLast != bLast) {
            return new int[]{aLast, bLast};
        }
        int aPrev = a.previousColor();
        int bPrev = b.previousColor();
        if (aPrev != PairingCandidate.NONE && bPrev != PairingCandidate.NONE && aPrev != bPrev) {
            return new int[]{aPrev, bPrev};
        }
        return new int[]{PairingCandidate.NONE, PairingCandidate.NONE};
    }
}
