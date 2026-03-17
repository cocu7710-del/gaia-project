package com.gaiaproject.service;

import com.gaiaproject.domain.entity.player.GamePlayerState;
import com.gaiaproject.repository.player.GamePlayerStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 프리 액션: 자원 변환 (턴 소모 없음)
 * - ORE_TO_CREDIT     : 광석 1 → 크레딧 1
 * - ORE_TO_TOKEN      : 광석 1 → 파워토큰 1 (bowl1)
 * - POWER_TO_CREDIT   : 파워 1 → 크레딧 1
 * - POWER_TO_ORE      : 파워 3 → 광석 1
 * - POWER_TO_KNOWLEDGE: 파워 4 → 지식 1
 * - POWER_TO_QIC      : 파워 4 → QIC 1
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class FreeConvertService {

    private final GamePlayerStateRepository playerStateRepository;

    public record FreeConvertResponse(boolean success, String message) {
        static FreeConvertResponse ok() { return new FreeConvertResponse(true, null); }
        static FreeConvertResponse fail(String msg) { return new FreeConvertResponse(false, msg); }
    }

    public FreeConvertResponse convert(UUID gameId, UUID playerId, String convertCode) {
        return convert(gameId, playerId, convertCode, false);
    }

    public FreeConvertResponse convert(UUID gameId, UUID playerId, String convertCode, boolean useBrainstone) {
        GamePlayerState ps = playerStateRepository.findByGameIdAndPlayerId(gameId, playerId)
                .orElseThrow(() -> new IllegalArgumentException("플레이어 상태 없음: " + playerId));

        // 타클론 브레인스톤 플래그 설정
        if (useBrainstone) ps.setUseBrainstone(true);

        try {
            switch (convertCode) {
                case "ORE_TO_CREDIT" -> {
                    ps.spendOre(1);
                    ps.addCredit(1);
                    log.info("[FREE] 광석→크레딧: player={}", playerId);
                }
                case "ORE_TO_TOKEN" -> {
                    ps.spendOre(1);
                    ps.addPowerToken(1);
                    log.info("[FREE] 광석→파워토큰: player={}", playerId);
                }
                case "ORE_TO_POWER3" -> {
                    ps.spendOre(1);
                    ps.gainPower(1);  // bowl3에 직접 추가
                    log.info("[FREE] 광석→3구역파워(XENOS): player={}", playerId);
                }
                case "POWER_TO_CREDIT" -> {
                    boolean isNevlasPi = ps.getFactionType() == com.gaiaproject.domain.enumtype.player.FactionType.NEVLAS
                            && ps.getStockPlanetaryInstitute() == 0;
                    if (useBrainstone) {
                        ps.spendPower(3);
                        ps.addCredit(3);
                        log.info("[FREE] 브레인스톤→3크레딧: player={}", playerId);
                    } else if (isNevlasPi) {
                        // 네블라 PI: 1토큰 = 2파워 = 2크레딧
                        ps.spendPower(1);
                        ps.addCredit(2);
                        log.info("[FREE] 네블라PI 파워1토큰→2크레딧: player={}", playerId);
                    } else {
                        ps.spendPower(1);
                        ps.addCredit(1);
                        log.info("[FREE] 파워1→크레딧: player={}", playerId);
                    }
                }
                case "POWER_TO_ORE" -> {
                    ps.spendPower(3); // 브레인스톤이면 브레인스톤만, 일반이면 3토큰
                    ps.addOre(1);
                    log.info("[FREE] 파워3→광석: player={}", playerId);
                }
                case "POWER_TO_KNOWLEDGE" -> {
                    ps.spendPower(4); // 브레인스톤(3)+일반(1) or 일반(4)
                    ps.addKnowledge(1);
                    log.info("[FREE] 파워4→지식: player={}", playerId);
                }
                case "POWER_TO_QIC" -> {
                    ps.spendPower(4);
                    ps.addQic(1);
                    log.info("[FREE] 파워4→QIC: player={}", playerId);
                }
                case "KNOWLEDGE_TO_CREDIT" -> {
                    ps.spendKnowledge(1);
                    ps.addCredit(1);
                    log.info("[FREE] 지식1→크레딧: player={}", playerId);
                }
                case "QIC_TO_ORE" -> {
                    ps.spendQic(1);
                    ps.addOre(1);
                    log.info("[FREE] QIC1→광석: player={}", playerId);
                }
                case "BAL_TAKS_CONVERT_GAIAFORMER" -> {
                    if (ps.getStockGaiaformer() <= 0) throw new IllegalStateException("가이아포머 재고 없음");
                    ps.convertGaiaformerToQic();
                    log.info("[FREE] 발타크 포머→QIC: player={}", playerId);
                }
                case "HADSCH_HALLAS_3C_ORE" -> {
                    ps.spendCredit(3);
                    ps.addOre(1);
                    log.info("[FREE] 하쉬할라 3c→광석: player={}", playerId);
                }
                case "HADSCH_HALLAS_4C_KNOWLEDGE" -> {
                    ps.spendCredit(4);
                    ps.addKnowledge(1);
                    log.info("[FREE] 하쉬할라 4c→지식: player={}", playerId);
                }
                case "HADSCH_HALLAS_4C_QIC" -> {
                    ps.spendCredit(4);
                    ps.addQic(1);
                    log.info("[FREE] 하쉬할라 4c→QIC: player={}", playerId);
                }
                // 네블라 PI: 3구역 파워 1개 = 2파워 (2개 소모 = 4파워)
                case "NEVLAS_4P_ORE_CREDIT" -> {
                    // 네블라 PI: bowl3 토큰 2개 직접 차감 (spendPower 우회) → bowl1로 이동
                    if (ps.getPowerBowl3() < 2) throw new IllegalStateException("3구역 파워 2개 필요");
                    ps.removePowerFromBowl3(2);
                    ps.addPowerToBowl1(2);
                    ps.addOre(1);
                    ps.addCredit(1);
                    log.info("[FREE] 네블라 PI 4파워(2토큰)→1광석+1크레딧: player={}", playerId);
                }
                case "NEVLAS_4P_ORE2" -> {
                    // 네블라 PI: bowl3 토큰 2개 직접 차감 → bowl1로 이동
                    if (ps.getPowerBowl3() < 2) throw new IllegalStateException("3구역 파워 2개 필요");
                    ps.removePowerFromBowl3(2);
                    ps.addPowerToBowl1(2);
                    ps.addOre(2);
                    log.info("[FREE] 네블라 PI 4파워(2토큰)→2광석: player={}", playerId);
                }
                // 네블라 기본 능력: 3구역 토큰 1개 → 가이아 구역 + 지식 1
                case "NEVLAS_POWER3_TO_GAIA_KNOWLEDGE" -> {
                    if (ps.getPowerBowl3() < 1) throw new IllegalStateException("3구역 파워가 없습니다");
                    ps.removePowerFromBowl3(1);
                    ps.addGaiaPower(1);
                    ps.addKnowledge(1);
                    log.info("[FREE] 네블라 3구역→가이아+지식: player={}", playerId);
                }
                default -> { return FreeConvertResponse.fail("알 수 없는 변환 코드: " + convertCode); }
            }
        } catch (IllegalStateException e) {
            return FreeConvertResponse.fail(e.getMessage());
        }

        playerStateRepository.save(ps);
        return FreeConvertResponse.ok();
    }
}
