package com.gaiaproject.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * 좌석 선택 전에 보여줄 게임 "공개 상태" 응답 DTO.
 * - 지금은 seats 중심
 * - 나중에 map/booster/techTile도 확장
 */
@Schema(description = "게임 공개 상태 응답")
public record GamePublicStateResponse(
        UUID roomId,
        String status,
        Integer currentRound,
        String economyTrackOption,  // OPTION_A 또는 OPTION_B (게임 시작 후)

        @Schema(description = "게임 페이즈 (SETUP_MINE_FIRST, SETUP_MINE_SECOND, PLAYING)")
        String gamePhase,

        @Schema(description = "초기 광산 배치 시 다음 배치할 좌석 번호")
        Integer nextSetupSeatNo,

        @Schema(description = "현재 턴 좌석 번호 (PLAYING 페이즈)")
        Integer currentTurnSeatNo,

        @Schema(description = "팅커로이드의 추가 3삽 행성 (없으면 null)")
        String tinkeroidsExtraRingPlanet,

        @Schema(description = "모웨이드의 추가 3삽 행성 (없으면 null)")
        String moweidsExtraRingPlanet,

        @Schema(description = "특수 페이즈 대기 중인 플레이어 ID (ITARS_GAIA_PHASE, TINKEROIDS_ACTION_PHASE)")
        String pendingSpecialPlayerId,

        @Schema(description = "특수 페이즈 부가 정보 (아이타: 선택 가능 횟수, 팅커로이드: 선택 가능 액션 목록)")
        java.util.Map<String, Object> pendingSpecialData,

        List<SeatView> seats
) {
    /**
     * 좌석(턴) 미리보기 정보
     *
     * @param seatNo         좌석 번호(1~4)
     * @param turnOrder      실제 턴 순서(1~4)
     * @param raceCode       종족 코드(Enum name)
     * @param raceNameKo     종족 한글 이름
     * @param homePlanetType 고향 행성 타입 코드(Enum name)
     * @param playerId       좌석을 선점한 플레이어 id(없으면 null)
     * @param nickname       플레이어 닉네임(없으면 null)
     */
    public record SeatView(
            int seatNo,
            int turnOrder,
            String raceCode,
            String raceNameKo,
            String homePlanetType,
            UUID playerId,
            String nickname
    ) {}
}
