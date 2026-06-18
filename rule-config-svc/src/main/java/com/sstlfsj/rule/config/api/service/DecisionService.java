package com.sstlfsj.rule.config.api.service;

import com.sstlfsj.rule.config.internal.domain.DecisionDefinition;

import java.util.List;

/** decision_definition tenant 级写服务（D26/D27：Decision 是 tenant 级一等实体，与 scene 无关）。 */
public interface DecisionService {

    /**
     * 新建 decision；code 在 tenant 内唯一，重复抛 {@link IllegalArgumentException}。
     *
     * @param tenantId    租户 id
     * @param code        decision 编码（tenant 内唯一）
     * @param name        decision 名称
     * @param priority    优先级（数值越小越优先，由业务自定）
     * @param description 描述，可空
     * @param actorId     操作人（X-Actor-Id）
     * @return 新建 decision 的 id
     */
    Long create(Long tenantId, String code, String name, Integer priority,
                String description, String actorId);

    /** 更新 decision 的 name/priority/description（按 tenantId+code 定位，不存在抛异常）。 */
    void update(Long tenantId, String code, String name, Integer priority,
                String description, String actorId);

    /** 停用 decision（status → DISABLED）。 */
    void disable(Long tenantId, String code, String actorId);

    /** 启用 decision（status → ACTIVE）。 */
    void enable(Long tenantId, String code, String actorId);

    /** 列出 tenant 下所有 decision。 */
    List<DecisionDefinition> list(Long tenantId);

    /**
     * 产出某 decision 的所有 ACTIVE 规则（下线前影响面 / Decision 覆盖来源）。
     * 口径同 MetricWriteService#findReferencingRules：按 rv.status=ACTIVE 收集，不按 rule_definition.status 过滤。
     *
     * @param tenantId     租户 id
     * @param decisionCode decision 编码
     * @return 产出该 decision 的规则引用项；无引用返回空列表
     */
    List<RuleRef> findRulesProducingDecision(Long tenantId, String decisionCode);

    /**
     * 按 tenantId+code 取单个 decision（详情页加载）。
     *
     * @param tenantId 租户 id
     * @param code     decision 编码
     * @return decision 定义
     * @throws IllegalArgumentException decision 不存在时抛出
     */
    DecisionDefinition get(Long tenantId, String code);

    /**
     * 一次扫聚合 tenant 下每个 decisionCode 的 ACTIVE 规则产出计数（列表徽标）。
     *
     * @param tenantId 租户 id
     * @return 每个 decisionCode 的引用计数（无引用的 decision 不出现在结果里）
     */
    List<UsageCount> countRuleUsages(Long tenantId);

    /** 产出某 decision 的规则引用项。sceneCode 由 rule_definition.scene_id 关联；status 为 rule_definition.status。 */
    record RuleRef(Long ruleDefinitionId, String ruleCode, String ruleName,
                   String sceneCode, String status) {}
}
