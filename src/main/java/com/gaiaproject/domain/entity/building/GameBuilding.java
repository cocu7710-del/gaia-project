package com.gaiaproject.domain.entity.building;

import com.gaiaproject.domain.enumtype.building.AcademyType;
import com.gaiaproject.domain.enumtype.building.BuildingType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * 게임 내 건물 배치 정보
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "game_building")
public class GameBuilding {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "player_id", nullable = false)
    private UUID playerId;

    @Column(name = "hex_q", nullable = false)
    private Integer hexQ;

    @Column(name = "hex_r", nullable = false)
    private Integer hexR;

    @Enumerated(EnumType.STRING)
    @Column(name = "building_type", nullable = false, length = 30)
    private BuildingType buildingType;

    // 아카데미 종류 (KNOWLEDGE / QIC, 아카데미가 아닌 건물은 null)
    @Enumerated(EnumType.STRING)
    @Column(name = "academy_type", length = 20)
    private AcademyType academyType;

    // 란티다 전용: 타인 건물 위치에 지은 광산 (업그레이드 불가)
    @Column(name = "is_lantids_mine", nullable = false)
    @com.fasterxml.jackson.annotation.JsonProperty("isLantidsMine")
    private boolean isLantidsMine = false;

    /**
     * 건물 배치 생성
     */
    public static GameBuilding place(UUID gameId, UUID playerId, int hexQ, int hexR, BuildingType buildingType) {
        GameBuilding building = new GameBuilding();
        building.gameId = gameId;
        building.playerId = playerId;
        building.hexQ = hexQ;
        building.hexR = hexR;
        building.buildingType = buildingType;
        return building;
    }

    /** 엠바스 PI: 건물 위치 교환 */
    public void setPosition(int newQ, int newR) {
        this.hexQ = newQ;
        this.hexR = newR;
    }

    /** 란티다 광산으로 표시 */
    public void markAsLantidsMine() {
        this.isLantidsMine = true;
    }

    /**
     * 건물 업그레이드
     */
    public void upgrade(BuildingType newType) {
        this.buildingType = newType;
    }

    /**
     * 아카데미 업그레이드 (종류 지정)
     */
    public void upgradeToAcademy(AcademyType academyType) {
        this.buildingType = BuildingType.ACADEMY;
        this.academyType = academyType;
    }
}
