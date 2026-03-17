package com.gaiaproject.service;

import com.gaiaproject.domain.entity.building.GameBuilding;
import com.gaiaproject.domain.entity.game.Game;
import com.gaiaproject.domain.entity.game.GameSeat;
import com.gaiaproject.domain.entity.map.GameHex;
import com.gaiaproject.domain.entity.player.GamePlayerState;
import com.gaiaproject.domain.enumtype.action.ActionType;
import com.gaiaproject.domain.enumtype.building.BuildingType;
import com.gaiaproject.domain.enumtype.player.FactionType;
import com.gaiaproject.domain.enumtype.player.PlanetType;
import com.gaiaproject.domain.enumtype.rounds.RoundScoringEvent;
import com.gaiaproject.dto.request.PlaceInitialMineRequest;
import com.gaiaproject.dto.request.PlaceMinePlayRequest;
import com.gaiaproject.dto.request.UpgradeBuildingRequest;
import com.gaiaproject.dto.response.PlaceInitialMineResponse;
import com.gaiaproject.dto.response.PlaceMinePlayResponse;
import com.gaiaproject.dto.response.UpgradeBuildingResponse;
import com.gaiaproject.repository.building.GameBuildingRepository;
import com.gaiaproject.repository.game.GameRepository;
import com.gaiaproject.repository.game.GameSeatRepository;
import com.gaiaproject.repository.map.GameHexRepository;
import com.gaiaproject.repository.player.GamePlayerStateRepository;
import com.gaiaproject.repository.tech.GamePlayerTechTileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BuildingService {

    private final GameRepository gameRepository;
    private final GameSeatRepository gameSeatRepository;
    private final GamePlayerStateRepository gamePlayerStateRepository;
    private final GameBuildingRepository gameBuildingRepository;
    private final GameHexRepository gameHexRepository;
    private final IncomeService incomeService;
    private final GameWebSocketService webSocketService;
    private final ActionService actionService;
    private final TechTileService techTileService;
    private final GamePlayerTechTileRepository playerTechTileRepository;
    private final RoundScoringService roundScoringService;
    private final PowerLeechService powerLeechService;
    private final com.gaiaproject.repository.player.GamePlayerFederationTokenRepository federationTokenRepository;
    private final FederationFormService federationFormService;

    /**
     * 초기 광산 배치 (게임 시작 전)
     * 순서: 1→2→3→4→4→3→2→1 (snake draft)
     */
    @Transactional
    public PlaceInitialMineResponse placeInitialMine(UUID gameId, PlaceInitialMineRequest request) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new IllegalArgumentException("게임을 찾을 수 없습니다: " + gameId));

        // 1. 게임 페이즈 확인
        if (!game.isInSetupPhase()) {
            throw new IllegalStateException("초기 광산 배치 단계가 아닙니다. 현재 페이즈: " + game.getGamePhase());
        }

        // 2. 현재 배치할 좌석 확인
        int currentSetupSeatNo = game.getCurrentSetupSeatNo();
        GameSeat currentSeat = gameSeatRepository.findByGameIdAndSeatNo(gameId, currentSetupSeatNo)
                .orElseThrow(() -> new IllegalStateException("좌석을 찾을 수 없습니다: " + currentSetupSeatNo));

        // 3. 플레이어 검증
        if (!currentSeat.getPlayerId().equals(request.playerId())) {
            throw new IllegalArgumentException(
                    "현재 배치 순서가 아닙니다. 현재 배치 순서: 좌석 " + currentSetupSeatNo
            );
        }

        // 4. 헥스 유효성 및 행성 타입 확인 (game_hex에서 조회)
        GameHex hex = gameHexRepository.findByGameIdAndHexQAndHexR(gameId, request.hexQ(), request.hexR())
                .orElseThrow(() -> new IllegalArgumentException(
                        "유효하지 않은 좌표입니다: (" + request.hexQ() + ", " + request.hexR() + ")"
                ));

        // 5. 헥스 점유 확인
        if (gameBuildingRepository.existsByGameIdAndHexQAndHexR(gameId, request.hexQ(), request.hexR())) {
            throw new IllegalArgumentException(
                    "이미 건물이 있는 위치입니다: (" + request.hexQ() + ", " + request.hexR() + ")"
            );
        }

        // 6. 행성 타입 검증 (종족의 홈 플래닛과 일치해야 함)
        FactionType faction = currentSeat.getFactionType();
        PlanetType homePlanet = faction.getHomePlanet();
        PlanetType hexPlanet = hex.getPlanetType();

        // BE에서 조회한 행성 타입과 홈 플래닛 비교
        if (!hexPlanet.equals(homePlanet)) {
            throw new IllegalArgumentException(
                    "종족의 홈 플래닛에만 광산을 배치할 수 있습니다. " +
                    "종족: " + faction.getDisplayNameKo() + ", 홈 플래닛: " + homePlanet.getDisplayNameKo() +
                    ", 선택된 행성: " + hexPlanet.getDisplayNameKo()
            );
        }

        // 6. 플레이어 상태 조회 및 재고 확인
        GamePlayerState playerState = gamePlayerStateRepository.findByGameIdAndPlayerId(gameId, request.playerId())
                .orElseThrow(() -> new IllegalStateException("플레이어 상태를 찾을 수 없습니다: " + request.playerId()));

        boolean isSetupPI = faction.isSetupPlanetaryInstitute();
        if (isSetupPI) {
            if (playerState.getStockPlanetaryInstitute() <= 0) {
                throw new IllegalStateException("행성 의회 재고가 없습니다.");
            }
        } else {
            if (playerState.getStockMine() <= 0) {
                throw new IllegalStateException("광산 재고가 없습니다.");
            }
        }

        // 7. 건물 배치 (종족에 따라 광산 또는 행성 의회)
        BuildingType buildingType = isSetupPI ? BuildingType.PLANETARY_INSTITUTE : BuildingType.MINE;
        GameBuilding mine = GameBuilding.place(
                gameId,
                request.playerId(),
                request.hexQ(),
                request.hexR(),
                buildingType
        );
        gameBuildingRepository.save(mine);

        // 8. 재고 감소
        if (isSetupPI) {
            playerState.decreaseStockPlanetaryInstitute();
        } else {
            playerState.decreaseStockMine();
        }
        gamePlayerStateRepository.save(playerState);

        // 9. 다음 배치 턴으로
        game.nextMinePlacement();
        gameRepository.save(game);

        log.info("초기 광산 배치 완료: 게임={}, 좌석={}, 위치=({},{}), 남은재고={}",
                gameId, currentSetupSeatNo, request.hexQ(), request.hexR(), playerState.getStockMine());

        // 10. WebSocket 브로드캐스트 - 광산 배치 알림
        Integer nextSeatNoForBroadcast = game.isInSetupPhase() ? game.getCurrentSetupSeatNo() : null;
        webSocketService.broadcastMinePlaced(
                gameId,
                request.playerId(),
                currentSetupSeatNo,
                request.hexQ(),
                request.hexR(),
                nextSeatNoForBroadcast,
                game.getGamePhase()
        );

        // 11. 광산 배치 완료 여부 확인 (부스터 선택 단계로 진입)
        //     수입 적용은 부스터 선택 완료 후에 진행됨
        boolean isSetupComplete = !game.isInSetupPhase();
        if (isSetupComplete) {
            log.info("초기 광산 배치 완료. 부스터 선택 단계로 진입: 게임={}", gameId);
        }
        Integer nextSeatNo = isSetupComplete ? null : game.getCurrentSetupSeatNo();
        String nextPhase = game.getGamePhase();

        return new PlaceInitialMineResponse(
                gameId,
                mine.getId(),
                request.hexQ(),
                request.hexR(),
                currentSetupSeatNo,
                playerState.getStockMine(),
                isSetupComplete,
                nextSeatNo,
                nextPhase,
                buildingType.name()
        );
    }

    /**
     * 게임 내 모든 건물 조회
     */
    @Transactional(readOnly = true)
    public List<GameBuilding> getBuildingsByGame(UUID gameId) {
        return gameBuildingRepository.findByGameId(gameId);
    }

    /**
     * 특정 플레이어의 건물 조회
     */
    @Transactional(readOnly = true)
    public List<GameBuilding> getBuildingsByPlayer(UUID gameId, UUID playerId) {
        return gameBuildingRepository.findByGameIdAndPlayerId(gameId, playerId);
    }

    /**
     * 특정 플레이어의 특정 타입 건물 수
     */
    @Transactional(readOnly = true)
    public int countBuildingsByType(UUID gameId, UUID playerId, BuildingType buildingType) {
        return gameBuildingRepository.countByGameIdAndPlayerIdAndBuildingType(gameId, playerId, buildingType);
    }

    /**
     * 건물 업그레이드 (PLAYING 페이즈)
     */
    @Transactional
    public UpgradeBuildingResponse upgradeBuildingInPlay(UUID gameId, UpgradeBuildingRequest request) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new IllegalArgumentException("게임을 찾을 수 없습니다"));

        if (!"PLAYING".equals(game.getGamePhase())) {
            return UpgradeBuildingResponse.fail(gameId, "PLAYING 페이즈가 아닙니다");
        }

        // 건물 조회 (메인 건물, 란티다 기생 제외)
        GameBuilding building = gameBuildingRepository
                .findFirstByGameIdAndHexQAndHexRAndIsLantidsMine(gameId, request.hexQ(), request.hexR(), false)
                .orElse(null);
        if (building == null) return UpgradeBuildingResponse.fail(gameId, "건물을 찾을 수 없습니다");
        if (!building.getPlayerId().equals(request.playerId())) return UpgradeBuildingResponse.fail(gameId, "본인 건물이 아닙니다");
        if (building.isLantidsMine()) return UpgradeBuildingResponse.fail(gameId, "란티다 특수 광산은 업그레이드할 수 없습니다");

        BuildingType fromType = building.getBuildingType();
        BuildingType targetType;
        try {
            targetType = BuildingType.valueOf(request.targetBuildingType());
        } catch (Exception e) {
            return UpgradeBuildingResponse.fail(gameId, "잘못된 건물 타입입니다");
        }

        // 업그레이드 경로 유효성 검증
        boolean validPath = switch (fromType) {
            case MINE -> targetType == BuildingType.TRADING_STATION;
            case TRADING_STATION -> targetType == BuildingType.RESEARCH_LAB || targetType == BuildingType.PLANETARY_INSTITUTE;
            case RESEARCH_LAB -> targetType == BuildingType.ACADEMY;
            default -> false;
        };
        if (!validPath) return UpgradeBuildingResponse.fail(gameId, "업그레이드 경로가 올바르지 않습니다");

        GamePlayerState playerState = gamePlayerStateRepository.findByGameIdAndPlayerId(gameId, request.playerId())
                .orElseThrow(() -> new IllegalStateException("플레이어 상태를 찾을 수 없습니다"));

        // 2거리 이내 다른 플레이어 건물 목록
        List<GameBuilding> allBuildings = gameBuildingRepository.findByGameId(gameId);
        boolean hasEnemyNeighbor = allBuildings.stream()
                .anyMatch(b -> !b.getPlayerId().equals(request.playerId())
                        && hexDistance(request.hexQ(), request.hexR(), b.getHexQ(), b.getHexR()) <= 2);

        // 비용 검증 및 지불
        try {
            switch (targetType) {
                case TRADING_STATION -> {
                    if (playerState.getStockTradingStation() <= 0) return UpgradeBuildingResponse.fail(gameId, "교역소 재고가 없습니다");
                    int creditCost = hasEnemyNeighbor ? 3 : 6; // 이웃 있으면 3c, 없으면 6c
                    playerState.spendCredit(creditCost);
                    playerState.spendOre(2);
                    playerState.decreaseStockTradingStation();
                }
                case RESEARCH_LAB -> {
                    if (playerState.getStockResearchLab() <= 0) return UpgradeBuildingResponse.fail(gameId, "연구소 재고가 없습니다");
                    playerState.spendCredit(5);
                    playerState.spendOre(3);
                    playerState.decreaseStockResearchLab();
                }
                case PLANETARY_INSTITUTE -> {
                    if (playerState.getStockPlanetaryInstitute() <= 0) return UpgradeBuildingResponse.fail(gameId, "행성 의회 재고가 없습니다");
                    playerState.spendCredit(6);
                    playerState.spendOre(4);
                    playerState.decreaseStockPlanetaryInstitute();
                }
                case ACADEMY -> {
                    if (playerState.getStockAcademy() <= 0) return UpgradeBuildingResponse.fail(gameId, "아카데미 재고가 없습니다");
                    // 아카데미 종류 검증
                    if (request.academyType() == null || request.academyType().isBlank()) {
                        return UpgradeBuildingResponse.fail(gameId, "아카데미 종류를 선택해야 합니다 (KNOWLEDGE / QIC)");
                    }
                    com.gaiaproject.domain.enumtype.building.AcademyType acaType;
                    try {
                        acaType = com.gaiaproject.domain.enumtype.building.AcademyType.valueOf(request.academyType());
                    } catch (Exception e) {
                        return UpgradeBuildingResponse.fail(gameId, "잘못된 아카데미 종류입니다: " + request.academyType());
                    }
                    playerState.spendCredit(6);
                    playerState.spendOre(6);
                    playerState.decreaseStockAcademy();
                }
                default -> { return UpgradeBuildingResponse.fail(gameId, "업그레이드 불가 건물 타입"); }
            }
        } catch (IllegalStateException e) {
            return UpgradeBuildingResponse.fail(gameId, e.getMessage());
        }

        // 이전 건물 타입 재고 반환
        switch (fromType) {
            case MINE -> playerState.addMineToStock();
            case TRADING_STATION -> playerState.addTradingStationToStock();
            case RESEARCH_LAB -> playerState.addResearchLabToStock();
            default -> {}
        }

        gamePlayerStateRepository.save(playerState);

        // 건물 업그레이드
        if (targetType == BuildingType.ACADEMY && request.academyType() != null) {
            var acType = com.gaiaproject.domain.enumtype.building.AcademyType.valueOf(request.academyType());
            building.upgradeToAcademy(acType);
            // 글린: QIC 아카데미 건설 시 QIC→ORE 변환 해제
            if (acType == com.gaiaproject.domain.enumtype.building.AcademyType.QIC
                    && playerState.getFactionType() == FactionType.GLEENS) {
                playerState.setGleensHasQicAcademy(true);
                gamePlayerStateRepository.save(playerState);
            }
        } else {
            building.upgrade(targetType);
        }
        gameBuildingRepository.save(building);

        log.info("건물 업그레이드: game={}, player={}, ({},{}) {}→{}", gameId, request.playerId(), request.hexQ(), request.hexR(), fromType, targetType);

        // 라운드 점수 타일 적용
        int upgradeRound = game.getCurrentRound() != null ? game.getCurrentRound() : 1;
        switch (targetType) {
            case TRADING_STATION -> roundScoringService.award(gameId, upgradeRound, playerState, RoundScoringEvent.TRADING_STATION_BUILT, 1);
            case PLANETARY_INSTITUTE -> roundScoringService.award(gameId, upgradeRound, playerState, RoundScoringEvent.PLANETARY_INSTITUTE_BUILT, 1);
            case ACADEMY -> roundScoringService.award(gameId, upgradeRound, playerState, RoundScoringEvent.ACADEMY_BUILT, 1);
            default -> {}
        }

        // 글린 PI 건설 시 글린 전용 연방 토큰 즉시 지급 (즉시 효과: 2c+1o+1k)
        if (targetType == BuildingType.PLANETARY_INSTITUTE
                && playerState.getFactionType() == FactionType.GLEENS) {
            var gleensFedToken = com.gaiaproject.domain.entity.player.GamePlayerFederationToken.builder()
                    .gameId(gameId)
                    .playerId(request.playerId())
                    .federationTileType(com.gaiaproject.domain.enumtype.federation.FederationTileType.GLEENS_FEDERATION)
                    .build();
            federationTokenRepository.save(gleensFedToken);
            playerState.addCredit(2);
            playerState.addOre(1);
            playerState.addKnowledge(1);
            // 라운드 점수: 연방 토큰 획득
            roundScoringService.award(gameId, upgradeRound, playerState,
                    com.gaiaproject.domain.enumtype.rounds.RoundScoringEvent.FEDERATION_FORMED, 1);
            log.info("[GLEENS PI] 전용 연방 토큰 지급 + 즉시 보상 2c 1o 1k + 라운드점수: player={}", request.playerId());
        }

        gamePlayerStateRepository.save(playerState);

        // 연구소/아카데미/스페이스자이언트 의회 건설 시 기술 타일 획득 (선택)
        boolean isTileEligible = targetType == BuildingType.RESEARCH_LAB
                || targetType == BuildingType.ACADEMY
                || (targetType == BuildingType.PLANETARY_INSTITUTE
                    && playerState.getFactionType() == FactionType.SPACE_GIANTS);
        log.info("[TILE_ELIGIBLE] targetType={}, isTileEligible={}, techTileCode={}, techTrackCode={}",
                targetType, isTileEligible, request.techTileCode(), request.techTrackCode());
        if (isTileEligible && request.techTileCode() != null && !request.techTileCode().isBlank()) {
            try {
                log.info("[TILE_ACQUIRE] acquireTileForBuilding 호출: tile={}, track={}", request.techTileCode(), request.techTrackCode());
                techTileService.acquireTileForBuilding(
                        gameId, request.playerId(),
                        request.techTileCode(), request.techTrackCode(),
                        game.getEconomyTrackOption());
                log.info("[TILE_ACQUIRE] 성공: tile={}", request.techTileCode());
            } catch (IllegalStateException e) {
                log.error("[TILE_ACQUIRE] 실패: {}", e.getMessage());
                return UpgradeBuildingResponse.fail(gameId, "기술 타일 획득 실패: " + e.getMessage());
            }
        } else if (isTileEligible) {
            log.warn("[TILE_ACQUIRE] 스킵 - techTileCode 미전달: techTileCode={}", request.techTileCode());
        }

        // TERRAFORM_2_PLACE_MINE 타일 여부 확인
        String leechFollowUpType = null;
        String leechFollowUpData = null;
        if (isTileEligible && request.techTileCode() != null && !request.techTileCode().isBlank()) {
            try {
                com.gaiaproject.domain.enumtype.tech.TechTileCode tileCode =
                    com.gaiaproject.domain.enumtype.tech.TechTileCode.valueOf(request.techTileCode());
                if ("TERRAFORM_2_PLACE_MINE".equals(tileCode.getAbility().getSpecialEffect())) {
                    leechFollowUpType = "PLACE_MINE_TERRAFORM_2";
                    leechFollowUpData = "{\"terraformDiscount\":2}";
                }
            } catch (Exception ignored) {}
        }

        // 액션 저장 (턴 진행은 리치 해소 후)
        String actionData = String.format("{\"hexQ\":%d,\"hexR\":%d,\"from\":\"%s\",\"to\":\"%s\"}", request.hexQ(), request.hexR(), fromType, targetType);
        actionService.saveActionOnly(gameId, request.playerId(), ActionType.UPGRADE_BUILDING, actionData);

        // 파워 리치 처리 (자동/수동 결정 포함, 턴 진행까지 담당)
        powerLeechService.createBatchAndProcess(game, request.playerId(), request.hexQ(), request.hexR(), targetType, allBuildings, leechFollowUpType, leechFollowUpData);

        return UpgradeBuildingResponse.success(gameId, request.hexQ(), request.hexR(), fromType.name(), targetType.name(), 0);
    }

    /**
     * PLAYING 페이즈 광산 건설
     */
    @Transactional
    public PlaceMinePlayResponse placeMineInPlay(UUID gameId, PlaceMinePlayRequest request) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new IllegalArgumentException("게임을 찾을 수 없습니다"));

        if (!"PLAYING".equals(game.getGamePhase())) {
            return PlaceMinePlayResponse.fail(gameId, "PLAYING 페이즈가 아닙니다");
        }

        // 헥스 유효성 확인
        GameHex hex = gameHexRepository.findByGameIdAndHexQAndHexR(gameId, request.hexQ(), request.hexR())
                .orElse(null);
        if (hex == null) return PlaceMinePlayResponse.fail(gameId, "유효하지 않은 좌표입니다");

        // 기존 건물 확인 (메인 건물 우선, 란티다 기생 제외)
        GameBuilding existingBuilding = gameBuildingRepository
                .findFirstByGameIdAndHexQAndHexRAndIsLantidsMine(gameId, request.hexQ(), request.hexR(), false)
                .orElse(null);

        boolean isGaiaformerReturn = existingBuilding != null
                && existingBuilding.getBuildingType() == BuildingType.GAIAFORMER
                && existingBuilding.getPlayerId().equals(request.playerId())
                && hex.getPlanetType() == PlanetType.GAIA;

        // 플레이어 상태 및 자원 확인 (란티다 판단을 위해 먼저 조회)
        GamePlayerState playerState = gamePlayerStateRepository.findByGameIdAndPlayerId(gameId, request.playerId())
                .orElseThrow(() -> new IllegalStateException("플레이어 상태를 찾을 수 없습니다"));

        boolean isLantidsMine = false;
        if (existingBuilding != null && !isGaiaformerReturn) {
            // 란티다: 타인 건물이 있는 위치에 광산 건설 가능
            boolean isOpponentBuilding = !existingBuilding.getPlayerId().equals(request.playerId());
            if (playerState.getFactionType() == FactionType.LANTIDS && isOpponentBuilding) {
                isLantidsMine = true; // 업그레이드 불가 광산
            } else {
                return PlaceMinePlayResponse.fail(gameId, "이미 건물이 있는 위치입니다");
            }
        }

        if (playerState.getStockMine() <= 0) return PlaceMinePlayResponse.fail(gameId, "광산 재고가 없습니다");

        if (isLantidsMine) {
            // 란티다 기생 광산: 2c + 1o + 항법 QIC (테라포밍 비용 없음)
            try {
                playerState.spendCredit(2);
                playerState.spendOre(1);
                if (request.qicUsed() > 0) playerState.spendQic(request.qicUsed());
            } catch (IllegalStateException e) {
                return PlaceMinePlayResponse.fail(gameId, e.getMessage());
            }
        } else if (isGaiaformerReturn) {
            // 가이아포머가 있는 가이아 행성 → 광산 건설: QIC 면제, 포머 반환
            try {
                playerState.spendCredit(2);
                playerState.spendOre(1);
                if (request.qicUsed() > 0) playerState.spendQic(request.qicUsed()); // 항법 QIC만
            } catch (IllegalStateException e) {
                return PlaceMinePlayResponse.fail(gameId, e.getMessage());
            }
            playerState.addGaiaformer(1); // 포머 반환
        } else if (request.gaiaformerUsed()) {
            // 소행성(비홈) 건설: 가이아포머 1개 영구 제거
            if (playerState.getStockGaiaformer() <= 0) {
                return PlaceMinePlayResponse.fail(gameId, "사용 가능한 가이아포머가 없습니다");
            }
            playerState.permanentlyRemoveGaiaformer();
            if (request.qicUsed() > 0) playerState.spendQic(request.qicUsed());
        } else {
            // 일반 건설: 2c + 1o + 테라포밍 광석
            int terraformingOre = calcTerraformingOre(
                    playerState.getFactionType(),
                    hex.getPlanetType(),
                    playerState.getTechTerraforming(),
                    request.terraformDiscount()
            );
            try {
                playerState.spendCredit(2);
                playerState.spendOre(1 + terraformingOre);
                if (request.qicUsed() > 0) playerState.spendQic(request.qicUsed());
            } catch (IllegalStateException e) {
                return PlaceMinePlayResponse.fail(gameId, e.getMessage());
            }
        }

        // 원시행성 건설 보너스 VP (+6)
        if (hex.getPlanetType() == PlanetType.LOST_PLANET) {
            playerState.addVP(6);
        }

        // PASSIVE: BASIC_TILE_8 - 가이아 구역 광산 건설 시 VP +3
        if (hex.getPlanetType() == PlanetType.GAIA
                && hasActiveTechTile(gameId, request.playerId(), "BASIC_TILE_8")) {
            playerState.addVP(3);
            log.info("[PASSIVE TILE_8] 가이아 구역 광산 VP +3: player={}", request.playerId());
        }

        // 글린 고유 능력: 가이아 행성 광산 건설 시 VP +2
        if (hex.getPlanetType() == PlanetType.GAIA
                && playerState.getFactionType() == FactionType.GLEENS) {
            playerState.addVP(2);
            log.info("[GLEENS] 가이아 광산 VP +2: player={}", request.playerId());
        }

        // 기오덴 PI: 새 행성 개척 시 +3 지식 (란티다 기생 제외, 가이아포머 반환은 포함)
        if (!isLantidsMine
                && playerState.getFactionType() == FactionType.GEODENS
                && hasPlanetaryInstitute(playerState)) {
            playerState.addKnowledge(3);
            log.info("[GEODENS PI] 새 행성 개척 지식 +3: player={}", request.playerId());
        }

        // 란티다 PI: 타인 건물 위치에 광산 기생 시 +2 지식
        if (isLantidsMine && hasPlanetaryInstitute(playerState)) {
            playerState.addKnowledge(2);
            log.info("[LANTIDS PI] 기생 광산 지식 +2: player={}", request.playerId());
        }

        // 다카니안 PI: 새로운 섹터에 광산 건설 시 +2 크레딧, +1 지식
        if (playerState.getFactionType() == FactionType.DAKANIANS
                && hasPlanetaryInstitute(playerState)
                && isNewSectorForPlayer(gameId, request.playerId(), hex.getSectorId())) {
            playerState.addCredit(2);
            playerState.addKnowledge(1);
            log.info("[DAKANIANS PI] 새 섹터 광산 보너스: player={}", request.playerId());
        }

        // 라운드 점수 타일 적용
        int mineRound = game.getCurrentRound() != null ? game.getCurrentRound() : 1;

        // 광산 건설
        roundScoringService.award(gameId, mineRound, playerState, RoundScoringEvent.MINE_PLACED, 1);

        // 가이아 행성 개척 (가이아포머 반환 = 가이아 행성 식민지화)
        if (isGaiaformerReturn) {
            roundScoringService.award(gameId, mineRound, playerState, RoundScoringEvent.GAIA_PLANET_COLONIZED, 1);
        }

        // 테라포밍 단계 (일반 건설만, 가이아포머/소행성/란티다 제외)
        if (!isGaiaformerReturn && !request.gaiaformerUsed() && !isLantidsMine) {
            int terraformSteps = calcRawTerraformingSteps(playerState.getFactionType(), hex.getPlanetType());
            if (terraformSteps > 0) {
                roundScoringService.award(gameId, mineRound, playerState, RoundScoringEvent.TERRAFORM_STEP, terraformSteps);
            }
        }

        // 새로운 섹터 진출
        if (isNewSectorForPlayer(gameId, request.playerId(), hex.getSectorId())) {
            roundScoringService.award(gameId, mineRound, playerState, RoundScoringEvent.NEW_SECTOR_ENTERED, 1);
        }

        // 새로운 행성 종류 개척 (란티다 기생 제외)
        if (!isLantidsMine && isNewPlanetTypeForPlayer(gameId, request.playerId(), hex.getPlanetType())) {
            roundScoringService.award(gameId, mineRound, playerState, RoundScoringEvent.NEW_PLANET_TYPE_COLONIZED, 1);
        }

        playerState.decreaseStockMine();
        gamePlayerStateRepository.save(playerState);

        // 건물 배치 (가이아포머 반환 시 기존 건물을 MINE으로 업그레이드, 아니면 새로 생성)
        GameBuilding mine;
        if (isGaiaformerReturn) {
            existingBuilding.upgrade(BuildingType.MINE);
            mine = gameBuildingRepository.save(existingBuilding);
        } else {
            mine = GameBuilding.place(gameId, request.playerId(), request.hexQ(), request.hexR(), BuildingType.MINE);
            if (isLantidsMine) mine.markAsLantidsMine();
            gameBuildingRepository.save(mine);
        }

        log.info("광산 건설 (PLAYING): game={}, player={}, ({},{})", gameId, request.playerId(), request.hexQ(), request.hexR());

        // 인접 연방에 자동 편입
        federationFormService.autoJoinFederation(gameId, request.playerId(), request.hexQ(), request.hexR());

        // 액션 저장 (턴 진행은 리치 해소 후)
        String actionData = String.format("{\"hexQ\":%d,\"hexR\":%d}", request.hexQ(), request.hexR());
        actionService.saveActionOnly(gameId, request.playerId(), ActionType.PLACE_MINE, actionData);

        // 파워 리치 처리 (자동/수동 결정 포함, 턴 진행까지 담당)
        List<GameBuilding> allBuildings = gameBuildingRepository.findByGameId(gameId);
        powerLeechService.createBatchAndProcess(game, request.playerId(), request.hexQ(), request.hexR(), BuildingType.MINE, allBuildings, null, null);

        return PlaceMinePlayResponse.success(gameId, request.hexQ(), request.hexR(), 0);
    }

    /** 헥스 거리 계산 (flat-top axial) */
    private int hexDistance(int q1, int r1, int q2, int r2) {
        int dq = q2 - q1, dr = r2 - r1;
        return (Math.abs(dq) + Math.abs(dr) + Math.abs(dq + dr)) / 2;
    }

    /** 건물 파워 값 (리치 계산용) */
    private int buildingPowerValue(BuildingType type) {
        return switch (type) {
            case MINE -> 1;
            case TRADING_STATION, RESEARCH_LAB -> 2;
            case PLANETARY_INSTITUTE, ACADEMY -> 3;
            default -> 0;
        };
    }

    /**
     * 테라포밍 광석 비용 계산
     * - 소행성/미행성 종족: 링 행성은 항상 1단계
     * - 일반 종족: 테라포밍 링 순환 최단 거리
     * - 단계당 광석: techTerraforming 0~2 → 3, 3 → 2, 4~5 → 1
     */
    private int calcTerraformingOre(FactionType faction, PlanetType targetPlanet, int techTerraforming, int terraformDiscount) {
        // 원시행성(LOST_PLANET): 홈 종족 포함 항상 3삽 비용
        if (targetPlanet == PlanetType.LOST_PLANET) {
            int orePerStep = techTerraforming <= 1 ? 3 : techTerraforming == 2 ? 2 : 1;
            int effectiveSteps = Math.max(0, 3 - terraformDiscount);
            return effectiveSteps * orePerStep;
        }

        // 소행성(ASTEROIDS): gaiaformerUsed=true 경로로 처리 → 여기 도달하면 0
        if (targetPlanet == PlanetType.ASTEROIDS) {
            return 0;
        }

        PlanetType homePlanet = faction.getHomePlanet();
        if (homePlanet == targetPlanet) return 0;

        int steps;
        if (homePlanet == PlanetType.ASTEROIDS || homePlanet == PlanetType.LOST_PLANET) {
            // 소행성/미행성 종족: 링 행성은 항상 1단계
            steps = isRingPlanet(targetPlanet) ? 1 : 0;
        } else {
            steps = terraformRingDistance(homePlanet, targetPlanet);
        }

        steps = Math.max(0, steps - terraformDiscount);

        if (steps <= 0) return 0;
        // 레벨 0~1: 3광석, 레벨 2: 2광석, 레벨 3~5: 1광석
        int orePerStep = techTerraforming <= 1 ? 3 : techTerraforming == 2 ? 2 : 1;
        return steps * orePerStep;
    }

    private static final PlanetType[] TERRAFORM_RING = {
        PlanetType.TERRA, PlanetType.VOLCANIC, PlanetType.OXIDE,
        PlanetType.DESERT, PlanetType.SWAMP, PlanetType.TITANIUM, PlanetType.ICE
    };

    private boolean isRingPlanet(PlanetType p) {
        for (PlanetType r : TERRAFORM_RING) if (r == p) return true;
        return false;
    }

    private int terraformRingDistance(PlanetType from, PlanetType to) {
        int a = -1, b = -1;
        for (int i = 0; i < TERRAFORM_RING.length; i++) {
            if (TERRAFORM_RING[i] == from) a = i;
            if (TERRAFORM_RING[i] == to) b = i;
        }
        if (a == -1 || b == -1) return 0;
        int diff = Math.abs(a - b);
        return Math.min(diff, TERRAFORM_RING.length - diff);
    }

    /** 의회 건설 여부 확인 (재고 0 = 이미 건설) */
    private boolean hasPlanetaryInstitute(GamePlayerState ps) {
        return ps.getStockPlanetaryInstitute() == 0;
    }

    /** 다카니안: 해당 섹터에 내 건물이 없으면 새 섹터 */
    private boolean isNewSectorForPlayer(UUID gameId, UUID playerId, String sectorId) {
        if (sectorId == null) return false;
        List<GameHex> sectorHexes = gameHexRepository.findByGameIdAndSectorId(gameId, sectorId);
        for (GameHex h : sectorHexes) {
            if (gameBuildingRepository.existsByGameIdAndHexQAndHexR(gameId, h.getHexQ(), h.getHexR())) {
                GameBuilding found = gameBuildingRepository.findFirstByGameIdAndHexQAndHexRAndIsLantidsMine(gameId, h.getHexQ(), h.getHexR(), false).orElse(null);
                if (found != null && found.getPlayerId().equals(playerId)) return false;
            }
        }
        return true;
    }

    /**
     * 테라포밍 원시 단계 수 (할인 없이, 점수 계산용)
     */
    private int calcRawTerraformingSteps(FactionType faction, PlanetType targetPlanet) {
        if (targetPlanet == PlanetType.LOST_PLANET) return 3;
        if (targetPlanet == PlanetType.ASTEROIDS || targetPlanet == null) return 0;
        PlanetType homePlanet = faction.getHomePlanet();
        if (homePlanet == targetPlanet) return 0;
        if (homePlanet == PlanetType.ASTEROIDS || homePlanet == PlanetType.LOST_PLANET) {
            return isRingPlanet(targetPlanet) ? 1 : 0;
        }
        return terraformRingDistance(homePlanet, targetPlanet);
    }

    /**
     * 해당 플레이어에게 처음 개척하는 행성 종류인지 확인 (ROUND_TILE_NEW_PLANET_TYPE 용)
     */
    private boolean isNewPlanetTypeForPlayer(UUID gameId, UUID playerId, PlanetType newPlanetType) {
        if (newPlanetType == null
                || newPlanetType == PlanetType.EMPTY
                || newPlanetType == PlanetType.TRANSDIM
                || newPlanetType == PlanetType.ASTEROIDS) {
            return false;
        }
        List<GameBuilding> existing = gameBuildingRepository.findByGameIdAndPlayerId(gameId, playerId);
        for (GameBuilding b : existing) {
            if (b.getBuildingType() == BuildingType.GAIAFORMER) continue;
            if (b.isLantidsMine()) continue; // 란티다 기생은 행성 종류에 포함하지 않음
            GameHex bHex = gameHexRepository.findByGameIdAndHexQAndHexR(gameId, b.getHexQ(), b.getHexR()).orElse(null);
            if (bHex != null && bHex.getPlanetType() == newPlanetType) return false;
        }
        return true;
    }

    /** 덮이지 않은 특정 기술 타일 보유 여부 확인 */
    private boolean hasActiveTechTile(UUID gameId, UUID playerId, String tileCode) {
        return playerTechTileRepository
                .findByGameIdAndPlayerIdAndIsCovered(gameId, playerId, false)
                .stream()
                .anyMatch(t -> tileCode.equals(t.getTechTileCode()));
    }
}
