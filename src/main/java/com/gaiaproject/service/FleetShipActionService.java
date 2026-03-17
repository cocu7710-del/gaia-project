package com.gaiaproject.service;

import com.gaiaproject.domain.entity.building.GameBuilding;
import com.gaiaproject.domain.entity.map.GameHex;
import com.gaiaproject.domain.entity.player.GamePlayerState;
import com.gaiaproject.domain.entity.player.GamePlayerTechTile;
import com.gaiaproject.domain.entity.tech.GameTechOffer;
import com.gaiaproject.domain.enumtype.action.ActionType;
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
    private final com.gaiaproject.repository.game.GameRepository gameRepository;

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
                case "TF_MARS_GAIAFORM" -> executeTfMarsGaiaform(gameId, playerId, ps, actionCode, request.hexQ(), request.hexR());
                case "TF_MARS_TERRAFORM" -> executeTfMarsTerraform(gameId, playerId, ps, actionCode);

                // === ECLIPSE ===
                case "ECLIPSE_VP" -> executeEclipseVp(gameId, playerId, ps, actionCode);
                case "ECLIPSE_TECH" -> executeEclipseTech(gameId, playerId, ps, actionCode, request.trackCode());
                case "ECLIPSE_MINE" -> executeEclipseMine(gameId, playerId, ps, actionCode, request.hexQ(), request.hexR());

                // === REBELLION ===
                case "REBELLION_TECH" -> executeRebellionTech(gameId, playerId, ps, actionCode, request.trackCode(), request.techTrackCode());
                case "REBELLION_UPGRADE" -> executeRebellionUpgrade(gameId, playerId, ps, actionCode, request.hexQ(), request.hexR());
                case "REBELLION_CONVERT" -> executeRebellionConvert(gameId, playerId, ps, actionCode);

                // === TWILIGHT ===
                case "TWILIGHT_FED" -> executeTwilightFed(gameId, playerId, ps, actionCode);
                case "TWILIGHT_UPGRADE" -> executeTwilightUpgrade(gameId, playerId, ps, actionCode, request.hexQ(), request.hexR(), request.trackCode(), request.techTrackCode());
                case "TWILIGHT_NAV" -> executeTwilightNav(gameId, playerId, ps, actionCode);
                case "TWILIGHT_ARTIFACT" -> executeTwilightArtifact(gameId, playerId, ps, actionCode, request.trackCode());

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
        playerStateRepository.save(ps);
        ConfirmActionResponse result = endTurn(gameId, playerId, code);
        log.info("[함대] {}: game={}, player={}, techTiles={}, vp={}", code, gameId, playerId, techTileCount, vp);
        return FleetShipActionResponse.success(gameId, code, vp, result.nextTurnSeatNo(), true);
    }

    /** TF_MARS_GAIAFORM: 파워 2 → TRANSDIM 행성에 가이아포머 즉시 배치 (토큰 소비 없음, 즉시 GAIA 변환) */
    private FleetShipActionResponse executeTfMarsGaiaform(UUID gameId, UUID playerId, GamePlayerState ps, String code,
                                                            Integer hexQ, Integer hexR) {
        if (hexQ == null || hexR == null) return FleetShipActionResponse.fail(gameId, code, "헥스 좌표가 필요합니다");

        GameHex hex = hexRepository.findByGameIdAndHexQAndHexR(gameId, hexQ, hexR)
                .orElse(null);
        if (hex == null) return FleetShipActionResponse.fail(gameId, code, "유효하지 않은 좌표입니다");
        if (hex.getPlanetType() != PlanetType.TRANSDIM)
            return FleetShipActionResponse.fail(gameId, code, "차원변형 행성에만 즉시 가이아포밍이 가능합니다");
        if (buildingRepository.existsByGameIdAndHexQAndHexR(gameId, hexQ, hexR))
            return FleetShipActionResponse.fail(gameId, code, "이미 건물이 있는 위치입니다");

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
        // 턴 종료 안 함 — FE가 후속 placeMineInPlay(terraformDiscount=1) 호출
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
                .map(b -> hexRepository.findByGameIdAndHexQAndHexR(gameId, b.getHexQ(), b.getHexR())
                        .map(GameHex::getPlanetType).orElse(null))
                .filter(pt -> pt != null && pt != PlanetType.EMPTY && pt != PlanetType.TRANSDIM && pt != PlanetType.GAIA)
                .collect(Collectors.toSet());
        int vp = colonizedTypes.size() + 2;
        ps.addVP(vp);
        playerStateRepository.save(ps);
        ConfirmActionResponse result = endTurn(gameId, playerId, code);
        log.info("[함대] {}: game={}, player={}, planetTypes={}, vp={}", code, gameId, playerId, colonizedTypes.size(), vp);
        return FleetShipActionResponse.success(gameId, code, vp, result.nextTurnSeatNo(), true);
    }

    /** ECLIPSE_TECH: 파워 2 + 지식 2 → 기술 트랙 1단계 전진 */
    private FleetShipActionResponse executeEclipseTech(UUID gameId, UUID playerId, GamePlayerState ps, String code, String trackCode) {
        if (trackCode == null || trackCode.isBlank())
            return FleetShipActionResponse.fail(gameId, code, "기술 트랙 코드가 필요합니다");
        ps.spendPower(2);
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

        ConfirmActionResponse result = endTurn(gameId, playerId, code);
        log.info("[함대] {}: game={}, player={}, hex=({},{})", code, gameId, playerId, hexQ, hexR);
        return FleetShipActionResponse.success(gameId, code, 0, result.nextTurnSeatNo(), true);
    }

    // ===========================
    // REBELLION
    // ===========================

    /** REBELLION_TECH: QIC 3 → 기본 기술 타일 1장 획득 + 해당 트랙 1칸 전진 */
    private FleetShipActionResponse executeRebellionTech(UUID gameId, UUID playerId, GamePlayerState ps, String code, String tileCode, String techTrackCode) {
        if (tileCode == null || tileCode.isBlank())
            return FleetShipActionResponse.fail(gameId, code, "기술 타일 코드가 필요합니다");

        // QIC 3 소모
        ps.spendQic(3);
        playerStateRepository.save(ps);

        // 기술 타일 획득 + 트랙 1칸 전진 (연구소/아카데미와 동일한 로직)
        try {
            var game = gameRepository.findById(gameId)
                    .orElseThrow(() -> new IllegalStateException("게임을 찾을 수 없습니다"));
            techTileService.acquireTileForBuilding(gameId, playerId, tileCode, techTrackCode, game.getEconomyTrackOption());
        } catch (IllegalStateException e) {
            return FleetShipActionResponse.fail(gameId, code, e.getMessage());
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

        ps.spendPower(3);
        ps.spendOre(1);
        ps.decreaseStockTradingStation();
        ps.addMineToStock();
        playerStateRepository.save(ps);

        building.upgrade(BuildingType.TRADING_STATION);
        buildingRepository.save(building);

        ConfirmActionResponse result = endTurn(gameId, playerId, code);
        log.info("[함대] {}: game={}, player={}, hex=({},{})", code, gameId, playerId, hexQ, hexR);
        return FleetShipActionResponse.success(gameId, code, 0, result.nextTurnSeatNo(), true);
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

    /** TWILIGHT_FED: QIC 3 → 연방 수입 (1 QIC + 1 광석 + 2 VP, simplified) */
    private FleetShipActionResponse executeTwilightFed(UUID gameId, UUID playerId, GamePlayerState ps, String code) {
        ps.spendQic(3);
        ps.addQic(1);
        ps.addOre(1);
        ps.addVP(2);
        playerStateRepository.save(ps);
        ConfirmActionResponse result = endTurn(gameId, playerId, code);
        log.info("[함대] {}: game={}, player={}", code, gameId, playerId);
        return FleetShipActionResponse.success(gameId, code, 2, result.nextTurnSeatNo(), true);
    }

    /** TWILIGHT_UPGRADE: 파워 3 + 광석 2 → 자신의 교역소를 연구소로 업그레이드 + 기술 타일 획득 */
    private FleetShipActionResponse executeTwilightUpgrade(UUID gameId, UUID playerId, GamePlayerState ps, String code,
                                                             Integer hexQ, Integer hexR,
                                                             String tileCode, String techTrackCode) {
        if (hexQ == null || hexR == null) return FleetShipActionResponse.fail(gameId, code, "헥스 좌표가 필요합니다");

        GameBuilding building = buildingRepository.findFirstByGameIdAndHexQAndHexRAndIsLantidsMine(gameId, hexQ, hexR, false).orElse(null);
        if (building == null) return FleetShipActionResponse.fail(gameId, code, "건물을 찾을 수 없습니다");
        if (!building.getPlayerId().equals(playerId)) return FleetShipActionResponse.fail(gameId, code, "본인 건물이 아닙니다");
        if (building.getBuildingType() != BuildingType.TRADING_STATION)
            return FleetShipActionResponse.fail(gameId, code, "교역소만 업그레이드 가능합니다");
        if (ps.getStockResearchLab() <= 0) return FleetShipActionResponse.fail(gameId, code, "연구소 재고가 없습니다");

        ps.spendPower(3);
        ps.spendOre(2);
        ps.decreaseStockResearchLab();
        ps.addTradingStationToStock();
        playerStateRepository.save(ps);

        building.upgrade(BuildingType.RESEARCH_LAB);
        buildingRepository.save(building);

        // 기술 타일 획득 (연구소 업그레이드이므로)
        if (tileCode != null && !tileCode.isBlank()) {
            try {
                var game = gameRepository.findById(gameId).orElseThrow();
                techTileService.acquireTileForBuilding(gameId, playerId, tileCode, techTrackCode, game.getEconomyTrackOption());
            } catch (IllegalStateException e) {
                return FleetShipActionResponse.fail(gameId, code, "기술 타일 획득 실패: " + e.getMessage());
            }
        }

        ConfirmActionResponse result = endTurn(gameId, playerId, code);
        log.info("[함대] {}: game={}, player={}, hex=({},{})", code, gameId, playerId, hexQ, hexR);
        return FleetShipActionResponse.success(gameId, code, 0, result.nextTurnSeatNo(), true);
    }

    /** TWILIGHT_NAV: 지식 1 소모, 다음 광산 건설 시 항법 거리 +3 (턴 종료 안 함) */
    private FleetShipActionResponse executeTwilightNav(UUID gameId, UUID playerId, GamePlayerState ps, String code) {
        ps.spendKnowledge(1);
        playerStateRepository.save(ps);
        // 턴 종료 안 함 — FE가 후속 placeMineInPlay 호출
        log.info("[함대] {}: game={}, player={} (후속 광산 건설 대기)", code, gameId, playerId);
        return FleetShipActionResponse.success(gameId, code, 0, null, false);
    }

    /** TWILIGHT_ARTIFACT: 파워 6 소각 → 인공물 선택 획득 */
    private FleetShipActionResponse executeTwilightArtifact(UUID gameId, UUID playerId, GamePlayerState ps, String code, String artifactCode) {
        if (artifactCode == null || artifactCode.isBlank()) {
            return FleetShipActionResponse.fail(gameId, code, "인공물 코드가 필요합니다");
        }
        artifactService.acquireArtifact(gameId, playerId, artifactCode);
        // acquireArtifact에서 파워 소각 + 즉시 효과 + DB 기록 모두 처리
        ConfirmActionResponse result = endTurn(gameId, playerId, code);
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
