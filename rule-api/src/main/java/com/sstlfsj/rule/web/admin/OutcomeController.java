package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.audit.api.service.EffectivenessService;
import com.sstlfsj.rule.audit.api.service.EffectivenessService.EffectivenessQuery;
import com.sstlfsj.rule.audit.api.service.EffectivenessService.EffectivenessReport;
import com.sstlfsj.rule.eval.api.service.OutcomeService;
import com.sstlfsj.rule.eval.api.service.OutcomeService.OutcomeRecord;
import com.sstlfsj.rule.web.admin.dto.RecordOutcomesRequest;
import com.sstlfsj.rule.web.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 决策效果闭环入口（B32）：标签回灌 + 效果聚合查询。 */
@RestController
@RequestMapping("/admin/v1/decision-outcomes")
@RequiredArgsConstructor
public class OutcomeController {

    private final OutcomeService outcomeService;
    private final EffectivenessService effectivenessService;

    /** POST /admin/v1/decision-outcomes — 批量回灌结果标签（幂等 upsert）。
     * @param req 回灌请求体
     * @return 接受条数 */
    @PostMapping
    public ApiResponse<Map<String, Integer>> record(@Valid @RequestBody RecordOutcomesRequest req) {
        List<OutcomeRecord> records = req.outcomes().stream()
                .map(o -> new OutcomeRecord(o.eventId(), o.outcomeLabel(), o.outcomeValue(),
                        o.labeledAt(), o.source(), o.note()))
                .toList();
        int accepted = outcomeService.recordOutcomes(req.tenantId(), records);
        return ApiResponse.ok(Map.of("accepted", accepted));
    }

    /** GET /admin/v1/decision-outcomes/effectiveness — 按需聚合决策效果。
     * @param tenantId 租户 @param sceneCode 场景 @param from 窗口起(ISO-8601) @param to 窗口止(ISO-8601)
     * @param positiveLabels positive 判定口径(逗号分隔) @param dimension RULE_VERSION|DECISION @param bucket NONE|DAY|WEEK
     * @return 分桶效果报表 */
    @GetMapping("/effectiveness")
    public ApiResponse<EffectivenessReport> effectiveness(
            @RequestParam Long tenantId,
            @RequestParam String sceneCode,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(required = false) List<String> positiveLabels,
            @RequestParam(defaultValue = "RULE_VERSION") EffectivenessService.Dimension dimension,
            @RequestParam(defaultValue = "NONE") EffectivenessService.Bucket bucket) {
        EffectivenessReport report = effectivenessService.aggregate(new EffectivenessQuery(
                tenantId, sceneCode, Instant.parse(from), Instant.parse(to),
                positiveLabels == null ? List.of() : positiveLabels, dimension, bucket));
        return ApiResponse.ok(report);
    }
}
