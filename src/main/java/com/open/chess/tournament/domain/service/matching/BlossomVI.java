package com.open.chess.tournament.domain.service.matching;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minimum-weight perfect matching solver implementing the Blossom VI
 * algorithm of Arkhipov and Kolmogorov ("Blossom VI: A Practical Minimum
 * Weight Perfect Matching Algorithm", arXiv:2604.20351, 2026).
 *
 * <p>The solver runs the classical primal-dual scheme, but its primal phase
 * is exactly a maximum-cardinality unweighted matching over the zero-slack
 * edges, carried out on <em>cherry trees</em> instead of traditional
 * alternating trees (Grow-out, Grow-in, Augment and Expand operations).
 * Odd cycles are not contracted when they are discovered; they are kept as
 * <em>cherry blossoms</em> — sets of ±-nodes sharing a purely-plus
 * receptacle that both an odd and an even alternating path can reach. Only
 * when the primal phase is exhausted are whole cherry blossoms shrunk into
 * supernodes (one shrink instead of a cascade of nested traditional
 * blossoms, which keeps supernodes shallow), after which the dual update
 * assigns a common delta to every connected component of trees linked by
 * tight plus-minus edges and raises it greedily. A supernode is expanded
 * (Lemma 2.3 receptacle rotation followed by uncontraction) as soon as it
 * is a purely-minus node with a zero dual variable.
 *
 * <p>Initialization follows the greedy strategy of Section 5.1: node duals
 * start at half the minimum incident weight, are raised greedily until an
 * incident edge becomes tight, and a greedy matching over tight edges seeds
 * the primal solution. The lazy-heap machinery of Section 5 is replaced by
 * straightforward edge scans: the graphs handed over by the pairing engines
 * are small, so simplicity wins over the asymptotics the paper optimizes.
 *
 * <p>Weights are plain doubles; an edge is tight when its slack is at most
 * {@link #EPS}. Dual bounds are computed only from non-tight quantities, so
 * every dual update makes strictly positive progress.
 */
final class BlossomVI {

    private static final double EPS = 1e-9;

    /** An edge keeps its original endpoints; contraction is resolved lazily. */
    private static final class Edge {
        final Node[] elem = new Node[2];
        double slack;
        boolean matched;

        Edge(Node a, Node b) {
            elem[0] = a;
            elem[1] = b;
        }
    }

    /**
     * An elementary node of the input graph or a supernode holding a shrunk
     * cherry blossom. The cherry-forest fields (parents, receptacle,
     * labels) describe the node while it is a top node inside a tree; when
     * the node is swallowed by a supernode they stay frozen and describe
     * the blossom's internal cherry forest until it is expanded.
     */
    private static final class Node {
        final int id;
        double y;
        Node blossomParent;
        List<Node> children;
        Node internalRoot;
        List<Edge> edges;
        Edge matched;
        Edge plusParent;
        Edge minusParent;
        Node receptacle;
        boolean isPlus;
        boolean isMinus;
        Tree tree;
        int stamp;

        Node(int id) {
            this.id = id;
        }

        boolean isSuper() {
            return children != null;
        }
    }

    private static final class Tree {
        Node root;
        final List<Node> nodes = new ArrayList<>();
        boolean alive = true;
        double delta;
        int index;

        Tree(Node root) {
            this.root = root;
            nodes.add(root);
        }
    }

    /** A walk along parent arcs: {@code nodes} has one entry more than {@code edges}. */
    private static final class Walk {
        final List<Node> nodes = new ArrayList<>();
        final List<Edge> edges = new ArrayList<>();

        Node last() {
            return nodes.getLast();
        }
    }

    private final Node[] elementary;
    private final List<Edge> allEdges = new ArrayList<>();
    private final List<Tree> trees = new ArrayList<>();
    private final ArrayDeque<Node> plusQueue = new ArrayDeque<>();
    private int stampCounter;

    private BlossomVI(int n, int[] from, int[] to, double[] weight) {
        elementary = new Node[n];
        for (int v = 0; v < n; v++) {
            elementary[v] = new Node(v);
            elementary[v].edges = new ArrayList<>();
        }
        for (int e = 0; e < from.length; e++) {
            Edge edge = new Edge(elementary[from[e]], elementary[to[e]]);
            edge.slack = weight[e];
            allEdges.add(edge);
            elementary[from[e]].edges.add(edge);
            elementary[to[e]].edges.add(edge);
        }
    }

    /**
     * Computes a minimum-weight perfect matching of the given graph and
     * returns the mate of every vertex.
     *
     * @throws IllegalArgumentException when the graph has no perfect matching
     */
    static int[] minimumWeightPerfectMatching(int n, int[] from, int[] to, double[] weight) {
        if (n % 2 != 0) {
            throw new IllegalArgumentException("Graph does not contain a perfect matching");
        }
        if (n == 0) {
            return new int[0];
        }
        return new BlossomVI(n, from, to, weight).solve();
    }

    private int[] solve() {
        initialize();
        while (!trees.isEmpty()) {
            primalPhase();
            trees.removeIf(tree -> !tree.alive);
            if (trees.isEmpty()) {
                break;
            }
            shrinkPhase();
            dualUpdatePhase();
        }
        return extractMatching();
    }

    // ------------------------------------------------------------------
    // Initialization (Section 5.1, greedy strategy)
    // ------------------------------------------------------------------

    private void initialize() {
        for (Node v : elementary) {
            if (v.edges.isEmpty()) {
                throw new IllegalArgumentException("Graph does not contain a perfect matching");
            }
            double min = Double.POSITIVE_INFINITY;
            for (Edge e : v.edges) {
                min = Math.min(min, e.slack);
            }
            v.y = min / 2.0;
        }
        for (Edge e : allEdges) {
            e.slack -= e.elem[0].y + e.elem[1].y;
        }
        for (Node v : elementary) {
            double min = Double.POSITIVE_INFINITY;
            for (Edge e : v.edges) {
                min = Math.min(min, e.slack);
            }
            if (min > 0) {
                v.y += min;
                for (Edge e : v.edges) {
                    e.slack -= min;
                }
            }
        }
        for (Node v : elementary) {
            if (v.matched != null) {
                continue;
            }
            for (Edge e : v.edges) {
                Node other = e.elem[0] == v ? e.elem[1] : e.elem[0];
                if (e.slack <= EPS && other.matched == null) {
                    e.matched = true;
                    v.matched = e;
                    other.matched = e;
                    break;
                }
            }
        }
        for (Node v : elementary) {
            if (v.matched == null) {
                Tree tree = new Tree(v);
                v.tree = tree;
                v.isPlus = true;
                trees.add(tree);
            }
        }
    }

    // ------------------------------------------------------------------
    // Contraction bookkeeping
    // ------------------------------------------------------------------

    private Node top(Node v) {
        Node cur = v;
        while (cur.blossomParent != null) {
            cur = cur.blossomParent;
        }
        return cur;
    }

    private Node topOther(Edge e, Node side) {
        Node a = top(e.elem[0]);
        return a == side ? top(e.elem[1]) : a;
    }

    /** The direct child of {@code parent} whose subtree contains {@code descendant}. */
    private Node childUnder(Node parent, Node descendant) {
        Node cur = descendant;
        while (cur.blossomParent != parent) {
            cur = cur.blossomParent;
        }
        return cur;
    }

    private boolean isDescendantOf(Node descendant, Node ancestor) {
        for (Node cur = descendant; cur != null; cur = cur.blossomParent) {
            if (cur == ancestor) {
                return true;
            }
        }
        return false;
    }

    private void collectElementary(Node node, List<Node> out) {
        if (!node.isSuper()) {
            out.add(node);
            return;
        }
        for (Node child : node.children) {
            collectElementary(child, out);
        }
    }

    /** The receptacle of the cherry blossom of a plus node, resolved lazily. */
    private Node receptacleOf(Node v) {
        if (!(v.isPlus && v.isMinus)) {
            return v;
        }
        Node r = receptacleOf(v.receptacle);
        v.receptacle = r;
        return r;
    }

    // ------------------------------------------------------------------
    // Walks along the cherry forest
    // ------------------------------------------------------------------

    /**
     * Follows parent arcs starting with {@code first}: after a matched arc
     * the walk continues with the minus-parent, after an unmatched arc with
     * the plus-parent, until the tree root (the node without continuation).
     */
    private Walk walkFrom(Node start, Edge first) {
        Walk walk = new Walk();
        walk.nodes.add(start);
        if (first == null) {
            return walk;
        }
        Node cur = start;
        Edge arc = first;
        int guard = 4 * elementary.length + 4;
        while (guard-- > 0) {
            walk.edges.add(arc);
            cur = topOther(arc, cur);
            walk.nodes.add(cur);
            Edge next = arc.matched ? cur.minusParent : cur.plusParent;
            if (next == null) {
                return walk;
            }
            arc = next;
        }
        throw new IllegalStateException("Cherry forest walk did not terminate");
    }

    private Walk plusWalk(Node v) {
        return walkFrom(v, v.plusParent);
    }

    /**
     * Same walk inside a shrunk blossom: endpoints resolve to the children
     * of {@code blossom} and the walk stops at its internal root.
     */
    private Walk internalWalk(Node blossom, Node start) {
        Walk walk = new Walk();
        walk.nodes.add(start);
        if (start == blossom.internalRoot) {
            return walk;
        }
        Node cur = start;
        Edge arc = start.plusParent;
        int guard = 4 * elementary.length + 4;
        while (guard-- > 0) {
            walk.edges.add(arc);
            Node a = childUnder(blossom, arc.elem[0]);
            cur = a == cur ? childUnder(blossom, arc.elem[1]) : a;
            walk.nodes.add(cur);
            if (cur == blossom.internalRoot) {
                return walk;
            }
            arc = arc.matched ? cur.minusParent : cur.plusParent;
        }
        throw new IllegalStateException("Blossom-internal walk did not terminate");
    }

    // ------------------------------------------------------------------
    // Primal phase: maximum matching over the tight edges
    // ------------------------------------------------------------------

    private void primalPhase() {
        plusQueue.clear();
        expandEligibleSupernodes();
        int stamp = ++stampCounter;
        for (Node v : elementary) {
            Node topNode = top(v);
            if (topNode.stamp == stamp) {
                continue;
            }
            topNode.stamp = stamp;
            if (topNode.tree != null && topNode.tree.alive && topNode.isPlus) {
                plusQueue.add(topNode);
            }
        }
        while (!plusQueue.isEmpty()) {
            Node u = plusQueue.poll();
            if (u.blossomParent == null && u.tree != null && u.tree.alive && u.isPlus) {
                scanPlusNode(u);
            }
        }
    }

    private void expandEligibleSupernodes() {
        for (Tree tree : trees) {
            if (!tree.alive) {
                continue;
            }
            for (int i = 0; i < tree.nodes.size(); i++) {
                Node node = tree.nodes.get(i);
                if (node.blossomParent == null && node.tree == tree
                        && node.isMinus && !node.isPlus && node.isSuper() && node.y <= EPS) {
                    expand(node);
                }
            }
        }
    }

    private void scanPlusNode(Node u) {
        List<Node> members = new ArrayList<>();
        collectElementary(u, members);
        for (Node member : members) {
            for (Edge e : member.edges) {
                if (e.slack > EPS) {
                    continue;
                }
                if (u.blossomParent != null || u.tree == null || !u.tree.alive || !u.isPlus) {
                    return;
                }
                Node v = topOther(e, u);
                if (v == u) {
                    continue;
                }
                if (v.tree == null) {
                    growOut(u, e, v);
                } else if (v.isPlus) {
                    if (v.tree == u.tree) {
                        if (receptacleOf(u) != receptacleOf(v) && !e.matched) {
                            growIn(u, v, e);
                        }
                    } else {
                        augment(u, e, v);
                        return;
                    }
                }
            }
        }
    }

    /** Grow-out: acquire the free matched pair (v, w) as children of plus node u. */
    private void growOut(Node u, Edge e, Node v) {
        Node w = topOther(v.matched, v);
        Tree tree = u.tree;
        v.tree = tree;
        v.isMinus = true;
        v.isPlus = false;
        v.minusParent = e;
        v.plusParent = null;
        v.receptacle = null;
        w.tree = tree;
        w.isPlus = true;
        w.isMinus = false;
        w.plusParent = v.matched;
        w.minusParent = null;
        w.receptacle = null;
        tree.nodes.add(v);
        tree.nodes.add(w);
        plusQueue.add(w);
        if (v.isSuper() && v.y <= EPS) {
            expand(v);
        }
    }

    /**
     * Grow-in: a tight unmatched edge between two plus nodes of the same
     * tree but different cherry blossoms merges everything on the two
     * branches up to the meeting blossom into one cherry blossom. Both
     * branch walks are captured before any mutation.
     */
    private void growIn(Node u, Node v, Edge e) {
        Walk pathU = plusWalk(u);
        Walk pathV = plusWalk(v);
        Map<Edge, Node> arcsOfU = new HashMap<>();
        for (int i = 0; i < pathU.edges.size(); i++) {
            arcsOfU.put(pathU.edges.get(i), pathU.nodes.get(i));
        }
        Node meeting = null;
        for (int i = 0; i < pathV.edges.size(); i++) {
            if (arcsOfU.get(pathV.edges.get(i)) == pathV.nodes.get(i)) {
                meeting = pathV.nodes.get(i);
                break;
            }
        }
        if (meeting == null) {
            meeting = pathU.last();
        }
        Node receptacle = receptacleOf(meeting);
        convertBranch(pathU, e, receptacle);
        convertBranch(pathV, e, receptacle);
    }

    /**
     * Converts one branch up to the meeting blossom: every node of the
     * captured plus-walk before the blossom becomes a ±-node whose backward
     * parent runs along the path (the forward parent it already had), and
     * the branch start is closed through edge {@code e}.
     */
    private void convertBranch(Walk path, Edge e, Node receptacle) {
        Node w = path.nodes.getFirst();
        if (receptacleOf(w) == receptacle) {
            return;
        }
        int cut = -1;
        for (int i = 1; i < path.nodes.size(); i++) {
            if (receptacleOf(path.nodes.get(i)) == receptacle) {
                cut = i;
                break;
            }
        }
        if (cut < 0) {
            throw new IllegalStateException("Cherry blossom receptacle not found on branch");
        }
        for (int i = 0; i < cut; i++) {
            Node node = path.nodes.get(i);
            node.isPlus = true;
            node.isMinus = true;
            node.receptacle = receptacle;
            // A formerly purely-minus node is now a plus-node too; its
            // tight edges were ignored so far and must be scanned.
            plusQueue.add(node);
        }
        w.minusParent = e;
        for (int i = 1; i < cut; i++) {
            Edge backward = path.edges.get(i - 1);
            Node node = path.nodes.get(i);
            if (backward.matched) {
                node.plusParent = backward;
            } else {
                node.minusParent = backward;
            }
        }
    }

    /** Augment: flip the matching along reverse(P+_u), (u,v), P+_v and free both trees. */
    private void augment(Node u, Edge e, Node v) {
        Walk pathU = plusWalk(u);
        Walk pathV = plusWalk(v);
        List<Node> nodes = new ArrayList<>(pathU.nodes.reversed());
        List<Edge> edges = new ArrayList<>(pathU.edges.reversed());
        edges.add(e);
        edges.addAll(pathV.edges);
        nodes.addAll(pathV.nodes);
        for (Edge edge : edges) {
            edge.matched = !edge.matched;
        }
        for (int i = 0; i < nodes.size(); i++) {
            Edge left = i > 0 ? edges.get(i - 1) : null;
            Edge right = i < edges.size() ? edges.get(i) : null;
            nodes.get(i).matched = left != null && left.matched ? left : right;
        }
        Tree treeU = u.tree;
        Tree treeV = v.tree;
        destroyTree(treeU);
        destroyTree(treeV);
    }

    private void destroyTree(Tree tree) {
        List<Node> freed = new ArrayList<>();
        for (Node node : tree.nodes) {
            if (node.blossomParent == null && node.tree == tree) {
                node.tree = null;
                node.isPlus = false;
                node.isMinus = false;
                node.plusParent = null;
                node.minusParent = null;
                node.receptacle = null;
                freed.add(node);
            }
        }
        tree.alive = false;
        for (Node node : freed) {
            enqueueTightPlusNeighbors(node);
        }
    }

    /**
     * A node just became free: plus nodes of other trees that reach it
     * through a tight edge gained a Grow-out opportunity and must be
     * scanned again.
     */
    private void enqueueTightPlusNeighbors(Node freedTop) {
        List<Node> members = new ArrayList<>();
        collectElementary(freedTop, members);
        for (Node member : members) {
            for (Edge e : member.edges) {
                if (e.slack > EPS) {
                    continue;
                }
                Node other = topOther(e, freedTop);
                if (other != freedTop && other.tree != null && other.tree.alive && other.isPlus) {
                    plusQueue.add(other);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Shrink phase: contract every cherry blossom into a supernode
    // ------------------------------------------------------------------

    private void shrinkPhase() {
        for (Tree tree : trees) {
            int stamp = ++stampCounter;
            Map<Node, List<Node>> blossoms = new HashMap<>();
            for (Node node : new ArrayList<>(tree.nodes)) {
                if (node.blossomParent == null && node.tree == tree
                        && node.isPlus && node.isMinus && node.stamp != stamp) {
                    node.stamp = stamp;
                    blossoms.computeIfAbsent(receptacleOf(node), r -> new ArrayList<>()).add(node);
                }
            }
            for (Map.Entry<Node, List<Node>> blossom : blossoms.entrySet()) {
                shrink(tree, blossom.getKey(), blossom.getValue());
            }
        }
    }

    private void shrink(Tree tree, Node receptacle, List<Node> members) {
        Node supernode = new Node(-1);
        supernode.children = new ArrayList<>(members);
        supernode.children.add(receptacle);
        supernode.internalRoot = receptacle;
        supernode.y = 0;
        supernode.isPlus = true;
        supernode.tree = tree;
        supernode.matched = receptacle.matched;
        supernode.plusParent = receptacle.plusParent;
        for (Node child : supernode.children) {
            child.blossomParent = supernode;
        }
        receptacle.matched = null;
        receptacle.plusParent = null;
        tree.nodes.add(supernode);
        if (tree.root == receptacle) {
            tree.root = supernode;
        }
    }

    // ------------------------------------------------------------------
    // Expand: receptacle rotation (Lemma 2.3) followed by uncontraction
    // ------------------------------------------------------------------

    private void expand(Node supernode) {
        if (supernode.blossomParent != null || supernode.tree == null || !supernode.tree.alive
                || !supernode.isMinus || supernode.isPlus || !supernode.isSuper()) {
            return;
        }
        Tree tree = supernode.tree;
        Edge matchedEdge = supernode.matched;
        Edge parentEdge = supernode.minusParent;
        Node matchedElem = isDescendantOf(matchedEdge.elem[0], supernode)
                ? matchedEdge.elem[0] : matchedEdge.elem[1];
        Node parentElem = isDescendantOf(parentEdge.elem[0], supernode)
                ? parentEdge.elem[0] : parentEdge.elem[1];
        Node newRoot = childUnder(supernode, matchedElem);
        rotateReceptacle(supernode, newRoot);
        Node entry = childUnder(supernode, parentElem);
        Walk path = internalWalk(supernode, entry);

        for (Node child : supernode.children) {
            child.blossomParent = null;
            child.tree = null;
            child.isPlus = false;
            child.isMinus = false;
            child.plusParent = null;
            child.minusParent = null;
            child.receptacle = null;
        }
        newRoot.matched = matchedEdge;

        for (int i = 0; i < path.nodes.size(); i++) {
            Node node = path.nodes.get(i);
            boolean minus = (path.nodes.size() - 1 - i) % 2 == 0;
            node.tree = tree;
            node.isMinus = minus;
            node.isPlus = !minus;
            if (i == 0) {
                node.minusParent = parentEdge;
            } else if (minus) {
                node.minusParent = path.edges.get(i - 1);
            } else {
                node.plusParent = path.edges.get(i - 1);
            }
            tree.nodes.add(node);
            if (node.isPlus) {
                plusQueue.add(node);
            }
        }
        supernode.tree = null;
        supernode.isMinus = false;
        supernode.matched = null;
        supernode.plusParent = null;
        supernode.minusParent = null;

        for (Node child : supernode.children) {
            if (child.tree == null) {
                enqueueTightPlusNeighbors(child);
            }
        }
        for (Node node : path.nodes) {
            if (node.isMinus && node.isSuper() && node.y <= EPS) {
                expand(node);
            }
        }
    }

    /**
     * Moves the internal root of a shrunk cherry blossom to {@code target}
     * by repeatedly augmenting along the odd cycle closed by the last arc
     * of P+_target, exactly as in Lemma 2.3 of the paper.
     */
    private void rotateReceptacle(Node blossom, Node target) {
        if (blossom.internalRoot == target) {
            return;
        }
        Map<Node, Integer> distance = new HashMap<>();
        Walk targetPath = internalWalk(blossom, target);
        for (int i = 0; i < targetPath.nodes.size(); i++) {
            distance.put(targetPath.nodes.get(i), i);
        }
        while (blossom.internalRoot != target) {
            Node root = blossom.internalRoot;
            Walk pathToRoot = internalWalk(blossom, target);
            Edge closingArc = pathToRoot.edges.getLast();
            Node x = pathToRoot.nodes.get(pathToRoot.nodes.size() - 2);
            Walk pathX = internalWalk(blossom, x);
            int best = -1;
            for (int i = 0; i < pathX.nodes.size(); i++) {
                Integer d = distance.get(pathX.nodes.get(i));
                if (d != null && (best < 0 || d < distance.get(pathX.nodes.get(best)))) {
                    best = i;
                }
            }
            Node newRoot = pathX.nodes.get(best);

            closingArc.matched = true;
            for (int i = 0; i < best; i++) {
                pathX.edges.get(i).matched = !pathX.edges.get(i).matched;
            }
            root.matched = closingArc;
            for (int i = 0; i < best; i++) {
                Node node = pathX.nodes.get(i);
                Edge left = i > 0 ? pathX.edges.get(i - 1) : closingArc;
                Edge right = pathX.edges.get(i);
                node.matched = left.matched ? left : right;
            }
            newRoot.matched = null;

            List<Edge> cycleEdges = new ArrayList<>(pathX.edges);
            cycleEdges.add(closingArc);
            for (int i = 0; i < pathX.nodes.size(); i++) {
                Node node = pathX.nodes.get(i);
                if (node == newRoot) {
                    continue;
                }
                Edge before = i == 0 ? closingArc : cycleEdges.get(i - 1);
                Edge after = cycleEdges.get(i);
                node.plusParent = before.matched ? before : after;
                node.minusParent = before.matched ? after : before;
            }
            blossom.internalRoot = newRoot;
        }
    }

    // ------------------------------------------------------------------
    // Dual update (connected components strategy, Section 5.2)
    // ------------------------------------------------------------------

    private void dualUpdatePhase() {
        int treeCount = trees.size();
        for (int i = 0; i < treeCount; i++) {
            trees.get(i).index = i;
        }
        double[] singleBound = new double[treeCount];
        Arrays.fill(singleBound, Double.POSITIVE_INFINITY);
        int[] component = new int[treeCount];
        for (int i = 0; i < treeCount; i++) {
            component[i] = i;
        }
        List<int[]> plusPlus = new ArrayList<>();
        List<Double> plusPlusSlack = new ArrayList<>();
        List<int[]> plusMinus = new ArrayList<>();
        List<Double> plusMinusSlack = new ArrayList<>();

        int stamp = ++stampCounter;
        for (Node v : elementary) {
            Node topNode = top(v);
            if (topNode.stamp == stamp) {
                continue;
            }
            topNode.stamp = stamp;
            if (topNode.tree != null && topNode.isMinus && topNode.isSuper()) {
                int i = topNode.tree.index;
                singleBound[i] = Math.min(singleBound[i], topNode.y);
            }
        }
        for (Edge e : allEdges) {
            Node a = top(e.elem[0]);
            Node b = top(e.elem[1]);
            if (a == b) {
                continue;
            }
            boolean aPlus = a.tree != null && a.isPlus;
            boolean bPlus = b.tree != null && b.isPlus;
            boolean aMinus = a.tree != null && a.isMinus;
            boolean bMinus = b.tree != null && b.isMinus;
            if (aPlus && bPlus) {
                if (a.tree == b.tree) {
                    singleBound[a.tree.index] = Math.min(singleBound[a.tree.index], e.slack / 2.0);
                } else {
                    plusPlus.add(new int[]{a.tree.index, b.tree.index});
                    plusPlusSlack.add(e.slack);
                }
            } else if (aPlus && b.tree == null) {
                singleBound[a.tree.index] = Math.min(singleBound[a.tree.index], e.slack);
            } else if (bPlus && a.tree == null) {
                singleBound[b.tree.index] = Math.min(singleBound[b.tree.index], e.slack);
            } else if (aPlus && bMinus && a.tree != b.tree) {
                if (e.slack <= EPS) {
                    union(component, a.tree.index, b.tree.index);
                } else {
                    plusMinus.add(new int[]{a.tree.index, b.tree.index});
                    plusMinusSlack.add(e.slack);
                }
            } else if (bPlus && aMinus && a.tree != b.tree) {
                if (e.slack <= EPS) {
                    union(component, b.tree.index, a.tree.index);
                } else {
                    plusMinus.add(new int[]{b.tree.index, a.tree.index});
                    plusMinusSlack.add(e.slack);
                }
            }
        }

        double[] componentDelta = new double[treeCount];
        boolean[] processed = new boolean[treeCount];
        double maxDelta = 0;
        for (int c = 0; c < treeCount; c++) {
            if (find(component, c) != c) {
                continue;
            }
            double delta = Double.POSITIVE_INFINITY;
            for (int i = 0; i < treeCount; i++) {
                if (find(component, i) == c) {
                    delta = Math.min(delta, singleBound[i]);
                }
            }
            for (int k = 0; k < plusPlus.size(); k++) {
                int ci = find(component, plusPlus.get(k)[0]);
                int cj = find(component, plusPlus.get(k)[1]);
                double slack = plusPlusSlack.get(k);
                if (ci == c && cj == c) {
                    delta = Math.min(delta, slack / 2.0);
                } else if (ci == c) {
                    delta = Math.min(delta, slack - (processed[cj] ? componentDelta[cj] : 0));
                } else if (cj == c) {
                    delta = Math.min(delta, slack - (processed[ci] ? componentDelta[ci] : 0));
                }
            }
            for (int k = 0; k < plusMinus.size(); k++) {
                int ci = find(component, plusMinus.get(k)[0]);
                int cj = find(component, plusMinus.get(k)[1]);
                double slack = plusMinusSlack.get(k);
                if (ci == c && cj != c) {
                    delta = Math.min(delta, slack + (processed[cj] ? componentDelta[cj] : 0));
                }
            }
            if (Double.isInfinite(delta)) {
                throw new IllegalArgumentException("Graph does not contain a perfect matching");
            }
            componentDelta[c] = Math.max(delta, 0);
            processed[c] = true;
            maxDelta = Math.max(maxDelta, componentDelta[c]);
        }
        if (maxDelta <= 0) {
            throw new IllegalStateException("Dual update made no progress");
        }

        for (Tree tree : trees) {
            tree.delta = componentDelta[find(component, tree.index)];
        }
        stamp = ++stampCounter;
        for (Node v : elementary) {
            Node topNode = top(v);
            if (topNode.stamp == stamp) {
                continue;
            }
            topNode.stamp = stamp;
            if (topNode.tree != null) {
                if (topNode.isPlus) {
                    topNode.y += topNode.tree.delta;
                } else if (topNode.isMinus) {
                    topNode.y -= topNode.tree.delta;
                }
            }
        }
        for (Edge e : allEdges) {
            Node a = top(e.elem[0]);
            Node b = top(e.elem[1]);
            if (a == b) {
                continue;
            }
            e.slack -= contribution(a) + contribution(b);
        }
    }

    private double contribution(Node topNode) {
        if (topNode.tree == null) {
            return 0;
        }
        if (topNode.isPlus) {
            return topNode.tree.delta;
        }
        if (topNode.isMinus) {
            return -topNode.tree.delta;
        }
        return 0;
    }

    private int find(int[] component, int i) {
        int root = i;
        while (component[root] != root) {
            root = component[root];
        }
        while (component[i] != root) {
            int next = component[i];
            component[i] = root;
            i = next;
        }
        return root;
    }

    private void union(int[] component, int a, int b) {
        component[find(component, a)] = find(component, b);
    }

    // ------------------------------------------------------------------
    // Final extraction: dissolve every remaining supernode
    // ------------------------------------------------------------------

    private int[] extractMatching() {
        int[] mate = new int[elementary.length];
        Arrays.fill(mate, -1);
        Set<Edge> visited = new HashSet<>();
        int stamp = ++stampCounter;
        for (Node v : elementary) {
            Node topNode = top(v);
            if (topNode.stamp == stamp) {
                continue;
            }
            topNode.stamp = stamp;
            Edge e = topNode.matched;
            if (e == null || !visited.add(e)) {
                continue;
            }
            mate[e.elem[0].id] = e.elem[1].id;
            mate[e.elem[1].id] = e.elem[0].id;
            dissolve(mate, top(e.elem[0]), e.elem[0]);
            dissolve(mate, top(e.elem[1]), e.elem[1]);
        }
        for (int v = 0; v < mate.length; v++) {
            if (mate[v] < 0) {
                throw new IllegalStateException("Matching extraction left an unmatched vertex");
            }
        }
        return mate;
    }

    /**
     * Recursively dissolves a supernode whose external matched edge enters
     * at elementary node {@code taken}: the internal root rotates to the
     * child holding {@code taken}, and the internal matching of the other
     * children becomes part of the final matching.
     */
    private void dissolve(int[] mate, Node node, Node taken) {
        if (!node.isSuper()) {
            return;
        }
        Node rootChild = childUnder(node, taken);
        rotateReceptacle(node, rootChild);
        Set<Edge> internal = new LinkedHashSet<>();
        for (Node child : node.children) {
            if (child != rootChild && child.matched != null) {
                internal.add(child.matched);
            }
        }
        for (Edge e : internal) {
            mate[e.elem[0].id] = e.elem[1].id;
            mate[e.elem[1].id] = e.elem[0].id;
            dissolve(mate, childUnder(node, e.elem[0]), e.elem[0]);
            dissolve(mate, childUnder(node, e.elem[1]), e.elem[1]);
        }
        dissolve(mate, rootChild, taken);
    }
}
