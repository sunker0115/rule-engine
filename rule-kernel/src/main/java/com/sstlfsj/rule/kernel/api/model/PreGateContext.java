package com.sstlfsj.rule.kernel.api.model;

/** Pre-Gate 评估的入参，包含租户、场景和触发事件信息。 */
public record PreGateContext(
        String tenantId,
        String sceneCode,
        String subjectId,
        RuleEvent event
) {}
