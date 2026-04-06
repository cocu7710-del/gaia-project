package com.gaiaproject.repository.game;

import com.gaiaproject.domain.entity.game.GameAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GameActionRepository extends JpaRepository<GameAction, UUID> {

    /**
     * 특정 라운드의 액션 조회
     */
    List<GameAction> findByGameIdAndRoundNumber(UUID gameId, Integer roundNumber);

    /**
     * 특정 플레이어의 액션 조회
     */
    List<GameAction> findByGameIdAndPlayerId(UUID gameId, UUID playerId);

    List<GameAction> findByGameIdOrderByCreatedAtAsc(UUID gameId);
}
