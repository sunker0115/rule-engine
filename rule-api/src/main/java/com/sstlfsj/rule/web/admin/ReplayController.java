package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.audit.api.service.AuditService;
import com.sstlfsj.rule.config.api.service.SceneService;
import com.sstlfsj.rule.eval.api.service.ReplayService;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import com.sstlfsj.rule.web.common.ApiResponse;
import com.sstlfsj.rule.web.mask.TraceMasker;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 历史评估会话忠实重放入口。 */
@RestController
@RequestMapping("/admin/v1")
@RequiredArgsConstructor
public class ReplayController {

    private static final Logger log = LoggerFactory.getLogger(ReplayController.class);

    private final ReplayService replayService;
    private final AuditService auditService;
    private final SceneService sceneService;

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
            @RequestParam String tenantId) {
        EvalResult result = replayService.replay(tenantId, sessionId);
        List<NodeTrace> masked = TraceMasker.maskKernel(resolveRefs(tenantId, sessionId), result.nodeTrace());
        return ApiResponse.ok(new EvalResult(result.ruleHit(), result.finalDecision(),
                result.hitDecisions(), masked, result.errorCode(), result.score(),
                result.category(), result.decision()));
    }

    /** 解析会话场景的 live 敏感集；失败返回 null（masker fail-closed 全抹，D71）。 */
    private SceneService.SensitiveRefs resolveRefs(String tenantId, Long sessionId) {
        try {
            String sceneCode = auditService.getSessionSceneCode(tenantId, sessionId);
            if (sceneCode == null) return null;
            return sceneService.getSensitiveRefs(tenantId, sceneCode);
        } catch (RuntimeException e) {
            log.warn("getSensitiveRefs 失败，replay trace 读时脱敏 fail-closed 全抹: tenantId={}, sessionId={}",
                    tenantId, sessionId, e);
            return null;
        }
    }
}
