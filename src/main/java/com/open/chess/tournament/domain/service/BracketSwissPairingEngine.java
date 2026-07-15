package com.open.chess.tournament.domain.service;

import com.open.chess.tournament.domain.exception.NoPairingPossibleException;
import com.open.chess.tournament.domain.service.PairingPlan.Board;
import org.jgrapht.alg.interfaces.MatchingAlgorithm.Matching;
import org.jgrapht.alg.matching.blossom.v5.KolmogorovWeightedPerfectMatching;
import org.jgrapht.alg.matching.blossom.v5.ObjectiveSense;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleWeightedGraph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Swiss-system pairing engine following the FIDE (Dutch) sequential
 * bracket procedure (handbook C.04.3), with a literal port of the
 * candidate ordering rules:
 * - Art. 4.2 — transpositions of S2 are tried in the lexicographic order
 *   of their first N1 bracket sequence numbers;
 * - Art. 4.3 — exchanges between the original S1 and S2 are tried by
 *   fewest exchanged players, then smallest BSN-sum difference, then the
 *   largest BSNs leaving S1, then the smallest BSNs entering S1;
 * - Art. 3.3/3.7 — heterogeneous brackets pair the moved-down players
 *   (S1) against residents (S2) and the remainder is paired as a nested
 *   homogeneous bracket, altered innermost-first;
 * - Art. 3.4 — the first candidate that satisfies every applicable
 *   criterion is taken; otherwise (Art. 3.8) the earliest candidate with
 *   the best criteria vector wins.

 * Criteria carried by the candidate evaluation: C1 (no rematch) and C3
 * (no same absolute color preference) as hard legality, C4 (completion,
 * checked as a perfect-matching feasibility of floaters plus the rest of
 * the field), C5 (lowest possible bye score), C6/C7 structurally (the
 * number of floaters and which score levels float come from a weighted
 * matching over the whole remainder), C12/C13 (color and strong-color
 * satisfaction) and C14/C15 (down/upfloat repeats). Not implemented:
 * topscorer exceptions (C3 relaxation, C10/C11), the next-bracket
 * look-ahead C8, C9, the two-rounds-back float history C16-C21 and the
 * Art. 4.4.2 Limbo ordering — when a bracket needs them (or exceeds the
 * enumeration budget, or pairs two moved-down players with each other),
 * the engine falls back to the weighted-matching pairing of that bracket,
 * which respects the same criteria as costs but not the canonical order.
 */
public class BracketSwissPairingEngine implements PairingEngine {

    private static final double COST_SAME_ABSOLUTE_COLOR = 6e9;
    private static final double COST_DOWNFLOAT = 2e8;
    private static final double COST_SCORE_DIFF_UNIT = 5e6;
    private static final double COST_SAME_COLOR_PREFERENCE = 1e5;
    private static final double COST_BOTH_STRONG_PREFERENCE = 5e4;
    private static final double COST_FLOAT_REPEAT = 1200;
    private static final int SCORE_DIFF_CAP = 36;

    private static final int CANDIDATE_BUDGET = 50_000;
    private static final int MAX_EXCHANGED_PLAYERS = 2;

    @Override
    public PairingPlan generate(List<PairingCandidate> candidates) {
        if (candidates.size() < 2) {
            throw new NoPairingPossibleException("At least two active players are required to pair a round");
        }
        List<PairingCandidate> ranked = candidates.stream()
                .sorted(ranking())
                .toList();
        boolean oddField = ranked.size() % 2 != 0;
        List<List<PairingCandidate>> groups = scoreGroups(ranked);

        List<PairingCandidate[]> pairs = new ArrayList<>();
        List<PairingCandidate> carried = new ArrayList<>();
        UUID byePlayerId = null;
        for (int g = 0; g < groups.size(); g++) {
            List<PairingCandidate> bracket = new ArrayList<>(carried);
            bracket.addAll(groups.get(g));
            bracket.sort(ranking());
            List<PairingCandidate> rest = new ArrayList<>();
            for (int h = g + 1; h < groups.size(); h++) {
                rest.addAll(groups.get(h));
            }
            BracketResult result = pairBracket(bracket, rest, oddField);
            pairs.addAll(result.pairs());
            carried = result.floaters();
            byePlayerId = result.byePlayerId();
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

    private record BracketResult(List<PairingCandidate[]> pairs,
                                 List<PairingCandidate> floaters,
                                 UUID byePlayerId) {
    }

    private BracketResult pairBracket(List<PairingCandidate> bracket, List<PairingCandidate> rest,
                                      boolean oddField) {
        MatchingSolution structure = solveByMatching(bracket, rest, oddField);
        Candidate literal = null;
        if (literalProcedureApplies(bracket, structure)) {
            literal = dutchProcedure(bracket, rest, oddField, structure);
        }
        return literal != null
                ? toResult(bracket, literal.pairs, literal.floaters, literal.byeIndex)
                : toResult(bracket, structure.pairs(), structure.floaters(), structure.byeIndex());
    }

    private BracketResult toResult(List<PairingCandidate> bracket, List<int[]> pairIndices,
                                   List<Integer> floaterIndices, int byeIndex) {
        List<PairingCandidate[]> pairs = new ArrayList<>(pairIndices.size());
        for (int[] pair : pairIndices) {
            pairs.add(new PairingCandidate[]{bracket.get(pair[0]), bracket.get(pair[1])});
        }
        List<PairingCandidate> floaters = new ArrayList<>(floaterIndices.size());
        for (int index : floaterIndices) {
            floaters.add(bracket.get(index));
        }
        floaters.sort(ranking());
        UUID byePlayerId = byeIndex < 0 ? null : bracket.get(byeIndex).playerId();
        return new BracketResult(pairs, floaters, byePlayerId);
    }

    // ------------------------------------------------------------------
    // Structure and fallback: weighted matching over bracket + remainder
    // ------------------------------------------------------------------

    private record MatchingSolution(List<int[]> pairs, List<Integer> floaters, int byeIndex) {
    }

    /**
     * Pairs the bracket as a minimum-cost perfect matching over the
     * bracket, every player below it (zero-cost completion witnesses) and
     * the bye vertex. Determines how many players pair, who floats and,
     * in the last bracket, who takes the bye; also the fallback pairing
     * when the literal procedure does not apply.
     */
    private MatchingSolution solveByMatching(List<PairingCandidate> bracket, List<PairingCandidate> rest,
                                             boolean oddField) {
        boolean lastBracket = rest.isEmpty();
        int bracketSize = bracket.size();
        List<PairingCandidate> remaining = new ArrayList<>(bracket);
        remaining.addAll(rest);
        int total = remaining.size();
        int byeVertex = total;

        SimpleWeightedGraph<Integer, DefaultWeightedEdge> graph =
                new SimpleWeightedGraph<>(DefaultWeightedEdge.class);
        for (int v = 0; v < total; v++) {
            graph.addVertex(v);
        }
        for (int i = 0; i < total; i++) {
            for (int j = i + 1; j < total; j++) {
                if (remaining.get(i).hasPlayed(remaining.get(j).playerId())) {
                    continue;
                }
                double cost;
                if (j < bracketSize) {
                    cost = pairCost(bracket.get(i), bracket.get(j));
                } else if (i < bracketSize) {
                    cost = downfloatCost(bracket, i);
                } else {
                    cost = 0.0;
                }
                graph.setEdgeWeight(graph.addEdge(i, j), cost);
            }
        }
        if (oddField) {
            graph.addVertex(byeVertex);
            for (int i = 0; i < total; i++) {
                if (!remaining.get(i).eligibleForBye()) {
                    continue;
                }
                double cost;
                if (i >= bracketSize) {
                    cost = 0.0;
                } else if (lastBracket) {
                    cost = byeCost(bracket.get(i)) + (bracketSize - 1 - i);
                } else {
                    cost = downfloatCost(bracket, i) + 1;
                }
                graph.setEdgeWeight(graph.addEdge(i, byeVertex), cost);
            }
        }

        List<int[]> pairs = new ArrayList<>();
        List<Integer> floaters = new ArrayList<>();
        int byeIndex = -1;
        try {
            Matching<Integer, DefaultWeightedEdge> matching =
                    new KolmogorovWeightedPerfectMatching<>(graph, ObjectiveSense.MINIMIZE).getMatching();
            for (DefaultWeightedEdge edge : matching.getEdges()) {
                int low = Math.min(graph.getEdgeSource(edge), graph.getEdgeTarget(edge));
                int high = Math.max(graph.getEdgeSource(edge), graph.getEdgeTarget(edge));
                if (low >= bracketSize) {
                    continue;
                }
                if (high < bracketSize) {
                    pairs.add(new int[]{low, high});
                } else if (high == byeVertex && lastBracket) {
                    byeIndex = low;
                } else {
                    floaters.add(low);
                }
            }
        } catch (IllegalArgumentException noPerfectMatching) {
            // Only reachable on the first bracket: every later bracket
            // inherits a feasibility witness from its predecessor.
            throw new NoPairingPossibleException("Unable to generate a round without rematches or a second bye");
        }
        pairs.sort(Comparator.comparingInt(pair -> pair[0]));
        return new MatchingSolution(pairs, floaters, byeIndex);
    }

    private double pairCost(PairingCandidate higher, PairingCandidate lower) {
        double cost = 0.0;
        boolean samePreference = higher.colorPreference() != PairingCandidate.NONE
                && higher.colorPreference() == lower.colorPreference();
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
        }
        return cost;
    }

    private double downfloatCost(List<PairingCandidate> bracket, int i) {
        PairingCandidate candidate = bracket.get(i);
        double cost = COST_DOWNFLOAT;
        // C7: downfloater scores are minimized — an MDP pairs in the
        // bracket rather than floating past it when a resident can float
        // in its place.
        int doubledScore = (int) Math.round(candidate.score() * 2);
        cost += COST_SCORE_DIFF_UNIT * Math.min(doubledScore * doubledScore, SCORE_DIFF_CAP);
        if (candidate.floatedDownLastRound()) {
            cost += COST_FLOAT_REPEAT;
        }
        cost += bracket.size() - 1 - i;
        return cost;
    }

    private double byeCost(PairingCandidate candidate) {
        int doubledScore = (int) Math.round(candidate.score() * 2);
        double cost = COST_SCORE_DIFF_UNIT * Math.min(doubledScore * doubledScore, SCORE_DIFF_CAP);
        if (candidate.floatedDownLastRound()) {
            cost += COST_FLOAT_REPEAT;
        }
        return cost;
    }

    // ------------------------------------------------------------------
    // Literal Dutch procedure (Art. 3.3-3.8, 4.2, 4.3)
    // ------------------------------------------------------------------

    /** The literal procedure pairs MDPs against residents, never with each other. */
    private boolean literalProcedureApplies(List<PairingCandidate> bracket, MatchingSolution structure) {
        double residentScore = bracket.getLast().score();
        for (int[] pair : structure.pairs()) {
            if (bracket.get(pair[0]).score() > residentScore
                    && bracket.get(pair[1]).score() > residentScore) {
                return false;
            }
        }
        return true;
    }

    private static final class Candidate {
        private final List<int[]> pairs;
        private final List<Integer> floaters;
        private final int byeIndex;
        private final int[] vector;

        private Candidate(List<int[]> pairs, List<Integer> floaters, int byeIndex, int[] vector) {
            this.pairs = pairs;
            this.floaters = floaters;
            this.byeIndex = byeIndex;
            this.vector = vector;
        }
    }

    private static final class Enumeration {
        private int generated;
        private Candidate best;
        private Candidate perfect;
        private final Map<Set<UUID>, Boolean> completionCache = new HashMap<>();

        private boolean exhausted() {
            return generated >= CANDIDATE_BUDGET;
        }
    }

    /**
     * Runs the candidate enumeration in the canonical order and returns
     * the first perfect candidate (Art. 3.4), the earliest best one
     * (Art. 3.8), or null to use the matching fallback.
     */
    private Candidate dutchProcedure(List<PairingCandidate> bracket, List<PairingCandidate> rest,
                                     boolean oddField, MatchingSolution structure) {
        boolean lastBracket = rest.isEmpty();
        double residentScore = bracket.getLast().score();

        List<Integer> mdpsPaired = new ArrayList<>();
        for (int[] pair : structure.pairs()) {
            if (bracket.get(pair[0]).score() > residentScore) {
                mdpsPaired.add(pair[0]);
            }
        }
        mdpsPaired.sort(Comparator.naturalOrder());
        List<Integer> limbo = new ArrayList<>();
        for (int floater : structure.floaters()) {
            if (bracket.get(floater).score() > residentScore) {
                limbo.add(floater);
            }
        }
        // A last-bracket bye taken by an MDP stays in limbo so the
        // enumeration can still hand it the bye slot.
        boolean byeToMdp = structure.byeIndex() >= 0
                && bracket.get(structure.byeIndex()).score() > residentScore;
        if (byeToMdp) {
            limbo.add(structure.byeIndex());
        }
        List<Integer> residents = new ArrayList<>();
        for (int i = 0; i < bracket.size(); i++) {
            if (bracket.get(i).score() <= residentScore) {
                residents.add(i);
            }
        }
        int unpairedResidents = (int) structure.floaters().stream()
                .filter(floater -> bracket.get(floater).score() <= residentScore)
                .count()
                + (structure.byeIndex() >= 0 && !byeToMdp ? 1 : 0);

        Enumeration state = new Enumeration();
        if (mdpsPaired.isEmpty()) {
            enumerateHomogeneous(bracket, rest, oddField, lastBracket, residents, unpairedResidents,
                    List.of(), limbo, state);
        } else {
            enumerateHeterogeneous(bracket, rest, oddField, lastBracket, mdpsPaired, residents,
                    unpairedResidents, limbo, state);
        }
        if (state.perfect != null) {
            return state.perfect;
        }
        return state.best;
    }

    /**
     * Heterogeneous bracket (Art. 3.3.3, 3.7): S1 = pairable MDPs, S2 =
     * residents; each S2 transposition fixes the MDP pairing and the
     * remainder is altered innermost as a homogeneous bracket.
     */
    private void enumerateHeterogeneous(List<PairingCandidate> bracket, List<PairingCandidate> rest,
                                        boolean oddField, boolean lastBracket,
                                        List<Integer> mdps, List<Integer> residents,
                                        int unpairedResidents, List<Integer> limbo, Enumeration state) {
        int m1 = mdps.size();
        int[] chosen = new int[m1];
        boolean[] used = new boolean[residents.size()];
        enumerateMdpArrangement(bracket, rest, oddField, lastBracket, mdps, residents,
                unpairedResidents, limbo, chosen, used, 0, state);
    }

    private void enumerateMdpArrangement(List<PairingCandidate> bracket, List<PairingCandidate> rest,
                                         boolean oddField, boolean lastBracket,
                                         List<Integer> mdps, List<Integer> residents,
                                         int unpairedResidents, List<Integer> limbo,
                                         int[] chosen, boolean[] used, int slot, Enumeration state) {
        if (state.perfect != null || state.exhausted()) {
            return;
        }
        if (slot == mdps.size()) {
            List<int[]> mdpPairs = new ArrayList<>(mdps.size());
            for (int k = 0; k < mdps.size(); k++) {
                mdpPairs.add(new int[]{mdps.get(k), residents.get(chosen[k])});
            }
            List<Integer> remainder = new ArrayList<>();
            for (int r = 0; r < residents.size(); r++) {
                if (!used[r]) {
                    remainder.add(residents.get(r));
                }
            }
            enumerateHomogeneous(bracket, rest, oddField, lastBracket, remainder, unpairedResidents,
                    mdpPairs, limbo, state);
            return;
        }
        for (int r = 0; r < residents.size(); r++) {
            if (used[r]) {
                continue;
            }
            used[r] = true;
            chosen[slot] = r;
            enumerateMdpArrangement(bracket, rest, oddField, lastBracket, mdps, residents,
                    unpairedResidents, limbo, chosen, used, slot + 1, state);
            used[r] = false;
            if (state.perfect != null || state.exhausted()) {
                return;
            }
        }
    }

    /**
     * Homogeneous bracket or remainder (Art. 3.3.1, 3.6): S1 versus S2 in
     * fold order, altered by S2 transpositions (Art. 4.2) and then by
     * exchanges applied to the original S1/S2 (Art. 4.3).
     */
    private void enumerateHomogeneous(List<PairingCandidate> bracket, List<PairingCandidate> rest,
                                      boolean oddField, boolean lastBracket,
                                      List<Integer> players, int unpaired,
                                      List<int[]> fixedPairs, List<Integer> limbo, Enumeration state) {
        int pairsToMake = (players.size() - unpaired) / 2;
        if (pairsToMake * 2 + unpaired != players.size()) {
            return;
        }
        List<Integer> s1 = new ArrayList<>(players.subList(0, pairsToMake));
        List<Integer> s2 = new ArrayList<>(players.subList(pairsToMake, players.size()));
        // Identity first: most brackets find a perfect candidate through
        // transpositions alone, so the exchange list is built lazily.
        enumerateTranspositions(bracket, rest, oddField, lastBracket, s1, s2, fixedPairs, limbo, state);
        if (state.perfect != null || state.exhausted()) {
            return;
        }
        for (int[] exchange : exchangesInOrder(s1.size(), s2.size())) {
            List<Integer> exchangedS1 = applyExchange(s1, s2, exchange, true);
            List<Integer> exchangedS2 = applyExchange(s1, s2, exchange, false);
            enumerateTranspositions(bracket, rest, oddField, lastBracket, exchangedS1, exchangedS2,
                    fixedPairs, limbo, state);
            if (state.perfect != null || state.exhausted()) {
                return;
            }
        }
    }

    /**
     * Exchanges between the original S1 and S2 (Art. 4.3.2): identity
     * first, then by number of exchanged players, smallest BSN-sum
     * difference, largest BSNs out of S1, smallest BSNs into S1. Encoded
     * as {s1Position..., s2Position...} index sets.
     */
    private List<int[]> exchangesInOrder(int s1Size, int s2Size) {
        List<int[]> swaps = new ArrayList<>();
        for (int count = 1; count <= Math.min(MAX_EXCHANGED_PLAYERS, Math.min(s1Size, s2Size)); count++) {
            for (int[] fromS1 : combinations(s1Size, count)) {
                for (int[] fromS2 : combinations(s2Size, count)) {
                    int[] swap = new int[2 * count];
                    System.arraycopy(fromS1, 0, swap, 0, count);
                    System.arraycopy(fromS2, 0, swap, count, count);
                    swaps.add(swap);
                }
            }
        }
        swaps.sort(Comparator
                .comparingInt((int[] swap) -> swap.length)
                .thenComparingInt(swap -> {
                    int count = swap.length / 2;
                    int difference = 0;
                    for (int k = 0; k < count; k++) {
                        difference += (s1Size + swap[count + k]) - swap[k];
                    }
                    return difference;
                })
                .thenComparing(swap -> descendingS1(swap), BracketSwissPairingEngine::compareDescending)
                .thenComparing(swap -> ascendingS2(swap), BracketSwissPairingEngine::compareAscending));
        return swaps;
    }

    private static int[] descendingS1(int[] swap) {
        int count = swap.length / 2;
        int[] s1 = Arrays.copyOfRange(swap, 0, count);
        Arrays.sort(s1);
        for (int k = 0; k < count / 2; k++) {
            int tmp = s1[k];
            s1[k] = s1[count - 1 - k];
            s1[count - 1 - k] = tmp;
        }
        return s1;
    }

    private static int[] ascendingS2(int[] swap) {
        int count = swap.length / 2;
        int[] s2 = Arrays.copyOfRange(swap, count, swap.length);
        Arrays.sort(s2);
        return s2;
    }

    /** Higher BSNs leaving S1 come first: descending arrays, larger first. */
    private static int compareDescending(int[] a, int[] b) {
        for (int k = 0; k < Math.min(a.length, b.length); k++) {
            if (a[k] != b[k]) {
                return Integer.compare(b[k], a[k]);
            }
        }
        return Integer.compare(a.length, b.length);
    }

    /** Lower BSNs entering S1 come first: ascending arrays, smaller first. */
    private static int compareAscending(int[] a, int[] b) {
        for (int k = 0; k < Math.min(a.length, b.length); k++) {
            if (a[k] != b[k]) {
                return Integer.compare(a[k], b[k]);
            }
        }
        return Integer.compare(a.length, b.length);
    }

    private List<Integer> applyExchange(List<Integer> s1, List<Integer> s2, int[] exchange, boolean firstHalf) {
        int count = exchange.length / 2;
        Set<Integer> outOfS1 = new HashSet<>();
        Set<Integer> outOfS2 = new HashSet<>();
        for (int k = 0; k < count; k++) {
            outOfS1.add(exchange[k]);
            outOfS2.add(exchange[count + k]);
        }
        List<Integer> result = new ArrayList<>();
        if (firstHalf) {
            for (int k = 0; k < s1.size(); k++) {
                if (!outOfS1.contains(k)) {
                    result.add(s1.get(k));
                }
            }
            for (int k = 0; k < s2.size(); k++) {
                if (outOfS2.contains(k)) {
                    result.add(s2.get(k));
                }
            }
        } else {
            for (int k = 0; k < s1.size(); k++) {
                if (outOfS1.contains(k)) {
                    result.add(s1.get(k));
                }
            }
            for (int k = 0; k < s2.size(); k++) {
                if (!outOfS2.contains(k)) {
                    result.add(s2.get(k));
                }
            }
        }
        result.sort(Comparator.naturalOrder());
        return result;
    }

    private List<int[]> combinations(int n, int k) {
        List<int[]> result = new ArrayList<>();
        int[] current = new int[k];
        combine(n, k, 0, 0, current, result);
        return result;
    }

    private void combine(int n, int k, int start, int depth, int[] current, List<int[]> result) {
        if (depth == k) {
            result.add(current.clone());
            return;
        }
        for (int v = start; v <= n - (k - depth); v++) {
            current[depth] = v;
            combine(n, k, v + 1, depth + 1, current, result);
        }
    }

    /** Transpositions of S2 in lexicographic order of the first N1 BSNs (Art. 4.2). */
    private void enumerateTranspositions(List<PairingCandidate> bracket, List<PairingCandidate> rest,
                                         boolean oddField, boolean lastBracket,
                                         List<Integer> s1, List<Integer> s2,
                                         List<int[]> fixedPairs, List<Integer> limbo, Enumeration state) {
        int[] chosen = new int[s1.size()];
        boolean[] used = new boolean[s2.size()];
        transpose(bracket, rest, oddField, lastBracket, s1, s2, fixedPairs, limbo, chosen, used, 0, state);
    }

    private void transpose(List<PairingCandidate> bracket, List<PairingCandidate> rest,
                           boolean oddField, boolean lastBracket,
                           List<Integer> s1, List<Integer> s2,
                           List<int[]> fixedPairs, List<Integer> limbo,
                           int[] chosen, boolean[] used, int slot, Enumeration state) {
        if (state.perfect != null || state.exhausted()) {
            return;
        }
        if (slot == s1.size()) {
            List<int[]> pairs = new ArrayList<>(fixedPairs.size() + s1.size());
            pairs.addAll(fixedPairs);
            for (int k = 0; k < s1.size(); k++) {
                int a = s1.get(k);
                int b = s2.get(chosen[k]);
                pairs.add(new int[]{Math.min(a, b), Math.max(a, b)});
            }
            List<Integer> unpaired = new ArrayList<>(limbo);
            for (int k = 0; k < s2.size(); k++) {
                if (!used[k]) {
                    unpaired.add(s2.get(k));
                }
            }
            evaluate(bracket, rest, oddField, lastBracket, pairs, unpaired, state);
            return;
        }
        for (int k = 0; k < s2.size(); k++) {
            if (used[k]) {
                continue;
            }
            used[k] = true;
            chosen[slot] = k;
            transpose(bracket, rest, oddField, lastBracket, s1, s2, fixedPairs, limbo,
                    chosen, used, slot + 1, state);
            used[k] = false;
            if (state.perfect != null || state.exhausted()) {
                return;
            }
        }
    }

    /**
     * Art. 3.4/3.8: a candidate is perfect when it is legal (C1, C3),
     * completable (C4), gives the bye to the lowest eligible score (C5)
     * and has no color (C12/C13) or float-repeat (C14/C15) defects;
     * otherwise the earliest candidate with the best vector is kept.
     */
    private void evaluate(List<PairingCandidate> bracket, List<PairingCandidate> rest,
                          boolean oddField, boolean lastBracket,
                          List<int[]> pairs, List<Integer> unpaired, Enumeration state) {
        state.generated++;

        double residentScore = bracket.getLast().score();
        int colorViolations = 0;
        int strongViolations = 0;
        int upfloatRepeats = 0;
        for (int[] pair : pairs) {
            PairingCandidate higher = bracket.get(pair[0]);
            PairingCandidate lower = bracket.get(pair[1]);
            if (higher.hasPlayed(lower.playerId())) {
                return;
            }
            boolean samePreference = higher.colorPreference() != PairingCandidate.NONE
                    && higher.colorPreference() == lower.colorPreference();
            if (samePreference) {
                if (higher.hasAbsoluteColorPreference() && lower.hasAbsoluteColorPreference()) {
                    return;
                }
                colorViolations++;
                if (higher.hasStrongColorPreference() && lower.hasStrongColorPreference()) {
                    strongViolations++;
                }
            }
            if (higher.score() > residentScore && lower.floatedUpLastRound()) {
                upfloatRepeats++;
            }
        }

        int byeIndex = -1;
        int byeDoubledScore = 0;
        List<Integer> floaters = unpaired;
        if (lastBracket) {
            if (oddField) {
                if (unpaired.size() != 1) {
                    return;
                }
                byeIndex = unpaired.getFirst();
                if (!bracket.get(byeIndex).eligibleForBye()) {
                    return;
                }
                byeDoubledScore = (int) Math.round(bracket.get(byeIndex).score() * 2);
                floaters = List.of();
            } else if (!unpaired.isEmpty()) {
                return;
            }
        }
        int downfloatRepeats = 0;
        for (int floater : floaters) {
            if (bracket.get(floater).floatedDownLastRound()) {
                downfloatRepeats++;
            }
        }
        if (byeIndex >= 0 && bracket.get(byeIndex).floatedDownLastRound()) {
            downfloatRepeats++;
        }

        int[] vector = new int[]{byeDoubledScore, colorViolations, strongViolations,
                downfloatRepeats, upfloatRepeats};
        if (state.best != null && compareAscending(vector, state.best.vector) >= 0) {
            return;
        }
        if (!completionFeasible(bracket, rest, oddField, floaters, state)) {
            return;
        }
        Candidate candidate = new Candidate(List.copyOf(pairs), List.copyOf(floaters), byeIndex, vector);
        state.best = candidate;
        int minimumByeDoubled = byeIndex >= 0 ? minimumEligibleDoubledScore(bracket) : 0;
        if (byeDoubledScore == minimumByeDoubled && colorViolations == 0
                && downfloatRepeats == 0 && upfloatRepeats == 0) {
            state.perfect = candidate;
        }
    }

    private int minimumEligibleDoubledScore(List<PairingCandidate> bracket) {
        int minimum = Integer.MAX_VALUE;
        for (PairingCandidate candidate : bracket) {
            if (candidate.eligibleForBye()) {
                minimum = Math.min(minimum, (int) Math.round(candidate.score() * 2));
            }
        }
        return minimum;
    }

    /** C4: the floaters plus every player below must admit a legal pairing. */
    private boolean completionFeasible(List<PairingCandidate> bracket, List<PairingCandidate> rest,
                                       boolean oddField, List<Integer> floaters, Enumeration state) {
        if (rest.isEmpty() && floaters.isEmpty()) {
            return true;
        }
        List<PairingCandidate> remainder = new ArrayList<>(floaters.size() + rest.size());
        Set<UUID> key = new HashSet<>();
        for (int floater : floaters) {
            remainder.add(bracket.get(floater));
            key.add(bracket.get(floater).playerId());
        }
        remainder.addAll(rest);
        return state.completionCache.computeIfAbsent(key, ignored -> {
            SimpleWeightedGraph<Integer, DefaultWeightedEdge> graph =
                    new SimpleWeightedGraph<>(DefaultWeightedEdge.class);
            int size = remainder.size();
            for (int v = 0; v < size; v++) {
                graph.addVertex(v);
            }
            for (int i = 0; i < size; i++) {
                for (int j = i + 1; j < size; j++) {
                    if (!remainder.get(i).hasPlayed(remainder.get(j).playerId())) {
                        graph.setEdgeWeight(graph.addEdge(i, j), 0.0);
                    }
                }
            }
            if (oddField) {
                graph.addVertex(size);
                for (int i = 0; i < size; i++) {
                    if (remainder.get(i).eligibleForBye()) {
                        graph.setEdgeWeight(graph.addEdge(i, size), 0.0);
                    }
                }
            }
            try {
                new KolmogorovWeightedPerfectMatching<>(graph, ObjectiveSense.MINIMIZE).getMatching();
                return true;
            } catch (IllegalArgumentException noPerfectMatching) {
                return false;
            }
        });
    }

    private Board assignColors(PairingCandidate higher, PairingCandidate lower, int boardIndex) {
        int higherPref = higher.colorPreference();
        int lowerPref = lower.colorPreference();
        boolean higherAbsolute = higher.hasAbsoluteColorPreference();
        boolean lowerAbsolute = lower.hasAbsoluteColorPreference();

        Board higherWhite = new Board(higher.playerId(), lower.playerId());
        Board higherBlack = new Board(lower.playerId(), higher.playerId());
        Board higherChoice = higherPref == PairingCandidate.WHITE ? higherWhite : higherBlack;

        if (higherAbsolute) {
            return higherChoice;
        }
        if (lowerAbsolute) {
            return lowerPref == PairingCandidate.WHITE ? higherBlack : higherWhite;
        }
        if (higher.colorBalance() < lower.colorBalance()) {
            return higherWhite;
        }
        if (lower.colorBalance() < higher.colorBalance()) {
            return higherBlack;
        }
        if (higher.lastColor() == PairingCandidate.BLACK && lower.lastColor() == PairingCandidate.WHITE) {
            return higherWhite;
        }
        if (higher.lastColor() == PairingCandidate.WHITE && lower.lastColor() == PairingCandidate.BLACK) {
            return higherBlack;
        }
        if (higher.lastColor() == PairingCandidate.NONE && lower.lastColor() == PairingCandidate.NONE) {
            return boardIndex % 2 == 0 ? higherWhite : higherBlack;
        }
        if (higherPref == PairingCandidate.NONE) {
            return lowerPref == PairingCandidate.WHITE ? higherBlack : higherWhite;
        }
        return higherChoice;
    }
}
