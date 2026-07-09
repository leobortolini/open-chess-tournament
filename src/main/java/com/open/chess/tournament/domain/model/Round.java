package com.open.chess.tournament.domain.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "rounds")
public class Round {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tournament_id")
    private Tournament tournament;

    @Column(name = "round_number", nullable = false)
    private int number;

    @OneToMany(mappedBy = "round", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("boardNumber")
    private List<Pairing> pairings = new ArrayList<>();

    protected Round() {
    }

    Round(Tournament tournament, int number) {
        this.id = UUID.randomUUID();
        this.tournament = tournament;
        this.number = number;
    }

    Pairing addPairing(int boardNumber, UUID whitePlayerId, UUID blackPlayerId) {
        Pairing pairing = new Pairing(this, boardNumber, whitePlayerId, blackPlayerId);
        pairings.add(pairing);
        return pairing;
    }

    public boolean isComplete() {
        return pairings.stream().allMatch(p -> p.getResult().isDecided());
    }

    public UUID getId() {
        return id;
    }

    public int getNumber() {
        return number;
    }

    public List<Pairing> getPairings() {
        return List.copyOf(pairings);
    }
}
