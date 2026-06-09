package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.config.api.service.SceneActionBindingService;
import com.sstlfsj.rule.config.api.service.SceneActionBindingService.SceneActionBindingItem;
import com.sstlfsj.rule.web.admin.dto.ActionBindingItemDto;
import com.sstlfsj.rule.web.admin.dto.ReplaceActionBindingsRequest;
import com.sstlfsj.rule.web.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/** Scene action 绑定（白名单）管理入口：列表查询 + 整组覆盖式保存。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/v1/scenes/{sceneCode}/action-bindings")
public class SceneActionBindingController {

    private final SceneActionBindingService bindingService;
    /** Spring Boot 自动配置的 ObjectMapper bean，用于 defaultParams / rateLimitOverride 的 Object↔JSON 互转。 */
    private final ObjectMapper objectMapper;

    /**
     * GET /admin/v1/scenes/{sceneCode}/action-bindings?tenantId=xxx — 列出场景当前 action 绑定。
     * 存库的 JSON 串反序列化为对象返回，供前端编辑器渲染。
     */
    @GetMapping
    public ApiResponse<List<ActionBindingItemDto>> list(
            @PathVariable String sceneCode,
            @RequestParam String tenantId) {
        List<ActionBindingItemDto> items = bindingService.list(tenantId, sceneCode).stream()
                .map(this::toDto)
                .toList();
        return ApiResponse.ok(items);
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
        List<SceneActionBindingItem> items = bindings.stream()
                .map(this::toItem)
                .toList();
        bindingService.replace(req.tenantId(), sceneCode, items, actorId);
        return ApiResponse.ok(null);
    }

    /** service 项（JSON 串）→ 响应 DTO（对象）。 */
    private ActionBindingItemDto toDto(SceneActionBindingItem item) {
        return new ActionBindingItemDto(item.actionType(),
                parse(item.defaultParamsJson()), parse(item.rateLimitOverrideJson()));
    }

    /** 请求 DTO（对象）→ service 项（JSON 串）。 */
    private SceneActionBindingItem toItem(ActionBindingItemDto dto) {
        return new SceneActionBindingItem(dto.actionType(),
                write(dto.defaultParams()), write(dto.rateLimitOverride()));
    }

    private Object parse(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JacksonException e) {
            // 存库 JSON 串理论上恒合法，反序列化失败属内部数据异常
            throw new IllegalStateException("action 绑定 JSON 反序列化失败", e);
        }
    }

    private String write(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JacksonException e) {
            throw new IllegalStateException("action 绑定 JSON 序列化失败", e);
        }
    }
}
