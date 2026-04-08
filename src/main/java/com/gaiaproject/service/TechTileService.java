package com.gaiaproject.service;

import com.gaiaproject.domain.entity.building.GameBuilding;
import com.gaiaproject.domain.entity.game.Game;
import com.gaiaproject.domain.entity.map.GameHex;
import com.gaiaproject.domain.entity.player.GamePlayerState;
import com.gaiaproject.domain.entity.tech.GameAdvTechOffer;
import com.gaiaproject.domain.entity.tech.GameTechOffer;
import com.gaiaproject.domain.enumtype.action.ActionType;
import com.gaiaproject.domain.enumtype.building.BuildingType;
import com.gaiaproject.domain.enumtype.player.PlanetType;
import com.gaiaproject.domain.enumtype.player.FactionType;
import com.gaiaproject.domain.enumtype.action.VpCategory;
import com.gaiaproject.domain.enumtype.rounds.RoundScoringEvent;
import com.gaiaproject.domain.enumtype.tech.AdvancedTechTileCode;
import com.gaiaproject.domain.enumtype.tech.CommonAdvTileConditionType;
import com.gaiaproject.domain.enumtype.tech.EconomyTrackOption;
import com.gaiaproject.domain.enumtype.tech.TechAbilityType;
import com.gaiaproject.domain.enumtype.tech.TechCategoryType;
import com.gaiaproject.domain.enumtype.tech.TechTileCode;
import com.gaiaproject.dto.TechAbility;
import com.gaiaproject.dto.request.AdvanceTechRequest;
import com.gaiaproject.dto.response.AdvanceTechResponse;
import com.gaiaproject.dto.response.ConfirmActionResponse;
import com.gaiaproject.domain.entity.player.GamePlayerTechTile;
import com.gaiaproject.repository.building.GameBuildingRepository;
import com.gaiaproject.repository.game.GameRepository;
import com.gaiaproject.repository.map.GameHexRepository;
import com.gaiaproject.repository.player.GamePlayerStateRepository;
import com.gaiaproject.repository.tech.GameAdvTechOfferRepository;
import com.gaiaproject.repository.tech.GamePlayerTechTileRepository;
import com.gaiaproject.repository.tech.GameTechOfferRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(noRollbackFor = IllegalStateException.class)
public class TechTileService {

    private final GameTechOfferRepository gameTechOfferRepository;
    private final GameAdvTechOfferRepository gameAdvTechOfferRepository;
    private final GamePlayerTechTileRepository playerTechTileRepository;
    private final GameRepository gameRepository;
    private final GamePlayerStateRepository gamePlayerStateRepository;
    private final GameBuildingRepository gameBuildingRepository;
    private final GameHexRepository gameHexRepository;
    private final ActionService actionService;
    private final RoundScoringService roundScoringService;
    private final com.gaiaproject.repository.federation.GameFederationGroupRepository federationGroupRepository;
    private final com.gaiaproject.repository.player.GamePlayerFederationTokenRepository playerFederationTokenRepository;
    private final com.gaiaproject.repository.federation.GameFederationOfferRepository federationOfferRepository;
    private final com.gaiaproject.repository.player.GamePlayerFleetProbeRepository fleetProbeRepository;
    private final GameWebSocketService webSocketService;
    private final VpLogService vpLogService;
    private final com.gaiaproject.repository.player.GamePlayerArtifactRepository playerArtifactRepository;

    /** 지식 트랙 전진 (지식 4 소모, PLAYING 페이즈) */
    public AdvanceTechResponse advanceTechTrack(UUID gameId, AdvanceTechRequest request) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new IllegalArgumentException("게임을 찾을 수 없습니다"));

        if (!"PLAYING".equals(game.getGamePhase())) {
            return AdvanceTechResponse.fail(gameId, "PLAYING 페이즈가 아닙니다");
        }

        GamePlayerState playerState = gamePlayerStateRepository.findByGameIdAndPlayerId(gameId, request.playerId())
                .orElseThrow(() -> new IllegalStateException("플레이어 상태를 찾을 수 없습니다"));

        // 발타크: PI 건설 전 NAVIGATION 트랙 전진 불가
        if ("NAVIGATION".equals(request.trackCode())
                && playerState.getFactionType() == FactionType.BAL_TAKS
                && playerState.getStockPlanetaryInstitute() > 0) {
            return AdvanceTechResponse.fail(gameId, "발타크는 행성 의회 건설 후 거리 트랙을 올릴 수 있습니다.");
        }

        // 5단계 1명 제한: 현재 4단계에서 5단계 진입 시도할 때만 체크
        int currentLevel = switch (request.trackCode()) {
            case "TERRA_FORMING" -> playerState.getTechTerraforming();
            case "NAVIGATION"    -> playerState.getTechNavigation();
            case "AI"            -> playerState.getTechAi();
            case "GAIA_FORMING"  -> playerState.getTechGaia();
            case "ECONOMY"       -> playerState.getTechEconomy();
            case "SCIENCE"       -> playerState.getTechScience();
            default -> 0;
        };
        if (currentLevel == 4 && isTrackLevel5Occupied(gameId, request.playerId(), request.trackCode())) {
            return AdvanceTechResponse.fail(gameId, "이미 다른 플레이어가 해당 트랙 5단계에 있습니다.");
        }

        try {
            playerState.advanceTechTrack(request.trackCode());
        } catch (IllegalStateException | IllegalArgumentException e) {
            return AdvanceTechResponse.fail(gameId, e.getMessage());
        }

        int newLevel = switch (request.trackCode()) {
            case "TERRA_FORMING" -> playerState.getTechTerraforming();
            case "NAVIGATION"    -> playerState.getTechNavigation();
            case "AI"            -> playerState.getTechAi();
            case "GAIA_FORMING"  -> playerState.getTechGaia();
            case "ECONOMY"       -> playerState.getTechEconomy();
            case "SCIENCE"       -> playerState.getTechScience();
            default -> 0;
        };

        applyTechTrackReward(playerState, request.trackCode(), newLevel, game.getEconomyTrackOption());

        // 라운드 점수 타일: 연구 트랙 1칸 전진당 2VP
        int techRound = game.getCurrentRound() != null ? game.getCurrentRound() : 1;
        roundScoringService.award(gameId, techRound, playerState, RoundScoringEvent.RESEARCH_ADVANCED, 1);

        gamePlayerStateRepository.save(playerState);

        log.info("기술 트랙 전진: game={}, player={}, track={}, newLevel={}", gameId, request.playerId(), request.trackCode(), newLevel);

        String actionData = String.format("{\"trackCode\":\"%s\",\"newLevel\":%d}", request.trackCode(), newLevel);

        // NAVIGATION 4→5: 검은행성 배치 대기 (턴 넘기지 않음)
        if ("NAVIGATION".equals(request.trackCode()) && newLevel == 5) {
            actionService.saveActionOnly(gameId, request.playerId(), ActionType.ADVANCE_TECH, actionData);
            webSocketService.broadcastDeferredActionRequired(gameId, request.playerId(),
                    "PLACE_LOST_PLANET", String.format("{\"triggerPlayerId\":\"%s\"}", request.playerId()));
            return AdvanceTechResponse.success(gameId, request.trackCode(), newLevel, 0);
        }

        ConfirmActionResponse actionResult = actionService.saveActionAndNextTurn(gameId, request.playerId(), ActionType.ADVANCE_TECH, actionData);

        return AdvanceTechResponse.success(gameId, request.trackCode(), newLevel,
                actionResult.nextTurnSeatNo() != null ? actionResult.nextTurnSeatNo() : 0);
    }

    /**
     * 교역소/아카데미 건설 시 기술 타일 획득 (지식 소모 없음, 트랙 1칸 전진)
     * - 기본 타일: 그대로 획득 + 트랙 전진
     * - 고급 타일(ADV_): 연방 토큰 플립 + 기본타일 커버 + 트랙 전진
     * - COMMON/EXPANSION 타일: techTrackCode로 플레이어가 선택한 트랙 전진
     *
     * @param gameId 게임 ID
     * @param playerId 플레이어 ID
     * @param tileCode 획득할 기술 타일 코드
     * @param techTrackCode COMMON 타일일 때 플레이어가 선택한 트랙 코드 (nullable)
     * @param economyOption 경제 트랙 옵션 (즉발 보상 계산용)
     * @param coveredTileCode 고급 타일 획득 시 덮을 기본 타일 코드 (nullable, 고급 타일일 때 필수)
     * @throws IllegalStateException 유효하지 않은 타일 또는 중복 소유 시
     */
    public void acquireTileForBuilding(UUID gameId, UUID playerId, String tileCode,
                                       String techTrackCode, EconomyTrackOption economyOption,
                                       String coveredTileCode) {
        log.info("[acquireTileForBuilding] 진입: game={}, player={}, tile={}, track={}, cover={}",
                gameId, playerId, tileCode, techTrackCode, coveredTileCode);

        boolean isAdvanced = tileCode.startsWith("ADV_");

        if (isAdvanced) {
            acquireAdvancedTile(gameId, playerId, tileCode, techTrackCode, economyOption, coveredTileCode);
        } else {
            acquireBasicTile(gameId, playerId, tileCode, techTrackCode, economyOption);
        }
    }

    /** 하위 호환: coveredTileCode 없이 호출 (기본 타일 전용) */
    public void acquireTileForBuilding(UUID gameId, UUID playerId, String tileCode,
                                       String techTrackCode, EconomyTrackOption economyOption) {
        acquireTileForBuilding(gameId, playerId, tileCode, techTrackCode, economyOption, null);
    }

    /**
     * 기본 기술 타일 획득
     */
    private void acquireBasicTile(UUID gameId, UUID playerId, String tileCode,
                                  String techTrackCode, EconomyTrackOption economyOption) {
        TechTileCode techTileCode;
        try {
            techTileCode = TechTileCode.valueOf(tileCode);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("알 수 없는 기술 타일 코드: " + tileCode);
        }

        GameTechOffer offer = gameTechOfferRepository.findByGameIdAndTechTileCode(gameId, techTileCode)
                .orElseThrow(() -> new IllegalStateException("해당 기술 타일이 없습니다: " + tileCode));

        // 본인 중복 소유 불가
        if (playerTechTileRepository.existsByGameIdAndPlayerIdAndTechTileCode(gameId, playerId, tileCode))
            throw new IllegalStateException("이미 보유 중인 기술 타일입니다: " + tileCode);

        // 플레이어 타일 기록
        playerTechTileRepository.save(GamePlayerTechTile.builder()
                .gameId(gameId).playerId(playerId).techTileCode(tileCode).build());

        // 트랙 결정 (COMMON/EXPANSION은 플레이어 선택 트랙 사용)
        String tileTrack = offer.getTechTrack();
        String advanceTrack;
        if ("COMMON".equals(tileTrack) || "EXPANSION".equals(tileTrack)) {
            if (techTrackCode == null || techTrackCode.isBlank())
                throw new IllegalStateException("공용/확장 타일은 트랙 코드가 필요합니다");
            advanceTrack = techTrackCode;
        } else {
            advanceTrack = tileTrack;
        }

        // 트랙 전진 + 보상
        GamePlayerState ps = gamePlayerStateRepository.findByGameIdAndPlayerId(gameId, playerId)
                .orElseThrow(() -> new IllegalStateException("플레이어 상태를 찾을 수 없습니다"));

        // 발타크: PI 건설 전 NAVIGATION 트랙 전진 스킵 (타일만 획득)
        boolean skipTrackAdvance = "NAVIGATION".equals(advanceTrack)
                && ps.getFactionType() == FactionType.BAL_TAKS
                && ps.getStockPlanetaryInstitute() > 0;

        int newLevel = getTrackLevel(ps, advanceTrack);
        // 5단계 점유 시 4→5 진입 불가 → 타일만 획득, 트랙 전진 스킵
        boolean skipLevel5Block = (newLevel == 4 && isTrackLevel5Occupied(gameId, playerId, advanceTrack));
        if (!skipTrackAdvance && !skipLevel5Block) {
            String fieldName = trackCodeToFieldName(advanceTrack);
            ps.advanceTechTrackNoKnowledge(fieldName);

            newLevel = getTrackLevel(ps, advanceTrack);
            applyTechTrackReward(ps, advanceTrack, newLevel, economyOption);

            // 라운드 점수 타일
            Game tileGame = gameRepository.findById(gameId).orElse(null);
            if (tileGame != null && "PLAYING".equals(tileGame.getGamePhase()) && tileGame.getCurrentRound() != null) {
                roundScoringService.award(gameId, tileGame.getCurrentRound(), ps, RoundScoringEvent.RESEARCH_ADVANCED, 1);
            }
        }

        // 즉발(IMMEDIATE) 효과 적용
        TechAbility ability = techTileCode.getAbility();
        if (ability.getType() == TechAbilityType.IMMEDIATE) {
            applyImmediateTileEffect(gameId, playerId, ps, techTileCode, ability);
        }

        gamePlayerStateRepository.save(ps);
        log.info("[기본 타일 획득] game={}, player={}, tile={}, track={}, newLevel={}",
                gameId, playerId, tileCode, advanceTrack, newLevel);
    }

    /**
     * 고급 기술 타일 획득 (ADV_TILE_*)
     * - 연방 토큰 플립 필수
     * - 기본 타일 커버 필수 (플레이어 선택)
     * - COMMON 고급 타일: 게임 조건(VP_25/FLEET_3) 충족 필수
     * - 일반 고급 타일: 해당 트랙 레벨 4 이상 필수
     */
    private void acquireAdvancedTile(UUID gameId, UUID playerId, String tileCode,
                                     String techTrackCode, EconomyTrackOption economyOption,
                                     String coveredTileCode) {
        // 1. 고급 타일 코드 파싱
        AdvancedTechTileCode advTileCode;
        try {
            advTileCode = AdvancedTechTileCode.valueOf(tileCode);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("알 수 없는 고급 기술 타일 코드: " + tileCode);
        }

        // 2. 보드에서 고급 타일 조회
        GameAdvTechOffer advOffer = gameAdvTechOfferRepository.findByGameIdAndAdvTechTileCode(gameId, advTileCode)
                .orElseThrow(() -> new IllegalStateException("해당 고급 기술 타일이 보드에 없습니다: " + tileCode));

        if (advOffer.getTakenByPlayerId() != null)
            throw new IllegalStateException("이미 가져간 고급 기술 타일입니다: " + tileCode);

        // 3. 본인 중복 소유 불가
        if (playerTechTileRepository.existsByGameIdAndPlayerIdAndTechTileCode(gameId, playerId, tileCode))
            throw new IllegalStateException("이미 보유 중인 기술 타일입니다: " + tileCode);

        // 4. 조건 검증
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new IllegalStateException("게임을 찾을 수 없습니다"));
        GamePlayerState ps = gamePlayerStateRepository.findByGameIdAndPlayerId(gameId, playerId)
                .orElseThrow(() -> new IllegalStateException("플레이어 상태를 찾을 수 없습니다"));

        String offerTrack = advOffer.getTechTrack();
        if ("COMMON".equals(offerTrack)) {
            // COMMON 고급 타일: 게임 조건 체크
            validateCommonAdvTileCondition(game, ps, gameId, playerId);
        } else {
            // 일반 고급 타일: 해당 트랙 레벨 4 이상
            int trackLevel = getTrackLevel(ps, offerTrack);
            if (trackLevel < 4) {
                throw new IllegalStateException("고급 기술 타일 획득에는 해당 트랙 레벨 4 이상이 필요합니다 (현재: " + trackLevel + ")");
            }
        }

        // 5. 연방 토큰 플립
        if (!flipUsableFederationToken(gameId, playerId)) {
            throw new IllegalStateException("고급 기술 타일 획득에는 사용 가능한 연방 토큰이 필요합니다");
        }

        // 6. 기본 타일 커버 (필수)
        if (coveredTileCode == null || coveredTileCode.isBlank()) {
            throw new IllegalStateException("고급 기술 타일 획득 시 덮을 기본 타일을 선택해야 합니다");
        }
        GamePlayerTechTile coverTarget = playerTechTileRepository
                .findByGameIdAndPlayerIdAndTechTileCode(gameId, playerId, coveredTileCode)
                .orElseThrow(() -> new IllegalStateException("덮을 기본 타일을 보유하고 있지 않습니다: " + coveredTileCode));
        if (Boolean.TRUE.equals(coverTarget.getIsCovered())) {
            throw new IllegalStateException("이미 덮인 기본 타일입니다: " + coveredTileCode);
        }
        coverTarget.cover(tileCode);
        playerTechTileRepository.save(coverTarget);
        log.info("[타일 커버] player={}, covered={}, by={}", playerId, coveredTileCode, tileCode);

        // 7. 고급 타일 점유 처리
        advOffer.take(playerId);
        gameAdvTechOfferRepository.save(advOffer);

        // 8. 플레이어 타일 기록
        playerTechTileRepository.save(GamePlayerTechTile.builder()
                .gameId(gameId).playerId(playerId).techTileCode(tileCode).build());

        // 9. 트랙 결정 (모든 고급 타일은 플레이어가 원하는 트랙으로 전진)
        if (techTrackCode == null || techTrackCode.isBlank())
            throw new IllegalStateException("고급 타일 획득 시 전진할 트랙 코드가 필요합니다");
        String advanceTrack = techTrackCode;

        // 10. 트랙 전진 + 보상 (발타크: PI 건설 전 NAVIGATION 스킵)
        boolean skipAdvTrack = "NAVIGATION".equals(advanceTrack)
                && ps.getFactionType() == FactionType.BAL_TAKS
                && ps.getStockPlanetaryInstitute() > 0;

        int newLevel = getTrackLevel(ps, advanceTrack);
        boolean skipAdvLevel5Block = (newLevel == 4 && isTrackLevel5Occupied(gameId, playerId, advanceTrack));
        if (!skipAdvTrack && !skipAdvLevel5Block) {
            String fieldName = trackCodeToFieldName(advanceTrack);
            ps.advanceTechTrackNoKnowledge(fieldName);

            newLevel = getTrackLevel(ps, advanceTrack);
            applyTechTrackReward(ps, advanceTrack, newLevel, economyOption);

            // 12. 라운드 점수 타일
            if ("PLAYING".equals(game.getGamePhase()) && game.getCurrentRound() != null) {
                roundScoringService.award(gameId, game.getCurrentRound(), ps, RoundScoringEvent.RESEARCH_ADVANCED, 1);
            }
        }

        // 11. 고급 타일 즉발(IMMEDIATE) 효과 적용
        TechAbility ability = advTileCode.getAbility();
        if (ability.getType() == TechAbilityType.IMMEDIATE) {
            applyImmediateAdvTileEffect(gameId, playerId, ps, advTileCode, ability);
        }

        gamePlayerStateRepository.save(ps);
        log.info("[고급 타일 획득] game={}, player={}, tile={}, track={}, newLevel={}, covered={}",
                gameId, playerId, tileCode, advanceTrack, newLevel, coveredTileCode);
    }

    /** COMMON 고급 타일 조건 검증 (VP_25 또는 FLEET_3) */
    private void validateCommonAdvTileCondition(Game game, GamePlayerState ps, UUID gameId, UUID playerId) {
        CommonAdvTileConditionType condition = game.getCommonAdvTileCondition();
        if (condition == null) {
            throw new IllegalStateException("COMMON 고급 타일 조건이 설정되지 않았습니다");
        }
        switch (condition) {
            case VP_25 -> {
                if (ps.getVictoryPoints() < 25) {
                    throw new IllegalStateException("COMMON 고급 타일 획득 조건 미충족: 현재 VP " + ps.getVictoryPoints() + " (25점 이상 필요)");
                }
            }
            case FLEET_3 -> {
                long fleetCount = fleetProbeRepository.countByGameIdAndPlayerId(gameId, playerId);
                if (fleetCount < 3) {
                    throw new IllegalStateException("COMMON 고급 타일 획득 조건 미충족: 현재 함대 " + fleetCount + "개 (3개 이상 필요)");
                }
            }
        }
    }

    /** 고급 타일 즉발 효과 적용 (기존 applyImmediateTileEffect와 유사하지만 AdvancedTechTileCode 사용) */
    private void applyImmediateAdvTileEffect(UUID gameId, UUID playerId, GamePlayerState ps,
                                              AdvancedTechTileCode advTileCode, TechAbility ability) {
        String effect = ability.getSpecialEffect();
        if (effect == null) return;

        int vp = 0;
        switch (effect) {
            case "VP_PER_SECTOR_BUILDING" -> {
                long count = countSectorsWithBuildings(gameId, playerId);
                vp = (int) count * 2;
            }
            case "ORE_PER_SECTOR_BUILDING" -> {
                long count = countSectorsWithBuildings(gameId, playerId);
                ps.addOre((int) count);
            }
            case "VP_PER_MINE" -> {
                int mines = 8 - ps.getStockMine();
                vp = mines * 2;
            }
            case "VP_PER_TRADING_STATION" -> {
                int ts = 4 - ps.getStockTradingStation();
                vp = ts * 4;
            }
            case "VP_PER_FEDERATION_TOKEN" -> {
                vp = ps.getFederationCount() * 5;
            }
            case "VP_PER_GAIA_PLANET" -> {
                long gaiaCount = gameBuildingRepository.findByGameIdAndPlayerId(gameId, playerId).stream()
                        .filter(b -> b.getBuildingType() != BuildingType.GAIAFORMER && !b.isLantidsMine())
                        .filter(b -> {
                            var hex = gameHexRepository.findByGameIdAndHexQAndHexR(gameId, b.getHexQ(), b.getHexR());
                            return hex.isPresent() && "GAIA".equals(hex.get().getPlanetType());
                        }).count();
                vp = (int) gaiaCount * 2;
            }
            case "VP_BIG" -> {
                int bigBuildings = (1 - ps.getStockPlanetaryInstitute()) + (2 - ps.getStockAcademy());
                vp = bigBuildings * 6;
            }
            case "PER_LOST_PLANET_VP4" -> {
                // 건물 있는 깊은 구역(DEEP_ 섹터) 수당 4VP
                Set<String> deepSectors = new HashSet<>();
                for (var b : gameBuildingRepository.findByGameIdAndPlayerId(gameId, playerId)) {
                    if (b.getBuildingType() == BuildingType.GAIAFORMER || b.isLantidsMine()) continue;
                    gameHexRepository.findByGameIdAndHexQAndHexR(gameId, b.getHexQ(), b.getHexR())
                            .ifPresent(h -> {
                                if (h.getSectorId() != null && h.getSectorId().startsWith("DEEP_")) {
                                    deepSectors.add(h.getSectorId());
                                }
                            });
                }
                vp = deepSectors.size() * 4;
            }
        }

        if (vp > 0) {
            ps.addVP(vp);
            Game vpGame = gameRepository.findById(gameId).orElse(null);
            Integer round = vpGame != null ? vpGame.getCurrentRound() : null;
            vpLogService.logVp(gameId, playerId, VpCategory.ADV_TECH_TILE, vp, round,
                    advTileCode.name() + " 즉발 효과");
            log.info("[고급타일 즉발] player={}, tile={}, effect={}, vp={}", playerId, advTileCode, effect, vp);
        }
    }

    /** 트랙 레벨 조회 헬퍼 */
    private int getTrackLevel(GamePlayerState ps, String trackCode) {
        return switch (trackCode) {
            case "TERRA_FORMING" -> ps.getTechTerraforming();
            case "NAVIGATION"    -> ps.getTechNavigation();
            case "AI"            -> ps.getTechAi();
            case "GAIA_FORMING"  -> ps.getTechGaia();
            case "ECONOMY"       -> ps.getTechEconomy();
            case "SCIENCE"       -> ps.getTechScience();
            default -> 0;
        };
    }

    /** 건물이 있는 일반 섹터 수 카운트 (DEEP_ 제외) */
    private long countSectorsWithBuildings(UUID gameId, UUID playerId) {
        Set<String> sectors = new HashSet<>();
        for (var b : gameBuildingRepository.findByGameIdAndPlayerId(gameId, playerId)) {
            if (b.getBuildingType() == BuildingType.GAIAFORMER || b.isLantidsMine()) continue;
            gameHexRepository.findByGameIdAndHexQAndHexR(gameId, b.getHexQ(), b.getHexR())
                    .ifPresent(h -> {
                        if (h.getSectorId() != null && !h.getSectorId().startsWith("DEEP_")) {
                            sectors.add(h.getSectorId());
                        }
                    });
        }
        return sectors.size();
    }

    /**
     * IMMEDIATE 타입 기술 타일 즉발 효과 적용
     */
    private void applyImmediateTileEffect(UUID gameId, UUID playerId, GamePlayerState ps,
                                           TechTileCode tileCode, TechAbility ability) {
        String specialEffect = ability.getSpecialEffect();

        if (specialEffect == null) {
            // 특수 효과 없으면 일반 자원 적용 (QIC, 광석, 크레딧, VP 등)
            ability.applyTo(ps);
            if (ability.getVpGain() != null && ability.getVpGain() > 0) {
                vpLogService.logVp(gameId, playerId, VpCategory.TECH_TILE, ability.getVpGain(), null, tileCode + " 즉시 VP");
            }
            log.info("[TILE_IMMEDIATE] 자원 즉발 적용: {}", tileCode);
            return;
        }

        switch (specialEffect) {
            case "KNOWLEDGE_PER_PLANET_TYPE" -> {
                // 플레이어 건물이 있는 행성 종류 수만큼 지식 획득
                // SPACE_STATION(빈 헥스)·GAIAFORMER(TRANSDIM)은 행성이 아니므로 제외
                List<GameBuilding> buildings = gameBuildingRepository.findByGameIdAndPlayerId(gameId, playerId);
                Set<String> planetTypes = new HashSet<>();
                for (GameBuilding b : buildings) {
                    if (b.getBuildingType() == BuildingType.SPACE_STATION
                            || b.getBuildingType() == BuildingType.GAIAFORMER
                            || b.isLantidsMine()) continue;
                    gameHexRepository.findByGameIdAndHexQAndHexR(gameId, b.getHexQ(), b.getHexR())
                            .map(GameHex::getPlanetType)
                            .ifPresent(pt -> {
                                if (pt != null && pt != PlanetType.EMPTY && pt != PlanetType.TRANSDIM) planetTypes.add(pt.name());
                            });
                }
                // 인공물 가상 행성 종류
                if (playerArtifactRepository.existsByGameIdAndPlayerIdAndArtifactType(gameId, playerId, "ARTIFACT_7")) planetTypes.add("ASTEROIDS");
                if (playerArtifactRepository.existsByGameIdAndPlayerIdAndArtifactType(gameId, playerId, "ARTIFACT_8")) planetTypes.add("LOST_PLANET");
                ps.addKnowledge(planetTypes.size());
                log.info("[TILE_IMMEDIATE] KNOWLEDGE_PER_PLANET_TYPE: 행성 종류={}, 지식+={}", planetTypes.size(), planetTypes.size());
            }
            case "TERRAFORM_2_PLACE_MINE" -> {
                // FE에서 pending 체인으로 처리 (업그레이드 확정 → 광산 배치 → 확정)
                log.info("[TILE_IMMEDIATE] TERRAFORM_2_PLACE_MINE - FE pending 체인 처리, game={}, player={}", gameId, playerId);
            }
            case "VP_PER_FEDERATION_TOKEN" -> {
                int tokenCount = federationGroupRepository.findByGameIdAndPlayerId(gameId, playerId).size();
                int vp = tokenCount * 5;
                if (vp > 0) {
                    ps.addVP(vp);
                    vpLogService.logVp(gameId, playerId, VpCategory.ADV_TECH_TILE, vp, null, "연방 토큰당 5VP (" + tokenCount + "개)");
                }
                log.info("[TILE_IMMEDIATE] VP_PER_FEDERATION_TOKEN: tokens={}, vp={}", tokenCount, vp);
            }
            case "VP_PER_GAIA_PLANET" -> {
                long gaiaCount = gameBuildingRepository.findByGameIdAndPlayerId(gameId, playerId).stream()
                        .filter(b -> b.getBuildingType() != BuildingType.GAIAFORMER && !b.isLantidsMine())
                        .filter(b -> {
                            var hex = gameHexRepository.findByGameIdAndHexQAndHexR(gameId, b.getHexQ(), b.getHexR()).orElse(null);
                            return hex != null && hex.getPlanetType() == com.gaiaproject.domain.enumtype.player.PlanetType.GAIA;
                        }).count();
                int vp = (int) gaiaCount * 3;
                if (vp > 0) {
                    ps.addVP(vp);
                    vpLogService.logVp(gameId, playerId, VpCategory.ADV_TECH_TILE, vp, null, "가이아 행성당 3VP (" + gaiaCount + "개)");
                }
                log.info("[TILE_IMMEDIATE] VP_PER_GAIA_PLANET: gaia={}, vp={}", gaiaCount, vp);
            }
            case "VP_PER_SECTOR_BUILDING" -> {
                Set<String> sectors = new HashSet<>();
                for (var b : gameBuildingRepository.findByGameIdAndPlayerId(gameId, playerId)) {
                    if (b.getBuildingType() == BuildingType.GAIAFORMER || b.isLantidsMine()) continue;
                    gameHexRepository.findByGameIdAndHexQAndHexR(gameId, b.getHexQ(), b.getHexR())
                            .ifPresent(h -> {
                                if (h.getSectorId() != null && !h.getSectorId().startsWith("DEEP_")) {
                                    sectors.add(h.getSectorId());
                                }
                            });
                }
                int vp = sectors.size() * 2;
                if (vp > 0) {
                    ps.addVP(vp);
                    vpLogService.logVp(gameId, playerId, VpCategory.ADV_TECH_TILE, vp, null, "섹터당 2VP (" + sectors.size() + "개)");
                }
                log.info("[TILE_IMMEDIATE] VP_PER_SECTOR_BUILDING: sectors={}, vp={}", sectors.size(), vp);
            }
            case "VP_PER_MINE" -> {
                long mineCount = gameBuildingRepository.findByGameIdAndPlayerId(gameId, playerId).stream()
                        .filter(b -> b.getBuildingType() == com.gaiaproject.domain.enumtype.building.BuildingType.MINE).count();
                int vp = (int) mineCount * 2;
                if (vp > 0) {
                    ps.addVP(vp);
                    vpLogService.logVp(gameId, playerId, VpCategory.ADV_TECH_TILE, vp, null, "광산당 2VP (" + mineCount + "개)");
                }
                log.info("[TILE_IMMEDIATE] VP_PER_MINE: mines={}, vp={}", mineCount, vp);
            }
            case "VP_PER_TRADING_STATION" -> {
                long tsCount = gameBuildingRepository.findByGameIdAndPlayerId(gameId, playerId).stream()
                        .filter(b -> b.getBuildingType() == com.gaiaproject.domain.enumtype.building.BuildingType.TRADING_STATION).count();
                int vp = (int) tsCount * 4;
                if (vp > 0) {
                    ps.addVP(vp);
                    vpLogService.logVp(gameId, playerId, VpCategory.ADV_TECH_TILE, vp, null, "교역소당 4VP (" + tsCount + "개)");
                }
                log.info("[TILE_IMMEDIATE] VP_PER_TRADING_STATION: ts={}, vp={}", tsCount, vp);
            }
            case "VP_PER_TERRAFORMING_STEP" -> {
                int vp = ps.getTechTerraforming() * 2;
                if (vp > 0) {
                    ps.addVP(vp);
                    vpLogService.logVp(gameId, playerId, VpCategory.ADV_TECH_TILE, vp, null, "테라포밍 단계당 2VP (" + ps.getTechTerraforming() + "단계)");
                }
                log.info("[TILE_IMMEDIATE] VP_PER_TERRAFORMING_STEP: level={}, vp={}", ps.getTechTerraforming(), vp);
            }
            default -> {
                ability.applyTo(ps);
                log.info("[TILE_IMMEDIATE] 즉발 적용 (specialEffect={}): {}", specialEffect, tileCode);
            }
        }
    }

    /**
     * ACTION 타입 기술 타일 사용 (라운드당 1회)
     * - BASIC_TILE_1: 파워 4 차징
     * - ADV 타일 ACTION 계열: 광석 3, 지식 3, QIC 1+크레딧 5
     */
    public ConfirmActionResponse useTechTileAction(UUID gameId, UUID playerId, String tileCode) {
        GamePlayerState ps = gamePlayerStateRepository.findByGameIdAndPlayerId(gameId, playerId)
                .orElseThrow(() -> new IllegalStateException("플레이어 상태를 찾을 수 없습니다"));

        // 1. 플레이어 소유 타일 확인
        GamePlayerTechTile playerTile = playerTechTileRepository
                .findByGameIdAndPlayerIdAndTechTileCode(gameId, playerId, tileCode)
                .orElseThrow(() -> new IllegalStateException("해당 기술 타일을 보유하지 않습니다: " + tileCode));

        if (Boolean.TRUE.equals(playerTile.getIsCovered())) throw new IllegalStateException("덮인 기술 타일은 사용할 수 없습니다");
        if (playerTile.isActionUsed()) throw new IllegalStateException("이미 이번 라운드에 사용한 액션 타일입니다");

        // 2. ACTION 타입 확인 및 효과 적용
        String specialEffect = resolveActionEffect(tileCode);
        if (specialEffect == null) throw new IllegalStateException("액션 타일이 아닙니다: " + tileCode);

        switch (specialEffect) {
            case "CHARGE_POWER_4"      -> ps.chargePowerWithFactionRules(4);
            case "ACTION_ORE_3"        -> ps.addOre(3);
            case "ACTION_KNOWLEDGE_3"  -> ps.addKnowledge(3);
            case "ACTION_QIC_1_CREDIT_5" -> { ps.addQic(1); ps.addCredit(5); }
            default -> throw new IllegalStateException("알 수 없는 액션 효과: " + specialEffect);
        }

        playerTile.useAction();
        playerTechTileRepository.save(playerTile);
        gamePlayerStateRepository.save(ps);

        log.info("[TECH_ACTION] game={}, player={}, tile={}, effect={}", gameId, playerId, tileCode, specialEffect);

        return actionService.saveActionAndNextTurn(gameId, playerId, ActionType.TECH_TILE_ACTION,
                String.format("{\"tileCode\":\"%s\",\"actionEffect\":\"%s\"}", tileCode, specialEffect));
    }

    /** 타일 코드로 ACTION specialEffect 문자열 조회 */
    private String resolveActionEffect(String tileCode) {
        // 기본 타일
        try {
            TechTileCode basic = TechTileCode.valueOf(tileCode);
            TechAbility ability = basic.getAbility();
            if (ability.getType() == TechAbilityType.ACTION) return ability.getSpecialEffect();
            return null;
        } catch (IllegalArgumentException ignored) {}
        // 고급 타일
        try {
            AdvancedTechTileCode adv = AdvancedTechTileCode.valueOf(tileCode);
            TechAbility ability = adv.getAbility();
            if (ability.getType() == TechAbilityType.ACTION) return ability.getSpecialEffect();
            return null;
        } catch (IllegalArgumentException ignored) {}
        return null;
    }

    /** 트랙 코드 → GamePlayerState 필드명 변환 */
    private String trackCodeToFieldName(String trackCode) {
        return switch (trackCode) {
            case "TERRA_FORMING" -> "techTerraforming";
            case "NAVIGATION"    -> "techNavigation";
            case "AI"            -> "techAi";
            case "GAIA_FORMING"  -> "techGaia";
            case "ECONOMY"       -> "techEconomy";
            case "SCIENCE"       -> "techScience";
            default -> throw new IllegalArgumentException("알 수 없는 트랙 코드: " + trackCode);
        };
    }

    /**
     * 게임에 사용할 기술 타일 초기화
     * @param gameId 게임 ID
     */
    public void setupTechTiles(UUID gameId) {
        // 1. 셔플 된 기본 타일 9개
        List<TechTileCode> basicTiles = TechTileCode.getBasicTiles();
        List<TechCategoryType> tracks = new ArrayList<>(TechCategoryType.getList());

        // 2. 기본 타일 1~9 위치에 배치
        int position = 1;
        int trackType = 0;
        int expansionTrackType = 7;

        for (TechTileCode tile : basicTiles) {
            gameTechOfferRepository.save(
                    GameTechOffer.builder()
                            .gameId(gameId)
                            .position(position++)
                            .techTrack(tracks.get(trackType).name())
                            .techTileCode(tile)
                            .build()
            );

            if (trackType < 6) {  // 0~5까지만 증가, 6(COMMON)에서 멈춤
                trackType++;
            }
        }

        // 3. 확장 타일 10~12 배치
        List<TechTileCode> expansionTiles = TechTileCode.getExpansionTiles();

        for (TechTileCode tile : expansionTiles) {
            gameTechOfferRepository.save(
                    GameTechOffer.builder()
                            .gameId(gameId)
                            .position(position++)
                            .techTrack(tracks.get(expansionTrackType).name())  // EXPANSION (고정)
                            .techTileCode(tile)
                            .build()
            );
        }

         /* 4. 고급 기술 타일 1~7 배치 */
        int advPosition = 1;
        List<AdvancedTechTileCode> advancedTiles = AdvancedTechTileCode.getRandomTile();

        for (int i = 0; i < 7; i++) {  // 7개만 선택
            gameAdvTechOfferRepository.save(
                    GameAdvTechOffer.builder()
                            .gameId(gameId)
                            .position(advPosition++)
                            .techTrack(tracks.get(i).name())  // TERRA_FORMING ~ SCIENCE
                            .advTechTileCode(advancedTiles.get(i))
                            .build()
            );
        }
    }

    /**
     * 게임의 기술 타일 목록 조회
     *
     * @param gameId 게임 ID
     * @return 기술 타일 목록 (위치 순서대로)
     */
    public List<GameTechOffer> getTechTiles(UUID gameId) {
        return gameTechOfferRepository.findByGameIdOrderByPosition(gameId);
    }

    /** 이번 라운드 ACTION 사용 완료된 (playerId:tileCode) Set (플레이어별 개인 추적) */
    public Set<String> getActionUsedPlayerTileCodes(UUID gameId) {
        return playerTechTileRepository.findByGameId(gameId).stream()
                .filter(GamePlayerTechTile::isActionUsed)
                .map(pt -> pt.getPlayerId().toString() + ":" + pt.getTechTileCode())
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * 특정 위치의 기술 타일 조회
     *
     * @param gameId 게임 ID
     * @
     *               param position 위치 (1~9)
     * @return 기술 타일
     */
    public GameTechOffer getTechTileByPosition(UUID gameId, int position) {
        return gameTechOfferRepository.findByGameIdOrderByPosition(gameId)
                .stream()
                .filter(offer -> offer.getPosition().equals(position))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "해당 위치의 기술 타일을 찾을 수 없습니다. position: " + position));
    }

    /**
     * 게임의 기술 타일 초기화 (재설정)
     *
     * @param gameId 게임 ID
     * @param includeExpansion 확장팩 포함 여부
     */
    public void resetTechTiles(UUID gameId, boolean includeExpansion) {
        // 기존 타일 삭제
        gameTechOfferRepository.deleteByGameId(gameId);

        // 새로 설정
        setupTechTiles(gameId);
    }

    /**
     * 게임의 고급 기술 타일 목록 조회
     *
     * @param gameId 게임 ID
     * @return 고급 기술 타일 목록 (위치 순서대로)
     */
    public List<GameAdvTechOffer> getAdvancedTechTiles(UUID gameId) {
        return gameAdvTechOfferRepository.findByGameIdOrderByPosition(gameId);
    }

    /**
     * 지식 트랙 전진 시 즉각 보상 적용 (레벨 1~4)
     * - 테라포밍: 1→+2광석, 2→T2(패시브), 3→T1(패시브), 4→+2광석
     * - 거리:     1→+1QIC, 2→거리2(패시브), 3→+1QIC, 4→거리3(패시브)
     * - AI:       1→+1QIC, 2→+1QIC, 3→+2QIC, 4→+2QIC
     * - 가이아:   1→가이아포머+1, 2→파워토큰+3(bowl1), 3→가이아포머+1, 4→가이아포머+1
     * - 수입:     1→2돈+1파워순환, 2→1광+2돈+2파워순환, 3/4→옵션A/B에 따라 다름
     * - 지식:     1~4→지식수입(라운드 수입 단계 처리, 즉각 보상 없음)
     */
    public void applyTechTrackReward(GamePlayerState ps, String trackCode, int newLevel, EconomyTrackOption economyOption) {
        // 5트랙 진입: 사용 가능한 연방 토큰 1개 뒤집기 필요
        if (newLevel == 5) {
            if (!flipUsableFederationToken(ps.getGameId(), ps.getPlayerId())) {
                throw new IllegalStateException("5단계 진입에는 사용 가능한 연방 토큰이 필요합니다");
            }
            // 테라포밍 5단계: 해당 칸의 연방 타일 획득
            if ("TERRA_FORMING".equals(trackCode)) {
                var offerOpt = federationOfferRepository.findByGameIdAndPosition(ps.getGameId(), 0);
                if (offerOpt.isPresent()) {
                    var offer = offerOpt.get();
                    if (offer.getQuantity() > 0) {
                        offer.decreaseQuantity();
                        federationOfferRepository.save(offer);
                        // 플레이어 연방 토큰 기록
                        playerFederationTokenRepository.save(
                                com.gaiaproject.domain.entity.player.GamePlayerFederationToken.builder()
                                        .gameId(ps.getGameId()).playerId(ps.getPlayerId())
                                        .federationTileType(offer.getFederationTileType()).build());
                        // 즉시 보상 적용
                        ps.applyIncome(offer.getFederationTileType().getImmediateReward());
                        if (offer.getFederationTileType().getImmediateReward().vp() > 0) {
                            vpLogService.logVp(ps.getGameId(), ps.getPlayerId(), VpCategory.FEDERATION_TOKEN, offer.getFederationTileType().getImmediateReward().vp(), null, "5단계 연방 토큰: " + offer.getFederationTileType().name());
                        }
                        log.info("[TECH_LV5_TERRA] 연방 타일 획득: player={}, tile={}", ps.getPlayerId(), offer.getFederationTileType());
                    }
                }
            }
            // 라운드 점수: 연방 토큰 획득 (테라포밍 5단계)
            if ("TERRA_FORMING".equals(trackCode)) {
                var game = gameRepository.findById(ps.getGameId()).orElse(null);
                if (game != null && game.getCurrentRound() != null) {
                    roundScoringService.award(ps.getGameId(), game.getCurrentRound(), ps,
                            com.gaiaproject.domain.enumtype.rounds.RoundScoringEvent.FEDERATION_FORMED, 1);
                }
            }
            // 5단계 즉시 보상 (연방 토큰 뒤집기 이후)
            switch (trackCode) {
                case "AI" -> ps.addQic(4);
                case "GAIA_FORMING" -> {
                    // 가이아 5단계: 가이아 땅 갯수당 1VP + 4VP
                    long gaiaCount = gameBuildingRepository.findByGameIdAndPlayerId(ps.getGameId(), ps.getPlayerId()).stream()
                            .filter(b -> b.getBuildingType() != BuildingType.GAIAFORMER && !b.isLantidsMine())
                            .filter(b -> {
                                var hex = gameHexRepository.findByGameIdAndHexQAndHexR(ps.getGameId(), b.getHexQ(), b.getHexR()).orElse(null);
                                return hex != null && hex.getPlanetType() == com.gaiaproject.domain.enumtype.player.PlanetType.GAIA;
                            }).count();
                    int gaiaVp = (int) gaiaCount + 4;
                    ps.addVP(gaiaVp);
                    vpLogService.logVp(ps.getGameId(), ps.getPlayerId(), VpCategory.TECH_TILE, gaiaVp, null, "가이아 5단계: 가이아 땅 " + gaiaCount + "개 + 4VP");
                    log.info("[TECH_LV5_GAIA] 가이아 땅 {}개 × 1VP + 4VP = {}VP", gaiaCount, gaiaVp);
                }
                case "ECONOMY" -> {
                    // 경제 5단계: 수입 사라지고 즉시 6크레딧 + 3광석 + 6파워순환
                    ps.addCredit(6);
                    ps.addOre(3);
                    ps.chargePowerWithFactionRules(6);
                }
                case "SCIENCE" -> {
                    // 지식 5단계: 수입 사라지고 즉시 9지식
                    ps.addKnowledge(9);
                }
                default -> {} // TERRA_FORMING은 연방 타일 위에서 처리, NAVIGATION은 검은행성(별도)
            }
            log.info("[TECH_LV5] 연방 토큰 뒤집기 + 5단계 보상 완료: player={}, track={}", ps.getPlayerId(), trackCode);
            return;
        }
        if (newLevel < 1 || newLevel > 4) return;

        // 모든 트랙 공통: 2→3 전진 시 3파워 순환
        if (newLevel == 3) {
            ps.chargePowerWithFactionRules(3);
        }

        switch (trackCode) {
            case "TERRA_FORMING" -> {
                if (newLevel == 1 || newLevel == 4) ps.addOre(2);
                // 레벨 2(T2), 3(T1)은 테라포밍 비용 패시브 변경 → 레벨 자체로 반영
            }
            case "NAVIGATION" -> {
                if (newLevel == 1 || newLevel == 3) ps.addQic(1);
                // 레벨 2(거리2), 4(거리3)는 패시브 → 레벨 자체로 반영
            }
            case "AI" -> {
                int qic = (newLevel <= 2) ? 1 : 2;
                ps.addQic(qic);
            }
            case "GAIA_FORMING" -> {
                if (newLevel == 1 || newLevel == 3 || newLevel == 4) {
                    ps.addGaiaformer(1);
                } else if (newLevel == 2) {
                    ps.addPowerToken(3);
                }
            }
            // ECONOMY: 수입 트랙이므로 즉시 보상 없음 (라운드 수입 단계에서 처리)
            case "ECONOMY" -> {}
            // SCIENCE: 지식 수입은 라운드 수입 단계에서 처리 (즉각 보상 없음)
        }

        log.info("[TECH_REWARD] player={}, track={}, level={}", ps.getPlayerId(), trackCode, newLevel);
    }

    /** 해당 트랙 5단계에 다른 플레이어가 이미 있는지 확인 (본인이 4→5 진입 시도 시 체크) */
    private boolean isTrackLevel5Occupied(UUID gameId, UUID playerId, String trackCode) {
        List<GamePlayerState> allPlayers = gamePlayerStateRepository.findByGameId(gameId);
        for (var ps : allPlayers) {
            if (ps.getPlayerId().equals(playerId)) continue;
            int level = switch (trackCode) {
                case "TERRA_FORMING" -> ps.getTechTerraforming();
                case "NAVIGATION"    -> ps.getTechNavigation();
                case "AI"            -> ps.getTechAi();
                case "GAIA_FORMING"  -> ps.getTechGaia();
                case "ECONOMY"       -> ps.getTechEconomy();
                case "SCIENCE"       -> ps.getTechScience();
                default -> 0;
            };
            if (level >= 5) return true;
        }
        return false;
    }

    /**
     * 사용 가능한 연방 토큰 1개 뒤집기 (고급 기술타일 획득 / 5트랙 진입 시 필요)
     * 연방 그룹 토큰 또는 직접 보유 토큰 중 useFederation=true이고 아직 사용 안 한 것을 뒤집음.
     * @return true if flipped, false if no usable token
     */
    public boolean flipUsableFederationToken(UUID gameId, UUID playerId) {
        log.info("[FED_FLIP] 시도: player={}", playerId);
        // 1. 연방 그룹에서 사용 가능한 토큰 찾기
        var groups = federationGroupRepository.findByGameIdAndPlayerId(gameId, playerId);
        log.info("[FED_FLIP] 그룹 수: {}", groups.size());
        for (var g : groups) {
            if (!g.isUsed()) {
                try {
                    var tileType = com.gaiaproject.domain.enumtype.federation.FederationTileType.valueOf(g.getFederationTileCode());
                    if (tileType.isUseFederation()) {
                        g.markUsed();
                        federationGroupRepository.save(g);
                        log.info("[FED_FLIP] 연방 그룹 토큰 뒤집기: player={}, tile={}", playerId, g.getFederationTileCode());
                        return true;
                    }
                } catch (IllegalArgumentException ignored) {}
            }
        }

        // 2. 직접 보유 토큰에서 사용 가능한 것 찾기 (글린 PI 등)
        var tokens = playerFederationTokenRepository.findByGameIdAndPlayerId(gameId, playerId);
        log.info("[FED_FLIP] 직접 보유 토큰 수: {}", tokens.size());
        for (var t : tokens) {
            log.info("[FED_FLIP] 토큰: type={}, used={}, useFed={}", t.getFederationTileType(), t.isUsed(), t.getFederationTileType().isUseFederation());
            if (!t.isUsed() && t.getFederationTileType().isUseFederation()) {
                t.markUsed();
                playerFederationTokenRepository.save(t);
                log.info("[FED_FLIP] 직접 보유 토큰 뒤집기: player={}, tile={}", playerId, t.getFederationTileType());
                return true;
            }
        }

        log.warn("[FED_FLIP] 실패: 사용 가능한 토큰 없음. player={}", playerId);
        return false;
    }

    /**
     * 플레이어가 사용 가능한(뒤집을 수 있는) 연방 토큰을 보유하고 있는지 확인
     */
    public boolean hasUsableFederationToken(UUID gameId, UUID playerId) {
        var groups = federationGroupRepository.findByGameIdAndPlayerId(gameId, playerId);
        for (var g : groups) {
            if (!g.isUsed()) {
                try {
                    var tileType = com.gaiaproject.domain.enumtype.federation.FederationTileType.valueOf(g.getFederationTileCode());
                    if (tileType.isUseFederation()) return true;
                } catch (IllegalArgumentException ignored) {}
            }
        }
        var tokens = playerFederationTokenRepository.findByGameIdAndPlayerId(gameId, playerId);
        for (var t : tokens) {
            if (!t.isUsed() && t.getFederationTileType().isUseFederation()) return true;
        }
        return false;
    }
}