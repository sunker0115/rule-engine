package com.sstlfsj.rule.web.config;

import com.sstlfsj.rule.config.api.service.SceneService;
import com.sstlfsj.rule.web.common.ApiResponse;
import com.sstlfsj.rule.web.config.dto.CreateSceneRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 场景管理入口：创建场景。 */
@RestController
@RequestMapping("/api/v1/scenes")
public class SceneController {

    private final SceneService sceneService;

    public SceneController(SceneService sceneService) {
        this.sceneService = sceneService;
    }

    /**
     * POST /api/v1/scenes — 创建场景。
     *
     * @param req     请求体（tenantId / sceneCode / name）
     * @param actorId 操作人（来自 X-Actor-Id header）
     * @return 新建场景 ID
     */
    @PostMapping
    public ApiResponse<Map<String, Object>> createScene(
            @Valid @RequestBody CreateSceneRequest req,
            @RequestHeader("X-Actor-Id") String actorId) {
        Long id = sceneService.createScene(req.tenantId(), req.sceneCode(), req.name(), actorId);
        return ApiResponse.ok(Map.of("id", id));
    }
}
