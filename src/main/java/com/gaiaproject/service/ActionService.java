package com.gaiaproject.service;

import com.gaiaproject.domain.entity.game.Game;
import com.gaiaproject.domain.entity.game.GameAction;
import com.gaiaproject.domain.entity.game.GameSeat;
import com.gaiaproject.domain.entity.player.GamePlayerState;
import com.gaiaproject.domain.enumtype.action.ActionType;
import com.gaiaproject.dto.response.ActionLogEntry;
import com.gaiaproject.dto.response.ConfirmActionResponse;
import com.gaiaproject.repository.game.GameActionRepository;
import com.gaiaproject.repository.game.GamePlayerPassRepository;
import com.gaiaproject.repository.game.GameRepository;
import com.gaiaproject.repository.game.GameSeatRepository;
import com.gaiaproject.repository.player.GamePlayerStateRepository;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 게임 액션 관리 서비스
 * - FE에서 확정된 액션만 DB에 저장 (기록용)
 * - 액션 확정 시 자동 턴 넘김
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class ActionService {

    private final GameActionRepository actionRepository;
    private final GamePlayerPassRepository passRepository;
    private final GameRepository gameRepository;
    private final GameSeatRepository seatRepository;
    private final GamePlayerStateRepository playerStateRepository;
    private final GameWebSocketService webSocketService;

    /**
     * 액션 저장 + 자동 턴 넘김
     * FE에서 확정 버튼을 누른 후 호출
     */
    public ConfirmActionResponse saveActionAndNextTurn(UUID gameId, UUID playerId,
                                                        ActionType actionType, String actionData) {
        try {
            Game game = gameRepository.findById(gameId)
                    .orElseThrow(() -> new IllegalArgumentException("게임을 찾을 수 없습니다"));

            // 현재 라운드와 턴 시퀀스 조회
            int currentRound = game.getCurrentRound() != null ? game.getCurrentRound() : 1;
            int turnSequence = calculateCurrentTurnSequence(gameId, currentRound);

            // 액션 저장 (기록용)
            GameAction action = GameAction.builder()
                    .gameId(gameId)
                    .playerId(playerId)
                    .roundNumber(currentRound)
                    .turnSequence(turnSequence)
                    .actionType(actionType)
                    .actionData(actionData)
                    .build();

            action = actionRepository.save(action);
            broadcastActionLog(gameId, playerId, action, currentRound, turnSequence);

            log.info("액션 저장: actionId={}, type={}, player={}", action.getId(), actionType, playerId);

            // 현재 플레이어 타이머 종료
            stopTurnTimer(gameId, playerId);

            // 다음 턴 계산
            int nextSeatNo = calculateNextTurnSeatNo(game);
            boolean roundEnded = (nextSeatNo == 0);

            if (roundEnded) {
                // 라운드 종료 처리
                endRoundAndStartNext(game);
                webSocketService.broadcastRoundStarted(gameId, game.getCurrentRound());
                // 새 라운드 첫 플레이어 타이머 시작
                startTurnTimerBySeatNo(gameId, game.getCurrentTurnSeatNo());
            } else {
                // 턴 넘김
                game.nextTurn(nextSeatNo);
                gameRepository.save(game);
                webSocketService.broadcastTurnChanged(gameId, nextSeatNo);
                // 다음 플레이어 타이머 시작
                startTurnTimerBySeatNo(gameId, nextSeatNo);
            }

            return ConfirmActionResponse.success(gameId, action.getId(), nextSeatNo, roundEnded);

        } catch (Exception e) {
            log.error("액션 저장 실패: gameId={}, playerId={}", gameId, playerId, e);
            return ConfirmActionResponse.fail(gameId, null, "액션 저장 중 오류가 발생했습니다: " + e.getMessage());
        }
    }

    /**
     * 액션 저장만 (턴 진행 없음) — 파워 리치 결정 후 턴 진행 시 사용
     */
    public void saveActionOnly(UUID gameId, UUID playerId, ActionType actionType, String actionData) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new IllegalArgumentException("게임을 찾을 수 없습니다"));
        int currentRound = game.getCurrentRound() != null ? game.getCurrentRound() : 1;
        int turnSequence = calculateCurrentTurnSequence(gameId, currentRound);

        GameAction action = GameAction.builder()
                .gameId(gameId).playerId(playerId)
                .roundNumber(currentRound).turnSequence(turnSequence)
                .actionType(actionType).actionData(actionData)
                .build();
        action = actionRepository.save(action);
        broadcastActionLog(gameId, playerId, action, currentRound, turnSequence);
        log.info("액션 저장(리치 대기): actionType={}, player={}", actionType, playerId);
    }

    /** 액션 로그 브로드캐스트 */
    private void broadcastActionLog(UUID gameId, UUID playerId, GameAction action, int round, int turnSeq) {
        try {
            // POWER_INCOME은 로그 표시하지 않음
            if (action.getActionType() == ActionType.POWER_INCOME) return;

            GameSeat seat = seatRepository.findByGameIdAndPlayerId(gameId, playerId).orElse(null);
            int seatNo = seat != null ? seat.getSeatNo() : 0;
            String factionCode = seat != null && seat.getFactionType() != null ? seat.getFactionType().name() : "";

            Map<String, Object> dataMap = new LinkedHashMap<>();
            if (action.getActionData() != null && !action.getActionData().isEmpty()) {
                try {
                    dataMap = new com.fasterxml.jackson.databind.ObjectMapper().readValue(action.getActionData(), Map.class);
                } catch (Exception ignored) {}
            }

            Map<String, Object> logPayload = new LinkedHashMap<>();
            logPayload.put("actionId", action.getId().toString());
            logPayload.put("playerId", playerId.toString());
            logPayload.put("seatNo", seatNo);
            logPayload.put("factionCode", factionCode);
            logPayload.put("roundNumber", round);
            logPayload.put("turnSequence", turnSeq);
            logPayload.put("actionType", action.getActionType().name());
            logPayload.put("actionData", dataMap);

            webSocketService.broadcast(com.gaiaproject.dto.websocket.GameEvent.of(gameId, "ACTION_LOGGED",
                    Map.of("entry", logPayload)));
        } catch (Exception e) {
            log.warn("액션 로그 브로드캐스트 실패: {}", e.getMessage());
        }
    }

    /**
     * 턴 진행 + 브로드캐스트 — 파워 리치 해소 완료 후 호출
     */
    public void advanceTurnAndBroadcast(Game game) {
        // 현재 플레이어 타이머 종료
        stopTurnTimerBySeatNo(game.getId(), game.getCurrentTurnSeatNo());

        int nextSeatNo = calculateNextTurnSeatNo(game);
        boolean roundEnded = (nextSeatNo == 0);

        if (roundEnded) {
            endRoundAndStartNext(game);
            webSocketService.broadcastRoundStarted(game.getId(), game.getCurrentRound());
            startTurnTimerBySeatNo(game.getId(), game.getCurrentTurnSeatNo());
        } else {
            game.nextTurn(nextSeatNo);
            gameRepository.save(game);
            webSocketService.broadcastTurnChanged(game.getId(), nextSeatNo);
            startTurnTimerBySeatNo(game.getId(), nextSeatNo);
        }
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
     * 현재 턴 시퀀스 계산
     */
    private int calculateCurrentTurnSequence(UUID gameId, int roundNumber) {
        List<GameAction> actions = actionRepository.findByGameIdAndRoundNumber(gameId, roundNumber);
        return actions.size() + 1;
    }

    /**
     * 라운드 종료 및 다음 라운드 시작
     */
    private void endRoundAndStartNext(Game game) {
        log.info("라운드 {} 종료, 다음 라운드 시작", game.getCurrentRound());

        // TODO: 라운드 종료 처리 (수입 배분, 부스터 반환 등)

        // 다음 라운드로 이동
        game.nextRound();
        gameRepository.save(game);

        log.info("라운드 {} 시작", game.getCurrentRound());
    }

    // ===== 턴 타이머 헬퍼 =====

    /** playerId로 타이머 종료 + 누적 */
    public void stopTurnTimer(UUID gameId, UUID playerId) {
        playerStateRepository.findByGameIdAndPlayerId(gameId, playerId).ifPresent(ps -> {
            ps.stopTurnTimer();
            playerStateRepository.save(ps);
        });
    }

    /** seatNo로 타이머 종료 + 누적 */
    public void stopTurnTimerBySeatNo(UUID gameId, int seatNo) {
        var seat = seatRepository.findByGameIdAndSeatNo(gameId, seatNo);
        if (seat.isPresent() && seat.get().getPlayerId() != null) {
            stopTurnTimer(gameId, seat.get().getPlayerId());
        }
    }

    /** seatNo로 타이머 시작 */
    public void startTurnTimerBySeatNo(UUID gameId, int seatNo) {
        var seat = seatRepository.findByGameIdAndSeatNo(gameId, seatNo);
        if (seat.isPresent() && seat.get().getPlayerId() != null) {
            playerStateRepository.findByGameIdAndPlayerId(gameId, seat.get().getPlayerId()).ifPresent(ps -> {
                ps.startTurnTimer();
                playerStateRepository.save(ps);
            });
        }
    }

    /** playerId로 타이머 시작 */
    public void startTurnTimer(UUID gameId, UUID playerId) {
        playerStateRepository.findByGameIdAndPlayerId(gameId, playerId).ifPresent(ps -> {
            ps.startTurnTimer();
            playerStateRepository.save(ps);
        });
    }
}
