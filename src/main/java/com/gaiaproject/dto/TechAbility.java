package com.gaiaproject.dto;

import com.gaiaproject.domain.entity.player.GamePlayerState;
import com.gaiaproject.domain.enumtype.tech.TechAbilityType;
import lombok.Builder;
import lombok.Getter;

/**
 * 기술 타일의 능력 정보
 */
@Getter
@Builder
public class TechAbility {
    private final TechAbilityType type;              // 능력 타입
    private final String description;                // 능력 설명

    // 수입 관련
    private final Integer oreIncome;                 // 광석 수입
    private final Integer creditIncome;              // 크레딧 수입
    private final Integer knowledgeIncome;           // 지식 수입
    private final Integer qicIncome;                 // QIC 수입

    // 파워 관련 (명확하게 구분)
    private final Integer powerCharge;               // 파워 차징 (Bowl I → II → III 이동)
    private final Integer powerGain;                 // 파워 토큰 획득

    // VP 관련
    private final Integer vpGain;                    // 승점 획득
    private final Integer vpOnPass;                  // 패스 시 승점

    // 특수 능력
    private final String specialEffect;              // 특수 효과 코드

    /**
     * 능력을 플레이어에게 적용
     */
    public void applyTo(GamePlayerState playerState) {
        if (oreIncome != null) {
            playerState.addOre(oreIncome);
        }
        if (creditIncome != null) {
            playerState.addCredit(creditIncome);
        }
        if (knowledgeIncome != null) {
            playerState.addKnowledge(knowledgeIncome);
        }
        if (qicIncome != null) {
            playerState.addQic(qicIncome);
        }
        if (powerCharge != null) {
            playerState.chargePowerWithFactionRules(powerCharge);
        }
        if (powerGain != null) {
            playerState.gainPower(powerGain);
        }
        if (vpGain != null) {
            playerState.addVP(vpGain);
        }
    }
}