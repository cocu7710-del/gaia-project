package com.gaiaproject.repository.tech;

import com.gaiaproject.domain.entity.tech.GameAdvTechOffer;
import com.gaiaproject.domain.enumtype.tech.AdvancedTechTileCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GameAdvTechOfferRepository  extends JpaRepository<GameAdvTechOffer, UUID> {

    List<GameAdvTechOffer> findByGameIdOrderByPosition(UUID gameId);

    Optional<GameAdvTechOffer> findByGameIdAndAdvTechTileCode(UUID gameId, AdvancedTechTileCode advTechTileCode);

    void deleteByGameId(UUID gameId);
}