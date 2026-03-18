package com.gaiaproject.service;

import com.gaiaproject.domain.entity.game.Game;
import com.gaiaproject.domain.entity.game.GameParticipant;
import com.gaiaproject.domain.entity.game.GameSeat;
import com.gaiaproject.domain.entity.player.GamePlayerState;
import com.gaiaproject.domain.entity.player.Player;
import com.gaiaproject.domain.entity.rounds.GameFinalScoring;
import com.gaiaproject.domain.entity.rounds.GameRoundScoring;
import com.gaiaproject.domain.enumtype.player.FactionType;
import com.gaiaproject.domain.enumtype.rounds.FinalScoringTileType;
import com.gaiaproject.domain.enumtype.rounds.RoundScoringTileType;
import com.gaiaproject.dto.request.CreateRoomRequest;
import com.gaiaproject.dto.request.EnterGameRequest;
import com.gaiaproject.dto.request.SelectBoosterRequest;
import com.gaiaproject.dto.response.CreateRoomResponse;
import com.gaiaproject.dto.response.EnterGameResponse;
import com.gaiaproject.dto.response.ClaimSeatResponse;
import com.gaiaproject.dto.response.GamePublicStateResponse;
import com.gaiaproject.dto.response.ParticipantListResponse;
import com.gaiaproject.dto.response.PlayerStateResponse;
import com.gaiaproject.dto.response.SelectBoosterResponse;
import com.gaiaproject.dto.response.StartGameResponse;
import com.gaiaproject.dto.response.VerifyParticipantResponse;
import com.gaiaproject.domain.enumtype.building.AcademyType;
import com.gaiaproject.domain.enumtype.building.BuildingType;
import com.gaiaproject.repository.building.GameBuildingRepository;
import com.gaiaproject.repository.game.GameParticipantRepository;
import com.gaiaproject.repository.game.GameRepository;
import com.gaiaproject.repository.game.GameSeatRepository;
import com.gaiaproject.repository.player.GamePlayerStateRepository;
import com.gaiaproject.repository.player.PlayerRepository;
import com.gaiaproject.repository.rounds.GameFinalScoringRepository;
import com.gaiaproject.repository.rounds.GameRoundScoringRepository;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class GameService {

    /** 방(게임) 저장소 */
    private final GameRepository gameRepository;

    /** 플레이어 저장소 */
    private final PlayerRepository playerRepository;

    /** 입장 기록 저장소 */
    private final GameParticipantRepository gameParticipantRepository;

    /** 좌석 저장소 */
    private final GameSeatRepository gameSeatRepository;

    /** 라운드 타일 저장소 */
    private final GameRoundScoringRepository roundScoringRepository;

    /** 라운드 타일 저장소 */
    private final GameFinalScoringRepository finalScoringRepository;

    /** 라운드 부스터 관련 서비스 **/
    private final RoundBoosterService roundBoosterService;

    /** 기술 타일 관련 서비스 **/
    private final TechTileService techTileService;

    /** 연방 타일 관련 서비스 **/
    private final FederationTileService federationTileService;

    /** 인공물 타일 관련 서비스 **/
    private final ArtifactService artifactService;

    /** 맵 섹터 관련 서비스 **/
    private final MapService mapService;

    /** 플레이어 상태 저장소 **/
    private final GamePlayerStateRepository playerStateRepository;

    /** 건물 저장소 **/
    private final GameBuildingRepository gameBuildingRepository;

    /** 수입 관련 서비스 **/
    private final IncomeService incomeService;

    /** WebSocket 브로드캐스트 서비스 **/
    private final GameWebSocketService webSocketService;

    /** 함대 탐사선 저장소 **/
    private final com.gaiaproject.repository.player.GamePlayerFleetProbeRepository fleetProbeRepository;

    /** 광산 배치 순서 계산 서비스 */
    private final MineSetupOrderService mineSetupOrderService;

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 방 코드 또는 UUID로 roomId 조회
     */
    public UUID findRoomIdByCode(String roomCodeOrId) {
        // 1. UUID 형식인지 확인
        try {
            UUID uuid = UUID.fromString(roomCodeOrId);
            // UUID로 직접 조회
            if (gameRepository.existsById(uuid)) {
                return uuid;
            }
        } catch (IllegalArgumentException ignored) {
            // UUID 형식이 아님 - roomCode로 조회
        }

        // 2. roomCode로 조회
        return gameRepository.findByRoomCode(roomCodeOrId)
                .map(Game::getId)
                .orElse(null);
    }

    /**
     * 방 생성
     * - game row 생성
     * - seat 1~4 생성(종족 4개 랜덤 + turnOrder 랜덤)
     */
    public CreateRoomResponse createRoom(CreateRoomRequest request) {
        String roomCode = generateUniqueRoomCode(8);

        /* 4인 고정 */
        int playerCount = 4;

        Game game = Game.createRoom(request.title(), roomCode);
        gameRepository.save(game);

        /* 종족 4개 랜덤 선택(Enum 기반) */
        List<FactionType> factionTypeResult = setupGameSeats(game.getId(), playerCount);

        /* 라운드 부스터 셋팅 */
        roundBoosterService.initializeBoosters(game, playerCount);

        /* 기본 기술 타일 셋팅 */
        techTileService.setupTechTiles(game.getId());

        /* 연방 타일 셋팅 */
        federationTileService.setupFederationTiles(game.getId());

        /* 인공물 타일 셋팅 */
        artifactService.setupArtifactTiles(game.getId());

        /* 라운드 미션 & 최종 미션 */
        setupScoringTiles(game.getId());

        /* 맵 섹터 배치 */
        mapService.setupMapSectors(game.getId());

        return new CreateRoomResponse(game.getId(), game.getTitle(), game.getRoomCode(), game.getStatus(), factionTypeResult);
    }

    /**
     * 방 입장
     * 동작:
     * 1) roomId 존재 확인
     * 2) Player 생성
     * 3) GameParticipant(입장 기록) 생성
     * 4) playerId 반환
     */
    public EnterGameResponse enterRoom(UUID roomId, EnterGameRequest request) {
        // 1) 방 존재 확인
        gameRepository.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("room not found: " + roomId));

        String nickname = request.nickname();

        // 2) 관전자 처리 (닉네임 없음)
        if (nickname == null || nickname.isBlank()) {
            return EnterGameResponse.spectator(roomId);
        }

        // 3) 닉네임 중복 확인 → 재입장 처리
        Optional<GameParticipant> existingParticipant = gameParticipantRepository.findByGameIdAndNickname(roomId, nickname);
        if (existingParticipant.isPresent()) {
            GameParticipant participant = existingParticipant.get();
            String providedToken = request.rejoinToken();

            // rejoinToken 검증
            if (providedToken == null || !providedToken.equals(participant.getRejoinToken())) {
                // 토큰 없거나 불일치 → 입장 거부
                return EnterGameResponse.invalidRejoinToken(roomId);
            }

            // 토큰 일치 → 재입장 허용
            return EnterGameResponse.rejoin(roomId, participant.getPlayerId(), participant.getRejoinToken());
        }

        // 4) 참가자 수 제한 (최대 4명)
        long participantCount = gameParticipantRepository.countByGameId(roomId);
        if (participantCount >= 4) {
            return EnterGameResponse.full(roomId);
        }

        // 5) 플레이어 생성
        Player player = Player.create(nickname);
        playerRepository.save(player);

        // 6) 입장 기록 생성
        GameParticipant participant = GameParticipant.enter(roomId, player.getId());
        gameParticipantRepository.save(participant);

        // 7) WebSocket 브로드캐스트 - 새 플레이어 입장 알림
        webSocketService.broadcastPlayerJoined(roomId, player.getId(), nickname);

        // 8) 응답 (rejoinToken 포함)
        return EnterGameResponse.player(roomId, player.getId(), participant.getRejoinToken());
    }


    /**
     * 좌석 선점(턴 선택)
     *
     * 동작:
     * 1) 방 존재 확인
     * 2) player가 해당 방에 입장했는지 확인(Participant 존재)
     * 3) 좌석 row를 비관적 락으로 조회
     * 4) 이미 선점됐으면 예외(409로 매핑 권장)
     * 5) 선점 처리(seat.playerId 업데이트 + participant.claimedSeatNo 업데이트)
     * 6) 최신 public-state 반환(프론트가 즉시 갱신 가능)
     */
    public ClaimSeatResponse claimSeat(UUID roomId, int seatNo, UUID playerId) {
        // 1) 방 존재 확인
        Game game = gameRepository.findById(roomId).orElse(null);
        if (game == null) {
            return ClaimSeatResponse.fail(roomId, "방을 찾을 수 없습니다.");
        }

        // 2) 입장 여부 확인 (playerId 위조/실수 방지)
        GameParticipant participant = gameParticipantRepository.findByGameIdAndPlayerId(roomId, playerId)
                .orElse(null);
        if (participant == null) {
            return ClaimSeatResponse.fail(roomId, "방에 입장하지 않은 플레이어입니다.");
        }

        // 3) 좌석 번호 검증(1~4 고정)
        if (seatNo < 1 || seatNo > 4) {
            return ClaimSeatResponse.fail(roomId, "좌석 번호는 1~4 사이여야 합니다.");
        }

        // 4) 좌석 row 조회
        GameSeat seat = gameSeatRepository.findByGameIdAndSeatNo(roomId, seatNo).orElse(null);
        if (seat == null) {
            return ClaimSeatResponse.fail(roomId, "좌석을 찾을 수 없습니다.");
        }

        // 5) 이미 선점된 좌석이면 실패
        if (seat.getPlayerId() != null) {
            return ClaimSeatResponse.fail(roomId, "이미 선점된 좌석입니다. seatNo=" + seatNo);
        }

        // 6) 같은 플레이어가 이미 다른 좌석을 선점했는지 방어
        boolean alreadyClaimed = gameSeatRepository.findByGameIdOrderBySeatNoAsc(roomId).stream()
                .anyMatch(s -> playerId.equals(s.getPlayerId()));
        if (alreadyClaimed) {
            return ClaimSeatResponse.fail(roomId, "이미 좌석을 선택한 플레이어입니다.");
        }

        // 7) 선점 처리 (dirty checking으로 update됨)
        seat.claim(playerId);
        participant.claimSeat(seatNo);

        // 8) 플레이어 상태 생성 (종족 초기 자원 적용)
        GamePlayerState playerState = GamePlayerState.createWithFaction(
                roomId,
                playerId,
                seatNo,
                seat.getFactionType()
        );
        playerStateRepository.save(playerState);

        // 9) WebSocket 브로드캐스트 - 좌석 선택 알림
        webSocketService.broadcastSeatClaimed(roomId, playerId, seatNo, seat.getFactionType().getDisplayNameKo());

        // 10) 성공 응답
        return ClaimSeatResponse.success(roomId, getPublicState(roomId));
    }

    /**
     * 방 공개 상태 조회
     * - 좌석 선택 전에도, 선택 후에도 동일한 응답으로 FE를 갱신할 수 있게 한다.
     */
    public GamePublicStateResponse getPublicState(UUID roomId) {
        Game game = gameRepository.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("room not found: " + roomId));

        var seats = gameSeatRepository.findByGameIdOrderBySeatNoAsc(roomId).stream()
                .map(s -> {
                    // 플레이어 닉네임 조회
                    String nickname = null;
                    if (s.getPlayerId() != null) {
                        nickname = playerRepository.findById(s.getPlayerId())
                                .map(Player::getNickname)
                                .orElse(null);
                    }
                    return new GamePublicStateResponse.SeatView(
                            s.getSeatNo(),
                            s.getTurnOrder(),
                            s.getFactionType().name(),                 // 종족 코드
                            s.getFactionType().getDisplayNameKo(),     // 종족 한글명
                            s.getFactionType().getHomePlanet().name(), // 고향 행성 타입 코드
                            s.getPlayerId(),
                            nickname
                    );
                })
                .collect(Collectors.toList());

        String economyOption = game.getEconomyTrackOption() != null
                ? game.getEconomyTrackOption().name()
                : null;

        String gamePhase = game.getGamePhase();
        Integer nextSetupSeatNo = game.isInSetupPhase() ? game.getCurrentSetupSeatNo() : null;
        Integer currentTurnSeatNo = "PLAYING".equals(gamePhase) ? game.getCurrentTurnSeatNo() : null;

        // 특수 페이즈 복원 정보
        String pendingSpecialPlayerId = null;
        java.util.Map<String, Object> pendingSpecialData = null;

        if ("ITARS_GAIA_PHASE".equals(gamePhase)) {
            GamePlayerState itarsPlayer = playerStateRepository.findByGameId(game.getId()).stream()
                    .filter(p -> p.getFactionType() == FactionType.ITARS)
                    .findFirst().orElse(null);
            if (itarsPlayer != null) {
                pendingSpecialPlayerId = itarsPlayer.getPlayerId().toString();
                pendingSpecialData = java.util.Map.of("availableChoices", itarsPlayer.getGaiaPower() / 4);
            }
        } else if ("TINKEROIDS_ACTION_PHASE".equals(gamePhase)) {
            GamePlayerState tinkPlayer = playerStateRepository.findByGameId(game.getId()).stream()
                    .filter(p -> p.getFactionType() == FactionType.TINKEROIDS)
                    .findFirst().orElse(null);
            if (tinkPlayer != null) {
                pendingSpecialPlayerId = tinkPlayer.getPlayerId().toString();
                int round = game.getCurrentRound() != null ? game.getCurrentRound() : 1;
                java.util.List<String> pool = round <= 3
                        ? java.util.List.of("TINK_TERRAFORM_1", "TINK_POWER_4", "TINK_QIC_1")
                        : java.util.List.of("TINK_TERRAFORM_3", "TINK_KNOWLEDGE_3", "TINK_QIC_2");
                java.util.List<String> available = pool.stream().filter(a -> !tinkPlayer.isTinkeroidsActionUsed(a)).toList();
                pendingSpecialData = java.util.Map.of("availableActions", available, "currentRound", round);
            }
        }

        return new GamePublicStateResponse(
                game.getId(),
                game.getStatus(),
                game.getCurrentRound(),
                economyOption,
                gamePhase,
                nextSetupSeatNo,
                currentTurnSeatNo,
                game.getTinkeroidsExtraRingPlanet(),
                game.getMoweidsExtraRingPlanet(),
                pendingSpecialPlayerId,
                pendingSpecialData,
                seats
        );
    }

    /**
     * 참가자 검증 (재입장 시 playerId 유효성 확인)
     * - playerId가 해당 방에 입장한 기록이 있는지 확인
     * - 기존 좌석 정보, 닉네임 등을 반환
     */
    public VerifyParticipantResponse verifyParticipant(UUID roomId, UUID playerId) {
        // 1) 방 존재 확인
        Game game = gameRepository.findById(roomId).orElse(null);
        if (game == null) {
            return VerifyParticipantResponse.invalid(roomId, playerId, "방을 찾을 수 없습니다.");
        }

        // 2) 참가자 기록 확인
        GameParticipant participant = gameParticipantRepository.findByGameIdAndPlayerId(roomId, playerId)
                .orElse(null);
        if (participant == null) {
            return VerifyParticipantResponse.invalid(roomId, playerId, "해당 방에 입장한 기록이 없습니다.");
        }

        // 3) 플레이어 정보 조회
        String nickname = playerRepository.findById(playerId)
                .map(Player::getNickname)
                .orElse("Unknown");

        // 4) 좌석 정보 조회
        Integer seatNo = participant.getClaimedSeatNo();
        String factionName = null;

        if (seatNo != null) {
            GameSeat seat = gameSeatRepository.findByGameIdAndSeatNo(roomId, seatNo).orElse(null);
            if (seat != null) {
                factionName = seat.getFactionType().getDisplayNameKo();
            }
        }

        return VerifyParticipantResponse.valid(roomId, playerId, nickname, seatNo, factionName);
    }

    /**
     * 방 참가자 목록 조회
     */
    public ParticipantListResponse getParticipants(UUID roomId) {
        // 참가자 목록 조회
        var participants = gameParticipantRepository.findByGameIdOrderByEnteredAtAsc(roomId);

        // 좌석 정보 조회 (종족명 표시용)
        var seatMap = gameSeatRepository.findByGameIdOrderBySeatNoAsc(roomId).stream()
                .collect(Collectors.toMap(GameSeat::getSeatNo, s -> s));

        // 참가자 정보 변환
        var participantViews = participants.stream()
                .map(p -> {
                    // 플레이어 닉네임 조회
                    String nickname = playerRepository.findById(p.getPlayerId())
                            .map(Player::getNickname)
                            .orElse("Unknown");

                    // 좌석 선택 시 종족명
                    String factionName = null;
                    if (p.getClaimedSeatNo() != null) {
                        GameSeat seat = seatMap.get(p.getClaimedSeatNo());
                        if (seat != null) {
                            factionName = seat.getFactionType().getDisplayNameKo();
                        }
                    }

                    return new ParticipantListResponse.ParticipantView(
                            p.getPlayerId(),
                            nickname,
                            p.getClaimedSeatNo(),
                            factionName,
                            p.getEnteredAt()
                    );
                })
                .collect(Collectors.toList());

        return new ParticipantListResponse(roomId, participants.size(), participantViews);
    }

    /**
     * 중복 없는 roomCode 생성
     */
    private String generateUniqueRoomCode(int length) {
        String code;
        do {
            code = randomCode(length);
        } while (gameRepository.existsByRoomCode(code));
        return code;
    }

    private String randomCode(int length) {
        final String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // O/I/0/1 제외
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }

    /**
     * 라운드 부스터 선택
     * - 초기 선택 순서: 4 → 3 → 2 → 1
     * - 광산 배치 완료 후 진행
     */
    public SelectBoosterResponse selectBooster(UUID roomId, SelectBoosterRequest request) {
        UUID playerId = request.playerId();
        String boosterCode = request.boosterCode();

        // 1) 방 존재 확인
        Game game = gameRepository.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("room not found: " + roomId));

        // 2) 부스터 선택 단계인지 확인
        if (!game.isInBoosterSelectionPhase()) {
            return SelectBoosterResponse.fail(roomId, "부스터 선택 단계가 아닙니다. 현재 단계: " + game.getGamePhase());
        }

        // 3) 플레이어가 좌석을 선점했는지 확인
        GameSeat seat = gameSeatRepository.findByGameIdOrderBySeatNoAsc(roomId).stream()
                .filter(s -> playerId.equals(s.getPlayerId()))
                .findFirst()
                .orElse(null);

        if (seat == null) {
            return SelectBoosterResponse.fail(roomId, "좌석을 선택하지 않은 플레이어입니다.");
        }

        int seatNo = seat.getSeatNo();

        // 4) 현재 선택해야 할 좌석 번호 확인 (4 → 3 → 2 → 1)
        int currentPickSeatNo = roundBoosterService.getCurrentPickSeatNo(roomId);
        if (currentPickSeatNo == 0) {
            return SelectBoosterResponse.fail(roomId, "모든 플레이어가 이미 부스터를 선택했습니다.");
        }

        if (seatNo != currentPickSeatNo) {
            return SelectBoosterResponse.fail(roomId,
                    "현재 " + currentPickSeatNo + "번 좌석 플레이어의 차례입니다.");
        }

        // 5) 부스터 선택 처리
        String errorMessage = roundBoosterService.pickBooster(roomId, seatNo, playerId, boosterCode);
        if (errorMessage != null) {
            return SelectBoosterResponse.fail(roomId, errorMessage);
        }

        // 6) 다음 선택할 좌석 번호 반환
        int nextSeatNo = roundBoosterService.getCurrentPickSeatNo(roomId);
        System.out.println("=== [DEBUG] nextSeatNo: " + nextSeatNo + " ===");

        // 7) WebSocket 브로드캐스트 - 부스터 선택 알림
        webSocketService.broadcastBoosterSelected(roomId, playerId, seatNo, boosterCode, nextSeatNo);

        // 8) 모든 부스터 선택 완료 시 → 실제 게임 시작 (라운드 1 수입 적용)
        if (nextSeatNo == 0) {
            game.startPlaying();
            gameRepository.save(game);
            incomeService.applyRoundIncome(game);

            // 팅커로이드 PI 체크 (셋업 시 PI 배치하므로 라운드 1부터 적용)
            java.util.List<GamePlayerState> allPlayers = playerStateRepository.findByGameId(roomId);
            GamePlayerState tinkeroidsPlayer = allPlayers.stream()
                    .filter(p -> p.getFactionType() == FactionType.TINKEROIDS
                            && p.getStockPlanetaryInstitute() == 0)
                    .findFirst().orElse(null);

            if (tinkeroidsPlayer != null) {
                int round = game.getCurrentRound() != null ? game.getCurrentRound() : 1;
                java.util.List<String> pool = round <= 3
                        ? java.util.List.of("TINK_TERRAFORM_1", "TINK_POWER_4", "TINK_QIC_1")
                        : java.util.List.of("TINK_TERRAFORM_3", "TINK_KNOWLEDGE_3", "TINK_QIC_2");
                java.util.List<String> available = pool.stream().filter(a -> !tinkeroidsPlayer.isTinkeroidsActionUsed(a)).toList();
                if (!available.isEmpty()) {
                    game.setGamePhase("TINKEROIDS_ACTION_PHASE");
                    gameRepository.save(game);
                    webSocketService.broadcastTinkeroidsActionChoice(roomId, tinkeroidsPlayer.getPlayerId(), available, round);
                    return SelectBoosterResponse.success(roomId, nextSeatNo);
                }
            }

            webSocketService.broadcastStateUpdated(roomId);
        }

        return SelectBoosterResponse.success(roomId, nextSeatNo);
    }

    /**
     * 게임 시작 (초기 광산 배치 단계로 진입)
     * - 플로우: 좌석 선택 → 게임 시작 → 광산 배치 → 부스터 선택 → 라운드 1
     */
    public StartGameResponse startGame(UUID roomId) {
        // 1) 방 존재 확인
        Game game = gameRepository.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("room not found: " + roomId));

        // 2) 이미 시작된 게임인지 확인
        if (!"READY".equals(game.getStatus())) {
            return StartGameResponse.fail(roomId, "이미 시작된 게임입니다.");
        }

        // 3) 모든 좌석이 선점되었는지 확인
        List<GameSeat> seats = gameSeatRepository.findByGameIdOrderBySeatNoAsc(roomId);
        for (GameSeat seat : seats) {
            if (seat.getPlayerId() == null) {
                return StartGameResponse.fail(roomId, seat.getSeatNo() + "번 좌석에 플레이어가 없습니다.");
            }
        }

        // 4) 광산 배치 순서 계산
        List<MineSetupOrderService.SetupTurn> setupOrder = mineSetupOrderService.calculateSetupOrder(seats);
        List<Integer> seatNoList = setupOrder.stream()
                .map(MineSetupOrderService.SetupTurn::getSeatNo)
                .toList();
        String setupOrderJson = seatNoList.toString(); // "[1,2,3,4,3,2,1]"

        // 5) 경제 트랙 옵션 랜덤 선택 및 초기 광산 배치 단계 시작
        game.startGame();  // status = IN_PROGRESS, currentRound = 1
        game.startInitialMinePlacement(setupOrderJson);  // gamePhase = SETUP_MINE_FIRST

        // 5-1) 팅커로이드/모웨이드 추가 3삽 행성 랜덤 할당
        assignExtraRingPlanetsIfNeeded(game, seats);

        // 5-2) 모웨이드: TF_MARS 함대 우주선 자동 입장 (VP 제거 없음)
        for (GameSeat seat : seats) {
            if (seat.getFactionType() == com.gaiaproject.domain.enumtype.player.FactionType.MOWEIDS && seat.getPlayerId() != null) {
                fleetProbeRepository.save(com.gaiaproject.domain.entity.player.GamePlayerFleetProbe.builder()
                        .gameId(game.getId()).playerId(seat.getPlayerId()).fleetName("TF_MARS").build());
            }
        }

        gameRepository.save(game);

        // 6) WebSocket 브로드캐스트 - 게임 시작 알림
        webSocketService.broadcastGameStarted(roomId, game.getGamePhase(), game.getCurrentSetupSeatNo());

        return StartGameResponse.startMinePlacement(roomId, game.getCurrentSetupSeatNo(), game.getGamePhase());
    }

    /** 기본 7종 링 행성 순서 (테라포밍 링) */
    private static final List<String> TERRAFORM_RING = List.of(
            "TERRA", "SWAMP", "OXIDE", "VOLCANIC", "ICE", "TITANIUM", "DESERT"
    );

    /**
     * 팅커로이드/모웨이드 추가 3삽 행성 랜덤 할당.
     * - 팅커로이드: 모웨이드 or 스페이스자이언트가 함께 플레이할 때 필요
     * - 모웨이드: 다카니안 or 팅커로이드가 함께 플레이할 때 필요
     * - 두 종족이 모두 필요하면 서로 다른 행성 할당 (uniqueness constraint)
     */
    private void assignExtraRingPlanetsIfNeeded(Game game, List<GameSeat> seats) {
        List<FactionType> factions = seats.stream()
                .map(GameSeat::getFactionType)
                .collect(Collectors.toList());

        boolean tinkeroidsPlaying  = factions.contains(FactionType.TINKEROIDS);
        boolean moweidsPlaying     = factions.contains(FactionType.MOWEIDS);
        boolean dakanianPlaying    = factions.contains(FactionType.DAKANIANS);
        boolean spaceGiantsPlaying = factions.contains(FactionType.SPACE_GIANTS);

        boolean tinkeroidsNeedsExtra = tinkeroidsPlaying  && (moweidsPlaying || spaceGiantsPlaying);
        boolean moweidsNeedsExtra    = moweidsPlaying     && (dakanianPlaying || tinkeroidsPlaying);

        if (!tinkeroidsNeedsExtra && !moweidsNeedsExtra) return;

        // 현재 플레이 중인 표준 링 행성 목록 제외
        Set<String> usedRingPlanets = factions.stream()
                .map(f -> f.getHomePlanet().name())
                .filter(TERRAFORM_RING::contains)
                .collect(Collectors.toSet());

        List<String> unplayed = new ArrayList<>(TERRAFORM_RING);
        unplayed.removeAll(usedRingPlanets);
        Collections.shuffle(unplayed, RANDOM);

        String tinkeroidsExtra = null;
        String moweidsExtra    = null;

        if (tinkeroidsNeedsExtra && moweidsNeedsExtra) {
            tinkeroidsExtra = unplayed.get(0);
            moweidsExtra    = unplayed.get(1);
        } else if (tinkeroidsNeedsExtra) {
            tinkeroidsExtra = unplayed.get(0);
        } else {
            moweidsExtra = unplayed.get(0);
        }

        game.assignExtraRingPlanets(tinkeroidsExtra, moweidsExtra);
    }

    /**
     * 실제 게임 시작 (광산 배치 완료 후 호출)
     * - 라운드 1 수입 적용
     */
    public void completeSetupAndStartGame(Game game) {
        // 라운드 1 수입 적용 (부스터 + 기술트랙 + 기술타일 + 인공물 + 건물)
        incomeService.applyRoundIncome(game);
    }

    /**
     * 새 라운드 시작 (라운드 2~6)
     * - 모든 플레이어가 패스했는지 확인
     * - 라운드 수입 적용
     * - 다음 라운드로 진행
     */
    public StartGameResponse startNewRound(UUID roomId) {
        // 1) 방 존재 및 상태 확인
        Game game = gameRepository.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("room not found: " + roomId));

        if (!"IN_PROGRESS".equals(game.getStatus())) {
            return StartGameResponse.fail(roomId, "진행 중인 게임이 아닙니다.");
        }

        if (game.getCurrentRound() >= 6) {
            return StartGameResponse.fail(roomId, "이미 마지막 라운드입니다.");
        }

        // 2) 다음 라운드로 진행
        game.nextRound();

        // 3) 라운드 수입 적용
        incomeService.applyRoundIncome(game);

        return StartGameResponse.success(roomId, game.getCurrentRound(), game.getCurrentTurnSeatNo());
    }

    /**
     * 게임 좌석 초기화 (종족 4개 랜덤 배치)
     */
    public List<FactionType> setupGameSeats(UUID gameId, int playerCount) {
        // Enum에서 4개 랜덤 선택
        List<FactionType> selectedFactions = FactionType.getRandomFourDifferentHomePlanets(playerCount);

        // 좌석 1~4 생성 및 저장
        for (int seatNo = 1; seatNo <= playerCount; seatNo++) {
            FactionType faction = selectedFactions.get(seatNo - 1);

            GameSeat seat = GameSeat.create(
                    gameId,
                    seatNo,      // 좌석 번호
                    0,           // 턴 순서 (0=미패스, 패스 시 1~4)
                    faction      // 종족
            );

            gameSeatRepository.save(seat);
        }

        return selectedFactions;
    }

    /**
     * 게임 점수 타일 초기화
     */
    public void setupScoringTiles(UUID gameId) {
        // 1. 라운드 점수 타일 6개 랜덤 선택
        List<RoundScoringTileType> roundTiles = RoundScoringTileType.getRandomSix();
        for (int i = 0; i < roundTiles.size(); i++) {
            GameRoundScoring scoring = GameRoundScoring.builder()
                    .gameId(gameId)
                    .roundNumber(i + 1)  // 1~6
                    .scoringTileCode(roundTiles.get(i))
                    .build();
            roundScoringRepository.save(scoring);
        }

        // 2. 최종 점수 타일 2개 랜덤 선택
        List<FinalScoringTileType> finalTiles = FinalScoringTileType.getRandomTwo();
        for (int i = 0; i < finalTiles.size(); i++) {
            GameFinalScoring scoring = GameFinalScoring.builder()
                    .gameId(gameId)
                    .position(i + 1)  // 1, 2
                    .scoringTileCode(finalTiles.get(i))
                    .build();
            finalScoringRepository.save(scoring);
        }
    }

    /**
     * 플레이어 상태 조회 (자원, 건물 재고 등)
     */
    public List<PlayerStateResponse> getPlayerStates(UUID roomId) {
        return playerStateRepository.findByGameId(roomId).stream()
                .map(p -> {
                    boolean hasQicAcademy = gameBuildingRepository.countByGameIdAndPlayerIdAndBuildingTypeAndAcademyType(
                            roomId, p.getPlayerId(), BuildingType.ACADEMY, AcademyType.QIC) > 0;
                    return new PlayerStateResponse(
                        p.getPlayerId(),
                        p.getSeatNo(),
                        p.getFactionType() != null ? p.getFactionType().name() : null,
                        p.getCredit(),
                        p.getOre(),
                        p.getKnowledge(),
                        p.getQic(),
                        p.getPowerBowl1(),
                        p.getPowerBowl2(),
                        p.getPowerBowl3(),
                        p.getVictoryPoints(),
                        p.getStockMine(),
                        p.getStockTradingStation(),
                        p.getStockResearchLab(),
                        p.getStockPlanetaryInstitute(),
                        p.getStockAcademy(),
                        p.getStockGaiaformer(),
                        p.getTechTerraforming(),
                        p.getTechNavigation(),
                        p.getTechAi(),
                        p.getTechGaia(),
                        p.getTechEconomy(),
                        p.getTechScience(),
                        p.getGaiaPower(),
                        p.getBrainstoneBowl(),
                        p.isBoosterActionUsed(),
                        p.isFactionAbilityUsed(),
                        p.getBaltaksConvertedGaiaformers(),
                        p.getPermanentlyRemovedGaiaformers(),
                        hasQicAcademy,
                        p.isQicAcademyActionUsed()
                    );
                })
                .collect(Collectors.toList());
    }
}
