package com.gaiaproject.service;

import com.gaiaproject.domain.entity.player.GamePlayerFleetProbe;
import com.gaiaproject.domain.entity.player.GamePlayerState;
import com.gaiaproject.domain.enumtype.action.ActionType;
import com.gaiaproject.domain.enumtype.action.VpCategory;
import com.gaiaproject.dto.response.ConfirmActionResponse;
import com.gaiaproject.dto.response.FleetOccupancyResponse;
import com.gaiaproject.dto.response.FleetProbeStatusResponse;
import com.gaiaproject.dto.response.PlaceFleetProbeResponse;
import com.gaiaproject.repository.player.GamePlayerFleetProbeRepository;
import com.gaiaproject.repository.player.GamePlayerStateRepository;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 함대 탐사선 관리 서비스
 * - FE에서 확정 후 DB에 직접 저장
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class FleetService {

    private final GamePlayerFleetProbeRepository fleetProbeRepository;
    private final GamePlayerStateRepository playerStateRepository;
    private final ActionService actionService;
    private final VpLogService vpLogService;

    private static final Map<String, List<String>> FLEET_ACTIONS = Map.of(
            "TF_MARS", List.of("FLEET_TF_MARS_1", "FLEET_TF_MARS_2"),
            "ECLIPSE", List.of("FLEET_ECLIPSE_1", "FLEET_ECLIPSE_2"),
            "TWILIGHT", List.of("FLEET_TWILIGHT_1", "FLEET_TWILIGHT_2"),
            "REBELLION", List.of("FLEET_REBELLION_1", "FLEET_REBELLION_2")
    );

    /**
     * 탐사선 배치 (FE에서 확정 후 호출)
     * - VP 5 이상 보유 필요, 입장 시 VP 5 차감
     */
    public PlaceFleetProbeResponse placeFleetProbe(UUID gameId, UUID playerId, String fleetName, int qicUsed) {
        // 중복 검증
        if (fleetProbeRepository.existsByGameIdAndPlayerIdAndFleetName(gameId, playerId, fleetName)) {
            return PlaceFleetProbeResponse.fail(gameId, playerId, "이미 해당 함대에 탐사선이 배치되어 있습니다");
        }

        // 플레이어당 최대 3개 함대 입장 제한
        int playerFleetCount = fleetProbeRepository.countByGameIdAndPlayerId(gameId, playerId);
        if (playerFleetCount >= 3) {
            return PlaceFleetProbeResponse.fail(gameId, playerId, "최대 3개 함대까지만 입장할 수 있습니다");
        }

        // VP 검증 및 차감
        GamePlayerState playerState = playerStateRepository.findByGameIdAndPlayerId(gameId, playerId)
                .orElseThrow(() -> new IllegalStateException("플레이어 상태를 찾을 수 없습니다"));

        // 타클론: 브레인스톤을 가이아 구역으로 보낼 수 있어야 함대 입장 가능
        boolean isTaklons = playerState.getFactionType() == com.gaiaproject.domain.enumtype.player.FactionType.TAKLONS;
        if (isTaklons) {
            Integer bsBowl = playerState.getBrainstoneBowl();
            // 브레인스톤이 가이아(0)에 있으면 다시 가이아로 보낼 수 없음 → 입장 불가
            if (bsBowl == null || bsBowl == 0) {
                return PlaceFleetProbeResponse.fail(gameId, playerId, "브레인스톤을 가이아 구역으로 보낼 수 없어 함대 입장이 불가능합니다");
            }
        }

        boolean isBalTaks = playerState.getFactionType() == com.gaiaproject.domain.enumtype.player.FactionType.BAL_TAKS;
        int biddingCost = isBalTaks ? 7 : 5;
        if (playerState.getVictoryPoints() < biddingCost) {
            return PlaceFleetProbeResponse.fail(gameId, playerId, "함대 입장에 VP " + biddingCost + "가 필요합니다 (현재: " + playerState.getVictoryPoints() + ")");
        }

        // 현재 함대 입장 인원 수 (0-based slot index for new entry)
        int slotIndex = fleetProbeRepository.countByGameIdAndFleetName(gameId, fleetName);

        playerState.spendVP(biddingCost);
        vpLogService.logVp(playerState.getGameId(), playerState.getPlayerId(), VpCategory.FLEET_ENTRY, -biddingCost, null, "함대 입장 -" + biddingCost + "VP");
        // 항법 QIC 소모
        if (qicUsed > 0) {
            playerState.spendQic(qicUsed);
        }
        // 네블라/아이타: 우주선 입장 시 파워 토큰 1개 영구 소각 (bowl1→2→3 순)
        var faction = playerState.getFactionType();
        if (faction == com.gaiaproject.domain.enumtype.player.FactionType.NEVLAS
                || faction == com.gaiaproject.domain.enumtype.player.FactionType.ITARS) {
            int total = playerState.getPowerBowl1() + playerState.getPowerBowl2() + playerState.getPowerBowl3();
            if (total <= 0) {
                return PlaceFleetProbeResponse.fail(gameId, playerId, "파워 토큰이 부족하여 함대에 입장할 수 없습니다");
            }
            if (playerState.getPowerBowl1() > 0) playerState.removePowerFromBowl1(1);
            else if (playerState.getPowerBowl2() > 0) playerState.removePowerFromBowl2(1);
            else playerState.removePowerFromBowl3(1);
        }
        // 타클론: 브레인스톤을 가이아 구역으로 이동
        if (isTaklons && playerState.getBrainstoneBowl() != null && playerState.getBrainstoneBowl() != 0) {
            playerState.setBrainstoneBowl(0); // 0 = 가이아 구역
        }

        // 2, 3번째 입장: 파워 2 순환 / 4번째 입장: 파워 3 순환
        if (slotIndex == 1 || slotIndex == 2) {
            playerState.chargePowerWithFactionRules(2);
        } else if (slotIndex == 3) {
            playerState.chargePowerWithFactionRules(3);
        }
        playerStateRepository.save(playerState);

        try {
            // 탐사선 배치
            GamePlayerFleetProbe probe = GamePlayerFleetProbe.builder()
                    .gameId(gameId)
                    .playerId(playerId)
                    .fleetName(fleetName)
                    .build();
            fleetProbeRepository.save(probe);

            // 활성화된 액션 목록 반환
            List<String> unlockedActions = getFleetActions(fleetName);

            log.info("탐사선 배치: gameId={}, playerId={}, fleet={}", gameId, playerId, fleetName);

            // 액션 기록 및 턴 넘김
            String actionData = String.format("{\"fleetName\":\"%s\"}", fleetName);
            ConfirmActionResponse actionResult = actionService.saveActionAndNextTurn(
                    gameId, playerId, ActionType.FLEET_ACTION, actionData);

            return PlaceFleetProbeResponse.success(gameId, playerId, fleetName, unlockedActions,
                    actionResult.actionId());

        } catch (Exception e) {
            log.error("탐사선 배치 실패: gameId={}, playerId={}, fleet={}", gameId, playerId, fleetName, e);
            return PlaceFleetProbeResponse.fail(gameId, playerId, "탐사선 배치 중 오류가 발생했습니다: " + e.getMessage());
        }
    }

    /**
     * 탐사선 상태 조회
     */
    public FleetProbeStatusResponse getFleetProbeStatus(UUID gameId, UUID playerId) {
        List<GamePlayerFleetProbe> probes = fleetProbeRepository.findByGameIdAndPlayerId(gameId, playerId);

        Map<String, Boolean> status = new HashMap<>();
        status.put("TF_MARS", hasProbe(probes, "TF_MARS"));
        status.put("ECLIPSE", hasProbe(probes, "ECLIPSE"));
        status.put("TWILIGHT", hasProbe(probes, "TWILIGHT"));
        status.put("REBELLION", hasProbe(probes, "REBELLION"));

        return new FleetProbeStatusResponse(gameId, playerId, status);
    }

    /**
     * 전체 함대 점유 현황 조회 (fleetName → 입장 순서대로 playerId 목록)
     */
    public FleetOccupancyResponse getAllFleetOccupancy(UUID gameId) {
        List<GamePlayerFleetProbe> allProbes = fleetProbeRepository.findByGameIdOrderByPlacedAtAsc(gameId);

        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String fleet : List.of("TF_MARS", "ECLIPSE", "REBELLION", "TWILIGHT")) {
            result.put(fleet, new ArrayList<>());
        }
        for (GamePlayerFleetProbe probe : allProbes) {
            result.computeIfAbsent(probe.getFleetName(), k -> new ArrayList<>())
                    .add(probe.getPlayerId().toString());
        }
        return new FleetOccupancyResponse(gameId, result);
    }

    /**
     * 함대 액션 목록 조회
     */
    private List<String> getFleetActions(String fleetName) {
        return FLEET_ACTIONS.getOrDefault(fleetName, Collections.emptyList());
    }

    /**
     * 탐사선 배치 여부 확인
     */
    private boolean hasProbe(List<GamePlayerFleetProbe> probes, String fleetName) {
        return probes.stream().anyMatch(p -> p.getFleetName().equals(fleetName));
    }
}
