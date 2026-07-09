package com.open.chess.tournament.domain.repository;

import com.open.chess.tournament.domain.model.Tournament;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TournamentRepository {

    Tournament save(Tournament tournament);

    Optional<Tournament> findById(UUID id);

    List<Tournament> findAll();
}
