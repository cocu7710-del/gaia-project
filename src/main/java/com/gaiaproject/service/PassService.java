package com.gaiaproject.service;

import com.gaiaproject.domain.entity.booster.GameBoosterOffer;
import com.gaiaproject.domain.entity.building.GameBuilding;
import com.gaiaproject.domain.entity.game.Game;
import com.gaiaproject.domain.entity.game.GamePlayerPass;
import com.gaiaproject.domain.entity.game.GameSeat;
import com.gaiaproject.domain.entity.map.GameHex;
import com.gaiaproject.domain.entity.player.GamePlayerRoundBooster;
import com.gaiaproject.domain.entity.player.GamePlayerState;
import com.gaiaproject.domain.enumtype.building.BuildingType;
import com.gaiaproject.domain.enumtype.action.VpCategory;
import com.gaiaproject.domain.enumtype.booster.RoundBoosterType;
import com.gaiaproject.domain.enumtype.player.PlanetType;
import com.gaiaproject.dto.PassContextVo;
import com.gaiaproject.dto.response.PassRoundResponse;
import com.gaiaproject.repository.booster.GameBoosterOfferRepository;
import com.gaiaproject.repository.building.GameBuildingRepository;
import com.gaiaproject.repository.game.GamePlayerPassRepository;
import com.gaiaproject.repository.game.GameRepository;
import com.gaiaproject.repository.game.GameSeatRepository;
import com.gaiaproject.repository.map.GameHexRepository;
import com.gaiaproject.repository.player.GamePlayerRoundBoosterRepository;
import com.gaiaproject.repository.player.GamePlayerStateRepository;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 패스 관리 서비스
 * - 패스 시 다음 라운드 부스터 선택
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class PassService {

    private final GamePlayerPassRepository passRepository;
    private final GameRepository gameRepository;
    private final GameSeatRepository seatRepository;
    private final GameWebSocketService webSocketService;
    private final GamePlayerRoundBoosterRepository playerBoosterRepository;
    private final GameBoosterOfferRepository boosterOfferRepository;
    private final GaiaformingService gaiaformingService;
    private final IncomeService incomeService;
    private final GameBuildingRepository buildingRepository;
    private final GameHexRepository hexRepository;
    private final GamePlayerStateRepository playerStateRepository;
    private final VpLogService vpLogService;
    private final GameEndScoringService gameEndScoringService;
    private final com.gaiaproject.repository.game.GameActionRepository gameActionRepository;
    private final ActionService actionService;
    private final com.gaiaproject.repository.tech.GamePlayerTechTileRepository playerTechTileRepository;
    private final com.gaiaproject.repository.player.GamePlayerFederationTokenRepository federationTokenRepository;
    private final com.gaiaproject.repository.player.GamePlayerArtifactRepository playerArtifactRepository;

    /**
     * 라운드 패스 (다음 라운드 부스터 선택 포함)
     */
    public PassRoundResponse passRound(UUID gameId, UUID playerId, String nextRoundBoosterCode) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new IllegalArgumentException("게임을 찾을 수 없습니다"));

        int currentRound = game.getCurrentRound();

        // 이미 패스했는지 확인
        if (passRepository.existsByGameIdAndPlayerIdAndRoundNumber(gameId, playerId, currentRound)) {
            return PassRoundResponse.fail(gameId, playerId, "이미 패스했습니다");
        }

        // 현재 턴인지 확인
        GameSeat currentSeat = seatRepository.findByGameIdAndPlayerId(gameId, playerId)
                .orElseThrow(() -> new IllegalArgumentException("좌석 정보를 찾을 수 없습니다"));

        if (currentSeat.getSeatNo() != game.getCurrentTurnSeatNo()) {
            return PassRoundResponse.fail(gameId, playerId, "현재 턴이 아닙니다");
        }

        // 1. 현재 부스터 패스 VP 계산 및 지급
        GamePlayerRoundBooster currentBooster = playerBoosterRepository
                .findByGameIdAndPlayerId(gameId, playerId)
                .orElse(null);

        if (currentBooster != null) {
            RoundBoosterType boosterType = currentBooster.getRoundBoosterType();
            int passVp = boosterType.scoreOnPass(buildPassContext(gameId, playerId));
            if (passVp > 0) {
                GamePlayerState ps = playerStateRepository.findByGameIdAndPlayerId(gameId, playerId)
                        .orElseThrow(() -> new IllegalStateException("플레이어 상태를 찾을 수 없습니다"));
                ps.addVP(passVp);
                vpLogService.logVp(gameId, playerId, VpCategory.BOOSTER_PASS, passVp, currentRound, "부스터 패스 VP: " + boosterType.name());
                playerStateRepository.save(ps);
                log.info("패스 VP 지급: playerId={}, booster={}, vp={}", playerId, boosterType, passVp);
            }
        }

        // 2. 고급 기술 타일 패스 VP 지급
        {
            GamePlayerState ps = playerStateRepository.findByGameIdAndPlayerId(gameId, playerId)
                    .orElse(null);
            if (ps != null) {
                var myTiles = playerTechTileRepository.findByGameIdAndPlayerIdAndIsCovered(gameId, playerId, false);
                PassContextVo ctx = buildPassContext(gameId, playerId);
                int advPassVp = 0;
                for (var tile : myTiles) {
                    String code = tile.getTechTileCode();
                    if (!code.startsWith("ADV_")) continue;
                    try {
                        var advCode = com.gaiaproject.domain.enumtype.tech.AdvancedTechTileCode.valueOf(code);
                        String effect = advCode.getAbility().getSpecialEffect();
                        if (effect == null) continue;
                        int vp = switch (effect) {
                            case "VP_PER_LOST_PLANET_PASS" -> {
                                // 건물이 있는 깊은 구역 섹터 수 (건물 수가 아님)
                                var deepBuildings = buildingRepository.findByGameIdAndPlayerId(gameId, playerId);
                                java.util.Set<String> deepSectors = new java.util.HashSet<>();
                                for (var b : deepBuildings) {
                                    hexRepository.findByGameIdAndHexQAndHexR(gameId, b.getHexQ(), b.getHexR()).ifPresent(h -> {
                                        if (h.getSectorId() != null && h.getSectorId().startsWith("DEEP_SECTOR")) {
                                            deepSectors.add(h.getSectorId());
                                        }
                                    });
                                }
                                yield deepSectors.size() * 2;
                            }
                            case "VP_PER_ASTEROID_SECTOR_PASS" -> {
                                // 소행성 구역 수 (sectorId 기반)
                                var buildings = buildingRepository.findByGameIdAndPlayerId(gameId, playerId);
                                var hexRepo = hexRepository;
                                java.util.Set<String> asteroidSectors = new java.util.HashSet<>();
                                for (var b : buildings) {
                                    hexRepo.findByGameIdAndHexQAndHexR(gameId, b.getHexQ(), b.getHexR()).ifPresent(h -> {
                                        if (h.getPlanetType() == com.gaiaproject.domain.enumtype.player.PlanetType.ASTEROIDS && h.getSectorId() != null) {
                                            asteroidSectors.add(h.getSectorId());
                                        }
                                    });
                                }
                                yield asteroidSectors.size() * 2;
                            }
                            case "VP_PER_FEDERATION_TOKEN_PASS" -> {
                                long fedCount = federationTokenRepository.countByGameIdAndPlayerId(gameId, playerId);
                                yield (int) fedCount * 3;
                            }
                            case "VP_PER_LAB_PASS" -> ctx.researchLabs() * 3;
                            case "VP_PER_PLANET_TYPE_PASS" -> ctx.colonizedPlanetTypeKinds() * 1;
                            default -> 0;
                        };
                        advPassVp += vp;
                    } catch (IllegalArgumentException ignored) {}
                }
                if (advPassVp > 0) {
                    ps.addVP(advPassVp);
                    vpLogService.logVp(gameId, playerId, VpCategory.ADV_TECH_TILE, advPassVp, currentRound, "고급 타일 패스 VP");
                    playerStateRepository.save(ps);
                    log.info("고급 타일 패스 VP: playerId={}, vp={}", playerId, advPassVp);
                }
            }
        }

        // 3. 현재 부스터를 offer 풀에 반환

        if (currentBooster != null) {
            String currentBoosterCode = currentBooster.getRoundBoosterType().name();
            GameBoosterOffer currentOffer = boosterOfferRepository
                    .findByGameIdAndBoosterCode(gameId, currentBoosterCode)
                    .orElse(null);

            if (currentOffer != null) {
                currentOffer.returnToPool();
                boosterOfferRepository.save(currentOffer);
            }

            // 현재 부스터 삭제
            playerBoosterRepository.delete(currentBooster);
        }

        // 4. 새 부스터 선택 (6라운드에서는 스킵)
        if (currentRound < 6) {
            GameBoosterOffer nextOffer = boosterOfferRepository
                    .findByGameIdAndBoosterCode(gameId, nextRoundBoosterCode)
                    .orElse(null);

            if (nextOffer == null || !nextOffer.isAvailable()) {
                return PassRoundResponse.fail(gameId, playerId, "선택할 수 없는 부스터입니다");
            }

            // 새 부스터 할당
            nextOffer.takeByPlayer(playerId);
            nextOffer.pick(currentSeat.getSeatNo());
            boosterOfferRepository.save(nextOffer);

            GamePlayerRoundBooster newBooster = GamePlayerRoundBooster.builder()
                    .gameId(gameId)
                    .playerId(playerId)
                    .roundBoosterType(RoundBoosterType.valueOf(nextRoundBoosterCode))
                    .build();
            playerBoosterRepository.save(newBooster);
        }

        // 5. 패스 기록 생성
        GamePlayerPass pass = GamePlayerPass.builder()
                .gameId(gameId)
                .playerId(playerId)
                .roundNumber(currentRound)
                .build();
        passRepository.save(pass);

        // 발타크: 패스 시 사용 가능한 포머 → QIC 자동 변환
        {
            GamePlayerState passPs = playerStateRepository.findByGameIdAndPlayerId(gameId, playerId).orElse(null);
            if (passPs != null && passPs.getFactionType() == com.gaiaproject.domain.enumtype.player.FactionType.BAL_TAKS) {
                int freeGaiaformers = passPs.getStockGaiaformer();
                if (freeGaiaformers > 0) {
                    for (int i = 0; i < freeGaiaformers; i++) {
                        passPs.convertGaiaformerToQic();
                    }
                    playerStateRepository.save(passPs);
                    log.info("[BAL_TAKS PASS] 포머 {}개 → QIC 자동 변환: player={}", freeGaiaformers, playerId);
                }
            }
        }

        log.info("플레이어 패스: gameId={}, playerId={}, round={}, nextBooster={}",
                gameId, playerId, currentRound, nextRoundBoosterCode);

        // 6. 모든 플레이어 패스 확인
        long totalPlayers = seatRepository.countByGameIdAndPlayerIdIsNotNull(gameId);
        long passedPlayers = passRepository.countByGameIdAndRoundNumber(gameId, currentRound);
        boolean allPassed = (passedPlayers >= totalPlayers);

        int nextSeatNo;
        if (allPassed) {
            nextSeatNo = 0;
            if (currentRound >= 6) {
                // 6라운드 종료 → 최종 점수 계산 → 게임 종료
                gameEndScoringService.calculateFinalScores(gameId);
                game.changeStatus("FINISHED");
                game.setGamePhase("FINISHED");
                gameRepository.save(game);
                webSocketService.broadcastPlayerPassed(gameId, playerId, currentSeat.getSeatNo(), true);
                webSocketService.broadcast(com.gaiaproject.dto.websocket.GameEvent.of(gameId, "GAME_FINISHED", java.util.Map.of()));
                log.info("게임 종료: gameId={}", gameId);
            } else {
                // 라운드 종료 → 다음 라운드
                boolean itarsWaiting = endRoundAndStartNext(game);
                webSocketService.broadcastPlayerPassed(gameId, playerId, currentSeat.getSeatNo(), true);
                if (!itarsWaiting) {
                    webSocketService.broadcastRoundStarted(gameId, game.getCurrentRound());
                }
            }
        } else {
            // 현재 플레이어 타이머 종료
            actionService.stopTurnTimer(gameId, playerId);
            // 다음 턴 계산 (setTurnOrder 전에 호출해야 정렬이 올바름)
            nextSeatNo = calculateNextTurnSeatNo(game);
            // 이번 라운드 패스 순서 기록 (다음 라운드 플레이 순서 결정용)
            // calculateNextTurnSeatNo 이후에 설정해야 현재 라운드 정렬이 올바름
            long passOrder = passRepository.countByGameIdAndRoundNumber(gameId, currentRound);
            currentSeat.setTurnOrder((int) passOrder);
            seatRepository.save(currentSeat);
            game.nextTurn(nextSeatNo);
            gameRepository.save(game);
            webSocketService.broadcastPlayerPassed(gameId, playerId, currentSeat.getSeatNo(), false);
            webSocketService.broadcastTurnChanged(gameId, nextSeatNo);
            // 다음 플레이어 타이머 시작
            actionService.startTurnTimerBySeatNo(gameId, nextSeatNo);
        }

        return PassRoundResponse.success(gameId, playerId, currentRound, nextSeatNo, allPassed);
    }

    /**
     * 다음 턴 좌석 번호 계산
     */
    private int calculateNextTurnSeatNo(Game game) {
        UUID gameId = game.getId();
        int currentRound = game.getCurrentRound();
        int currentSeatNo = game.getCurrentTurnSeatNo();

        // 점유된 좌석만 추출
        List<GameSeat> occupied = seatRepository.findByGameIdOrderBySeatNo(gameId).stream()
                .filter(s -> s.getPlayerId() != null)
                .collect(java.util.stream.Collectors.toList());

        // turnOrder가 설정된 경우(라운드 2+) 이전 라운드 패스 순서 기준 정렬, 아니면 seatNo 기준 유지
        boolean hasTurnOrder = occupied.stream().anyMatch(s -> s.getTurnOrder() > 0);
        if (hasTurnOrder) {
            occupied.sort(java.util.Comparator.comparingInt(GameSeat::getTurnOrder));
        }

        // 현재 좌석의 위치 찾기
        int currentIdx = -1;
        for (int i = 0; i < occupied.size(); i++) {
            if (occupied.get(i).getSeatNo() == currentSeatNo) {
                currentIdx = i;
                break;
            }
        }

        // 다음 패스 안 한 플레이어 찾기 (순환)
        for (int i = 1; i <= occupied.size(); i++) {
            int nextIdx = (currentIdx + i) % occupied.size();
            GameSeat seat = occupied.get(nextIdx);

            boolean hasPassed = passRepository.existsByGameIdAndPlayerIdAndRoundNumber(
                    gameId, seat.getPlayerId(), currentRound);

            if (!hasPassed) {
                return seat.getSeatNo();
            }
        }

        // 모든 플레이어가 패스한 경우
        return 0;
    }

    /**
     * 패스 VP 계산에 필요한 컨텍스트 구성
     */
    private PassContextVo buildPassContext(UUID gameId, UUID playerId) {
        List<GameBuilding> buildings = buildingRepository.findByGameIdAndPlayerId(gameId, playerId);

        int mines            = (int) buildings.stream().filter(b -> b.getBuildingType() == BuildingType.MINE).count();
        int tradingStations  = (int) buildings.stream().filter(b -> b.getBuildingType() == BuildingType.TRADING_STATION).count();
        int researchLabs     = (int) buildings.stream().filter(b -> b.getBuildingType() == BuildingType.RESEARCH_LAB).count();
        int academies        = (int) buildings.stream().filter(b -> b.getBuildingType() == BuildingType.ACADEMY).count();
        int planetaryInsts   = (int) buildings.stream().filter(b -> b.getBuildingType() == BuildingType.PLANETARY_INSTITUTE).count();
        int gaiaformersOnMap = (int) buildings.stream().filter(b -> b.getBuildingType() == BuildingType.GAIAFORMER).count();
        GamePlayerState gfState = playerStateRepository.findByGameIdAndPlayerId(gameId, playerId).orElse(null);
        int stockGf = gfState != null ? gfState.getStockGaiaformer() : 0;
        int convertedGf = gfState != null ? gfState.getBaltaksConvertedGaiaformers() : 0;
        int gaiaformers      = stockGf + gaiaformersOnMap + convertedGf; // 소각하지 않은 포머 = 재고 + 맵 배치 + 발타크 변환

        // 건물이 있는 헥스 일괄 조회 (N+1 → 1 쿼리)
        List<GameHex> allHexes = hexRepository.findByGameId(gameId);
        java.util.Map<String, GameHex> hexLookup = new java.util.HashMap<>();
        for (GameHex hex : allHexes) {
            hexLookup.put(hex.getHexQ() + "," + hex.getHexR(), hex);
        }

        java.util.Map<String, PlanetType> hexPlanetMap = new java.util.HashMap<>();
        for (GameBuilding b : buildings) {
            String key = b.getHexQ() + "," + b.getHexR();
            if (!hexPlanetMap.containsKey(key)) {
                GameHex hex = hexLookup.get(key);
                if (hex != null) hexPlanetMap.put(key, hex.getPlanetType());
            }
        }

        int gaiaPlanets = (int) hexPlanetMap.values().stream().filter(p -> p == PlanetType.GAIA).count();
        // 딥섹터 건물 수 (LOST_PLANET뿐 아니라 딥섹터 내 모든 건물)
        int deepStructures = (int) buildings.stream().filter(b -> {
            GameHex hex = hexLookup.get(b.getHexQ() + "," + b.getHexR());
            return hex != null && hex.getSectorId() != null && hex.getSectorId().startsWith("DEEP_SECTOR");
        }).count();
        long colonizedKinds = hexPlanetMap.values().stream()
                .filter(p -> p != PlanetType.EMPTY && p != PlanetType.TRANSDIM && p != PlanetType.GAIA)
                .distinct().count();

        // 가이아 행성도 종류에 포함
        if (gaiaPlanets > 0) colonizedKinds++;
        // 인공물 가상 행성 종류
        java.util.Set<PlanetType> kindSet = hexPlanetMap.values().stream()
                .filter(p -> p != PlanetType.EMPTY && p != PlanetType.TRANSDIM)
                .collect(java.util.stream.Collectors.toSet());
        if (playerArtifactRepository.existsByGameIdAndPlayerIdAndArtifactType(gameId, playerId, "ARTIFACT_7") && !kindSet.contains(PlanetType.ASTEROIDS)) colonizedKinds++;
        if (playerArtifactRepository.existsByGameIdAndPlayerIdAndArtifactType(gameId, playerId, "ARTIFACT_8") && !kindSet.contains(PlanetType.LOST_PLANET)) colonizedKinds++;

        return new PassContextVo(mines, tradingStations, researchLabs, academies, planetaryInsts,
                gaiaPlanets, gaiaformers, deepStructures, (int) colonizedKinds);
    }

    /**
     * 라운드 종료 및 다음 라운드 시작.
     * @return true: 아이타 선택 대기 중 (ROUND_STARTED 브로드캐스트 하지 않음)
     */
    private boolean endRoundAndStartNext(Game game) {
        UUID gameId = game.getId();
        log.info("라운드 {} 종료, 다음 라운드 시작", game.getCurrentRound());

        // 다음 라운드로 이동 (첫 번째 플레이어 = 먼저 패스한 플레이어)
        int prevRound = game.getCurrentRound();
        game.nextRound();

        // 패스 순서(passedAt)로 다음 라운드 선 플레이어 결정
        List<GamePlayerPass> passes = passRepository.findByGameIdAndRoundNumber(gameId, prevRound);
        passes.sort((a, b) -> a.getPassedAt().compareTo(b.getPassedAt()));
        if (!passes.isEmpty()) {
            UUID firstPassedPlayerId = passes.get(0).getPlayerId();
            seatRepository.findByGameIdAndPlayerId(gameId, firstPassedPlayerId)
                    .ifPresent(seat -> game.nextTurn(seat.getSeatNo()));
        }
        // turnOrder를 패스 순서대로 설정 (먼저 패스 = 다음 라운드 선 플레이어 = turnOrder 1)
        List<com.gaiaproject.domain.entity.game.GameSeat> allSeats = seatRepository.findByGameIdOrderBySeatNo(gameId);
        java.util.Map<UUID, Integer> passOrder = new java.util.HashMap<>();
        for (int i = 0; i < passes.size(); i++) {
            passOrder.put(passes.get(i).getPlayerId(), i + 1);
        }
        for (var seat : allSeats) {
            seat.setTurnOrder(passOrder.getOrDefault(seat.getPlayerId(), 0));
        }
        seatRepository.saveAllAndFlush(allSeats);
        gameRepository.save(game);

        // 1. 비파워 수입 배분 + 리셋
        incomeService.applyNonPowerIncome(game);

        // 1-1. 파워 수입 선택 페이즈 체크 (동시 진행)
        try {
            List<GamePlayerState> allPlayersForPower = playerStateRepository.findByGameId(gameId);
            // 파워 수입이 있는 플레이어별 항목 수집
            java.util.List<java.util.Map<String, Object>> allPlayerItems = new java.util.ArrayList<>();
            for (var ps : allPlayersForPower) {
                var powerItems = incomeService.calculatePowerIncomeItems(gameId, ps, game.getEconomyTrackOption());
                if (!powerItems.isEmpty()) {
                    var itemMaps = powerItems.stream().map(item -> java.util.Map.<String, Object>of(
                            "id", item.id(), "source", item.source(), "label", item.label(),
                            "powerCharge", item.powerCharge(), "powerBowl1", item.powerBowl1()
                    )).toList();
                    allPlayerItems.add(java.util.Map.of(
                            "playerId", ps.getPlayerId().toString(),
                            "items", itemMaps
                    ));
                }
            }
            if (!allPlayerItems.isEmpty()) {
                game.setGamePhase("POWER_INCOME_PHASE");
                gameRepository.save(game);
                webSocketService.broadcast(com.gaiaproject.dto.websocket.GameEvent.of(gameId, "POWER_INCOME_CHOICE",
                        java.util.Map.of("players", allPlayerItems)));
                log.info("파워 수입 동시 선택 대기: game={}, 대상 {}명", gameId, allPlayerItems.size());
                return true;
            }
        } catch (Exception e) {
            log.error("파워 수입 체크 중 오류 발생 — 기존 플로우로 진행: {}", e.getMessage(), e);
            incomeService.applyRoundIncome(game);
        }

        // 파워 수입 없으면 기존 플로우 진행
        // 2. TRANSDIM → GAIA 헥스 변환
        gaiaformingService.processGaiaPlanetConversion(gameId);

        // 3. 테란 PI 체크: 가이아 구역 파워 → 자원 수동 변환
        List<GamePlayerState> allPlayers = playerStateRepository.findByGameId(gameId);
        GamePlayerState terransPlayer = allPlayers.stream()
                .filter(p -> {
                    var ft = p.getFactionType();
                    if (ft == null) ft = seatRepository.findByGameIdAndSeatNo(gameId, p.getSeatNo())
                            .map(s -> s.getFactionType()).orElse(null);
                    return ft == com.gaiaproject.domain.enumtype.player.FactionType.TERRANS
                            && p.getStockPlanetaryInstitute() == 0
                            && p.getGaiaPower() > 0;
                })
                .findFirst().orElse(null);

        if (terransPlayer != null) {
            // 테란 가이아 교환 다이얼로그 브로드캐스트
            game.setGamePhase("TERRANS_GAIA_PHASE");
            gameRepository.save(game);
            webSocketService.broadcast(com.gaiaproject.dto.websocket.GameEvent.of(gameId, "TERRANS_GAIA_CHOICE",
                    java.util.Map.of("terransPlayerId", terransPlayer.getPlayerId().toString(),
                            "gaiaPower", terransPlayer.getGaiaPower())));
            log.info("테란 가이아 변환 대기: game={}, gaia={}", gameId, terransPlayer.getGaiaPower());
            return true; // 대기
        }

        // 4. 팅커로이드 PI 체크: 액션 타일 선택 기회
        GamePlayerState tinkeroidsPlayer = allPlayers.stream()
                .filter(p -> p.getFactionType() == com.gaiaproject.domain.enumtype.player.FactionType.TINKEROIDS
                        && p.getStockPlanetaryInstitute() == 0)
                .findFirst().orElse(null);

        if (tinkeroidsPlayer != null) {
            List<String> available = getTinkeroidsAvailableActions(tinkeroidsPlayer, game.getCurrentRound());
            if (!available.isEmpty()) {
                game.setGamePhase("TINKEROIDS_ACTION_PHASE");
                gameRepository.save(game);
                webSocketService.broadcastTinkeroidsActionChoice(gameId, tinkeroidsPlayer.getPlayerId(), available, game.getCurrentRound());
                log.info("팅커로이드 액션 선택 대기: game={}, round={}, available={}", gameId, game.getCurrentRound(), available);
                return true;
            }
        }

        // 4. 아이타 PI 체크: 가이아 4개 이상이면 선택 기회 부여
        GamePlayerState itarsPlayer = allPlayers.stream()
                .filter(p -> p.getFactionType() == com.gaiaproject.domain.enumtype.player.FactionType.ITARS
                        && p.getStockPlanetaryInstitute() == 0
                        && p.getGaiaPower() >= 4)
                .findFirst().orElse(null);

        if (itarsPlayer != null) {
            // 아이타 제외 모든 플레이어 가이아 복귀
            gaiaformingService.returnGaiaPowerExcept(gameId, itarsPlayer.getPlayerId());

            // 아이타: 4의 배수 유지, 나머지 복귀
            int keep = (itarsPlayer.getGaiaPower() / 4) * 4;
            int returnAmt = itarsPlayer.getGaiaPower() - keep;
            if (returnAmt > 0) {
                itarsPlayer.removeGaiaPower(returnAmt);
                itarsPlayer.addPowerToBowl1(returnAmt);
                playerStateRepository.save(itarsPlayer);
            }

            game.setGamePhase("ITARS_GAIA_PHASE");
            gameRepository.save(game);

            webSocketService.broadcastItarsGaiaChoice(gameId, itarsPlayer.getPlayerId(), keep / 4);
            log.info("아이타 가이아 선택 대기: game={}, player={}, choices={}", gameId, itarsPlayer.getPlayerId(), keep / 4);
            return true;
        }

        // 일반: 모든 플레이어 가이아 복귀
        gaiaformingService.returnAllGaiaPower(gameId);
        log.info("라운드 {} 시작", game.getCurrentRound());
        return false;
    }

    /** 팅커로이드 라운드별 선택 가능 액션 */
    private List<String> getTinkeroidsAvailableActions(GamePlayerState ps, int round) {
        List<String> pool = round <= 3
                ? List.of("TINK_TERRAFORM_1", "TINK_POWER_4", "TINK_QIC_1")
                : List.of("TINK_TERRAFORM_3", "TINK_KNOWLEDGE_3", "TINK_QIC_2");
        return pool.stream().filter(a -> !ps.isTinkeroidsActionUsed(a)).toList();
    }

    /**
     * 팅커로이드 액션 선택 완료 → 아이타 체크 또는 라운드 시작으로 진행
     */
    public void continueTinkeroidsToNextPhase(UUID gameId) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new IllegalArgumentException("게임을 찾을 수 없습니다"));

        List<GamePlayerState> allPlayers = playerStateRepository.findByGameId(gameId);

        // 아이타 PI 체크
        GamePlayerState itarsPlayer = allPlayers.stream()
                .filter(p -> p.getFactionType() == com.gaiaproject.domain.enumtype.player.FactionType.ITARS
                        && p.getStockPlanetaryInstitute() == 0
                        && p.getGaiaPower() >= 4)
                .findFirst().orElse(null);

        if (itarsPlayer != null) {
            gaiaformingService.returnGaiaPowerExcept(gameId, itarsPlayer.getPlayerId());
            int keep = (itarsPlayer.getGaiaPower() / 4) * 4;
            int returnAmt = itarsPlayer.getGaiaPower() - keep;
            if (returnAmt > 0) {
                itarsPlayer.removeGaiaPower(returnAmt);
                itarsPlayer.addPowerToBowl1(returnAmt);
                playerStateRepository.save(itarsPlayer);
            }
            game.setGamePhase("ITARS_GAIA_PHASE");
            gameRepository.save(game);
            webSocketService.broadcastItarsGaiaChoice(gameId, itarsPlayer.getPlayerId(), keep / 4);
            return;
        }

        // 일반 진행
        gaiaformingService.returnAllGaiaPower(gameId);
        game.setGamePhase("PLAYING");
        gameRepository.save(game);
        webSocketService.broadcastRoundStarted(gameId, game.getCurrentRound());
    }

    /**
     * 파워 수입 선택 완료 (동시 진행): 항목 순서대로 적용 후 완료 체크
     * @param completedPlayerId 완료한 플레이어
     * @param itemIds 플레이어가 선택한 순서대로의 항목 ID 리스트
     */
    public void continueAfterPowerIncome(UUID gameId, UUID completedPlayerId, java.util.List<String> itemIds) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new IllegalArgumentException("게임을 찾을 수 없습니다"));
        GamePlayerState ps = playerStateRepository.findByGameIdAndPlayerId(gameId, completedPlayerId)
                .orElseThrow(() -> new IllegalStateException("플레이어 상태를 찾을 수 없습니다"));

        // 항목을 순서대로 적용
        var allItems = incomeService.calculatePowerIncomeItems(gameId, ps, game.getEconomyTrackOption());
        for (String itemId : itemIds) {
            var item = allItems.stream().filter(i -> i.id().equals(itemId)).findFirst().orElse(null);
            if (item != null) {
                incomeService.applySinglePowerIncome(ps, item);
            }
        }

        // 이 플레이어 수입 완료 → 다른 플레이어가 아직 완료 안 했는지 체크
        // 모든 대상 플레이어의 파워가 수입 전과 동일하면 아직 미완료
        // → 간단하게 powerIncomeCompleted 카운트 관리 (game_action에 마커)
        // 완료 마커 저장
        int turnSeq = (int) gameActionRepository.findByGameIdAndRoundNumber(gameId, game.getCurrentRound()).stream().count() + 1;
        gameActionRepository.save(com.gaiaproject.domain.entity.game.GameAction.builder()
                .gameId(gameId).playerId(completedPlayerId)
                .roundNumber(game.getCurrentRound())
                .turnSequence(turnSeq)
                .actionType(com.gaiaproject.domain.enumtype.action.ActionType.POWER_INCOME)
                .actionData("{\"completed\":true}")
                .build());

        // 완료한 플레이어 수 체크
        long completedCount = gameActionRepository.findByGameIdAndRoundNumber(gameId, game.getCurrentRound()).stream()
                .filter(a -> a.getActionType() == com.gaiaproject.domain.enumtype.action.ActionType.POWER_INCOME)
                .count();
        // 전체 파워 수입 대상 수 체크
        long totalTarget = playerStateRepository.findByGameId(gameId).stream()
                .filter(p -> !incomeService.calculatePowerIncomeItems(gameId, p, game.getEconomyTrackOption()).isEmpty())
                .count();

        log.info("파워 수입 완료: game={}, player={}, completed={}/{}", gameId, completedPlayerId, completedCount, totalTarget);

        // 개별 완료 브로드캐스트 (보라색 테두리 제거용)
        webSocketService.broadcast(com.gaiaproject.dto.websocket.GameEvent.of(gameId, "POWER_INCOME_COMPLETED",
                java.util.Map.of("completedPlayerId", completedPlayerId.toString())));

        if (completedCount >= totalTarget) {
            // 전원 완료 → 다음 단계
            gaiaformingService.processGaiaPlanetConversion(gameId);
            game.setGamePhase("PLAYING");
            continueAfterGaiaConversion(game);
        }
        // 아직 대기 중인 플레이어 있으면 그냥 리턴 (FE에서 개별 완료)
    }

    /** 가이아 변환 후 테란/팅커/아이타 체크 (기존 endRoundAndStartNext 후반부 추출) */
    private void continueAfterGaiaConversion(Game game) {
        UUID gameId = game.getId();
        List<GamePlayerState> allPlayers = playerStateRepository.findByGameId(gameId);

        // 테란 PI 체크
        GamePlayerState terransPlayer = allPlayers.stream()
                .filter(p -> {
                    var ft = p.getFactionType();
                    if (ft == null) ft = seatRepository.findByGameIdAndSeatNo(gameId, p.getSeatNo())
                            .map(s -> s.getFactionType()).orElse(null);
                    return ft == com.gaiaproject.domain.enumtype.player.FactionType.TERRANS
                            && p.getStockPlanetaryInstitute() == 0
                            && p.getGaiaPower() > 0;
                })
                .findFirst().orElse(null);

        if (terransPlayer != null) {
            game.setGamePhase("TERRANS_GAIA_PHASE");
            gameRepository.save(game);
            webSocketService.broadcast(com.gaiaproject.dto.websocket.GameEvent.of(gameId, "TERRANS_GAIA_CHOICE",
                    java.util.Map.of("terransPlayerId", terransPlayer.getPlayerId().toString(),
                            "gaiaPower", terransPlayer.getGaiaPower())));
            return;
        }

        // 팅커로이드 체크
        GamePlayerState tinkeroidsPlayer = allPlayers.stream()
                .filter(p -> p.getFactionType() == com.gaiaproject.domain.enumtype.player.FactionType.TINKEROIDS
                        && p.getStockPlanetaryInstitute() == 0)
                .findFirst().orElse(null);

        if (tinkeroidsPlayer != null) {
            int round = game.getCurrentRound() != null ? game.getCurrentRound() : 1;
            java.util.List<String> available = getTinkeroidsAvailableActions(tinkeroidsPlayer, round);
            if (!available.isEmpty()) {
                game.setGamePhase("TINKEROIDS_ACTION_PHASE");
                gameRepository.save(game);
                webSocketService.broadcast(com.gaiaproject.dto.websocket.GameEvent.of(gameId, "TINKEROIDS_ACTION_CHOICE",
                        java.util.Map.of("tinkeroidsPlayerId", tinkeroidsPlayer.getPlayerId().toString(),
                                "availableActions", available, "currentRound", round)));
                return;
            }
        }

        // 아이타 체크
        GamePlayerState itarsPlayer = allPlayers.stream()
                .filter(p -> p.getFactionType() == com.gaiaproject.domain.enumtype.player.FactionType.ITARS
                        && p.getStockPlanetaryInstitute() == 0
                        && p.getGaiaPower() >= 4)
                .findFirst().orElse(null);

        if (itarsPlayer != null) {
            gaiaformingService.returnGaiaPowerExcept(gameId, itarsPlayer.getPlayerId());
            int keep = (itarsPlayer.getGaiaPower() / 4) * 4;
            int returnAmt = itarsPlayer.getGaiaPower() - keep;
            if (returnAmt > 0) {
                itarsPlayer.removeGaiaPower(returnAmt);
                itarsPlayer.addPowerToBowl1(returnAmt);
                playerStateRepository.save(itarsPlayer);
            }
            game.setGamePhase("ITARS_GAIA_PHASE");
            gameRepository.save(game);
            webSocketService.broadcastItarsGaiaChoice(gameId, itarsPlayer.getPlayerId(), keep / 4);
            return;
        }

        // 일반: 모든 플레이어 가이아 복귀
        gaiaformingService.returnAllGaiaPower(gameId);
        game.setGamePhase("PLAYING");
        gameRepository.save(game);
        webSocketService.broadcastRoundStarted(gameId, game.getCurrentRound());
    }

    /** 테란 가이아 변환 완료 후 다음 단계 진행 (팅커→아이타→라운드 시작) */
    public void continueAfterTerransGaia(UUID gameId) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new IllegalArgumentException("게임을 찾을 수 없습니다"));
        List<GamePlayerState> allPlayers = playerStateRepository.findByGameId(gameId);

        // 팅커로이드 PI 체크
        GamePlayerState tinkeroidsPlayer = allPlayers.stream()
                .filter(p -> p.getFactionType() == com.gaiaproject.domain.enumtype.player.FactionType.TINKEROIDS
                        && p.getStockPlanetaryInstitute() == 0)
                .findFirst().orElse(null);
        if (tinkeroidsPlayer != null) {
            List<String> available = getTinkeroidsAvailableActions(tinkeroidsPlayer, game.getCurrentRound());
            if (!available.isEmpty()) {
                game.setGamePhase("TINKEROIDS_ACTION_PHASE");
                gameRepository.save(game);
                webSocketService.broadcastTinkeroidsActionChoice(gameId, tinkeroidsPlayer.getPlayerId(), available, game.getCurrentRound());
                return;
            }
        }

        // 아이타 → 가이아 복귀 → 라운드 시작 (continueTinkeroidsToNextPhase와 동일)
        continueTinkeroidsToNextPhase(gameId);
    }
}
