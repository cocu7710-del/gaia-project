package com.gaiaproject.dto.response;

import java.util.UUID;

/**
 * 플레이어 상태 응답 DTO
 */
public record PlayerStateResponse(
        UUID playerId,
        Integer seatNo,
        String factionCode,

        // 자원
        Integer credit,
        Integer ore,
        Integer knowledge,
        Integer qic,

        // 파워
        Integer powerBowl1,
        Integer powerBowl2,
        Integer powerBowl3,

        // VP
        Integer victoryPoints,

        // 건물 재고
        Integer stockMine,
        Integer stockTradingStation,
        Integer stockResearchLab,
        Integer stockPlanetaryInstitute,
        Integer stockAcademy,
        Integer stockGaiaformer,

        // 기술 트랙
        Integer techTerraforming,
        Integer techNavigation,
        Integer techAi,
        Integer techGaia,
        Integer techEconomy,
        Integer techScience,

        // 가이아 파워
        Integer gaiaPower,

        // 타클론 전용: 브레인스톤 위치 (1, 2, 3 = 파워볼, null = 없음)
        Integer brainstoneBowl,

        // 부스터 액션 사용 여부
        Boolean boosterActionUsed,

        // 종족 고유 능력 사용 여부 (라운드당 1회)
        Boolean factionAbilityUsed,

        // 발타크 전용: 이번 라운드 변환된 가이아포머 수
        Integer baltaksConvertedGaiaformers,

        // 소행성 광산 건설로 영구 제거된 가이아포머 수
        Integer permanentlyRemovedGaiaformers,

        // QIC 아카데미 보유 여부 (QIC 획득 액션 가능)
        Boolean hasQicAcademy,

        // QIC 아카데미 액션 사용 여부 (라운드당 1회)
        Boolean qicAcademyActionUsed,

        // 팅커로이드: 현재 라운드 선택된 액션 코드
        String tinkeroidsCurrentAction
) {}
