package com.gaiaproject.repository.federation;

import com.gaiaproject.domain.entity.federation.GameFederationGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GameFederationGroupRepository extends JpaRepository<GameFederationGroup, UUID> {
    List<GameFederationGroup> findByGameIdAndPlayerId(UUID gameId, UUID playerId);
    List<GameFederationGroup> findByGameId(UUID gameId);
}
