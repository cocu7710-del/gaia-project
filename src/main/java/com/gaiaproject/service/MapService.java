package com.gaiaproject.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiaproject.domain.entity.map.GameHex;
import com.gaiaproject.domain.entity.map.GameSectorPlacement;
import com.gaiaproject.domain.entity.map.GameSingleHexPlacement;
import com.gaiaproject.domain.enumtype.map.MapPosition;
import com.gaiaproject.domain.enumtype.map.SectorType;
import com.gaiaproject.domain.enumtype.map.SingleHexTileType;
import com.gaiaproject.domain.enumtype.player.PlanetType;
import com.gaiaproject.domain.entity.game.GameSeat;
import com.gaiaproject.repository.game.GameSeatRepository;
import com.gaiaproject.repository.map.GameHexRepository;
import com.gaiaproject.repository.map.GameSectorPlacementRepository;
import com.gaiaproject.repository.map.GameSingleHexPlacementRepository;
import com.gaiaproject.util.HexUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class MapService {

    private final GameSectorPlacementRepository sectorPlacementRepository;
    private final GameSingleHexPlacementRepository singleHexPlacementRepository;
    private final GameHexRepository gameHexRepository;
    private final GameSeatRepository gameSeatRepository;
    private final com.gaiaproject.repository.game.GameRepository gameRepository;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 잊혀진 함대 우주선 배치 금지 인접 관계 (실제 헥스 좌표 거리 ≤ 3)
     *
     * 포지션 배치 구조 (링 형태):
     *   1 - 2 - 3 - 4 - 5
     *   |               |
     *  10 - 9 - 8 - 7 - 6
     *
     * 링: 1-2-3-4-5-6-7-8-9-10-1 (각 거리 3)
     * 추가 코드: (3,8) - 헥스 좌표 실제 거리 3
     */
    private static final Map<Integer, Set<Integer>> FLEET_ADJACENCY;
    static {
        int[][] edges = {
            {1,2},{2,3},{3,4},{4,5},{5,6},{6,7},{7,8},{8,9},{9,10},{10,1}, // 링
            {3,8}  // 추가 코드: 실제 헥스 거리 3
        };
        Map<Integer, Set<Integer>> map = new HashMap<>();
        for (int i = 1; i <= 10; i++) map.put(i, new HashSet<>());
        for (int[] e : edges) {
            map.get(e[0]).add(e[1]);
            map.get(e[1]).add(e[0]);
        }
        FLEET_ADJACENCY = Collections.unmodifiableMap(map);
    }

    // 각도: 0, 60, 120, 180, 240, 300 (헥스 좌표계 60도 단위)
    private static final int[] ROTATION_ANGLES = {0, 60, 120, 180, 240, 300};

    /**
     * 맵 섹터 전체 초기화
     */
    public void setupMapSectors(UUID gameId) {
        // 1. 기본 섹터 배치 (1~10번 섹터 → 위치 1~10)
        setupBasicSectors(gameId);

        // 2. 딥 섹터 배치 (8개)
        setupDeepSectors(gameId);

        // 3. 1헥스 타일 배치
        setupSingleHexTiles(gameId);

        // 4. 모든 헥스를 글로벌 좌표로 변환하여 저장
        generateGlobalHexes(gameId);
    }

    /**
     * 섹터 60도 회전 (캐릭터 선택 전에만 가능)
     */
    public void rotateSector(UUID gameId, int positionNo) {
        // 1. 게임 시작 여부 확인 - gamePhase가 설정되면 회전 불가
        var game = gameRepository.findById(gameId)
                .orElseThrow(() -> new IllegalArgumentException("게임을 찾을 수 없습니다"));
        if (!"MAP_ROTATE".equals(game.getGamePhase())) {
            throw new IllegalStateException("맵 회전은 4명 입장 후 비딩 시작 전에만 가능합니다.");
        }

        // 2. 섹터 배치 조회
        GameSectorPlacement placement = sectorPlacementRepository.findByGameIdAndPositionNo(gameId, positionNo)
                .orElseThrow(() -> new IllegalArgumentException("섹터를 찾을 수 없습니다: positionNo=" + positionNo));

        // 3. 회전 적용
        placement.rotateBy60();
        sectorPlacementRepository.save(placement);

        // 4. 기존 헥스 삭제 후 재생성
        gameHexRepository.deleteByGameIdAndPositionNo(gameId, positionNo);

        MapPosition mapPos = positionNo <= 10
                ? MapPosition.getSectorPosition(positionNo)
                : MapPosition.getDeepSectorPosition(positionNo);
        saveSectorHexes(gameId, placement.getSectorType(), mapPos, placement.getRotation());

        log.info("섹터 회전 완료: gameId={}, positionNo={}, newRotation={}", gameId, positionNo, placement.getRotation());
    }

    /**
     * 기본 섹터 배치
     * - 1~4번 섹터 중 2개를 랜덤으로 5, 6번 위치에 배치
     * - 나머지 섹터들은 남은 위치에 랜덤 배치
     */
    private void setupBasicSectors(UUID gameId) {
        // 섹터 1~10 가져오기
        List<SectorType> basicSectors = new ArrayList<>(SectorType.getBasicSectors());

        // 1~4번 섹터 (인덱스 0~3)
        List<SectorType> sectors1to4 = new ArrayList<>(basicSectors.subList(0, 4));
        // 5~10번 섹터 (인덱스 4~9)
        List<SectorType> sectors5to10 = new ArrayList<>(basicSectors.subList(4, 10));

        // 1~4번 섹터 중 2개 랜덤 선택 → 5, 6번 위치에 배치
        Collections.shuffle(sectors1to4);
        SectorType sectorForPos5 = sectors1to4.remove(0);
        SectorType sectorForPos6 = sectors1to4.remove(0);

        // 남은 1~4번 섹터 2개 + 5~10번 섹터 6개 = 8개 → 1~4, 7~10 위치에 배치
        List<SectorType> remainingSectors = new ArrayList<>();
        remainingSectors.addAll(sectors1to4);  // 남은 2개
        remainingSectors.addAll(sectors5to10); // 6개
        Collections.shuffle(remainingSectors);

        // 위치 배열: 1, 2, 3, 4, 7, 8, 9, 10
        int[] otherPositions = {1, 2, 3, 4, 7, 8, 9, 10};

        // 5, 6번 위치 저장
        saveSectorPlacement(gameId, 5, sectorForPos5);
        saveSectorPlacement(gameId, 6, sectorForPos6);

        // 나머지 위치 저장
        for (int i = 0; i < remainingSectors.size(); i++) {
            saveSectorPlacement(gameId, otherPositions[i], remainingSectors.get(i));
        }
    }

    /**
     * 딥 섹터 배치
     * - 1~8번 딥 섹터에서 같은 번호가 겹치지 않게 FRONT 또는 BACK 랜덤 선택
     * - 회전: MapPosition에 정의된 고정 회전값 사용
     */
    private void setupDeepSectors(UUID gameId) {
        List<SectorType> selectedDeepSectors = new ArrayList<>();

        // 딥 섹터 1~8 각각에서 FRONT 또는 BACK 랜덤 선택
        for (int i = 1; i <= 8; i++) {
            boolean useFront = RANDOM.nextBoolean();
            String sectorName = useFront
                    ? "DEEP_SECTOR_" + i + "_FRONT"
                    : "DEEP_SECTOR_" + i + "_BACK";
            selectedDeepSectors.add(SectorType.valueOf(sectorName));
        }

        // 순서 랜덤으로 섞기
        Collections.shuffle(selectedDeepSectors);

        // 위치 11~18에 저장 (MapPosition의 기본 회전값 사용)
        for (int i = 0; i < selectedDeepSectors.size(); i++) {
            int positionNo = 11 + i;
            MapPosition mapPos = MapPosition.getDeepSectorPosition(positionNo);
            saveSectorPlacementWithRotation(gameId, positionNo, selectedDeepSectors.get(i), mapPos.getDefaultRotation());
        }
    }

    /**
     * 섹터 배치 저장 (회전값 지정)
     */
    private void saveSectorPlacementWithRotation(UUID gameId, int positionNo, SectorType sectorType, int rotation) {
        GameSectorPlacement placement = GameSectorPlacement.create(
                gameId,
                positionNo,
                sectorType,
                rotation
        );
        sectorPlacementRepository.save(placement);
    }

    /**
     * 1헥스 타일 배치
     * - 잊혀진 함대 우주선은 서로 3헥스 이내 포지션에 배치 불가
     * - 나머지 타일은 남은 포지션에 랜덤 배치
     */
    private void setupSingleHexTiles(UUID gameId) {
        List<SingleHexTileType> fleetTiles = Arrays.stream(SingleHexTileType.values())
                .filter(SingleHexTileType::isForgottenFleet)
                .collect(Collectors.toCollection(ArrayList::new));

        List<SingleHexTileType> otherTiles = Arrays.stream(SingleHexTileType.values())
                .filter(t -> !t.isForgottenFleet())
                .collect(Collectors.toCollection(ArrayList::new));

        Collections.shuffle(fleetTiles, RANDOM);
        Collections.shuffle(otherTiles, RANDOM);

        // 함대 우주선 배치 포지션 선택 (인접 포지션 배제)
        List<Integer> fleetPositions = findValidFleetPositions(fleetTiles.size());

        Map<Integer, SingleHexTileType> positionTileMap = new HashMap<>();
        for (int i = 0; i < fleetPositions.size(); i++) {
            positionTileMap.put(fleetPositions.get(i), fleetTiles.get(i));
        }

        // 나머지 포지션에 기타 타일 랜덤 배치
        List<Integer> remainingPositions = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            if (!positionTileMap.containsKey(i)) remainingPositions.add(i);
        }
        Collections.shuffle(remainingPositions, RANDOM);
        for (int i = 0; i < otherTiles.size(); i++) {
            positionTileMap.put(remainingPositions.get(i), otherTiles.get(i));
        }

        for (int pos = 1; pos <= 10; pos++) {
            SingleHexTileType tile = positionTileMap.get(pos);
            if (tile != null) {
                singleHexPlacementRepository.save(GameSingleHexPlacement.create(gameId, pos, tile));
            }
        }
    }

    /**
     * 잊혀진 함대 우주선이 서로 인접하지 않도록 배치 포지션 선택
     * FLEET_ADJACENCY 기준으로 이미 선택된 포지션의 인접 포지션은 제외
     */
    private List<Integer> findValidFleetPositions(int count) {
        List<Integer> allPositions = new ArrayList<>(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10));

        while (true) {
            Collections.shuffle(allPositions, RANDOM);
            List<Integer> selected = new ArrayList<>();
            Set<Integer> blocked = new HashSet<>();

            for (int pos : allPositions) {
                if (!blocked.contains(pos)) {
                    selected.add(pos);
                    blocked.addAll(FLEET_ADJACENCY.getOrDefault(pos, Set.of()));
                    if (selected.size() == count) break;
                }
            }

            if (selected.size() == count) return selected;
        }
    }

    /**
     * 섹터 배치 저장
     */
    private void saveSectorPlacement(UUID gameId, int positionNo, SectorType sectorType) {
        int rotation = getRandomRotation();

        GameSectorPlacement placement = GameSectorPlacement.create(
                gameId,
                positionNo,
                sectorType,
                rotation
        );
        sectorPlacementRepository.save(placement);
    }

    /**
     * 랜덤 각도 반환 (0, 60, 120, 180, 240, 300)
     */
    private int getRandomRotation() {
        return ROTATION_ANGLES[RANDOM.nextInt(ROTATION_ANGLES.length)];
    }

    /**
     * 모든 헥스를 글로벌 좌표로 변환하여 저장
     */
    private void generateGlobalHexes(UUID gameId) {
        // 1. 기본 섹터 헥스 저장
        List<GameSectorPlacement> sectorPlacements = sectorPlacementRepository.findByGameIdOrderByPositionNoAsc(gameId);
        for (GameSectorPlacement placement : sectorPlacements) {
            int positionNo = placement.getPositionNo();

            if (positionNo <= 10) {
                // 기본 섹터
                MapPosition mapPos = MapPosition.getSectorPosition(positionNo);
                saveSectorHexes(gameId, placement.getSectorType(), mapPos, placement.getRotation());
            } else {
                // Deep 섹터 (11~18)
                MapPosition mapPos = MapPosition.getDeepSectorPosition(positionNo);
                saveSectorHexes(gameId, placement.getSectorType(), mapPos, placement.getRotation());
            }
        }

        // 2. 1헥스 타일 저장
        List<GameSingleHexPlacement> singleHexPlacements = singleHexPlacementRepository.findByGameIdOrderByPositionNoAsc(gameId);
        for (GameSingleHexPlacement placement : singleHexPlacements) {
            MapPosition mapPos = MapPosition.getSingleHexPosition(placement.getPositionNo());
            saveSingleHex(gameId, placement.getTileTypeEnum(), mapPos);
        }

        log.info("게임 헥스 생성 완료: gameId={}", gameId);
    }

    /**
     * 섹터의 모든 헥스를 글로벌 좌표로 변환하여 저장
     */
    private void saveSectorHexes(UUID gameId, SectorType sectorType, MapPosition mapPos, int rotation) {
        List<HexData> hexes = parseSectorHexes(sectorType.getHexesJson());

        for (HexData hex : hexes) {
            // 로컬 좌표 → 글로벌 좌표 변환
            int[] global = HexUtil.toGlobal(
                    hex.q, hex.r,
                    mapPos.getOffsetQ(), mapPos.getOffsetR(),
                    rotation
            );

            PlanetType planetType = PlanetType.valueOf(hex.planet);

            GameHex gameHex = GameHex.create(
                    gameId,
                    global[0],
                    global[1],
                    planetType,
                    sectorType.name(),
                    mapPos.getPositionNo()
            );
            gameHexRepository.save(gameHex);
        }
    }

    /**
     * 1헥스 타일 저장
     */
    private void saveSingleHex(UUID gameId, SingleHexTileType tileType, MapPosition mapPos) {
        String planetTypeStr = tileType.getPlanetType();

        // 잊혀진 함대는 특수 타입이므로 EMPTY로 처리하거나 별도 처리
        PlanetType planetType;
        try {
            planetType = PlanetType.valueOf(planetTypeStr);
        } catch (IllegalArgumentException e) {
            // 잊혀진 함대 타입 (TF_MARS, REBELLION, ECLIPSE, TWILIGHT)은 EMPTY로 저장
            // 실제 함대 정보는 game_single_hex_placement에서 관리
            planetType = PlanetType.EMPTY;
        }

        GameHex gameHex = GameHex.create(
                gameId,
                mapPos.getOffsetQ(),
                mapPos.getOffsetR(),
                planetType,
                tileType.name(),
                mapPos.getPositionNo()
        );
        gameHexRepository.save(gameHex);
    }

    /**
     * 섹터 JSON 파싱
     */
    private List<HexData> parseSectorHexes(String hexesJson) {
        try {
            return OBJECT_MAPPER.readValue(hexesJson, new TypeReference<List<HexData>>() {});
        } catch (Exception e) {
            log.error("섹터 헥스 JSON 파싱 실패: {}", hexesJson, e);
            return Collections.emptyList();
        }
    }

    /**
     * 헥스 데이터 DTO
     */
    private static class HexData {
        public int q;
        public int r;
        public String planet;
    }
}
