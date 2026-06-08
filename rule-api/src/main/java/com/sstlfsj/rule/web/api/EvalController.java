package com.sstlfsj.rule.web.api;

import com.sstlfsj.rule.config.api.service.TenantQueryService;
import com.sstlfsj.rule.eval.api.service.EvalService;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.web.common.ApiResponse;
import com.sstlfsj.rule.web.api.dto.EvalEventRequest;
import com.sstlfsj.rule.web.api.dto.PushEventResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 规则评估 HTTP 入口，支持 PUSH 异步和 PULL 同步两种模式（D14）。 */
@RestController
@RequestMapping("/api/v1/rule")
@RequiredArgsConstructor
public class EvalController {

    private final EvalService evalService;
    private final TenantQueryService tenantQueryService;

    /** POST /api/v1/rule/event — PUSH 评估（异步，返回 202）
     * @param req 待评估的事件请求体
     * @return 是否已接受 */
    @PostMapping("/event")
    public ResponseEntity<ApiResponse<PushEventResponse>> pushEvent(
            @RequestBody EvalEventRequest req) {
        RuleEvent event = toEvent(req);
        boolean accepted = evalService.acceptEvent(event);
        return ResponseEntity.accepted()
                .body(ApiResponse.ok(new PushEventResponse(event.eventId(), accepted)));
    }

    /** POST /api/v1/rule/evaluate — PULL 评估（同步，返回 200）
     * @param req 待评估的事件请求体
     * @return 完整评估结果 */
    @PostMapping("/evaluate")
    public ApiResponse<EvalResult> evaluate(@RequestBody EvalEventRequest req) {
        return ApiResponse.ok(evalService.evaluate(toEvent(req)));
    }

    /** POST /api/v1/rule/dry-run — dry-run（含 nodeTrace，不派发 Action）
     * @param req 待评估的事件请求体
     * @param ruleVersionId null 表示使用当前活跃版本
     * @return 评估结果（含节点 trace） */
    @PostMapping("/dry-run")
    public ApiResponse<EvalResult> dryRun(
            @RequestBody EvalEventRequest req,
            @RequestParam(required = false) Long ruleVersionId) {
        return ApiResponse.ok(evalService.dryRun(toEvent(req), ruleVersionId));
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
                .providedMetrics(r.providedMetrics())
                .source(EventSource.HTTP)
                .build();
    }
}
