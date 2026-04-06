package com.gaiaproject.dto.response;

import java.util.UUID;

/**
 * 게임 입장 응답 DTO.
 * - FE는 이후 seat 선택/액션 요청 시 playerId를 사용한다.
 */
public record EnterGameResponse(
        UUID gameId,
        UUID playerId,
        boolean success,
        boolean spectator,
        String rejoinToken,  // 재입장용 토큰 (첫 입장 시 발급, localStorage에 저장)
        String message
) {
    public static EnterGameResponse spectator(UUID roomId) {
        return new EnterGameResponse(roomId, null, true, true, null, null);
    }

    public static EnterGameResponse player(UUID roomId, UUID playerId, String rejoinToken) {
        return new EnterGameResponse(roomId, playerId, true, false, rejoinToken, null);
    }

    public static EnterGameResponse full(UUID roomId) {
        return new EnterGameResponse(roomId, null, false, false, null, "정원이 초과되었습니다. (최대 4명)");
    }

    public static EnterGameResponse duplicateNickname(UUID roomId, String nickname) {
        return new EnterGameResponse(roomId, null, false, false, null, "이미 사용 중인 닉네임입니다: " + nickname);
    }

    public static EnterGameResponse rejoin(UUID roomId, UUID playerId, String rejoinToken) {
        return new EnterGameResponse(roomId, playerId, true, false, rejoinToken, "재입장 성공");
    }

    public static EnterGameResponse invalidRejoinToken(UUID roomId) {
        return new EnterGameResponse(roomId, null, false, false, null, "재입장 토큰이 유효하지 않습니다.");
    }

    public static EnterGameResponse notRegistered(UUID roomId) {
        return new EnterGameResponse(roomId, null, false, false, null, "등록되지 않은 닉네임입니다.");
    }
}
