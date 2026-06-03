package com.sstlfsj.rule.kernel.api.model;

import java.util.Map;

/** Pre-Gate 评估的入参，包含租户、场景、触发事件以及本次 Gate 的配置参数。 */
public record PreGateContext(
        String tenantId,
        String sceneCode,
        String subjectId,
        RuleEvent event,
        Long ruleVersionId,
        Map<String, Object> gateParams
) {
    public PreGateContext {
        gateParams = gateParams == null ? Map.of() : Map.copyOf(gateParams);
    }
}
