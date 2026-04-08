package com.gaiaproject.config;

import com.gaiaproject.service.GameWebSocketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
@Slf4j
public class WebSocketEventListener {

    private final GameWebSocketService webSocketService;

    // sessionId → roomId 매핑
    private final Map<String, String> sessionRoomMap = new ConcurrentHashMap<>();
    // roomId → sessionId 집합
    private final Map<String, Set<String>> roomSessionsMap = new ConcurrentHashMap<>();

    @EventListener
    public void handleSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String dest = accessor.getDestination();
        String sessionId = accessor.getSessionId();
        if (dest == null || sessionId == null || !dest.startsWith("/topic/room/")) return;

        String roomId = dest.replace("/topic/room/", "");
        sessionRoomMap.put(sessionId, roomId);
        roomSessionsMap.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(sessionId);

        int count = roomSessionsMap.get(roomId).size();
        log.info("[WS] Subscribe: session={}, room={}, viewers={}", sessionId, roomId, count);
        broadcastViewerCount(roomId, count);
    }

    @EventListener
    public void handleUnsubscribe(SessionUnsubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();
        removeSession(sessionId);
    }

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();
        removeSession(sessionId);
    }

    private void removeSession(String sessionId) {
        if (sessionId == null) return;
        String roomId = sessionRoomMap.remove(sessionId);
        if (roomId == null) return;
        Set<String> sessions = roomSessionsMap.get(roomId);
        if (sessions != null) {
            sessions.remove(sessionId);
            int count = sessions.size();
            if (count == 0) roomSessionsMap.remove(roomId);
            log.info("[WS] Disconnect: session={}, room={}, viewers={}", sessionId, roomId, count);
            broadcastViewerCount(roomId, count);
        }
    }

    private void broadcastViewerCount(String roomId, int count) {
        try {
            UUID gameId = UUID.fromString(roomId);
            webSocketService.broadcast(
                com.gaiaproject.dto.websocket.GameEvent.of(gameId, "VIEWER_COUNT",
                    Map.of("count", count))
            );
        } catch (Exception ignored) {}
    }

    /** 특정 방의 접속자 수 조회 */
    public int getViewerCount(String roomId) {
        Set<String> sessions = roomSessionsMap.get(roomId);
        return sessions != null ? sessions.size() : 0;
    }
}
