package com.gaiaproject.dto.websocket;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * WebSocket 게임 이벤트 DTO
 * - 실시간으로 브로드캐스트되는 모든 게임 이벤트
 */
public record GameEvent(
        UUID roomId,
        EventType eventType,
        UUID playerId,         // 이벤트를 발생시킨 플레이어 (없으면 null)
        Map<String, Object> payload,  // 이벤트별 추가 데이터
        Instant timestamp
) {
    public enum EventType {
        PLAYER_JOINED,          // 새 플레이어 입장
        SEAT_CLAIMED,           // 좌석 선택됨
        BOOSTER_SELECTED,       // 부스터 선택됨
        GAME_STARTED,           // 게임 시작됨
        MINE_PLACED,            // 광산 배치됨
        TURN_CHANGED,           // 턴 변경
        ROUND_STARTED,          // 라운드 시작
        PLAYER_PASSED,          // 플레이어 패스
        STATE_UPDATED,          // 상태 갱신 (범용)
        LEECH_OFFERED,          // 파워 리치 결정 요청
        LEECH_DECIDED,          // 파워 리치 결정 완료 (다음 결정자 또는 모두 완료)
        DEFERRED_ACTION_REQUIRED,  // 후속 액션 필요 (예: 2삽 1광산)
        POWER_INCOME_CHOICE,      // 파워 수입 순서 선택 요청 (동시)
        POWER_INCOME_COMPLETED,   // 파워 수입 개별 완료
        GAME_FINISHED,            // 게임 종료
        ACTION_LOGGED,            // 액션 로그 기록됨
        BIDDING_STARTED,          // 비딩 시작
        BID_UPDATED,              // 비딩 상태 변경 (입찰/패스)
        BID_WON,                  // 비딩 낙찰 (좌석 선택 대기)
        BID_SEAT_PICKED,          // 비딩 낙찰자 좌석 선택 완료
        BIDDING_COMPLETED         // 전체 비딩 완료
    }

    public static GameEvent playerJoined(UUID roomId, UUID playerId, String nickname) {
        return new GameEvent(
                roomId,
                EventType.PLAYER_JOINED,
                playerId,
                Map.of("nickname", nickname),
                Instant.now()
        );
    }

    public static GameEvent seatClaimed(UUID roomId, UUID playerId, int seatNo, String factionName) {
        return new GameEvent(
                roomId,
                EventType.SEAT_CLAIMED,
                playerId,
                Map.of("seatNo", seatNo, "factionName", factionName),
                Instant.now()
        );
    }

    public static GameEvent boosterSelected(UUID roomId, UUID playerId, int seatNo, String boosterCode, int nextPickSeatNo) {
        return new GameEvent(
                roomId,
                EventType.BOOSTER_SELECTED,
                playerId,
                Map.of("seatNo", seatNo, "boosterCode", boosterCode, "nextPickSeatNo", nextPickSeatNo),
                Instant.now()
        );
    }

    public static GameEvent gameStarted(UUID roomId, String gamePhase, Integer nextSetupSeatNo) {
        return new GameEvent(
                roomId,
                EventType.GAME_STARTED,
                null,
                Map.of("gamePhase", gamePhase, "nextSetupSeatNo", nextSetupSeatNo != null ? nextSetupSeatNo : 0),
                Instant.now()
        );
    }

    public static GameEvent minePlaced(UUID roomId, UUID playerId, int seatNo, int hexQ, int hexR, Integer nextSeatNo, String gamePhase) {
        return new GameEvent(
                roomId,
                EventType.MINE_PLACED,
                playerId,
                Map.of(
                        "seatNo", seatNo,
                        "hexQ", hexQ,
                        "hexR", hexR,
                        "nextSeatNo", nextSeatNo != null ? nextSeatNo : 0,
                        "gamePhase", gamePhase
                ),
                Instant.now()
        );
    }

    public static GameEvent stateUpdated(UUID roomId) {
        return new GameEvent(
                roomId,
                EventType.STATE_UPDATED,
                null,
                Map.of(),
                Instant.now()
        );
    }

    /**
     * 범용 이벤트 생성 메서드 (eventType 문자열 지원)
     */
    public static GameEvent of(UUID roomId, String eventTypeStr, Map<String, Object> payload) {
        EventType eventType;
        try {
            eventType = EventType.valueOf(eventTypeStr);
        } catch (IllegalArgumentException e) {
            eventType = EventType.STATE_UPDATED;
        }
        return new GameEvent(roomId, eventType, null, payload, Instant.now());
    }
}
