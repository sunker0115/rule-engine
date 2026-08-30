package com.sstlfsj.rule.config.api.service;

import java.util.List;

/** 规则血缘查询：rule→flow 反向引用（哪些 DECISION_FLOW 引用了某规则）。 */
public interface RuleLineageService {

    /**
     * 查 tenant 下所有 ACTIVE DECISION_FLOW 规则中，引用了 ruleCode 的规则列表。
     *
     * @param tenantId 租户 id
     * @param ruleCode 被引规则逻辑编码
     * @return 引用该规则的 flow 列表（无引用返回空列表）
     */
    List<ReferencingFlowItem> findFlowsReferencingRule(Long tenantId, String ruleCode);

    /**
     * 引用了目标规则的一条 DECISION_FLOW 规则。
     *
     * @param ruleDefinitionId flow 规则定义 id
     * @param ruleCode         flow 规则逻辑编码
     * @param sceneCode        flow 所属场景编码
     */
    record ReferencingFlowItem(Long ruleDefinitionId, String ruleCode, String sceneCode) {}
}
