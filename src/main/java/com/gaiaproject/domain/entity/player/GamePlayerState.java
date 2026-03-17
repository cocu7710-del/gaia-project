package com.gaiaproject.domain.entity.player;

import com.gaiaproject.domain.enumtype.player.FactionType;
import com.gaiaproject.dto.ResourcesVo;
import com.gaiaproject.dto.TechTracksVo;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 게임 내 플레이어 상태 (자원, VP 등)
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "game_player_state")
public class GamePlayerState {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "player_id", nullable = false)
    private UUID playerId;

    @Column(name = "seat_no", nullable = false)
    private Integer seatNo;

    // 종족 타입
    @Enumerated(EnumType.STRING)
    @Column(name = "faction_type", length = 30)
    private FactionType factionType;

    public void setFactionType(FactionType ft) { this.factionType = ft; }

    // 자원
    @Column(name = "ore", nullable = false)
    private Integer ore = 0;

    @Column(name = "credit", nullable = false)
    private Integer credit = 0;

    @Column(name = "knowledge", nullable = false)
    private Integer knowledge = 0;

    @Column(name = "qic", nullable = false)
    private Integer qic = 0;

    // 파워
    @Column(name = "power_bowl_1", nullable = false)
    private Integer powerBowl1 = 0;

    @Column(name = "power_bowl_2", nullable = false)
    private Integer powerBowl2 = 0;

    @Column(name = "power_bowl_3", nullable = false)
    private Integer powerBowl3 = 0;

    // VP
    @Column(name = "victory_points", nullable = false)
    private Integer victoryPoints = 0;

    // 브레인스톤 (타클론 전용, null: 없음, 1/2/3: 해당 bowl)
    @Column(name = "brainstone_bowl")
    private Integer brainstoneBowl;

    // 기술 트랙 레벨 (0~5)
    @Column(name = "tech_terraforming", nullable = false)
    private Integer techTerraforming = 0;

    @Column(name = "tech_navigation", nullable = false)
    private Integer techNavigation = 0;

    @Column(name = "tech_ai", nullable = false)
    private Integer techAi = 0;

    @Column(name = "tech_gaia", nullable = false)
    private Integer techGaia = 0;

    @Column(name = "tech_economy", nullable = false)
    private Integer techEconomy = 0;

    @Column(name = "tech_science", nullable = false)
    private Integer techScience = 0;

    // 건물 재고
    @Column(name = "stock_mine", nullable = false)
    private Integer stockMine = 8;

    @Column(name = "stock_trading_station", nullable = false)
    private Integer stockTradingStation = 4;

    @Column(name = "stock_research_lab", nullable = false)
    private Integer stockResearchLab = 3;

    @Column(name = "stock_planetary_institute", nullable = false)
    private Integer stockPlanetaryInstitute = 1;

    @Column(name = "stock_academy", nullable = false)
    private Integer stockAcademy = 2;

    @Column(name = "stock_gaiaformer", nullable = false)
    private Integer stockGaiaformer = 0;

    // 가이아 구역 파워 (포머 배치 시 이동, 다음 라운드 수입 후 bowl1으로 복귀)
    @Column(name = "gaia_power", nullable = false)
    private Integer gaiaPower = 0;

    @Column(name = "booster_action_used", nullable = false)
    private boolean boosterActionUsed = false;

    // 종족 고유 능력 사용 여부 (라운드당 1회 능력: BESCODS, SPACE_GIANTS, GLEENS 점프)
    @Column(name = "faction_ability_used", nullable = false)
    private boolean factionAbilityUsed = false;

    // 소행성 광산 건설로 영구 제거된 가이아포머 수
    @Column(name = "permanently_removed_gaiaformers", nullable = false)
    private int permanentlyRemovedGaiaformers = 0;

    // QIC 아카데미 액션 사용 여부 (라운드당 1회)
    @Column(name = "qic_academy_action_used", nullable = false)
    private boolean qicAcademyActionUsed = false;

    // 발타크 전용: 이번 라운드 QIC로 변환된 가이아포머 수 (다음 라운드 시작 시 반환)
    @Column(name = "baltaks_converted_gaiaformers", nullable = false)
    private int baltaksConvertedGaiaformers = 0;

    // 팅커로이드 전용: 게임 전체에서 사용한 액션 코드 목록 (쉼표 구분)
    @Column(name = "tinkeroids_used_actions", length = 200)
    private String tinkeroidsUsedActions = "";

    // 팅커로이드 전용: 현재 라운드 선택된 액션 코드 (사용 후 null)
    @Column(name = "tinkeroids_current_action", length = 50)
    private String tinkeroidsCurrentAction;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    // 자원 추가 메서드 (최대치 제한: 돈 30, 광석 15, 지식 15, QIC 무제한)
    public void addOre(int amount) {
        this.ore = Math.min(15, this.ore + amount);
    }

    public void addCredit(int amount) {
        this.credit = Math.min(30, this.credit + amount);
    }

    public void addKnowledge(int amount) {
        this.knowledge = Math.min(15, this.knowledge + amount);
    }

    /** 글린 전용: QIC 아카데미 건설 여부 (DB 영속) */
    @Column(name = "gleens_has_qic_academy", nullable = false)
    private boolean gleensHasQicAcademy = false;

    public void setGleensHasQicAcademy(boolean v) { this.gleensHasQicAcademy = v; }

    public void addQic(int amount) {
        // 글린: QIC 아카데미 건설 전까지 모든 QIC → ORE 자동 변환
        if (factionType == com.gaiaproject.domain.enumtype.player.FactionType.GLEENS && !gleensHasQicAcademy) {
            this.ore = Math.min(15, this.ore + amount);
            return;
        }
        this.qic += amount;
    }

    public void chargePower(int amount) {
        // 파워 차징 로직 (Bowl I → II → III)
        int remaining = amount;
        int originalBowl2 = powerBowl2; // Bowl I → II 이동 전 기존 Bowl II 값 저장

        // Bowl I → Bowl II
        int fromBowl1 = Math.min(powerBowl1, remaining);
        powerBowl1 -= fromBowl1;
        powerBowl2 += fromBowl1;
        remaining -= fromBowl1;

        // Bowl II → Bowl III (기존 Bowl II 토큰만 사용, 방금 이동한 토큰 제외)
        if (remaining > 0) {
            int fromBowl2 = Math.min(originalBowl2, remaining);
            powerBowl2 -= fromBowl2;
            powerBowl3 += fromBowl2;
        }
    }

    public void gainPower(int amount) {
        // Bowl III에 직접 추가
        this.powerBowl3 += amount;
    }

    /** 파워 토큰 추가: Bowl I에 직접 추가 (총 토큰 수 증가) */
    public void addPowerToken(int amount) {
        this.powerBowl1 += amount;
        this.updatedAt = LocalDateTime.now();
    }

    public void addVP(int amount) {
        this.victoryPoints += amount;
    }

    public static GamePlayerState create(UUID gameId, UUID playerId, Integer seatNo) {
        GamePlayerState state = new GamePlayerState();
        state.gameId = gameId;
        state.playerId = playerId;
        state.seatNo = seatNo;
        state.createdAt = LocalDateTime.now();
        state.updatedAt = LocalDateTime.now();
        return state;
    }

    /**
     * 종족 초기 자원으로 플레이어 상태 생성
     */
    public static GamePlayerState createWithFaction(UUID gameId, UUID playerId, Integer seatNo, FactionType faction) {
        GamePlayerState state = create(gameId, playerId, seatNo);

        // 종족 타입 저장
        state.factionType = faction;

        // 초기 자원 적용
        ResourcesVo init = faction.getInitialResources();
        state.credit = init.credits();
        state.ore = init.ore();
        state.knowledge = init.knowledge();
        state.qic = init.qic();
        state.powerBowl1 = init.powerBowl1();
        state.powerBowl2 = init.powerBowl2();
        state.powerBowl3 = init.powerBowl3();
        state.victoryPoints = init.vp();
        state.brainstoneBowl = init.brainstoneBowl();

        // 초기 기술 트랙 적용
        TechTracksVo tech = faction.getInitialTechTracks();
        state.techTerraforming = tech.terraforming();
        state.techNavigation = tech.navigation();
        state.techAi = tech.ai();
        state.techGaia = tech.gaia();
        state.techEconomy = tech.economy();
        state.techScience = tech.science();

        // 가이아 트랙 1단계 이상 시작 시 가이아포머 지급
        state.stockGaiaformer = faction.getInitialGaiaformers();

        return state;
    }

    /**
     * 수입 적용 (라운드 부스터 등)
     */
    public void applyIncome(ResourcesVo income) {
        addCredit(income.credits());
        addOre(income.ore());
        addKnowledge(income.knowledge());
        addQic(income.qic());
        this.powerBowl1 += income.powerBowl1();
        this.powerBowl2 += income.powerBowl2();
        this.powerBowl3 += income.powerBowl3();
        this.victoryPoints += income.vp();

        // 파워 차징 (종족 규칙 반영)
        if (income.powerCharge() > 0) {
            chargePowerWithFactionRules(income.powerCharge());
        }

        this.updatedAt = LocalDateTime.now();
    }

    /* 파워 1구역 토큰 제거 */
    public void addPowerBowl1(int power) {
        powerBowl1 -= power;
    }

    /* 파워 2구역 토큰 제거 */
    public void addPowerBowl2(int power) {
        powerBowl2 -= power;
    }

    /* 파워 3구역 토큰 제거 */
    public void addPowerBowl3(int power) {
        powerBowl3 -= power;
    }

    /* 부스터 액션 사용 처리 */
    public void markBoosterActionUsed() {
        this.boosterActionUsed = true;
        this.updatedAt = LocalDateTime.now();
    }

    /* 라운드 시작 시 부스터 액션 초기화 */
    public void resetBoosterActionUsed() {
        this.boosterActionUsed = false;
        this.updatedAt = LocalDateTime.now();
    }

    /* 브레인스톤 위치 설정 (타클론 전용) */
    public void setBrainstoneBowl(Integer bowl) {
        this.brainstoneBowl = bowl;
    }

    /* 브레인스톤 이동 */
    public void moveBrainstone(int toBowl) {
        if (toBowl < 1 || toBowl > 3) {
            throw new IllegalArgumentException("브레인스톤은 1~3 bowl에만 위치할 수 있습니다.");
        }
        this.brainstoneBowl = toBowl;
    }

    /* 광산 재고 감소 */
    public void decreaseStockMine() {
        if (this.stockMine <= 0) {
            throw new IllegalStateException("광산 재고가 없습니다.");
        }
        this.stockMine--;
        this.updatedAt = LocalDateTime.now();
    }

    /* 교역소 재고 감소 */
    public void decreaseStockTradingStation() {
        if (this.stockTradingStation <= 0) {
            throw new IllegalStateException("교역소 재고가 없습니다.");
        }
        this.stockTradingStation--;
        this.updatedAt = LocalDateTime.now();
    }

    /* 연구소 재고 감소 */
    public void decreaseStockResearchLab() {
        if (this.stockResearchLab <= 0) {
            throw new IllegalStateException("연구소 재고가 없습니다.");
        }
        this.stockResearchLab--;
        this.updatedAt = LocalDateTime.now();
    }

    /* 행성 의회 재고 감소 */
    public void decreaseStockPlanetaryInstitute() {
        if (this.stockPlanetaryInstitute <= 0) {
            throw new IllegalStateException("행성 의회 재고가 없습니다.");
        }
        this.stockPlanetaryInstitute--;
        this.updatedAt = LocalDateTime.now();
    }

    /* 아카데미 재고 감소 */
    public void decreaseStockAcademy() {
        if (this.stockAcademy <= 0) {
            throw new IllegalStateException("아카데미 재고가 없습니다.");
        }
        this.stockAcademy--;
        this.updatedAt = LocalDateTime.now();
    }

    /* 가이아포머 재고 감소 */
    public void decreaseStockGaiaformer() {
        if (this.stockGaiaformer <= 0) {
            throw new IllegalStateException("가이아포머 재고가 없습니다.");
        }
        this.stockGaiaformer--;
        this.updatedAt = LocalDateTime.now();
    }

    /* 가이아포머 영구 제거 (소행성 광산 건설) */
    public void permanentlyRemoveGaiaformer() {
        if (this.stockGaiaformer <= 0) {
            throw new IllegalStateException("가이아포머 재고가 없습니다.");
        }
        this.stockGaiaformer--;
        this.permanentlyRemovedGaiaformers++;
        this.updatedAt = LocalDateTime.now();
    }

    /* 가이아포머 재고 증가 */
    public void addGaiaformer(int amount) {
        this.stockGaiaformer += amount;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 가이아 포밍 파워 지불: bowl1 전부 → bowl2 → bowl3 순으로 required만큼 가이아 구역으로 이동
     * @param required 가이아 트랙 레벨별 필요 파워량
     */
    public void spendPowerToGaia(int required) {
        int total = powerBowl1 + powerBowl2 + powerBowl3;
        if (total < required) {
            throw new IllegalStateException("파워 토큰이 부족합니다. 필요: " + required + ", 보유: " + total);
        }
        int remaining = required;
        // bowl1 전부 먼저
        int fromBowl1 = Math.min(powerBowl1, remaining);
        powerBowl1 -= fromBowl1;
        remaining -= fromBowl1;
        // bowl2
        if (remaining > 0) {
            int fromBowl2 = Math.min(powerBowl2, remaining);
            powerBowl2 -= fromBowl2;
            remaining -= fromBowl2;
        }
        // bowl3
        if (remaining > 0) {
            int fromBowl3 = Math.min(powerBowl3, remaining);
            powerBowl3 -= fromBowl3;
        }
        gaiaPower += required;
        this.updatedAt = LocalDateTime.now();
    }

    /** 아이타 PI: 가이아 구역 파워 영구 제거 */
    public void removeGaiaPower(int amount) {
        if (this.gaiaPower < amount) throw new IllegalStateException("가이아 구역 파워 부족: " + gaiaPower);
        this.gaiaPower -= amount;
        this.updatedAt = java.time.LocalDateTime.now();
    }

    /** 가이아 파워 추가 (복원용) */
    public void addGaiaPower(int amount) {
        this.gaiaPower += amount;
        this.updatedAt = LocalDateTime.now();
    }

    /** 파워를 bowl1에 추가 */
    public void addPowerToBowl1(int amount) {
        this.powerBowl1 += amount;
        this.updatedAt = LocalDateTime.now();
    }

    /** 라운드 시작 시 가이아 구역 파워 복귀 (테란: bowl2, 나머지: bowl1) */
    public void returnGaiaPower() {
        if (factionType == com.gaiaproject.domain.enumtype.player.FactionType.TERRANS) {
            this.powerBowl2 += this.gaiaPower;
        } else {
            this.powerBowl1 += this.gaiaPower;
        }
        this.gaiaPower = 0;
        this.updatedAt = LocalDateTime.now();
    }

    /* 자원 소비 메서드 */
    public void spendOre(int amount) {
        if (this.ore < amount) throw new IllegalStateException("광석이 부족합니다. 필요: " + amount + ", 보유: " + this.ore);
        this.ore -= amount;
        this.updatedAt = LocalDateTime.now();
    }

    public void spendCredit(int amount) {
        if (this.credit < amount) throw new IllegalStateException("크레딧이 부족합니다. 필요: " + amount + ", 보유: " + this.credit);
        this.credit -= amount;
        this.updatedAt = LocalDateTime.now();
    }

    public void spendKnowledge(int amount) {
        if (this.knowledge < amount) throw new IllegalStateException("지식이 부족합니다. 필요: " + amount + ", 보유: " + this.knowledge);
        this.knowledge -= amount;
        this.updatedAt = LocalDateTime.now();
    }

    /** 지식 트랙 전진: 지식 4 소모, 해당 트랙 +1 */
    public void advanceTechTrack(String trackCode) {
        // 발타크: 의회 건설 전까지 거리 트랙 전진 불가 (의회 건설 후 해제)
        if (factionType == com.gaiaproject.domain.enumtype.player.FactionType.BAL_TAKS
                && "NAVIGATION".equals(trackCode)
                && stockPlanetaryInstitute > 0) {  // PI 재고 > 0 = 아직 건설 전
            throw new IllegalStateException("발타크는 행성 의회 건설 전까지 거리 트랙을 전진할 수 없습니다");
        }
        spendKnowledge(4);
        switch (trackCode) {
            case "TERRA_FORMING" -> { if (this.techTerraforming >= 5) throw new IllegalStateException("이미 최대 레벨입니다."); this.techTerraforming++; }
            case "NAVIGATION"    -> { if (this.techNavigation >= 5)   throw new IllegalStateException("이미 최대 레벨입니다."); this.techNavigation++; }
            case "AI"            -> { if (this.techAi >= 5)           throw new IllegalStateException("이미 최대 레벨입니다."); this.techAi++; }
            case "GAIA_FORMING"  -> { if (this.techGaia >= 5)         throw new IllegalStateException("이미 최대 레벨입니다."); this.techGaia++; }
            case "ECONOMY"       -> { if (this.techEconomy >= 5)      throw new IllegalStateException("이미 최대 레벨입니다."); this.techEconomy++; }
            case "SCIENCE"       -> { if (this.techScience >= 5)      throw new IllegalStateException("이미 최대 레벨입니다."); this.techScience++; }
            default -> throw new IllegalArgumentException("알 수 없는 트랙 코드: " + trackCode);
        }
        this.updatedAt = LocalDateTime.now();
    }

    /** 함대 액션 전용: 지식 소모 없이 트랙 1단계 전진 */
    public void advanceTechTrackNoKnowledge(String fieldName) {
        switch (fieldName) {
            case "techTerraforming" -> this.techTerraforming++;
            case "techNavigation"   -> this.techNavigation++;
            case "techAi"           -> this.techAi++;
            case "techGaia"         -> this.techGaia++;
            case "techEconomy"      -> this.techEconomy++;
            case "techScience"      -> this.techScience++;
            default -> throw new IllegalArgumentException("알 수 없는 트랙 필드: " + fieldName);
        }
        this.updatedAt = LocalDateTime.now();
    }

    /** 타클론 전용: 브레인스톤 우선 충전 */
    public void chargePowerTaklons(int amount) {
        int remaining = amount;
        int originalBowl2 = powerBowl2;

        // 브레인스톤이 bowl1에 있으면 우선 충전
        if (brainstoneBowl != null && brainstoneBowl == 1 && remaining > 0) {
            brainstoneBowl = 2;
            remaining--;
        }

        int fromBowl1 = Math.min(powerBowl1, remaining);
        powerBowl1 -= fromBowl1;
        powerBowl2 += fromBowl1;
        remaining -= fromBowl1;

        // 브레인스톤이 bowl2에 있으면 우선 충전
        if (remaining > 0 && brainstoneBowl != null && brainstoneBowl == 2) {
            brainstoneBowl = 3;
            remaining--;
        }

        if (remaining > 0) {
            int fromBowl2 = Math.min(originalBowl2, remaining);
            powerBowl2 -= fromBowl2;
            powerBowl3 += fromBowl2;
        }
        this.updatedAt = java.time.LocalDateTime.now();
    }

    /** 종족 규칙을 반영한 파워 충전 (TAKLONS 전용 규칙 적용, 아이타는 차징은 일반과 동일 - 소각만 다름) */
    public void chargePowerWithFactionRules(int amount) {
        if (factionType == com.gaiaproject.domain.enumtype.player.FactionType.TAKLONS) {
            chargePowerTaklons(amount);
        } else {
            chargePower(amount);
        }
    }

    /** 발타크 전용: 가이아포머 1개 → QIC 1개 변환 (프리 액션) */
    public void convertGaiaformerToQic() {
        if (stockGaiaformer <= 0) throw new IllegalStateException("사용 가능한 가이아포머가 없습니다");
        stockGaiaformer--;
        qic++;
        baltaksConvertedGaiaformers++;
        this.updatedAt = java.time.LocalDateTime.now();
    }

    /** 라운드 시작 시 발타크 변환된 가이아포머 반환 */
    public void returnConvertedGaiaformers() {
        if (baltaksConvertedGaiaformers > 0) {
            stockGaiaformer += baltaksConvertedGaiaformers;
            baltaksConvertedGaiaformers = 0;
            this.updatedAt = java.time.LocalDateTime.now();
        }
    }

    /** 팅커로이드: 액션이 이미 사용되었는지 */
    public boolean isTinkeroidsActionUsed(String actionCode) {
        return tinkeroidsUsedActions != null && tinkeroidsUsedActions.contains(actionCode);
    }

    /** 팅커로이드: 액션 선택 */
    public void selectTinkeroidsAction(String actionCode) {
        this.tinkeroidsCurrentAction = actionCode;
        if (tinkeroidsUsedActions == null || tinkeroidsUsedActions.isEmpty()) {
            tinkeroidsUsedActions = actionCode;
        } else {
            tinkeroidsUsedActions += "," + actionCode;
        }
        this.updatedAt = java.time.LocalDateTime.now();
    }

    /** 팅커로이드: 현재 라운드 액션 사용 */
    public void useTinkeroidsCurrentAction() {
        this.tinkeroidsCurrentAction = null;
        this.updatedAt = java.time.LocalDateTime.now();
    }

    /** 종족 고유 능력 사용 표시 */
    public void markFactionAbilityUsed() {
        this.factionAbilityUsed = true;
        this.updatedAt = java.time.LocalDateTime.now();
    }

    /** 라운드 시작 시 종족 고유 능력 초기화 */
    public void resetFactionAbilityUsed() {
        this.factionAbilityUsed = false;
        this.updatedAt = java.time.LocalDateTime.now();
    }

    /** QIC 아카데미 액션 사용 처리 */
    public void useQicAcademyAction() {
        if (this.qicAcademyActionUsed) {
            throw new IllegalStateException("이번 라운드에 이미 QIC 아카데미 액션을 사용했습니다.");
        }
        this.qicAcademyActionUsed = true;
        this.addQic(1);
        this.updatedAt = java.time.LocalDateTime.now();
    }

    /** 라운드 시작 시 QIC 아카데미 액션 초기화 */
    public void resetQicAcademyActionUsed() {
        this.qicAcademyActionUsed = false;
        this.updatedAt = java.time.LocalDateTime.now();
    }

    /** 매드안드로이드 전용: 가장 낮은 기술 트랙 1단계 전진 (지식 소모 없음) */
    public String advanceLowestTechTrack() {
        int min = Math.min(techTerraforming, Math.min(techNavigation, Math.min(techAi, Math.min(techGaia, Math.min(techEconomy, techScience)))));
        // 가장 낮은 트랙 중 최대 레벨이 아닌 것 전진
        if (techTerraforming == min && techTerraforming < 5) { techTerraforming++; this.updatedAt = java.time.LocalDateTime.now(); return "TERRA_FORMING"; }
        if (techNavigation == min && techNavigation < 5)     { techNavigation++;   this.updatedAt = java.time.LocalDateTime.now(); return "NAVIGATION"; }
        if (techAi == min && techAi < 5)                     { techAi++;           this.updatedAt = java.time.LocalDateTime.now(); return "AI"; }
        if (techGaia == min && techGaia < 5)                 { techGaia++;         this.updatedAt = java.time.LocalDateTime.now(); return "GAIA_FORMING"; }
        if (techEconomy == min && techEconomy < 5)           { techEconomy++;      this.updatedAt = java.time.LocalDateTime.now(); return "ECONOMY"; }
        if (techScience == min && techScience < 5)           { techScience++;      this.updatedAt = java.time.LocalDateTime.now(); return "SCIENCE"; }
        throw new IllegalStateException("전진 가능한 기술 트랙이 없습니다");
    }

    /** 최저 기술 트랙 레벨 조회 */
    public int getLowestTechLevel() {
        return Math.min(techTerraforming, Math.min(techNavigation, Math.min(techAi, Math.min(techGaia, Math.min(techEconomy, techScience)))));
    }

    /** 특정 트랙의 현재 레벨 조회 */
    public int getTechLevel(String trackCode) {
        return switch (trackCode) {
            case "TERRA_FORMING" -> techTerraforming;
            case "NAVIGATION" -> techNavigation;
            case "AI" -> techAi;
            case "GAIA_FORMING" -> techGaia;
            case "ECONOMY" -> techEconomy;
            case "SCIENCE" -> techScience;
            default -> throw new IllegalArgumentException("알 수 없는 트랙: " + trackCode);
        };
    }

    /** 매드안드로이드 전용: 지정된 트랙 1단계 전진 (지식 소모 없음) */
    public void advanceTechTrackFree(String trackCode) {
        switch (trackCode) {
            case "TERRA_FORMING" -> this.techTerraforming++;
            case "NAVIGATION" -> this.techNavigation++;
            case "AI" -> this.techAi++;
            case "GAIA_FORMING" -> this.techGaia++;
            case "ECONOMY" -> this.techEconomy++;
            case "SCIENCE" -> this.techScience++;
            default -> throw new IllegalArgumentException("알 수 없는 트랙: " + trackCode);
        }
        this.updatedAt = java.time.LocalDateTime.now();
    }

    /** 파워 소각: bowl2에서 2개 제거 → 1개는 bowl3, 아이타는 추가로 1개가 가이아 구역 */
    public void burnPower() {
        if (this.powerBowl2 < 2) throw new IllegalStateException("2구역 파워가 부족합니다. 보유: " + this.powerBowl2);
        this.powerBowl2 -= 2;
        if (this.factionType == com.gaiaproject.domain.enumtype.player.FactionType.ITARS) {
            this.powerBowl3 += 1;  // 1개는 3구역
            this.gaiaPower += 1;   // 1개는 가이아 구역
        } else {
            this.powerBowl3 += 1;  // 일반: 1개만 3구역 (1개 영구 제거)
        }
        this.updatedAt = LocalDateTime.now();
    }

    /** useBrainstone: 타클론 전용 — true면 브레인스톤(3파워)을 먼저 사용 */
    @Transient
    private boolean useBrainstone = false;
    public void setUseBrainstone(boolean v) { this.useBrainstone = v; }

    public void spendPower(int amount) {
        // 네블라 PI: bowl3 토큰 1개 = 2파워 (남는 파워 반환 없음)
        boolean isNevlasPi = factionType == com.gaiaproject.domain.enumtype.player.FactionType.NEVLAS
                && stockPlanetaryInstitute == 0;

        // 타클론: 브레인스톤 사용 (bowl3에 있을 때, 3파워 가치, 남는 파워 반환 없음)
        if (useBrainstone && brainstoneBowl != null && brainstoneBowl == 3) {
            int brainstonePower = 3;
            if (amount <= brainstonePower) {
                // 브레인스톤만으로 충분 → 브레인스톤만 이동 (bowl3→bowl1)
                brainstoneBowl = 1;
                this.updatedAt = LocalDateTime.now();
                return;
            } else {
                // 브레인스톤 + 추가 파워
                brainstoneBowl = 1;
                int remaining = amount - brainstonePower;
                if (this.powerBowl3 < remaining) throw new IllegalStateException("파워가 부족합니다");
                this.powerBowl3 -= remaining;
                this.powerBowl1 += remaining;
                this.updatedAt = LocalDateTime.now();
                return;
            }
        }

        if (isNevlasPi) {
            int tokensNeeded = (amount + 1) / 2;
            if (this.powerBowl3 < tokensNeeded) throw new IllegalStateException("파워가 부족합니다. 필요 토큰: " + tokensNeeded + ", 보유: " + this.powerBowl3);
            this.powerBowl3 -= tokensNeeded;
            this.powerBowl1 += tokensNeeded;
        } else {
            if (this.powerBowl3 < amount) throw new IllegalStateException("파워가 부족합니다. 필요: " + amount + ", 보유: " + this.powerBowl3);
            this.powerBowl3 -= amount;
            this.powerBowl1 += amount;
        }
        this.updatedAt = LocalDateTime.now();
    }

    /** 연방 토큰용: 파워 토큰 영구 제거 (순환하지 않음) */
    public void removePowerFromBowl1(int amount) { this.powerBowl1 -= amount; this.updatedAt = LocalDateTime.now(); }
    public void removePowerFromBowl2(int amount) { this.powerBowl2 -= amount; this.updatedAt = LocalDateTime.now(); }
    public void removePowerFromBowl3(int amount) { this.powerBowl3 -= amount; this.updatedAt = LocalDateTime.now(); }

    public void spendVP(int amount) {
        if (this.victoryPoints < amount) throw new IllegalStateException("VP가 부족합니다. 필요: " + amount + ", 보유: " + this.victoryPoints);
        this.victoryPoints -= amount;
        this.updatedAt = LocalDateTime.now();
    }

    public void spendQic(int amount) {
        if (this.qic < amount) throw new IllegalStateException("QIC가 부족합니다. 필요: " + amount + ", 보유: " + this.qic);
        this.qic -= amount;
        this.updatedAt = LocalDateTime.now();
    }

    /* 건물 재고 반환 메서드 (업그레이드 시 이전 건물 재고 복귀) */
    public void addMineToStock() {
        this.stockMine++;
        this.updatedAt = LocalDateTime.now();
    }

    public void addTradingStationToStock() {
        this.stockTradingStation++;
        this.updatedAt = LocalDateTime.now();
    }

    public void addResearchLabToStock() {
        this.stockResearchLab++;
        this.updatedAt = LocalDateTime.now();
    }
}
