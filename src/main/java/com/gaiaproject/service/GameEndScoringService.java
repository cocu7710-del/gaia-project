package com.gaiaproject.service;

import com.gaiaproject.domain.entity.building.GameBuilding;
import com.gaiaproject.domain.entity.game.Game;
import com.gaiaproject.domain.entity.map.GameHex;
import com.gaiaproject.domain.entity.player.GamePlayerState;
import com.gaiaproject.domain.entity.rounds.GameFinalScoring;
import com.gaiaproject.domain.enumtype.action.VpCategory;
import com.gaiaproject.domain.enumtype.building.BuildingType;
import com.gaiaproject.domain.enumtype.player.PlanetType;
import com.gaiaproject.repository.building.GameBuildingRepository;
import com.gaiaproject.repository.federation.GameFederationBuildingRepository;
import com.gaiaproject.repository.federation.GameFederationGroupRepository;
import com.gaiaproject.repository.game.GameRepository;
import com.gaiaproject.repository.map.GameHexRepository;
import com.gaiaproject.repository.player.GamePlayerStateRepository;
import com.gaiaproject.repository.rounds.GameFinalScoringRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class GameEndScoringService {

    private final GameRepository gameRepository;
    private final GamePlayerStateRepository playerStateRepository;
    private final GameBuildingRepository buildingRepository;
    private final GameHexRepository hexRepository;
    private final GameFinalScoringRepository finalScoringRepository;
    private final GameFederationGroupRepository federationGroupRepository;
    private final GameFederationBuildingRepository federationBuildingRepository;
    private final VpLogService vpLogService;

    /**
     * 게임 종료 시 최종 점수 계산 (최종 미션 + 남은 자원 + 지식트랙)
     */
    public void calculateFinalScores(UUID gameId) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new IllegalArgumentException("게임을 찾을 수 없습니다"));
        List<GamePlayerState> allPlayers = playerStateRepository.findByGameId(gameId);
        List<GameBuilding> allBuildings = buildingRepository.findByGameId(gameId);
        List<GameHex> allHexes = hexRepository.findByGameId(gameId);
        Map<String, GameHex> hexByCoord = allHexes.stream()
                .collect(Collectors.toMap(h -> h.getHexQ() + "," + h.getHexR(), h -> h, (a, b) -> a));

        // 1. 최종 미션 점수
        calculateFinalMissionScores(gameId, allPlayers, allBuildings, hexByCoord);

        // 2. 지식트랙 점수 (3단계 이상 1칸당 4VP)
        for (GamePlayerState ps : allPlayers) {
            int trackVp = calcKnowledgeTrackVP(ps);
            if (trackVp > 0) {
                ps.addVP(trackVp);
                vpLogService.logVp(gameId, ps.getPlayerId(), VpCategory.KNOWLEDGE_TRACK, trackVp, null, "지식트랙 VP");
                log.info("[FINAL] 지식트랙 VP: player={}, vp={}", ps.getPlayerId(), trackVp);
            }
        }

        // 3. 남은 자원 점수 (3자원당 1VP, 3구역 파워=1돈 취급)
        for (GamePlayerState ps : allPlayers) {
            int resourceVp = calcRemainingResourceVP(ps);
            if (resourceVp > 0) {
                ps.addVP(resourceVp);
                vpLogService.logVp(gameId, ps.getPlayerId(), VpCategory.REMAINING_RESOURCES, resourceVp, null, "남은 자원 VP");
                log.info("[FINAL] 남은 자원 VP: player={}, vp={}", ps.getPlayerId(), resourceVp);
            }
        }

        // 4. 비딩 패널티 차감
        for (GamePlayerState ps : allPlayers) {
            if (ps.getBidPenalty() > 0) {
                ps.addVP(-ps.getBidPenalty());
                vpLogService.logVp(gameId, ps.getPlayerId(), VpCategory.BIDDING, -ps.getBidPenalty(), null, "비딩 패널티");
                log.info("[FINAL] 비딩 패널티: player={}, penalty={}", ps.getPlayerId(), ps.getBidPenalty());
            }
        }

        playerStateRepository.saveAll(allPlayers);
        log.info("[FINAL] 최종 점수 계산 완료: gameId={}", gameId);
    }

    /**
     * 최종 미션 점수: 2개 미션 타일에 대해 등수별 VP
     * 1등 18VP, 2등 12VP, 3등 6VP
     * 공동 순위: (해당 등수들 VP 합) / 인원수
     */
    private void calculateFinalMissionScores(UUID gameId, List<GamePlayerState> allPlayers,
                                              List<GameBuilding> allBuildings, Map<String, GameHex> hexByCoord) {
        List<GameFinalScoring> finalScorings = finalScoringRepository.findByGameIdOrderByPosition(gameId);

        for (GameFinalScoring fs : finalScorings) {
            String tileCode = fs.getScoringTileCode().name();

            // 각 플레이어 달성도 계산
            List<PlayerScore> scores = new ArrayList<>();
            for (GamePlayerState ps : allPlayers) {
                int progress = calcFinalProgress(gameId, tileCode, ps.getPlayerId(), allBuildings, hexByCoord);
                scores.add(new PlayerScore(ps.getPlayerId(), progress));
            }

            // 내림차순 정렬
            scores.sort((a, b) -> b.score - a.score);

            // 등수별 VP 배분
            int[] rankVP = {18, 12, 6};
            int i = 0;
            while (i < scores.size()) {
                // 같은 점수 그룹 찾기
                int j = i;
                while (j < scores.size() && scores.get(j).score == scores.get(i).score) j++;
                int tiedCount = j - i;

                // 해당 등수 범위의 VP 합산
                int vpSum = 0;
                for (int k = i; k < j && k < rankVP.length; k++) {
                    vpSum += rankVP[k];
                }
                int vpEach = tiedCount > 0 ? vpSum / tiedCount : 0;

                // 0점이면 VP 없음
                if (scores.get(i).score > 0 && vpEach > 0) {
                    for (int k = i; k < j; k++) {
                        UUID pid = scores.get(k).playerId;
                        GamePlayerState ps = allPlayers.stream().filter(p -> p.getPlayerId().equals(pid)).findFirst().orElse(null);
                        if (ps != null) {
                            ps.addVP(vpEach);
                            vpLogService.logVp(gameId, pid, VpCategory.FINAL_SCORING, vpEach, null, "최종 미션: " + tileCode + " (" + scores.get(k).score + "개)");
                        }
                    }
                }
                i = j;
            }
        }
    }

    /** 지식트랙 VP: 각 트랙 3단계 이상 1칸당 4VP */
    private int calcKnowledgeTrackVP(GamePlayerState ps) {
        int vp = 0;
        vp += Math.max(0, ps.getTechTerraforming() - 2) * 4;
        vp += Math.max(0, ps.getTechNavigation() - 2) * 4;
        vp += Math.max(0, ps.getTechAi() - 2) * 4;
        vp += Math.max(0, ps.getTechGaia() - 2) * 4;
        vp += Math.max(0, ps.getTechEconomy() - 2) * 4;
        vp += Math.max(0, ps.getTechScience() - 2) * 4;
        return vp;
    }

    /** 남은 자원 VP: 3자원당 1VP (3구역 파워=1돈 취급) */
    private int calcRemainingResourceVP(GamePlayerState ps) {
        int brainstoneValue = (ps.getBrainstoneBowl() != null && ps.getBrainstoneBowl() == 3) ? 1 : 0;
        int totalResources = ps.getCredit() + ps.getOre() + ps.getKnowledge() + ps.getQic() + ps.getPowerBowl3() + brainstoneValue;
        return totalResources / 3;
    }

    /** 최종 미션 달성도 계산 (ScoringController에서 복사) */
    private int calcFinalProgress(UUID gameId, String tileCode, UUID playerId,
                                   List<GameBuilding> allBuildings, Map<String, GameHex> hexByCoord) {
        List<GameBuilding> myBuildings = allBuildings.stream()
                .filter(b -> b.getPlayerId().equals(playerId))
                .filter(b -> b.getBuildingType() != BuildingType.GAIAFORMER)
                .toList();

        return switch (tileCode) {
            case "FINAL_TILE_ASTEROID" -> (int) myBuildings.stream()
                    .filter(b -> {
                        GameHex hex = hexByCoord.get(b.getHexQ() + "," + b.getHexR());
                        return hex != null && hex.getPlanetType() == PlanetType.ASTEROIDS;
                    }).count();
            case "FINAL_TILE_GAIA_PLANET" -> (int) myBuildings.stream()
                    .filter(b -> {
                        GameHex hex = hexByCoord.get(b.getHexQ() + "," + b.getHexR());
                        return hex != null && hex.getPlanetType() == PlanetType.GAIA;
                    }).count();
            case "FINAL_TILE_MOST_BUILDINGS" -> myBuildings.size();
            case "FINAL_TILE_FEDERATION_BUILDINGS" -> {
                var groups = federationGroupRepository.findByGameIdAndPlayerId(gameId, playerId);
                if (groups.isEmpty()) yield 0;
                var groupIds = groups.stream().map(g -> g.getId()).toList();
                yield federationBuildingRepository.findByFederationGroupIdIn(groupIds).size();
            }
            case "FINAL_TILE_DEEP_SECTORS" -> (int) myBuildings.stream()
                    .filter(b -> {
                        GameHex hex = hexByCoord.get(b.getHexQ() + "," + b.getHexR());
                        return hex != null && hex.getPlanetType() == PlanetType.LOST_PLANET;
                    }).count();
            case "FINAL_TILE_PLANET_TYPES" -> {
                Set<PlanetType> types = myBuildings.stream()
                        .filter(b -> !b.isLantidsMine())
                        .map(b -> hexByCoord.get(b.getHexQ() + "," + b.getHexR()))
                        .filter(Objects::nonNull)
                        .map(GameHex::getPlanetType)
                        .filter(p -> p != PlanetType.EMPTY && p != PlanetType.TRANSDIM)
                        .collect(Collectors.toSet());
                yield types.size();
            }
            case "FINAL_TILE_FEDERATION_POWER" -> federationGroupRepository.findByGameIdAndPlayerId(gameId, playerId).size();
            case "FINAL_TILE_PI_ACADEMY_DISTANCE" -> {
                GameBuilding pi = myBuildings.stream()
                        .filter(b -> b.getBuildingType() == BuildingType.PLANETARY_INSTITUTE).findFirst().orElse(null);
                GameBuilding academy = myBuildings.stream()
                        .filter(b -> b.getBuildingType() == BuildingType.ACADEMY).findFirst().orElse(null);
                if (pi == null || academy == null) yield 0;
                else yield com.gaiaproject.util.HexUtil.distance(pi.getHexQ(), pi.getHexR(), academy.getHexQ(), academy.getHexR());
            }
            case "FINAL_TILE_SECTORS_WITH_BUILDINGS" -> {
                Set<String> sectors = myBuildings.stream()
                        .map(b -> hexByCoord.get(b.getHexQ() + "," + b.getHexR()))
                        .filter(Objects::nonNull)
                        .map(GameHex::getSectorId)
                        .filter(s -> s != null && s.startsWith("SECTOR_"))
                        .collect(Collectors.toSet());
                yield sectors.size();
            }
            default -> 0;
        };
    }

    private record PlayerScore(UUID playerId, int score) {}
}
