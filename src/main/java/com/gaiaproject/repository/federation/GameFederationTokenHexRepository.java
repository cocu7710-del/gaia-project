package com.gaiaproject.repository.federation;

import com.gaiaproject.domain.entity.federation.GameFederationTokenHex;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GameFederationTokenHexRepository extends JpaRepository<GameFederationTokenHex, UUID> {
    List<GameFederationTokenHex> findByFederationGroupId(UUID federationGroupId);
    List<GameFederationTokenHex> findByFederationGroupIdIn(List<UUID> groupIds);
}
