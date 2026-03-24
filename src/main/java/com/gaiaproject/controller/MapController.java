package com.gaiaproject.controller;

import com.gaiaproject.domain.entity.map.GameHex;
import com.gaiaproject.repository.map.GameHexRepository;
import com.gaiaproject.service.GameWebSocketService;
import com.gaiaproject.service.MapService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "Map", description = "맵 관련 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/rooms/{roomId}/map")
public class MapController {

    private final GameHexRepository gameHexRepository;
    private final MapService mapService;
    private final GameWebSocketService webSocketService;

    @Operation(summary = "게임 맵 전체 헥스 조회")
    @GetMapping("/hexes")
    public ResponseEntity<List<GameHex>> getAllHexes(@PathVariable UUID roomId) {
        List<GameHex> hexes = gameHexRepository.findByGameId(roomId);
        return ResponseEntity.ok(hexes);
    }

    @Operation(summary = "섹터 60도 회전 (캐릭터 선택 전에만 가능)")
    @PostMapping("/sectors/{positionNo}/rotate")
    public ResponseEntity<?> rotateSector(@PathVariable UUID roomId, @PathVariable int positionNo) {
        try {
            mapService.rotateSector(roomId, positionNo);
            List<GameHex> hexes = gameHexRepository.findByGameId(roomId);
            // 다른 플레이어에게 맵 회전 알림
            webSocketService.broadcast(com.gaiaproject.dto.websocket.GameEvent.of(
                    roomId, "MAP_ROTATED", Map.of("positionNo", positionNo)));
            return ResponseEntity.ok(hexes);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
}
