package com.sstlfsj.rule.kernel.api.model;

import java.time.Instant;
import java.util.Map;

/** Pre-Gate 评估的入参，包含租户、场景、触发事件、评估时刻以及本次 Gate 的配置参数。 */
public record PreGateContext(
        String tenantId,
        String sceneCode,
        String subjectId,
        RuleEvent event,
        Long ruleVersionId,
        Map<String, Object> gateParams,
        /** 引擎统一评估时刻（常规=now，重放/asOf=注入的历史时刻）；时段类 gate 据此判断，保证可复现。 */
        Instant occurredAt
) {
    public PreGateContext {
        gateParams = gateParams == null ? Map.of() : Map.copyOf(gateParams);
    }
}
