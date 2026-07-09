package com.open.chess.tournament.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "pairings")
public class Pairing {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "round_id")
    private Round round;

    @Column(name = "board_number", nullable = false)
    private int boardNumber;

    @Column(name = "white_player_id", nullable = false)
    private UUID whitePlayerId;

    @Column(name = "black_player_id")
    private UUID blackPlayerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GameResult result;

    protected Pairing() {
    }

    Pairing(Round round, int boardNumber, UUID whitePlayerId, UUID blackPlayerId) {
        this.id = UUID.randomUUID();
        this.round = round;
        this.boardNumber = boardNumber;
        this.whitePlayerId = whitePlayerId;
        this.blackPlayerId = blackPlayerId;
        this.result = blackPlayerId == null ? GameResult.BYE : GameResult.PENDING;
    }

    public boolean isBye() {
        return blackPlayerId == null;
    }

    public boolean involves(UUID playerId) {
        return whitePlayerId.equals(playerId) || playerId.equals(blackPlayerId);
    }

    void setResult(GameResult result) {
        this.result = result;
    }

    public double pointsFor(UUID playerId) {
        if (!involves(playerId)) {
            return 0.0;
        }
        return switch (result) {
            case BYE -> 1.0;
            case DRAW -> 0.5;
            case WHITE_WINS -> whitePlayerId.equals(playerId) ? 1.0 : 0.0;
            case BLACK_WINS -> playerId.equals(blackPlayerId) ? 1.0 : 0.0;
            case PENDING -> 0.0;
        };
    }

    public UUID opponentOf(UUID playerId) {
        if (whitePlayerId.equals(playerId)) {
            return blackPlayerId;
        }
        if (playerId.equals(blackPlayerId)) {
            return whitePlayerId;
        }
        return null;
    }

    public UUID getId() {
        return id;
    }

    public int getBoardNumber() {
        return boardNumber;
    }

    public UUID getWhitePlayerId() {
        return whitePlayerId;
    }

    public UUID getBlackPlayerId() {
        return blackPlayerId;
    }

    public GameResult getResult() {
        return result;
    }
}
