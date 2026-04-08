package com.gaiaproject.domain.enumtype.artifact;

import com.gaiaproject.dto.ResourcesVo;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 인공물 (Artifact)
 * - 트와일라잇 함대 탐사선 배치 + 파워 토큰 6개 영구 제거로 획득
 * - 즉시 효과 또는 수입 효과
 */
@Getter
public enum ArtifactType {

    // ========== 즉시 효과 인공물 (고정 자원) ==========

    /** 즉시: 지식 3, QIC 1 */
    ARTIFACT_1(
            new ResourcesVo(0, 0, 3, 1, 0, 0, 0, 0, 0, null),
            ArtifactEffectType.IMMEDIATE,
            null,
            "즉시: 지식 3, QIC 1"
    ),

    /** 즉시: 크레딧 5, 광석 2 */
    ARTIFACT_2(
            new ResourcesVo(5, 2, 0, 0, 0, 0, 0, 0, 0, null),
            ArtifactEffectType.IMMEDIATE,
            null,
            "즉시: 크레딧 5, 광석 2"
    ),

    /** 즉시: 크레딧 3, 광석 3 */
    ARTIFACT_3(
            new ResourcesVo(3, 3, 0, 0, 0, 0, 0, 0, 0, null),
            ArtifactEffectType.IMMEDIATE,
            null,
            "즉시: 크레딧 3, 광석 3"
    ),

    // ========== 수입 효과 인공물 ==========

    /** 수입: 3파워 구역 파워 토큰 2개 추가 */
    ARTIFACT_4(
            new ResourcesVo(0, 0, 0, 0, 0, 0, 2, 0, 0, null),
            ArtifactEffectType.INCOME,
            null,
            "수입: 파워 토큰 2 (Bowl 3에 추가)"
    ),

    /** 수입: 광석 1, 지식 1 */
    ARTIFACT_5(
            new ResourcesVo(0, 1, 1, 0, 0, 0, 0, 0, 0, null),
            ArtifactEffectType.INCOME,
            null,
            "수입: 광석 1, 지식 1"
    ),

    // ========== 즉시 효과 인공물 (동적 VP 계산) ==========

    /** 즉시: 자신의 건물이 1개 이상 있는 깊은 구역당 3VP */
    ARTIFACT_6(
            ResourcesVo.zero(),
            ArtifactEffectType.IMMEDIATE,
            "VP_PER_DEEP_SECTOR_WITH_BUILDING",
            "즉시: 깊은 구역(건물 1개 이상)당 3VP"
    ),

    /** 즉시: 7VP + 소행성 행성 종류 추가 + 건물 개수 1 추가 */
    ARTIFACT_7(
            new ResourcesVo(0, 0, 0, 0, 0, 0, 0, 0, 7, null),
            ArtifactEffectType.IMMEDIATE,
            "ADD_ASTEROID_PLANET_TYPE",
            "즉시: 7VP + 소행성을 행성 종류로 간주 + 건물 +1"
    ),

    /** 즉시: 7VP + 원시 행성 종류 추가 + 건물 개수 1 추가 */
    ARTIFACT_8(
            new ResourcesVo(0, 0, 0, 0, 0, 0, 0, 0, 7, null),
            ArtifactEffectType.IMMEDIATE,
            "ADD_PRIMITIVE_PLANET_TYPE",
            "즉시: 7VP + 원시 행성을 행성 종류로 간주 + 건물 +1"
    ),

    /** 즉시: 지식 트랙 1칸 전진당 3VP */
    ARTIFACT_9(
            ResourcesVo.zero(),
            ArtifactEffectType.IMMEDIATE,
            "VP_PER_KNOWLEDGE_TRACK_LEVEL",
            "즉시: 지식 트랙 레벨당 3VP"
    ),

    /** 즉시: 가이아 트랙 1칸 전진당 3VP */
    ARTIFACT_10(
            ResourcesVo.zero(),
            ArtifactEffectType.IMMEDIATE,
            "VP_PER_GAIA_TRACK_LEVEL",
            "즉시: 가이아 트랙 레벨당 3VP"
    ),

    /** 즉시: 연구 트랙 3칸 이상 전진한 트랙당 3VP */
    ARTIFACT_11(
            ResourcesVo.zero(),
            ArtifactEffectType.IMMEDIATE,
            "VP_PER_TRACK_LEVEL_3_PLUS",
            "즉시: 레벨 3 이상 트랙당 3VP"
    ),

    /** 즉시: 행성 유형당 1VP + 3VP */
    ARTIFACT_12(
            ResourcesVo.zero(),
            ArtifactEffectType.IMMEDIATE,
            "VP_PER_PLANET_TYPE_PLUS_3",
            "즉시: 행성 유형당 1VP + 3VP"
    ),

    // ========== 특수 효과 인공물 ==========

    /** 특수: 연방 토큰 능력 한번 더 발동 */
    ARTIFACT_13(
            ResourcesVo.zero(),
            ArtifactEffectType.SPECIAL,
            "FEDERATION_TOKEN_DOUBLE_USE",
            "특수: 연방 토큰 능력 1회 추가 발동"
    );

    private final ResourcesVo immediateReward;     // 즉시 효과 (자원)
    private final ArtifactEffectType effectType;   // 효과 타입
    private final String specialEffect;            // 특수 효과 코드
    private final String description;

    ArtifactType(ResourcesVo immediateReward, ArtifactEffectType effectType, String specialEffect, String description) {
        this.immediateReward = immediateReward;
        this.effectType = effectType;
        this.specialEffect = specialEffect;
        this.description = description;
    }

    /**
     * 즉시 효과가 있는지
     */
    public boolean hasImmediateEffect() {
        return effectType == ArtifactEffectType.IMMEDIATE;
    }

    /**
     * 수입 효과가 있는지
     */
    public boolean hasIncomeEffect() {
        return effectType == ArtifactEffectType.INCOME;
    }

    /**
     * 특수 효과가 있는지
     */
    public boolean hasSpecialEffect() {
        return effectType == ArtifactEffectType.SPECIAL;
    }

    /**
     * 동적 VP 계산이 필요한지
     */
    public boolean requiresDynamicVPCalculation() {
        return specialEffect != null && specialEffect.startsWith("VP_PER_");
    }

    /**
     * 인공물 4개 랜덤 선택
     */
    public static List<ArtifactType> getRandomSetup() {
        List<ArtifactType> allArtifacts = new ArrayList<>(Arrays.asList(values()));
        Collections.shuffle(allArtifacts);
        return allArtifacts.subList(0, Math.min(4, allArtifacts.size()));
    }
}