package com.open.chess.tournament.interfaces.rest;

import com.open.chess.tournament.application.TournamentService;
import com.open.chess.tournament.application.dto.NextRoundDto;
import com.open.chess.tournament.application.dto.PairingDto;
import com.open.chess.tournament.application.dto.PlayerDto;
import com.open.chess.tournament.application.dto.RoundDto;
import com.open.chess.tournament.application.dto.StandingDto;
import com.open.chess.tournament.application.dto.TournamentDto;
import com.open.chess.tournament.interfaces.rest.dto.CreateTournamentRequest;
import com.open.chess.tournament.interfaces.rest.dto.RegisterPlayerRequest;
import com.open.chess.tournament.interfaces.rest.dto.ReportResultRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/tournaments")
public class TournamentController {

    private final TournamentService tournamentService;

    public TournamentController(TournamentService tournamentService) {
        this.tournamentService = tournamentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TournamentDto create(@Valid @RequestBody CreateTournamentRequest request) {
        return tournamentService.createTournament(request.name(), request.totalRounds());
    }

    @GetMapping
    public List<TournamentDto> list() {
        return tournamentService.listTournaments();
    }

    @GetMapping("/{tournamentId}")
    public TournamentDto get(@PathVariable UUID tournamentId) {
        return tournamentService.getTournament(tournamentId);
    }

    @PostMapping("/{tournamentId}/players")
    @ResponseStatus(HttpStatus.CREATED)
    public PlayerDto registerPlayer(@PathVariable UUID tournamentId,
                                    @Valid @RequestBody RegisterPlayerRequest request) {
        return tournamentService.registerPlayer(tournamentId, request.name(), request.rating());
    }

    @GetMapping("/{tournamentId}/players")
    public List<PlayerDto> listPlayers(@PathVariable UUID tournamentId) {
        return tournamentService.listPlayers(tournamentId);
    }

    @PostMapping("/{tournamentId}/start")
    public TournamentDto start(@PathVariable UUID tournamentId) {
        return tournamentService.startTournament(tournamentId);
    }

    @PostMapping("/{tournamentId}/rounds")
    public ResponseEntity<NextRoundDto> generateNextRound(@PathVariable UUID tournamentId) {
        NextRoundDto result = tournamentService.generateNextRound(tournamentId);
        return result.roundGenerated()
                ? ResponseEntity.status(HttpStatus.CREATED).body(result)
                : ResponseEntity.ok(result);
    }

    @GetMapping("/{tournamentId}/rounds")
    public List<RoundDto> listRounds(@PathVariable UUID tournamentId) {
        return tournamentService.listRounds(tournamentId);
    }

    @GetMapping("/{tournamentId}/rounds/{roundNumber}")
    public RoundDto getRound(@PathVariable UUID tournamentId, @PathVariable int roundNumber) {
        return tournamentService.getRound(tournamentId, roundNumber);
    }

    @PutMapping("/{tournamentId}/pairings/{pairingId}/result")
    public PairingDto reportResult(@PathVariable UUID tournamentId,
                                   @PathVariable UUID pairingId,
                                   @Valid @RequestBody ReportResultRequest request) {
        return tournamentService.reportResult(tournamentId, pairingId, request.result());
    }

    @GetMapping("/{tournamentId}/standings")
    public List<StandingDto> standings(@PathVariable UUID tournamentId) {
        return tournamentService.standings(tournamentId);
    }
}
