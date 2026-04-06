package com.gaiaproject.controller;

import com.gaiaproject.service.BiddingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/rooms/{roomId}/bidding")
@RequiredArgsConstructor
public class BiddingController {

    private final BiddingService biddingService;

    /** 비딩 상태 조회 */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getBiddingState(@PathVariable UUID roomId) {
        return ResponseEntity.ok(biddingService.getBiddingState(roomId));
    }

    /** 비딩 입찰 */
    @PostMapping("/place")
    public ResponseEntity<Map<String, Object>> placeBid(
            @PathVariable UUID roomId,
            @RequestBody Map<String, Object> body) {
        UUID playerId = UUID.fromString((String) body.get("playerId"));
        int amount = (int) body.get("amount");
        return ResponseEntity.ok(biddingService.placeBid(roomId, playerId, amount));
    }

    /** 비딩 패스 */
    @PostMapping("/pass")
    public ResponseEntity<Map<String, Object>> passBid(
            @PathVariable UUID roomId,
            @RequestBody Map<String, Object> body) {
        UUID playerId = UUID.fromString((String) body.get("playerId"));
        return ResponseEntity.ok(biddingService.passBid(roomId, playerId));
    }

    /** 낙찰자 좌석 선택 */
    @PostMapping("/pick-seat")
    public ResponseEntity<Map<String, Object>> pickSeat(
            @PathVariable UUID roomId,
            @RequestBody Map<String, Object> body) {
        UUID playerId = UUID.fromString((String) body.get("playerId"));
        int seatNo = (int) body.get("seatNo");
        return ResponseEntity.ok(biddingService.pickSeat(roomId, playerId, seatNo));
    }
}
