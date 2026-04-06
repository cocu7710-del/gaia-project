package com.gaiaproject.service;

import com.gaiaproject.domain.entity.game.Game;
import com.gaiaproject.domain.entity.game.GameSeat;
import com.gaiaproject.domain.entity.player.GamePlayerArtifact;
import com.gaiaproject.domain.entity.player.GamePlayerState;
import com.gaiaproject.domain.entity.player.GamePlayerTechTile;
import com.gaiaproject.domain.enumtype.artifact.ArtifactType;
import com.gaiaproject.domain.enumtype.booster.RoundBoosterType;
import com.gaiaproject.domain.enumtype.player.FactionType;
import com.gaiaproject.domain.enumtype.tech.EconomyTrackOption;
import com.gaiaproject.domain.enumtype.tech.TechAbilityType;
import com.gaiaproject.domain.enumtype.tech.TechTileCode;
import com.gaiaproject.domain.enumtype.building.AcademyType;
import com.gaiaproject.domain.enumtype.building.BuildingType;
import com.gaiaproject.dto.BuildingIncomeVo;
import com.gaiaproject.dto.ResourcesVo;
import com.gaiaproject.dto.TechAbility;
import com.gaiaproject.dto.TechTrackIncomeVo;
import com.gaiaproject.repository.building.GameBuildingRepository;
import com.gaiaproject.repository.game.GameRepository;
import com.gaiaproject.repository.game.GameSeatRepository;
import com.gaiaproject.repository.player.GamePlayerArtifactRepository;
import com.gaiaproject.repository.player.GamePlayerRoundBoosterRepository;
import com.gaiaproject.repository.player.GamePlayerStateRepository;
import com.gaiaproject.repository.tech.GamePlayerTechTileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gaiaproject.dto.PowerIncomeItemVo;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 라운드 시작 시 수입 계산 및 적용 서비스
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class IncomeService {

    private final GameRepository gameRepository;
    private final GameSeatRepository gameSeatRepository;
    private final GamePlayerStateRepository playerStateRepository;
    private final GamePlayerRoundBoosterRepository playerBoosterRepository;
    private final GamePlayerTechTileRepository playerTechTileRepository;
    private final GamePlayerArtifactRepository playerArtifactRepository;
    private final GameBuildingRepository gameBuildingRepository;

    /**
     * 특정 게임의 모든 플레이어에게 라운드 수입 적용
     */
    public void applyRoundIncome(UUID gameId) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new IllegalArgumentException("게임을 찾을 수 없습니다: " + gameId));
        applyRoundIncome(game);
    }

    /**
     * 특정 게임의 모든 플레이어에게 라운드 수입 적용 (Game 엔티티 직접 전달)
     */
    public void applyRoundIncome(Game game) {
        UUID gameId = game.getId();
        EconomyTrackOption economyOption = game.getEconomyTrackOption();
        List<GamePlayerState> players = playerStateRepository.findByGameId(gameId);

        log.info("수입 적용 시작 - gameId: {}, 경제 트랙 옵션: {}, 플레이어 수: {}",
                gameId, economyOption, players.size());

        if (players.isEmpty()) {
            log.error("플레이어 상태가 없습니다! gameId: {}", gameId);
            return;
        }

        for (GamePlayerState player : players) {
            player.resetBoosterActionUsed();       // 라운드 시작 시 부스터 액션 초기화
            player.resetFactionAbilityUsed();      // 종족 고유 능력 초기화
            player.resetQicAcademyActionUsed();    // QIC 아카데미 액션 초기화
            player.returnConvertedGaiaformers();   // 발타크 변환 가이아포머 반환
            resetTechTileActions(gameId, player.getPlayerId()); // ACTION 타일 초기화
            applyTerransGaiaIncome(player);        // 테란 PI: 가이아 구역 파워 → 자원
            applyIncomeToPlayer(gameId, player, economyOption);
            playerStateRepository.saveAndFlush(player);  // 명시적 저장 + 즉시 반영
        }

        log.info("라운드 수입 적용 완료 - gameId: {}, 경제 트랙 옵션: {}", gameId, economyOption);
    }

    /**
     * 비파워 수입만 적용 (파워 순환/토큰 추가 제외) + 리셋 처리
     */
    public void applyNonPowerIncome(Game game) {
        UUID gameId = game.getId();
        EconomyTrackOption economyOption = game.getEconomyTrackOption();
        List<GamePlayerState> players = playerStateRepository.findByGameId(gameId);

        for (GamePlayerState player : players) {
            player.resetBoosterActionUsed();
            player.resetFactionAbilityUsed();
            player.resetQicAcademyActionUsed();
            player.returnConvertedGaiaformers();
            resetTechTileActions(gameId, player.getPlayerId());
            applyTerransGaiaIncome(player);
            applyNonPowerIncomeToPlayer(gameId, player, economyOption);
            playerStateRepository.saveAndFlush(player);
        }
        log.info("비파워 수입 적용 완료 - gameId: {}", gameId);
    }

    /**
     * 비파워 수입만 적용 (ResourcesVo에서 powerCharge/powerBowl1 제외)
     */
    private void applyNonPowerIncomeToPlayer(UUID gameId, GamePlayerState player, EconomyTrackOption economyOption) {
        UUID playerId = player.getPlayerId();

        // 1) 종족별 기본 수입 (파워 없음)
        applyFactionBaseIncome(gameId, player);

        // 2) 부스터 수입 — 비파워만
        var boosterOpt = playerBoosterRepository.findByGameIdAndPlayerId(gameId, playerId);
        if (boosterOpt.isPresent()) {
            ResourcesVo income = boosterOpt.get().getRoundBoosterType().getIncome();
            player.applyIncome(stripPower(income));
        }

        // 3) 경제 트랙 — 비파워만
        int economyLevel = player.getTechEconomy();
        if (economyLevel > 0) {
            ResourcesVo ecoIncome = TechTrackIncomeVo.getEconomyIncome(economyLevel, economyOption);
            player.applyIncome(stripPower(ecoIncome));
        }

        // 4) 과학 트랙 (파워 없음)
        int scienceLevel = player.getTechScience();
        if (scienceLevel > 0) {
            player.applyIncome(TechTrackIncomeVo.getScienceIncome(scienceLevel));
        }

        // 5) 기술 타일 — 비파워만
        for (var tile : playerTechTileRepository.findByGameIdAndPlayerIdAndIsCovered(gameId, playerId, false)) {
            try {
                TechTileCode code = TechTileCode.valueOf(tile.getTechTileCode());
                if (code.getAbility().getType() == TechAbilityType.INCOME) {
                    ResourcesVo income = new ResourcesVo(
                            code.getAbility().getCreditIncome() != null ? code.getAbility().getCreditIncome() : 0,
                            code.getAbility().getOreIncome() != null ? code.getAbility().getOreIncome() : 0,
                            code.getAbility().getKnowledgeIncome() != null ? code.getAbility().getKnowledgeIncome() : 0,
                            code.getAbility().getQicIncome() != null ? code.getAbility().getQicIncome() : 0,
                            0, 0, 0, 0, 0, null);
                    player.applyIncome(income);
                }
            } catch (IllegalArgumentException ignored) {}
        }

        // 6) 인공물 수입 — 비파워만
        applyArtifactIncome(gameId, playerId, player);

        // 7) 건물 수입 — 비파워만
        applyBuildingIncomeNonPower(player);
    }

    /** ResourcesVo에서 파워 관련 제거 */
    private ResourcesVo stripPower(ResourcesVo r) {
        return new ResourcesVo(r.credits(), r.ore(), r.knowledge(), r.qic(), 0, 0, 0, 0, r.vp(), null);
    }

    /** 건물 수입 — 비파워만 (파워 순환/토큰 추가 제외) */
    private void applyBuildingIncomeNonPower(GamePlayerState player) {
        FactionType buildFaction = player.getFactionType();
        if (buildFaction == null) {
            buildFaction = gameSeatRepository.findByGameIdAndSeatNo(player.getGameId(), player.getSeatNo())
                    .map(GameSeat::getFactionType).orElse(null);
        }
        int knowledgeAcademyCount = gameBuildingRepository.countByGameIdAndPlayerIdAndBuildingTypeAndAcademyType(
                player.getGameId(), player.getPlayerId(), BuildingType.ACADEMY, AcademyType.KNOWLEDGE);
        boolean isItars = buildFaction == FactionType.ITARS;
        boolean isBescods = buildFaction == FactionType.BESCODS;
        boolean isNevlas2 = buildFaction == FactionType.NEVLAS;
        ResourcesVo buildingIncome = BuildingIncomeVo.getTotalBuildingIncome(
                player.getStockMine(), player.getStockTradingStation(), player.getStockResearchLab(),
                player.getStockPlanetaryInstitute(), knowledgeAcademyCount, isItars, isBescods, isNevlas2);
        player.applyIncome(stripPower(buildingIncome));

        // PI 수입 — 비파워만
        if (buildFaction != null && player.getStockPlanetaryInstitute() == 0) {
            ResourcesVo piIncome = buildFaction.getPiIncome();
            player.applyIncome(stripPower(piIncome));
        }
    }

    /**
     * 특정 플레이어의 파워 수입 항목 리스트 계산
     */
    public List<PowerIncomeItemVo> calculatePowerIncomeItems(UUID gameId, GamePlayerState player, EconomyTrackOption economyOption) {
        List<PowerIncomeItemVo> items = new ArrayList<>();
        UUID playerId = player.getPlayerId();
        int idx = 0;

        // 1) 부스터
        var boosterOpt = playerBoosterRepository.findByGameIdAndPlayerId(gameId, playerId);
        if (boosterOpt.isPresent()) {
            ResourcesVo income = boosterOpt.get().getRoundBoosterType().getIncome();
            if (income.powerCharge() > 0) {
                items.add(new PowerIncomeItemVo("BOOSTER_CHARGE_" + (idx++), "BOOSTER",
                        "부스터: " + income.powerCharge() + "파순", income.powerCharge(), 0));
            }
            if (income.powerBowl1() > 0) {
                items.add(new PowerIncomeItemVo("BOOSTER_TOKEN_" + (idx++), "BOOSTER",
                        "부스터: " + income.powerBowl1() + "토추", 0, income.powerBowl1()));
            }
        }

        // 2) 경제 트랙
        int ecoLevel = player.getTechEconomy();
        if (ecoLevel > 0) {
            ResourcesVo ecoIncome = TechTrackIncomeVo.getEconomyIncome(ecoLevel, economyOption);
            if (ecoIncome.powerCharge() > 0) {
                items.add(new PowerIncomeItemVo("ECO_CHARGE_" + (idx++), "ECONOMY_TRACK",
                        "경제트랙: " + ecoIncome.powerCharge() + "파순", ecoIncome.powerCharge(), 0));
            }
        }

        // 3) 기술 타일 (INCOME 타입)
        for (var tile : playerTechTileRepository.findByGameIdAndPlayerIdAndIsCovered(gameId, playerId, false)) {
            try {
                TechTileCode code = TechTileCode.valueOf(tile.getTechTileCode());
                if (code.getAbility().getType() == TechAbilityType.INCOME) {
                    Integer pc = code.getAbility().getPowerCharge();
                    if (pc != null && pc > 0) {
                        items.add(new PowerIncomeItemVo("TECH_CHARGE_" + (idx++), "TECH_TILE",
                                "기술타일: " + pc + "파순", pc, 0));
                    }
                }
            } catch (IllegalArgumentException ignored) {}
        }

        // 4) 건물 — 기본 건물 수입 파워
        FactionType buildFaction = player.getFactionType();
        if (buildFaction == null) {
            buildFaction = gameSeatRepository.findByGameIdAndSeatNo(player.getGameId(), player.getSeatNo())
                    .map(GameSeat::getFactionType).orElse(null);
        }
        int knowledgeAcademyCount = gameBuildingRepository.countByGameIdAndPlayerIdAndBuildingTypeAndAcademyType(
                player.getGameId(), playerId, BuildingType.ACADEMY, AcademyType.KNOWLEDGE);
        boolean isItars = buildFaction == FactionType.ITARS;
        boolean isBescods2 = buildFaction == FactionType.BESCODS;
        boolean isNevlas3 = buildFaction == FactionType.NEVLAS;
        ResourcesVo buildingIncome = BuildingIncomeVo.getTotalBuildingIncome(
                player.getStockMine(), player.getStockTradingStation(), player.getStockResearchLab(),
                player.getStockPlanetaryInstitute(), knowledgeAcademyCount, isItars, isBescods2, isNevlas3);
        if (buildingIncome.powerCharge() > 0) {
            items.add(new PowerIncomeItemVo("BUILDING_CHARGE_" + (idx++), "BUILDING",
                    "건물: " + buildingIncome.powerCharge() + "파순", buildingIncome.powerCharge(), 0));
        }

        // 5) 종족 기본 수입 — 파워 토큰 (아이타/란티다 등)
        if (buildFaction != null) {
            ResourcesVo baseIncome = buildFaction.getBaseIncome();
            if (baseIncome.powerBowl1() > 0) {
                items.add(new PowerIncomeItemVo("BASE_TOKEN_" + (idx++), "FACTION_BASE",
                        "기본: " + baseIncome.powerBowl1() + "토추", 0, baseIncome.powerBowl1()));
            }
        }

        // 6) PI 수입 — 파워 관련
        if (buildFaction != null && player.getStockPlanetaryInstitute() == 0) {
            ResourcesVo piIncome = buildFaction.getPiIncome();
            if (piIncome.powerCharge() > 0) {
                items.add(new PowerIncomeItemVo("PI_CHARGE_" + (idx++), "PI",
                        "의회: " + piIncome.powerCharge() + "파순", piIncome.powerCharge(), 0));
            }
            if (piIncome.powerBowl1() > 0) {
                items.add(new PowerIncomeItemVo("PI_TOKEN_" + (idx++), "PI",
                        "의회: " + piIncome.powerBowl1() + "토추", 0, piIncome.powerBowl1()));
            }
        }

        return items;
    }

    /**
     * 파워 수입 항목 1개 적용
     */
    public void applySinglePowerIncome(GamePlayerState player, PowerIncomeItemVo item) {
        if (item.powerCharge() > 0) {
            player.chargePowerWithFactionRules(item.powerCharge());
        }
        if (item.powerBowl1() > 0) {
            player.addPowerToken(item.powerBowl1());
        }
        playerStateRepository.save(player);
        log.info("[POWER_INCOME] player={}, item={}, charge={}, token={}", player.getPlayerId(), item.id(), item.powerCharge(), item.powerBowl1());
    }

    /**
     * 특정 플레이어에게 모든 수입 적용
     */
    public void applyIncomeToPlayer(UUID gameId, GamePlayerState player, EconomyTrackOption economyOption) {
        UUID playerId = player.getPlayerId();

        log.info("=== 플레이어 {} 수입 적용 시작 (적용 전: 크레딧={}, 광석={}, 지식={}) ===",
                playerId, player.getCredit(), player.getOre(), player.getKnowledge());

        // 1) 종족별 기본 수입 (1광석, 1지식 + 종족 보너스)
        applyFactionBaseIncome(gameId, player);
        log.info("종족 기본 수입 후: 크레딧={}, 광석={}, 지식={}", player.getCredit(), player.getOre(), player.getKnowledge());

        // 2) 라운드 부스터 수입
        applyBoosterIncome(gameId, player);
        log.info("부스터 수입 후: 크레딧={}, 광석={}, 지식={}", player.getCredit(), player.getOre(), player.getKnowledge());

        // 3) 기술 트랙 수입 (경제, 과학)
        applyTechTrackIncome(player, economyOption);

        // 4) 기술 타일 수입 (덮이지 않은 INCOME 타입)
        applyTechTileIncome(gameId, playerId, player);

        // 5) 인공물 수입 (INCOME 타입)
        applyArtifactIncome(gameId, playerId, player);

        // 6) 건물 수입 (기본 수입 제외)
        applyBuildingIncome(player);
        log.info("건물 수입 후 (stockMine={}): 크레딧={}, 광석={}, 지식={}",
                player.getStockMine(), player.getCredit(), player.getOre(), player.getKnowledge());

        log.info("=== 플레이어 {} 수입 적용 완료 ===", playerId);
    }

    /**
     * 종족별 기본 수입 적용
     */
    private void applyFactionBaseIncome(UUID gameId, GamePlayerState player) {
        FactionType faction = player.getFactionType();

        // factionType이 null이면 game_seat에서 조회
        if (faction == null) {
            faction = gameSeatRepository.findByGameIdAndSeatNo(gameId, player.getSeatNo())
                    .map(GameSeat::getFactionType)
                    .orElse(null);
            log.debug("game_seat에서 종족 조회: seatNo={}, faction={}", player.getSeatNo(), faction);
        }

        if (faction != null) {
            ResourcesVo baseIncome = faction.getBaseIncome();
            player.applyIncome(baseIncome);
            log.debug("종족 기본 수입 적용 ({}): {}", faction, baseIncome);
        } else {
            // 종족 정보 없으면 기본값 (1 광석, 1 지식)
            ResourcesVo defaultIncome = new ResourcesVo(0, 1, 1, 0, 0, 0, 0, 0, 0, null);
            player.applyIncome(defaultIncome);
            log.warn("종족 정보 없음 - 기본 수입 적용: gameId={}, playerId={}", gameId, player.getPlayerId());
        }
    }

    /**
     * 라운드 부스터 수입 적용
     */
    private void applyBoosterIncome(UUID gameId, GamePlayerState player) {
        var boosterOpt = playerBoosterRepository.findByGameIdAndPlayerId(gameId, player.getPlayerId());
        if (boosterOpt.isEmpty()) {
            log.warn("부스터가 없습니다! gameId={}, playerId={}", gameId, player.getPlayerId());
            return;
        }
        var booster = boosterOpt.get();
        RoundBoosterType boosterType = booster.getRoundBoosterType();
        ResourcesVo income = boosterType.getIncome();
        log.info("부스터 수입 적용: {} -> credits={}, ore={}, knowledge={}",
                boosterType, income.credits(), income.ore(), income.knowledge());
        player.applyIncome(income);
    }

    /**
     * 기술 트랙 수입 적용 (경제, 과학)
     */
    private void applyTechTrackIncome(GamePlayerState player, EconomyTrackOption economyOption) {
        // 경제 트랙 수입
        int economyLevel = player.getTechEconomy();
        if (economyLevel > 0) {
            ResourcesVo economyIncome = TechTrackIncomeVo.getEconomyIncome(economyLevel, economyOption);
            player.applyIncome(economyIncome);
            log.debug("경제 트랙 수입 (레벨 {}, 옵션 {}): {}", economyLevel, economyOption, economyIncome);
        }

        // 과학 트랙 수입
        int scienceLevel = player.getTechScience();
        if (scienceLevel > 0) {
            ResourcesVo scienceIncome = TechTrackIncomeVo.getScienceIncome(scienceLevel);
            player.applyIncome(scienceIncome);
            log.debug("과학 트랙 수입 (레벨 {}): {}", scienceLevel, scienceIncome);
        }
    }

    /**
     * 테란 PI: 수입 전 가이아 구역 파워 → 자원 변환
     * 1 가이아파워 = 1 크레딧, 3 가이아파워 = 1 광석, 4 가이아파워 = 1 QIC, 4 가이아파워 = 1 지식
     */
    /**
     * 테란 PI: 가이아 토큰 → 자원 변환
     * 수입 단계에서는 자동 변환하지 않음 — FE에서 수동 배분 후 API 호출
     * (가이아 토큰이 없거나 PI 미건설이면 스킵)
     */
    private void applyTerransGaiaIncome(GamePlayerState player) {
        // 자동 변환 제거 — FE 다이얼로그에서 수동 처리
        // applyTerransGaiaConvert()로 이동
    }

    /**
     * 테란 PI: FE에서 선택한 배분으로 가이아 토큰 → 자원 변환
     * @param credits 크레딧으로 변환할 토큰 수 (1:1)
     * @param ores 광석으로 변환할 묶음 수 (3:1)
     * @param qics QIC로 변환할 묶음 수 (4:1)
     * @param knowledges 지식으로 변환할 묶음 수 (4:1)
     */
    public void applyTerransGaiaConvert(UUID gameId, UUID playerId, int credits, int ores, int qics, int knowledges) {
        GamePlayerState player = playerStateRepository.findByGameIdAndPlayerId(gameId, playerId)
                .orElseThrow(() -> new IllegalStateException("플레이어 상태를 찾을 수 없습니다"));

        int totalTokens = credits + ores * 3 + qics * 4 + knowledges * 4;
        if (totalTokens > player.getGaiaPower()) {
            throw new IllegalStateException("가이아 토큰이 부족합니다. 보유: " + player.getGaiaPower() + ", 요청: " + totalTokens);
        }

        player.addCredit(credits);
        player.addOre(ores);
        player.addQic(qics);
        player.addKnowledge(knowledges);
        playerStateRepository.save(player);
        log.info("[TERRANS PI] 가이아 수동 변환: gaia={}, c+{}, o+{}, q+{}, k+{}",
                player.getGaiaPower(), credits, ores, qics, knowledges);

        // 테란 변환 완료 → 다음 단계 진행 (팅커→아이타→라운드시작)
        passService.continueAfterTerransGaia(gameId);
    }

    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired
    private PassService passService;

    /**
     * ACTION 타입 기술 타일 사용 여부 초기화 (라운드 시작 시)
     */
    private void resetTechTileActions(UUID gameId, UUID playerId) {
        List<GamePlayerTechTile> tiles = playerTechTileRepository.findByGameIdAndPlayerId(gameId, playerId);
        for (GamePlayerTechTile tile : tiles) {
            tile.resetAction();
        }
        if (!tiles.isEmpty()) playerTechTileRepository.saveAll(tiles);
    }

    /**
     * 기술 타일 수입 적용 (덮이지 않은 INCOME 타입만)
     */
    private void applyTechTileIncome(UUID gameId, UUID playerId, GamePlayerState player) {
        List<GamePlayerTechTile> tiles = playerTechTileRepository
                .findByGameIdAndPlayerIdAndIsCovered(gameId, playerId, false);

        for (GamePlayerTechTile tile : tiles) {
            try {
                TechTileCode tileCode = TechTileCode.valueOf(tile.getTechTileCode());
                TechAbility ability = tileCode.getAbility();

                if (ability.getType() == TechAbilityType.INCOME) {
                    ability.applyTo(player);
                    log.debug("기술 타일 수입 적용: {}", tileCode);
                }
            } catch (IllegalArgumentException e) {
                log.warn("알 수 없는 기술 타일 코드: {}", tile.getTechTileCode());
            }
        }
    }

    /**
     * 인공물 수입 적용 (INCOME 타입만)
     */
    private void applyArtifactIncome(UUID gameId, UUID playerId, GamePlayerState player) {
        List<GamePlayerArtifact> artifacts = playerArtifactRepository
                .findByGameIdAndPlayerId(gameId, playerId);

        for (GamePlayerArtifact artifact : artifacts) {
            try {
                ArtifactType artifactType = ArtifactType.valueOf(artifact.getArtifactType());

                if (artifactType.hasIncomeEffect()) {
                    ResourcesVo income = artifactType.getImmediateReward();
                    player.applyIncome(income);
                    log.debug("인공물 수입 적용: {}", artifactType);
                }
            } catch (IllegalArgumentException e) {
                log.warn("알 수 없는 인공물 타입: {}", artifact.getArtifactType());
            }
        }
    }

    /**
     * 건물 수입 적용 (배치된 건물 기준)
     */
    private void applyBuildingIncome(GamePlayerState player) {
        // 종족 타입 (null이면 seat에서 조회)
        FactionType buildFaction = player.getFactionType();
        if (buildFaction == null) {
            buildFaction = gameSeatRepository.findByGameIdAndSeatNo(player.getGameId(), player.getSeatNo())
                    .map(GameSeat::getFactionType).orElse(null);
        }

        // 지식 아카데미 수 조회 (건물 DB에서)
        int knowledgeAcademyCount = gameBuildingRepository.countByGameIdAndPlayerIdAndBuildingTypeAndAcademyType(
                player.getGameId(), player.getPlayerId(), BuildingType.ACADEMY, AcademyType.KNOWLEDGE);
        boolean isItars = buildFaction == FactionType.ITARS;
        boolean isBescods3 = buildFaction == FactionType.BESCODS;
        boolean isNevlas = buildFaction == FactionType.NEVLAS;

        ResourcesVo buildingIncome = BuildingIncomeVo.getTotalBuildingIncome(
                player.getStockMine(),
                player.getStockTradingStation(),
                player.getStockResearchLab(),
                player.getStockPlanetaryInstitute(),
                knowledgeAcademyCount,
                isItars,
                isBescods3,
                isNevlas
        );

        player.applyIncome(buildingIncome);

        // 종족별 PI 수입 (파워차징 + 토큰/QIC/광석 등)
        if (buildFaction != null && player.getStockPlanetaryInstitute() == 0) {
            ResourcesVo piIncome = buildFaction.getPiIncome();
            player.applyIncome(piIncome);
            log.debug("[{} PI] 수입 적용: {}", buildFaction, piIncome);
        }
        log.debug("건물 수입 적용: 광산 {}개, 교역소 {}개, 연구소 {}개, 행성수도 {}개, 학원 {}개 -> {}",
                8 - player.getStockMine(),
                4 - player.getStockTradingStation(),
                3 - player.getStockResearchLab(),
                1 - player.getStockPlanetaryInstitute(),
                2 - player.getStockAcademy(),
                buildingIncome);
    }
}
