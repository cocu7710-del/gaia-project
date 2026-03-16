package com.gaiaproject.dto;

/**
 * 건물 배치에 따른 수입 계산
 * - 플레이어 보드에서 건물을 배치하면 수입 칸이 열림
 * - 재고에서 빠진 만큼 = 배치된 건물 수
 */
public class BuildingIncomeVo {

    // 기본 재고 수량
    private static final int MAX_MINE = 8;
    private static final int MAX_TRADING_STATION = 4;
    private static final int MAX_RESEARCH_LAB = 3;
    private static final int MAX_PLANETARY_INSTITUTE = 1;
    private static final int MAX_ACADEMY = 2;

    /**
     * 기본 수입 (모든 플레이어 공통)
     * - 광석 1, 지식 1
     */
    public static ResourcesVo getBaseIncome() {
        return new ResourcesVo(0, 1, 1, 0, 0, 0, 0, 0, 0, null);
    }

    /**
     * 광산 수입 계산
     * - 1번째 광산: +1 광석
     * - 2번째 광산: +1 광석
     * - 3번째 광산: +0 광석 (수입 없음)
     * - 4~8번째 광산: +1 광석씩
     */
    public static ResourcesVo getMineIncome(int stockMine) {
        int placedMines = MAX_MINE - stockMine;
        int oreIncome = 0;

        for (int i = 1; i <= placedMines; i++) {
            if (i == 3) {
                // 3번째 광산은 수입 없음
                continue;
            }
            oreIncome++;
        }

        return new ResourcesVo(0, oreIncome, 0, 0, 0, 0, 0, 0, 0, null);
    }

    /**
     * 교역소 수입 계산
     * - 1번째 교역소: 3 크레딧
     * - 2번째 교역소: 4 크레딧
     * - 3번째 교역소: 4 크레딧
     * - 4번째 교역소: 5 크레딧
     */
    public static ResourcesVo getTradingStationIncome(int stockTradingStation) {
        int placed = MAX_TRADING_STATION - stockTradingStation;
        int creditIncome = 0;

        // 각 교역소별 수입 계산
        int[] incomePerSlot = {3, 4, 4, 5};
        for (int i = 0; i < placed && i < incomePerSlot.length; i++) {
            creditIncome += incomePerSlot[i];
        }

        return new ResourcesVo(creditIncome, 0, 0, 0, 0, 0, 0, 0, 0, null);
    }

    /**
     * 연구소 수입 계산
     * - 각 연구소당 1 지식
     */
    public static ResourcesVo getResearchLabIncome(int stockResearchLab) {
        int placed = MAX_RESEARCH_LAB - stockResearchLab;
        return new ResourcesVo(0, 0, placed, 0, 0, 0, 0, 0, 0, null);
    }

    /**
     * 행성 의회 수입 계산
     * - 배치 시 4 파워 차징
     * (종족별 추가 효과는 별도 처리)
     */
    public static ResourcesVo getPlanetaryInstituteIncome(int stockPlanetaryInstitute) {
        int placed = MAX_PLANETARY_INSTITUTE - stockPlanetaryInstitute;
        if (placed > 0) {
            // 4 파워 차징
            return new ResourcesVo(0, 0, 0, 0, 0, 0, 0, 4, 0, null);
        }
        return ResourcesVo.zero();
    }

    /**
     * 학원 수입 계산
     * - 지식 아카데미: 2 지식 (아이타는 3 지식)
     * - QIC 아카데미: 수입 없음 (액션 별도)
     */
    public static ResourcesVo getAcademyIncome(int knowledgeAcademyCount, boolean isItars) {
        int perAcademy = isItars ? 3 : 2;
        int knowledgeIncome = knowledgeAcademyCount * perAcademy;
        return new ResourcesVo(0, 0, knowledgeIncome, 0, 0, 0, 0, 0, 0, null);
    }

    /**
     * 전체 건물 수입 합산 (기본 수입 제외 - 종족별로 별도 적용)
     */
    public static ResourcesVo getTotalBuildingIncome(
            int stockMine,
            int stockTradingStation,
            int stockResearchLab,
            int stockPlanetaryInstitute,
            int knowledgeAcademyCount,
            boolean isItars
    ) {
        ResourcesVo total = ResourcesVo.zero();
        total = total.add(getMineIncome(stockMine));
        total = total.add(getTradingStationIncome(stockTradingStation));
        total = total.add(getResearchLabIncome(stockResearchLab));
        total = total.add(getPlanetaryInstituteIncome(stockPlanetaryInstitute));
        total = total.add(getAcademyIncome(knowledgeAcademyCount, isItars));
        return total;
    }
}
