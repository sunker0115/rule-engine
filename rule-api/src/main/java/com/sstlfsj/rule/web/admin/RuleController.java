package com.sstlfsj.rule.web.admin;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import com.sstlfsj.rule.config.api.dto.DraftCreatedResult;
import com.sstlfsj.rule.config.api.dto.RuleContent;
import com.sstlfsj.rule.config.api.dto.RuleDetailVO;
import com.sstlfsj.rule.config.api.dto.RuleListItemVO;
import com.sstlfsj.rule.config.api.dto.RuleListQuery;
import com.sstlfsj.rule.config.api.dto.RuleVersionContentVO;
import com.sstlfsj.rule.config.api.service.ConfigService;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.web.common.ApiResponse;
import com.sstlfsj.rule.web.common.PageResponse;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import com.sstlfsj.rule.web.admin.dto.CreateRuleRequest;
import com.sstlfsj.rule.web.admin.dto.EditDraftRequest;
import com.sstlfsj.rule.web.admin.dto.NewVersionRequest;
import com.sstlfsj.rule.web.admin.dto.RuleContentSource;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
        return ApiResponse.ok(configService.createDraft(
                req.tenantId(), req.sceneCode(), req.code(), toContent(req), actorId));
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
        return ApiResponse.ok(configService.editDraft(req.tenantId(), ruleId, toContent(req), actorId));
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
        return ApiResponse.ok(configService.newVersion(
                req.tenantId(), ruleId, toContent(req), req.fromVersionId(), actorId));
    }

    private static RuleContent toContent(RuleContentSource src) {
        List<DecisionBinding> bindings = src.decisionBindings() == null ? null
                : src.decisionBindings().stream().map(i -> new DecisionBinding(i.decisionCode(), 0)).toList();
        return new RuleContent(src.name(), src.kind(), src.conditionAst(), bindings,
                src.preGates(), src.triggerEventTypes(), src.script(), null);
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
            @RequestParam Long tenantId,
            @RequestHeader("X-Actor-Id") String actorId) {
        return ApiResponse.ok(configService.publish(tenantId, ruleId, actorId));
    }

    /**
     * POST /admin/v1/rules/{ruleId}/disable — 禁用规则（PUBLISHED → DISABLED，其它态拒绝）。
     *
     * @param ruleId   规则 ID
     * @param tenantId 租户
     * @param actorId  操作人
     * @return 空数据
     */
    @PostMapping("/{ruleId}/disable")
    public ApiResponse<Void> disable(
            @PathVariable Long ruleId,
            @RequestParam Long tenantId,
            @RequestHeader("X-Actor-Id") String actorId) {
        configService.disable(tenantId, ruleId, actorId);
        return ApiResponse.ok(null);
    }

    /**
     * POST /admin/v1/rules/{ruleId}/enable — 重新启用规则（DISABLED → PUBLISHED，其它态拒绝）。
     *
     * @param ruleId   规则 ID
     * @param tenantId 租户
     * @param actorId  操作人
     * @return 空数据
     */
    @PostMapping("/{ruleId}/enable")
    public ApiResponse<Void> enable(
            @PathVariable Long ruleId,
            @RequestParam Long tenantId,
            @RequestHeader("X-Actor-Id") String actorId) {
        configService.enable(tenantId, ruleId, actorId);
        return ApiResponse.ok(null);
    }

    /**
     * DELETE /admin/v1/rules/{ruleId} — 删整条未发布规则（级联删定义 + 全部版本）。
     *
     * @param ruleId   规则 ID
     * @param tenantId 租户
     * @param actorId  操作人
     * @return 空数据
     */
    @DeleteMapping("/{ruleId}")
    public ApiResponse<Void> deleteRule(
            @PathVariable Long ruleId,
            @RequestParam Long tenantId,
            @RequestHeader("X-Actor-Id") String actorId) {
        configService.deleteRule(tenantId, ruleId, actorId);
        return ApiResponse.ok(null);
    }

    /**
     * DELETE /admin/v1/rules/{ruleId}/versions/{versionId} — 删单个待发布草稿版本。
     *
     * @param ruleId    规则 ID
     * @param versionId 待删版本 ID
     * @param tenantId  租户
     * @param actorId   操作人
     * @return 空数据
     */
    @DeleteMapping("/{ruleId}/versions/{versionId}")
    public ApiResponse<Void> deleteDraftVersion(
            @PathVariable Long ruleId,
            @PathVariable Long versionId,
            @RequestParam Long tenantId,
            @RequestHeader("X-Actor-Id") String actorId) {
        configService.deleteDraftVersion(tenantId, ruleId, versionId, actorId);
        return ApiResponse.ok(null);
    }

    /**
     * GET /admin/v1/rules — 查询规则列表，支持 sceneCode / status / 时间范围 过滤与分页。
     *
     * @param tenantId  租户 ID
     * @param sceneCode Scene 编码（可选）
     * @param status    规则状态（可选；DRAFT / PUBLISHED / DISABLED）
     * @param from      发布时间起始（含，ISO 日期字符串如 2026-06-01；可选）
     * @param to        发布时间截止（含，ISO 日期字符串；可选）
     * @param page      页码，默认 1
     * @param size      每页条数，默认 20
     * @return 分页规则列表
     */
    @GetMapping
    public ApiResponse<PageResponse<RuleListItemVO>> listRules(
            @RequestParam Long tenantId,
            @RequestParam(required = false) String sceneCode,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<RuleDefinition> rdPage = configService.listRules(
                new RuleListQuery(tenantId, sceneCode, status, from, to, page, size));

        Map<Long, String> sceneCodeMap = configService.getSceneCodeMap(
                rdPage.getRecords().stream().map(RuleDefinition::getSceneId).collect(Collectors.toSet()));

        List<RuleListItemVO> vos = rdPage.getRecords().stream()
                .map(rd -> new RuleListItemVO(
                        rd.getTenantId(),
                        rd.getId(), rd.getCode(), rd.getName(),
                        rd.getKind() != null ? rd.getKind().name() : null,
                        sceneCodeMap.getOrDefault(rd.getSceneId(), null),
                        rd.getStatus().name(), rd.getCurrentVersion(), rd.getPublishedAt(),
                        rd.getCreatedAt()))
                .toList();

        return ApiResponse.ok(PageResponse.of(vos, rdPage.getTotal(), page, size));
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
                                               @RequestParam Long tenantId) {
        return ApiResponse.ok(configService.getRuleDetail(tenantId, ruleId));
    }

    /**
     * GET /admin/v1/rules/{ruleId}/versions/{versionId} — 取指定版本完整内容（历史版本查看 / diff）。
     *
     * @param ruleId    规则定义 ID
     * @param versionId 规则版本 ID
     * @param tenantId  租户 ID
     * @return 该版本完整内容
     */
    @GetMapping("/{ruleId}/versions/{versionId}")
    public ApiResponse<RuleVersionContentVO> getVersion(@PathVariable Long ruleId,
                                                        @PathVariable Long versionId,
                                                        @RequestParam Long tenantId) {
        return ApiResponse.ok(configService.getRuleVersion(tenantId, ruleId, versionId));
    }
}
