package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.config.api.dto.DraftCreatedResult;
import com.sstlfsj.rule.config.api.dto.TemplateDetail;
import com.sstlfsj.rule.config.api.service.RuleTemplateService;
import com.sstlfsj.rule.config.internal.domain.RuleTemplate;
import com.sstlfsj.rule.config.internal.domain.RuleTemplateVersion;
import com.sstlfsj.rule.web.admin.dto.CreateTemplateRequest;
import com.sstlfsj.rule.web.admin.dto.InstantiateRequest;
import com.sstlfsj.rule.web.admin.dto.UpdateTemplateRequest;
import com.sstlfsj.rule.web.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 规则模板管理入口（v2：身份/快照分离，版本化生命周期，可见性列表，VO 边界 enum→String）。 */
@RestController
@RequestMapping("/admin/v1/rule-templates")
@RequiredArgsConstructor
public class RuleTemplateController {

    private final RuleTemplateService templateService;

    /** POST /admin/v1/rule-templates — 创建 DRAFT 模板。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Long> create(
            @Valid @RequestBody CreateTemplateRequest req,
            @RequestHeader("X-Actor-Id") String actorId) {
        return ApiResponse.ok(templateService.create(
                req.tenantId(), req.code(), req.name(), req.kind(),
                req.description(), req.bodySkeleton(), req.slots(), req.bindings(), actorId));
    }

    /** PUT /admin/v1/rule-templates/{code} — 更新模板（原地更新 DRAFT 或新建 v(n+1) DRAFT）。 */
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

    /** GET /admin/v1/rule-templates — 模板列表（可见性过滤）。 */
    @GetMapping
    public ApiResponse<List<RuleTemplate>> list(
            @RequestHeader("X-Tenant-Id") Long tenantId,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(templateService.list(tenantId, status));
    }

    /** GET /admin/v1/rule-templates/{code} — 模板详情（身份 + 最新版本快照）。 */
    @GetMapping("/{code}")
    public ApiResponse<TemplateDetail> get(
            @PathVariable String code,
            @RequestHeader("X-Tenant-Id") Long tenantId) {
        return ApiResponse.ok(templateService.getVersion(tenantId, code));
    }

    /** GET /admin/v1/rule-templates/{code}/versions — 版本历史列表。 */
    @GetMapping("/{code}/versions")
    public ApiResponse<List<RuleTemplateVersion>> listVersions(
            @PathVariable String code,
            @RequestHeader("X-Tenant-Id") Long tenantId) {
        return ApiResponse.ok(templateService.listVersions(tenantId, code));
    }

    /** GET /admin/v1/rule-templates/{code}/versions/{version} — 指定版本快照。 */
    @GetMapping("/{code}/versions/{version}")
    public ApiResponse<TemplateDetail> getVersion(
            @PathVariable String code,
            @PathVariable Integer version,
            @RequestHeader("X-Tenant-Id") Long tenantId) {
        return ApiResponse.ok(templateService.getVersion(tenantId, code, version));
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
