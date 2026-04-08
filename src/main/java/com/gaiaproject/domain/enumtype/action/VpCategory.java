package com.gaiaproject.domain.enumtype.action;

public enum VpCategory {
    BASE,                  // 기본 시작 점수 (10VP)
    ROUND_SCORING,         // 라운드 미션 점수
    BOOSTER_PASS,          // 라운드 부스터 패스 점수
    TECH_TILE,             // 기술타일 점수 (즉시 7VP, 가이아당 3VP 등)
    ADV_TECH_TILE,         // 고급 기술타일 점수 (즉시/패스)
    FEDERATION_TOKEN,      // 연방 토큰 점수
    FLEET,                 // 함대 VP 액션 점수
    ARTIFACT,              // 인공물 점수
    KNOWLEDGE_TRACK,       // 지식트랙 점수 (3단계 이상 1칸당 4VP)
    FINAL_SCORING,         // 최종 미션 점수
    REMAINING_RESOURCES,   // 남은 자원 점수 (3자원당 1VP)
    LEECH_COST,            // 파워 리치 비용 (마이너스)
    FLEET_ENTRY,           // 우주선 입장 비용 (마이너스)
    BIDDING,               // 비딩 패널티 (마이너스)
    OTHER,                 // 기타 (건물 보너스 등)
}
