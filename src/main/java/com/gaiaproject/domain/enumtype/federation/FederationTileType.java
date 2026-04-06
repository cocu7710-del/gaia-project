package com.gaiaproject.domain.enumtype.federation;

import com.gaiaproject.dto.ResourcesVo;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 연방 타일 (Federation Token)
 */
@Getter
public enum FederationTileType {

    /** 즉시: 지식 2, VP 6 */
    FED_TILE_1(
            new ResourcesVo(0, 0, 2, 0, 0, 0, 0, 0, 6, null),
            true,
            FederationActionType.NONE
    ),

    /** 즉시: 크레딧 6, VP 7 */
    FED_TILE_2(
            new ResourcesVo(6, 0, 0, 0, 0, 0, 0, 0, 7, null),
            true,
            FederationActionType.NONE
    ),

    /** 즉시: VP 12 (비활성 연방토큰 — 획득 즉시 used 처리) */
    FED_TILE_3(
            new ResourcesVo(0, 0, 0, 0, 0, 0, 0, 0, 12, null),
            false,
            FederationActionType.NONE
    ),

    /** 즉시: VP 8, QIC 1 */
    FED_TILE_4(
            new ResourcesVo(0, 0, 0, 1, 0, 0, 0, 0, 8, null),
            true,
            FederationActionType.NONE
    ),

    /** 즉시: VP 7, 광석 2 */
    FED_TILE_5(
            new ResourcesVo(0, 2, 0, 0, 0, 0, 0, 0, 7, null),
            true,
            FederationActionType.NONE
    ),

    /** 즉시: VP 8, 파워 토큰 2 */
    FED_TILE_6(
            new ResourcesVo(0, 0, 0, 0, 2, 0, 0, 0, 8, null),
            true,
            FederationActionType.NONE
    ),

    /** 즉시: 기본 기술타일 1개 가져오기 (자원 보상 없음, 기술 타일 획득은 별도 처리) */
    FED_EXP_TILE_1(
            new ResourcesVo(0, 0, 0, 0, 0, 0, 0, 0, 0, null),
            true,
            FederationActionType.GAIN_BASIC_TECH_TILE
    ),

    /** 즉시: VP 4, 지식 4 */
    FED_EXP_TILE_2(
            new ResourcesVo(0, 0, 4, 0, 0, 0, 0, 0, 4, null),
            true,
            FederationActionType.NONE
    ),

    /** 즉시: VP 8, 크레딧 8 */
    FED_EXP_TILE_3(
            new ResourcesVo(8, 0, 0, 0, 0, 0, 0, 0, 8, null),
            true,
            FederationActionType.NONE
    ),

    /** 즉시: VP 4, 광석 2, QIC 1 */
    FED_EXP_TILE_4(
            new ResourcesVo(0, 2, 0, 1, 0, 0, 0, 0, 4, null),
            true,
            FederationActionType.NONE
    ),

    /** 즉시: 3 테라포밍 + 무료 광산 */
    FED_EXP_TILE_5(
            new ResourcesVo(0, 0, 0, 0, 0, 0, 0, 0, 0, null),
            true,
            FederationActionType.TERRAFORM_3_PLACE_MINE
    ),

    /** 즉시: VP 12 */
    FED_EXP_TILE_6(
            new ResourcesVo(0, 0, 0, 0, 0, 0, 0, 0, 12, null),
            true,
            FederationActionType.NONE
    ),

    /** 즉시: 사거리 제한 없이 무료 광산 */
    FED_EXP_TILE_7(
            new ResourcesVo(0, 0, 0, 0, 0, 0, 0, 0, 0, null),
            true,
            FederationActionType.PLACE_MINE_NO_RANGE_LIMIT
    ),

    /** 글린 전용 연방 토큰: 즉시 크레딧 2, 광석 1, 지식 1 (사용 가능) */
    GLEENS_FEDERATION(
            new ResourcesVo(2, 1, 1, 0, 0, 0, 0, 0, 0, null),
            true,
            FederationActionType.NONE
    ),

    /** 즉시: VP 7, 파워 토큰 2 (3구역에 추가) */
    FED_EXP_TILE_8(
            new ResourcesVo(0, 0, 0, 0, 0, 0, 2, 0, 7, null),
            true,
            FederationActionType.POWER_TOKEN_TO_BOWL_3
    );

    private final ResourcesVo immediateReward;         // 즉시 자원 효과
    private final boolean useFederation;               // 연방 토큰 사용 가능 여부
    private final FederationActionType specialAction;  // 특수 액션

    FederationTileType(ResourcesVo immediateReward, boolean useFederation, FederationActionType specialAction) {
        this.immediateReward = immediateReward;
        this.useFederation = useFederation;
        this.specialAction = specialAction;
    }

    public boolean hasSpecialAction() {
        return specialAction != FederationActionType.NONE;
    }

    /**
     * 기본 연방 타일 6개 (랜덤 셔플)
     */
    public static List<FederationTileType> getBasicTiles() {
        List<FederationTileType> basic = new ArrayList<>(List.of(
                FED_TILE_1, FED_TILE_2, FED_TILE_3,
                FED_TILE_4, FED_TILE_5, FED_TILE_6
        ));
        Collections.shuffle(basic);
        return basic;
    }

    /**
     * 확장팩 연방 타일 목록 (8개)
     */
    public static List<FederationTileType> getExpansionTiles() {
        return List.of(FED_EXP_TILE_1, FED_EXP_TILE_2, FED_EXP_TILE_3,
                FED_EXP_TILE_4, FED_EXP_TILE_5, FED_EXP_TILE_6,
                FED_EXP_TILE_7, FED_EXP_TILE_8);
    }

    /**
     * 확장팩 타일 중 4개 랜덤 선택
     */
    public static List<FederationTileType> getRandomExpansionFour() {
        List<FederationTileType> expansion = new ArrayList<>(getExpansionTiles());
        Collections.shuffle(expansion);
        return expansion.subList(0, 4);
    }
}