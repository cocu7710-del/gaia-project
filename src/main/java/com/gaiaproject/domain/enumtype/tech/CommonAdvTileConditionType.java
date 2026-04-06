package com.gaiaproject.domain.enumtype.tech;

import java.util.Random;

/**
 * COMMON 고급 기술 타일 획득 조건 타입
 * - 게임 시작 시 랜덤으로 1개 선택
 * - VP_25: 현재 승점 25점 이상
 * - FLEET_3: 현재 입장한 함대 갯수 3개 이상
 */
public enum CommonAdvTileConditionType {

    VP_25,
    FLEET_3;

    private static final Random RANDOM = new Random();

    public static CommonAdvTileConditionType random() {
        return values()[RANDOM.nextInt(values().length)];
    }
}
