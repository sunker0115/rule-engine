package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.config.api.dto.DraftCreatedResult;
import com.sstlfsj.rule.config.api.service.RuleTemplateService;
import com.sstlfsj.rule.config.internal.domain.RuleTemplate;
import com.sstlfsj.rule.web.admin.dto.CreateTemplateRequest;
import com.sstlfsj.rule.web.admin.dto.InstantiateRequest;
import com.sstlfsj.rule.web.admin.dto.UpdateTemplateRequest;
import com.sstlfsj.rule.web.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 规则模板管理入口（D74 authoring 便利层，参数化重设计；默认关闭，需 rule.template.enabled=true 开启）。 */
@RestController
@RequestMapping("/admin/v1/rule-templates")
@RequiredArgsConstructor
public class RuleTemplateController {

    private final RuleTemplateService templateService;

    /** POST /admin/v1/rule-templates — 创建模板（DRAFT）。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Long> create(
            @Valid @RequestBody CreateTemplateRequest req,
            @RequestHeader("X-Actor-Id") String actorId) {
        return ApiResponse.ok(templateService.create(
                req.tenantId(), req.code(), req.name(), req.kind(),
                req.description(), req.bodySkeleton(), req.slots(), req.bindings(), actorId));
    }

    /** PUT /admin/v1/rule-templates/{code} — 更新 DRAFT 模板。 */
    @PutMapping("/{code}")
    public ApiResponse<Void> update(
            @PathVariable String code,
            @Valid @RequestBody UpdateTemplateRequest req,
            @RequestHeader("X-Actor-Id") String actorId) {
        templateService.update(req.tenantId(), code, req.name(), req.kind(),
                req.description(), req.bodySkeleton(), req.slots(), req.bindings(), actorId);
        return ApiResponse.ok((Void) null);
    }

    /** POST /admin/v1/rule-templates/{code}/publish — 发布模板。 */
    @PostMapping("/{code}/publish")
    public ApiResponse<Void> publish(
            @PathVariable String code,
            @RequestHeader("X-Tenant-Id") Long tenantId,
            @RequestHeader("X-Actor-Id") String actorId) {
        templateService.publish(tenantId, code, actorId);
        return ApiResponse.ok((Void) null);
    }

    /** POST /admin/v1/rule-templates/{code}/disable — 禁用模板。 */
    @PostMapping("/{code}/disable")
    public ApiResponse<Void> disable(
            @PathVariable String code,
            @RequestHeader("X-Tenant-Id") Long tenantId,
            @RequestHeader("X-Actor-Id") String actorId) {
        templateService.disable(tenantId, code, actorId);
        return ApiResponse.ok((Void) null);
    }

    /** GET /admin/v1/rule-templates — 模板列表。 */
    @GetMapping
    public ApiResponse<List<RuleTemplate>> list(
            @RequestHeader("X-Tenant-Id") Long tenantId,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(templateService.list(tenantId, status));
    }

    /** GET /admin/v1/rule-templates/{code} — 模板详情。 */
    @GetMapping("/{code}")
    public ApiResponse<RuleTemplate> get(
            @PathVariable String code,
            @RequestHeader("X-Tenant-Id") Long tenantId) {
        return ApiResponse.ok(templateService.get(tenantId, code));
    }

    /** POST /admin/v1/rule-templates/{code}/instantiate — 实例化模板为规则（核心）。 */
    @PostMapping("/{code}/instantiate")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DraftCreatedResult> instantiate(
            @PathVariable String code,
            @Valid @RequestBody InstantiateRequest req,
            @RequestHeader("X-Actor-Id") String actorId) {
        return ApiResponse.ok(templateService.instantiate(
                req.tenantId(), code, req.ruleCode(), req.ruleName(),
                req.sceneCode(), req.triggerEventTypes(), req.slotValues(), actorId));
    }
}
