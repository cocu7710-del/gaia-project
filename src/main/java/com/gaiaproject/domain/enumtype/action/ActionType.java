package com.gaiaproject.domain.enumtype.action;

/**
 * 게임 액션 타입
 */
public enum ActionType {
    /** 광산 배치 */
    PLACE_MINE,

    /** 건물 업그레이드 */
    UPGRADE_BUILDING,

    /** 파워 액션 */
    POWER_ACTION,

    /** 함대 액션 */
    FLEET_ACTION,

    /** 지식 트랙 전진 (지식 4 소모) */
    ADVANCE_TECH,

    /** 패스 (다음 라운드 부스터 선택 포함) */
    PASS,

    /** 가이아 포머 배치 (차원변형 행성에 포머 던지기) */
    DEPLOY_GAIAFORMER,

    /** 함대 선박 특수 액션 */
    FLEET_SHIP_ACTION,

    /** 종족 고유 능력 액션 (파이락 다운그레이드 등) */
    FACTION_ABILITY,

    /** 파워 수입 완료 마커 */
    POWER_INCOME,

    /** 부스터 액션 사용 */
    BOOSTER_ACTION,

    /** 연방 형성 */
    FORM_FEDERATION,

    /** 기술 타일 액션 사용 */
    TECH_TILE_ACTION,

    /** QIC 아카데미 액션 */
    QIC_ACADEMY_ACTION
}
