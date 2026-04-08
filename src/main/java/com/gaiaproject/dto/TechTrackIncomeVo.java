package com.gaiaproject.dto;

import com.gaiaproject.domain.enumtype.tech.EconomyTrackOption;

/**
 * 기술 트랙 레벨별 수입 정의
 */
public class TechTrackIncomeVo {

    /**
     * 경제 트랙 수입 (레벨 0~5) - 옵션에 따라 레벨 3, 4가 다름
     *
     * 공통:
     * 레벨 1: 크레딧 2, 파워차징 1
     * 레벨 2: 크레딧 2, 광석 1, 파워차징 2
     * 레벨 5: 크레딧 6, 광석 3, 파워차징 2
     *
     * 옵션 A (VP 보너스):
     * 레벨 3: 크레딧 3, 광석 1, VP 1
     * 레벨 4: 크레딧 4, 광석 2, VP 1
     *
     * 옵션 B (파워 차징 보너스):
     * 레벨 3: 크레딧 2, 광석 1, 파워차징 3
     * 레벨 4: 크레딧 2, 광석 2, 파워차징 2
     */
    // credits, ore, knowledge, qic, powerBowl1, powerBowl2, powerBowl3, powerCharge, vp, brainstoneBowl
    public static ResourcesVo getEconomyIncome(int level, EconomyTrackOption option) {
        return switch (level) {
            case 0 -> ResourcesVo.zero();
            case 1 -> new ResourcesVo(2, 0, 0, 0, 0, 0, 0, 1, 0, null);  // 크레딧 2, 파워차징 1
            case 2 -> new ResourcesVo(2, 1, 0, 0, 0, 0, 0, 2, 0, null);  // 크레딧 2, 광석 1, 파워차징 2
            case 3 -> getEconomyLevel3Income(option);
            case 4 -> getEconomyLevel4Income(option);
            case 5 -> ResourcesVo.zero();  // 5단계: 수입 사라지고 즉시 보상(6c+3o+6pw)으로 전환
            default -> ResourcesVo.zero();
        };
    }

    /**
     * 경제 트랙 레벨 3 수입 (옵션별)
     */
    private static ResourcesVo getEconomyLevel3Income(EconomyTrackOption option) {
        if (option == EconomyTrackOption.OPTION_A) {
            // 옵션 A: 크레딧 3, 광석 1, VP 1
            return new ResourcesVo(3, 1, 0, 0, 0, 0, 0, 0, 1, null);
        } else {
            // 옵션 B: 크레딧 2, 광석 1, 파워차징 3
            return new ResourcesVo(2, 1, 0, 0, 0, 0, 0, 3, 0, null);
        }
    }

    /**
     * 경제 트랙 레벨 4 수입 (옵션별)
     */
    private static ResourcesVo getEconomyLevel4Income(EconomyTrackOption option) {
        if (option == EconomyTrackOption.OPTION_A) {
            // 옵션 A: 크레딧 4, 광석 2, VP 1
            return new ResourcesVo(4, 2, 0, 0, 0, 0, 0, 0, 1, null);
        } else {
            // 옵션 B: 크레딧 2, 광석 2, 파워차징 2
            return new ResourcesVo(2, 2, 0, 0, 0, 0, 0, 2, 0, null);
        }
    }

    /**
     * 경제 트랙 수입 (기본값: OPTION_A)
     * @deprecated 옵션을 명시적으로 전달하는 getEconomyIncome(int, EconomyTrackOption) 사용 권장
     */
    @Deprecated
    public static ResourcesVo getEconomyIncome(int level) {
        return getEconomyIncome(level, EconomyTrackOption.OPTION_A);
    }

    /**
     * 과학 트랙 수입 (레벨 0~5)
     * 레벨 1: 지식 1
     * 레벨 2: 지식 2
     * 레벨 3: 지식 3
     * 레벨 4: 지식 4
     * 레벨 5: 지식 4
     */
    // credits, ore, knowledge, qic, powerBowl1, powerBowl2, powerBowl3, powerCharge, vp, brainstoneBowl
    public static ResourcesVo getScienceIncome(int level) {
        return switch (level) {
            case 0 -> ResourcesVo.zero();
            case 1 -> new ResourcesVo(0, 0, 1, 0, 0, 0, 0, 0, 0, null);
            case 2 -> new ResourcesVo(0, 0, 2, 0, 0, 0, 0, 0, 0, null);
            case 3 -> new ResourcesVo(0, 0, 3, 0, 0, 0, 0, 0, 0, null);
            case 4 -> new ResourcesVo(0, 0, 4, 0, 0, 0, 0, 0, 0, null);
            case 5 -> ResourcesVo.zero();  // 5단계: 수입 사라지고 즉시 9지식으로 전환
            default -> ResourcesVo.zero();
        };
    }
}
