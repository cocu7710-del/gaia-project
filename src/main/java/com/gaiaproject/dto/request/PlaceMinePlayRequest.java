package com.gaiaproject.dto.request;

import java.util.UUID;

public record PlaceMinePlayRequest(
        UUID playerId,
        int hexQ,
        int hexR,
        int qicUsed,            // 항법 거리 확장에 사용한 Qic 수
        boolean gaiaformerUsed, // 소행성(비홈) 건설 시 가이아포머 제거 여부
        int terraformDiscount,  // 파워액션/부스터액션으로 받은 테라포밍 단계 할인
        boolean freeMine,       // 연방 특수 타일(FED_EXP_TILE_5/7)로 무료 광산 건설
        boolean hasFollowUp     // 후속 액션(검은행성 등)이 있으면 턴 넘기지 않음
) {
    /** 하위 호환 */
    public PlaceMinePlayRequest(UUID playerId, int hexQ, int hexR, int qicUsed, boolean gaiaformerUsed, int terraformDiscount) {
        this(playerId, hexQ, hexR, qicUsed, gaiaformerUsed, terraformDiscount, false, false);
    }
    public PlaceMinePlayRequest(UUID playerId, int hexQ, int hexR, int qicUsed, boolean gaiaformerUsed, int terraformDiscount, boolean freeMine) {
        this(playerId, hexQ, hexR, qicUsed, gaiaformerUsed, terraformDiscount, freeMine, false);
    }
}
