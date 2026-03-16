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
    private void applyTerransGaiaIncome(GamePlayerState player) {
        if (player.getFactionType() != FactionType.TERRANS) return;
        if (player.getStockPlanetaryInstitute() > 0) return; // PI 미건설
        int gaia = player.getGaiaPower();
        if (gaia <= 0) return;
        player.addCredit(gaia);
        player.addOre(gaia / 3);
        player.addQic(gaia / 4);
        player.addKnowledge(gaia / 4);
        log.info("[TERRANS PI] 가이아 수입: gaia={}, credit+{}, ore+{}, qic+{}, knowledge+{}",
                gaia, gaia, gaia / 3, gaia / 4, gaia / 4);
    }

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
        // 지식 아카데미 수 조회 (건물 DB에서)
        int knowledgeAcademyCount = gameBuildingRepository.countByGameIdAndPlayerIdAndBuildingTypeAndAcademyType(
                player.getGameId(), player.getPlayerId(), BuildingType.ACADEMY, AcademyType.KNOWLEDGE);
        boolean isItars = player.getFactionType() == FactionType.ITARS;

        ResourcesVo buildingIncome = BuildingIncomeVo.getTotalBuildingIncome(
                player.getStockMine(),
                player.getStockTradingStation(),
                player.getStockResearchLab(),
                player.getStockPlanetaryInstitute(),
                knowledgeAcademyCount,
                isItars
        );

        player.applyIncome(buildingIncome);

        // 하이브/제노스 PI 수입: 1 QIC
        if ((player.getFactionType() == FactionType.IVITS || player.getFactionType() == FactionType.XENOS)
                && player.getStockPlanetaryInstitute() == 0) {
            player.addQic(1);
            log.debug("[{} PI] QIC +1 수입 적용", player.getFactionType());
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
