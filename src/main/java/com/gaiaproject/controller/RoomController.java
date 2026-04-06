package com.gaiaproject.controller;

import com.gaiaproject.dto.request.CreateRoomRequest;
import com.gaiaproject.dto.request.EnterGameRequest;
import com.gaiaproject.dto.request.SelectBoosterRequest;
import com.gaiaproject.dto.response.ClaimSeatResponse;
import com.gaiaproject.dto.response.CreateRoomResponse;
import com.gaiaproject.dto.response.EnterGameResponse;
import com.gaiaproject.dto.response.GamePublicStateResponse;
import com.gaiaproject.dto.response.ParticipantListResponse;
import com.gaiaproject.dto.response.PlayerStateResponse;
import com.gaiaproject.dto.response.SelectBoosterResponse;
import com.gaiaproject.dto.response.StartGameResponse;
import com.gaiaproject.dto.response.VerifyParticipantResponse;
import com.gaiaproject.service.GameService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Tag(name = "Room", description = "방 관련 API")
@RestController
@RequiredArgsConstructor
public class RoomController {

    private final GameService gameService;

    @Operation(summary = "방 공개 상태 조회")
    @GetMapping("/api/rooms/{roomId}/public-state")
    public ResponseEntity<GamePublicStateResponse> getPublicState(@PathVariable UUID roomId) {
        GamePublicStateResponse response = gameService.getPublicState(roomId);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "방 참가자 목록 조회")
    @GetMapping("/api/rooms/{roomId}/participants")
    public ResponseEntity<ParticipantListResponse> getParticipants(@PathVariable UUID roomId) {
        ParticipantListResponse response = gameService.getParticipants(roomId);
        return ResponseEntity.ok(response);
    }

    /** [임시 디버그] 닉네임으로 rejoinToken 조회 */
    @GetMapping("/api/rooms/{roomId}/participants/debug")
    public ResponseEntity<?> debugParticipant(@PathVariable UUID roomId, @RequestParam String nickname) {
        return ResponseEntity.ok(gameService.debugGetToken(roomId, nickname));
    }

    @Operation(summary = "참가자 검증 (재입장 시)")
    @GetMapping("/api/rooms/{roomId}/participants/{playerId}/verify")
    public ResponseEntity<VerifyParticipantResponse> verifyParticipant(
            @PathVariable UUID roomId,
            @PathVariable UUID playerId
    ) {
        VerifyParticipantResponse response = gameService.verifyParticipant(roomId, playerId);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "방 코드로 roomId 조회")
    @GetMapping("/api/rooms/code/{roomCode}")
    public ResponseEntity<Map<String, Object>> getRoomByCode(@PathVariable String roomCode) {
        UUID roomId = gameService.findRoomIdByCode(roomCode);
        if (roomId == null) {
            return ResponseEntity.ok(Map.of("found", false, "message", "방을 찾을 수 없습니다: " + roomCode));
        }
        return ResponseEntity.ok(Map.of("found", true, "roomId", roomId.toString()));
    }

    @Operation(summary = "방 생성")
    @PostMapping("/api/rooms")
    public ResponseEntity<CreateRoomResponse> createRoom(@Valid @RequestBody CreateRoomRequest request) {
        CreateRoomResponse response = gameService.createRoom(request);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "방 입장")
    @PostMapping("/api/rooms/{roomId}/enter")
    public ResponseEntity<EnterGameResponse> enterRoom(
            @PathVariable UUID roomId,
            @Valid @RequestBody EnterGameRequest request
    ) {
        EnterGameResponse response = gameService.enterRoom(roomId, request);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "비딩 수동 시작 (맵 회전 완료 후)")
    @PostMapping("/api/rooms/{roomId}/start-bidding")
    public ResponseEntity<?> startBidding(@PathVariable UUID roomId) {
        try {
            gameService.startBidding(roomId);
            return ResponseEntity.ok(java.util.Map.of("success", true));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("success", false, "message", e.getMessage()));
        }
    }

    @Operation(summary = "좌석 선택(턴 선택)")
    @PostMapping("/api/rooms/{roomId}/seats/{seatNo}/claim")
    public ResponseEntity<ClaimSeatResponse> claimSeat(
            @PathVariable UUID roomId,
            @PathVariable int seatNo,
            @RequestParam UUID playerId
    ) {
        ClaimSeatResponse response = gameService.claimSeat(roomId, seatNo, playerId);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "라운드 부스터 선택 (순서: 4→3→2→1)")
    @PostMapping("/api/rooms/{roomId}/boosters/select")
    public ResponseEntity<SelectBoosterResponse> selectBooster(
            @PathVariable UUID roomId,
            @Valid @RequestBody SelectBoosterRequest request
    ) {
        SelectBoosterResponse response = gameService.selectBooster(roomId, request);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "게임 시작 (부스터 선택 완료 후)")
    @PostMapping("/api/rooms/{roomId}/start")
    public ResponseEntity<StartGameResponse> startGame(@PathVariable UUID roomId) {
        StartGameResponse response = gameService.startGame(roomId);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "새 라운드 시작 (라운드 2~6)")
    @PostMapping("/api/rooms/{roomId}/next-round")
    public ResponseEntity<StartGameResponse> startNewRound(@PathVariable UUID roomId) {
        StartGameResponse response = gameService.startNewRound(roomId);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "플레이어 상태 조회 (자원, 건물 등)")
    @GetMapping("/api/rooms/{roomId}/players")
    public ResponseEntity<List<PlayerStateResponse>> getPlayerStates(@PathVariable UUID roomId) {
        List<PlayerStateResponse> response = gameService.getPlayerStates(roomId);
        return ResponseEntity.ok(response);
    }
}
