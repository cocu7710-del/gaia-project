package com.gaiaproject.repository.game;

import com.gaiaproject.domain.entity.game.GameBid;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GameBidRepository extends JpaRepository<GameBid, UUID> {

    List<GameBid> findByGameId(UUID gameId);

    List<GameBid> findByGameIdOrderByCreatedAtAsc(UUID gameId);

    Optional<GameBid> findByGameIdAndPlayerId(UUID gameId, UUID playerId);

    List<GameBid> findByGameIdAndIsPassedFalseAndPickOrderIsNull(UUID gameId);

    List<GameBid> findByGameIdAndPickOrderIsNotNullOrderByPickOrderAsc(UUID gameId);
}
