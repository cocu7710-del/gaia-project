package com.gaiaproject.service;

import com.gaiaproject.domain.entity.player.GamePlayerState;
import com.gaiaproject.domain.entity.rounds.GameRoundScoring;
import com.gaiaproject.domain.enumtype.rounds.RoundScoringEvent;
import com.gaiaproject.domain.enumtype.action.VpCategory;
import com.gaiaproject.domain.enumtype.rounds.RoundScoringTileType;
import com.gaiaproject.repository.rounds.GameRoundScoringRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 라운드 점수 타일 적용 서비스
 * - 액션 발생 시 현재 라운드 타일과 매칭되면 즉시 VP 지급
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RoundScoringService {

    private final GameRoundScoringRepository roundScoringRepository;
    private final VpLogService vpLogService;

    /**
     * 이벤트 발생 시 현재 라운드 점수 타일과 매칭되면 VP 지급
     *
     * @param gameId  게임 ID
     * @param round   현재 라운드 번호
     * @param ps      VP를 받을 플레이어 상태 (호출자가 save해야 함)
     * @param event   발생한 이벤트
     * @param count   이벤트 횟수 (예: 테라포밍 3단계 = 3)
     * @return 지급된 총 VP
     */
    public int award(UUID gameId, int round, GamePlayerState ps, RoundScoringEvent event, int count) {
        GameRoundScoring scoring = roundScoringRepository
                .findByGameIdAndRoundNumber(gameId, round)
                .orElse(null);
        if (scoring == null) return 0;

        RoundScoringTileType tile = scoring.getScoringTileCode();
        int vpPerEvent = tile.getVpForEvent(event);
        int totalVp = vpPerEvent * count;

        if (totalVp > 0) {
            ps.addVP(totalVp);
            vpLogService.logVp(ps.getGameId(), ps.getPlayerId(), VpCategory.ROUND_SCORING, totalVp, round, "라운드 미션 VP");
            log.info("[ROUND_SCORING] game={}, player={}, tile={}, event={}, count={}, vp=+{}",
                    gameId, ps.getPlayerId(), tile, event, count, totalVp);
        }
        return totalVp;
    }
}
