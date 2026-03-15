package com.gaiaproject.domain.entity.federation;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "game_federation_group")
public class GameFederationGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "player_id", nullable = false)
    private UUID playerId;

    @Column(name = "federation_tile_code", nullable = false, length = 50)
    private String federationTileCode;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Builder
    public GameFederationGroup(UUID gameId, UUID playerId, String federationTileCode) {
        this.gameId = gameId;
        this.playerId = playerId;
        this.federationTileCode = federationTileCode;
        this.createdAt = LocalDateTime.now();
    }
}
