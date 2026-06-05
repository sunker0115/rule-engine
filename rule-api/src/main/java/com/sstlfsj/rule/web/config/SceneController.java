package com.sstlfsj.rule.web.config;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.sstlfsj.rule.config.api.dto.SceneDetailDto;
import com.sstlfsj.rule.config.api.service.SceneService;
import com.sstlfsj.rule.web.common.ApiResponse;
import com.sstlfsj.rule.web.config.dto.CreateSceneRequest;
import com.sstlfsj.rule.web.config.dto.UpdateSceneRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 场景管理入口：创建、更新、查询场景（D13）。 */
@RestController
@RequestMapping("/api/v1/scenes")
public class SceneController {

    private final SceneService sceneService;
    /** Spring Boot 自动配置的 ObjectMapper bean，通过构造器注入。 */
    private final ObjectMapper objectMapper;

    public SceneController(SceneService sceneService, ObjectMapper objectMapper) {
        this.sceneService = sceneService;
        this.objectMapper = objectMapper;
    }

    /**
     * POST /api/v1/scenes — 创建场景（含 payloadSchema / eventTypes 等 D13 字段）。
     */
    @PostMapping
    public ApiResponse<Map<String, Object>> createScene(
            @Valid @RequestBody CreateSceneRequest req,
            @RequestHeader("X-Actor-Id") String actorId) {
        try {
            String eventTypesJson = req.eventTypes() != null
                    ? objectMapper.writeValueAsString(req.eventTypes()) : null;
            // payloadSchema / defaultParams 是 Object（Jackson 反序列化为 List/Map），转为 JSON 字符串传给 Service
            String payloadSchemaJson = req.payloadSchema() != null
                    ? objectMapper.writeValueAsString(req.payloadSchema()) : null;
            String defaultParamsJson = req.defaultParams() != null
                    ? objectMapper.writeValueAsString(req.defaultParams()) : null;
            Long id = sceneService.createScene(
                    req.tenantId(), req.sceneCode(), req.name(),
                    req.description(), req.dominantMode(), req.subjectType(),
                    eventTypesJson, payloadSchemaJson, defaultParamsJson,
                    actorId);
            return ApiResponse.ok(Map.of("id", id));
        } catch (JacksonException e) {
            // Object → JSON 序列化不应失败，属于内部错误
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    /**
     * GET /api/v1/scenes/{sceneCode}?tenantId=xxx — 查询 Scene 详情（含 payloadSchema）。
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
     * PATCH /api/v1/scenes/{sceneCode} — 更新场景元数据（payloadSchema / eventTypes 等）。
     * payloadSchema 发生变化时自动快照历史版本并自增版本号。
     */
    @PatchMapping("/{sceneCode}")
    public ApiResponse<Void> updateScene(
            @PathVariable String sceneCode,
            @Valid @RequestBody UpdateSceneRequest req,
            @RequestHeader("X-Actor-Id") String actorId) {
        try {
            String eventTypesJson = req.eventTypes() != null
                    ? objectMapper.writeValueAsString(req.eventTypes()) : null;
            String payloadSchemaJson = req.payloadSchema() != null
                    ? objectMapper.writeValueAsString(req.payloadSchema()) : null;
            String defaultParamsJson = req.defaultParams() != null
                    ? objectMapper.writeValueAsString(req.defaultParams()) : null;
            sceneService.updateScene(
                    req.tenantId(), sceneCode,
                    req.name(), eventTypesJson,
                    payloadSchemaJson, defaultParamsJson,
                    actorId);
            return ApiResponse.ok(null);
        } catch (JacksonException e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }
}
