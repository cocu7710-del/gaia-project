package com.gaiaproject.service;

import com.gaiaproject.domain.entity.building.GameBuilding;
import com.gaiaproject.domain.entity.game.Game;
import com.gaiaproject.domain.entity.game.GameSeat;
import com.gaiaproject.domain.entity.map.GameHex;
import com.gaiaproject.domain.entity.player.GamePlayerState;
import com.gaiaproject.domain.enumtype.action.ActionType;
import com.gaiaproject.domain.enumtype.building.BuildingType;
import com.gaiaproject.domain.enumtype.player.PlanetType;
import com.gaiaproject.dto.request.DeployGaiaformerRequest;
import com.gaiaproject.dto.response.ConfirmActionResponse;
import com.gaiaproject.dto.response.DeployGaiaformerResponse;
import com.gaiaproject.repository.building.GameBuildingRepository;
import com.gaiaproject.repository.game.GameRepository;
import com.gaiaproject.repository.game.GameSeatRepository;
import com.gaiaproject.repository.map.GameHexRepository;
import com.gaiaproject.repository.player.GamePlayerStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 가이아 포밍 서비스
 * - 포머 배치: 차원변형(TRANSDIM) 행성에 포머 던지기
 * - 라운드 시작 시: TRANSDIM → GAIA 변환, 가이아 파워 bowl1 복귀
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class GaiaformingService {

    private final GameRepository gameRepository;
    private final GameSeatRepository gameSeatRepository;
    private final GamePlayerStateRepository playerStateRepository;
    private final GameBuildingRepository buildingRepository;
    private final GameHexRepository hexRepository;
    private final ActionService actionService;
    private final GameWebSocketService webSocketService;

    /**
     * 가이아 트랙 레벨 → 필요 파워 토큰 수
     * 1~2: 6, 3: 4, 4: 5, 5+: 4
     */
    public static int getRequiredPower(int gaiaLevel) {
        return switch (gaiaLevel) {
            case 1, 2 -> 6;
            case 3 -> 4;
            case 4, 5 -> 3;
            default -> 6;
        };
    }

    /**
     * 포머 배치 (차원변형 행성에 가이아포머 던지기)
     */
    public DeployGaiaformerResponse deployGaiaformer(UUID gameId, DeployGaiaformerRequest request) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new IllegalArgumentException("게임을 찾을 수 없습니다"));

        if (!"PLAYING".equals(game.getGamePhase())) {
            return DeployGaiaformerResponse.fail(gameId, "PLAYING 페이즈가 아닙니다");
        }

        GameSeat seat = gameSeatRepository.findByGameIdAndPlayerId(gameId, request.playerId())
                .orElseThrow(() -> new IllegalArgumentException("좌석 정보를 찾을 수 없습니다"));

        if (seat.getSeatNo() != game.getCurrentTurnSeatNo()) {
            return DeployGaiaformerResponse.fail(gameId, "현재 턴이 아닙니다");
        }

        GamePlayerState ps = playerStateRepository.findByGameIdAndPlayerId(gameId, request.playerId())
                .orElseThrow(() -> new IllegalStateException("플레이어 상태를 찾을 수 없습니다"));

        // 가이아 트랙 레벨 확인
        int gaiaLevel = ps.getTechGaia();
        if (gaiaLevel < 1) {
            return DeployGaiaformerResponse.fail(gameId, "가이아 트랙 레벨 1 이상이 필요합니다");
        }

        // 포머 재고 확인
        if (ps.getStockGaiaformer() < 1) {
            return DeployGaiaformerResponse.fail(gameId, "사용 가능한 가이아포머가 없습니다");
        }

        // 대상 헥스 확인
        GameHex hex = hexRepository.findByGameIdAndHexQAndHexR(gameId, request.hexQ(), request.hexR())
                .orElseThrow(() -> new IllegalArgumentException("헥스를 찾을 수 없습니다"));

        if (hex.getPlanetType() != PlanetType.TRANSDIM) {
            return DeployGaiaformerResponse.fail(gameId, "차원변형 행성에만 포머를 배치할 수 있습니다");
        }

        // 이미 건물이 있는지 확인
        if (buildingRepository.existsByGameIdAndHexQAndHexR(gameId, request.hexQ(), request.hexR())) {
            return DeployGaiaformerResponse.fail(gameId, "이미 건물이 있는 위치입니다");
        }

        int powerSpent = 0;
        if (request.isInstant()) {
            // 즉시 포밍 (BOOSTER_12): 파워 차감 없이 즉시 GAIA 변환
            hex.convertToGaia();
            hexRepository.save(hex);
        } else {
            // 일반 포밍: 파워 차감 후 GAIAFORMER 건물 배치 (다음 가이아 페이즈에 GAIA 변환)
            int required = getRequiredPower(gaiaLevel);
            try {
                ps.spendPowerToGaia(required);
            } catch (IllegalStateException e) {
                return DeployGaiaformerResponse.fail(gameId, e.getMessage());
            }
            powerSpent = required;
        }

        // 포머 재고 감소 (광산 건설 시 반환)
        ps.decreaseStockGaiaformer();

        // QIC 소모 (항법 거리 확장)
        if (request.qicUsed() > 0) {
            try {
                ps.spendQic(request.qicUsed());
            } catch (IllegalStateException e) {
                return DeployGaiaformerResponse.fail(gameId, e.getMessage());
            }
        }

        // GAIAFORMER 건물 배치
        GameBuilding gaiaformer = GameBuilding.place(gameId, request.playerId(),
                request.hexQ(), request.hexR(), BuildingType.GAIAFORMER);
        buildingRepository.save(gaiaformer);

        playerStateRepository.save(ps);

        log.info("포머 배치: game={}, player={}, ({},{}) gaiaLevel={}, powerSpent={}, isInstant={}",
                gameId, request.playerId(), request.hexQ(), request.hexR(), gaiaLevel, powerSpent, request.isInstant());

        String actionData = String.format("{\"hexQ\":%d,\"hexR\":%d,\"powerSpent\":%d,\"isInstant\":%b}",
                request.hexQ(), request.hexR(), powerSpent, request.isInstant());
        ConfirmActionResponse actionResult = actionService.saveActionAndNextTurn(
                gameId, request.playerId(), ActionType.DEPLOY_GAIAFORMER, actionData);

        webSocketService.broadcastStateUpdated(gameId);

        int nextTurn = actionResult.nextTurnSeatNo() != null ? actionResult.nextTurnSeatNo() : 0;
        return DeployGaiaformerResponse.success(gameId, request.hexQ(), request.hexR(), nextTurn);
    }

    /**
     * 라운드 시작 시 가이아 페이즈 처리:
     * 1. GAIAFORMER 건물이 있는 TRANSDIM 헥스 → GAIA 행성으로 변환
     * 2. 모든 플레이어의 가이아 구역 파워 → bowl1으로 복귀
     */
    public void processGaiaPhase(UUID gameId) {
        // 1. GAIAFORMER 건물 목록 조회
        List<GameBuilding> gaiaformers = buildingRepository.findByGameIdAndBuildingType(gameId, BuildingType.GAIAFORMER);

        for (GameBuilding gf : gaiaformers) {
            hexRepository.findByGameIdAndHexQAndHexR(gameId, gf.getHexQ(), gf.getHexR())
                    .ifPresent(hex -> {
                        if (hex.getPlanetType() == PlanetType.TRANSDIM) {
                            hex.convertToGaia();
                            hexRepository.save(hex);
                            log.info("TRANSDIM → GAIA 변환: game={}, ({},{})", gameId, gf.getHexQ(), gf.getHexR());
                        }
                    });
        }

        // 2. 모든 플레이어 가이아 파워 복귀
        returnAllGaiaPower(gameId);
    }

    /** TRANSDIM → GAIA 헥스 변환만 (가이아 파워 복귀 없이) */
    public void processGaiaPlanetConversion(UUID gameId) {
        List<GameBuilding> gaiaformers = buildingRepository.findByGameIdAndBuildingType(gameId, BuildingType.GAIAFORMER);
        for (GameBuilding gf : gaiaformers) {
            hexRepository.findByGameIdAndHexQAndHexR(gameId, gf.getHexQ(), gf.getHexR())
                    .ifPresent(hex -> {
                        if (hex.getPlanetType() == PlanetType.TRANSDIM) {
                            hex.convertToGaia();
                            hexRepository.save(hex);
                            log.info("TRANSDIM → GAIA 변환: game={}, ({},{})", gameId, gf.getHexQ(), gf.getHexR());
                        }
                    });
        }
    }

    /** 모든 플레이어 가이아 파워 복귀 */
    public void returnAllGaiaPower(UUID gameId) {
        List<GamePlayerState> players = playerStateRepository.findByGameId(gameId);
        for (GamePlayerState player : players) {
            if (player.getGaiaPower() > 0) {
                // factionType null일 경우 seat에서 조회하여 설정 (테란 bowl2 복귀 판단용)
                if (player.getFactionType() == null) {
                    gameSeatRepository.findByGameIdAndSeatNo(gameId, player.getSeatNo())
                        .ifPresent(s -> player.setFactionType(s.getFactionType()));
                }
                player.returnGaiaPower();
                playerStateRepository.save(player);
                log.info("가이아 파워 복귀: game={}, player={}, faction={}", gameId, player.getPlayerId(), player.getFactionType());
            }
        }
    }

    /** 특정 플레이어 제외 가이아 파워 복귀 */
    public void returnGaiaPowerExcept(UUID gameId, UUID excludePlayerId) {
        List<GamePlayerState> players = playerStateRepository.findByGameId(gameId);
        for (GamePlayerState player : players) {
            if (player.getGaiaPower() > 0 && !player.getPlayerId().equals(excludePlayerId)) {
                if (player.getFactionType() == null) {
                    gameSeatRepository.findByGameIdAndSeatNo(gameId, player.getSeatNo())
                        .ifPresent(s -> player.setFactionType(s.getFactionType()));
                }
                player.returnGaiaPower();
                playerStateRepository.save(player);
            }
        }
    }

    /** 특정 플레이어 잔여 가이아 파워 복귀 */
    public void returnGaiaPowerForPlayer(UUID gameId, UUID playerId) {
        GamePlayerState ps = playerStateRepository.findByGameIdAndPlayerId(gameId, playerId).orElse(null);
        if (ps != null && ps.getGaiaPower() > 0) {
            ps.returnGaiaPower();
            playerStateRepository.save(ps);
            log.info("아이타 잔여 가이아 복귀: game={}, player={}", gameId, playerId);
        }
    }
}
