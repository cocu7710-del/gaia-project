package com.gaiaproject.repository.building;

import com.gaiaproject.domain.entity.building.GameBuilding;
import com.gaiaproject.domain.enumtype.building.AcademyType;
import com.gaiaproject.domain.enumtype.building.BuildingType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GameBuildingRepository extends JpaRepository<GameBuilding, UUID> {

    /**
     * 특정 헥스에 건물이 있는지 확인
     */
    boolean existsByGameIdAndHexQAndHexR(UUID gameId, int hexQ, int hexR);

    /**
     * 특정 헥스의 메인 건물 조회 (란티다 기생 제외)
     */
    Optional<GameBuilding> findFirstByGameIdAndHexQAndHexRAndIsLantidsMine(UUID gameId, int hexQ, int hexR, boolean isLantidsMine);

    /**
     * 특정 헥스의 모든 건물 조회 (란티다 기생 포함)
     */
    List<GameBuilding> findAllByGameIdAndHexQAndHexR(UUID gameId, int hexQ, int hexR);

    /**
     * 게임의 모든 건물 조회
     */
    List<GameBuilding> findByGameId(UUID gameId);

    /**
     * 특정 플레이어의 건물 조회
     */
    List<GameBuilding> findByGameIdAndPlayerId(UUID gameId, UUID playerId);

    /**
     * 특정 플레이어의 특정 타입 건물 수
     */
    int countByGameIdAndPlayerIdAndBuildingType(UUID gameId, UUID playerId, BuildingType buildingType);

    /**
     * 게임 내 특정 타입 건물 전체 조회
     */
    List<GameBuilding> findByGameIdAndBuildingType(UUID gameId, BuildingType buildingType);

    /**
     * 특정 플레이어의 아카데미 종류별 수 조회
     */
    int countByGameIdAndPlayerIdAndBuildingTypeAndAcademyType(UUID gameId, UUID playerId, BuildingType buildingType, AcademyType academyType);
}
