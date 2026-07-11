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
 * Rematches are never allowed: pairings are found via backtracking and,
 * with an odd number of players, every bye choice is attempted (preferring
 * the lowest-ranked player without a previous bye) until a rematch-free
 * pairing exists. If no such pairing is possible, the round cannot be
 * generated and a domain error is raised.
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

        UUID byePlayerId = null;
        List<PairingCandidate[]> pairs;
        if (ranked.size() % 2 != 0) {
            pairs = null;
            for (PairingCandidate byeCandidate : byeCandidatesInPreferenceOrder(ranked)) {
                List<PairingCandidate> toPair = new ArrayList<>(ranked);
                toPair.remove(byeCandidate);
                pairs = solve(toPair);
                if (pairs != null) {
                    byePlayerId = byeCandidate.playerId();
                    break;
                }
            }
        } else {
            pairs = solve(ranked);
        }
        if (pairs == null) {
            throw new NoPairingPossibleException("Unable to generate a round without rematches");
        }

        List<Board> boards = new ArrayList<>(pairs.size());
        for (int i = 0; i < pairs.size(); i++) {
            boards.add(assignColors(pairs.get(i)[0], pairs.get(i)[1], i));
        }
        return new PairingPlan(boards, byePlayerId);
    }

    /**
     * Bye attempts from the bottom of the ranking up, players without a
     * previous bye first.
     */
    private List<PairingCandidate> byeCandidatesInPreferenceOrder(List<PairingCandidate> ranked) {
        List<PairingCandidate> withoutBye = new ArrayList<>();
        List<PairingCandidate> withBye = new ArrayList<>();
        for (int i = ranked.size() - 1; i >= 0; i--) {
            PairingCandidate candidate = ranked.get(i);
            if (candidate.hadBye()) {
                withBye.add(candidate);
            } else {
                withoutBye.add(candidate);
            }
        }
        withoutBye.addAll(withBye);
        return withoutBye;
    }

    private List<PairingCandidate[]> solve(List<PairingCandidate> remaining) {
        if (remaining.isEmpty()) {
            return new ArrayList<>();
        }
        PairingCandidate first = remaining.getFirst();
        for (PairingCandidate opponent : preferredOpponents(first, remaining)) {
            if (first.hasPlayed(opponent.playerId())) {
                continue;
            }
            List<PairingCandidate> rest = new ArrayList<>(remaining);
            rest.remove(first);
            rest.remove(opponent);
            List<PairingCandidate[]> solution = solve(rest);
            if (solution != null) {
                solution.addFirst(new PairingCandidate[]{first, opponent});
                return solution;
            }
        }
        return null;
    }

    /**
     * Opponent preference for the group leader: fold order inside the same
     * score group (the opposite-half player first), then lower score groups
     * from the top down.
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
                .comparingInt(PairingCandidate::upFloats)
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

        Board board1 = new Board(higher.playerId(), lower.playerId());
        Board board2 = new Board(lower.playerId(), higher.playerId());
        Board board = higherPref == PairingCandidate.WHITE ? board1 : board2;

        if (higherAbsolute && lowerAbsolute && higherPref != lowerPref) {
            return board;
        }
        if (higherAbsolute && !lowerAbsolute) {
            return board;
        }
        if (lowerAbsolute && !higherAbsolute) {
            return lowerPref == PairingCandidate.WHITE ? board2 : board1;
        }

        if (higher.colorBalance() < lower.colorBalance()) {
            return board1;
        }

        if (lower.colorBalance() < higher.colorBalance()) {
            return board2;
        }

        if (higher.lastColor() == PairingCandidate.BLACK && lower.lastColor() == PairingCandidate.WHITE) {
            return board1;
        }
        if (higher.lastColor() == PairingCandidate.WHITE && lower.lastColor() == PairingCandidate.BLACK) {
            return board2;
        }

        return boardIndex % 2 == 0 ? board1 : board2;
    }
}
