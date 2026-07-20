package com.open.chess.tournament.domain.service.matching;

import org.jgrapht.alg.interfaces.MatchingAlgorithm.Matching;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleWeightedGraph;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Validates the in-house Blossom VI implementation against JGraphT's
 * Blossom V (Kolmogorov) on hand-crafted and randomized instances: both
 * must agree on feasibility and on the optimal matching weight, and every
 * matching returned must be a perfect matching over real edges.
 */
class BlossomVITest {

    private final PerfectMatchingSolver blossomV = new BlossomVMatchingSolver();
    private final PerfectMatchingSolver blossomVI = new BlossomVIMatchingSolver();

    @Test
    void singleEdgeIsMatched() {
        SimpleWeightedGraph<Integer, DefaultWeightedEdge> graph = emptyGraph(2);
        graph.setEdgeWeight(graph.addEdge(0, 1), 7.0);

        Matching<Integer, DefaultWeightedEdge> matching = blossomVI.solve(graph, MatchingObjective.MINIMIZE);

        assertEquals(7.0, matching.getWeight(), 1e-9);
        assertEquals(1, matching.getEdges().size());
    }

    @Test
    void pathOfFourForcesTheOuterEdges() {
        // Path 0-1-2-3: the only perfect matching is {01, 23} even though 12 is cheap.
        SimpleWeightedGraph<Integer, DefaultWeightedEdge> graph = emptyGraph(4);
        graph.setEdgeWeight(graph.addEdge(0, 1), 5.0);
        graph.setEdgeWeight(graph.addEdge(1, 2), 1.0);
        graph.setEdgeWeight(graph.addEdge(2, 3), 5.0);

        Matching<Integer, DefaultWeightedEdge> matching = blossomVI.solve(graph, MatchingObjective.MINIMIZE);

        assertEquals(10.0, matching.getWeight(), 1e-9);
    }

    @Test
    void triangleWithPendantUsesTheBlossom() {
        // Odd cycle 0-1-2 plus pendant 3 attached to 0: matching must take
        // an edge of the triangle not incident to 0, exercising blossom logic.
        SimpleWeightedGraph<Integer, DefaultWeightedEdge> graph = emptyGraph(4);
        graph.setEdgeWeight(graph.addEdge(0, 1), 1.0);
        graph.setEdgeWeight(graph.addEdge(1, 2), 4.0);
        graph.setEdgeWeight(graph.addEdge(0, 2), 1.0);
        graph.setEdgeWeight(graph.addEdge(0, 3), 2.0);

        Matching<Integer, DefaultWeightedEdge> matching = blossomVI.solve(graph, MatchingObjective.MINIMIZE);

        assertEquals(6.0, matching.getWeight(), 1e-9);
    }

    @Test
    void oddVertexCountHasNoPerfectMatching() {
        SimpleWeightedGraph<Integer, DefaultWeightedEdge> graph = emptyGraph(3);
        graph.setEdgeWeight(graph.addEdge(0, 1), 1.0);
        graph.setEdgeWeight(graph.addEdge(1, 2), 1.0);

        assertThrows(IllegalArgumentException.class,
                () -> blossomVI.solve(graph, MatchingObjective.MINIMIZE));
    }

    @Test
    void starHasNoPerfectMatching() {
        SimpleWeightedGraph<Integer, DefaultWeightedEdge> graph = emptyGraph(4);
        graph.setEdgeWeight(graph.addEdge(0, 1), 1.0);
        graph.setEdgeWeight(graph.addEdge(0, 2), 1.0);
        graph.setEdgeWeight(graph.addEdge(0, 3), 1.0);

        assertThrows(IllegalArgumentException.class,
                () -> blossomVI.solve(graph, MatchingObjective.MINIMIZE));
    }

    @Test
    void agreesWithBlossomVOnCompleteGraphs() {
        Random random = new Random(20260719);
        for (int trial = 0; trial < 300; trial++) {
            int size = 2 * (1 + random.nextInt(8));
            SimpleWeightedGraph<Integer, DefaultWeightedEdge> graph = emptyGraph(size);
            for (int i = 0; i < size; i++) {
                for (int j = i + 1; j < size; j++) {
                    graph.setEdgeWeight(graph.addEdge(i, j), random.nextInt(2001) - 1000);
                }
            }
            crossCheck(graph, MatchingObjective.MINIMIZE, "complete trial " + trial);
            crossCheck(graph, MatchingObjective.MAXIMIZE, "complete trial " + trial);
        }
    }

    @Test
    void agreesWithBlossomVOnSparseGraphs() {
        Random random = new Random(42);
        for (int trial = 0; trial < 300; trial++) {
            int size = 2 * (2 + random.nextInt(20));
            SimpleWeightedGraph<Integer, DefaultWeightedEdge> graph = emptyGraph(size);
            for (int i = 0; i < size; i++) {
                for (int j = i + 1; j < size; j++) {
                    if (random.nextDouble() < 0.18) {
                        graph.setEdgeWeight(graph.addEdge(i, j), random.nextInt(1000));
                    }
                }
            }
            crossCheck(graph, MatchingObjective.MINIMIZE, "sparse trial " + trial);
        }
    }

    @Test
    void agreesWithBlossomVOnPairingEngineScaleWeights() {
        // Weights shaped like the engines' reward tiers: huge dominant
        // levels plus tiny dyadic tie-breakers on a shared magnitude.
        double[] tiers = {7e9, 1.5e9, 1.1e8, 1.05e7, 1.2e6, 1.15e5, 1.1e4, 2500, 240, 12, 1, 0.0625, 0.00390625};
        Random random = new Random(7);
        for (int trial = 0; trial < 100; trial++) {
            int size = 2 * (2 + random.nextInt(9));
            SimpleWeightedGraph<Integer, DefaultWeightedEdge> graph = emptyGraph(size);
            for (int i = 0; i < size; i++) {
                for (int j = i + 1; j < size; j++) {
                    if (random.nextDouble() < 0.7) {
                        double weight = 0.0;
                        for (double tier : tiers) {
                            if (random.nextBoolean()) {
                                weight += tier;
                            }
                        }
                        graph.setEdgeWeight(graph.addEdge(i, j), weight);
                    }
                }
            }
            crossCheck(graph, MatchingObjective.MAXIMIZE, "tier trial " + trial);
        }
    }

    @Test
    void agreesWithBlossomVOnSmallWeightDenseTieGraphs() {
        // Small integer weights produce many tight edges and therefore big
        // cherry blossoms, nested shrinks and cascading expands — the
        // instance family the paper singles out as the hard one.
        Random random = new Random(1234);
        for (int trial = 0; trial < 120; trial++) {
            int size = 2 * (5 + random.nextInt(56));
            SimpleWeightedGraph<Integer, DefaultWeightedEdge> graph = emptyGraph(size);
            for (int i = 0; i < size; i++) {
                for (int j = i + 1; j < size; j++) {
                    if (random.nextDouble() < 12.0 / size) {
                        graph.setEdgeWeight(graph.addEdge(i, j), random.nextInt(6));
                    }
                }
            }
            crossCheck(graph, MatchingObjective.MINIMIZE, "small-weight trial " + trial);
            crossCheck(graph, MatchingObjective.MAXIMIZE, "small-weight trial " + trial);
        }
    }

    @Test
    void agreesWithBlossomVOnZeroWeightGraphs() {
        Random random = new Random(99);
        for (int trial = 0; trial < 100; trial++) {
            int size = 2 * (2 + random.nextInt(10));
            SimpleWeightedGraph<Integer, DefaultWeightedEdge> graph = emptyGraph(size);
            for (int i = 0; i < size; i++) {
                for (int j = i + 1; j < size; j++) {
                    if (random.nextDouble() < 0.3) {
                        graph.setEdgeWeight(graph.addEdge(i, j), 0.0);
                    }
                }
            }
            crossCheck(graph, MatchingObjective.MINIMIZE, "zero trial " + trial);
        }
    }

    private SimpleWeightedGraph<Integer, DefaultWeightedEdge> emptyGraph(int size) {
        SimpleWeightedGraph<Integer, DefaultWeightedEdge> graph =
                new SimpleWeightedGraph<>(DefaultWeightedEdge.class);
        for (int v = 0; v < size; v++) {
            graph.addVertex(v);
        }
        return graph;
    }

    private void crossCheck(SimpleWeightedGraph<Integer, DefaultWeightedEdge> graph,
                            MatchingObjective objective, String label) {
        Matching<Integer, DefaultWeightedEdge> reference;
        try {
            reference = blossomV.solve(graph, objective);
        } catch (IllegalArgumentException noPerfectMatching) {
            assertThrows(IllegalArgumentException.class,
                    () -> blossomVI.solve(graph, objective),
                    label + ": Blossom V found no perfect matching but Blossom VI did");
            return;
        }
        Matching<Integer, DefaultWeightedEdge> matching;
        try {
            matching = blossomVI.solve(graph, objective);
        } catch (IllegalArgumentException noPerfectMatching) {
            fail(label + ": Blossom VI found no perfect matching but Blossom V did");
            return;
        }
        assertPerfect(graph, matching, label);
        double tolerance = Math.max(1e-6, Math.abs(reference.getWeight()) * 1e-12);
        assertEquals(reference.getWeight(), matching.getWeight(), tolerance,
                label + ": optimal weights differ");
    }

    private void assertPerfect(SimpleWeightedGraph<Integer, DefaultWeightedEdge> graph,
                               Matching<Integer, DefaultWeightedEdge> matching, String label) {
        Set<Integer> covered = new HashSet<>();
        double total = 0.0;
        for (DefaultWeightedEdge edge : matching.getEdges()) {
            assertTrue(graph.containsEdge(edge), label + ": matching uses a foreign edge");
            assertTrue(covered.add(graph.getEdgeSource(edge)), label + ": vertex covered twice");
            assertTrue(covered.add(graph.getEdgeTarget(edge)), label + ": vertex covered twice");
            total += graph.getEdgeWeight(edge);
        }
        assertEquals(graph.vertexSet().size(), covered.size(), label + ": matching is not perfect");
        assertEquals(total, matching.getWeight(), 1e-6, label + ": reported weight is inconsistent");
    }
}
