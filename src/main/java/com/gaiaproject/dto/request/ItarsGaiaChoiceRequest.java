package com.gaiaproject.dto.request;

import java.util.UUID;

public record ItarsGaiaChoiceRequest(
        UUID playerId,
        String action,          // "TAKE_TILE" | "SKIP"
        String tileCode,        // 기술 타일 코드 (TAKE_TILE일 때)
        String techTrackCode    // COMMON 타일의 트랙 (nullable)
) {}
