package com.sstlfsj.rule.web.api;

import com.sstlfsj.rule.config.api.service.SceneService;
import com.sstlfsj.rule.config.api.service.TenantQueryService;
import com.sstlfsj.rule.eval.api.service.EvalService;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.web.common.ApiResponse;
import jakarta.validation.Valid;
import com.sstlfsj.rule.web.api.dto.EvalEventRequest;
import com.sstlfsj.rule.web.api.dto.PushEventResponse;
import com.sstlfsj.rule.web.mask.TraceMasker;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 规则评估 HTTP 入口，支持 PUSH 异步和 PULL 同步两种模式（D14）。 */
@RestController
@RequestMapping("/api/v1/rule")
@RequiredArgsConstructor
public class EvalController {

    private static final Logger log = LoggerFactory.getLogger(EvalController.class);

    private final EvalService evalService;
    private final TenantQueryService tenantQueryService;
    private final SceneService sceneService;

    /** POST /api/v1/rule/event — PUSH 评估（异步，返回 202）
     * @param req 待评估的事件请求体
     * @return 是否已接受 */
    @PostMapping("/event")
    public ResponseEntity<ApiResponse<PushEventResponse>> pushEvent(
            @Valid @RequestBody EvalEventRequest req) {
        RuleEvent event = toEvent(req);
        boolean accepted = evalService.acceptEvent(event);
        return ResponseEntity.accepted()
                .body(ApiResponse.ok(new PushEventResponse(event.eventId(), accepted)));
    }

    /** POST /api/v1/rule/evaluate — PULL 评估（同步，返回 200）
     * @param req 待评估的事件请求体
     * @return 完整评估结果 */
    @PostMapping("/evaluate")
    public ApiResponse<EvalResult> evaluate(@Valid @RequestBody EvalEventRequest req) {
        return ApiResponse.ok(evalService.evaluate(toEvent(req), req.asOf()));
    }

    /** POST /api/v1/rule/dry-run — dry-run（含 nodeTrace，不派发 Action）
     * ruleId / ruleVersionId 二选一必传，都不传返回 400。
     * @param req 待评估的事件请求体
     * @param ruleId 规则 id（取其最新版本，含 DRAFT）；与 ruleVersionId 二选一
     * @param ruleVersionId 精确版本 id；与 ruleId 二选一，优先生效
     * @return 评估结果（含节点 trace） */
    @PostMapping("/dry-run")
    public ApiResponse<EvalResult> dryRun(
            @Valid @RequestBody EvalEventRequest req,
            @RequestParam(required = false) Long ruleId,
            @RequestParam(required = false) Long ruleVersionId) {
        RuleEvent event = toEvent(req);
        EvalResult result = evalService.dryRun(event, ruleId, ruleVersionId);
        // D71 读时脱敏：按 (租户, 场景) live 敏感集抹去 nodeTrace 中敏感叶子值，raw 仍按原样落库
        List<NodeTrace> masked = TraceMasker.maskKernel(
                resolveRefs(Long.parseLong(event.tenantId()), req.sceneCode()), result.nodeTrace());
        return ApiResponse.ok(new EvalResult(result.ruleHit(), result.finalDecision(),
                result.hitDecisions(), masked, result.errorCode(), result.score(),
                result.category(), result.decision()));
    }

    /** 解析 (租户, 场景) 的 live 敏感集；失败返回 null（masker fail-closed 全抹，D71）。 */
    private SceneService.SensitiveRefs resolveRefs(Long tenantId, String sceneCode) {
        try {
            return sceneService.getSensitiveRefs(tenantId, sceneCode);
        } catch (RuntimeException e) {
            log.warn("getSensitiveRefs 失败，dry-run trace 读时脱敏 fail-closed 全抹: tenantId={}, sceneCode={}",
                    tenantId, sceneCode, e);
            return null;
        }
    }

    /**
     * 将 HTTP 请求体转为 RuleEvent：边界把租户 code 解析为内部 id（surrogate 不外泄），
     * 渠道由入口权威设为 HTTP。
     */
    private RuleEvent toEvent(EvalEventRequest r) {
        Long tenantId = tenantQueryService.resolveIdByCode(r.tenantCode());
        if (tenantId == null) {
            throw new IllegalArgumentException("未知或缺失的租户 code: " + r.tenantCode());
        }
        return RuleEvent.builder()
                .tenantId(String.valueOf(tenantId))
                .sceneCode(r.sceneCode())
                .eventType(r.eventType())
                .subjectId(r.subjectId())
                .eventId(r.eventId())
                .occurredAt(r.occurredAt())
                .payload(r.payload())
                .source(EventSource.HTTP)
                .build();
    }
}
