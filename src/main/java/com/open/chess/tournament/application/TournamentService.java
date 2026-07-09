package com.open.chess.tournament.application;

import com.open.chess.tournament.application.dto.NextRoundDto;
import com.open.chess.tournament.application.dto.PairingDto;
import com.open.chess.tournament.application.dto.PlayerDto;
import com.open.chess.tournament.application.dto.RoundDto;
import com.open.chess.tournament.application.dto.StandingDto;
import com.open.chess.tournament.application.dto.TournamentDto;
import com.open.chess.tournament.domain.exception.NotFoundException;
import com.open.chess.tournament.domain.model.GameResult;
import com.open.chess.tournament.domain.model.Pairing;
import com.open.chess.tournament.domain.model.Player;
import com.open.chess.tournament.domain.model.Round;
import com.open.chess.tournament.domain.model.Tournament;
import com.open.chess.tournament.domain.repository.TournamentRepository;
import com.open.chess.tournament.domain.service.SwissPairingEngine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class TournamentService {

    private final TournamentRepository tournamentRepository;
    private final SwissPairingEngine pairingEngine;

    public TournamentService(TournamentRepository tournamentRepository, SwissPairingEngine pairingEngine) {
        this.tournamentRepository = tournamentRepository;
        this.pairingEngine = pairingEngine;
    }

    @Transactional
    public TournamentDto createTournament(String name, int totalRounds) {
        Tournament tournament = Tournament.create(name, totalRounds);
        return TournamentDto.from(tournamentRepository.save(tournament));
    }

    @Transactional(readOnly = true)
    public List<TournamentDto> listTournaments() {
        return tournamentRepository.findAll().stream().map(TournamentDto::from).toList();
    }

    @Transactional(readOnly = true)
    public TournamentDto getTournament(UUID tournamentId) {
        return TournamentDto.from(load(tournamentId));
    }

    @Transactional
    public PlayerDto registerPlayer(UUID tournamentId, String name, int rating) {
        Tournament tournament = load(tournamentId);
        Player player = tournament.registerPlayer(name, rating);
        tournamentRepository.save(tournament);
        return PlayerDto.from(player);
    }

    @Transactional(readOnly = true)
    public List<PlayerDto> listPlayers(UUID tournamentId) {
        return load(tournamentId).getPlayers().stream().map(PlayerDto::from).toList();
    }

    @Transactional
    public TournamentDto startTournament(UUID tournamentId) {
        Tournament tournament = load(tournamentId);
        tournament.start();
        return TournamentDto.from(tournamentRepository.save(tournament));
    }

    @Transactional
    public NextRoundDto generateNextRound(UUID tournamentId) {
        Tournament tournament = load(tournamentId);
        NextRoundDto result = tournament.generateNextRound(pairingEngine)
                .map(round -> NextRoundDto.generated(
                        tournament.getStatus().name(), RoundDto.from(tournament, round)))
                .orElseGet(() -> NextRoundDto.tournamentFinished(tournament.getStatus().name()));
        tournamentRepository.save(tournament);
        return result;
    }

    @Transactional(readOnly = true)
    public List<RoundDto> listRounds(UUID tournamentId) {
        Tournament tournament = load(tournamentId);
        return tournament.getRounds().stream()
                .map(round -> RoundDto.from(tournament, round))
                .toList();
    }

    @Transactional(readOnly = true)
    public RoundDto getRound(UUID tournamentId, int roundNumber) {
        Tournament tournament = load(tournamentId);
        Round round = tournament.roundByNumber(roundNumber)
                .orElseThrow(() -> new NotFoundException(
                        "Round " + roundNumber + " not found in tournament " + tournamentId));
        return RoundDto.from(tournament, round);
    }

    @Transactional
    public PairingDto reportResult(UUID tournamentId, UUID pairingId, GameResult result) {
        Tournament tournament = load(tournamentId);
        Pairing pairing = tournament.reportResult(pairingId, result);
        tournamentRepository.save(tournament);
        return PairingDto.from(tournament, pairing);
    }

    @Transactional(readOnly = true)
    public List<StandingDto> standings(UUID tournamentId) {
        return load(tournamentId).standings().stream().map(StandingDto::from).toList();
    }

    private Tournament load(UUID tournamentId) {
        return tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new NotFoundException("Tournament " + tournamentId + " not found"));
    }
}
