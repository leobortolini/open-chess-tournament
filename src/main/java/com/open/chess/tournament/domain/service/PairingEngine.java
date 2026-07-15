package com.open.chess.tournament.domain.service;

import java.util.List;

/**
 * Builds one round's pairings from the current state of every active
 * player. Implementations differ in how they search for the round, not in
 * the rules they must respect: no rematches, no second bye, and no bye for
 * a player who won by forfeit.
 */
public interface PairingEngine {

    PairingPlan generate(List<PairingCandidate> candidates);
}
