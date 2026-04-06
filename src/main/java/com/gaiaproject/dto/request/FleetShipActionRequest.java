package com.gaiaproject.dto.request;

import java.util.UUID;

public record FleetShipActionRequest(
        UUID playerId,
        String actionCode,
        Integer hexQ,           // 광산 배치 / 건물 업그레이드 좌표 (nullable)
        Integer hexR,
        String trackCode,       // 기술 타일 코드 또는 트랙 코드 (nullable)
        String techTrackCode,   // REBELLION_TECH: 타일 획득 시 전진할 트랙 코드 (nullable)
        String coveredTileCode, // 고급 타일 획득 시 덮을 기본 타일 코드 (nullable)
        Integer qicUsed,        // 거리 확장에 사용한 QIC 수 (nullable)
        Boolean splitAction,    // true: 후속 광산 건설이 있어 턴을 넘기지 않음 (nullable)
        String federationTileCode // TWILIGHT_FED: 재사용할 연방 토큰 코드 (nullable)
) {}
