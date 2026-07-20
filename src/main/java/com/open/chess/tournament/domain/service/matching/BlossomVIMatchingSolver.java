package com.open.chess.tournament.domain.service.matching;

import org.jgrapht.Graph;
import org.jgrapht.alg.interfaces.MatchingAlgorithm.Matching;
import org.jgrapht.alg.interfaces.MatchingAlgorithm.MatchingImpl;
import org.jgrapht.graph.DefaultWeightedEdge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Blossom VI solver: adapts the pairing engines' JGraphT graphs to the
 * in-house {@link BlossomVI} implementation of Arkhipov and Kolmogorov's
 * algorithm (arXiv:2604.20351). Maximization is solved by negating the
 * weights.
 */
public class BlossomVIMatchingSolver implements PerfectMatchingSolver {

    @Override
    public Matching<Integer, DefaultWeightedEdge> solve(Graph<Integer, DefaultWeightedEdge> graph,
                                                        MatchingObjective objective) {
        List<Integer> vertices = new ArrayList<>(graph.vertexSet());
        Map<Integer, Integer> index = new HashMap<>();
        for (int i = 0; i < vertices.size(); i++) {
            index.put(vertices.get(i), i);
        }
        Set<DefaultWeightedEdge> edgeSet = graph.edgeSet();
        int[] from = new int[edgeSet.size()];
        int[] to = new int[edgeSet.size()];
        double[] weight = new double[edgeSet.size()];
        DefaultWeightedEdge[] byIndex = new DefaultWeightedEdge[edgeSet.size()];
        double sign = objective == MatchingObjective.MINIMIZE ? 1.0 : -1.0;
        int e = 0;
        for (DefaultWeightedEdge edge : edgeSet) {
            from[e] = index.get(graph.getEdgeSource(edge));
            to[e] = index.get(graph.getEdgeTarget(edge));
            weight[e] = sign * graph.getEdgeWeight(edge);
            byIndex[e] = edge;
            e++;
        }

        int[] mate = BlossomVI.minimumWeightPerfectMatching(vertices.size(), from, to, weight);

        Set<DefaultWeightedEdge> matchedEdges = new HashSet<>();
        double total = 0.0;
        for (int v = 0; v < mate.length; v++) {
            if (mate[v] > v) {
                DefaultWeightedEdge edge = graph.getEdge(vertices.get(v), vertices.get(mate[v]));
                matchedEdges.add(edge);
                total += graph.getEdgeWeight(edge);
            }
        }
        return new MatchingImpl<>(graph, matchedEdges, total);
    }
}
