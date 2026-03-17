package com.gaiaproject.service;

import com.gaiaproject.domain.entity.building.GameBuilding;
import com.gaiaproject.domain.entity.map.GameHex;
import com.gaiaproject.domain.entity.federation.GameFederationBuilding;
import com.gaiaproject.domain.entity.federation.GameFederationGroup;
import com.gaiaproject.domain.entity.federation.GameFederationTokenHex;
import com.gaiaproject.domain.entity.game.Game;
import com.gaiaproject.domain.entity.player.GamePlayerFederationToken;
import com.gaiaproject.domain.entity.player.GamePlayerState;
import com.gaiaproject.domain.enumtype.building.BuildingType;
import com.gaiaproject.domain.enumtype.federation.FederationTileType;
import com.gaiaproject.domain.enumtype.player.FactionType;
import com.gaiaproject.dto.request.FormFederationRequest;
import com.gaiaproject.dto.response.ConfirmActionResponse;
import com.gaiaproject.dto.response.FormFederationResponse;
import com.gaiaproject.repository.building.GameBuildingRepository;
import com.gaiaproject.repository.federation.*;
import com.gaiaproject.repository.game.GameRepository;
import com.gaiaproject.repository.player.GamePlayerFederationTokenRepository;
import com.gaiaproject.repository.player.GamePlayerStateRepository;
import com.gaiaproject.repository.tech.GamePlayerTechTileRepository;
import com.gaiaproject.util.HexUtil;
import com.gaiaproject.domain.enumtype.action.ActionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class FederationFormService {

    private final GameRepository gameRepository;
    private final GameBuildingRepository buildingRepository;
    private final GamePlayerStateRepository playerStateRepository;
    private final GameFederationGroupRepository federationGroupRepository;
    private final GameFederationBuildingRepository federationBuildingRepository;
    private final GameFederationTokenHexRepository federationTokenHexRepository;
    private final GameFederationOfferRepository federationOfferRepository;
    private final GamePlayerFederationTokenRepository playerFederationTokenRepository;
    private final ActionService actionService;
    private final GamePlayerTechTileRepository playerTechTileRepository;
    private final RoundScoringService roundScoringService;
    private final com.gaiaproject.repository.map.GameHexRepository hexRepository;

    /**
     * 건물 선택 검증 (토큰 배치 전). 파워 합계 + 기존 연방 중복 체크 + 최소 토큰 수 반환.
     */
    public Map<String, Object> validateBuildingSelection(UUID gameId, FormFederationRequest request) {
        UUID playerId = request.playerId();
        GamePlayerState ps = playerStateRepository.findByGameIdAndPlayerId(gameId, playerId)
                .orElseThrow(() -> new IllegalStateException("플레이어 상태를 찾을 수 없습니다"));

        boolean isIvits = ps.getFactionType() == FactionType.IVITS;
        List<int[]> buildingHexes = request.buildingHexes() != null ? request.buildingHexes() : List.of();

        if (buildingHexes.isEmpty()) {
            return Map.of("success", false, "message", "건물을 선택해야 합니다");
        }

        // 건물 검증
        List<GameBuilding> myBuildings = buildingRepository.findByGameIdAndPlayerId(gameId, playerId);
        Map<String, GameBuilding> myBuildingMap = myBuildings.stream()
                .collect(Collectors.toMap(b -> b.getHexQ() + "," + b.getHexR(), b -> b, (a, c) -> a));

        int totalPower = 0;
        for (int[] hex : buildingHexes) {
            String key = hex[0] + "," + hex[1];
            GameBuilding b = myBuildingMap.get(key);
            if (b == null) return Map.of("success", false, "message", "내 건물이 아닙니다: (" + hex[0] + "," + hex[1] + ")");
            totalPower += buildingPowerValue(b.getBuildingType(), gameId, playerId, b.isHasRing());
        }

        // 기존 연방 중복 체크 (하이브 제외)
        if (!isIvits) {
            Set<String> usedFedHexes = getUsedFederationHexes(gameId, playerId);
            for (int[] hex : buildingHexes) {
                if (usedFedHexes.contains(hex[0] + "," + hex[1])) {
                    return Map.of("success", false, "message", "이미 연방에 사용된 건물이 포함되어 있습니다");
                }
            }
        }

        // 파워 체크 (제노스 PI: 6, 기본: 7)
        boolean isXenosPi = ps.getFactionType() == FactionType.XENOS && ps.getStockPlanetaryInstitute() == 0;
        int requiredPower = isXenosPi ? 6 : 7;
        if (isIvits) {
            List<GameFederationGroup> existingGroups = federationGroupRepository.findByGameIdAndPlayerId(gameId, playerId);
            if (!existingGroups.isEmpty()) {
                int existingPower = 0;
                for (GameFederationBuilding fb : federationBuildingRepository.findByFederationGroupId(existingGroups.get(0).getId())) {
                    GameBuilding b = buildingRepository.findFirstByGameIdAndHexQAndHexRAndIsLantidsMine(gameId, fb.getHexQ(), fb.getHexR(), false).orElse(null);
                    if (b != null) existingPower += buildingPowerValue(b.getBuildingType());
                }
                requiredPower = ((existingPower / 7) + 1) * 7 - existingPower;
            }
        }

        if (totalPower < requiredPower) {
            return Map.of("success", false, "message", "파워가 부족합니다 (현재: " + totalPower + ", 필요: " + requiredPower + ")");
        }

        // 최소 토큰 수 계산: BFS 실제 경로 (장애물 우회)
        Set<String> buildingSet = new HashSet<>();
        for (int[] h : buildingHexes) buildingSet.add(h[0] + "," + h[1]);
        int groups = countConnectedGroups(buildingSet);

        // 토큰 배치 가능 헥스 (EMPTY + 맵 존재 + 연방 인접 아님)
        Set<String> allowedHexes = buildAllowedHexes(gameId, playerId, isIvits);
        log.info("[FEDERATION_VALIDATE] allowedHexes count={}, buildings={}", allowedHexes.size(), buildingHexes.size());
        int minTokens = calcMinTokensToConnect(buildingHexes, gameId, allowedHexes, buildingSet);
        log.info("[FEDERATION_VALIDATE] minTokens={}", minTokens);

        return Map.of("success", true, "totalPower", totalPower, "minTokens", minTokens, "groups", groups);
    }

    /**
     * 선택한 건물들을 모두 연결하는 최소 토큰 수 계산 (Prim MST + BFS 실제 경로)
     * 장애물(행성, 기존 연방 헥스, 인접 금지) 우회하여 실제 빈 헥스 경로로 계산
     */
    private int calcMinTokensToConnect(List<int[]> buildingHexes) {
        return calcMinTokensToConnect(buildingHexes, null, Set.of(), Set.of());
    }

    private int calcMinTokensToConnect(List<int[]> buildingHexes, UUID gameId,
                                         Set<String> allowedHexes, Set<String> buildingHexSet) {
        if (buildingHexes.size() <= 1) return 0;

        int n = buildingHexes.size();
        // BFS로 실제 경로 거리 계산 (장애물 우회)
        int[][] dist = new int[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                int d = bfsDistance(buildingHexes.get(i), buildingHexes.get(j), allowedHexes, buildingHexSet);
                dist[i][j] = d;
                dist[j][i] = d;
            }
        }

        // Prim MST: 최소 신장 트리의 엣지 가중치 합
        boolean[] inMST = new boolean[n];
        int[] minDist = new int[n];
        java.util.Arrays.fill(minDist, Integer.MAX_VALUE);
        minDist[0] = 0;
        int totalTokens = 0;

        for (int count = 0; count < n; count++) {
            int u = -1;
            for (int i = 0; i < n; i++) {
                if (!inMST[i] && (u == -1 || minDist[i] < minDist[u])) u = i;
            }
            inMST[u] = true;
            if (minDist[u] > 0) totalTokens += Math.max(0, minDist[u] - 1);

            // 인접 노드 업데이트
            for (int v = 0; v < n; v++) {
                if (!inMST[v] && dist[u][v] < minDist[v]) {
                    minDist[v] = dist[u][v];
                }
            }
        }

        return totalTokens;
    }

    /**
     * 토큰 배치 가능 헥스 구성 (EMPTY + 맵에 존재 + 기존 연방 인접 아님)
     */
    private Set<String> buildAllowedHexes(UUID gameId, UUID playerId, boolean isIvits) {
        Set<String> allowed = new HashSet<>();
        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1},{1,-1},{-1,1}};

        // 맵에 존재하는 EMPTY 헥스만 허용
        List<GameHex> allHexes = hexRepository.findByGameId(gameId);
        for (GameHex hex : allHexes) {
            if ("EMPTY".equals(hex.getPlanetType().name())) {
                allowed.add(hex.getHexQ() + "," + hex.getHexR());
            }
        }

        // 기존 연방 헥스 + 인접 제거 (하이브 제외)
        if (!isIvits) {
            Set<String> fedHexes = getUsedFederationHexes(gameId, playerId);
            allowed.removeAll(fedHexes);
            for (String fedHex : fedHexes) {
                String[] parts = fedHex.split(",");
                int q = Integer.parseInt(parts[0]);
                int r = Integer.parseInt(parts[1]);
                for (int[] d : dirs) {
                    allowed.remove((q + d[0]) + "," + (r + d[1]));
                }
            }
        }

        return allowed;
    }

    /**
     * BFS로 두 건물 간 실제 최단 토큰 수 계산
     * 토큰 배치 가능 조건: EMPTY 헥스만 (행성/소행성/초월행성 불가), 기존 연방 헥스 및 인접 불가
     * 건물 헥스는 통과 가능 (연결 대상이므로)
     */
    private int bfsDistance(int[] from, int[] to, Set<String> allowedHexes, Set<String> buildingHexSet) {
        String startKey = from[0] + "," + from[1];
        String endKey = to[0] + "," + to[1];
        if (startKey.equals(endKey)) return 0;

        // 인접하면 토큰 0
        int directDist = com.gaiaproject.util.HexUtil.distance(from[0], from[1], to[0], to[1]);
        if (directDist == 1) return 0;

        // BFS
        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1},{1,-1},{-1,1}};
        Map<String, Integer> visited = new HashMap<>();
        Queue<String> queue = new LinkedList<>();
        queue.add(startKey);
        visited.put(startKey, 0);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            int currentDist = visited.get(current);
            String[] parts = current.split(",");
            int q = Integer.parseInt(parts[0]);
            int r = Integer.parseInt(parts[1]);

            for (int[] d : dirs) {
                String neighbor = (q + d[0]) + "," + (r + d[1]);
                if (visited.containsKey(neighbor)) continue;

                // 도착지면 완료 (토큰 수 = 경로 길이 - 1, 시작/끝 제외)
                if (neighbor.equals(endKey)) {
                    return currentDist; // 시작 건물은 0부터, 중간 헥스 수 = 토큰 수
                }

                // 건물 헥스면 통과 가능 (토큰 안 놓지만 연결 경로)
                if (buildingHexSet.contains(neighbor)) {
                    visited.put(neighbor, currentDist);
                    queue.add(neighbor);
                    continue;
                }

                // 허용된 헥스만 통과 가능 (EMPTY + 맵 존재 + 연방 인접 아님)
                if (!allowedHexes.contains(neighbor)) continue;
                visited.put(neighbor, currentDist + 1);
                queue.add(neighbor);
            }
        }

        return Integer.MAX_VALUE; // 연결 불가
    }

    /** BFS로 연결된 그룹 수 계산 */
    private int countConnectedGroups(Set<String> hexes) {
        if (hexes.isEmpty()) return 0;
        Set<String> visited = new HashSet<>();
        int groups = 0;
        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1},{1,-1},{-1,1}};

        for (String hex : hexes) {
            if (visited.contains(hex)) continue;
            groups++;
            Queue<String> queue = new LinkedList<>();
            queue.add(hex);
            visited.add(hex);
            while (!queue.isEmpty()) {
                String cur = queue.poll();
                String[] parts = cur.split(",");
                int q = Integer.parseInt(parts[0]);
                int r = Integer.parseInt(parts[1]);
                for (int[] d : dirs) {
                    String neighbor = (q + d[0]) + "," + (r + d[1]);
                    if (hexes.contains(neighbor) && !visited.contains(neighbor)) {
                        visited.add(neighbor);
                        queue.add(neighbor);
                    }
                }
            }
        }
        return groups;
    }

    /**
     * 연방 배치 조건 체크 (타일 선택 전). DB 변경 없이 검증만 수행.
     */
    public FormFederationResponse validateFederation(UUID gameId, FormFederationRequest request) {
        UUID playerId = request.playerId();
        GamePlayerState ps = playerStateRepository.findByGameIdAndPlayerId(gameId, playerId)
                .orElseThrow(() -> new IllegalStateException("플레이어 상태를 찾을 수 없습니다"));

        boolean isIvits = ps.getFactionType() == FactionType.IVITS;
        List<int[]> tokenHexes = request.tokenHexes() != null ? request.tokenHexes() : List.of();

        List<GameBuilding> myBuildings = buildingRepository.findByGameIdAndPlayerId(gameId, playerId);
        Map<String, GameBuilding> myBuildingMap = myBuildings.stream()
                .collect(Collectors.toMap(b -> b.getHexQ() + "," + b.getHexR(), b -> b, (a, c) -> a));

        // 이미 연방된 헥스 (일반 종족: 제외, 하이브: 포함 가능)
        Set<String> usedFedHexes = isIvits ? Set.of() : getUsedFederationHexes(gameId, playerId);

        // 토큰이 이미 연방된 위치에 있는지 체크
        for (int[] hex : tokenHexes) {
            if (usedFedHexes.contains(hex[0] + "," + hex[1])) {
                return FormFederationResponse.fail(gameId, "이미 연방에 사용된 위치에 토큰을 놓았습니다");
            }
        }

        List<int[]> buildingHexes = findConnectedBuildings(tokenHexes, myBuildingMap, usedFedHexes);
        if (buildingHexes.isEmpty()) {
            return FormFederationResponse.fail(gameId, "토큰에 연결된 건물이 없습니다");
        }

        List<GameBuilding> selectedBuildings = buildingHexes.stream()
                .map(h -> myBuildingMap.get(h[0] + "," + h[1])).toList();

        // 연결성
        Set<String> allHexes = new HashSet<>();
        for (int[] h : buildingHexes) allHexes.add(h[0] + "," + h[1]);
        for (int[] h : tokenHexes) allHexes.add(h[0] + "," + h[1]);
        if (!isConnected(allHexes)) {
            return FormFederationResponse.fail(gameId, "건물과 토큰이 연결되어 있지 않습니다");
        }

        // 파워 값 (BASIC_TILE_9 반영)
        int totalPowerValue = selectedBuildings.stream().mapToInt(b -> buildingPowerValue(b.getBuildingType(), gameId, playerId, b.isHasRing())).sum();

        // 제노스 PI: 연방 필요 파워 6
        boolean isXenosPi = ps.getFactionType() == FactionType.XENOS && ps.getStockPlanetaryInstitute() == 0;
        int fedRequiredPower = isXenosPi ? 6 : 7;

        if (isIvits) {
            // 하이브: 기존 연방 파워 합산 + 7의 배수 체크
            List<GameFederationGroup> existingGroups = federationGroupRepository.findByGameIdAndPlayerId(gameId, playerId);
            int existingPowerValue = 0;
            if (!existingGroups.isEmpty()) {
                GameFederationGroup eg = existingGroups.get(0);
                for (GameFederationBuilding fb : federationBuildingRepository.findByFederationGroupId(eg.getId())) {
                    GameBuilding b = buildingRepository.findFirstByGameIdAndHexQAndHexRAndIsLantidsMine(gameId, fb.getHexQ(), fb.getHexR(), false).orElse(null);
                    if (b != null) existingPowerValue += buildingPowerValue(b.getBuildingType(), gameId, playerId, b.isHasRing());
                }
            }
            int total = existingPowerValue + totalPowerValue;
            int prevTiles = existingPowerValue / 7;
            if (total / 7 <= prevTiles) {
                return FormFederationResponse.fail(gameId, "연방 파워가 부족합니다 (현재: " + total + ", 다음 목표: " + (prevTiles + 1) * 7 + ")");
            }
        } else {
            if (totalPowerValue < fedRequiredPower) {
                return FormFederationResponse.fail(gameId, "파워 값이 " + fedRequiredPower + " 미만입니다 (현재: " + totalPowerValue + ")");
            }
        }

        // 최소 토큰
        if (!tokenHexes.isEmpty() && canFormWithFewerTokens(selectedBuildings, tokenHexes, buildingHexes)) {
            return FormFederationResponse.fail(gameId, "최소 토큰 수보다 많은 토큰을 사용했습니다");
        }

        return FormFederationResponse.success(gameId, null, null); // 검증 통과
    }

    public FormFederationResponse formFederation(UUID gameId, FormFederationRequest request) {
        UUID playerId = request.playerId();

        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new IllegalArgumentException("게임을 찾을 수 없습니다"));
        if (!"PLAYING".equals(game.getGamePhase())) {
            return FormFederationResponse.fail(gameId, "PLAYING 페이즈가 아닙니다");
        }

        GamePlayerState ps = playerStateRepository.findByGameIdAndPlayerId(gameId, playerId)
                .orElseThrow(() -> new IllegalStateException("플레이어 상태를 찾을 수 없습니다"));

        boolean isIvits = ps.getFactionType() == FactionType.IVITS;

        // 1. 토큰 + 건물 준비
        List<int[]> tokenHexes = request.tokenHexes() != null ? request.tokenHexes() : List.of();

        List<GameBuilding> myBuildings = buildingRepository.findByGameIdAndPlayerId(gameId, playerId);
        Map<String, GameBuilding> myBuildingMap = myBuildings.stream()
                .collect(Collectors.toMap(b -> b.getHexQ() + "," + b.getHexR(), b -> b, (a, c) -> a));

        // 이미 연방된 헥스 제외 (하이브는 제외 없음)
        Set<String> usedFedHexes = isIvits ? Set.of() : getUsedFederationHexes(gameId, playerId);

        for (int[] hex : tokenHexes) {
            if (usedFedHexes.contains(hex[0] + "," + hex[1])) {
                return FormFederationResponse.fail(gameId, "이미 연방에 사용된 위치에 토큰을 놓았습니다");
            }
        }

        List<int[]> buildingHexes = findConnectedBuildings(tokenHexes, myBuildingMap, usedFedHexes);
        if (buildingHexes.isEmpty()) {
            return FormFederationResponse.fail(gameId, "토큰에 연결된 건물이 없습니다");
        }

        List<GameBuilding> selectedBuildings = new ArrayList<>();
        for (int[] hex : buildingHexes) {
            selectedBuildings.add(myBuildingMap.get(hex[0] + "," + hex[1]));
        }

        // 2. 하이브: 기존 연방과 합치는 "확장" 로직 / 일반: 이미 사용된 건물 불가
        if (isIvits) {
            return formFederationIvits(gameId, playerId, ps, game, selectedBuildings, buildingHexes, tokenHexes, request.federationTileCode());
        }

        // ── 일반 종족 ──
        // (이미 연방된 건물은 findConnectedBuildings에서 제외됨)

        // 3. 토큰 헥스 검증
        int tokenCount = tokenHexes.size();
        if (tokenCount > 0) {
            int totalPower = ps.getPowerBowl1() + ps.getPowerBowl2() + ps.getPowerBowl3();
            if (totalPower < tokenCount) {
                return FormFederationResponse.fail(gameId, "파워 토큰이 부족합니다 (필요: " + tokenCount + ", 보유: " + totalPower + ")");
            }
        }

        for (int[] hex : tokenHexes) {
            String key = hex[0] + "," + hex[1];
            if (usedFedHexes.contains(key)) {
                return FormFederationResponse.fail(gameId, "이미 연방에 사용된 위치입니다");
            }
            if (myBuildingMap.containsKey(key)) {
                return FormFederationResponse.fail(gameId, "내 건물 위치에는 토큰을 놓을 수 없습니다");
            }
        }

        // 4. 연결성 검증
        Set<String> allHexes = new HashSet<>();
        for (int[] h : buildingHexes) allHexes.add(h[0] + "," + h[1]);
        for (int[] h : tokenHexes) allHexes.add(h[0] + "," + h[1]);
        if (!isConnected(allHexes)) {
            return FormFederationResponse.fail(gameId, "건물과 토큰이 연결되어 있지 않습니다");
        }

        // 5. 파워 값 합계 검증 (제노스 PI: 6 이상, 기본: 7 이상)
        int totalPowerValue = selectedBuildings.stream().mapToInt(b -> buildingPowerValue(b.getBuildingType(), gameId, playerId, b.isHasRing())).sum();
        // 매안 PI: 본인 행성(TITANIUM) 건물 파워값 +1
        if (ps.getFactionType() == FactionType.BESCODS && ps.getStockPlanetaryInstitute() == 0) {
            for (GameBuilding b : selectedBuildings) {
                var hexOpt = hexRepository.findByGameIdAndHexQAndHexR(gameId, b.getHexQ(), b.getHexR());
                if (hexOpt.isPresent() && hexOpt.get().getPlanetType() == com.gaiaproject.domain.enumtype.player.PlanetType.TITANIUM) {
                    totalPowerValue += 1;
                }
            }
        }
        boolean isXenosPiHere = ps.getFactionType() == FactionType.XENOS && ps.getStockPlanetaryInstitute() == 0;
        int fedReq = isXenosPiHere ? 6 : 7;
        if (totalPowerValue < fedReq) {
            return FormFederationResponse.fail(gameId, "파워 값이 " + fedReq + " 미만입니다 (현재: " + totalPowerValue + ")");
        }

        // 6. 최소 토큰 사용 검증
        if (tokenCount > 0 && canFormWithFewerTokens(selectedBuildings, tokenHexes, buildingHexes)) {
            return FormFederationResponse.fail(gameId, "최소 토큰 수보다 많은 토큰을 사용했습니다");
        }

        // 7. 연방 타일 검증
        FederationTileType tileType;
        try { tileType = FederationTileType.valueOf(request.federationTileCode()); }
        catch (Exception e) { return FormFederationResponse.fail(gameId, "알 수 없는 연방 타일"); }

        // 8. 처리
        if (tokenCount > 0) { removePowerTokens(ps, tokenCount); }

        GameFederationGroup group = federationGroupRepository.save(GameFederationGroup.builder()
                .gameId(gameId).playerId(playerId).federationTileCode(tileType.name()).build());
        for (int[] h : buildingHexes) federationBuildingRepository.save(GameFederationBuilding.builder().federationGroupId(group.getId()).hexQ(h[0]).hexR(h[1]).build());
        for (int[] h : tokenHexes) federationTokenHexRepository.save(GameFederationTokenHex.builder().federationGroupId(group.getId()).hexQ(h[0]).hexR(h[1]).build());

        FederationTileService.acquireTileFromOffer(federationOfferRepository, gameId, tileType);
        playerFederationTokenRepository.save(GamePlayerFederationToken.builder().gameId(gameId).playerId(playerId).federationTileType(tileType).build());

        ps.applyIncome(tileType.getImmediateReward());
        // 라운드 점수: 연방 형성
        if (game.getCurrentRound() != null) {
            roundScoringService.award(gameId, game.getCurrentRound(), ps,
                    com.gaiaproject.domain.enumtype.rounds.RoundScoringEvent.FEDERATION_FORMED, 1);
        }
        playerStateRepository.save(ps);

        log.info("[FEDERATION] 일반 연방: game={}, player={}, tile={}, buildings={}, tokens={}", gameId, playerId, tileType, buildingHexes.size(), tokenCount);
        ConfirmActionResponse result = actionService.saveActionAndNextTurn(gameId, playerId, ActionType.FACTION_ABILITY, "{\"type\":\"FEDERATION\",\"tileCode\":\"" + tileType.name() + "\"}");
        return FormFederationResponse.success(gameId, tileType.name(), result.nextTurnSeatNo());
    }

    /**
     * 하이브 연방: QIC로 연결, 하나의 연방 그룹에 확장, 파워 합이 7의 배수 도달 시 타일 획득
     */
    private FormFederationResponse formFederationIvits(UUID gameId, UUID playerId, GamePlayerState ps, Game game,
                                                        List<GameBuilding> selectedBuildings, List<int[]> buildingHexes,
                                                        List<int[]> tokenHexes, String tileCode) {
        int tokenCount = tokenHexes.size();

        // QIC 검증 (파워 토큰 대신 QIC 사용)
        if (tokenCount > 0 && ps.getQic() < tokenCount) {
            return FormFederationResponse.fail(gameId, "QIC가 부족합니다 (필요: " + tokenCount + ", 보유: " + ps.getQic() + ")");
        }

        // 연결성 검증
        Set<String> allHexes = new HashSet<>();
        for (int[] h : buildingHexes) allHexes.add(h[0] + "," + h[1]);
        for (int[] h : tokenHexes) allHexes.add(h[0] + "," + h[1]);

        // 기존 연방 그룹이 있으면 그 그룹의 건물+토큰도 포함하여 연결성 검증
        List<GameFederationGroup> existingGroups = federationGroupRepository.findByGameIdAndPlayerId(gameId, playerId);
        GameFederationGroup existingGroup = existingGroups.isEmpty() ? null : existingGroups.get(0);

        if (existingGroup != null) {
            // 기존 연방 건물/토큰도 연결 대상에 포함
            federationBuildingRepository.findByFederationGroupId(existingGroup.getId())
                    .forEach(b -> allHexes.add(b.getHexQ() + "," + b.getHexR()));
            federationTokenHexRepository.findByFederationGroupId(existingGroup.getId())
                    .forEach(t -> allHexes.add(t.getHexQ() + "," + t.getHexR()));
        }

        if (!isConnected(allHexes)) {
            return FormFederationResponse.fail(gameId, "건물과 QIC 토큰이 연결되어 있지 않습니다");
        }

        // 신규 건물 파워값
        int newPowerValue = selectedBuildings.stream().mapToInt(b -> buildingPowerValue(b.getBuildingType(), gameId, playerId, b.isHasRing())).sum();

        // 기존 연방 파워 합산
        int existingPowerValue = 0;
        if (existingGroup != null) {
            List<GameFederationBuilding> existingFedBuildings = federationBuildingRepository.findByFederationGroupId(existingGroup.getId());
            for (GameFederationBuilding fb : existingFedBuildings) {
                GameBuilding b = buildingRepository.findFirstByGameIdAndHexQAndHexRAndIsLantidsMine(gameId, fb.getHexQ(), fb.getHexR(), false).orElse(null);
                if (b != null) existingPowerValue += buildingPowerValue(b.getBuildingType(), gameId, playerId, b.isHasRing());
            }
        }

        int totalPowerValue = existingPowerValue + newPowerValue;

        // 첫 연방: 7 이상 필요
        // 확장: 기존 파워에서 다음 7의 배수 도달 필요
        int previousTiles = existingPowerValue / 7; // 이전에 획득한 타일 수
        int newTiles = totalPowerValue / 7;         // 총 획득 가능한 타일 수
        if (newTiles <= previousTiles) {
            int nextThreshold = (previousTiles + 1) * 7;
            return FormFederationResponse.fail(gameId,
                    "연방 파워가 부족합니다 (현재: " + totalPowerValue + ", 다음 목표: " + nextThreshold + ")");
        }

        // 연방 타일 검증
        FederationTileType tileType;
        try { tileType = FederationTileType.valueOf(tileCode); }
        catch (Exception e) { return FormFederationResponse.fail(gameId, "알 수 없는 연방 타일"); }

        // 최소 토큰 검증
        if (tokenCount > 0 && canFormWithFewerTokens(selectedBuildings, tokenHexes, buildingHexes)) {
            return FormFederationResponse.fail(gameId, "최소 QIC 수보다 많은 QIC를 사용했습니다");
        }

        // QIC 차감
        if (tokenCount > 0) { ps.spendQic(tokenCount); }

        // 그룹 생성 또는 기존 그룹에 확장
        GameFederationGroup group;
        if (existingGroup != null) {
            group = existingGroup;
        } else {
            group = federationGroupRepository.save(GameFederationGroup.builder()
                    .gameId(gameId).playerId(playerId).federationTileCode(tileType.name()).build());
        }

        // 건물/토큰 추가
        for (int[] h : buildingHexes) federationBuildingRepository.save(GameFederationBuilding.builder().federationGroupId(group.getId()).hexQ(h[0]).hexR(h[1]).build());
        for (int[] h : tokenHexes) federationTokenHexRepository.save(GameFederationTokenHex.builder().federationGroupId(group.getId()).hexQ(h[0]).hexR(h[1]).build());

        // 연방 타일 획득
        FederationTileService.acquireTileFromOffer(federationOfferRepository, gameId, tileType);
        playerFederationTokenRepository.save(GamePlayerFederationToken.builder().gameId(gameId).playerId(playerId).federationTileType(tileType).build());

        ps.applyIncome(tileType.getImmediateReward());
        // 라운드 점수: 연방 형성
        if (game.getCurrentRound() != null) {
            roundScoringService.award(gameId, game.getCurrentRound(), ps,
                    com.gaiaproject.domain.enumtype.rounds.RoundScoringEvent.FEDERATION_FORMED, 1);
        }
        playerStateRepository.save(ps);

        log.info("[FEDERATION-IVITS] 연방: game={}, player={}, tile={}, newBuildings={}, qicTokens={}, totalPower={}",
                gameId, playerId, tileType, buildingHexes.size(), tokenCount, totalPowerValue);
        ConfirmActionResponse result = actionService.saveActionAndNextTurn(gameId, playerId, ActionType.FACTION_ABILITY, "{\"type\":\"FEDERATION_IVITS\",\"tileCode\":\"" + tileType.name() + "\"}");
        return FormFederationResponse.success(gameId, tileType.name(), result.nextTurnSeatNo());
    }

    /** 플레이어의 이미 사용된 연방 헥스 (건물 + 토큰) 조회 */
    private Set<String> getUsedFederationHexes(UUID gameId, UUID playerId) {
        List<GameFederationGroup> groups = federationGroupRepository.findByGameIdAndPlayerId(gameId, playerId);
        if (groups.isEmpty()) return Set.of();

        List<UUID> groupIds = groups.stream().map(GameFederationGroup::getId).toList();
        Set<String> used = new HashSet<>();

        federationBuildingRepository.findByFederationGroupIdIn(groupIds)
                .forEach(b -> used.add(b.getHexQ() + "," + b.getHexR()));
        federationTokenHexRepository.findByFederationGroupIdIn(groupIds)
                .forEach(t -> used.add(t.getHexQ() + "," + t.getHexR()));

        return used;
    }

    /** 연결성 검증 (BFS) */
    private boolean isConnected(Set<String> hexes) {
        if (hexes.size() <= 1) return true;
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        String start = hexes.iterator().next();
        queue.add(start);
        visited.add(start);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            String[] parts = current.split(",");
            int q = Integer.parseInt(parts[0]);
            int r = Integer.parseInt(parts[1]);

            // 6방향 이웃
            int[][] neighbors = {{1,0},{-1,0},{0,1},{0,-1},{1,-1},{-1,1}};
            for (int[] n : neighbors) {
                String neighbor = (q + n[0]) + "," + (r + n[1]);
                if (hexes.contains(neighbor) && !visited.contains(neighbor)) {
                    visited.add(neighbor);
                    queue.add(neighbor);
                }
            }
        }
        return visited.size() == hexes.size();
    }

    /** 사용된 토큰 수가 최소보다 많은지 검증 */
    private boolean canFormWithFewerTokens(List<GameBuilding> buildings, List<int[]> tokenHexes, List<int[]> buildingHexes) {
        if (tokenHexes == null || tokenHexes.isEmpty()) return false;
        int minTokens = calcMinTokensToConnect(buildingHexes);
        return tokenHexes.size() > minTokens;
    }

    /** 파워 토큰 제거 (bowl1 → bowl2 → bowl3 순) */
    private void removePowerTokens(GamePlayerState ps, int count) {
        int remaining = count;
        int fromBowl1 = Math.min(ps.getPowerBowl1(), remaining);
        if (fromBowl1 > 0) { ps.removePowerFromBowl1(fromBowl1); remaining -= fromBowl1; }
        int fromBowl2 = Math.min(ps.getPowerBowl2(), remaining);
        if (fromBowl2 > 0) { ps.removePowerFromBowl2(fromBowl2); remaining -= fromBowl2; }
        if (remaining > 0) { ps.removePowerFromBowl3(remaining); }
    }

    private int buildingPowerValue(BuildingType type) {
        return switch (type) {
            case MINE -> 1;
            case TRADING_STATION, RESEARCH_LAB -> 2;
            case PLANETARY_INSTITUTE, ACADEMY -> 3;
            case SPACE_STATION -> 1;
            default -> 0;
        };
    }

    /** BASIC_TILE_9 보유 시 큰 건물 파워 가치 +1, 모웨이드 링 +2 반영 */
    private int buildingPowerValue(BuildingType type, UUID gameId, UUID playerId) {
        return buildingPowerValue(type, gameId, playerId, false);
    }

    private int buildingPowerValue(BuildingType type, UUID gameId, UUID playerId, boolean hasRing) {
        int base = buildingPowerValue(type);
        if ((type == BuildingType.PLANETARY_INSTITUTE || type == BuildingType.ACADEMY)
                && playerTechTileRepository
                    .findByGameIdAndPlayerIdAndIsCovered(gameId, playerId, false)
                    .stream()
                    .anyMatch(t -> "BASIC_TILE_9".equals(t.getTechTileCode()))) {
            base += 1;
        }
        if (hasRing) base += 2;
        return base;
    }

    /** 게임의 모든 연방 데이터 조회 (FE 표시용) — 연방 그룹 + 플레이어 직접 보유 토큰(글린 PI 등) */
    public List<FederationGroupInfo> getFederationGroups(UUID gameId) {
        List<FederationGroupInfo> result = new java.util.ArrayList<>();

        // 1. 연방 그룹 기반 토큰
        List<GameFederationGroup> groups = federationGroupRepository.findByGameId(gameId);
        for (var g : groups) {
            List<int[]> bHexes = federationBuildingRepository.findByFederationGroupId(g.getId())
                    .stream().map(b -> new int[]{b.getHexQ(), b.getHexR()}).toList();
            List<int[]> tHexes = federationTokenHexRepository.findByFederationGroupId(g.getId())
                    .stream().map(t -> new int[]{t.getHexQ(), t.getHexR()}).toList();
            result.add(new FederationGroupInfo(g.getPlayerId().toString(), g.getFederationTileCode(), bHexes, tHexes, g.isUsed()));
        }

        // 2. 플레이어 직접 보유 토큰 (글린 PI 등 — 연방 그룹에 속하지 않는 토큰)
        var allPlayerTokens = playerFederationTokenRepository.findByGameId(gameId);
        for (var token : allPlayerTokens) {
            String tileCode = token.getFederationTileType().name();
            boolean alreadyInGroup = result.stream().anyMatch(
                    r -> r.playerId().equals(token.getPlayerId().toString()) && r.tileCode().equals(tileCode)
            );
            if (!alreadyInGroup) {
                result.add(new FederationGroupInfo(token.getPlayerId().toString(), tileCode, List.of(), List.of(), token.isUsed()));
            }
        }

        return result;
    }

    /**
     * 건물 건설/업그레이드 시 인접 연방에 자동 편입.
     * 새 건물의 6방향 이웃에 해당 플레이어의 연방 소속 건물이나 토큰이 있으면 같은 그룹에 추가.
     */
    public void autoJoinFederation(UUID gameId, UUID playerId, int hexQ, int hexR) {
        List<GameFederationGroup> groups = federationGroupRepository.findByGameIdAndPlayerId(gameId, playerId);
        if (groups.isEmpty()) return;

        int[][] neighbors = {{1,0},{-1,0},{0,1},{0,-1},{1,-1},{-1,1}};

        for (GameFederationGroup group : groups) {
            List<GameFederationBuilding> fedBuildings = federationBuildingRepository.findByFederationGroupId(group.getId());
            List<GameFederationTokenHex> fedTokens = federationTokenHexRepository.findByFederationGroupId(group.getId());

            Set<String> fedHexes = new HashSet<>();
            fedBuildings.forEach(b -> fedHexes.add(b.getHexQ() + "," + b.getHexR()));
            fedTokens.forEach(t -> fedHexes.add(t.getHexQ() + "," + t.getHexR()));

            // 이미 이 그룹에 포함되어 있으면 스킵
            if (fedHexes.contains(hexQ + "," + hexR)) return;

            // 새 건물의 이웃 중 이 연방에 속하는 헥스가 있는지 확인
            boolean adjacent = false;
            for (int[] n : neighbors) {
                if (fedHexes.contains((hexQ + n[0]) + "," + (hexR + n[1]))) {
                    adjacent = true;
                    break;
                }
            }

            if (adjacent) {
                federationBuildingRepository.save(GameFederationBuilding.builder()
                        .federationGroupId(group.getId()).hexQ(hexQ).hexR(hexR).build());
                log.info("[FEDERATION] 자동 편입: game={}, player={}, hex=({},{}), group={}", gameId, playerId, hexQ, hexR, group.getId());
                return; // 하나의 그룹에만 편입
            }
        }
    }

    /**
     * 토큰 헥스에 인접한 내 건물을 BFS로 탐색하여 연결된 건물 전부 반환.
     * 토큰끼리도 연결되고, 토큰-건물, 건물-건물도 인접하면 연결.
     */
    private List<int[]> findConnectedBuildings(List<int[]> tokenHexes, Map<String, GameBuilding> myBuildingMap, Set<String> excludeHexes) {
        // 토큰 + 내 모든 건물을 합쳐서 연결 그래프 구성
        Set<String> tokenSet = new HashSet<>();
        for (int[] t : tokenHexes) tokenSet.add(t[0] + "," + t[1]);

        // BFS: 토큰에서 시작, 인접한 내 건물 탐색, 건물에서도 인접 건물/토큰 탐색
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new LinkedList<>();

        // 시드: 모든 토큰 + 토큰에 인접한 내 건물
        for (int[] t : tokenHexes) {
            String key = t[0] + "," + t[1];
            if (!visited.contains(key)) {
                visited.add(key);
                queue.add(key);
            }
        }

        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1},{1,-1},{-1,1}};
        Set<String> connectedBuildings = new LinkedHashSet<>();

        while (!queue.isEmpty()) {
            String current = queue.poll();
            String[] parts = current.split(",");
            int q = Integer.parseInt(parts[0]);
            int r = Integer.parseInt(parts[1]);

            // 현재 위치가 내 건물이고 아직 연방에 속하지 않으면 포함
            if (myBuildingMap.containsKey(current) && !excludeHexes.contains(current)) {
                connectedBuildings.add(current);
            }

            for (int[] d : dirs) {
                String neighbor = (q + d[0]) + "," + (r + d[1]);
                if (visited.contains(neighbor)) continue;
                // 이웃이 토큰이거나 (이미 연방되지 않은) 내 건물이면 탐색 대상
                if (tokenSet.contains(neighbor) || (myBuildingMap.containsKey(neighbor) && !excludeHexes.contains(neighbor))) {
                    visited.add(neighbor);
                    queue.add(neighbor);
                }
            }
        }

        return connectedBuildings.stream()
                .map(k -> { String[] p = k.split(","); return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1])}; })
                .toList();
    }

    public record FederationGroupInfo(String playerId, String tileCode, List<int[]> buildingHexes, List<int[]> tokenHexes, boolean used) {}
}
