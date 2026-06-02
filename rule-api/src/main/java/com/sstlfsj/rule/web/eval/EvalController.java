package com.sstlfsj.rule.web.eval;

import com.sstlfsj.rule.eval.api.service.EvalService;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.web.common.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 规则评估 HTTP 入口，支持 PUSH 异步和 PULL 同步两种模式（D14）。 */
@RestController
@RequestMapping("/api/v1/rule")
public class EvalController {

    private final EvalService evalService;

    public EvalController(EvalService evalService) {
        this.evalService = evalService;
    }

    /** POST /api/v1/rule/event — PUSH 评估（异步，返回 202）
     * @param event 待评估的规则事件
     * @return 是否已接受 */
    @PostMapping("/event")
    public ResponseEntity<ApiResponse<Map<String, Object>>> pushEvent(
            @RequestBody RuleEvent event) {
        boolean accepted = evalService.acceptEvent(event);
        return ResponseEntity.accepted()
                .body(ApiResponse.ok(Map.of("eventId", event.eventId(), "accepted", accepted)));
    }

    /** POST /api/v1/rule/evaluate — PULL 评估（同步，返回 200）
     * @param event 待评估的规则事件
     * @return 完整评估结果 */
    @PostMapping("/evaluate")
    public ApiResponse<EvalResult> evaluate(@RequestBody RuleEvent event) {
        return ApiResponse.ok(evalService.evaluate(event));
    }

    /** POST /api/v1/rule/dry-run — dry-run（含 nodeTrace，不派发 Action）
     * @param event 待评估的规则事件
     * @param ruleVersionId null 表示使用当前活跃版本
     * @return 评估结果（含节点 trace） */
    @PostMapping("/dry-run")
    public ApiResponse<EvalResult> dryRun(
            @RequestBody RuleEvent event,
            @RequestParam(required = false) Long ruleVersionId) {
        return ApiResponse.ok(evalService.dryRun(event, ruleVersionId));
    }
}
