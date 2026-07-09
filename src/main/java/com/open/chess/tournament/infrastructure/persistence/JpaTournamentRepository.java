package com.open.chess.tournament.infrastructure.persistence;

import com.open.chess.tournament.domain.model.Tournament;
import com.open.chess.tournament.domain.repository.TournamentRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface JpaTournamentRepository extends JpaRepository<Tournament, UUID>, TournamentRepository {
}
