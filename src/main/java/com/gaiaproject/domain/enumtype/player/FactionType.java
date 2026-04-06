package com.gaiaproject.domain.enumtype.player;

import com.gaiaproject.dto.ResourcesVo;
import com.gaiaproject.dto.TechTracksVo;
import lombok.Getter;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 플레이어가 선택하는 종족(Faction).
 *
 * - 게임 시작 시 좌석에 고정됨
 * - 각 종족은 고유한 '고향 행성 타입'을 가진다
 * - 초기 자원: credits, ore, knowledge, qic, powerBowl1, powerBowl2, powerBowl3
 */
@Getter
public enum FactionType {

    // 기본 게임 종족

    /* 사막 */
    XENOS("Xenos", "제노스", PlanetType.DESERT,
            new ResourcesVo(15, 4, 3, 2, 2, 4, 0, 0, 10, null),
            new TechTracksVo(0, 0, 1, 0, 0, 0)),  // AI 1

    GLEENS("Gleens", "글린", PlanetType.DESERT,
            new ResourcesVo(15, 5, 3, 0, 2, 4, 0, 0, 10, null),
            new TechTracksVo(0, 1, 0, 0, 0, 0)),  // 항해 1

    /* 늪지 */
    TAKLONS("Taklons", "타클론", PlanetType.SWAMP,
            new ResourcesVo(15, 4, 3, 1, 2, 4, 0, 0, 10, 1),  // 브레인스톤 Bowl 1
            TechTracksVo.zero()),
    AMBAS("Ambas", "엠바스", PlanetType.SWAMP,
            new ResourcesVo(15, 4, 3, 2, 2, 4, 0, 0, 10, null),
            new TechTracksVo(0, 1, 0, 0, 0, 0)),  // 항해 1

    /* 화산 */
    HADSCH_HALLAS("Hadsch Hallas", "하드쉬 할라", PlanetType.VOLCANIC,
            new ResourcesVo(15, 4, 3, 1, 2, 4, 0, 0, 10, null),
            new TechTracksVo(0, 0, 0, 0, 1, 0)),  // 경제 1
    IVITS("Ivits", "하이브", PlanetType.VOLCANIC,
            new ResourcesVo(15, 4, 3, 1, 2, 2, 0, 0, 10, null),
            TechTracksVo.zero()),

    /* 산화물 */
    GEODENS("Geodens", "기오덴", PlanetType.OXIDE,
            new ResourcesVo(15, 6, 3, 1, 2, 4, 0, 0, 10, null),
            new TechTracksVo(1, 0, 0, 0, 0, 0)),  // 테라포밍 1
    BAL_TAKS("Bal T'aks", "발타크", PlanetType.OXIDE,
            new ResourcesVo(15, 4, 3, 0, 2, 2, 0, 0, 10, null),
            new TechTracksVo(0, 0, 0, 1, 0, 0)),  // 가이아 1

    /* 지구 */
    LANTIDS("Lantids", "란티다", PlanetType.TERRA,
            new ResourcesVo(13, 4, 3, 1, 4, 0, 0, 0, 10, null),
            TechTracksVo.zero()),
    TERRANS("Terrans", "테란", PlanetType.TERRA,
            new ResourcesVo(15, 4, 3, 1, 4, 4, 0, 0, 10, null),
            new TechTracksVo(0, 0, 0, 1, 0, 0)),  // 가이아 1

    /* 티타늄 */
    FIRAKS("Firaks", "파이락", PlanetType.TITANIUM,
            new ResourcesVo(15, 3, 2, 1, 2, 4, 0, 0, 10, null),
            TechTracksVo.zero()),

    BESCODS("Bescods", "매드안드로이드", PlanetType.TITANIUM,
            new ResourcesVo(15, 4, 3, 1, 2, 4, 0, 0, 10, null),
            TechTracksVo.zero()),

    /* 얼음 */
    NEVLAS("Nevlas", "네블라", PlanetType.ICE,
            new ResourcesVo(15, 4, 2, 1, 2, 4, 0, 0, 10, null),
            new TechTracksVo(0, 0, 0, 0, 0, 1)),  // 과학 1
    ITARS("Itars", "아이타", PlanetType.ICE,
            new ResourcesVo(15, 5, 3, 1, 4, 4, 0, 0, 10, null),
            TechTracksVo.zero()),

    // 확장 종족

    /* 초월차원 */
    SPACE_GIANTS("Space Giants", "스페이스 자이언트", PlanetType.LOST_PLANET,
            new ResourcesVo(15, 6, 3, 2, 4, 4, 0, 0, 10, null),
            new TechTracksVo(0, 1, 0, 0, 0, 0)),  // 항해 1

    MOWEIDS("Moweids", "모웨이드", PlanetType.LOST_PLANET,
            new ResourcesVo(15, 6, 5, 2, 4, 4, 0, 0, 10, null),
            new TechTracksVo(0, 0, 0, 1, 0, 0)),  // 가이아 1

    /* 소행성 */
    TINKEROIDS("Tinkeroids", "팅커로이드", PlanetType.ASTEROIDS,
            new ResourcesVo(15, 4, 2, 1, 4, 4, 0, 0, 10, null),
            new TechTracksVo(0, 0, 0, 0, 0, 1)),  // 과학 1

    DAKANIANS("Dakanians", "다카니안", PlanetType.ASTEROIDS,
            new ResourcesVo(15, 7, 3, 2, 4, 2, 0, 0, 10, null),
            new TechTracksVo(0, 1, 0, 0, 1, 0));  // 항해 1, 경제 1


    private final String displayNameEn;
    private final String displayNameKo;
    private final PlanetType homePlanet;
    private final ResourcesVo initialResources;
    private final TechTracksVo initialTechTracks;

    FactionType(String displayNameEn, String displayNameKo, PlanetType homePlanet,
                ResourcesVo initialResources, TechTracksVo initialTechTracks) {
        this.displayNameEn = displayNameEn;
        this.displayNameKo = displayNameKo;
        this.homePlanet = homePlanet;
        this.initialResources = initialResources;
        this.initialTechTracks = initialTechTracks;
    }

    /**
     * 종족별 기본 수입 (매 라운드)
     * - 기본: 1 광석, 1 지식
     * - 종족별 예외 적용
     */
    public ResourcesVo getBaseIncome() {
        return switch (this) {
            // 파이락: 1 광석, 2 지식
            case FIRAKS -> new ResourcesVo(0, 1, 2, 0, 0, 0, 0, 0, 0, null);

            // 하드쉬 할라: 3 크레딧, 1 광석, 1 지식
            case HADSCH_HALLAS -> new ResourcesVo(3, 1, 1, 0, 0, 0, 0, 0, 0, null);

            // 엠바스: 2 광석, 1 지식
            case AMBAS -> new ResourcesVo(0, 2, 1, 0, 0, 0, 0, 0, 0, null);

            // 매드안드로이드: 1 광석 (지식 없음)
            case BESCODS -> new ResourcesVo(0, 1, 0, 0, 0, 0, 0, 0, 0, null);

            // 아이타: 1 광석, 1 지식, 파워 토큰 1 (1구역 추가)
            case ITARS -> new ResourcesVo(0, 1, 1, 0, 1, 0, 0, 0, 0, null);

            // 란티다: 1 광석, 1 지식, 파워 토큰 1
            case LANTIDS -> new ResourcesVo(0, 1, 1, 0, 1, 0, 0, 0, 0, null);

            // 기본: 1 광석, 1 지식
            default -> new ResourcesVo(0, 1, 1, 0, 0, 0, 0, 0, 0, null);
        };
    }

    /**
     * 종족별 PI(행성 의회) 수입 (파워 차징 외 추가분)
     * - 대부분: 4파순 + 1토큰
     * - 하이브/제노스: 4파순 + 1QIC (별도 처리)
     * - 글린: 4파순 + 1광석
     * - 엠바스/매안: 4파순 + 2토큰
     * - 스페이스 자이언트: 6파순 + 1토큰
     * - 란티다: 4파순만 (토큰 없음)
     */
    public ResourcesVo getPiIncome() {
        return switch (this) {
            // 하이브: 4파순 + 1토큰 + 1QIC
            case IVITS -> new ResourcesVo(0, 0, 0, 1, 1, 0, 0, 4, 0, null);
            // 제노스: 4파순 + 1QIC
            case XENOS -> new ResourcesVo(0, 0, 0, 1, 0, 0, 0, 4, 0, null);
            // 글린: 4파순 + 1광석
            case GLEENS -> new ResourcesVo(0, 1, 0, 0, 0, 0, 0, 4, 0, null);
            // 엠바스: 4파순 + 2토큰
            case AMBAS -> new ResourcesVo(0, 0, 0, 0, 2, 0, 0, 4, 0, null);
            // 매드안드로이드: 4파순 + 2토큰
            case BESCODS -> new ResourcesVo(0, 0, 0, 0, 2, 0, 0, 4, 0, null);
            // 스페이스 자이언트: 6파순 + 1토큰
            case SPACE_GIANTS -> new ResourcesVo(0, 0, 0, 0, 1, 0, 0, 6, 0, null);
            // 란티다: 4파순 + 1토큰
            case LANTIDS -> new ResourcesVo(0, 0, 0, 0, 1, 0, 0, 4, 0, null);
            // 기본: 4파순 + 1토큰
            default -> new ResourcesVo(0, 0, 0, 0, 1, 0, 0, 4, 0, null);
        };
    }

    /**
     * 초기 가이아포머 개수 (가이아 트랙 1단계 시작 종족은 1개)
     */
    public int getInitialGaiaformers() {
        return initialTechTracks.gaia() >= 1 ? 1 : 0;
    }

    /**
     * 확장 종족 여부 확인
     */
    public boolean isExpansionFaction() {
        return this == SPACE_GIANTS || this == MOWEIDS ||
               this == TINKEROIDS || this == DAKANIANS;
    }

    /**
     * 하이브 종족 여부 확인
     */
    public boolean isIvits() {
        return this == IVITS;
    }

    /**
     * 셋업 시 광산 대신 행성 의회를 배치하는 종족 여부
     * - 하이브(IVITS): 의회 1개
     * - 팅커로이드(TINKEROIDS): 의회 1개
     */
    public boolean isSetupPlanetaryInstitute() {
        return this == IVITS || this == TINKEROIDS;
    }

    /**
     * 제노스 종족 여부 확인 (추가 광산 1개)
     */
    public boolean isXenos() {
        return this == XENOS;
    }

    /**
     * 종족 4개 랜덤 선택 (제약: 같은 고향 행성 타입은 1개만)
     * 규칙:
     * - 서로 다른 PlanetType을 가진 4개만 선택
     * - 동일 PlanetType 그룹에서는 최대 1개만 선택
     */
    public static List<FactionType> getRandomFourDifferentHomePlanets(int playerCount) {
        // 1) PlanetType별로 종족 그룹핑
        Map<PlanetType, List<FactionType>> byPlanet =
                Arrays.stream(values())
                        .collect(Collectors.groupingBy(FactionType::getHomePlanet));

        // 2) 행성 타입 목록
        List<PlanetType> planets = new ArrayList<>(byPlanet.keySet());

        // 3) 행성 타입이 플레이어 수 미만이면 예외
        if (planets.size() < playerCount) {
            throw new IllegalStateException(
                    "종족을 " + playerCount +  "개 뽑기 위한 고유 행성 타입이 부족합니다. 현재: " + planets.size()
            );
        }

        // 4) 행성 타입 셔플 후 4개 선택
        Collections.shuffle(planets);
        List<PlanetType> selectedPlanets = planets.subList(0, playerCount);

        // 5) 각 행성 타입에서 종족 1개씩 랜덤 선택
        List<FactionType> result = new ArrayList<>(playerCount);
        Random random = new Random();

        for (PlanetType planet : selectedPlanets) {
            List<FactionType> candidates = byPlanet.get(planet);
            FactionType picked = candidates.get(random.nextInt(candidates.size()));
            result.add(picked);
        }

        // 6) 최종 결과 셔플 (좌석 배치 순서 랜덤화)
        Collections.shuffle(result);

        return result;
    }

}
