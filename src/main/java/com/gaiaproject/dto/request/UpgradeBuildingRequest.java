package com.gaiaproject.dto.request;

import java.util.UUID;

public record UpgradeBuildingRequest(
        UUID playerId,
        int hexQ,
        int hexR,
        String targetBuildingType,  // "TRADING_STATION", "RESEARCH_LAB", "PLANETARY_INSTITUTE", "ACADEMY"
        String techTileCode,        // (선택) 교역소/아카데미 건설 시 획득할 기술 타일 코드
        String techTrackCode,       // (선택) COMMON 타일일 때 플레이어가 선택한 트랙
        String academyType,         // (선택) "KNOWLEDGE" 또는 "QIC" — 아카데미 건설 시 필수
        String coveredTileCode,     // (선택) 고급 타일 획득 시 덮을 기본 타일 코드
        Integer lostPlanetHexQ,     // (선택) 거리 5단계 진입 시 검은행성 배치 좌표
        Integer lostPlanetHexR      // (선택) 거리 5단계 진입 시 검은행성 배치 좌표
) {}
