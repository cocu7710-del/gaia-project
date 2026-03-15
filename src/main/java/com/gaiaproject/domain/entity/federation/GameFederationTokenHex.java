package com.gaiaproject.domain.entity.federation;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "game_federation_token_hex")
public class GameFederationTokenHex {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "federation_group_id", nullable = false)
    private UUID federationGroupId;

    @Column(name = "hex_q", nullable = false)
    private int hexQ;

    @Column(name = "hex_r", nullable = false)
    private int hexR;

    @Builder
    public GameFederationTokenHex(UUID federationGroupId, int hexQ, int hexR) {
        this.federationGroupId = federationGroupId;
        this.hexQ = hexQ;
        this.hexR = hexR;
    }
}
