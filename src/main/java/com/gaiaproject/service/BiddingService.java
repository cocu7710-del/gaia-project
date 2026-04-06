package com.gaiaproject.service;

import com.gaiaproject.domain.entity.game.*;
import com.gaiaproject.domain.entity.player.GamePlayerState;
import com.gaiaproject.dto.websocket.GameEvent;
import com.gaiaproject.domain.entity.player.Player;
import com.gaiaproject.repository.game.*;
import com.gaiaproject.repository.player.GamePlayerStateRepository;
import com.gaiaproject.repository.player.PlayerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@Transactional
public class BiddingService {

    private final GameRepository gameRepository;
    private final GameBidRepository gameBidRepository;
    private final GameParticipantRepository gameParticipantRepository;
    private final GameSeatRepository gameSeatRepository;
    private final GamePlayerStateRepository playerStateRepository;
    private final PlayerRepository playerRepository;
    private final GameWebSocketService webSocketService;
    private final GameService gameService;

    public BiddingService(GameRepository gameRepository, GameBidRepository gameBidRepository,
                          GameParticipantRepository gameParticipantRepository, GameSeatRepository gameSeatRepository,
                          GamePlayerStateRepository playerStateRepository, PlayerRepository playerRepository,
                          GameWebSocketService webSocketService, @Lazy GameService gameService) {
        this.gameRepository = gameRepository;
        this.gameBidRepository = gameBidRepository;
        this.gameParticipantRepository = gameParticipantRepository;
        this.gameSeatRepository = gameSeatRepository;
        this.playerStateRepository = playerStateRepository;
        this.playerRepository = playerRepository;
        this.webSocketService = webSocketService;
        this.gameService = gameService;
    }

    /**
     * 4명 입장 시 비딩 시작
     */
    public void startBidding(UUID gameId) {
        Game game = gameRepository.findById(gameId).orElseThrow();

        // 입장 순서대로 참가자 목록
        List<GameParticipant> participants = gameParticipantRepository.findByGameIdOrderByEnteredAtAsc(gameId);
        if (participants.size() < 4) {
            throw new IllegalStateException("4명이 필요합니다.");
        }

        // GameBid 레코드 생성
        for (GameParticipant p : participants) {
            GameBid bid = GameBid.create(gameId, p.getPlayerId());
            gameBidRepository.save(bid);
        }

        // 게임 상태 변경
        game.startBidding();
        game.setBiddingTurnPlayerId(participants.get(0).getPlayerId());
        gameRepository.save(game);

        // WebSocket 브로드캐스트
        broadcastBiddingState(game);
        log.info("비딩 시작: game={}", gameId);
    }

    /**
     * 비딩 입찰
     */
    public Map<String, Object> placeBid(UUID gameId, UUID playerId, int amount) {
        Game game = gameRepository.findById(gameId).orElseThrow();

        if (!"BIDDING".equals(game.getGamePhase())) {
            return Map.of("success", false, "message", "비딩 단계가 아닙니다.");
        }
        if (!playerId.equals(game.getBiddingTurnPlayerId())) {
            return Map.of("success", false, "message", "내 비딩 턴이 아닙니다.");
        }
        if (amount <= game.getBiddingCurrentBid()) {
            return Map.of("success", false, "message", "현재 비딩(" + game.getBiddingCurrentBid() + ")보다 높아야 합니다.");
        }

        // 비딩 금액 업데이트
        GameBid bid = gameBidRepository.findByGameIdAndPlayerId(gameId, playerId).orElseThrow();
        bid.setBidAmount(amount);
        gameBidRepository.save(bid);

        game.setBiddingCurrentBid(amount);

        // 다음 턴으로
        advanceBiddingTurn(game);
        gameRepository.save(game);

        broadcastBiddingState(game);
        log.info("비딩 입찰: game={}, player={}, amount={}", gameId, playerId, amount);
        return Map.of("success", true);
    }

    /**
     * 비딩 패스
     */
    public Map<String, Object> passBid(UUID gameId, UUID playerId) {
        Game game = gameRepository.findById(gameId).orElseThrow();

        if (!"BIDDING".equals(game.getGamePhase())) {
            return Map.of("success", false, "message", "비딩 단계가 아닙니다.");
        }
        if (!playerId.equals(game.getBiddingTurnPlayerId())) {
            return Map.of("success", false, "message", "내 비딩 턴이 아닙니다.");
        }

        GameBid bid = gameBidRepository.findByGameIdAndPlayerId(gameId, playerId).orElseThrow();
        bid.setPassed(true);
        bid.setBidAmount(0); // 패스한 플레이어는 비딩 금액 0
        gameBidRepository.save(bid);

        // 남은 활성 비더 확인
        List<GameBid> activeBids = getActiveBids(gameId);

        if (activeBids.size() == 1) {
            // 1명 남음 → 낙찰
            GameBid winner = activeBids.get(0);
            handleBidWin(game, winner);
        } else {
            // 다음 턴으로
            advanceBiddingTurn(game);
            gameRepository.save(game);
            broadcastBiddingState(game);
        }

        log.info("비딩 패스: game={}, player={}", gameId, playerId);
        return Map.of("success", true);
    }

    /**
     * 낙찰자 좌석 선택
     */
    public Map<String, Object> pickSeat(UUID gameId, UUID playerId, int seatNo) {
        Game game = gameRepository.findById(gameId).orElseThrow();

        if (!"BID_SEAT_PICK".equals(game.getGamePhase())) {
            return Map.of("success", false, "message", "좌석 선택 단계가 아닙니다.");
        }
        if (!playerId.equals(game.getBiddingTurnPlayerId())) {
            return Map.of("success", false, "message", "좌석 선택 권한이 없습니다.");
        }

        // 좌석이 이미 선점되었는지 확인
        GameSeat seat = gameSeatRepository.findByGameIdAndSeatNo(gameId, seatNo).orElse(null);
        if (seat == null) {
            return Map.of("success", false, "message", "존재하지 않는 좌석입니다.");
        }
        if (seat.getPlayerId() != null) {
            return Map.of("success", false, "message", "이미 선점된 좌석입니다.");
        }

        // 좌석 선점
        seat.claim(playerId);
        gameSeatRepository.save(seat);

        // 플레이어 상태 생성 (종족 초기 자원) + bidPenalty 설정
        GamePlayerState playerState = GamePlayerState.createWithFaction(gameId, playerId, seatNo, seat.getFactionType());
        GameBid bid = gameBidRepository.findByGameIdAndPlayerId(gameId, playerId).orElseThrow();
        playerState.setBidPenalty(bid.getBidAmount());
        playerStateRepository.save(playerState);
        List<GameBid> alreadyPicked = gameBidRepository.findByGameIdAndPickOrderIsNotNullOrderByPickOrderAsc(gameId);
        bid.setPickOrder(alreadyPicked.size() + 1);
        bid.setSeatNo(seatNo);
        gameBidRepository.save(bid);

        // participant claimSeat
        gameParticipantRepository.findByGameIdAndPlayerId(gameId, playerId)
                .ifPresent(p -> { p.claimSeat(seatNo); gameParticipantRepository.save(p); });

        log.info("비딩 좌석 선택: game={}, player={}, seat={}, bidAmount={}", gameId, playerId, seatNo, bid.getBidAmount());

        // 좌석 선택 브로드캐스트
        webSocketService.broadcast(new GameEvent(
                gameId, GameEvent.EventType.BID_SEAT_PICKED, playerId,
                Map.of("seatNo", seatNo, "factionType", seat.getFactionType().name()),
                java.time.Instant.now()));

        // 다음 비딩 라운드 또는 비딩 완료
        startNextBiddingRoundOrFinish(game);

        return Map.of("success", true);
    }

    /**
     * 비딩 전체 상태 조회 (API용)
     */
    public Map<String, Object> getBiddingState(UUID gameId) {
        Game game = gameRepository.findById(gameId).orElseThrow();
        List<GameBid> allBids = gameBidRepository.findByGameIdOrderByCreatedAtAsc(gameId);
        List<GameParticipant> participants = gameParticipantRepository.findByGameIdOrderByEnteredAtAsc(gameId);

        // 닉네임 매핑 (Player 테이블에서)
        Map<UUID, String> nicknameMap = new HashMap<>();
        for (GameParticipant p : participants) {
            playerRepository.findById(p.getPlayerId())
                    .ifPresent(player -> nicknameMap.put(p.getPlayerId(), player.getNickname()));
        }

        List<Map<String, Object>> bidders = new ArrayList<>();
        for (GameBid bid : allBids) {
            bidders.add(Map.of(
                    "playerId", bid.getPlayerId().toString(),
                    "nickname", nicknameMap.getOrDefault(bid.getPlayerId(), ""),
                    "bidAmount", bid.getBidAmount(),
                    "isPassed", bid.isPassed(),
                    "pickOrder", bid.getPickOrder() != null ? bid.getPickOrder() : 0,
                    "seatNo", bid.getSeatNo() != null ? bid.getSeatNo() : 0
            ));
        }

        return Map.of(
                "gamePhase", game.getGamePhase() != null ? game.getGamePhase() : "",
                "biddingRound", game.getBiddingRound(),
                "currentBid", game.getBiddingCurrentBid(),
                "turnPlayerId", game.getBiddingTurnPlayerId() != null ? game.getBiddingTurnPlayerId().toString() : "",
                "bidders", bidders
        );
    }

    // === Private methods ===

    private List<GameBid> getActiveBids(UUID gameId) {
        return gameBidRepository.findByGameIdAndIsPassedFalseAndPickOrderIsNull(gameId);
    }

    private void handleBidWin(Game game, GameBid winner) {
        UUID gameId = game.getId();

        game.enterBidSeatPick(winner.getPlayerId());
        gameRepository.save(game);

        webSocketService.broadcast(new GameEvent(
                gameId, GameEvent.EventType.BID_WON, winner.getPlayerId(),
                Map.of("bidAmount", winner.getBidAmount()),
                java.time.Instant.now()));

        log.info("비딩 낙찰: game={}, player={}, amount={}", gameId, winner.getPlayerId(), winner.getBidAmount());
    }

    private void startNextBiddingRoundOrFinish(Game game) {
        UUID gameId = game.getId();
        List<GameBid> remaining = gameBidRepository.findByGameId(gameId).stream()
                .filter(b -> b.getPickOrder() == null)
                .collect(Collectors.toList());

        if (remaining.size() <= 1) {
            // 마지막 1명 → 자동 배정
            if (remaining.size() == 1) {
                GameBid lastBid = remaining.get(0);
                lastBid.setBidAmount(0);
                lastBid.setPassed(false);

                // 남은 좌석 자동 배정
                List<GameSeat> availableSeats = gameSeatRepository.findByGameIdOrderBySeatNoAsc(gameId).stream()
                        .filter(s -> s.getPlayerId() == null)
                        .toList();
                if (!availableSeats.isEmpty()) {
                    GameSeat lastSeat = availableSeats.get(0);
                    lastSeat.claim(lastBid.getPlayerId());
                    gameSeatRepository.save(lastSeat);

                    List<GameBid> alreadyPicked = gameBidRepository.findByGameIdAndPickOrderIsNotNullOrderByPickOrderAsc(gameId);
                    lastBid.setPickOrder(alreadyPicked.size() + 1);
                    lastBid.setSeatNo(lastSeat.getSeatNo());

                    // PlayerState 생성 (bidPenalty=0)
                    GamePlayerState lastPs = GamePlayerState.createWithFaction(
                            gameId, lastBid.getPlayerId(), lastSeat.getSeatNo(), lastSeat.getFactionType());
                    lastPs.setBidPenalty(0);
                    playerStateRepository.save(lastPs);
                }
                gameBidRepository.save(lastBid);

                gameParticipantRepository.findByGameIdAndPlayerId(gameId, lastBid.getPlayerId())
                        .ifPresent(p -> {
                            if (lastBid.getSeatNo() != null) p.claimSeat(lastBid.getSeatNo());
                            gameParticipantRepository.save(p);
                        });
            }

            // 비딩 완료 → 게임 시작 프로세스
            finishBiddingAndStartGame(game);
        } else {
            // 남은 플레이어들로 다음 비딩 라운드
            // 패스 상태 리셋
            for (GameBid bid : remaining) {
                bid.setPassed(false);
                bid.setBidAmount(0);
                gameBidRepository.save(bid);
            }

            // 입장 순서대로 첫 턴
            List<GameParticipant> participants = gameParticipantRepository.findByGameIdOrderByEnteredAtAsc(gameId);
            UUID firstTurn = null;
            for (GameParticipant p : participants) {
                if (remaining.stream().anyMatch(b -> b.getPlayerId().equals(p.getPlayerId()))) {
                    firstTurn = p.getPlayerId();
                    break;
                }
            }

            game.nextBiddingRound(firstTurn);
            gameRepository.save(game);

            broadcastBiddingState(game);
            log.info("다음 비딩 라운드: game={}, round={}, remaining={}", gameId, game.getBiddingRound(), remaining.size());
        }
    }

    private void finishBiddingAndStartGame(Game game) {
        UUID gameId = game.getId();

        game.finishBidding();
        gameRepository.save(game);

        webSocketService.broadcast(new GameEvent(
                gameId, GameEvent.EventType.BIDDING_COMPLETED, null,
                Map.of(), java.time.Instant.now()));

        log.info("비딩 완료, 게임 자동 시작: game={}", gameId);

        // 비딩 완료 → 게임 자동 시작 (광산 배치 단계)
        gameService.startGame(gameId);
    }

    private void advanceBiddingTurn(Game game) {
        UUID gameId = game.getId();
        UUID currentTurn = game.getBiddingTurnPlayerId();

        // 전체 입장 순서
        List<GameParticipant> participants = gameParticipantRepository.findByGameIdOrderByEnteredAtAsc(gameId);
        List<GameBid> activeBids = getActiveBids(gameId);
        Set<UUID> activePlayerIds = activeBids.stream().map(GameBid::getPlayerId).collect(Collectors.toSet());

        // 전체 참가자 순서에서 현재 위치 찾기
        List<UUID> allPlayerIds = participants.stream().map(GameParticipant::getPlayerId).toList();
        int currentIdx = allPlayerIds.indexOf(currentTurn);

        // 현재 위치 다음부터 순환하면서 활성 플레이어 찾기
        for (int i = 1; i <= allPlayerIds.size(); i++) {
            int nextIdx = (currentIdx + i) % allPlayerIds.size();
            UUID candidate = allPlayerIds.get(nextIdx);
            if (activePlayerIds.contains(candidate)) {
                game.setBiddingTurnPlayerId(candidate);
                return;
            }
        }
    }

    private void broadcastBiddingState(Game game) {
        Map<String, Object> state = getBiddingState(game.getId());
        webSocketService.broadcast(new GameEvent(
                game.getId(), GameEvent.EventType.BID_UPDATED, null,
                state, java.time.Instant.now()));
    }
}
