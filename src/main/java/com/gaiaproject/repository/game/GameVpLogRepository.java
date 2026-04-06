package com.gaiaproject.repository.game;

import com.gaiaproject.domain.entity.game.GameVpLog;
import com.gaiaproject.domain.enumtype.action.VpCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface GameVpLogRepository extends JpaRepository<GameVpLog, UUID> {

    List<GameVpLog> findByGameId(UUID gameId);

    List<GameVpLog> findByGameIdAndPlayerId(UUID gameId, UUID playerId);

    @Query("SELECT v.playerId, v.category, SUM(v.amount) FROM GameVpLog v WHERE v.gameId = :gameId GROUP BY v.playerId, v.category")
    List<Object[]> sumByGameIdGroupByPlayerAndCategory(UUID gameId);
}
