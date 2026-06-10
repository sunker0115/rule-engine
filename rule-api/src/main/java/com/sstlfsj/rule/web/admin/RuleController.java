package com.sstlfsj.rule.web.admin;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import com.sstlfsj.rule.config.api.dto.DraftCreatedResult;
import com.sstlfsj.rule.config.api.dto.RuleDetailVO;
import com.sstlfsj.rule.config.api.dto.RuleListItemVO;
import com.sstlfsj.rule.config.api.service.ConfigService;
import com.sstlfsj.rule.web.common.ApiResponse;
import com.sstlfsj.rule.web.common.PageResponse;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import com.sstlfsj.rule.web.admin.dto.CreateRuleRequest;
import com.sstlfsj.rule.web.admin.dto.EditDraftRequest;
import com.sstlfsj.rule.web.admin.dto.NewVersionRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 规则版本生命周期管理入口：发布、禁用、查询。 */
@RestController
@RequestMapping("/admin/v1/rules")
@RequiredArgsConstructor
public class RuleController {

    private final ConfigService configService;

    /**
     * POST /admin/v1/rules — 创建规则草稿。
     *
     * @param req     创建草稿请求体
     * @param actorId 操作人
     * @return 新建草稿的 ID 信息
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DraftCreatedResult> createDraft(
            @Valid @RequestBody CreateRuleRequest req,
            @RequestHeader("X-Actor-Id") String actorId) {
        // DecisionBindingInput(仅 decisionCode) → DecisionBinding：priority 草稿期占位 0，发布时从 decision_definition 回填
        List<DecisionBinding> bindings = req.decisionBindings() == null ? null
                : req.decisionBindings().stream()
                        .map(i -> new DecisionBinding(i.decisionCode(), 0))
                        .toList();
        return ApiResponse.ok(configService.createDraft(
                req.tenantId(), req.sceneCode(), req.code(), req.name(),
                req.conditionAst(), bindings,
                req.preGates(), req.triggerEventTypes(),
                req.kind(), actorId));
    }

    /**
     * PUT /admin/v1/rules/{ruleId}/draft — 原地编辑规则草稿（不增版本）。
     *
     * @param ruleId  规则 ID
     * @param req     编辑草稿请求体
     * @param actorId 操作人
     * @return 被更新草稿的 ID 信息（version 不变）
     */
    @PutMapping("/{ruleId}/draft")
    public ApiResponse<DraftCreatedResult> editDraft(
            @PathVariable Long ruleId,
            @Valid @RequestBody EditDraftRequest req,
            @RequestHeader("X-Actor-Id") String actorId) {
        // DecisionBindingInput(仅 decisionCode) → DecisionBinding：priority 草稿期占位 0，发布时回填
        List<DecisionBinding> bindings = req.decisionBindings() == null ? null
                : req.decisionBindings().stream()
                        .map(i -> new DecisionBinding(i.decisionCode(), 0))
                        .toList();
        return ApiResponse.ok(configService.editDraft(
                req.tenantId(), ruleId, req.name(), req.kind(),
                req.conditionAst(), bindings,
                req.preGates(), req.triggerEventTypes(), actorId));
    }

    /**
     * POST /admin/v1/rules/{ruleId}/versions — 出新版本草稿（body 可带 fromVersionId = 回退克隆）。
     *
     * @param ruleId  规则 ID
     * @param req     出新版本请求体
     * @param actorId 操作人
     * @return 新建草稿的 ID 信息（version = v_max+1）
     */
    @PostMapping("/{ruleId}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DraftCreatedResult> newVersion(
            @PathVariable Long ruleId,
            @Valid @RequestBody NewVersionRequest req,
            @RequestHeader("X-Actor-Id") String actorId) {
        // DecisionBindingInput(仅 decisionCode) → DecisionBinding：priority 草稿期占位 0，发布时回填
        List<DecisionBinding> bindings = req.decisionBindings() == null ? null
                : req.decisionBindings().stream()
                        .map(i -> new DecisionBinding(i.decisionCode(), 0))
                        .toList();
        return ApiResponse.ok(configService.newVersion(
                req.tenantId(), ruleId, req.name(), req.kind(),
                req.conditionAst(), bindings,
                req.preGates(), req.triggerEventTypes(),
                req.fromVersionId(), actorId));
    }

    /**
     * POST /admin/v1/rules/{ruleId}/publish — 发布规则版本。
     *
     * @param ruleId   规则 ID
     * @param tenantId 租户
     * @param actorId  操作人
     * @return 发布后的 RuleVersionSnapshot
     */
    @PostMapping("/{ruleId}/publish")
    public ApiResponse<RuleVersionSnapshot> publish(
            @PathVariable Long ruleId,
            @RequestParam String tenantId,
            @RequestHeader("X-Actor-Id") String actorId) {
        return ApiResponse.ok(configService.publish(tenantId, ruleId, actorId));
    }

    /**
     * POST /admin/v1/rules/{ruleId}/disable — 禁用规则版本。
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

    /**
     * GET /admin/v1/rules — 查询规则列表，支持 sceneCode / status 过滤与分页。
     *
     * @param tenantId  租户 ID
     * @param sceneCode Scene 编码（可选）
     * @param status    规则状态（可选；DRAFT / PUBLISHED / DISABLED）
     * @param page      页码，默认 1
     * @param size      每页条数，默认 20
     * @return 分页规则列表
     */
    @GetMapping
    public ApiResponse<PageResponse<RuleListItemVO>> listRules(
            @RequestParam String tenantId,
            @RequestParam(required = false) String sceneCode,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<RuleListItemVO> result = configService.listRules(tenantId, sceneCode, status, page, size);
        return ApiResponse.ok(PageResponse.of(result.getRecords(), result.getTotal(), page, size));
    }

    /**
     * GET /admin/v1/rules/{ruleId} — 查询规则详情（含 ACTIVE 版本 conditionAst / decisionBindings）。
     *
     * @param ruleId   规则定义 ID
     * @param tenantId 租户 ID
     * @return 规则详情
     */
    @GetMapping("/{ruleId}")
    public ApiResponse<RuleDetailVO> getDetail(@PathVariable Long ruleId,
                                               @RequestParam String tenantId) {
        return ApiResponse.ok(configService.getRuleDetail(tenantId, ruleId));
    }
}
