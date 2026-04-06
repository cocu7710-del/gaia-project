package com.gaiaproject.service;

import com.gaiaproject.domain.entity.building.GameBuilding;
import com.gaiaproject.domain.entity.map.GameHex;
import com.gaiaproject.domain.entity.player.GamePlayerState;
import com.gaiaproject.domain.entity.player.GamePlayerTechTile;
import com.gaiaproject.domain.entity.tech.GameTechOffer;
import com.gaiaproject.domain.enumtype.action.ActionType;
import com.gaiaproject.domain.enumtype.action.VpCategory;
import com.gaiaproject.domain.enumtype.building.BuildingType;
import com.gaiaproject.domain.enumtype.player.PlanetType;
import com.gaiaproject.dto.request.FleetShipActionRequest;
import com.gaiaproject.dto.response.ConfirmActionResponse;
import com.gaiaproject.dto.response.FleetShipActionResponse;
import com.gaiaproject.domain.enumtype.tech.TechTileCode;
import com.gaiaproject.repository.building.GameBuildingRepository;
import com.gaiaproject.repository.map.GameHexRepository;
import com.gaiaproject.repository.player.GamePlayerStateRepository;
import com.gaiaproject.repository.tech.GamePlayerTechTileRepository;
import com.gaiaproject.repository.tech.GameTechOfferRepository;
import com.gaiaproject.repository.player.GamePlayerFleetProbeRepository;
import com.gaiaproject.repository.player.GamePlayerFederationTokenRepository;
import com.gaiaproject.domain.enumtype.federation.FederationTileType;
import com.gaiaproject.domain.enumtype.federation.FederationActionType;
import com.gaiaproject.dto.ResourcesVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 함대 선박 특수 액션 서비스
 * - TF_MARS, ECLIPSE, REBELLION, TWILIGHT 선박별 3-4개 특수 액션
 * - 즉시 액션: 자원 소비/획득 후 턴 종료
 * - 후속 액션: 자원 소비 후 mine/upgrade 별도 호출로 턴 종료
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class FleetShipActionService {

    private final GamePlayerStateRepository playerStateRepository;
    private final GameBuildingRepository buildingRepository;
    private final GameHexRepository hexRepository;
    private final GamePlayerTechTileRepository techTileRepository;
    private final GameTechOfferRepository techOfferRepository;
    private final GamePlayerFleetProbeRepository fleetProbeRepository;
    private final ActionService actionService;
    private final TechTileService techTileService;
    private final RoundScoringService roundScoringService;
    private final ArtifactService artifactService;
    private final PowerLeechService powerLeechService;
    private final VpLogService vpLogService;
    private final BuildingService buildingService;
    private final com.gaiaproject.repository.game.GameRepository gameRepository;
    private final FederationFormService federationFormService;
    private final GamePlayerFederationTokenRepository federationTokenRepository;

    /** 함대 선박 특수 액션 실행 */
    public FleetShipActionResponse executeAction(UUID gameId, FleetShipActionRequest request) {
        UUID playerId = request.playerId();
        String actionCode = request.actionCode();

        // 함대 멤버십 검증
        String fleetName = getFleetName(actionCode);
        if (fleetName != null && !fleetProbeRepository.existsByGameIdAndPlayerIdAndFleetName(gameId, playerId, fleetName)) {
            return FleetShipActionResponse.fail(gameId, actionCode, "해당 함대에 입장하지 않았습니다: " + fleetName);
        }

        GamePlayerState ps = playerStateRepository.findByGameIdAndPlayerId(gameId, playerId)
                .orElseThrow(() -> new IllegalStateException("플레이어 상태를 찾을 수 없습니다"));

        try {
            return switch (actionCode) {
                // === TF_MARS ===
                case "TF_MARS_VP" -> executeTfMarsVp(gameId, playerId, ps, actionCode);
                case "TF_MARS_GAIAFORM" -> executeTfMarsGaiaform(gameId, playerId, ps, actionCode, request.hexQ(), request.hexR(), request.qicUsed());
                case "TF_MARS_TERRAFORM" -> executeTfMarsTerraform(gameId, playerId, ps, actionCode);

                // === ECLIPSE ===
                case "ECLIPSE_VP" -> executeEclipseVp(gameId, playerId, ps, actionCode);
                case "ECLIPSE_TECH" -> executeEclipseTech(gameId, playerId, ps, actionCode, request.trackCode());
                case "ECLIPSE_MINE" -> executeEclipseMine(gameId, playerId, ps, actionCode, request.hexQ(), request.hexR());

                // === REBELLION ===
                case "REBELLION_TECH" -> executeRebellionTech(gameId, playerId, ps, actionCode, request.trackCode(), request.techTrackCode(), request.coveredTileCode(), Boolean.TRUE.equals(request.splitAction()));
                case "REBELLION_UPGRADE" -> executeRebellionUpgrade(gameId, playerId, ps, actionCode, request.hexQ(), request.hexR());
                case "REBELLION_CONVERT" -> executeRebellionConvert(gameId, playerId, ps, actionCode);

                // === TWILIGHT ===
                case "TWILIGHT_FED" -> executeTwilightFed(gameId, playerId, ps, actionCode, request.federationTileCode(), request.trackCode(), request.techTrackCode(), request.coveredTileCode());
                case "TWILIGHT_UPGRADE" -> executeTwilightUpgrade(gameId, playerId, ps, actionCode, request.hexQ(), request.hexR(), request.trackCode(), request.techTrackCode(), request.coveredTileCode());
                case "TWILIGHT_NAV" -> executeTwilightNav(gameId, playerId, ps, actionCode);
                case "TWILIGHT_ARTIFACT" -> executeTwilightArtifact(gameId, playerId, ps, actionCode, request.trackCode(), request.federationTileCode());

                default -> FleetShipActionResponse.fail(gameId, actionCode, "알 수 없는 함대 액션: " + actionCode);
            };
        } catch (IllegalStateException e) {
            return FleetShipActionResponse.fail(gameId, actionCode, e.getMessage());
        }
    }

    // ===========================
    // TF_MARS
    // ===========================

    /** TF_MARS_VP: QIC 2 → VP (보유 기술 타일 수 + 2) */
    private FleetShipActionResponse executeTfMarsVp(UUID gameId, UUID playerId, GamePlayerState ps, String code) {
        ps.spendQic(2);
        int techTileCount = techTileRepository.findByGameIdAndPlayerId(gameId, playerId).size();
        int vp = techTileCount + 2;
        ps.addVP(vp);
        vpLogService.logVp(ps.getGameId(), ps.getPlayerId(), VpCategory.FLEET, vp, null, "TF_MARS VP");
        playerStateRepository.save(ps);
        ConfirmActionResponse result = endTurn(gameId, playerId, code);
        log.info("[함대] {}: game={}, player={}, techTiles={}, vp={}", code, gameId, playerId, techTileCount, vp);
        return FleetShipActionResponse.success(gameId, code, vp, result.nextTurnSeatNo(), true);
    }

    /** TF_MARS_GAIAFORM: 파워 2 → TRANSDIM 행성에 가이아포머 즉시 배치 (토큰 소비 없음, 즉시 GAIA 변환) */
    private FleetShipActionResponse executeTfMarsGaiaform(UUID gameId, UUID playerId, GamePlayerState ps, String code,
                                                            Integer hexQ, Integer hexR, Integer qicUsed) {
        if (hexQ == null || hexR == null) return FleetShipActionResponse.fail(gameId, code, "헥스 좌표가 필요합니다");

        GameHex hex = hexRepository.findByGameIdAndHexQAndHexR(gameId, hexQ, hexR)
                .orElse(null);
        if (hex == null) return FleetShipActionResponse.fail(gameId, code, "유효하지 않은 좌표입니다");
        if (hex.getPlanetType() != PlanetType.TRANSDIM)
            return FleetShipActionResponse.fail(gameId, code, "차원변형 행성에만 즉시 가이아포밍이 가능합니다");
        if (buildingRepository.existsByGameIdAndHexQAndHexR(gameId, hexQ, hexR))
            return FleetShipActionResponse.fail(gameId, code, "이미 건물이 있는 위치입니다");

        // QIC 거리 확장 소모
        int qic = qicUsed != null ? qicUsed : 0;
        if (qic > 0) {
            if (ps.getQic() < qic) return FleetShipActionResponse.fail(gameId, code, "QIC가 부족합니다");
            ps.spendQic(qic);
        }

        // 파워 2 소비 + 가이아포머 재고 소모
        if (ps.getStockGaiaformer() <= 0) {
            return FleetShipActionResponse.fail(gameId, code, "사용 가능한 가이아포머가 없습니다");
        }
        ps.spendPower(2);
        ps.addGaiaformer(-1);  // 재고 소모, 광산 건설 시 반환
        playerStateRepository.save(ps);

        // 즉시 GAIA 변환
        hex.convertToGaia();
        hexRepository.save(hex);

        // 가이아포머 건물 배치 (이번 라운드 내 광산 건설 가능)
        GameBuilding gaiaformer = GameBuilding.place(gameId, playerId, hexQ, hexR, BuildingType.GAIAFORMER);
        buildingRepository.save(gaiaformer);

        ConfirmActionResponse result = endTurn(gameId, playerId, code);
        log.info("[함대] {}: game={}, player={}, hex=({},{})", code, gameId, playerId, hexQ, hexR);
        return FleetShipActionResponse.success(gameId, code, 0, result.nextTurnSeatNo(), true);
    }

    /** TF_MARS_TERRAFORM: 크레딧 3 소모, 다음 광산 건설 시 테라포밍 1단계 무료 (턴 종료 안 함) */
    private FleetShipActionResponse executeTfMarsTerraform(UUID gameId, UUID playerId, GamePlayerState ps, String code) {
        ps.spendCredit(3);
        playerStateRepository.save(ps);
        // 액션 기록 (사용 여부 추적용) — 턴 종료는 안 함
        actionService.saveActionOnly(gameId, playerId, com.gaiaproject.domain.enumtype.action.ActionType.FLEET_SHIP_ACTION,
                String.format("{\"actionCode\":\"%s\"}", code));
        log.info("[함대] {}: game={}, player={} (후속 광산 건설 대기)", code, gameId, playerId);
        return FleetShipActionResponse.success(gameId, code, 0, null, false);
    }

    // ===========================
    // ECLIPSE
    // ===========================

    /** ECLIPSE_VP: QIC 2 → VP (식민화한 고유 행성 타입 수 + 2) */
    private FleetShipActionResponse executeEclipseVp(UUID gameId, UUID playerId, GamePlayerState ps, String code) {
        ps.spendQic(2);
        List<GameBuilding> myBuildings = buildingRepository.findByGameIdAndPlayerId(gameId, playerId);
        Set<PlanetType> colonizedTypes = myBuildings.stream()
                .filter(b -> b.getBuildingType() != BuildingType.GAIAFORMER
                        && b.getBuildingType() != BuildingType.SPACE_STATION
                        && !b.isLantidsMine())
                .map(b -> hexRepository.findByGameIdAndHexQAndHexR(gameId, b.getHexQ(), b.getHexR())
                        .map(GameHex::getPlanetType).orElse(null))
                .filter(pt -> pt != null && pt != PlanetType.EMPTY && pt != PlanetType.TRANSDIM)
                .collect(Collectors.toSet());
        int vp = colonizedTypes.size() + 2;
        ps.addVP(vp);
        vpLogService.logVp(ps.getGameId(), ps.getPlayerId(), VpCategory.FLEET, vp, null, "ECLIPSE VP");
        playerStateRepository.save(ps);
        ConfirmActionResponse result = endTurn(gameId, playerId, code);
        log.info("[함대] {}: game={}, player={}, planetTypes={}, vp={}", code, gameId, playerId, colonizedTypes.size(), vp);
        return FleetShipActionResponse.success(gameId, code, vp, result.nextTurnSeatNo(), true);
    }

    /** ECLIPSE_TECH: 파워 2 + 지식 2 → 기술 트랙 1단계 전진 */
    private FleetShipActionResponse executeEclipseTech(UUID gameId, UUID playerId, GamePlayerState ps, String code, String trackCode) {
        if (trackCode == null || trackCode.isBlank())
            return FleetShipActionResponse.fail(gameId, code, "기술 트랙 코드가 필요합니다");
        ps.spendPower(3);
        ps.spendKnowledge(2);
        advanceTrack(gameId, ps, trackCode);
        playerStateRepository.save(ps);
        ConfirmActionResponse result = endTurn(gameId, playerId, code);
        log.info("[함대] {}: game={}, player={}, track={}", code, gameId, playerId, trackCode);
        return FleetShipActionResponse.success(gameId, code, 0, result.nextTurnSeatNo(), true);
    }

    /** ECLIPSE_MINE: 크레딧 6 → 소행성(ASTEROIDS) 행성에 광산 건설 */
    private FleetShipActionResponse executeEclipseMine(UUID gameId, UUID playerId, GamePlayerState ps, String code,
                                                        Integer hexQ, Integer hexR) {
        if (hexQ == null || hexR == null) return FleetShipActionResponse.fail(gameId, code, "헥스 좌표가 필요합니다");

        GameHex hex = hexRepository.findByGameIdAndHexQAndHexR(gameId, hexQ, hexR).orElse(null);
        if (hex == null) return FleetShipActionResponse.fail(gameId, code, "유효하지 않은 좌표입니다");
        if (hex.getPlanetType() != PlanetType.ASTEROIDS)
            return FleetShipActionResponse.fail(gameId, code, "소행성 행성에만 건설 가능합니다");
        if (buildingRepository.existsByGameIdAndHexQAndHexR(gameId, hexQ, hexR))
            return FleetShipActionResponse.fail(gameId, code, "이미 건물이 있는 위치입니다");
        if (ps.getStockMine() <= 0) return FleetShipActionResponse.fail(gameId, code, "광산 재고가 없습니다");

        ps.spendCredit(6);
        ps.decreaseStockMine();
        playerStateRepository.save(ps);

        GameBuilding mine = GameBuilding.place(gameId, playerId, hexQ, hexR, BuildingType.MINE);
        buildingRepository.save(mine);

        var game = gameRepository.findById(gameId).orElseThrow();

        // 라운드 점수: 광산 건설
        roundScoringService.award(gameId, game.getCurrentRound(), ps, com.gaiaproject.domain.enumtype.rounds.RoundScoringEvent.MINE_PLACED, 1);
        // 새 섹터 진출 점수
        if (buildingRepository.findByGameIdAndPlayerId(gameId, playerId).stream()
                .filter(b -> b.getId() != mine.getId())
                .noneMatch(b -> {
                    var h = hexRepository.findByGameIdAndHexQAndHexR(gameId, b.getHexQ(), b.getHexR()).orElse(null);
                    return h != null && h.getSectorId() != null && h.getSectorId().equals(hex.getSectorId());
                })) {
            roundScoringService.award(gameId, game.getCurrentRound(), ps, com.gaiaproject.domain.enumtype.rounds.RoundScoringEvent.NEW_SECTOR_ENTERED, 1);
        }

        // 인접 연방 자동 편입
        federationFormService.autoJoinFederation(gameId, playerId, hexQ, hexR);

        // 액션 저장 (턴 진행은 파워 리치 해소 후)
        String actionData = String.format("{\"actionCode\":\"%s\",\"hexQ\":%d,\"hexR\":%d}", code, hexQ, hexR);
        actionService.saveActionOnly(gameId, playerId, com.gaiaproject.domain.enumtype.action.ActionType.FLEET_SHIP_ACTION, actionData);

        // 파워 리치 처리 (인접 건물 파워 충전 알림 + 턴 진행 담당)
        List<GameBuilding> allBuildings = buildingRepository.findByGameId(gameId);
        powerLeechService.createBatchAndProcess(game, playerId, hexQ, hexR, BuildingType.MINE, allBuildings, null, null);

        log.info("[함대] {}: game={}, player={}, hex=({},{})", code, gameId, playerId, hexQ, hexR);
        return FleetShipActionResponse.success(gameId, code, 0, null, true);
    }

    // ===========================
    // REBELLION
    // ===========================

    /** REBELLION_TECH: QIC 3 → 기본 기술 타일 1장 획득 + 해당 트랙 1칸 전진 */
    private FleetShipActionResponse executeRebellionTech(UUID gameId, UUID playerId, GamePlayerState ps, String code, String tileCode, String techTrackCode, String coveredTileCode, boolean splitAction) {
        if (tileCode == null || tileCode.isBlank())
            return FleetShipActionResponse.fail(gameId, code, "기술 타일 코드가 필요합니다");

        // QIC 3 소모
        ps.spendQic(3);
        playerStateRepository.save(ps);

        // 기술 타일 획득 + 트랙 1칸 전진 (연구소/아카데미와 동일한 로직)
        try {
            var game = gameRepository.findById(gameId)
                    .orElseThrow(() -> new IllegalStateException("게임을 찾을 수 없습니다"));
            techTileService.acquireTileForBuilding(gameId, playerId, tileCode, techTrackCode, game.getEconomyTrackOption(), coveredTileCode);
        } catch (IllegalStateException e) {
            return FleetShipActionResponse.fail(gameId, code, e.getMessage());
        }

        // splitAction=true: 후속 광산 건설이 이어지므로 턴을 넘기지 않음
        if (splitAction) {
            String actionData = String.format("{\"actionCode\":\"%s\"}", code);
            actionService.saveActionOnly(gameId, playerId, ActionType.FLEET_SHIP_ACTION, actionData);
            log.info("[함대] {}: game={}, player={}, tile={}, track={} (split, 후속 광산 대기)", code, gameId, playerId, tileCode, techTrackCode);
            return FleetShipActionResponse.success(gameId, code, 0, null, false);
        }

        ConfirmActionResponse result = endTurn(gameId, playerId, code);
        log.info("[함대] {}: game={}, player={}, tile={}, track={}", code, gameId, playerId, tileCode, techTrackCode);
        return FleetShipActionResponse.success(gameId, code, 0, result.nextTurnSeatNo(), true);
    }

    /** REBELLION_UPGRADE: 파워 3 + 광석 1 → 자신의 광산을 교역소로 업그레이드 */
    private FleetShipActionResponse executeRebellionUpgrade(UUID gameId, UUID playerId, GamePlayerState ps, String code,
                                                              Integer hexQ, Integer hexR) {
        if (hexQ == null || hexR == null) return FleetShipActionResponse.fail(gameId, code, "헥스 좌표가 필요합니다");

        GameBuilding building = buildingRepository.findFirstByGameIdAndHexQAndHexRAndIsLantidsMine(gameId, hexQ, hexR, false).orElse(null);
        if (building == null) return FleetShipActionResponse.fail(gameId, code, "건물을 찾을 수 없습니다");
        if (!building.getPlayerId().equals(playerId)) return FleetShipActionResponse.fail(gameId, code, "본인 건물이 아닙니다");
        if (building.getBuildingType() != BuildingType.MINE) return FleetShipActionResponse.fail(gameId, code, "광산만 업그레이드 가능합니다");
        if (ps.getStockTradingStation() <= 0) return FleetShipActionResponse.fail(gameId, code, "교역소 재고가 없습니다");

        // 비용 지불
        ps.spendPower(3);
        ps.spendOre(1);
        playerStateRepository.save(ps);

        // 공용 코어: 재고/건물/라운드점수/리치 처리
        var game = gameRepository.findById(gameId).orElseThrow();
        String actionData = String.format("{\"actionCode\":\"%s\",\"hexQ\":%d,\"hexR\":%d}", code, hexQ, hexR);
        String error = buildingService.upgradeCore(game, playerId, building, BuildingType.TRADING_STATION,
                null, null, null, null,
                ActionType.FLEET_SHIP_ACTION, actionData, true);
        if (error != null) return FleetShipActionResponse.fail(gameId, code, error);

        log.info("[함대] {}: game={}, player={}, hex=({},{})", code, gameId, playerId, hexQ, hexR);
        return FleetShipActionResponse.success(gameId, code, 0, null, true);
    }

    /** REBELLION_CONVERT: 지식 2 → QIC 1 + 크레딧 2 */
    private FleetShipActionResponse executeRebellionConvert(UUID gameId, UUID playerId, GamePlayerState ps, String code) {
        ps.spendKnowledge(2);
        ps.addQic(1);
        ps.addCredit(2);
        playerStateRepository.save(ps);
        ConfirmActionResponse result = endTurn(gameId, playerId, code);
        log.info("[함대] {}: game={}, player={}", code, gameId, playerId);
        return FleetShipActionResponse.success(gameId, code, 0, result.nextTurnSeatNo(), true);
    }

    // ===========================
    // TWILIGHT
    // ===========================

    /** TWILIGHT_FED: QIC 3 → 선택한 연방 토큰 보상 재사용 */
    private FleetShipActionResponse executeTwilightFed(UUID gameId, UUID playerId, GamePlayerState ps, String code,
                                                        String federationTileCode, String trackCode, String techTrackCode, String coveredTileCode) {
        if (federationTileCode == null || federationTileCode.isEmpty()) {
            return FleetShipActionResponse.fail(gameId, code, "연방 토큰을 선택해야 합니다");
        }
        FederationTileType tileType;
        try { tileType = FederationTileType.valueOf(federationTileCode); }
        catch (Exception e) { return FleetShipActionResponse.fail(gameId, code, "알 수 없는 연방 토큰: " + federationTileCode); }

        if (!federationTokenRepository.existsByGameIdAndPlayerIdAndFederationTileType(gameId, playerId, tileType)) {
            return FleetShipActionResponse.fail(gameId, code, "해당 연방 토큰을 보유하고 있지 않습니다: " + federationTileCode);
        }

        ps.spendQic(3);
        var reward = tileType.getImmediateReward();
        ps.applyIncome(reward);
        if (reward.vp() > 0) {
            vpLogService.logVp(gameId, playerId, VpCategory.FLEET, reward.vp(), null, "TWILIGHT_FED VP (" + federationTileCode + ")");
        }
        playerStateRepository.save(ps);

        var specialAction = tileType.getSpecialAction();

        // 기술 타일 획득 (FED_EXP_TILE_1)
        if (specialAction == FederationActionType.GAIN_BASIC_TECH_TILE) {
            if (trackCode != null && !trackCode.isEmpty()) {
                var game = gameRepository.findById(gameId).orElseThrow();
                techTileService.acquireTileForBuilding(gameId, playerId, trackCode, techTrackCode, game.getEconomyTrackOption(), coveredTileCode);
            }
            ConfirmActionResponse result = endTurn(gameId, playerId, code);
            log.info("[함대] {}: game={}, player={}, tile={}", code, gameId, playerId, federationTileCode);
            return FleetShipActionResponse.success(gameId, code, 0, result.nextTurnSeatNo(), true);
        }

        // 3삽 광산 배치 (FED_EXP_TILE_5) — 후속 placeMine 호출 대기
        if (specialAction == FederationActionType.TERRAFORM_3_PLACE_MINE) {
            String actionData = String.format("{\"actionCode\":\"%s\",\"federationTileCode\":\"%s\"}", code, federationTileCode);
            actionService.saveActionOnly(gameId, playerId, ActionType.FLEET_SHIP_ACTION, actionData);
            log.info("[함대] {}: game={}, player={}, tile={} (3삽 광산 대기)", code, gameId, playerId, federationTileCode);
            return FleetShipActionResponse.success(gameId, code, 0, null, false);
        }

        // 무한거리 광산 배치 (FED_EXP_TILE_7) — 후속 placeMine 호출 대기
        if (specialAction == FederationActionType.PLACE_MINE_NO_RANGE_LIMIT) {
            String actionData = String.format("{\"actionCode\":\"%s\",\"federationTileCode\":\"%s\"}", code, federationTileCode);
            actionService.saveActionOnly(gameId, playerId, ActionType.FLEET_SHIP_ACTION, actionData);
            log.info("[함대] {}: game={}, player={}, tile={} (무한거리 광산 대기)", code, gameId, playerId, federationTileCode);
            return FleetShipActionResponse.success(gameId, code, 0, null, false);
        }

        // 일반 토큰: 즉시 턴 종료
        ConfirmActionResponse result = endTurn(gameId, playerId, code);
        log.info("[함대] {}: game={}, player={}, tile={}", code, gameId, playerId, federationTileCode);
        return FleetShipActionResponse.success(gameId, code, reward.vp(), result.nextTurnSeatNo(), true);
    }

    /** TWILIGHT_UPGRADE: 파워 3 + 광석 2 → 자신의 교역소를 연구소로 업그레이드 + 기술 타일 획득 */
    private FleetShipActionResponse executeTwilightUpgrade(UUID gameId, UUID playerId, GamePlayerState ps, String code,
                                                             Integer hexQ, Integer hexR,
                                                             String tileCode, String techTrackCode, String coveredTileCode) {
        if (hexQ == null || hexR == null) return FleetShipActionResponse.fail(gameId, code, "헥스 좌표가 필요합니다");

        GameBuilding building = buildingRepository.findFirstByGameIdAndHexQAndHexRAndIsLantidsMine(gameId, hexQ, hexR, false).orElse(null);
        if (building == null) return FleetShipActionResponse.fail(gameId, code, "건물을 찾을 수 없습니다");
        if (!building.getPlayerId().equals(playerId)) return FleetShipActionResponse.fail(gameId, code, "본인 건물이 아닙니다");
        if (building.getBuildingType() != BuildingType.TRADING_STATION)
            return FleetShipActionResponse.fail(gameId, code, "교역소만 업그레이드 가능합니다");
        if (ps.getStockResearchLab() <= 0) return FleetShipActionResponse.fail(gameId, code, "연구소 재고가 없습니다");

        // 비용 지불
        ps.spendPower(3);
        ps.spendOre(2);
        playerStateRepository.save(ps);

        // 공용 코어: 재고/건물/기술타일/리치 처리 (연구소 건설은 별도 RoundScoringEvent 없음)
        var game = gameRepository.findById(gameId).orElseThrow();
        String actionData = String.format("{\"actionCode\":\"%s\",\"hexQ\":%d,\"hexR\":%d}", code, hexQ, hexR);
        try {
            String error = buildingService.upgradeCore(game, playerId, building, BuildingType.RESEARCH_LAB,
                    null, tileCode, techTrackCode, coveredTileCode,
                    ActionType.FLEET_SHIP_ACTION, actionData, false);
            if (error != null) return FleetShipActionResponse.fail(gameId, code, error);
        } catch (IllegalStateException e) {
            return FleetShipActionResponse.fail(gameId, code, e.getMessage());
        }

        log.info("[함대] {}: game={}, player={}, hex=({},{})", code, gameId, playerId, hexQ, hexR);
        return FleetShipActionResponse.success(gameId, code, 0, null, true);
    }

    /** TWILIGHT_NAV: 지식 1 소모, 다음 광산 건설 시 항법 거리 +3 (턴 종료 안 함) */
    private FleetShipActionResponse executeTwilightNav(UUID gameId, UUID playerId, GamePlayerState ps, String code) {
        ps.spendKnowledge(1);
        playerStateRepository.save(ps);
        // 액션 기록 (사용 여부 추적용) — 턴 종료는 안 함
        actionService.saveActionOnly(gameId, playerId, com.gaiaproject.domain.enumtype.action.ActionType.FLEET_SHIP_ACTION,
                String.format("{\"actionCode\":\"%s\"}", code));
        log.info("[함대] {}: game={}, player={} (후속 광산 건설 대기)", code, gameId, playerId);
        return FleetShipActionResponse.success(gameId, code, 0, null, false);
    }

    /** TWILIGHT_ARTIFACT: 파워 6 소각 → 인공물 선택 획득 */
    private FleetShipActionResponse executeTwilightArtifact(UUID gameId, UUID playerId, GamePlayerState ps, String code, String artifactCode, String federationTileCode) {
        if (artifactCode == null || artifactCode.isBlank()) {
            return FleetShipActionResponse.fail(gameId, code, "인공물 코드가 필요합니다");
        }

        // ARTIFACT_13: 연방 토큰 보상 1회 받기
        if ("ARTIFACT_13".equals(artifactCode) && federationTileCode != null && !federationTileCode.isBlank()) {
            // 파워 6 소각
            artifactService.acquireArtifact(gameId, playerId, artifactCode);
            // 연방 토큰 보상 적용
            FederationTileType tileType;
            try { tileType = FederationTileType.valueOf(federationTileCode); }
            catch (Exception e) { return FleetShipActionResponse.fail(gameId, code, "알 수 없는 연방 토큰: " + federationTileCode); }
            ResourcesVo reward = tileType.getImmediateReward();
            ps = playerStateRepository.findByGameIdAndPlayerId(gameId, playerId).orElse(ps);
            ps.applyIncome(reward);
            if (reward.vp() > 0) vpLogService.logVp(gameId, playerId, VpCategory.ARTIFACT, reward.vp(), null, "ARTIFACT_13 연방토큰 보상: " + federationTileCode);
            playerStateRepository.save(ps);
            String actionData = String.format("{\"actionCode\":\"%s\",\"artifactCode\":\"%s\"}", code, artifactCode);
            ConfirmActionResponse result = actionService.saveActionAndNextTurn(gameId, playerId, ActionType.FLEET_SHIP_ACTION, actionData);
            log.info("[함대] {}: game={}, player={}, artifact={}, fedToken={}", code, gameId, playerId, artifactCode, federationTileCode);
            return FleetShipActionResponse.success(gameId, code, reward.vp(), result.nextTurnSeatNo(), true);
        }

        artifactService.acquireArtifact(gameId, playerId, artifactCode);
        String actionData = String.format("{\"actionCode\":\"%s\",\"artifactCode\":\"%s\"}", code, artifactCode);
        ConfirmActionResponse result = actionService.saveActionAndNextTurn(gameId, playerId, ActionType.FLEET_SHIP_ACTION, actionData);
        log.info("[함대] {}: game={}, player={}, artifact={}", code, gameId, playerId, artifactCode);
        return FleetShipActionResponse.success(gameId, code, 0, result.nextTurnSeatNo(), true);
    }

    // ===========================
    // 헬퍼
    // ===========================

    /** 기술 트랙 1단계 전진 (지식 소모 없음, 코드만 받아 해당 트랙 +1 + 즉시 보상 + 라운드 점수) */
    private void advanceTrack(UUID gameId, GamePlayerState ps, String trackCode) {
        switch (trackCode) {
            case "TERRA_FORMING" -> { if (ps.getTechTerraforming() >= 5) throw new IllegalStateException("이미 최대 레벨입니다"); }
            case "NAVIGATION"    -> { if (ps.getTechNavigation() >= 5)   throw new IllegalStateException("이미 최대 레벨입니다"); }
            case "AI"            -> { if (ps.getTechAi() >= 5)           throw new IllegalStateException("이미 최대 레벨입니다"); }
            case "GAIA_FORMING"  -> { if (ps.getTechGaia() >= 5)         throw new IllegalStateException("이미 최대 레벨입니다"); }
            case "ECONOMY"       -> { if (ps.getTechEconomy() >= 5)      throw new IllegalStateException("이미 최대 레벨입니다"); }
            case "SCIENCE"       -> { if (ps.getTechScience() >= 5)      throw new IllegalStateException("이미 최대 레벨입니다"); }
            default -> throw new IllegalArgumentException("알 수 없는 트랙 코드: " + trackCode);
        }
        // GamePlayerState.advanceTechTrack은 지식 4를 소모하므로 직접 필드 조작
        switch (trackCode) {
            case "TERRA_FORMING" -> incrementField(ps, "techTerraforming");
            case "NAVIGATION"    -> incrementField(ps, "techNavigation");
            case "AI"            -> incrementField(ps, "techAi");
            case "GAIA_FORMING"  -> incrementField(ps, "techGaia");
            case "ECONOMY"       -> incrementField(ps, "techEconomy");
            case "SCIENCE"       -> incrementField(ps, "techScience");
        }
        // 즉시 보상 적용
        int newLevel = ps.getTechLevel(trackCode);
        var game = gameRepository.findById(gameId).orElse(null);
        if (game != null) {
            techTileService.applyTechTrackReward(ps, trackCode, newLevel, game.getEconomyTrackOption());
            if (game.getCurrentRound() != null) {
                roundScoringService.award(gameId, game.getCurrentRound(), ps,
                        com.gaiaproject.domain.enumtype.rounds.RoundScoringEvent.RESEARCH_ADVANCED, 1);
            }
        }
    }

    /** 리플렉션 없이 트랙 필드 증가 (GamePlayerState에 fleet용 메서드 추가) */
    private void incrementField(GamePlayerState ps, String field) {
        ps.advanceTechTrackNoKnowledge(field);
    }

    private ConfirmActionResponse endTurn(UUID gameId, UUID playerId, String actionCode) {
        String actionData = String.format("{\"actionCode\":\"%s\"}", actionCode);
        return actionService.saveActionAndNextTurn(gameId, playerId, ActionType.FLEET_SHIP_ACTION, actionData);
    }

    /** 액션 코드에서 함대 이름 추출 */
    private String getFleetName(String actionCode) {
        if (actionCode.startsWith("TF_MARS_")) return "TF_MARS";
        if (actionCode.startsWith("ECLIPSE_")) return "ECLIPSE";
        if (actionCode.startsWith("REBELLION_")) return "REBELLION";
        if (actionCode.startsWith("TWILIGHT_")) return "TWILIGHT";
        return null;
    }
}
