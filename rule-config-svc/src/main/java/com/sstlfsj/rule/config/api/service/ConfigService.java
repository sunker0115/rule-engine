package com.sstlfsj.rule.config.api.service;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;

/** Manages rule definition lifecycle: publishing and disabling rules. */
public interface ConfigService {

    /**
     * Publishes the latest draft version of a rule definition, making it active.
     *
     * @param tenantId         tenant owning the rule
     * @param ruleDefinitionId ID of the rule definition to publish
     * @param actorId          ID of the operator triggering the publish
     * @return snapshot of the newly activated rule version
     */
    RuleVersionSnapshot publish(String tenantId, Long ruleDefinitionId, String actorId);

    /**
     * Disables a rule definition and its active version.
     *
     * @param tenantId         tenant owning the rule
     * @param ruleDefinitionId ID of the rule definition to disable
     * @param actorId          ID of the operator triggering the disable
     */
    void disable(String tenantId, Long ruleDefinitionId, String actorId);
}
