package com.sstlfsj.rule.web.admin;

import lombok.RequiredArgsConstructor;
import com.sstlfsj.rule.config.api.dto.SceneDetailDto;
import com.sstlfsj.rule.config.api.dto.SceneListItem;
import com.sstlfsj.rule.config.api.service.SceneService;
import com.sstlfsj.rule.web.common.ApiResponse;
import com.sstlfsj.rule.web.admin.dto.CreateSceneRequest;
import com.sstlfsj.rule.web.admin.dto.CreateSceneResponse;
import com.sstlfsj.rule.web.admin.dto.UpdateSceneRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 场景管理入口：创建、更新、查询场景（D13）。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/v1/scenes")
public class SceneController {

    private final SceneService sceneService;

    /**
     * GET /admin/v1/scenes — 查询租户全部场景（精简列表，供前端场景选择器 / 列表页）。
     *
     * @param tenantId 租户 ID
     * @return 场景精简列表
     */
    @GetMapping
    public ApiResponse<List<SceneListItem>> listScenes(@RequestParam String tenantId) {
        return ApiResponse.ok(sceneService.listScenes(tenantId));
    }

    /**
     * POST /admin/v1/scenes — 创建场景（含 payloadSchema / eventTypes 等 D13 字段）。
     */
    @PostMapping
    public ApiResponse<CreateSceneResponse> createScene(
            @Valid @RequestBody CreateSceneRequest req,
            @RequestHeader("X-Actor-Id") String actorId) {
        Long id = sceneService.createScene(
                req.tenantId(), req.sceneCode(), req.name(),
                req.description(), req.dominantMode(), req.subjectType(),
                req.eventTypes(), req.payloadSchema(), req.defaultParams(),
                actorId);
        return ApiResponse.ok(new CreateSceneResponse(id));
    }

    /**
     * GET /admin/v1/scenes/{sceneCode}?tenantId=xxx — 查询 Scene 详情（含 payloadSchema）。
     *
     * @param sceneCode 场景编码（路径参数）
     * @param tenantId  租户 ID（查询参数）
     * @return Scene 详情（含 payloadSchema / eventTypes / defaultParams）
     */
    @GetMapping("/{sceneCode}")
    public ApiResponse<SceneDetailDto> getScene(
            @PathVariable String sceneCode,
            @RequestParam String tenantId) {
        return ApiResponse.ok(sceneService.getScene(tenantId, sceneCode));
    }

    /**
     * PATCH /admin/v1/scenes/{sceneCode} — 更新场景元数据（payloadSchema / eventTypes 等）。
     * payloadSchema 发生变化时自动快照历史版本并自增版本号。
     */
    @PatchMapping("/{sceneCode}")
    public ApiResponse<Void> updateScene(
            @PathVariable String sceneCode,
            @Valid @RequestBody UpdateSceneRequest req,
            @RequestHeader("X-Actor-Id") String actorId) {
        sceneService.updateScene(
                req.tenantId(), sceneCode,
                req.name(), req.eventTypes(),
                req.payloadSchema(), req.defaultParams(),
                actorId);
        return ApiResponse.ok(null);
    }
}
