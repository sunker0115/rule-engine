package com.sstlfsj.rule.config.api.service;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;

/** Manages rule definition lifecycle: publishing and disabling rules. */
public interface ConfigService {
    RuleVersionSnapshot publish(String tenantId, Long ruleDefinitionId, String actorId);
    void disable(String tenantId, Long ruleDefinitionId, String actorId);
}
