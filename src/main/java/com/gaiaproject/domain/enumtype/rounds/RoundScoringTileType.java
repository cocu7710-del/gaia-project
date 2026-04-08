package com.gaiaproject.domain.enumtype.rounds;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 라운드 점수 타일
 */
@Getter
public enum RoundScoringTileType {

    // 라운드 점수 타일 (10개 중 6개 랜덤)
    ROUND_TILE_MINE("광산 건설 시 2VP"),
    ROUND_TILE_TRADING_STATION_3("교역소 건설 시 3VP"),
    ROUND_TILE_TRADING_STATION_4("교역소 건설 시 4VP"),
    ROUND_TILE_PLANETARY_INSTITUTE("연구소 건설 시 4VP"),
    ROUND_TILE_ACADEMY("행성 의회 또는 아카데미 건설 시 5VP"),
    ROUND_TILE_GAIA_PLANET_3("가이아 행성 개척 시 3VP"),
    ROUND_TILE_GAIA_PLANET_4("가이아 행성 개척 시 4VP"),
    ROUND_TILE_TERRAFORM("테라포밍 1단계당 2VP"),
    ROUND_TILE_RESEARCH_ADVANCE("연구 트랙 1칸 전진당 2VP"),
    ROUND_TILE_FEDERATION("연방 구성 시 5VP"),
    ROUND_TILE_NEW_SECTOR("새로운 섹터 진출 시 3VP"),  // 일반 또는 깊은 구역
    ROUND_TILE_NEW_PLANET_TYPE("새로운 행성 종류 개척 시 3VP");

    private final String description;

    RoundScoringTileType(String description) {
        this.description = description;
    }

    /**
     * 이벤트 발생 시 이 타일이 부여하는 VP (count=1 기준)
     */
    public int getVpForEvent(RoundScoringEvent event) {
        return switch (this) {
            case ROUND_TILE_MINE                -> event == RoundScoringEvent.MINE_PLACED ? 2 : 0;
            case ROUND_TILE_TRADING_STATION_3   -> event == RoundScoringEvent.TRADING_STATION_BUILT ? 3 : 0;
            case ROUND_TILE_TRADING_STATION_4   -> event == RoundScoringEvent.TRADING_STATION_BUILT ? 4 : 0;
            case ROUND_TILE_PLANETARY_INSTITUTE -> event == RoundScoringEvent.RESEARCH_LAB_BUILT ? 4 : 0;
            case ROUND_TILE_ACADEMY             -> (event == RoundScoringEvent.ACADEMY_BUILT
                                                    || event == RoundScoringEvent.PLANETARY_INSTITUTE_BUILT) ? 5 : 0;
            case ROUND_TILE_GAIA_PLANET_3       -> event == RoundScoringEvent.GAIA_PLANET_COLONIZED ? 3 : 0;
            case ROUND_TILE_GAIA_PLANET_4       -> event == RoundScoringEvent.GAIA_PLANET_COLONIZED ? 4 : 0;
            case ROUND_TILE_TERRAFORM           -> event == RoundScoringEvent.TERRAFORM_STEP ? 2 : 0;
            case ROUND_TILE_RESEARCH_ADVANCE    -> event == RoundScoringEvent.RESEARCH_ADVANCED ? 2 : 0;
            case ROUND_TILE_FEDERATION          -> event == RoundScoringEvent.FEDERATION_FORMED ? 5 : 0;
            case ROUND_TILE_NEW_SECTOR          -> event == RoundScoringEvent.NEW_SECTOR_ENTERED ? 3 : 0;
            case ROUND_TILE_NEW_PLANET_TYPE     -> event == RoundScoringEvent.NEW_PLANET_TYPE_COLONIZED ? 3 : 0;
        };
    }

    /**
     * 라운드용 6개 랜덤 선택
     */
    public static List<RoundScoringTileType> getRandomSix() {
        List<RoundScoringTileType> all = new ArrayList<>(Arrays.asList(values()));
        Collections.shuffle(all);
        return all.subList(0, 6);
    }
}