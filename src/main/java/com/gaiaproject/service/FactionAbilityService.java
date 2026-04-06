package com.gaiaproject.service;

import com.gaiaproject.domain.entity.building.GameBuilding;
import com.gaiaproject.domain.entity.player.GamePlayerFederationToken;
import com.gaiaproject.domain.entity.player.GamePlayerState;
import com.gaiaproject.domain.enumtype.action.ActionType;
import com.gaiaproject.domain.enumtype.building.BuildingType;
import com.gaiaproject.domain.enumtype.federation.FederationTileType;
import com.gaiaproject.domain.enumtype.player.FactionType;
import com.gaiaproject.domain.entity.game.Game;
import com.gaiaproject.dto.request.FactionAbilityRequest;
import com.gaiaproject.dto.request.ItarsGaiaChoiceRequest;
import com.gaiaproject.dto.response.FactionAbilityResponse;
import com.gaiaproject.domain.entity.map.GameHex;
import com.gaiaproject.domain.enumtype.player.PlanetType;
import com.gaiaproject.repository.building.GameBuildingRepository;
import com.gaiaproject.util.HexUtil;
import com.gaiaproject.repository.map.GameHexRepository;
import com.gaiaproject.repository.player.GamePlayerFederationTokenRepository;
import com.gaiaproject.repository.player.GamePlayerStateRepository;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 종족 고유 능력 처리 서비스
 *
 * 기본 능력:
 * - BAL_TAKS_CONVERT_GAIAFORMER : (프리 액션) 가이아포머 → QIC
 * - XENOS_ORE_TO_POWER          : (프리 액션) 광석 1 → 3구역 파워 1
 * - BESCODS_ADVANCE_LOWEST_TRACK: (액션) 최저 기술 트랙 +1
 * - SPACE_GIANTS_TERRAFORM_2    : (액션) 2삽 테라포밍 선언
 * - GLEENS_JUMP                 : (액션) 2거리 점프 선언
 *
 * PI 능력:
 * - FIRAKS_DOWNGRADE            : (액션, 라운드당 1회) 연구소→교역소 + 지식트랙 1칸
 * - AMBAS_SWAP                  : (액션, 라운드당 1회) 광산↔의회 위치 교환
 * - HADSCH_HALLAS_CREDIT_CONVERT: (프리 액션) 크레딧으로 자원 변환 (trackCode=ORE/KNOWLEDGE/QIC)
 * - GLEENS_FEDERATION_TOKEN     : (액션) 2크레딧+1광석+1지식 → 연방 토큰 획득
 * - ITARS_GAIA_TO_TECH_TILE     : (액션) 4 가이아파워 영구 제거 → 기본 기술타일 (FE에서 타일 선택)
 * - IVITS_PLACE_STATION         : (액션, 라운드당 1회) 빈 헥스에 우주정거장 배치 + 인접 연방 자동 편입
 * - TINKEROIDS_ACTION           : (라운드 시작 시) 6개 액션 중 선택 → 메인 액션으로 사용
 * - MOWEIDS_RING                : TODO
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class FactionAbilityService {

    private final GamePlayerStateRepository playerStateRepository;
    private final GameBuildingRepository buildingRepository;
    private final GameHexRepository hexRepository;
    private final GamePlayerFederationTokenRepository federationTokenRepository;
    private final ActionService actionService;
    private final FederationFormService federationFormService;
    private final TechTileService techTileService;
    private final com.gaiaproject.repository.game.GameRepository gameRepository;
    private final PassService passService;
    private final GameWebSocketService webSocketService;
    private final GaiaformingService gaiaformingService;
    private final PowerLeechService powerLeechService;
    private final RoundScoringService roundScoringService;
    private final com.gaiaproject.repository.tech.GamePlayerTechTileRepository playerTechTileRepository;

    /** 의회 건설 여부 확인 */
    private boolean hasPi(GamePlayerState ps) {
        return ps.getStockPlanetaryInstitute() == 0;
    }

    public FactionAbilityResponse useAbility(UUID gameId, FactionAbilityRequest request) {
        String code = request.abilityCode();
        UUID playerId = request.playerId();

        GamePlayerState ps = playerStateRepository.findByGameIdAndPlayerId(gameId, playerId)
                .orElseThrow(() -> new IllegalStateException("플레이어 상태를 찾을 수 없습니다"));

        try {
            return switch (code) {
                // ── 기본 능력 ──
                case "BAL_TAKS_CONVERT_GAIAFORMER"  -> handleBaltaksConvert(gameId, playerId, ps, code);
                case "XENOS_ORE_TO_POWER"           -> handleXenosOreToPower(gameId, playerId, ps, code);
                case "BESCODS_ADVANCE_LOWEST_TRACK" -> handleBescodsAdvance(gameId, playerId, ps, code, request);
                case "SPACE_GIANTS_TERRAFORM_2"     -> handleSpaceGiantsTerraform(gameId, playerId, ps, code);
                case "GLEENS_JUMP"                  -> handleGleensJump(gameId, playerId, ps, code);
                // ── PI 능력 ──
                case "FIRAKS_DOWNGRADE"             -> handleFiraksPiDowngrade(gameId, playerId, ps, code, request);
                case "AMBAS_SWAP"                   -> handleAmbasPiSwap(gameId, playerId, ps, code, request);
                case "HADSCH_HALLAS_CREDIT_CONVERT" -> handleHadschHallasCreditConvert(gameId, playerId, ps, code, request);
                case "GLEENS_FEDERATION_TOKEN"      -> handleGleensFederationToken(gameId, playerId, ps, code);
                case "TINKEROIDS_USE_ACTION"         -> handleTinkeroidsUseAction(gameId, playerId, ps, code);
                // ITARS_GAIA_TO_TECH_TILE: 라운드 종료 시 자동 처리 (handleItarsRoundEndChoice)
                case "IVITS_PLACE_STATION"          -> handleIvitsPlaceStation(gameId, playerId, ps, code, request);
                case "QIC_ACADEMY_ACTION"           -> handleQicAcademyAction(gameId, playerId, ps, code);
                case "MOWEIDS_RING"                 -> handleMoweidsRing(gameId, playerId, ps, code, request);
                default -> FactionAbilityResponse.fail(gameId, code, "알 수 없는 능력 코드: " + code);
            };
        } catch (IllegalStateException e) {
            return FactionAbilityResponse.fail(gameId, code, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 기본 능력
    // ═══════════════════════════════════════════════════════════

    /** 발타크: 가이아포머 → QIC (프리 액션) */
    private FactionAbilityResponse handleBaltaksConvert(UUID gameId, UUID playerId, GamePlayerState ps, String code) {
        if (ps.getFactionType() != FactionType.BAL_TAKS) return FactionAbilityResponse.fail(gameId, code, "발타크만 사용 가능");
        ps.convertGaiaformerToQic();
        playerStateRepository.save(ps);
        log.info("[BAL_TAKS] 포머→QIC: player={}", playerId);
        return FactionAbilityResponse.success(gameId, code, null);
    }

    /** 제노스: 광석 1 → 3구역 파워 1 (프리 액션) */
    private FactionAbilityResponse handleXenosOreToPower(UUID gameId, UUID playerId, GamePlayerState ps, String code) {
        if (ps.getFactionType() != FactionType.XENOS) return FactionAbilityResponse.fail(gameId, code, "제노스만 사용 가능");
        ps.spendOre(1);
        ps.gainPower(1);
        playerStateRepository.save(ps);
        log.info("[XENOS] 광석→파워3: player={}", playerId);
        return FactionAbilityResponse.success(gameId, code, null);
    }

    /** 매드안드로이드: 최저 기술 트랙 중 선택한 트랙 +1 (액션, 선언형) */
    private FactionAbilityResponse handleBescodsAdvance(UUID gameId, UUID playerId, GamePlayerState ps, String code, FactionAbilityRequest request) {
        if (ps.getFactionType() != FactionType.BESCODS) return FactionAbilityResponse.fail(gameId, code, "매드안드로이드만 사용 가능");
        if (ps.isFactionAbilityUsed()) return FactionAbilityResponse.fail(gameId, code, "이번 라운드 이미 사용했습니다");

        String trackCode = request.trackCode();
        if (trackCode == null || trackCode.isBlank()) {
            return FactionAbilityResponse.fail(gameId, code, "트랙 코드가 필요합니다");
        }

        // 선택한 트랙이 최저 레벨인지 검증
        int minLevel = ps.getLowestTechLevel();
        int selectedLevel = ps.getTechLevel(trackCode);
        if (selectedLevel != minLevel) {
            return FactionAbilityResponse.fail(gameId, code, "최저 레벨 트랙만 선택할 수 있습니다");
        }
        if (selectedLevel >= 5) {
            return FactionAbilityResponse.fail(gameId, code, "이미 최대 레벨입니다");
        }

        ps.advanceTechTrackFree(trackCode);
        int newLevel = ps.getTechLevel(trackCode);

        // 트랙 전진 즉시 보상 적용 (QIC, 광석, 파워 등)
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new IllegalStateException("게임을 찾을 수 없습니다"));
        techTileService.applyTechTrackReward(ps, trackCode, newLevel, game.getEconomyTrackOption());

        // 라운드 점수 타일: 연구 트랙 1칸 전진당 VP
        if (game.getCurrentRound() != null) {
            roundScoringService.award(gameId, game.getCurrentRound(), ps,
                    com.gaiaproject.domain.enumtype.rounds.RoundScoringEvent.RESEARCH_ADVANCED, 1);
        }

        ps.markFactionAbilityUsed();
        playerStateRepository.save(ps);
        log.info("[BESCODS] 트랙 전진: player={}, track={}, newLevel={}", playerId, trackCode, newLevel);
        var result = actionService.saveActionAndNextTurn(gameId, playerId, ActionType.FACTION_ABILITY,
                "{\"abilityCode\":\"" + code + "\",\"track\":\"" + trackCode + "\"}");
        return FactionAbilityResponse.success(gameId, code, result.nextTurnSeatNo());
    }

    /** 스페이스 자이언트: 2삽 선언 (액션, FE에서 광산 건설로 연결) */
    private FactionAbilityResponse handleSpaceGiantsTerraform(UUID gameId, UUID playerId, GamePlayerState ps, String code) {
        if (ps.getFactionType() != FactionType.SPACE_GIANTS) return FactionAbilityResponse.fail(gameId, code, "스페이스 자이언트만 사용 가능");
        if (ps.isFactionAbilityUsed()) return FactionAbilityResponse.fail(gameId, code, "이번 라운드 이미 사용했습니다");
        ps.markFactionAbilityUsed();
        playerStateRepository.save(ps);
        return FactionAbilityResponse.success(gameId, code, null);
    }

    /** 글린: 2거리 점프 선언 (액션) */
    private FactionAbilityResponse handleGleensJump(UUID gameId, UUID playerId, GamePlayerState ps, String code) {
        if (ps.getFactionType() != FactionType.GLEENS) return FactionAbilityResponse.fail(gameId, code, "글린만 사용 가능");
        if (ps.isFactionAbilityUsed()) return FactionAbilityResponse.fail(gameId, code, "이번 라운드 이미 사용했습니다");
        ps.markFactionAbilityUsed();
        playerStateRepository.save(ps);
        return FactionAbilityResponse.success(gameId, code, null);
    }

    // ═══════════════════════════════════════════════════════════
    // PI 능력
    // ═══════════════════════════════════════════════════════════

    /**
     * 파이락 PI: 연구소 → 교역소 다운그레이드 + 지식 트랙 1칸 (라운드당 1회)
     * request.hexQ/hexR = 다운그레이드할 연구소 위치
     * request.trackCode = 전진할 기술 트랙 코드
     */
    private FactionAbilityResponse handleFiraksPiDowngrade(UUID gameId, UUID playerId, GamePlayerState ps, String code, FactionAbilityRequest req) {
        if (ps.getFactionType() != FactionType.FIRAKS) return FactionAbilityResponse.fail(gameId, code, "파이락만 사용 가능");
        if (!hasPi(ps)) return FactionAbilityResponse.fail(gameId, code, "행성 의회 건설 후 사용 가능합니다");
        if (ps.isFactionAbilityUsed()) return FactionAbilityResponse.fail(gameId, code, "이번 라운드 이미 사용했습니다");
        if (req.hexQ() == null || req.hexR() == null) return FactionAbilityResponse.fail(gameId, code, "연구소 위치를 지정해야 합니다");
        if (req.trackCode() == null) return FactionAbilityResponse.fail(gameId, code, "전진할 트랙을 지정해야 합니다");

        // 연구소 확인 및 교역소로 다운그레이드
        Optional<GameBuilding> bOpt = buildingRepository.findFirstByGameIdAndHexQAndHexRAndIsLantidsMine(gameId, req.hexQ(), req.hexR(), false);
        if (bOpt.isEmpty() || !bOpt.get().getPlayerId().equals(playerId))
            return FactionAbilityResponse.fail(gameId, code, "해당 위치에 본인 건물이 없습니다");
        if (bOpt.get().getBuildingType() != BuildingType.RESEARCH_LAB)
            return FactionAbilityResponse.fail(gameId, code, "연구소만 다운그레이드 가능합니다");

        GameBuilding rl = bOpt.get();
        rl.upgrade(BuildingType.TRADING_STATION); // 다운그레이드: RL → TS
        buildingRepository.save(rl);

        // 재고 조정: RL +1 반환, TS -1 사용
        ps.addResearchLabToStock();
        ps.decreaseStockTradingStation();

        // 지식 트랙 1칸 전진 (지식 소모 없음) + 즉시 보상
        ps.advanceTechTrackNoKnowledge(trackCodeToField(req.trackCode()));
        int firakNewLevel = ps.getTechLevel(req.trackCode());
        Game game = gameRepository.findById(gameId).orElseThrow();
        techTileService.applyTechTrackReward(ps, req.trackCode(), firakNewLevel, game.getEconomyTrackOption());
        if (game.getCurrentRound() != null) {
            roundScoringService.award(gameId, game.getCurrentRound(), ps,
                    com.gaiaproject.domain.enumtype.rounds.RoundScoringEvent.RESEARCH_ADVANCED, 1);
        }

        ps.markFactionAbilityUsed();
        playerStateRepository.save(ps);
        log.info("[FIRAKS PI] RL→TS 다운그레이드 + {} 전진 (lv{}): player={}", req.trackCode(), firakNewLevel, playerId);

        // 액션 저장 (턴 진행은 리치 해소 후)
        actionService.saveActionOnly(gameId, playerId, ActionType.FACTION_ABILITY,
                "{\"abilityCode\":\"" + code + "\",\"track\":\"" + req.trackCode() + "\"}");

        // 파워 리치 처리 (교역소로 다운그레이드 = 파워값 2 건물)
        List<com.gaiaproject.domain.entity.building.GameBuilding> allBuildings = buildingRepository.findByGameId(gameId);
        powerLeechService.createBatchAndProcess(game, playerId, req.hexQ(), req.hexR(),
                BuildingType.TRADING_STATION, allBuildings, null, null);

        return FactionAbilityResponse.success(gameId, code, null);
    }

    /**
     * 엠바스 PI: 광산 위치 ↔ 의회 위치 교환 (라운드당 1회, 파워 리치 없음)
     * request.hexQ/hexR = 교환할 광산 위치
     */
    private FactionAbilityResponse handleAmbasPiSwap(UUID gameId, UUID playerId, GamePlayerState ps, String code, FactionAbilityRequest req) {
        if (ps.getFactionType() != FactionType.AMBAS) return FactionAbilityResponse.fail(gameId, code, "엠바스만 사용 가능");
        if (!hasPi(ps)) return FactionAbilityResponse.fail(gameId, code, "행성 의회 건설 후 사용 가능합니다");
        if (ps.isFactionAbilityUsed()) return FactionAbilityResponse.fail(gameId, code, "이번 라운드 이미 사용했습니다");
        if (req.hexQ() == null || req.hexR() == null) return FactionAbilityResponse.fail(gameId, code, "교환할 광산 위치를 지정해야 합니다");

        // 광산 확인
        Optional<GameBuilding> mineOpt = buildingRepository.findFirstByGameIdAndHexQAndHexRAndIsLantidsMine(gameId, req.hexQ(), req.hexR(), false);
        if (mineOpt.isEmpty() || !mineOpt.get().getPlayerId().equals(playerId) || mineOpt.get().getBuildingType() != BuildingType.MINE)
            return FactionAbilityResponse.fail(gameId, code, "해당 위치에 본인 광산이 없습니다");

        // 의회 확인
        List<GameBuilding> piList = buildingRepository.findByGameIdAndPlayerId(gameId, playerId).stream()
                .filter(b -> b.getBuildingType() == BuildingType.PLANETARY_INSTITUTE)
                .toList();
        if (piList.isEmpty()) return FactionAbilityResponse.fail(gameId, code, "행성 의회를 찾을 수 없습니다");

        GameBuilding mine = mineOpt.get();
        GameBuilding pi = piList.get(0);

        // 위치 교환 (각 건물의 Q,R 교환)
        int mq = mine.getHexQ(), mr = mine.getHexR();
        int pq = pi.getHexQ(), pr = pi.getHexR();
        swapBuildingPosition(mine, pq, pr);
        swapBuildingPosition(pi, mq, mr);
        buildingRepository.save(mine);
        buildingRepository.save(pi);

        ps.markFactionAbilityUsed();
        playerStateRepository.save(ps);
        log.info("[AMBAS PI] 광산({},{}) ↔ 의회({},{}) 교환: player={}", mq, mr, pq, pr, playerId);

        var result = actionService.saveActionAndNextTurn(gameId, playerId, ActionType.FACTION_ABILITY,
                "{\"abilityCode\":\"" + code + "\"}");
        return FactionAbilityResponse.success(gameId, code, result.nextTurnSeatNo());
    }

    /**
     * 하드쉬 할라 PI: 크레딧으로 자원 변환 (프리 액션)
     * trackCode: ORE(3c→1o), KNOWLEDGE(4c→1k), QIC(4c→1qic)
     */
    private FactionAbilityResponse handleHadschHallasCreditConvert(UUID gameId, UUID playerId, GamePlayerState ps, String code, FactionAbilityRequest req) {
        if (ps.getFactionType() != FactionType.HADSCH_HALLAS) return FactionAbilityResponse.fail(gameId, code, "하드쉬 할라만 사용 가능");
        if (!hasPi(ps)) return FactionAbilityResponse.fail(gameId, code, "행성 의회 건설 후 사용 가능합니다");

        String target = req.trackCode(); // ORE / KNOWLEDGE / QIC
        switch (target == null ? "" : target) {
            case "ORE" -> {
                ps.spendCredit(3);
                ps.addOre(1);
                log.info("[HADSCH_HALLAS PI] 3크레딧→1광석: player={}", playerId);
            }
            case "KNOWLEDGE" -> {
                ps.spendCredit(4);
                ps.addKnowledge(1);
                log.info("[HADSCH_HALLAS PI] 4크레딧→1지식: player={}", playerId);
            }
            case "QIC" -> {
                ps.spendCredit(4);
                ps.addQic(1);
                log.info("[HADSCH_HALLAS PI] 4크레딧→1QIC: player={}", playerId);
            }
            default -> { return FactionAbilityResponse.fail(gameId, code, "trackCode: ORE/KNOWLEDGE/QIC 중 하나를 지정하세요"); }
        }
        playerStateRepository.save(ps);
        return FactionAbilityResponse.success(gameId, code, null); // 프리 액션: 턴 소모 없음
    }

    /**
     * 글린 PI: 2크레딧+1광석+1지식 → 연방 토큰 획득 (액션)
     */
    private FactionAbilityResponse handleGleensFederationToken(UUID gameId, UUID playerId, GamePlayerState ps, String code) {
        if (ps.getFactionType() != FactionType.GLEENS) return FactionAbilityResponse.fail(gameId, code, "글린만 사용 가능");
        if (!hasPi(ps)) return FactionAbilityResponse.fail(gameId, code, "행성 의회 건설 후 사용 가능합니다");
        if (ps.isFactionAbilityUsed()) return FactionAbilityResponse.fail(gameId, code, "이번 라운드 이미 사용했습니다");

        ps.spendCredit(2);
        ps.spendOre(1);
        ps.spendKnowledge(1);

        // 기본 연방 토큰 중 하나 부여 (임의: FEDERATION_7VP)
        federationTokenRepository.save(GamePlayerFederationToken.builder()
                .gameId(gameId)
                .playerId(playerId)
                .federationTileType(FederationTileType.GLEENS_FEDERATION)
                .build());

        ps.markFactionAbilityUsed();
        playerStateRepository.save(ps);
        log.info("[GLEENS PI] 2c+1o+1k → 연방 토큰: player={}", playerId);

        var result = actionService.saveActionAndNextTurn(gameId, playerId, ActionType.FACTION_ABILITY,
                "{\"abilityCode\":\"" + code + "\"}");
        return FactionAbilityResponse.success(gameId, code, result.nextTurnSeatNo());
    }

    /** 팅커로이드: 선택된 액션 사용 (메인 액션, 턴 소모) */
    private FactionAbilityResponse handleTinkeroidsUseAction(UUID gameId, UUID playerId, GamePlayerState ps, String code) {
        if (ps.getFactionType() != FactionType.TINKEROIDS) return FactionAbilityResponse.fail(gameId, code, "팅커로이드만 사용 가능");
        String currentAction = ps.getTinkeroidsCurrentAction();
        if (currentAction == null) return FactionAbilityResponse.fail(gameId, code, "사용 가능한 액션이 없습니다");

        // 테라포밍 액션은 선언형 (FE에서 pending으로 처리) → 여기서는 비테라 액션만
        switch (currentAction) {
            case "TINK_POWER_4" -> ps.chargePowerWithFactionRules(4);
            case "TINK_QIC_1" -> ps.addQic(1);
            case "TINK_KNOWLEDGE_3" -> ps.addKnowledge(3);
            case "TINK_QIC_2" -> { ps.addQic(1); ps.addQic(1); }
            case "TINK_TERRAFORM_1", "TINK_TERRAFORM_3" -> {
                // 테라포밍: 사용 마킹만 하고 턴 넘기지 않음 (후속 광산 건설에서 턴 종료)
                ps.useTinkeroidsCurrentAction();
                ps.markFactionAbilityUsed();
                playerStateRepository.save(ps);
                log.info("[TINKEROIDS] 테라포밍 액션 선언: player={}, action={}", playerId, currentAction);
                return FactionAbilityResponse.success(gameId, code, null);
            }
            default -> { return FactionAbilityResponse.fail(gameId, code, "알 수 없는 액션: " + currentAction); }
        }

        ps.useTinkeroidsCurrentAction();
        ps.markFactionAbilityUsed();
        playerStateRepository.save(ps);
        log.info("[TINKEROIDS] 액션 사용: player={}, action={}", playerId, currentAction);

        var result = actionService.saveActionAndNextTurn(gameId, playerId, ActionType.FACTION_ABILITY,
                "{\"abilityCode\":\"TINKEROIDS_USE_ACTION\",\"action\":\"" + currentAction + "\"}");
        return FactionAbilityResponse.success(gameId, code, result.nextTurnSeatNo());
    }

    /**
     * 팅커로이드 PI: 라운드 시작 시 액션 타일 선택.
     * TINKEROIDS_ACTION_PHASE에서만 호출 가능.
     */
    public FactionAbilityResponse handleTinkeroidsActionChoice(UUID gameId, com.gaiaproject.dto.request.TinkeroidsActionChoiceRequest request) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new IllegalArgumentException("게임을 찾을 수 없습니다"));
        if (!"TINKEROIDS_ACTION_PHASE".equals(game.getGamePhase())) {
            return FactionAbilityResponse.fail(gameId, "TINKEROIDS_ACTION", "팅커로이드 액션 선택 단계가 아닙니다");
        }

        UUID playerId = request.playerId();
        GamePlayerState ps = playerStateRepository.findByGameIdAndPlayerId(gameId, playerId)
                .orElseThrow(() -> new IllegalStateException("플레이어 상태를 찾을 수 없습니다"));

        if (ps.getFactionType() != FactionType.TINKEROIDS) {
            return FactionAbilityResponse.fail(gameId, "TINKEROIDS_ACTION", "팅커로이드만 사용 가능");
        }

        String actionCode = request.actionCode();
        if (ps.isTinkeroidsActionUsed(actionCode)) {
            return FactionAbilityResponse.fail(gameId, "TINKEROIDS_ACTION", "이미 사용한 액션입니다: " + actionCode);
        }

        // 유효한 액션인지 확인 (효과는 PLAYING 중 사용 시 적용)
        if (!List.of("TINK_TERRAFORM_1","TINK_POWER_4","TINK_QIC_1","TINK_TERRAFORM_3","TINK_KNOWLEDGE_3","TINK_QIC_2").contains(actionCode)) {
            return FactionAbilityResponse.fail(gameId, "TINKEROIDS_ACTION", "알 수 없는 액션: " + actionCode);
        }

        ps.selectTinkeroidsAction(actionCode);
        playerStateRepository.save(ps);

        log.info("[TINKEROIDS] 액션 선택: player={}, action={}, round={}", playerId, actionCode, game.getCurrentRound());

        // 다음 단계로 진행 (아이타 체크 또는 라운드 시작)
        passService.continueTinkeroidsToNextPhase(gameId);

        return FactionAbilityResponse.success(gameId, "TINKEROIDS_ACTION", null);
    }

    /**
     * 아이타 PI: 라운드 종료 시 가이아→기술타일 선택 처리.
     * ITARS_GAIA_PHASE에서만 호출 가능.
     */
    public FactionAbilityResponse handleItarsRoundEndChoice(UUID gameId, ItarsGaiaChoiceRequest request) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new IllegalArgumentException("게임을 찾을 수 없습니다"));
        if (!"ITARS_GAIA_PHASE".equals(game.getGamePhase())) {
            return FactionAbilityResponse.fail(gameId, "ITARS_GAIA_CHOICE", "아이타 가이아 선택 단계가 아닙니다");
        }

        UUID playerId = request.playerId();
        GamePlayerState ps = playerStateRepository.findByGameIdAndPlayerId(gameId, playerId)
                .orElseThrow(() -> new IllegalStateException("플레이어 상태를 찾을 수 없습니다"));

        if ("TAKE_TILE".equals(request.action())) {
            if (ps.getGaiaPower() < 4) {
                return FactionAbilityResponse.fail(gameId, "ITARS_GAIA_CHOICE", "가이아 파워 부족");
            }
            ps.removeGaiaPower(4);
            playerStateRepository.save(ps);

            try {
                techTileService.acquireTileForBuilding(gameId, playerId,
                        request.tileCode(), request.techTrackCode(), game.getEconomyTrackOption(),
                        request.coveredTileCode());
            } catch (IllegalStateException e) {
                ps.addGaiaPower(4);
                playerStateRepository.save(ps);
                return FactionAbilityResponse.fail(gameId, "ITARS_GAIA_CHOICE", e.getMessage());
            }

            log.info("[ITARS] 가이아 4 → 기술타일: player={}, tile={}", playerId, request.tileCode());

            // 아직 4개 이상 남아있으면 다시 선택 기회
            if (ps.getGaiaPower() >= 4) {
                webSocketService.broadcastItarsGaiaChoice(gameId, playerId, ps.getGaiaPower() / 4);
                return FactionAbilityResponse.success(gameId, "ITARS_GAIA_CHOICE", null);
            }
        }

        // SKIP이거나 가이아 부족: 잔여 가이아 복귀 + 라운드 시작
        gaiaformingService.returnGaiaPowerForPlayer(gameId, playerId);

        game.setGamePhase("PLAYING");
        gameRepository.save(game);

        webSocketService.broadcastRoundStarted(gameId, game.getCurrentRound());
        log.info("[ITARS] 가이아 선택 완료, 라운드 {} 시작", game.getCurrentRound());
        return FactionAbilityResponse.success(gameId, "ITARS_GAIA_CHOICE", null);
    }


    /** 하이브 PI: 우주정거장 배치 (라운드당 1회, 빈 헥스에만 배치 가능) */
    private FactionAbilityResponse handleIvitsPlaceStation(UUID gameId, UUID playerId, GamePlayerState ps, String code, FactionAbilityRequest request) {
        if (ps.getFactionType() != FactionType.IVITS) return FactionAbilityResponse.fail(gameId, code, "하이브만 사용 가능");
        if (!hasPi(ps)) return FactionAbilityResponse.fail(gameId, code, "행성 의회 건설 후 사용 가능합니다");
        if (ps.isFactionAbilityUsed()) return FactionAbilityResponse.fail(gameId, code, "이번 라운드 이미 사용했습니다");

        Integer hexQ = request.hexQ();
        Integer hexR = request.hexR();
        if (hexQ == null || hexR == null) return FactionAbilityResponse.fail(gameId, code, "헥스 좌표가 필요합니다");

        // 헥스 존재 확인
        GameHex hex = hexRepository.findByGameIdAndHexQAndHexR(gameId, hexQ, hexR)
                .orElse(null);
        if (hex == null) return FactionAbilityResponse.fail(gameId, code, "유효하지 않은 좌표입니다");

        // 빈 헥스(EMPTY)만 가능 - 행성, 차원변형, 가이아 등 불가
        if (hex.getPlanetType() != PlanetType.EMPTY) {
            return FactionAbilityResponse.fail(gameId, code, "빈 우주 헥스에만 우주정거장을 배치할 수 있습니다");
        }

        // 이미 건물이 있는지 확인
        if (buildingRepository.existsByGameIdAndHexQAndHexR(gameId, hexQ, hexR)) {
            return FactionAbilityResponse.fail(gameId, code, "이미 건물이 있는 위치입니다");
        }

        // 항법 거리 체크: 자기 건물(우주정거장 포함)로부터 항법 거리 이내
        int navRange = switch (ps.getTechNavigation()) {
            case 0 -> 1; case 1 -> 1; case 2 -> 2; case 3 -> 2; case 4 -> 3; default -> 4;
        };
        // BASIC_EXP_TILE_1 보유 시 항법 거리 +1
        if (hasActiveTechTile(gameId, playerId, "BASIC_EXP_TILE_1")) {
            navRange += 1;
        }
        // QIC로 항법 거리 확장 (QIC 1개당 +2)
        int qicUsed = request.qicUsed() != null ? request.qicUsed() : 0;
        int finalNavRange = navRange + qicUsed * 2;
        List<GameBuilding> myBuildings = buildingRepository.findByGameIdAndPlayerId(gameId, playerId);
        boolean inRange = myBuildings.stream().anyMatch(b ->
                HexUtil.distance(b.getHexQ(), b.getHexR(), hexQ, hexR) <= finalNavRange);
        if (!inRange) {
            return FactionAbilityResponse.fail(gameId, code, "항법 거리 밖입니다 (현재 거리: " + finalNavRange + ")");
        }
        // QIC 소모
        if (qicUsed > 0) {
            try {
                ps.spendQic(qicUsed);
            } catch (IllegalStateException e) {
                return FactionAbilityResponse.fail(gameId, code, "QIC가 부족합니다");
            }
        }

        // 우주정거장 배치
        GameBuilding station = GameBuilding.place(gameId, playerId, hexQ, hexR, BuildingType.SPACE_STATION);
        buildingRepository.save(station);

        ps.markFactionAbilityUsed();
        playerStateRepository.save(ps);
        log.info("[IVITS PI] 우주정거장 배치: player={}, hex=({},{})", playerId, hexQ, hexR);

        // 인접 연방에 자동 편입
        federationFormService.autoJoinFederation(gameId, playerId, hexQ, hexR);

        var result = actionService.saveActionAndNextTurn(gameId, playerId, ActionType.FACTION_ABILITY,
                "{\"abilityCode\":\"" + code + "\",\"hexQ\":" + hexQ + ",\"hexR\":" + hexR + "}");
        return FactionAbilityResponse.success(gameId, code, result.nextTurnSeatNo());
    }

    // ═══════════════════════════════════════════════════════════
    // 유틸
    // ═══════════════════════════════════════════════════════════

    private String trackCodeToField(String trackCode) {
        return switch (trackCode) {
            case "TERRA_FORMING"  -> "techTerraforming";
            case "NAVIGATION"     -> "techNavigation";
            case "AI"             -> "techAi";
            case "GAIA_FORMING"   -> "techGaia";
            case "ECONOMY"        -> "techEconomy";
            case "SCIENCE"        -> "techScience";
            default -> throw new IllegalArgumentException("알 수 없는 트랙 코드: " + trackCode);
        };
    }

    /** QIC 아카데미 액션: QIC 1개 획득 (라운드당 1회, 프리 액션) */
    private FactionAbilityResponse handleQicAcademyAction(UUID gameId, UUID playerId, GamePlayerState ps, String code) {
        // QIC 아카데미 보유 여부 확인
        int qicAcademyCount = buildingRepository.countByGameIdAndPlayerIdAndBuildingTypeAndAcademyType(
                gameId, playerId, BuildingType.ACADEMY, com.gaiaproject.domain.enumtype.building.AcademyType.QIC);
        if (qicAcademyCount <= 0) {
            return FactionAbilityResponse.fail(gameId, code, "QIC 아카데미를 보유하고 있지 않습니다");
        }
        if (ps.isQicAcademyActionUsed()) {
            return FactionAbilityResponse.fail(gameId, code, "이번 라운드에 이미 사용했습니다");
        }
        ps.useQicAcademyAction();
        // 발타크: 4돈, 그 외: 1QIC
        if (ps.getFactionType() == com.gaiaproject.domain.enumtype.player.FactionType.BAL_TAKS) {
            ps.addCredit(4);
            log.info("[QIC ACADEMY - BAL_TAKS] 크레딧 +4: player={}", playerId);
        } else {
            ps.addQic(1);
            log.info("[QIC ACADEMY] QIC +1: player={}", playerId);
        }
        playerStateRepository.save(ps);

        var result = actionService.saveActionAndNextTurn(gameId, playerId, ActionType.FACTION_ABILITY,
                "{\"abilityCode\":\"" + code + "\"}");
        return FactionAbilityResponse.success(gameId, code, result.nextTurnSeatNo());
    }

    /** 건물 좌표 교환 (reflection 없이 직접 setter가 없으므로 GameBuilding에 swapPosition 추가 필요) */
    private void swapBuildingPosition(GameBuilding building, int newQ, int newR) {
        building.setPosition(newQ, newR);
    }

    /**
     * 모웨이드 PI: 본인 건물에 링 씌우기 (라운드당 1회, 파워값 +2)
     * request.hexQ/hexR = 링 씌울 건물 위치
     */
    private FactionAbilityResponse handleMoweidsRing(UUID gameId, UUID playerId, GamePlayerState ps, String code, FactionAbilityRequest req) {
        if (ps.getFactionType() != FactionType.MOWEIDS) return FactionAbilityResponse.fail(gameId, code, "모웨이드만 사용 가능");
        if (!hasPi(ps)) return FactionAbilityResponse.fail(gameId, code, "행성 의회 건설 후 사용 가능합니다");
        if (ps.isFactionAbilityUsed()) return FactionAbilityResponse.fail(gameId, code, "이번 라운드 이미 사용했습니다");
        if (req.hexQ() == null || req.hexR() == null) return FactionAbilityResponse.fail(gameId, code, "건물 위치를 지정해야 합니다");

        Optional<GameBuilding> bOpt = buildingRepository.findFirstByGameIdAndHexQAndHexRAndIsLantidsMine(gameId, req.hexQ(), req.hexR(), false);
        if (bOpt.isEmpty() || !bOpt.get().getPlayerId().equals(playerId))
            return FactionAbilityResponse.fail(gameId, code, "해당 위치에 본인 건물이 없습니다");

        GameBuilding building = bOpt.get();
        if (building.isHasRing())
            return FactionAbilityResponse.fail(gameId, code, "이미 링이 씌워진 건물입니다");

        building.applyRing();
        buildingRepository.save(building);

        ps.markFactionAbilityUsed();
        playerStateRepository.save(ps);
        log.info("[MOWEIDS PI] 링 씌우기: player={}, hex=({},{}), building={}", playerId, req.hexQ(), req.hexR(), building.getBuildingType());

        var result = actionService.saveActionAndNextTurn(gameId, playerId, ActionType.FACTION_ABILITY,
                "{\"abilityCode\":\"" + code + "\",\"hexQ\":" + req.hexQ() + ",\"hexR\":" + req.hexR() + "}");
        return FactionAbilityResponse.success(gameId, code, result.nextTurnSeatNo());
    }

    private boolean hasActiveTechTile(UUID gameId, UUID playerId, String tileCode) {
        return playerTechTileRepository
                .findByGameIdAndPlayerIdAndIsCovered(gameId, playerId, false)
                .stream()
                .anyMatch(t -> tileCode.equals(t.getTechTileCode()));
    }
}
