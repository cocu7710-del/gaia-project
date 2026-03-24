package com.gaiaproject.controller;

import com.gaiaproject.domain.entity.tech.GameAdvTechOffer;
import com.gaiaproject.domain.entity.tech.GameTechOffer;
import com.gaiaproject.domain.enumtype.tech.AdvancedTechTileCode;
import com.gaiaproject.domain.enumtype.tech.TechCategoryType;
import com.gaiaproject.domain.enumtype.tech.TechTileCode;
import com.gaiaproject.dto.response.TechTrackResponse;
import com.gaiaproject.dto.response.TechTrackResponse.*;
import com.gaiaproject.service.TechTileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Tag(name = "Tech", description = "기술 트랙 및 타일 관련 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/rooms/{roomId}/tech")
public class TechController {

    private final TechTileService techTileService;
    private final com.gaiaproject.repository.tech.GamePlayerTechTileRepository playerTechTileRepository;

    @Operation(summary = "기술 트랙 및 타일 정보 조회")
    @GetMapping
    public ResponseEntity<TechTrackResponse> getTechTracks(@PathVariable UUID roomId) {
        // 1. 기술 트랙 정보 생성
        List<TechTrackInfo> tracks = new ArrayList<>();
        int position = 0;
        for (TechCategoryType category : TechCategoryType.values()) {
            // COMMON, EXPANSION은 트랙이 아님
            if (category == TechCategoryType.COMMON || category == TechCategoryType.EXPANSION) {
                continue;
            }

            List<TrackLevelInfo> levels = new ArrayList<>();
            for (int level = 0; level <= 5; level++) {
                levels.add(new TrackLevelInfo(
                        level,
                        getLevelDescription(category, level),
                        level == 5  // 레벨 5에 연방 토큰
                ));
            }

            tracks.add(new TechTrackInfo(
                    category.name(),
                    category.getDisplayName(),
                    position++,
                    levels
            ));
        }

        // 2. 이번 라운드 ACTION 사용 완료 타일 코드
        java.util.Set<String> actionUsedCodes = techTileService.getActionUsedTileCodes(roomId);

        // 3. 기본 기술 타일 조회 + 소유자 목록
        List<GameTechOffer> techOffers = techTileService.getTechTiles(roomId);
        // GamePlayerTechTile에서 기본 타일 소유자 매핑 (tileCode → playerIds)
        var allPlayerTiles = playerTechTileRepository.findByGameId(roomId);
        java.util.Map<String, List<String>> ownersByTileCode = new java.util.HashMap<>();
        for (var pt : allPlayerTiles) {
            ownersByTileCode.computeIfAbsent(pt.getTechTileCode(), k -> new ArrayList<>())
                    .add(pt.getPlayerId().toString());
        }
        List<TechTileInfo> basicTiles = techOffers.stream()
                .map(offer -> {
                    TechTileCode tileCode = offer.getTechTileCode();
                    List<String> owners = ownersByTileCode.getOrDefault(tileCode.name(), List.of());
                    return new TechTileInfo(
                            tileCode.name(),
                            offer.getTechTrack(),
                            offer.getPosition(),
                            tileCode.getAbility().getType().name(),
                            tileCode.getAbility().getDescription(),
                            offer.getTakenByPlayerId() != null,
                            offer.getTakenByPlayerId() != null ? offer.getTakenByPlayerId().toString() : null,
                            actionUsedCodes.contains(tileCode.name()),
                            owners
                    );
                })
                .toList();

        // 4. 고급 기술 타일 조회
        List<GameAdvTechOffer> advOffers = techTileService.getAdvancedTechTiles(roomId);
        List<AdvancedTechTileInfo> advancedTiles = advOffers.stream()
                .map(offer -> {
                    AdvancedTechTileCode tileCode = offer.getAdvTechTileCode();
                    return new AdvancedTechTileInfo(
                            tileCode.name(),
                            offer.getTechTrack(),
                            offer.getPosition(),
                            tileCode.getAbility().getType().name(),
                            tileCode.getAbility().getDescription(),
                            offer.getTakenByPlayerId() != null,
                            offer.getTakenByPlayerId() != null ? offer.getTakenByPlayerId().toString() : null,
                            actionUsedCodes.contains(tileCode.name())
                    );
                })
                .toList();

        return ResponseEntity.ok(new TechTrackResponse(tracks, basicTiles, advancedTiles));
    }

    /**
     * 트랙 레벨별 설명 (간략화)
     */
    private String getLevelDescription(TechCategoryType category, int level) {
        return switch (category) {
            case TERRA_FORMING -> switch (level) {
                case 0 -> "테라포밍 3단계 필요";
                case 1 -> "테라포밍 3단계 필요";
                case 2 -> "테라포밍 2단계 필요";
                case 3 -> "테라포밍 1단계 필요";
                case 4 -> "테라포밍 1단계 필요";
                case 5 -> "연방 토큰 + 테라포밍 1단계";
                default -> "";
            };
            case NAVIGATION -> switch (level) {
                case 0 -> "항해 거리 1";
                case 1 -> "항해 거리 1, QIC=3 거리";
                case 2 -> "항해 거리 2";
                case 3 -> "항해 거리 2, QIC=2 거리";
                case 4 -> "항해 거리 3";
                case 5 -> "연방 토큰 + 항해 거리 4";
                default -> "";
            };
            case AI -> switch (level) {
                case 0 -> "-";
                case 1 -> "QIC 수입 1";
                case 2 -> "QIC 수입 1";
                case 3 -> "QIC 수입 2";
                case 4 -> "QIC 수입 2";
                case 5 -> "연방 토큰 + QIC 수입 4";
                default -> "";
            };
            case GAIA_FORMING -> switch (level) {
                case 0 -> "-";
                case 1 -> "가이아포머 1개, 가이아 비용 6";
                case 2 -> "가이아 비용 6";
                case 3 -> "가이아포머 1개, 가이아 비용 4";
                case 4 -> "가이아 비용 3";
                case 5 -> "연방 토큰 + VP 3/4/4";
                default -> "";
            };
            case ECONOMY -> switch (level) {
                case 0 -> "-";
                case 1 -> "수입: 크레딧 2, 파워 차징 1";
                case 2 -> "수입: 크레딧 2/광석 1, 파워 차징 2";
                case 3 -> "수입: 크레딧 3/광석 1, 파워 차징 3";
                case 4 -> "수입: 크레딧 4/광석 2, 파워 차징 4";
                case 5 -> "연방 토큰 + 수입: 크레딧 6/광석 3";
                default -> "";
            };
            case SCIENCE -> switch (level) {
                case 0 -> "-";
                case 1 -> "수입: 지식 1";
                case 2 -> "수입: 지식 2";
                case 3 -> "수입: 지식 3";
                case 4 -> "수입: 지식 4";
                case 5 -> "연방 토큰 + 수입: 지식 9";
                default -> "";
            };
            default -> "";
        };
    }
}
