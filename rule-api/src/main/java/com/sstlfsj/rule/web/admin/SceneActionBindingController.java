package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.config.api.service.SceneActionBindingService;
import com.sstlfsj.rule.web.admin.convert.SceneActionBindingConvert;
import com.sstlfsj.rule.web.admin.dto.ActionBindingItemDto;
import com.sstlfsj.rule.web.admin.dto.ReplaceActionBindingsRequest;
import com.sstlfsj.rule.web.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Scene action 绑定（白名单）管理入口：列表查询 + 整组覆盖式保存。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/v1/scenes/{sceneCode}/action-bindings")
public class SceneActionBindingController {

    private final SceneActionBindingService bindingService;
    private final SceneActionBindingConvert bindingConvert;

    /**
     * GET /admin/v1/scenes/{sceneCode}/action-bindings?tenantId=xxx — 列出场景当前 action 绑定。
     * 存库的 JSON 串由 service 反序列化为对象返回，供前端编辑器渲染。
     */
    @GetMapping
    public ApiResponse<List<ActionBindingItemDto>> list(
            @PathVariable String sceneCode,
            @RequestParam String tenantId) {
        return ApiResponse.ok(bindingConvert.toDtos(bindingService.list(tenantId, sceneCode)));
    }

    /**
     * PUT /admin/v1/scenes/{sceneCode}/action-bindings?tenantId(body) — 整组覆盖式保存全部绑定。
     * 服务端 reconcile（删缺失 / 改已存在 / 增新增）+ 发 SceneChangedEvent 热刷新索引。
     */
    @PutMapping
    public ApiResponse<Void> replace(
            @PathVariable String sceneCode,
            @Valid @RequestBody ReplaceActionBindingsRequest req,
            @RequestHeader("X-Actor-Id") String actorId) {
        List<ActionBindingItemDto> bindings = req.bindings() != null ? req.bindings() : List.of();
        bindingService.replace(req.tenantId(), sceneCode, bindingConvert.toItems(bindings), actorId);
        return ApiResponse.ok(null);
    }
}
