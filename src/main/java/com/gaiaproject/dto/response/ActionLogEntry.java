package com.gaiaproject.dto.response;

import java.time.LocalDateTime;
import java.util.Map;

public record ActionLogEntry(
        String actionId,
        String playerId,
        int seatNo,
        String factionCode,
        int roundNumber,
        int turnSequence,
        String actionType,
        Map<String, Object> actionData,
        LocalDateTime createdAt
) {}
