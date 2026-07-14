package com.open.chess.tournament.domain.service;

import com.open.chess.tournament.domain.exception.NoPairingPossibleException;
import com.open.chess.tournament.domain.service.PairingPlan.Board;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Swiss-system pairing engine (Dutch-style fold pairing).

 * Players are ranked by score, then rating. Within a score group the top
 * half is preferably paired against the bottom half (1 vs n/2+1, ...).
 * Absolute FIDE criteria are enforced:
 * - Rematches are never allowed (pairings are found via backtracking).
 * - A player never receives more than one bye; with an odd number of
 *   players every eligible bye choice is attempted, preferring the
 *   lowest-ranked player who did not float down in the previous round.
 * - Two players with the same absolute color preference (color difference
 *   of two or more, or the same color in the last two games) are not
 *   paired together; this rule alone is relaxed, as a last resort, when no
 *   pairing would exist otherwise.
 * Consecutive upfloats are avoided by preference when a player must be
 * paired against a lower score group. If no rematch-free, single-bye
 * pairing is possible, the round cannot be generated and a domain error
 * is raised.
 */
public class SwissPairingEngine {

    public PairingPlan generate(List<PairingCandidate> candidates) {
        if (candidates.size() < 2) {
            throw new NoPairingPossibleException("At least two active players are required to pair a round");
        }
        List<PairingCandidate> ranked = candidates.stream()
                .sorted(Comparator
                        .comparingDouble(PairingCandidate::score).reversed()
                        .thenComparing(Comparator.comparingInt(PairingCandidate::rating).reversed())
                        .thenComparing(candidate -> candidate.playerId().toString()))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);

        PairingPlan plan = attempt(ranked, true);
        if (plan == null) {
            plan = attempt(ranked, false);
        }
        if (plan == null) {
            throw new NoPairingPossibleException("Unable to generate a round without rematches or a second bye");
        }
        return plan;
    }

    private PairingPlan attempt(List<PairingCandidate> ranked, boolean enforceColorRule) {
        UUID byePlayerId = null;
        List<PairingCandidate[]> pairs;
        if (ranked.size() % 2 != 0) {
            pairs = null;
            for (PairingCandidate byeCandidate : byeCandidatesInPreferenceOrder(ranked)) {
                List<PairingCandidate> toPair = new ArrayList<>(ranked);
                toPair.remove(byeCandidate);
                pairs = solve(toPair, enforceColorRule);
                if (pairs != null) {
                    byePlayerId = byeCandidate.playerId();
                    break;
                }
            }
        } else {
            pairs = solve(ranked, enforceColorRule);
        }
        if (pairs == null) {
            return null;
        }

        List<Board> boards = new ArrayList<>(pairs.size());
        for (int i = 0; i < pairs.size(); i++) {
            boards.add(assignColors(pairs.get(i)[0], pairs.get(i)[1], i));
        }
        return new PairingPlan(boards, byePlayerId);
    }

    /**
     * Bye attempts from the bottom of the ranking up. Players who already
     * had a bye are never eligible; a bye is a downfloat, so players who
     * floated down in the previous round are tried last.
     */
    private List<PairingCandidate> byeCandidatesInPreferenceOrder(List<PairingCandidate> ranked) {
        List<PairingCandidate> preferred = new ArrayList<>();
        List<PairingCandidate> recentDownfloaters = new ArrayList<>();
        for (int i = ranked.size() - 1; i >= 0; i--) {
            PairingCandidate candidate = ranked.get(i);
            if (candidate.hadBye()) {
                continue;
            }
            if (candidate.floatedDownLastRound()) {
                recentDownfloaters.add(candidate);
            } else {
                preferred.add(candidate);
            }
        }
        preferred.addAll(recentDownfloaters);
        return preferred;
    }

    private List<PairingCandidate[]> solve(List<PairingCandidate> remaining, boolean enforceColorRule) {
        if (remaining.isEmpty()) {
            return new ArrayList<>();
        }
        PairingCandidate first = remaining.getFirst();
        for (PairingCandidate opponent : preferredOpponents(first, remaining)) {
            if (first.hasPlayed(opponent.playerId())) {
                continue;
            }
            if (enforceColorRule && haveSameAbsoluteColorPreference(first, opponent)) {
                continue;
            }
            List<PairingCandidate> rest = new ArrayList<>(remaining);
            rest.remove(first);
            rest.remove(opponent);
            List<PairingCandidate[]> solution = solve(rest, enforceColorRule);
            if (solution != null) {
                solution.addFirst(new PairingCandidate[]{first, opponent});
                return solution;
            }
        }
        return null;
    }

    private boolean haveSameAbsoluteColorPreference(PairingCandidate first, PairingCandidate opponent) {
        return first.hasAbsoluteColorPreference()
                && opponent.hasAbsoluteColorPreference()
                && first.colorPreference() == opponent.colorPreference();
    }

    /**
     * Opponent preference for the group leader: fold order inside the same
     * score group (the opposite-half player first), then lower score groups
     * from the top down, avoiding players who upfloated in the previous
     * round or upfloated often before.
     */
    private List<PairingCandidate> preferredOpponents(PairingCandidate first, List<PairingCandidate> remaining) {
        List<PairingCandidate> sameGroup = new ArrayList<>();
        List<PairingCandidate> lowerGroups = new ArrayList<>();
        for (PairingCandidate candidate : remaining) {
            if (candidate == first) {
                continue;
            }
            if (candidate.score() == first.score()) {
                sameGroup.add(candidate);
            } else {
                lowerGroups.add(candidate);
            }
        }
        List<PairingCandidate> preferred = new ArrayList<>(remaining.size() - 1);
        int fold = sameGroup.size() / 2;
        for (int i = fold; i < sameGroup.size(); i++) {
            preferred.add(sameGroup.get(i));
        }
        for (int i = fold - 1; i >= 0; i--) {
            preferred.add(sameGroup.get(i));
        }
        lowerGroups.sort(Comparator
                .comparing(PairingCandidate::floatedUpLastRound)
                .thenComparingInt(PairingCandidate::upFloats)
                .thenComparing(Comparator.comparingDouble(PairingCandidate::score).reversed()
                        .thenComparing(Comparator.comparingInt(PairingCandidate::rating).reversed())));
        preferred.addAll(lowerGroups);
        return preferred;
    }

    private Board assignColors(PairingCandidate higher, PairingCandidate lower, int boardIndex) {
        int higherPref = higher.colorPreference();
        int lowerPref = lower.colorPreference();
        boolean higherAbsolute = higher.hasAbsoluteColorPreference();
        boolean lowerAbsolute = lower.hasAbsoluteColorPreference();

        Board higherWhite = new Board(higher.playerId(), lower.playerId());
        Board higherBlack = new Board(lower.playerId(), higher.playerId());
        Board higherChoice = higherPref == PairingCandidate.WHITE ? higherWhite : higherBlack;

        if (higherAbsolute && lowerAbsolute) {
            // Opposite preferences satisfy both; equal preferences only
            // happen when the color rule was relaxed, and then the higher
            // ranked player's preference prevails.
            return higherChoice;
        }
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
            // First round: alternate colors down the boards.
            return boardIndex % 2 == 0 ? higherWhite : higherBlack;
        }
        return higherChoice;
    }
}
