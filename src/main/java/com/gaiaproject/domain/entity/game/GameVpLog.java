package com.gaiaproject.domain.entity.game;

import com.gaiaproject.domain.enumtype.action.VpCategory;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "game_vp_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class GameVpLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "player_id", nullable = false)
    private UUID playerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 50)
    private VpCategory category;

    @Column(name = "amount", nullable = false)
    private int amount;

    @Column(name = "round_number")
    private Integer roundNumber;

    @Column(name = "description", length = 200)
    private String description;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
