package com.gaiaproject.service;

import com.gaiaproject.domain.entity.artifact.GameArtifactOffer;
import com.gaiaproject.domain.entity.building.GameBuilding;
import com.gaiaproject.domain.entity.map.GameHex;
import com.gaiaproject.domain.entity.player.GamePlayerArtifact;
import com.gaiaproject.domain.entity.player.GamePlayerState;
import com.gaiaproject.domain.enumtype.artifact.ArtifactType;
import com.gaiaproject.domain.enumtype.building.BuildingType;
import com.gaiaproject.domain.enumtype.action.VpCategory;
import com.gaiaproject.domain.enumtype.rounds.RoundScoringEvent;
import com.gaiaproject.repository.artifact.GameArtifactOfferRepository;
import com.gaiaproject.repository.building.GameBuildingRepository;
import com.gaiaproject.repository.map.GameHexRepository;
import com.gaiaproject.repository.player.GamePlayerArtifactRepository;
import com.gaiaproject.repository.player.GamePlayerFleetProbeRepository;
import com.gaiaproject.repository.player.GamePlayerStateRepository;
import com.gaiaproject.repository.game.GameRepository;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ArtifactService {

    private final GamePlayerArtifactRepository artifactRepository;
    private final GameArtifactOfferRepository artifactOfferRepository;
    private final GamePlayerFleetProbeRepository fleetProbeRepository;
    private final GamePlayerStateRepository playerStateRepository;
    private final GameBuildingRepository buildingRepository;
    private final GameHexRepository hexRepository;
    private final GameRepository gameRepository;
    private final RoundScoringService roundScoringService;
    private final VpLogService vpLogService;

    /**
     * 인공물 초기 셋팅 (게임 시작 시 랜덤 4개)
     */
    public void setupArtifactTiles(UUID gameId) {
        List<ArtifactType> randomSetup = ArtifactType.getRandomSetup();
        int position = 1;
        for (ArtifactType artifact : randomSetup) {
            GameArtifactOffer offer = GameArtifactOffer.builder()
                    .gameId(gameId)
                    .artifactType(artifact)
                    .position(position++)
                    .build();
            artifactOfferRepository.save(offer);
        }
    }

    /**
     * 인공물 획득 가능 여부 확인
     */
    public boolean canAcquireArtifact(UUID gameId, UUID playerId) {
        boolean hasProbeInTwilight = fleetProbeRepository.existsByGameIdAndPlayerIdAndFleetName(
                gameId, playerId, "TWILIGHT");
        if (!hasProbeInTwilight) return false;

        GamePlayerState ps = playerStateRepository.findByGameIdAndPlayerId(gameId, playerId)
                .orElseThrow(() -> new IllegalStateException("플레이어 상태를 찾을 수 없습니다"));
        int totalPower = ps.getPowerBowl1() + ps.getPowerBowl2() + ps.getPowerBowl3();
        return totalPower >= 6;
    }

    /**
     * 인공물 획득 (파워 6 소각 + 즉시 효과 + DB 기록)
     */
    public void acquireArtifact(UUID gameId, UUID playerId, String artifactCode) {
        ArtifactType artifactType;
        try {
            artifactType = ArtifactType.valueOf(artifactCode);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("알 수 없는 인공물 코드: " + artifactCode);
        }

        // 오퍼 확인 (선착순 — 이미 획득된 것은 불가)
        GameArtifactOffer offer = artifactOfferRepository.findByGameIdAndArtifactType(gameId, artifactType)
                .orElseThrow(() -> new IllegalStateException("해당 인공물이 이 게임에 없습니다: " + artifactCode));
        if (offer.getIsAcquired()) {
            throw new IllegalStateException("이미 다른 플레이어가 획득한 인공물입니다");
        }

        GamePlayerState ps = playerStateRepository.findByGameIdAndPlayerId(gameId, playerId)
                .orElseThrow(() -> new IllegalStateException("플레이어 상태를 찾을 수 없습니다"));

        // 1. 파워 토큰 6개 영구 제거 (Bowl 1 → 2 → 3)
        removePowerTokens(ps, 6);

        // 2. 오퍼 점유
        offer.acquire(playerId);
        artifactOfferRepository.save(offer);

        // 3. 플레이어 인공물 기록
        artifactRepository.save(GamePlayerArtifact.builder()
                .gameId(gameId)
                .playerId(playerId)
                .artifactType(artifactType.name())
                .build());

        // 4. 즉시 효과 적용 (INCOME 타입은 수입으로만 처리 — 즉시 지급 안 함)
        if (!artifactType.hasIncomeEffect()) {
            applyImmediateEffect(gameId, ps, artifactType);
        }

        playerStateRepository.save(ps);
        log.info("[ARTIFACT] 획득: game={}, player={}, artifact={}", gameId, playerId, artifactCode);
    }

    /**
     * 파워 토큰 영구 제거 (Bowl 1 → 2 → 3 순서)
     */
    private void removePowerTokens(GamePlayerState player, int count) {
        int remaining = count;
        // addPowerBowlN은 `bowlN -= power` 이므로 양수를 전달하면 차감됨
        int fromBowl1 = Math.min(remaining, player.getPowerBowl1());
        player.addPowerBowl1(fromBowl1);
        remaining -= fromBowl1;
        if (remaining > 0) {
            int fromBowl2 = Math.min(remaining, player.getPowerBowl2());
            player.addPowerBowl2(fromBowl2);
            remaining -= fromBowl2;
        }
        if (remaining > 0) {
            int fromBowl3 = Math.min(remaining, player.getPowerBowl3());
            player.addPowerBowl3(fromBowl3);
            remaining -= fromBowl3;
        }
        if (remaining > 0) {
            throw new IllegalStateException("파워 토큰이 부족합니다");
        }
    }

    /**
     * 즉시 효과 적용
     */
    private void applyImmediateEffect(UUID gameId, GamePlayerState ps, ArtifactType artifact) {
        // 고정 자원 지급
        var reward = artifact.getImmediateReward();
        ps.addCredit(reward.credits());
        ps.addOre(reward.ore());
        ps.addKnowledge(reward.knowledge());
        ps.addQic(reward.qic());
        if (reward.vp() > 0) {
            ps.addVP(reward.vp());
            vpLogService.logVp(ps.getGameId(), ps.getPlayerId(), VpCategory.ARTIFACT, reward.vp(), null, "Artifact immediate reward VP");
        }
        // powerBowl3 추가는 applyIncome으로 처리
        if (reward.powerBowl1() > 0 || reward.powerBowl2() > 0 || reward.powerBowl3() > 0 || reward.powerCharge() > 0) {
            ps.applyIncome(new com.gaiaproject.dto.ResourcesVo(0, 0, 0, 0,
                    reward.powerBowl1(), reward.powerBowl2(), reward.powerBowl3(), reward.powerCharge(), 0, null));
        }

        // 동적 VP 계산
        String special = artifact.getSpecialEffect();
        if (special == null) return;

        switch (special) {
            case "VP_PER_DEEP_SECTOR_WITH_BUILDING" -> {
                // 깊은 구역(건물 1개 이상)당 3VP
                List<GameBuilding> myBuildings = buildingRepository.findByGameIdAndPlayerId(gameId, ps.getPlayerId());
                Set<String> deepSectors = new HashSet<>();
                for (GameBuilding b : myBuildings) {
                    GameHex hex = hexRepository.findByGameIdAndHexQAndHexR(gameId, b.getHexQ(), b.getHexR()).orElse(null);
                    if (hex != null && hex.getSectorId() != null && hex.getSectorId().startsWith("DEEP_")) {
                        deepSectors.add(hex.getSectorId());
                    }
                }
                int vp = deepSectors.size() * 3;
                ps.addVP(vp);
                vpLogService.logVp(ps.getGameId(), ps.getPlayerId(), VpCategory.ARTIFACT, vp, null, "VP per deep sector with building");
                log.info("[ARTIFACT_6] 깊은 구역 {}개 × 3VP = {}VP", deepSectors.size(), vp);
            }
            case "ADD_ASTEROID_PLANET_TYPE", "ADD_PRIMITIVE_PLANET_TYPE" -> {
                // 7VP는 immediateReward에서 이미 지급됨
                // 광산 건설로 간주 → 라운드 점수 적용
                var game = gameRepository.findById(gameId).orElse(null);
                if (game != null && game.getCurrentRound() != null) {
                    roundScoringService.award(gameId, game.getCurrentRound(), ps, RoundScoringEvent.MINE_PLACED, 1);
                    // 기오덴 PI: 새 행성 유형 개척 시 +3k
                    if (ps.getFactionType() == com.gaiaproject.domain.enumtype.player.FactionType.GEODENS
                            && ps.getStockPlanetaryInstitute() == 0) {
                        ps.addKnowledge(3);
                        log.info("[ARTIFACT] 기오덴 PI 새 행성 유형 +3k");
                    }
                }
                log.info("[ARTIFACT] {} 행성 유형 추가", special);
            }
            case "VP_PER_KNOWLEDGE_TRACK_LEVEL" -> {
                int vp = ps.getTechScience() * 3;
                ps.addVP(vp);
                vpLogService.logVp(ps.getGameId(), ps.getPlayerId(), VpCategory.ARTIFACT, vp, null, "VP per knowledge track level");
                log.info("[ARTIFACT_9] 지식 트랙 레벨 {} × 3VP = {}VP", ps.getTechScience(), vp);
            }
            case "VP_PER_GAIA_TRACK_LEVEL" -> {
                int vp = ps.getTechGaia() * 3;
                ps.addVP(vp);
                vpLogService.logVp(ps.getGameId(), ps.getPlayerId(), VpCategory.ARTIFACT, vp, null, "VP per gaia track level");
                log.info("[ARTIFACT_10] 가이아 트랙 레벨 {} × 3VP = {}VP", ps.getTechGaia(), vp);
            }
            case "VP_PER_TRACK_LEVEL_3_PLUS" -> {
                int count = 0;
                if (ps.getTechTerraforming() >= 3) count++;
                if (ps.getTechNavigation() >= 3) count++;
                if (ps.getTechAi() >= 3) count++;
                if (ps.getTechGaia() >= 3) count++;
                if (ps.getTechEconomy() >= 3) count++;
                if (ps.getTechScience() >= 3) count++;
                int vp = count * 3;
                ps.addVP(vp);
                vpLogService.logVp(ps.getGameId(), ps.getPlayerId(), VpCategory.ARTIFACT, vp, null, "VP per track level 3+");
                log.info("[ARTIFACT_11] 레벨3+트랙 {}개 × 3VP = {}VP", count, vp);
            }
            case "VP_PER_PLANET_TYPE_PLUS_3" -> {
                // 행성 유형당 1VP + 3VP
                List<GameBuilding> ptBuildings = buildingRepository.findByGameIdAndPlayerId(gameId, ps.getPlayerId());
                Set<String> planetTypes = new HashSet<>();
                for (GameBuilding b : ptBuildings) {
                    if (b.getBuildingType() == BuildingType.GAIAFORMER || b.isLantidsMine()) continue;
                    GameHex hex = hexRepository.findByGameIdAndHexQAndHexR(gameId, b.getHexQ(), b.getHexR()).orElse(null);
                    if (hex != null && hex.getPlanetType() != com.gaiaproject.domain.enumtype.player.PlanetType.EMPTY
                            && hex.getPlanetType() != com.gaiaproject.domain.enumtype.player.PlanetType.TRANSDIM) {
                        planetTypes.add(hex.getPlanetType().name());
                    }
                }
                int vp = planetTypes.size() + 3;
                ps.addVP(vp);
                vpLogService.logVp(ps.getGameId(), ps.getPlayerId(), VpCategory.ARTIFACT, vp, null, "행성 유형 " + planetTypes.size() + "종 + 3VP");
                log.info("[ARTIFACT_12] 행성 유형 {}종 + 3VP = {}VP", planetTypes.size(), vp);
            }
            case "FEDERATION_TOKEN_DOUBLE_USE" -> {
                // 특수: 사용한 연방 토큰 하나를 다시 사용 가능하게 뒤집기
                // 이 효과는 획득 즉시가 아니라 게임 중 선택 사용이므로 여기서는 패스
                log.info("[ARTIFACT_13] 연방 토큰 더블 유즈 획득");
            }
        }
    }

    /** 플레이어가 보유한 인공물 목록 */
    public List<GamePlayerArtifact> getPlayerArtifacts(UUID gameId, UUID playerId) {
        return artifactRepository.findByGameIdAndPlayerId(gameId, playerId);
    }

    /** 플레이어가 보유한 인공물 개수 */
    public int getArtifactCount(UUID gameId, UUID playerId) {
        return artifactRepository.countByGameIdAndPlayerId(gameId, playerId);
    }
}
