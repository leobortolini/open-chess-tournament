package com.open.chess.tournament.domain.service.matching;

import org.jgrapht.Graph;
import org.jgrapht.alg.interfaces.MatchingAlgorithm.Matching;
import org.jgrapht.graph.DefaultWeightedEdge;

/**
 * Computes an optimal-weight perfect matching of a weighted graph. The
 * pairing engines encode every FIDE criterion as edge weights and rely on
 * the matching being exactly optimal, so implementations must be exact.
 */
public interface PerfectMatchingSolver {

    /**
     * Returns a perfect matching of optimal total weight.
     *
     * @throws IllegalArgumentException when the graph has no perfect matching
     */
    Matching<Integer, DefaultWeightedEdge> solve(Graph<Integer, DefaultWeightedEdge> graph,
                                                 MatchingObjective objective);
}
