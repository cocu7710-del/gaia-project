package com.gaiaproject.repository.federation;

import com.gaiaproject.domain.entity.federation.GameFederationBuilding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GameFederationBuildingRepository extends JpaRepository<GameFederationBuilding, UUID> {
    List<GameFederationBuilding> findByFederationGroupId(UUID federationGroupId);
    List<GameFederationBuilding> findByFederationGroupIdIn(List<UUID> groupIds);
}
