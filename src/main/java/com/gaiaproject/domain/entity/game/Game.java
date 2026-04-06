package com.gaiaproject.domain.entity.game;

import com.gaiaproject.domain.enumtype.tech.CommonAdvTileConditionType;
import com.gaiaproject.domain.enumtype.tech.EconomyTrackOption;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 게임 1개를 나타내는 엔티티.
 * - 단일 게임이라도 확장/리셋 대비해서 UUID 유지
 */
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
@Entity
@Table(name = "game")
public class Game {

    /** 게임 PK */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 게임 상태(READY/IN_PROGRESS/FINISHED) */
    @Column(nullable = false, length = 30)
    private String status;

    @Column(length = 100)
    private String title;

    /** 방 참가 코드(짧은 코드) */
    @Column(name = "room_code", length = 12)
    private String roomCode;

    /** 최대 인원(고정 4) */
    @Column(name = "max_players", nullable = false)
    private int maxPlayers;

    /** 현재 라운드 (1~6) */
    @Column(name = "current_round")
    private Integer currentRound;

    /** 현재 턴 좌석 번호 (1~4) */
    @Column(name = "current_turn_seat_no")
    private Integer currentTurnSeatNo;

    /** 경제 트랙 옵션 (OPTION_A / OPTION_B) */
    @Enumerated(EnumType.STRING)
    @Column(name = "economy_track_option", length = 20)
    private EconomyTrackOption economyTrackOption;

    /** COMMON 고급 기술 타일 획득 조건 (VP_25 / FLEET_3) */
    @Enumerated(EnumType.STRING)
    @Column(name = "common_adv_tile_condition", length = 20)
    private CommonAdvTileConditionType commonAdvTileCondition;

    /** 게임 페이즈 (SETUP_MINE_FIRST, SETUP_MINE_SECOND, PLAYING) */
    @Column(name = "game_phase", length = 30)
    private String gamePhase;

    /** 초기 광산 배치 인덱스 (배치 순서 배열의 현재 위치) */
    @Column(name = "setup_mine_index")
    private Integer setupMineIndex;

    /** 초기 광산 배치 순서 (JSON 배열: [1,2,3,4,3,2,1,4,...]) */
    @Column(name = "setup_mine_order", columnDefinition = "TEXT")
    private String setupMineOrder;

    /**
     * 팅커로이드의 추가 3삽 행성 (모웨이드/스페이스자이언트가 플레이 중일 때 랜덤 할당)
     * 예: "TERRA", "ICE" 등 PlanetType name
     */
    @Column(name = "tinkeroids_extra_ring_planet", length = 20)
    private String tinkeroidsExtraRingPlanet;

    /**
     * 모웨이드의 추가 3삽 행성 (다카니안/팅커로이드가 플레이 중일 때 랜덤 할당)
     * 팅커로이드와 동일 행성 불가
     */
    @Column(name = "moweids_extra_ring_planet", length = 20)
    private String moweidsExtraRingPlanet;

    /** 비딩 라운드 (1~3, 0=비딩 없음) */
    @Column(name = "bidding_round", nullable = false)
    private int biddingRound = 0;

    /** 현재 비딩 최고액 */
    @Column(name = "bidding_current_bid", nullable = false)
    private int biddingCurrentBid = 0;

    /** 현재 비딩 턴 플레이어 */
    @Column(name = "bidding_turn_player_id")
    private UUID biddingTurnPlayerId;

    /** 생성 시각 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** 수정 시각 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 낙관적 락 버전 */
    @Version
    @Column(nullable = false)
    private long version;

    /**
     * 게임 생성 팩토리.
     * - status는 READY로 시작
     */
    public static Game createRoom(String title, String roomCode) {
        Game g = new Game();
        g.status = "READY";
        g.title = title;
        g.roomCode = roomCode;
        g.maxPlayers = 4;
        g.createdAt = LocalDateTime.now();
        g.updatedAt = LocalDateTime.now();
        return g;
    }
    /** 상태 변경(필요 시) */
    public void changeStatus(String newStatus) {
        this.status = newStatus;
        this.updatedAt = LocalDateTime.now();
    }

    /** 게임 시작 */
    public void startGame() {
        this.status = "IN_PROGRESS";
        this.currentRound = 1;
        this.currentTurnSeatNo = 1;
        this.economyTrackOption = EconomyTrackOption.random();
        this.commonAdvTileCondition = CommonAdvTileConditionType.random();
        this.updatedAt = LocalDateTime.now();
    }

    /** 다음 턴으로 이동 */
    public void nextTurn(int nextSeatNo) {
        this.currentTurnSeatNo = nextSeatNo;
        this.updatedAt = LocalDateTime.now();
    }

    /** 다음 라운드로 이동 */
    public void nextRound() {
        this.currentRound++;
        this.currentTurnSeatNo = 1;
        this.updatedAt = LocalDateTime.now();
    }

    /** 맵 회전 대기 페이즈 시작 (4명 입장 후, 비딩 전) */
    public void startMapRotate() {
        this.gamePhase = "MAP_ROTATE";
        this.updatedAt = LocalDateTime.now();
    }

    /** 초기 광산 배치 시작 (배치 순서 JSON 설정) */
    public void startInitialMinePlacement(String setupOrder) {
        this.setupMineOrder = setupOrder;
        this.setupMineIndex = 0;
        this.gamePhase = "SETUP_MINE_FIRST";
        this.updatedAt = LocalDateTime.now();
    }

    /** 현재 배치할 좌석 번호 조회 (JSON 배열에서 읽기) */
    public int getCurrentSetupSeatNo() {
        if (setupMineIndex == null || setupMineOrder == null) {
            return 0;
        }

        try {
            // JSON 배열 파싱: "[1,2,3,4,3,2,1]" -> List<Integer>
            String cleaned = setupMineOrder.replaceAll("[\\[\\]\\s]", "");
            String[] parts = cleaned.split(",");

            if (setupMineIndex >= parts.length) {
                return 0;
            }

            return Integer.parseInt(parts[setupMineIndex]);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 다음 광산 배치 턴으로 이동 */
    public void nextMinePlacement() {
        if (setupMineOrder == null) {
            return;
        }

        this.setupMineIndex++;

        // 배치 순서 총 개수 확인
        String cleaned = setupMineOrder.replaceAll("[\\[\\]\\s]", "");
        String[] parts = cleaned.split(",");
        int totalSetupTurns = parts.length;

        if (this.setupMineIndex >= totalSetupTurns) {
            // 모든 초기 배치 완료 → 부스터 선택 단계로
            this.gamePhase = "BOOSTER_SELECTION";
            this.setupMineIndex = null;
            this.setupMineOrder = null;
        } else {
            // 페이즈 업데이트 (간단하게 인덱스 기반)
            if (this.setupMineIndex == 4) {
                this.gamePhase = "SETUP_MINE_SECOND";
            } else if (this.setupMineIndex == 8) {
                this.gamePhase = "SETUP_MINE_XENOS";
            } else if (this.setupMineIndex > 8) {
                this.gamePhase = "SETUP_MINE_EXPANSION";
            }
        }

        this.updatedAt = LocalDateTime.now();
    }

    /** 팅커로이드/모웨이드 추가 3삽 행성 할당 */
    public void assignExtraRingPlanets(String tinkeroidsExtra, String moweidsExtra) {
        this.tinkeroidsExtraRingPlanet = tinkeroidsExtra;
        this.moweidsExtraRingPlanet = moweidsExtra;
        this.updatedAt = LocalDateTime.now();
    }

    /** 초기 광산 배치 중인지 확인 */
    public boolean isInSetupPhase() {
        return setupMineIndex != null && setupMineOrder != null;
    }

    /** 게임 페이즈 설정 */
    public void setGamePhase(String phase) {
        this.gamePhase = phase;
        this.updatedAt = LocalDateTime.now();
    }

    /** 부스터 선택 단계인지 확인 */
    public boolean isInBoosterSelectionPhase() {
        return "BOOSTER_SELECTION".equals(gamePhase);
    }

    /** 비딩 시작 */
    public void startBidding() {
        this.gamePhase = "BIDDING";
        this.biddingRound = 1;
        this.biddingCurrentBid = 0;
        this.updatedAt = LocalDateTime.now();
    }

    /** 비딩 낙찰 → 좌석 선택 대기 */
    public void enterBidSeatPick(UUID winnerPlayerId) {
        this.gamePhase = "BID_SEAT_PICK";
        this.biddingTurnPlayerId = winnerPlayerId;
        this.updatedAt = LocalDateTime.now();
    }

    /** 다음 비딩 라운드 */
    public void nextBiddingRound(UUID firstTurnPlayerId) {
        this.gamePhase = "BIDDING";
        this.biddingRound++;
        this.biddingCurrentBid = 0;
        this.biddingTurnPlayerId = firstTurnPlayerId;
        this.updatedAt = LocalDateTime.now();
    }

    /** 비딩 종료 */
    public void finishBidding() {
        this.gamePhase = null;
        this.biddingRound = 0;
        this.biddingCurrentBid = 0;
        this.biddingTurnPlayerId = null;
        this.updatedAt = LocalDateTime.now();
    }

    public void setBiddingCurrentBid(int bid) {
        this.biddingCurrentBid = bid;
    }

    public void setBiddingTurnPlayerId(UUID playerId) {
        this.biddingTurnPlayerId = playerId;
        this.updatedAt = LocalDateTime.now();
    }

    /** 부스터 선택 완료 → 실제 게임 시작 */
    public void startPlaying() {
        this.gamePhase = "PLAYING";
        this.updatedAt = LocalDateTime.now();
    }
}
