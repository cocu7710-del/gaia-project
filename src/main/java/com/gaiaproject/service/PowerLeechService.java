package com.gaiaproject.service;

import com.gaiaproject.domain.entity.game.Game;
import com.gaiaproject.domain.entity.game.GameSeat;
import com.gaiaproject.domain.entity.building.GameBuilding;
import com.gaiaproject.domain.entity.leech.GameLeechOffer;
import com.gaiaproject.domain.entity.player.GamePlayerState;
import com.gaiaproject.domain.enumtype.building.BuildingType;
import com.gaiaproject.domain.enumtype.player.FactionType;
import com.gaiaproject.domain.enumtype.action.VpCategory;
import com.gaiaproject.repository.game.GameRepository;
import com.gaiaproject.repository.game.GameSeatRepository;
import com.gaiaproject.repository.leech.GameLeechOfferRepository;
import com.gaiaproject.repository.player.GamePlayerStateRepository;
import com.gaiaproject.repository.tech.GamePlayerTechTileRepository;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class PowerLeechService {

    private final GameLeechOfferRepository leechOfferRepository;
    private final GamePlayerStateRepository playerStateRepository;
    private final GameSeatRepository seatRepository;
    private final GameRepository gameRepository;
    private final GameWebSocketService webSocketService;
    private final ActionService actionService;
    private final GamePlayerTechTileRepository playerTechTileRepository;
    private final com.gaiaproject.repository.map.GameHexRepository hexRepository;
    private final com.gaiaproject.repository.building.GameBuildingRepository buildingRepository;
    private final VpLogService vpLogService;

    /**
     * 건물 배치/업그레이드 후 파워 리치 배치 처리.
     * - 자동 결정 케이스는 즉시 처리
     * - 수동 결정 필요 케이스는 PENDING offer 생성
     * - PENDING이 있으면 LEECH_OFFERED 브로드캐스트, 없으면 턴 진행
     *
     * @param followUpType  이 배치 리치 해소 후 발생할 후속 액션 타입 (null 가능)
     * @param followUpData  후속 액션 JSON 데이터 (null 가능)
     */
    public void createBatchAndProcess(Game game, UUID triggerPlayerId,
                                       int hexQ, int hexR, BuildingType newType,
                                       List<GameBuilding> allBuildings,
                                       String followUpType, String followUpData) {
        // 게임 종료 후에는 리치 생성하지 않음
        if ("FINISHED".equals(game.getGamePhase()) || "FINISHED".equals(game.getStatus())) {
            return;
        }
        if (buildingPowerValue(newType) == 0) {
            // 파워 값 0인 건물 (가이아포머 등) → 리치 없음 → 바로 턴 진행
            if (followUpType != null) {
                broadcastFollowUp(game.getId(), triggerPlayerId, followUpType, followUpData);
            } else {
                actionService.advanceTurnAndBroadcast(game);
            }
            return;
        }

        UUID gameId = game.getId();
        String batchKey = UUID.randomUUID().toString();

        // 트리거 플레이어의 좌석 번호 조회
        int triggerSeatNo = seatRepository.findByGameIdAndPlayerId(gameId, triggerPlayerId)
                .map(GameSeat::getSeatNo)
                .orElse(1);

        // 2거리 이내 상대 플레이어별 최대 파워 값 계산
        Map<UUID, Integer> maxPowerByPlayer = new HashMap<>();
        for (GameBuilding b : allBuildings) {
            if (b.getPlayerId().equals(triggerPlayerId)) continue;
            int dist = hexDistance(hexQ, hexR, b.getHexQ(), b.getHexR());
            if (dist > 2) continue;
            int pv = buildingPowerValue(b.getBuildingType(), gameId, b.getPlayerId()) + (b.isHasRing() ? 2 : 0);
            // 매안 PI: 본인 행성(TITANIUM)에 있는 건물 파워값 +1
            var bOwnerState = playerStateRepository.findByGameIdAndPlayerId(gameId, b.getPlayerId()).orElse(null);
            if (bOwnerState != null && bOwnerState.getFactionType() == com.gaiaproject.domain.enumtype.player.FactionType.BESCODS
                    && bOwnerState.getStockPlanetaryInstitute() == 0) {
                var hex = hexRepository.findByGameIdAndHexQAndHexR(gameId, b.getHexQ(), b.getHexR()).orElse(null);
                if (hex != null && hex.getPlanetType() == com.gaiaproject.domain.enumtype.player.PlanetType.TITANIUM) pv += 1;
            }
            maxPowerByPlayer.merge(b.getPlayerId(), pv, Math::max);
        }

        if (maxPowerByPlayer.isEmpty()) {
            if (followUpType != null) {
                broadcastFollowUp(gameId, triggerPlayerId, followUpType, followUpData);
            } else {
                actionService.advanceTurnAndBroadcast(game);
            }
            return;
        }

        List<GameSeat> seats = seatRepository.findByGameIdOrderBySeatNo(gameId);
        int maxSeatNo = seats.size();
        List<GamePlayerState> allStates = playerStateRepository.findByGameId(gameId);

        List<GameLeechOffer> pendingOffers = new ArrayList<>();
        int seqNo = 0;
        boolean isFirstOffer = true;

        // 트리거 플레이어 다음 좌석부터 순서대로 처리
        for (int i = 1; i <= maxSeatNo; i++) {
            int checkSeatNo = ((triggerSeatNo - 1 + i) % maxSeatNo) + 1;

            GameSeat seat = seats.stream()
                    .filter(s -> s.getSeatNo() == checkSeatNo)
                    .findFirst().orElse(null);
            if (seat == null || seat.getPlayerId() == null) continue;

            Integer power = maxPowerByPlayer.get(seat.getPlayerId());
            if (power == null || power == 0) continue;

            GamePlayerState ps = allStates.stream()
                    .filter(s -> s.getPlayerId().equals(seat.getPlayerId()))
                    .findFirst().orElse(null);
            if (ps == null) continue;

            FactionType faction = resolveFaction(gameId, ps);
            boolean isTaklonsPI = faction == FactionType.TAKLONS && ps.getStockPlanetaryInstitute() == 0;
            boolean canDeclineOne = faction == FactionType.ITARS || isTaklonsPI;

            // 실제 순환 가능한 파워: bowl1은 2칸(1→2→3), bowl2는 1칸(2→3) 이동 가능
            // 타클론 브레인스톤: bowl1이면 2, bowl2이면 1 추가
            int chargeablePower = ps.getPowerBowl1() * 2 + ps.getPowerBowl2();
            if (faction == FactionType.TAKLONS && ps.getBrainstoneBowl() != null) {
                if (ps.getBrainstoneBowl() == 1) chargeablePower += 2;
                else if (ps.getBrainstoneBowl() == 2) chargeablePower += 1;
            }
            int effectivePower = Math.min(power, chargeablePower);

            // 순환 가능한 파워가 0이면 스킵
            if (effectivePower <= 0) {
                log.info("[LEECH] 순환 가능 파워 없음 스킵: player={}, bowl1={}, bowl2={}", seat.getPlayerId(), ps.getPowerBowl1(), ps.getPowerBowl2());
                continue;
            }

            // 1파워이면 자동 수령 (아이타/타클론PI 제외)
            if (effectivePower == 1 && !canDeclineOne) {
                applyPowerToPlayer(ps, 1, 0, isTaklonsPI, null);
                playerStateRepository.save(ps);
                saveOffer(gameId, batchKey, triggerPlayerId, seat.getPlayerId(), checkSeatNo,
                        1, 0, "AUTO_ACCEPTED", seqNo++, false,
                        isFirstOffer ? followUpType : null,
                        isFirstOffer ? followUpData : null);
                isFirstOffer = false;
                log.info("[LEECH] 순환 1파워 자동 수령: player={}", seat.getPlayerId());
                continue;
            }

            int vpCost = Math.max(0, effectivePower - 1);

            // VP 부족 시: 현재 VP만큼 까고 (VP+1)파워를 받을지 선택 (effectivePower 기준)
            if (vpCost > 0 && ps.getVictoryPoints() < vpCost) {
                int affordableVpCost = Math.min(ps.getVictoryPoints(), effectivePower - 1);
                int affordablePower = Math.min(affordableVpCost + 1, effectivePower);

                if (affordablePower <= 1 && !canDeclineOne) {
                    // 0VP = 1파워(무료)만 가능 + 아이타/타클론PI 아닌 경우 → 자동 수령
                    applyPowerToPlayer(ps, 1, 0, isTaklonsPI, null);
                    playerStateRepository.save(ps);
                    saveOffer(gameId, batchKey, triggerPlayerId, seat.getPlayerId(), checkSeatNo,
                            1, 0, "AUTO_ACCEPTED", seqNo++, false,
                            isFirstOffer ? followUpType : null,
                            isFirstOffer ? followUpData : null);
                    isFirstOffer = false;
                    log.info("[LEECH] VP 부족 자동 1파워: player={}, vp={}", seat.getPlayerId(), ps.getVictoryPoints());
                    continue;
                }

                // 2파워 이상 받을 수 있거나, 아이타/타클론PI → 수동 결정
                GameLeechOffer offer = saveOffer(gameId, batchKey, triggerPlayerId, seat.getPlayerId(), checkSeatNo,
                        affordablePower, affordableVpCost, "PENDING", seqNo++, isTaklonsPI,
                        isFirstOffer ? followUpType : null,
                        isFirstOffer ? followUpData : null);
                isFirstOffer = false;
                pendingOffers.add(offer);
                log.info("[LEECH] VP 부족 수동 결정: player={}, vp={}, affordablePower={}, affordableVpCost={}",
                        seat.getPlayerId(), ps.getVictoryPoints(), affordablePower, affordableVpCost);
                continue;
            }

            // 규칙 2-2: 1파워(실제 순환 가능 기준)는 특수 종족 제외 자동 수령
            if (effectivePower == 1 && !canDeclineOne) {
                applyPowerToPlayer(ps, 1, 0, isTaklonsPI, null);
                playerStateRepository.save(ps);
                saveOffer(gameId, batchKey, triggerPlayerId, seat.getPlayerId(), checkSeatNo,
                        1, 0, "AUTO_ACCEPTED", seqNo++, false,
                        isFirstOffer ? followUpType : null,
                        isFirstOffer ? followUpData : null);
                isFirstOffer = false;
                log.info("[LEECH] 1파워 자동 수령: player={}", seat.getPlayerId());
                continue;
            }

            // 수동 결정 필요 (effectivePower 기준)
            GameLeechOffer offer = saveOffer(gameId, batchKey, triggerPlayerId, seat.getPlayerId(), checkSeatNo,
                    effectivePower, vpCost, "PENDING", seqNo++, isTaklonsPI,
                    isFirstOffer ? followUpType : null,
                    isFirstOffer ? followUpData : null);
            isFirstOffer = false;
            pendingOffers.add(offer);
            log.info("[LEECH] 수동 결정 대기: player={}, effectivePower={}, vpCost={}, chargeable={}", seat.getPlayerId(), effectivePower, vpCost, chargeablePower);
        }

        if (pendingOffers.isEmpty()) {
            // 모든 자동 처리 완료 → 턴 또는 후속 액션 진행
            String resolvedFollowUpType = leechOfferRepository
                    .findByGameIdAndBatchKeyOrderBySequenceNo(gameId, batchKey)
                    .stream().findFirst()
                    .map(GameLeechOffer::getFollowUpType).orElse(null);

            if (resolvedFollowUpType != null) {
                String resolvedFollowUpData = leechOfferRepository
                        .findByGameIdAndBatchKeyOrderBySequenceNo(gameId, batchKey)
                        .stream().findFirst()
                        .map(GameLeechOffer::getFollowUpData).orElse(null);
                broadcastFollowUp(gameId, triggerPlayerId, resolvedFollowUpType, resolvedFollowUpData);
            } else {
                actionService.advanceTurnAndBroadcast(game);
            }
        } else {
            // 건설자 타이머 정지, 결정자 타이머 시작
            actionService.stopTurnTimer(gameId, triggerPlayerId);
            for (GameLeechOffer po : pendingOffers) {
                actionService.startTurnTimer(gameId, po.getReceivePlayerId());
            }
            // 모든 PENDING offer를 동시에 브로드캐스트 (각 플레이어가 독립적으로 결정)
            broadcastLeechOfferedAll(gameId, batchKey, pendingOffers);
        }
    }

    /**
     * 플레이어의 파워 리치 결정 처리
     */
    public void decidePowerLeech(UUID gameId, UUID leechId, UUID decidingPlayerId,
                                  boolean accept, String taklonsChoice) {
        GameLeechOffer offer = leechOfferRepository.findById(leechId)
                .orElseThrow(() -> new IllegalArgumentException("리치 오퍼를 찾을 수 없습니다"));

        if (!offer.getReceivePlayerId().equals(decidingPlayerId)) {
            throw new IllegalStateException("이 리치를 결정할 권한이 없습니다");
        }
        if (!offer.isPending()) {
            throw new IllegalStateException("이미 결정된 리치입니다");
        }

        // 결정자 타이머 정지
        actionService.stopTurnTimer(gameId, decidingPlayerId);

        if (accept) {
            GamePlayerState ps = playerStateRepository
                    .findByGameIdAndPlayerId(gameId, offer.getReceivePlayerId())
                    .orElseThrow(() -> new IllegalStateException("플레이어 상태를 찾을 수 없습니다"));

            applyPowerToPlayer(ps, offer.getPowerAmount(), offer.getVpCost(), offer.isTaklons(), taklonsChoice);
            playerStateRepository.save(ps);
            offer.accept(taklonsChoice);
            log.info("[LEECH] 수락: leechId={}, player={}, power={}, vpCost={}, taklonsChoice={}",
                    leechId, decidingPlayerId, offer.getPowerAmount(), offer.getVpCost(), taklonsChoice);
        } else {
            offer.decline();
            log.info("[LEECH] 거절: leechId={}, player={}", leechId, decidingPlayerId);
        }
        leechOfferRepository.save(offer);

        // 남은 PENDING offer 확인
        boolean anyPending = leechOfferRepository
                .findFirstByGameIdAndBatchKeyAndStatusOrderBySequenceNo(gameId, offer.getBatchKey(), "PENDING")
                .isPresent();

        // 개별 결정 브로드캐스트 (allResolved 포함)
        broadcastLeechDecidedWithStatus(gameId, offer, !anyPending);

        if (!anyPending) {
            // 모든 결정 완료 → 후속 액션 또는 턴 진행
            String followUpType = leechOfferRepository
                    .findByGameIdAndBatchKeyOrderBySequenceNo(gameId, offer.getBatchKey())
                    .stream().findFirst()
                    .map(GameLeechOffer::getFollowUpType).orElse(null);

            Game game = gameRepository.findById(gameId)
                    .orElseThrow(() -> new IllegalStateException("게임을 찾을 수 없습니다"));

            if (followUpType != null) {
                String followUpData = leechOfferRepository
                        .findByGameIdAndBatchKeyOrderBySequenceNo(gameId, offer.getBatchKey())
                        .stream().findFirst()
                        .map(GameLeechOffer::getFollowUpData).orElse(null);
                UUID triggerPlayerId = resolveTriggerPlayerId(gameId,
                        game.getCurrentTurnSeatNo() != null ? game.getCurrentTurnSeatNo() : 1);
                if (triggerPlayerId == null) triggerPlayerId = offer.getTriggerPlayerId();

                // 건설자 타이머 재시작 (후속 액션 처리용)
                actionService.startTurnTimer(gameId, triggerPlayerId);
                broadcastFollowUp(gameId, triggerPlayerId, followUpType, followUpData);
            } else {
                // advanceTurnAndBroadcast 내부에서 현재 턴 타이머 정지 + 다음 턴 타이머 시작
                // 건설자 타이머는 이미 정지 상태이므로 추가 시간 누적 없음
                actionService.advanceTurnAndBroadcast(game);
            }
        }
    }

    /**
     * 현재 게임의 대기 중인 리치 오퍼 조회 (페이지 리프레시 복구용)
     */
    public List<GameLeechOffer> getPendingOffers(UUID gameId) {
        return leechOfferRepository.findByGameIdAndStatus(gameId, "PENDING");
    }

    // ========== private helpers ==========

    private void applyPowerToPlayer(GamePlayerState ps, int power, int vpCost,
                                     boolean isTaklons, String taklonsChoice) {
        if (isTaklons && "TOKEN_FIRST".equals(taklonsChoice)) {
            ps.addPowerToken(1);
            ps.chargePowerWithFactionRules(power);
        } else {
            ps.chargePowerWithFactionRules(power);
            if (isTaklons) ps.addPowerToken(1);
        }
        if (vpCost > 0) ps.addVP(-vpCost);
        if (vpCost > 0) vpLogService.logVp(ps.getGameId(), ps.getPlayerId(), VpCategory.LEECH_COST, -vpCost, null, "파워 리치 비용");
    }

    private GameLeechOffer saveOffer(UUID gameId, String batchKey, UUID triggerPlayerId,
                                      UUID receivePlayerId, int receiveSeatNo,
                                      int powerAmount, int vpCost, String status,
                                      int seqNo, boolean isTaklons,
                                      String followUpType, String followUpData) {
        GameLeechOffer offer = GameLeechOffer.builder()
                .gameId(gameId).batchKey(batchKey)
                .triggerPlayerId(triggerPlayerId).receivePlayerId(receivePlayerId)
                .receiveSeatNo(receiveSeatNo).powerAmount(powerAmount).vpCost(vpCost)
                .status(status).taklons(isTaklons).sequenceNo(seqNo)
                .followUpType(followUpType).followUpData(followUpData)
                .build();
        return leechOfferRepository.save(offer);
    }

    /** 모든 PENDING offer를 동시 브로드캐스트 (각 플레이어가 독립적으로 결정) */
    private void broadcastLeechOfferedAll(UUID gameId, String batchKey,
                                           List<GameLeechOffer> pendingOffers) {
        List<Map<String, Object>> offerList = pendingOffers.stream()
                .map(o -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", o.getId().toString());
                    m.put("receivePlayerId", o.getReceivePlayerId().toString());
                    m.put("receiveSeatNo", o.getReceiveSeatNo());
                    m.put("powerAmount", o.getPowerAmount());
                    m.put("vpCost", o.getVpCost());
                    m.put("isTaklons", o.isTaklons());
                    return m;
                }).toList();

        // 모든 결정자 ID 목록 전달
        List<String> deciderIds = pendingOffers.stream()
                .map(o -> o.getReceivePlayerId().toString())
                .toList();

        webSocketService.broadcastLeechOfferedAll(gameId, batchKey, offerList, deciderIds);
    }

    private void broadcastLeechDecidedWithStatus(UUID gameId, GameLeechOffer decided, boolean allResolved) {
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("decidedLeechId", decided.getId().toString());
        payload.put("accepted", "ACCEPTED".equals(decided.getStatus()));
        payload.put("allResolved", allResolved);
        webSocketService.broadcast(com.gaiaproject.dto.websocket.GameEvent.of(gameId, "LEECH_DECIDED", payload));
    }

    private void broadcastFollowUp(UUID gameId, UUID triggerPlayerId, String followUpType, String followUpData) {
        // 검은행성 리치: 내부적으로 리치 배치 발동
        if ("LOST_PLANET_LEECH".equals(followUpType) && followUpData != null) {
            try {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var node = mapper.readTree(followUpData);
                int lpHexQ = node.get("hexQ").asInt();
                int lpHexR = node.get("hexR").asInt();
                Game game = gameRepository.findById(gameId).orElseThrow();
                List<GameBuilding> allBlds = buildingRepository.findByGameId(gameId);
                createBatchAndProcess(game, triggerPlayerId, lpHexQ, lpHexR,
                        com.gaiaproject.domain.enumtype.building.BuildingType.LOST_PLANET_MINE, allBlds, null, null);
                log.info("[LEECH] 검은행성 리치 자동 발동: game={}, hex=({},{})", gameId, lpHexQ, lpHexR);
                return;
            } catch (Exception e) {
                log.error("[LEECH] 검은행성 리치 처리 실패, 턴 진행으로 대체", e);
                Game game = gameRepository.findById(gameId).orElse(null);
                if (game != null) actionService.advanceTurnAndBroadcast(game);
                return;
            }
        }
        webSocketService.broadcastDeferredActionRequired(gameId, triggerPlayerId, followUpType, followUpData);
    }

    private FactionType resolveFaction(UUID gameId, GamePlayerState ps) {
        if (ps.getFactionType() != null) return ps.getFactionType();
        return seatRepository.findByGameIdAndSeatNo(gameId, ps.getSeatNo())
                .map(GameSeat::getFactionType).orElse(null);
    }

    private UUID resolveTriggerPlayerId(UUID gameId, int seatNo) {
        return seatRepository.findByGameIdAndSeatNo(gameId, seatNo)
                .map(GameSeat::getPlayerId).orElse(null);
    }

    private int buildingPowerValue(BuildingType type) {
        return switch (type) {
            case MINE, LOST_PLANET_MINE -> 1;
            case TRADING_STATION, RESEARCH_LAB -> 2;
            case PLANETARY_INSTITUTE, ACADEMY -> 3;
            default -> 0;
        };
    }

    /** BASIC_TILE_9 보유 시 큰 건물 파워 가치 +1 반영 */
    private int buildingPowerValue(BuildingType type, UUID gameId, UUID playerId) {
        int base = buildingPowerValue(type);
        if ((type == BuildingType.PLANETARY_INSTITUTE || type == BuildingType.ACADEMY)
                && hasActiveTechTile(gameId, playerId, "BASIC_TILE_9")) {
            base += 1;
        }
        return base;
    }

    private boolean hasActiveTechTile(UUID gameId, UUID playerId, String tileCode) {
        return playerTechTileRepository
                .findByGameIdAndPlayerIdAndIsCovered(gameId, playerId, false)
                .stream()
                .anyMatch(t -> tileCode.equals(t.getTechTileCode()));
    }

    private int hexDistance(int q1, int r1, int q2, int r2) {
        int dq = q2 - q1, dr = r2 - r1;
        return (Math.abs(dq) + Math.abs(dr) + Math.abs(dq + dr)) / 2;
    }
}
