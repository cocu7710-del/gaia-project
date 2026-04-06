package com.gaiaproject.service;

import com.gaiaproject.domain.entity.game.GameVpLog;
import com.gaiaproject.domain.enumtype.action.VpCategory;
import com.gaiaproject.repository.game.GameVpLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class VpLogService {

    private final GameVpLogRepository vpLogRepository;

    /**
     * VP 변동 기록
     */
    public void logVp(UUID gameId, UUID playerId, VpCategory category, int amount, Integer roundNumber, String description) {
        if (amount == 0) return;
        vpLogRepository.save(GameVpLog.builder()
                .gameId(gameId)
                .playerId(playerId)
                .category(category)
                .amount(amount)
                .roundNumber(roundNumber)
                .description(description)
                .build());
        log.debug("[VP_LOG] game={}, player={}, cat={}, amount={}, desc={}", gameId, playerId, category, amount, description);
    }

    /**
     * 게임 결과 조회: 플레이어별 카테고리별 VP 합산
     */
    public Map<UUID, Map<VpCategory, Integer>> getGameResult(UUID gameId) {
        Map<UUID, Map<VpCategory, Integer>> result = new LinkedHashMap<>();
        List<Object[]> rows = vpLogRepository.sumByGameIdGroupByPlayerAndCategory(gameId);
        for (Object[] row : rows) {
            UUID playerId = (UUID) row[0];
            VpCategory category = (VpCategory) row[1];
            int sum = ((Number) row[2]).intValue();
            result.computeIfAbsent(playerId, k -> new EnumMap<>(VpCategory.class)).put(category, sum);
        }
        return result;
    }
}
