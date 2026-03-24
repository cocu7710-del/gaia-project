package com.gaiaproject.dto.response;

import java.util.List;

/**
 * 기술 트랙 전체 정보 응답 DTO
 */
public record TechTrackResponse(
        List<TechTrackInfo> tracks,
        List<TechTileInfo> basicTiles,
        List<AdvancedTechTileInfo> advancedTiles
) {
    /**
     * 단일 기술 트랙 정보
     */
    public record TechTrackInfo(
            String trackCode,       // TERRA_FORMING, NAVIGATION, AI, GAIA_FORMING, ECONOMY, SCIENCE
            String trackNameKo,     // 테라포밍, 항해, AI, 가이아포밍, 경제, 과학
            int position,           // 트랙 위치 (0~5)
            List<TrackLevelInfo> levels  // 각 레벨별 보상/효과
    ) {}

    /**
     * 트랙 레벨 정보
     */
    public record TrackLevelInfo(
            int level,              // 0~5
            String description,     // 레벨 효과 설명
            boolean hasFederationToken  // 연방 토큰 위치 여부 (레벨 5)
    ) {}

    /**
     * 기본 기술 타일 정보
     */
    public record TechTileInfo(
            String tileCode,        // BASIC_TILE_1 ~ BASIC_TILE_9
            String trackCode,       // 배치된 트랙
            int position,           // 배치 위치 (1~12)
            String abilityType,     // ACTION, INCOME, IMMEDIATE, PASSIVE
            String description,     // 능력 설명
            boolean isTaken,        // 플레이어가 가져갔는지 (고급 타일용)
            String takenByPlayerId, // 고급 타일: 가져간 플레이어 UUID
            boolean isActionUsed,   // 이번 라운드 ACTION 사용 여부
            List<String> ownerPlayerIds // 기본 타일: 보유 플레이어 UUID 목록
    ) {}

    /**
     * 고급 기술 타일 정보
     */
    public record AdvancedTechTileInfo(
            String tileCode,        // ADV_TILE_1 ~ ADV_TILE_18
            String trackCode,       // 배치된 트랙
            int position,           // 배치 위치 (1~6)
            String abilityType,     // ACTION, IMMEDIATE, PASSIVE
            String description,     // 능력 설명
            boolean isTaken,        // 플레이어가 가져갔는지
            String takenByPlayerId, // 가져간 플레이어 UUID (null이면 미획득)
            boolean isActionUsed    // 이번 라운드 ACTION 사용 여부
    ) {}
}
