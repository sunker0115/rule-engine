package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.config.api.service.RuleAnalysisService;
import com.sstlfsj.rule.kernel.api.analysis.RuleSetAnalysisReport;
import com.sstlfsj.rule.web.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 规则集静态分析查询入口。 */
@RestController
@RequestMapping("/admin/v1/scenes")
@RequiredArgsConstructor
public class RuleAnalysisController {

    private final RuleAnalysisService ruleAnalysisService;

    /** GET /admin/v1/scenes/{sceneCode}/analysis — 规则集静态分析(死规则/冲突/重叠/覆盖缺口/不一致/未分析)。
     * @param sceneCode 场景编码 @param tenantId 租户
     * @return 聚合各类发现的分析报告 */
    @GetMapping("/{sceneCode}/analysis")
    public ApiResponse<RuleSetAnalysisReport> analyze(@PathVariable String sceneCode,
                                                      @RequestParam Long tenantId) {
        return ApiResponse.ok(ruleAnalysisService.analyze(tenantId, sceneCode));
    }
}
