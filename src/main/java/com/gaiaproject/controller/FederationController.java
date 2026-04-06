package com.gaiaproject.controller;

import com.gaiaproject.domain.entity.artifact.GameArtifactOffer;
import com.gaiaproject.domain.entity.federation.GameFederationOffer;
import com.gaiaproject.domain.enumtype.federation.FederationTileType;
import com.gaiaproject.dto.ResourcesVo;
import com.gaiaproject.dto.response.FederationTilesResponse;
import com.gaiaproject.dto.response.FederationTilesResponse.ArtifactInfo;
import com.gaiaproject.dto.response.FederationTilesResponse.FederationTileInfo;
import com.gaiaproject.dto.request.FormFederationRequest;
import com.gaiaproject.dto.response.FormFederationResponse;
import com.gaiaproject.repository.artifact.GameArtifactOfferRepository;
import com.gaiaproject.service.FederationFormService;
import com.gaiaproject.service.FederationTileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Tag(name = "Federation", description = "연방 타일 관련 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/rooms/{roomId}/federation")
public class FederationController {

    private final FederationTileService federationTileService;
    private final FederationFormService federationFormService;
    private final GameArtifactOfferRepository artifactOfferRepository;

    @Operation(summary = "연방 타일 목록 조회")
    @GetMapping
    public ResponseEntity<FederationTilesResponse> getFederationTiles(@PathVariable UUID roomId) {
        List<GameFederationOffer> offers = federationTileService.getFederationTiles(roomId);

        List<FederationTileInfo> generalSupply = new ArrayList<>();
        FederationTileInfo terraformingTrackTile = null;
        List<FederationTileInfo> forgottenFleet = new ArrayList<>();

        for (GameFederationOffer offer : offers) {
            FederationTileType tileType = offer.getFederationTileType();
            String description = formatTileDescription(tileType);

            FederationTileInfo info = new FederationTileInfo(
                    tileType.name(),
                    description,
                    offer.getQuantity(),
                    offer.getPosition()
            );

            if (offer.getPosition() == null) {
                generalSupply.add(info);
            } else if (offer.getPosition() == 0) {
                terraformingTrackTile = offer.getQuantity() > 0 ? info : null;
            } else {
                forgottenFleet.add(info);
            }
        }

        // 인공물 목록 조회 (트와일라잇 슬롯 4개)
        List<GameArtifactOffer> artifactOffers = artifactOfferRepository.findByGameIdOrderByPosition(roomId);
        List<ArtifactInfo> artifacts = artifactOffers.stream()
                .map(artifact -> new ArtifactInfo(
                        artifact.getArtifactType().name(),
                        artifact.getArtifactType().getDescription(),
                        artifact.getPosition(),
                        artifact.getIsAcquired(),
                        artifact.getAcquiredBy() != null ? artifact.getAcquiredBy().toString() : null
                ))
                .toList();

        return ResponseEntity.ok(new FederationTilesResponse(generalSupply, terraformingTrackTile, forgottenFleet, artifacts));
    }

    /**
     * 연방 타일 설명 생성
     */
    private String formatTileDescription(FederationTileType tileType) {
        ResourcesVo reward = tileType.getImmediateReward();
        StringBuilder sb = new StringBuilder();

        if (reward.vp() > 0) sb.append("VP ").append(reward.vp()).append(" ");
        if (reward.credits() > 0) sb.append("크레딧 ").append(reward.credits()).append(" ");
        if (reward.ore() > 0) sb.append("광석 ").append(reward.ore()).append(" ");
        if (reward.knowledge() > 0) sb.append("지식 ").append(reward.knowledge()).append(" ");
        if (reward.qic() > 0) sb.append("QIC ").append(reward.qic()).append(" ");
        if (reward.powerCharge() > 0) sb.append("파워토큰 ").append(reward.powerCharge()).append(" ");

        if (tileType.hasSpecialAction()) {
            sb.append("+ ").append(tileType.getSpecialAction().name());
        }

        return sb.toString().trim();
    }

    @Operation(summary = "연방 건물 선택 검증 (토큰 배치 전)")
    @PostMapping("/validate-buildings")
    public ResponseEntity<?> validateBuildings(
            @PathVariable UUID roomId,
            @RequestBody FormFederationRequest request) {
        var result = federationFormService.validateBuildingSelection(roomId, request);
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "연방 배치 조건 체크 (타일 선택 전)")
    @PostMapping("/validate")
    public ResponseEntity<FormFederationResponse> validateFederation(
            @PathVariable UUID roomId,
            @RequestBody FormFederationRequest request) {
        FormFederationResponse response = federationFormService.validateFederation(roomId, request);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "연방 형성 (타일 선택 후 확정)")
    @PostMapping("/form")
    public ResponseEntity<FormFederationResponse> formFederation(
            @PathVariable UUID roomId,
            @RequestBody FormFederationRequest request) {
        FormFederationResponse response = federationFormService.formFederation(roomId, request);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "게임의 연방 그룹 목록 조회")
    @GetMapping("/groups")
    public ResponseEntity<List<FederationFormService.FederationGroupInfo>> getFederationGroups(@PathVariable UUID roomId) {
        return ResponseEntity.ok(federationFormService.getFederationGroups(roomId));
    }
}
