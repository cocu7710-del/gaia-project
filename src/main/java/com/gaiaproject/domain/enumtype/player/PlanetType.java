package com.gaiaproject.domain.enumtype.player;

import lombok.Getter;

/**
 * 행성 타입.
 *
 * - 맵의 헥스가 가지는 기본 속성
 * - 테라포밍, 건설 가능 여부 판단에 사용
 */
@Getter
public enum PlanetType {
    /* 기본 행성 */
    TERRA("Terra", "지구"),
    DESERT("Desert", "사막"),
    SWAMP("Swamp", "늪지"),
    OXIDE("Oxide", "산화물"),
    VOLCANIC("Volcanic", "화산"),
    TITANIUM("Titanium", "티타늄"),
    ICE("Ice", "얼음"),
    GAIA("Gaia", "가이아"),
    TRANSDIM("Transdim", "잃어버린 행성"),

    /* 확장 행성 */
    LOST_PLANET("Lost_Planet", "초월 차원"),
    BLACK_PLANET("Black_Planet", "검은행성"),
    ASTEROIDS("Asteroids", "소행성"),

    /* 우주구역 */
    EMPTY("Empty", "없음");

    private final String displayNameEn;
    private final String displayNameKo;

    PlanetType(String displayNameEn, String displayNameKo) {
        this.displayNameEn = displayNameEn;
        this.displayNameKo = displayNameKo;
    }

}
