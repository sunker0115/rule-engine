package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.eval.api.service.ReplayService;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.web.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 历史评估会话忠实重放入口。 */
@RestController
@RequestMapping("/admin/v1")
@RequiredArgsConstructor
public class ReplayController {

    private final ReplayService replayService;

    /**
     * POST /admin/v1/evaluation-sessions/{sessionId}/replay — 忠实重放一个历史评估会话。
     *
     * @param sessionId 评估会话 id
     * @param tenantId  租户 id
     * @return 与当时一致的评估结果（含 nodeTrace）
     */
    @PostMapping("/evaluation-sessions/{sessionId}/replay")
    public ApiResponse<EvalResult> replay(
            @PathVariable Long sessionId,
            @RequestParam String tenantId) {
        return ApiResponse.ok(replayService.replay(tenantId, sessionId));
    }
}
