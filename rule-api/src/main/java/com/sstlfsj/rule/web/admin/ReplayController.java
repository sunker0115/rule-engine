package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.eval.api.service.ReplayService;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import com.sstlfsj.rule.web.common.ApiResponse;
import com.sstlfsj.rule.web.mask.SensitiveRefsResolver;
import com.sstlfsj.rule.web.mask.TraceMasker;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 历史评估会话忠实重放入口。 */
@RestController
@RequestMapping("/admin/v1")
@RequiredArgsConstructor
public class ReplayController {

    private final ReplayService replayService;
    private final SensitiveRefsResolver sensitiveRefsResolver;

    /**
     * POST /admin/v1/evaluation-sessions/{sessionId}/replay — 忠实重放一个历史评估会话。
     *
     * @param sessionId 评估会话 id
     * @param tenantId  租户 id
     * @return 与当时一致的评估结果（nodeTrace 经读时脱敏，D71）
     */
    @PostMapping("/evaluation-sessions/{sessionId}/replay")
    public ApiResponse<EvalResult> replay(
            @PathVariable Long sessionId,
            @RequestParam Long tenantId) {
        EvalResult result = replayService.replay(tenantId, sessionId);
        List<NodeTrace> masked = TraceMasker.maskKernel(sensitiveRefsResolver.forSession(tenantId, sessionId), result.nodeTrace());
        return ApiResponse.ok(new EvalResult(result.ruleHit(), result.finalDecision(),
                result.hitDecisions(), masked, result.errorCode(), result.score(),
                result.category(), result.decision()));
    }

}
