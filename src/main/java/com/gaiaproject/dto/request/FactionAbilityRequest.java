package com.gaiaproject.dto.request;

import java.util.UUID;

/**
 * 종족 고유 능력 액션 요청
 * - abilityCode: FIRAKS_DOWNGRADE 등
 * - trackCode: 기술 트랙 코드 (FIRAKS_DOWNGRADE 시 전진할 트랙)
 * - hexQ, hexR: 대상 건물 좌표 (FIRAKS_DOWNGRADE 시 다운그레이드할 RL 위치)
 */
public record FactionAbilityRequest(
        UUID playerId,
        String abilityCode,
        String trackCode,
        Integer hexQ,
        Integer hexR,
        Integer qicUsed
) {}
