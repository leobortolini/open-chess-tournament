package com.open.chess.tournament.domain.service;

import com.open.chess.tournament.domain.model.GameResult;
import com.open.chess.tournament.domain.model.Pairing;
import com.open.chess.tournament.domain.model.Round;
import com.open.chess.tournament.domain.model.Tournament;
import com.open.chess.tournament.domain.service.matching.BlossomVIMatchingSolver;
import com.open.chess.tournament.domain.service.matching.BlossomVMatchingSolver;
import com.open.chess.tournament.domain.service.matching.MatchingObjective;
import com.open.chess.tournament.domain.service.matching.PerfectMatchingSolver;
import org.jgrapht.Graph;
import org.jgrapht.alg.interfaces.MatchingAlgorithm.Matching;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The pairing engines must behave the same whether the perfect matchings
 * underneath come from Blossom V (JGraphT Kolmogorov) or from the in-house
 * Blossom VI. Simulated tournaments (draws, decisive games, forfeits, odd
 * and even fields) drive the engines over identical positions round after
 * round.
 *
 * <p>The bracket {@link SwissPairingEngine} fixes its boards greedily
 * against the optimal weight, so its rounds are asserted to be identical
 * board by board. The {@link LiteSwissPairingEngine} cost model can leave
 * genuine ties between optimal matchings (two solvers may pick different,
 * equally optimal rounds), so there the engine runs on a cross-checking
 * solver that asserts, for every single matching call, that Blossom VI
 * reaches exactly the optimal weight Blossom V reaches.
 */
class EngineMatchingSolverComparisonTest {

    private static final int TOURNAMENTS = 12;
    private static final int ROUNDS = 6;

    @Test
    void swissEnginePairsIdenticallyWithBothSolvers() {
        comparePlans(new SwissPairingEngine(new BlossomVMatchingSolver()),
                new SwissPairingEngine(new BlossomVIMatchingSolver()), "Swiss");
    }

    @Test
    void liteEngineBlossomVIMatchingsAreAlwaysOptimal() {
        PairingEngine engine = new LiteSwissPairingEngine(new CrossCheckingSolver());
        for (int seed = 1; seed <= TOURNAMENTS; seed++) {
            int players = seed % 2 == 0 ? 24 : 21;
            Tournament tournament = Tournament.create("LiteSolverSim" + seed, ROUNDS);
            for (int i = 0; i < players; i++) {
                tournament.registerPlayer("Player" + (i + 1), 2600 - i * 13);
            }
            tournament.start();
            Random random = new Random(seed * 7919L);
            for (int r = 1; r <= ROUNDS; r++) {
                Optional<Round> generated = tournament.generateNextRound(engine);
                assertNotNull(generated.orElse(null),
                        "Lite seed " + seed + " round " + r + ": no round generated");
                for (Pairing pairing : generated.get().getPairings()) {
                    if (!pairing.isBye()) {
                        tournament.reportResult(pairing.getId(), randomResult(random));
                    }
                }
            }
        }
    }

    /**
     * Solves every matching with both algorithms, asserts the optimal
     * weights coincide, and hands the Blossom VI matching to the engine.
     */
    private static final class CrossCheckingSolver implements PerfectMatchingSolver {
        private final PerfectMatchingSolver blossomV = new BlossomVMatchingSolver();
        private final PerfectMatchingSolver blossomVI = new BlossomVIMatchingSolver();

        @Override
        public Matching<Integer, DefaultWeightedEdge> solve(Graph<Integer, DefaultWeightedEdge> graph,
                                                            MatchingObjective objective) {
            Matching<Integer, DefaultWeightedEdge> reference = blossomV.solve(graph, objective);
            Matching<Integer, DefaultWeightedEdge> matching = blossomVI.solve(graph, objective);
            assertEquals(reference.getWeight(), matching.getWeight(),
                    Math.max(1e-6, Math.abs(reference.getWeight()) * 1e-12),
                    "Blossom VI returned a sub-optimal matching");
            return matching;
        }
    }

    private void comparePlans(PairingEngine reference, PairingEngine candidate, String label) {
        for (int seed = 1; seed <= TOURNAMENTS; seed++) {
            int players = seed % 2 == 0 ? 24 : 21;
            Tournament tournament = Tournament.create("SolverSim" + seed, ROUNDS);
            for (int i = 0; i < players; i++) {
                tournament.registerPlayer("Player" + (i + 1), 2600 - i * 13);
            }
            tournament.start();
            Random random = new Random(seed * 7919L);

            for (int r = 1; r <= ROUNDS; r++) {
                CapturingEngine driver = new CapturingEngine(reference);
                Optional<Round> generated = tournament.generateNextRound(driver);
                assertNotNull(generated.orElse(null),
                        label + " seed " + seed + " round " + r + ": no round generated");

                PairingPlan referencePlan = reference.generate(driver.captured);
                PairingPlan candidatePlan = candidate.generate(driver.captured);
                assertEquals(boards(referencePlan), boards(candidatePlan),
                        label + " seed " + seed + " round " + r
                                + ": solvers produced different rounds");

                for (Pairing pairing : generated.get().getPairings()) {
                    if (!pairing.isBye()) {
                        tournament.reportResult(pairing.getId(), randomResult(random));
                    }
                }
            }
        }
    }

    /** Boards with colors plus the bye, as a comparable canonical set. */
    private Set<String> boards(PairingPlan plan) {
        Set<String> result = new TreeSet<>();
        for (PairingPlan.Board board : plan.boards()) {
            result.add(board.whitePlayerId() + ">" + board.blackPlayerId());
        }
        if (plan.byePlayerId() != null) {
            result.add("bye:" + plan.byePlayerId());
        }
        return result;
    }

    private GameResult randomResult(Random random) {
        int roll = random.nextInt(100);
        if (roll < 4) {
            return GameResult.WHITE_WINS_FORFEIT;
        }
        if (roll < 8) {
            return GameResult.BLACK_WINS_FORFEIT;
        }
        if (roll < 40) {
            return GameResult.DRAW;
        }
        if (roll < 70) {
            return GameResult.WHITE_WINS;
        }
        return GameResult.BLACK_WINS;
    }

    /** A pairing engine that records the candidates it was asked to pair. */
    private static final class CapturingEngine implements PairingEngine {
        private final PairingEngine delegate;
        private List<PairingCandidate> captured = List.of();

        private CapturingEngine(PairingEngine delegate) {
            this.delegate = delegate;
        }

        @Override
        public PairingPlan generate(List<PairingCandidate> candidates) {
            this.captured = candidates;
            return delegate.generate(candidates);
        }
    }
}
