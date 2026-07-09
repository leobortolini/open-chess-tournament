package com.open.chess.tournament.application.dto;

import com.open.chess.tournament.domain.model.Player;

import java.util.UUID;

public record PlayerDto(UUID id, String name, int rating, boolean active) {

    public static PlayerDto from(Player player) {
        return new PlayerDto(player.getId(), player.getName(), player.getRating(), player.isActive());
    }
}
