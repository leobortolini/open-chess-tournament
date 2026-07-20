package com.open.chess.tournament.domain.service.matching;

import org.jgrapht.Graph;
import org.jgrapht.alg.interfaces.MatchingAlgorithm.Matching;
import org.jgrapht.alg.matching.blossom.v5.KolmogorovWeightedPerfectMatching;
import org.jgrapht.alg.matching.blossom.v5.ObjectiveSense;
import org.jgrapht.graph.DefaultWeightedEdge;

/** Blossom V solver backed by JGraphT's {@link KolmogorovWeightedPerfectMatching}. */
public class BlossomVMatchingSolver implements PerfectMatchingSolver {

    @Override
    public Matching<Integer, DefaultWeightedEdge> solve(Graph<Integer, DefaultWeightedEdge> graph,
                                                        MatchingObjective objective) {
        ObjectiveSense sense = objective == MatchingObjective.MINIMIZE
                ? ObjectiveSense.MINIMIZE
                : ObjectiveSense.MAXIMIZE;
        return new KolmogorovWeightedPerfectMatching<>(graph, sense).getMatching();
    }
}
