package com.sstlfsj.rule.web.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sstlfsj.rule.config.api.service.SceneService;
import com.sstlfsj.rule.web.common.ApiResponse;
import com.sstlfsj.rule.web.config.dto.CreateSceneRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 场景管理入口：创建、更新、查询场景（D13）。 */
@RestController
@RequestMapping("/api/v1/scenes")
public class SceneController {

    private final SceneService sceneService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SceneController(SceneService sceneService) {
        this.sceneService = sceneService;
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
        } catch (JsonProcessingException e) {
            // Object → JSON 序列化不应失败，属于内部错误
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }
}
