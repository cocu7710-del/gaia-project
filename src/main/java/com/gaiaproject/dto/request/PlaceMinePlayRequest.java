package com.gaiaproject.dto.request;

import java.util.UUID;

public record PlaceMinePlayRequest(
        UUID playerId,
        int hexQ,
        int hexR,
        int qicUsed,            // 항법 거리 확장에 사용한 Qic 수
        boolean gaiaformerUsed, // 소행성(비홈) 건설 시 가이아포머 제거 여부
        int terraformDiscount,  // 파워액션/부스터액션으로 받은 테라포밍 단계 할인
        boolean freeMine        // 연방 특수 타일(FED_EXP_TILE_5/7)로 무료 광산 건설
) {
    /** freeMine 없는 기존 호출 호환 */
    public PlaceMinePlayRequest(UUID playerId, int hexQ, int hexR, int qicUsed, boolean gaiaformerUsed, int terraformDiscount) {
        this(playerId, hexQ, hexR, qicUsed, gaiaformerUsed, terraformDiscount, false);
    }
}
