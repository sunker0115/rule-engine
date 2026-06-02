package com.sstlfsj.rule.web.config;

import com.sstlfsj.rule.config.api.service.SceneService;
import com.sstlfsj.rule.web.common.ApiResponse;
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

    /** POST /api/v1/scenes — 创建场景
     * @param body 包含 tenantId/code/name 的请求体
     * @param actorId 操作人
     * @return 新建场景 ID */
    @PostMapping
    public ApiResponse<Map<String, Object>> createScene(
            @RequestBody Map<String, String> body,
            @RequestHeader("X-Actor-Id") String actorId) {
        Long id = sceneService.createScene(body.get("tenantId"), body.get("code"),
                body.get("name"), actorId);
        return ApiResponse.ok(Map.of("id", id));
    }
}
