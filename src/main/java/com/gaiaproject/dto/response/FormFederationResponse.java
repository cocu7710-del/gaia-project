package com.gaiaproject.dto.response;

import java.util.UUID;

public record FormFederationResponse(
        UUID gameId,
        boolean success,
        String message,
        String federationTileCode,
        Integer nextTurnSeatNo
) {
    public static FormFederationResponse success(UUID gameId, String tileCode, Integer nextTurnSeatNo) {
        return new FormFederationResponse(gameId, true, null, tileCode, nextTurnSeatNo);
    }

    public static FormFederationResponse fail(UUID gameId, String message) {
        return new FormFederationResponse(gameId, false, message, null, null);
    }
}
