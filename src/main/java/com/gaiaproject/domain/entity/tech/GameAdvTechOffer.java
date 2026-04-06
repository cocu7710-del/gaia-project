package com.gaiaproject.domain.entity.tech;

import com.gaiaproject.domain.enumtype.tech.AdvancedTechTileCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "game_adv_tech_offer")
public class GameAdvTechOffer {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "position", nullable = false)
    private Integer position;

    @Column(name = "tech_track", nullable = false)
    private String techTrack;

    @Enumerated(EnumType.STRING)
    @Column(name = "adv_tech_tile_code", nullable = false, length = 50)
    private AdvancedTechTileCode advTechTileCode;

    @Column(name = "taken_by_player_id")
    private UUID takenByPlayerId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Builder
    public GameAdvTechOffer(UUID gameId, Integer position, String techTrack, AdvancedTechTileCode advTechTileCode) {
        this.gameId = gameId;
        this.position = position;
        this.techTrack = techTrack;
        this.advTechTileCode = advTechTileCode;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /** 고급 타일 점유 처리 */
    public void take(UUID playerId) {
        this.takenByPlayerId = playerId;
        this.updatedAt = LocalDateTime.now();
    }
}