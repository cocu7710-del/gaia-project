package com.gaiaproject.dto;

public record PowerIncomeItemVo(
        String id,           // 고유 식별자 (예: "BOOSTER_CHARGE_4", "PI_TOKEN_2")
        String source,       // 소스 카테고리 (BOOSTER, PI, TECH_TILE, ECONOMY_TRACK, BUILDING)
        String label,        // 표시 텍스트
        int powerCharge,     // 파워 순환량
        int powerBowl1       // 토큰 추가량 (bowl1)
) {}
