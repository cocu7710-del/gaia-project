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
                playerStateRepository.save(ps);
                log.info("패스 VP 지급: playerId={}, booster={}, vp={}", playerId, boosterType, passVp);
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

        // 4. 새 부스터 선택
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
            // 라운드 종료
            nextSeatNo = 0;
            boolean itarsWaiting = endRoundAndStartNext(game);
            webSocketService.broadcastPlayerPassed(gameId, playerId, currentSeat.getSeatNo(), true);
            if (!itarsWaiting) {
                webSocketService.broadcastRoundStarted(gameId, game.getCurrentRound());
            }
        } else {
            // 다음 턴 계산
            nextSeatNo = calculateNextTurnSeatNo(game);
            game.nextTurn(nextSeatNo);
            gameRepository.save(game);
            webSocketService.broadcastPlayerPassed(gameId, playerId, currentSeat.getSeatNo(), false);
            webSocketService.broadcastTurnChanged(gameId, nextSeatNo);
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

        // 모든 좌석 조회
        List<GameSeat> seats = seatRepository.findByGameIdOrderBySeatNo(gameId);
        int maxSeatNo = seats.size();

        // 패스하지 않은 플레이어 찾기 (순환)
        for (int i = 1; i <= maxSeatNo; i++) {
            int nextSeatNo = (currentSeatNo % maxSeatNo) + 1;
            currentSeatNo = nextSeatNo;

            // 해당 좌석의 플레이어가 패스했는지 확인
            GameSeat seat = seats.stream()
                    .filter(s -> s.getSeatNo() == nextSeatNo)
                    .findFirst()
                    .orElse(null);

            if (seat != null && seat.getPlayerId() != null) {
                boolean hasPassed = passRepository.existsByGameIdAndPlayerIdAndRoundNumber(
                        gameId, seat.getPlayerId(), currentRound);

                if (!hasPassed) {
                    return nextSeatNo;
                }
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
        int gaiaformers      = (int) buildings.stream().filter(b -> b.getBuildingType() == BuildingType.GAIAFORMER).count();

        // 건물이 있는 헥스 조회하여 행성 타입 분석
        java.util.Map<String, PlanetType> hexPlanetMap = new java.util.HashMap<>();
        for (GameBuilding b : buildings) {
            String key = b.getHexQ() + "," + b.getHexR();
            if (!hexPlanetMap.containsKey(key)) {
                hexRepository.findByGameIdAndHexQAndHexR(gameId, b.getHexQ(), b.getHexR())
                        .ifPresent(hex -> hexPlanetMap.put(key, hex.getPlanetType()));
            }
        }

        int gaiaPlanets = (int) hexPlanetMap.values().stream().filter(p -> p == PlanetType.GAIA).count();
        int deepStructures = (int) hexPlanetMap.values().stream().filter(p -> p == PlanetType.LOST_PLANET).count();
        long colonizedKinds = hexPlanetMap.values().stream()
                .filter(p -> p != PlanetType.EMPTY && p != PlanetType.TRANSDIM && p != PlanetType.GAIA)
                .distinct().count();

        // 가이아 행성도 종류에 포함 (게임 규칙에 따라 조정 가능)
        if (gaiaPlanets > 0) colonizedKinds++;

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

        // 패스 순서(passedAt)로 턴 순서 재배치
        List<GamePlayerPass> passes = passRepository.findByGameIdAndRoundNumber(gameId, prevRound);
        passes.sort((a, b) -> a.getPassedAt().compareTo(b.getPassedAt()));
        if (!passes.isEmpty()) {
            // turn_order를 패스 순서대로 갱신 (1, 2, 3, 4) — 한번에 저장
            List<com.gaiaproject.domain.entity.game.GameSeat> seatsToUpdate = new java.util.ArrayList<>();
            for (int i = 0; i < passes.size(); i++) {
                final int order = i + 1;
                UUID pid = passes.get(i).getPlayerId();
                seatRepository.findByGameIdAndPlayerId(gameId, pid)
                        .ifPresent(seat -> {
                            seat.setTurnOrder(order);
                            seatsToUpdate.add(seat);
                        });
            }
            seatRepository.saveAllAndFlush(seatsToUpdate);
            // 첫 번째 패스 플레이어가 다음 라운드 선
            UUID firstPassedPlayerId = passes.get(0).getPlayerId();
            seatRepository.findByGameIdAndPlayerId(gameId, firstPassedPlayerId)
                    .ifPresent(seat -> game.nextTurn(seat.getSeatNo()));
        }
        gameRepository.save(game);

        // 1. 수입 배분
        incomeService.applyRoundIncome(game);

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
