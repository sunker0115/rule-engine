package com.sstlfsj.rule.web.config;

import com.sstlfsj.rule.config.api.service.ConfigService;
import com.sstlfsj.rule.web.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 规则版本生命周期管理入口：发布、禁用。 */
@RestController
@RequestMapping("/api/v1/rules")
public class RuleController {

    private final ConfigService configService;

    public RuleController(ConfigService configService) {
        this.configService = configService;
    }

    /**
     * POST /api/v1/rules — 创建规则草稿（v1 占位，v1.5 实现）。
     *
     * @param actorId 操作人
     * @return 501 Not Implemented
     */
    @PostMapping
    @ResponseStatus(HttpStatus.NOT_IMPLEMENTED)
    public ApiResponse<Void> createDraft(
            @RequestHeader("X-Actor-Id") String actorId) {
        return ApiResponse.error("NOT_IMPLEMENTED", "规则草稿创建接口将在 v1.5 实现");
    }

    /**
     * POST /api/v1/rules/{ruleId}/publish — 发布规则版本。
     *
     * @param ruleId   规则 ID
     * @param tenantId 租户
     * @param actorId  操作人
     * @return 发布后的 RuleVersionSnapshot
     */
    @PostMapping("/{ruleId}/publish")
    public ApiResponse<Object> publish(
            @PathVariable Long ruleId,
            @RequestParam String tenantId,
            @RequestHeader("X-Actor-Id") String actorId) {
        return ApiResponse.ok(configService.publish(tenantId, ruleId, actorId));
    }

    /**
     * POST /api/v1/rules/{ruleId}/disable — 禁用规则版本。
     *
     * @param ruleId   规则 ID
     * @param tenantId 租户
     * @param actorId  操作人
     * @return 空数据
     */
    @PostMapping("/{ruleId}/disable")
    public ApiResponse<Void> disable(
            @PathVariable Long ruleId,
            @RequestParam String tenantId,
            @RequestHeader("X-Actor-Id") String actorId) {
        configService.disable(tenantId, ruleId, actorId);
        return ApiResponse.ok(null);
    }
}
