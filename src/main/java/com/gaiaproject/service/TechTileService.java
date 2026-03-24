package com.gaiaproject.service;

import com.gaiaproject.domain.entity.building.GameBuilding;
import com.gaiaproject.domain.entity.game.Game;
import com.gaiaproject.domain.entity.map.GameHex;
import com.gaiaproject.domain.entity.player.GamePlayerState;
import com.gaiaproject.domain.entity.tech.GameAdvTechOffer;
import com.gaiaproject.domain.entity.tech.GameTechOffer;
import com.gaiaproject.domain.enumtype.action.ActionType;
import com.gaiaproject.domain.enumtype.rounds.RoundScoringEvent;
import com.gaiaproject.domain.enumtype.tech.AdvancedTechTileCode;
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
@Transactional
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
    private final GameWebSocketService webSocketService;

    /** 지식 트랙 전진 (지식 4 소모, PLAYING 페이즈) */
    public AdvanceTechResponse advanceTechTrack(UUID gameId, AdvanceTechRequest request) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new IllegalArgumentException("게임을 찾을 수 없습니다"));

        if (!"PLAYING".equals(game.getGamePhase())) {
            return AdvanceTechResponse.fail(gameId, "PLAYING 페이즈가 아닙니다");
        }

        GamePlayerState playerState = gamePlayerStateRepository.findByGameIdAndPlayerId(gameId, request.playerId())
                .orElseThrow(() -> new IllegalStateException("플레이어 상태를 찾을 수 없습니다"));

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
        ConfirmActionResponse actionResult = actionService.saveActionAndNextTurn(gameId, request.playerId(), ActionType.ADVANCE_TECH, actionData);

        return AdvanceTechResponse.success(gameId, request.trackCode(), newLevel,
                actionResult.nextTurnSeatNo() != null ? actionResult.nextTurnSeatNo() : 0);
    }

    /**
     * 교역소/아카데미 건설 시 기술 타일 획득 (지식 소모 없음, 트랙 1칸 전진)
     * - 트랙 고유 타일: 해당 트랙 자동 전진
     * - COMMON/EXPANSION 타일: techTrackCode로 플레이어가 선택한 트랙 전진
     *
     * @param gameId 게임 ID
     * @param playerId 플레이어 ID
     * @param tileCode 획득할 기술 타일 코드 (TechTileCode 문자열)
     * @param techTrackCode COMMON 타일일 때 플레이어가 선택한 트랙 코드 (nullable)
     * @param economyOption 경제 트랙 옵션 (즉발 보상 계산용)
     * @throws IllegalStateException 유효하지 않은 타일 또는 중복 소유 시
     */
    public void acquireTileForBuilding(UUID gameId, UUID playerId, String tileCode,
                                       String techTrackCode, EconomyTrackOption economyOption) {
        log.info("[acquireTileForBuilding] 진입: game={}, player={}, tile={}, track={}", gameId, playerId, tileCode, techTrackCode);
        // 1. 타일 코드 파싱
        TechTileCode techTileCode;
        try {
            techTileCode = TechTileCode.valueOf(tileCode);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("알 수 없는 기술 타일 코드: " + tileCode);
        }

        // 2. 보드에 있는 타일 조회
        GameTechOffer offer = gameTechOfferRepository.findByGameIdAndTechTileCode(gameId, techTileCode)
                .orElseThrow(() -> new IllegalStateException("해당 기술 타일이 없습니다: " + tileCode));

        // 고급 기술 타일은 1명만 가져갈 수 있음, 기본 타일은 중복 가능 (본인 제외)
        boolean isAdvanced = tileCode.startsWith("ADV_");
        if (isAdvanced && offer.getTakenByPlayerId() != null)
            throw new IllegalStateException("이미 가져간 고급 기술 타일입니다: " + tileCode);

        // 고급 기술 타일: 사용 가능한 연방 토큰 1개 뒤집기 필요
        if (isAdvanced) {
            if (!flipUsableFederationToken(gameId, playerId)) {
                throw new IllegalStateException("고급 기술 타일 획득에는 사용 가능한 연방 토큰이 필요합니다");
            }
        }

        // 본인 중복 소유 불가
        if (playerTechTileRepository.existsByGameIdAndPlayerIdAndTechTileCode(gameId, playerId, tileCode))
            throw new IllegalStateException("이미 보유 중인 기술 타일입니다: " + tileCode);

        // 4. 타일 점유 처리 (고급 타일만 — 기본 타일은 여러 명이 가질 수 있음)
        if (isAdvanced) {
            offer.take(playerId);
            gameTechOfferRepository.save(offer);
        }

        // 5. 플레이어 타일 기록
        playerTechTileRepository.save(GamePlayerTechTile.builder()
                .gameId(gameId).playerId(playerId).techTileCode(tileCode).build());

        // 6. 타일 트랙 결정 (COMMON/EXPANSION은 플레이어 선택 트랙 사용)
        String tileTrack = offer.getTechTrack();
        String advanceTrack;
        if ("COMMON".equals(tileTrack) || "EXPANSION".equals(tileTrack)) {
            if (techTrackCode == null || techTrackCode.isBlank())
                throw new IllegalStateException("공용/확장 타일은 트랙 코드가 필요합니다");
            advanceTrack = techTrackCode;
        } else {
            // 트랙 고유 타일: 타일의 트랙 코드 사용
            advanceTrack = tileTrack;
        }

        // 7. 해당 트랙 전진 (지식 소모 없음) + 즉발 보상
        GamePlayerState ps = gamePlayerStateRepository.findByGameIdAndPlayerId(gameId, playerId)
                .orElseThrow(() -> new IllegalStateException("플레이어 상태를 찾을 수 없습니다"));

        String fieldName = trackCodeToFieldName(advanceTrack);
        ps.advanceTechTrackNoKnowledge(fieldName);

        int newLevel = switch (advanceTrack) {
            case "TERRA_FORMING" -> ps.getTechTerraforming();
            case "NAVIGATION"    -> ps.getTechNavigation();
            case "AI"            -> ps.getTechAi();
            case "GAIA_FORMING"  -> ps.getTechGaia();
            case "ECONOMY"       -> ps.getTechEconomy();
            case "SCIENCE"       -> ps.getTechScience();
            default -> 0;
        };

        applyTechTrackReward(ps, advanceTrack, newLevel, economyOption);

        // 8. 즉발(IMMEDIATE) 효과 적용
        TechAbility ability = techTileCode.getAbility();
        if (ability.getType() == TechAbilityType.IMMEDIATE) {
            applyImmediateTileEffect(gameId, playerId, ps, techTileCode, ability);
        }

        // 9. 라운드 점수 타일: 연구 트랙 1칸 전진당 2VP (건물 건설 시 타일 획득으로 인한 트랙 전진도 포함)
        com.gaiaproject.domain.entity.game.Game tileGame = gameRepository.findById(gameId).orElse(null);
        if (tileGame != null && "PLAYING".equals(tileGame.getGamePhase()) && tileGame.getCurrentRound() != null) {
            roundScoringService.award(gameId, tileGame.getCurrentRound(), ps, RoundScoringEvent.RESEARCH_ADVANCED, 1);
        }

        gamePlayerStateRepository.save(ps);

        log.info("[타일 획득] game={}, player={}, tile={}, track={}, newLevel={}",
                gameId, playerId, tileCode, advanceTrack, newLevel);
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
            log.info("[TILE_IMMEDIATE] 자원 즉발 적용: {}", tileCode);
            return;
        }

        switch (specialEffect) {
            case "KNOWLEDGE_PER_PLANET_TYPE" -> {
                // 플레이어 건물이 있는 행성 종류 수만큼 지식 획득
                List<GameBuilding> buildings = gameBuildingRepository.findByGameIdAndPlayerId(gameId, playerId);
                Set<String> planetTypes = new HashSet<>();
                for (GameBuilding b : buildings) {
                    gameHexRepository.findByGameIdAndHexQAndHexR(gameId, b.getHexQ(), b.getHexR())
                            .map(GameHex::getPlanetType)
                            .ifPresent(pt -> {
                                if (pt != null) planetTypes.add(pt.name());
                            });
                }
                ps.addKnowledge(planetTypes.size());
                log.info("[TILE_IMMEDIATE] KNOWLEDGE_PER_PLANET_TYPE: 행성 종류={}, 지식+={}", planetTypes.size(), planetTypes.size());
            }
            case "TERRAFORM_2_PLACE_MINE" -> {
                // FE에서 pending 체인으로 처리 (업그레이드 확정 → 광산 배치 → 확정)
                log.info("[TILE_IMMEDIATE] TERRAFORM_2_PLACE_MINE - FE pending 체인 처리, game={}, player={}", gameId, playerId);
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
            case "CHARGE_POWER_4"      -> ps.chargePower(4);
            case "ACTION_ORE_3"        -> ps.addOre(3);
            case "ACTION_KNOWLEDGE_3"  -> ps.addKnowledge(3);
            case "ACTION_QIC_1_CREDIT_5" -> { ps.addQic(1); ps.addCredit(5); }
            default -> throw new IllegalStateException("알 수 없는 액션 효과: " + specialEffect);
        }

        playerTile.useAction();
        playerTechTileRepository.save(playerTile);
        gamePlayerStateRepository.save(ps);

        log.info("[TECH_ACTION] game={}, player={}, tile={}, effect={}", gameId, playerId, tileCode, specialEffect);

        return actionService.saveActionAndNextTurn(gameId, playerId, ActionType.ADVANCE_TECH,
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

    /** 이번 라운드 ACTION 사용 완료된 타일 코드 Set (게임 내 전체 플레이어) */
    public Set<String> getActionUsedTileCodes(UUID gameId) {
        return playerTechTileRepository.findByGameId(gameId).stream()
                .filter(GamePlayerTechTile::isActionUsed)
                .map(GamePlayerTechTile::getTechTileCode)
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
            log.info("[TECH_LV5] 연방 토큰 뒤집기 완료: player={}, track={}", ps.getPlayerId(), trackCode);
            return;
        }
        if (newLevel < 1 || newLevel > 4) return;

        // 모든 트랙 공통: 2→3 전진 시 3파워 순환
        if (newLevel == 3) {
            ps.chargePower(3);
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
            case "ECONOMY" -> {
                switch (newLevel) {
                    case 1 -> { ps.addCredit(2); ps.chargePower(1); }
                    case 2 -> { ps.addOre(1); ps.addCredit(2); ps.chargePower(2); }
                    case 3 -> {
                        ps.addOre(1);
                        if (economyOption == EconomyTrackOption.OPTION_A) {
                            ps.addCredit(3); ps.addVP(1);
                        } else {
                            ps.addCredit(2); ps.chargePower(3);
                        }
                    }
                    case 4 -> {
                        ps.addOre(2);
                        if (economyOption == EconomyTrackOption.OPTION_A) {
                            ps.addCredit(4); ps.addVP(1);
                        } else {
                            ps.addCredit(2); ps.chargePower(2);
                        }
                    }
                }
            }
            // SCIENCE: 지식 수입은 라운드 수입 단계에서 처리 (즉각 보상 없음)
        }

        log.info("[TECH_REWARD] player={}, track={}, level={}", ps.getPlayerId(), trackCode, newLevel);
    }

    /**
     * 사용 가능한 연방 토큰 1개 뒤집기 (고급 기술타일 획득 / 5트랙 진입 시 필요)
     * 연방 그룹 토큰 또는 직접 보유 토큰 중 useFederation=true이고 아직 사용 안 한 것을 뒤집음.
     * @return true if flipped, false if no usable token
     */
    public boolean flipUsableFederationToken(UUID gameId, UUID playerId) {
        // 1. 연방 그룹에서 사용 가능한 토큰 찾기
        var groups = federationGroupRepository.findByGameIdAndPlayerId(gameId, playerId);
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
        for (var t : tokens) {
            if (!t.isUsed() && t.getFederationTileType().isUseFederation()) {
                t.markUsed();
                playerFederationTokenRepository.save(t);
                log.info("[FED_FLIP] 직접 보유 토큰 뒤집기: player={}, tile={}", playerId, t.getFederationTileType());
                return true;
            }
        }

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