package com.open.chess.tournament.domain.model;

import com.open.chess.tournament.domain.exception.DomainException;
import com.open.chess.tournament.domain.exception.NoPairingPossibleException;
import com.open.chess.tournament.domain.exception.NotFoundException;
import com.open.chess.tournament.domain.service.PairingCandidate;
import com.open.chess.tournament.domain.service.PairingEngine;
import com.open.chess.tournament.domain.service.PairingPlan;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Entity
@Table(name = "tournaments")
public class Tournament {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "total_rounds", nullable = false)
    private int totalRounds;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TournamentStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "tournament", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<Player> players = new ArrayList<>();

    @OneToMany(mappedBy = "tournament", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("number")
    private List<Round> rounds = new ArrayList<>();

    protected Tournament() {
    }

    private Tournament(String name, int totalRounds) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.totalRounds = totalRounds;
        this.status = TournamentStatus.REGISTRATION;
        this.createdAt = Instant.now();
    }

    public static Tournament create(String name, int totalRounds) {
        if (totalRounds < 1) {
            throw new DomainException("A tournament must have at least one round");
        }
        return new Tournament(name, totalRounds);
    }

    public Player registerPlayer(String playerName, int rating) {
        if (status != TournamentStatus.REGISTRATION) {
            throw new DomainException("Players can only be registered before the tournament starts");
        }
        Player player = new Player(this, playerName, rating);
        players.add(player);
        return player;
    }

    public void start() {
        if (status != TournamentStatus.REGISTRATION) {
            throw new DomainException("Tournament has already started");
        }
        if (players.size() < 2) {
            throw new DomainException("At least two players are required to start a tournament");
        }
        status = TournamentStatus.IN_PROGRESS;
    }

    /**
     * Generates the next round, or finishes the tournament and returns
     * empty when no rematch-free pairing is possible.
     */
    public Optional<Round> generateNextRound(PairingEngine engine) {
        if (status != TournamentStatus.IN_PROGRESS) {
            throw new DomainException("Rounds can only be generated while the tournament is in progress");
        }
        if (rounds.size() >= totalRounds) {
            throw new DomainException("All rounds have already been generated");
        }
        currentRound().ifPresent(round -> {
            if (!round.isComplete()) {
                throw new DomainException("Round " + round.getNumber() + " still has pending results");
            }
        });

        PairingPlan plan;
        try {
            plan = engine.generate(buildCandidates());
        } catch (NoPairingPossibleException exception) {
            status = TournamentStatus.FINISHED;
            return Optional.empty();
        }
        Round round = new Round(this, rounds.size() + 1);
        int board = 1;
        for (PairingPlan.Board planned : plan.boards()) {
            round.addPairing(board++, planned.whitePlayerId(), planned.blackPlayerId());
        }
        if (plan.byePlayerId() != null) {
            round.addPairing(board, plan.byePlayerId(), null);
        }
        rounds.add(round);
        return Optional.of(round);
    }

    public Pairing reportResult(UUID pairingId, GameResult result) {
        if (status == TournamentStatus.REGISTRATION) {
            throw new DomainException("Tournament has not started yet");
        }
        if (result == GameResult.PENDING || result == GameResult.BYE) {
            throw new DomainException(
                    "Result must be WHITE_WINS, BLACK_WINS, DRAW or a forfeit result");
        }
        Round round = currentRound()
                .orElseThrow(() -> new DomainException("No round has been generated yet"));
        Pairing pairing = round.getPairings().stream()
                .filter(p -> p.getId().equals(pairingId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException(
                        "Pairing " + pairingId + " not found in the current round"));
        if (pairing.isBye()) {
            throw new DomainException("Cannot report a result for a bye");
        }
        pairing.setResult(result);
        if (rounds.size() == totalRounds && round.isComplete()) {
            status = TournamentStatus.FINISHED;
        }
        return pairing;
    }

    public double scoreOf(UUID playerId) {
        return rounds.stream()
                .flatMap(round -> round.getPairings().stream())
                .mapToDouble(pairing -> pairing.pointsFor(playerId))
                .sum();
    }

    public List<PlayerStanding> standings() {
        Map<UUID, Double> scores = players.stream()
                .collect(Collectors.toMap(Player::getId, player -> scoreOf(player.getId())));
        Map<UUID, Double> buchholz = players.stream()
                .collect(Collectors.toMap(Player::getId, player -> buchholz(player.getId(), scores)));
        Map<UUID, Double> medianBuchholz = players.stream()
                .collect(Collectors.toMap(Player::getId,
                        player -> medianBuchholz(player.getId(), scores)));
        Map<UUID, Double> sonnebornBerger = players.stream()
                .collect(Collectors.toMap(Player::getId,
                        player -> sonnebornBerger(player.getId(), scores)));
        Map<UUID, Integer> wins = players.stream()
                .collect(Collectors.toMap(Player::getId, player -> winsOf(player.getId())));

        List<PlayerStanding> standings = players.stream()
                .sorted(Comparator
                        .comparingDouble((Player p) -> scores.get(p.getId())).reversed()
                        .thenComparing(Comparator.comparingDouble(
                                (Player p) -> directEncounterScore(p.getId(), scores)).reversed())
                        .thenComparing(Comparator.comparingDouble((Player p) -> buchholz.get(p.getId())).reversed())
                        .thenComparing(Comparator.comparingDouble(
                                (Player p) -> medianBuchholz.get(p.getId())).reversed())
                        .thenComparing(Comparator.comparingDouble(
                                (Player p) -> sonnebornBerger.get(p.getId())).reversed())
                        .thenComparing(Comparator.comparingInt(
                                (Player p) -> wins.get(p.getId())).reversed())
                        .thenComparing(Comparator.comparingInt(Player::getRating).reversed())
                        .thenComparing(Player::getName))
                .map(player -> new PlayerStanding(
                        0,
                        player.getId(),
                        player.getName(),
                        player.getRating(),
                        scores.get(player.getId()),
                        buchholz.get(player.getId()),
                        medianBuchholz.get(player.getId()),
                        sonnebornBerger.get(player.getId()),
                        wins.get(player.getId()),
                        player.isActive()))
                .toList();
        List<PlayerStanding> ranked = new ArrayList<>(standings.size());
        for (int i = 0; i < standings.size(); i++) {
            ranked.add(standings.get(i).withRank(i + 1));
        }
        return ranked;
    }

    private int winsOf(UUID playerId) {
        return (int) rounds.stream()
                .flatMap(round -> round.getPairings().stream())
                .filter(p -> p.pointsFor(playerId) == 1.0 && !p.isBye())
                .count();
    }

    /**
     * Per-round opponent values for the Buchholz family of tie-breaks.
     * Unplayed games (byes and forfeits) are replaced by a FIDE virtual
     * opponent: same score as the player before the round, the complement
     * of the player's result in that round, and a draw in every round
     * played since.
     */
    private List<double[]> tieBreakContributions(UUID playerId, Map<UUID, Double> scores) {
        int roundsSoFar = rounds.size();
        List<double[]> contributions = new ArrayList<>();
        for (Round round : rounds) {
            for (Pairing pairing : round.getPairings()) {
                if (!pairing.involves(playerId) || pairing.getResult() == GameResult.PENDING) {
                    continue;
                }
                double points = pairing.pointsFor(playerId);
                double opponentScore;
                if (pairing.isPlayed()) {
                    opponentScore = scores.getOrDefault(pairing.opponentOf(playerId), 0.0);
                } else {
                    opponentScore = scoreBeforeRound(playerId, round.getNumber())
                            + (1.0 - points)
                            + 0.5 * (roundsSoFar - round.getNumber());
                }
                contributions.add(new double[]{points, opponentScore});
            }
        }
        return contributions;
    }

    private double buchholz(UUID playerId, Map<UUID, Double> scores) {
        return tieBreakContributions(playerId, scores).stream()
                .mapToDouble(contribution -> contribution[1])
                .sum();
    }

    private double medianBuchholz(UUID playerId, Map<UUID, Double> scores) {
        List<Double> opponentScores = tieBreakContributions(playerId, scores).stream()
                .map(contribution -> contribution[1])
                .sorted()
                .toList();
        if (opponentScores.isEmpty()) {
            return 0.0;
        }
        if (opponentScores.size() <= 2) {
            return opponentScores.stream().mapToDouble(Double::doubleValue).sum();
        }
        return opponentScores.stream()
                .skip(1)
                .limit(opponentScores.size() - 2L)
                .mapToDouble(Double::doubleValue)
                .sum();
    }

    private double sonnebornBerger(UUID playerId, Map<UUID, Double> scores) {
        return tieBreakContributions(playerId, scores).stream()
                .mapToDouble(contribution -> contribution[0] * contribution[1])
                .sum();
    }

    private double directEncounterScore(UUID playerId, Map<UUID, Double> scores) {
        double myScore = scores.get(playerId);
        Map<UUID, Double> filtered = scores.entrySet().stream()
                .filter(e -> Math.abs(e.getValue() - myScore) < 0.001)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        if (filtered.size() <= 1) {
            return myScore;
        }
        double encounter = 0.0;
        for (Round round : rounds) {
            for (Pairing pairing : round.getPairings()) {
                if (!pairing.involves(playerId) || pairing.isBye()) {
                    continue;
                }
                UUID opponentId = pairing.opponentOf(playerId);
                if (filtered.containsKey(opponentId)) {
                    encounter += pairing.pointsFor(playerId);
                }
            }
        }
        return encounter;
    }

    private double scoreBeforeRound(UUID playerId, int roundNumber) {
        return rounds.stream()
                .filter(r -> r.getNumber() < roundNumber)
                .flatMap(r -> r.getPairings().stream())
                .mapToDouble(p -> p.pointsFor(playerId))
                .sum();
    }

    private List<PairingCandidate> buildCandidates() {
        Map<UUID, Player> byId = players.stream()
                .collect(Collectors.toMap(Player::getId, Function.identity()));
        int lastRoundNumber = rounds.isEmpty() ? 0 : rounds.getLast().getNumber();
        return players.stream()
                .filter(Player::isActive)
                .map(player -> {
                    UUID playerId = player.getId();
                    Set<UUID> opponents = new HashSet<>();
                    int whiteGames = 0;
                    int blackGames = 0;
                    boolean hadBye = false;
                    boolean forfeitWin = false;
                    int lastColor = PairingCandidate.NONE;
                    int previousColor = PairingCandidate.NONE;
                    int downFloats = 0;
                    int upFloats = 0;
                    boolean floatedDownLastRound = false;
                    boolean floatedUpLastRound = false;
                    for (Round round : rounds) {
                        boolean isLastRound = round.getNumber() == lastRoundNumber;
                        for (Pairing pairing : round.getPairings()) {
                            if (!pairing.involves(playerId)) {
                                continue;
                            }
                            if (pairing.isBye()) {
                                hadBye = true;
                                if (isLastRound) {
                                    floatedDownLastRound = true;
                                }
                                continue;
                            }
                            // Being paired counts as having met, and as a
                            // float across score groups, even if the game
                            // was later forfeited.
                            UUID opponentId = pairing.opponentOf(playerId);
                            opponents.add(opponentId);
                            double myScoreBefore = scoreBeforeRound(playerId, round.getNumber());
                            double opponentScoreBefore = scoreBeforeRound(opponentId, round.getNumber());
                            if (myScoreBefore > opponentScoreBefore + 0.01) {
                                downFloats++;
                                if (isLastRound) {
                                    floatedDownLastRound = true;
                                }
                            } else if (opponentScoreBefore > myScoreBefore + 0.01) {
                                upFloats++;
                                if (isLastRound) {
                                    floatedUpLastRound = true;
                                }
                            }
                            if (pairing.wonByForfeit(playerId)) {
                                forfeitWin = true;
                            }
                            if (!pairing.isPlayed()) {
                                // Forfeited games leave the color history untouched.
                                continue;
                            }
                            previousColor = lastColor;
                            if (pairing.getWhitePlayerId().equals(playerId)) {
                                whiteGames++;
                                lastColor = PairingCandidate.WHITE;
                            } else {
                                blackGames++;
                                lastColor = PairingCandidate.BLACK;
                            }
                        }
                    }
                    return new PairingCandidate(playerId, byId.get(playerId).getRating(),
                            scoreOf(playerId), opponents, whiteGames, blackGames, hadBye, forfeitWin,
                            lastColor, previousColor, downFloats, upFloats,
                            floatedDownLastRound, floatedUpLastRound);
                })
                .toList();
    }

    public Optional<Round> currentRound() {
        return rounds.isEmpty() ? Optional.empty() : Optional.of(rounds.getLast());
    }

    public Optional<Round> roundByNumber(int number) {
        return rounds.stream().filter(round -> round.getNumber() == number).findFirst();
    }

    public Optional<Player> playerById(UUID playerId) {
        return players.stream().filter(player -> player.getId().equals(playerId)).findFirst();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getTotalRounds() {
        return totalRounds;
    }

    public TournamentStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<Player> getPlayers() {
        return List.copyOf(players);
    }

    public List<Round> getRounds() {
        return List.copyOf(rounds);
    }
}
