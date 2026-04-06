package com.gaiaproject.dto.request;

import java.util.List;
import java.util.UUID;

public record FormFederationRequest(
        UUID playerId,
        String federationTileCode,       // 선택한 연방 타일 코드
        List<int[]> buildingHexes,       // 연방에 포함할 건물 좌표 [[q,r], ...]
        List<int[]> tokenHexes,          // 빈 헥스에 놓을 파워 토큰 좌표 [[q,r], ...]
        String techTileCode,             // 획득할 기술 타일 코드 (nullable)
        String techTrackCode,            // 기술 타일 배치 트랙 코드 (nullable)
        String coveredTileCode           // 고급 타일 획득 시 덮을 기본 타일 코드 (nullable)
) {}
