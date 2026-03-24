package com.gaiaproject.domain.entity.map;

import com.gaiaproject.domain.enumtype.player.PlanetType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

/**
 * 게임 헥스 정보 (글로벌 좌표)
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "game_hex")
@IdClass(GameHex.GameHexId.class)
public class GameHex {

    @Id
    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Id
    @Column(name = "hex_q", nullable = false)
    private Integer hexQ;

    @Id
    @Column(name = "hex_r", nullable = false)
    private Integer hexR;

    @Enumerated(EnumType.STRING)
    @Column(name = "planet_type", nullable = false, length = 30)
    private PlanetType planetType;

    @Column(name = "sector_id", length = 30)
    private String sectorId;

    @Column(name = "position_no")
    private Integer positionNo;

    /**
     * 복합 키 클래스
     */
    @Getter
    @NoArgsConstructor
    public static class GameHexId implements Serializable {
        private UUID gameId;
        private Integer hexQ;
        private Integer hexR;

        public GameHexId(UUID gameId, Integer hexQ, Integer hexR) {
            this.gameId = gameId;
            this.hexQ = hexQ;
            this.hexR = hexR;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            GameHexId that = (GameHexId) o;
            return gameId.equals(that.gameId) &&
                   hexQ.equals(that.hexQ) &&
                   hexR.equals(that.hexR);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(gameId, hexQ, hexR);
        }
    }

    /** 차원변형 → 가이아 행성으로 변환 (가이아 포밍 완료 시) */
    public void convertToGaia() {
        this.planetType = PlanetType.GAIA;
    }

    /** 행성 타입 변경 (검은행성 배치 등) */
    public void setPlanetType(PlanetType planetType) {
        this.planetType = planetType;
    }

    /**
     * 헥스 생성
     */
    public static GameHex create(UUID gameId, int hexQ, int hexR, PlanetType planetType,
                                  String sectorId, Integer positionNo) {
        GameHex hex = new GameHex();
        hex.gameId = gameId;
        hex.hexQ = hexQ;
        hex.hexR = hexR;
        hex.planetType = planetType;
        hex.sectorId = sectorId;
        hex.positionNo = positionNo;
        return hex;
    }
}
