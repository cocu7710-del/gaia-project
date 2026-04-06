package com.gaiaproject.domain.entity.game;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "game_bid")
@Getter @Setter
@NoArgsConstructor
public class GameBid {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "game_id", nullable = false, updatable = false)
    private UUID gameId;

    @Column(name = "player_id", nullable = false, updatable = false)
    private UUID playerId;

    @Column(name = "bid_round", nullable = false)
    private int bidRound = 1;

    @Column(name = "bid_amount", nullable = false)
    private int bidAmount = 0;

    @Column(name = "is_passed", nullable = false)
    private boolean isPassed = false;

    @Column(name = "pick_order")
    private Integer pickOrder;

    @Column(name = "seat_no")
    private Integer seatNo;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public static GameBid create(UUID gameId, UUID playerId) {
        GameBid bid = new GameBid();
        bid.gameId = gameId;
        bid.playerId = playerId;
        bid.createdAt = LocalDateTime.now();
        return bid;
    }
}
