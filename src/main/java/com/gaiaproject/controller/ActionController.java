package com.gaiaproject.controller;

import com.gaiaproject.domain.enumtype.action.ActionType;
import com.gaiaproject.dto.request.ConfirmActionRequest;
import com.gaiaproject.dto.request.PlaceMinePlayRequest;
import com.gaiaproject.dto.request.UpgradeBuildingRequest;
import com.gaiaproject.dto.request.UsePowerActionRequest;
import com.gaiaproject.dto.response.ConfirmActionResponse;
import com.gaiaproject.dto.response.PlaceMinePlayResponse;
import com.gaiaproject.dto.response.PlaceFleetProbeResponse;
import com.gaiaproject.dto.response.UpgradeBuildingResponse;
import com.gaiaproject.dto.response.UsePowerActionResponse;
import com.gaiaproject.dto.request.AdvanceTechRequest;
import com.gaiaproject.dto.response.AdvanceTechResponse;
import com.gaiaproject.dto.request.DeployGaiaformerRequest;
import com.gaiaproject.dto.response.DeployGaiaformerResponse;
import com.gaiaproject.service.ActionService;
import com.gaiaproject.service.BuildingService;
import com.gaiaproject.service.FleetService;
import com.gaiaproject.dto.request.FleetShipActionRequest;
import com.gaiaproject.dto.response.FleetShipActionResponse;
import com.gaiaproject.service.FleetShipActionService;
import com.gaiaproject.service.GaiaformingService;
import com.gaiaproject.service.PowerActionService;
import com.gaiaproject.service.TechTileService;
import com.gaiaproject.service.FactionAbilityService;
import com.gaiaproject.dto.request.FactionAbilityRequest;
import com.gaiaproject.dto.response.FactionAbilityResponse;
import com.gaiaproject.service.FreeConvertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@Tag(name = "Action", description = "플레이어 턴 액션 API")
@RestController
@RequestMapping("/api/rooms/{roomId}/actions")
@RequiredArgsConstructor
public class ActionController {

    private final ActionService actionService;
    private final BuildingService buildingService;
    private final PowerActionService powerActionService;
    private final FleetService fleetService;
    private final FleetShipActionService fleetShipActionService;
    private final TechTileService techTileService;
    private final FactionAbilityService factionAbilityService;
    private final GaiaformingService gaiaformingService;
    private final FreeConvertService freeConvertService;
    private final com.gaiaproject.service.IncomeService incomeService;

    @Operation(summary = "액션 저장 및 턴 넘김 (범용)")
    @PostMapping("/save")
    public ResponseEntity<ConfirmActionResponse> saveAction(
            @PathVariable UUID roomId,
            @RequestBody ConfirmActionRequest request
    ) {
        log.info("액션 저장: roomId={}, playerId={}, type={}", roomId, request.playerId(), request.actionType());
        ActionType actionType = ActionType.valueOf(request.actionType());
        String actionData = request.actionData() != null ? request.actionData() : "{}";
        return ResponseEntity.ok(actionService.saveActionAndNextTurn(roomId, request.playerId(), actionType, actionData));
    }

    @Operation(summary = "광산 건설 (PLAYING 페이즈)")
    @PostMapping("/mine")
    public ResponseEntity<PlaceMinePlayResponse> placeMine(
            @PathVariable UUID roomId,
            @RequestBody PlaceMinePlayRequest request
    ) {
        log.info("광산 건설: roomId={}, playerId={}, ({},{})", roomId, request.playerId(), request.hexQ(), request.hexR());
        return ResponseEntity.ok(buildingService.placeMineInPlay(roomId, request));
    }

    @Operation(summary = "검은행성 배치 (거리 트랙 5단계)")
    @PostMapping("/lost-planet")
    public ResponseEntity<PlaceMinePlayResponse> placeLostPlanet(
            @PathVariable UUID roomId,
            @RequestBody PlaceMinePlayRequest request
    ) {
        log.info("검은행성 배치: roomId={}, playerId={}, ({},{})", roomId, request.playerId(), request.hexQ(), request.hexR());
        return ResponseEntity.ok(buildingService.placeLostPlanet(roomId, request.playerId(), request.hexQ(), request.hexR()));
    }

    @Operation(summary = "건물 업그레이드")
    @PostMapping("/upgrade")
    public ResponseEntity<UpgradeBuildingResponse> upgradeBuilding(
            @PathVariable UUID roomId,
            @RequestBody UpgradeBuildingRequest request
    ) {
        log.info("건물 업그레이드: roomId={}, playerId={}, ({},{}) → {}", roomId, request.playerId(), request.hexQ(), request.hexR(), request.targetBuildingType());
        return ResponseEntity.ok(buildingService.upgradeBuildingInPlay(roomId, request));
    }

    @Operation(summary = "파워 액션 사용")
    @PostMapping("/power")
    public ResponseEntity<UsePowerActionResponse> usePowerAction(
            @PathVariable UUID roomId,
            @RequestBody UsePowerActionRequest request
    ) {
        log.info("파워 액션: roomId={}, playerId={}, action={}", roomId, request.playerId(), request.powerActionCode());
        return ResponseEntity.ok(powerActionService.usePowerAction(roomId, request));
    }

    @Operation(summary = "파워 소각 (bowl2 -2, bowl3 +1, 자유 행동)")
    @PostMapping("/burn-power")
    public ResponseEntity<Void> burnPower(
            @PathVariable UUID roomId,
            @RequestBody UsePowerActionRequest request
    ) {
        log.info("파워 소각: roomId={}, playerId={}", roomId, request.playerId());
        powerActionService.burnPower(roomId, request.playerId());
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "현재 라운드에서 사용된 파워 액션 코드 목록 조회")
    @GetMapping("/power/used")
    public ResponseEntity<List<String>> getUsedPowerActions(@PathVariable UUID roomId) {
        return ResponseEntity.ok(powerActionService.getUsedPowerActionCodes(roomId));
    }

    @Operation(summary = "지식 트랙 전진 (지식 4 소모)")
    @PostMapping("/tech-advance")
    public ResponseEntity<AdvanceTechResponse> advanceTechTrack(
            @PathVariable UUID roomId,
            @RequestBody AdvanceTechRequest request
    ) {
        log.info("기술 트랙 전진: roomId={}, playerId={}, track={}", roomId, request.playerId(), request.trackCode());
        return ResponseEntity.ok(techTileService.advanceTechTrack(roomId, request));
    }

    @Operation(summary = "가이아 포머 배치 (차원변형 행성)")
    @PostMapping("/deploy-gaiaformer")
    public ResponseEntity<DeployGaiaformerResponse> deployGaiaformer(
            @PathVariable UUID roomId,
            @RequestBody DeployGaiaformerRequest request
    ) {
        log.info("포머 배치: roomId={}, playerId={}, ({},{})", roomId, request.playerId(), request.hexQ(), request.hexR());
        return ResponseEntity.ok(gaiaformingService.deployGaiaformer(roomId, request));
    }

    @Operation(summary = "함대 선박 특수 액션")
    @PostMapping("/fleet-ship")
    public ResponseEntity<FleetShipActionResponse> fleetShipAction(
            @PathVariable UUID roomId,
            @RequestBody FleetShipActionRequest request
    ) {
        log.info("함대 선박 액션: roomId={}, playerId={}, action={}", roomId, request.playerId(), request.actionCode());
        return ResponseEntity.ok(fleetShipActionService.executeAction(roomId, request));
    }

    @Operation(summary = "함대 입장")
    @PostMapping("/fleet-probe")
    public ResponseEntity<PlaceFleetProbeResponse> placeFleetProbe(
            @PathVariable UUID roomId,
            @RequestBody com.gaiaproject.dto.request.PlaceFleetProbeRequest request
    ) {
        log.info("함대 입장: roomId={}, playerId={}, fleet={}", roomId, request.playerId(), request.fleetName());
        return ResponseEntity.ok(fleetService.placeFleetProbe(roomId, request.playerId(), request.fleetName(), request.qicUsed()));
    }

    @Operation(summary = "기술 타일 액션 사용 (라운드당 1회)")
    @PostMapping("/tech-tile-action")
    public ResponseEntity<ConfirmActionResponse> useTechTileAction(
            @PathVariable UUID roomId,
            @RequestBody UseTechTileActionRequest request
    ) {
        log.info("기술 타일 액션: roomId={}, playerId={}, tile={}", roomId, request.playerId(), request.tileCode());
        try {
            return ResponseEntity.ok(techTileService.useTechTileAction(roomId, request.playerId(), request.tileCode()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(
                    new ConfirmActionResponse(roomId, null, false, e.getMessage(), null, false));
        }
    }

    record UseTechTileActionRequest(UUID playerId, String tileCode) {}

    @Operation(summary = "프리 액션: 자원 변환 (턴 소모 없음)")
    @PostMapping("/free-convert")
    public ResponseEntity<FreeConvertService.FreeConvertResponse> freeConvert(
            @PathVariable UUID roomId,
            @RequestBody FreeConvertRequest request
    ) {
        log.info("프리 변환: roomId={}, playerId={}, code={}, brainstone={}", roomId, request.playerId(), request.convertCode(), request.useBrainstone());
        return ResponseEntity.ok(freeConvertService.convert(roomId, request.playerId(), request.convertCode(),
                request.useBrainstone() != null && request.useBrainstone()));
    }

    record FreeConvertRequest(UUID playerId, String convertCode, Boolean useBrainstone) {}

    @Operation(summary = "종족 고유 능력 사용")
    @PostMapping("/faction-ability")
    public ResponseEntity<FactionAbilityResponse> useFactionAbility(
            @PathVariable UUID roomId,
            @RequestBody FactionAbilityRequest request
    ) {
        log.info("종족 능력: roomId={}, playerId={}, ability={}", roomId, request.playerId(), request.abilityCode());
        return ResponseEntity.ok(factionAbilityService.useAbility(roomId, request));
    }

    @Operation(summary = "팅커로이드 라운드 시작 액션 타일 선택")
    @PostMapping("/tinkeroids-action-choice")
    public ResponseEntity<FactionAbilityResponse> tinkeroidsActionChoice(
            @PathVariable UUID roomId,
            @RequestBody com.gaiaproject.dto.request.TinkeroidsActionChoiceRequest request
    ) {
        log.info("팅커로이드 액션 선택: roomId={}, playerId={}, action={}", roomId, request.playerId(), request.actionCode());
        return ResponseEntity.ok(factionAbilityService.handleTinkeroidsActionChoice(roomId, request));
    }

    @Operation(summary = "아이타 라운드 종료 가이아→기술타일 선택")
    @PostMapping("/itars-gaia-choice")
    public ResponseEntity<FactionAbilityResponse> itarsGaiaChoice(
            @PathVariable UUID roomId,
            @RequestBody com.gaiaproject.dto.request.ItarsGaiaChoiceRequest request
    ) {
        log.info("아이타 가이아 선택: roomId={}, playerId={}, action={}", roomId, request.playerId(), request.action());
        return ResponseEntity.ok(factionAbilityService.handleItarsRoundEndChoice(roomId, request));
    }

    @Operation(summary = "테란 가이아→자원 수동 변환")
    @PostMapping("/terrans-gaia-convert")
    public ResponseEntity<java.util.Map<String, Object>> terransGaiaConvert(
            @PathVariable UUID roomId,
            @RequestBody java.util.Map<String, Object> request
    ) {
        UUID playerId = UUID.fromString((String) request.get("playerId"));
        int credits = (int) request.getOrDefault("credits", 0);
        int ores = (int) request.getOrDefault("ores", 0);
        int qics = (int) request.getOrDefault("qics", 0);
        int knowledges = (int) request.getOrDefault("knowledges", 0);
        try {
            incomeService.applyTerransGaiaConvert(roomId, playerId, credits, ores, qics, knowledges);
            return ResponseEntity.ok(java.util.Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.ok(java.util.Map.of("success", false, "message", e.getMessage()));
        }
    }
}
