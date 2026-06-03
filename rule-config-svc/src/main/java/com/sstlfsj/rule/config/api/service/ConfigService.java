package com.sstlfsj.rule.config.api.service;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;

/** 规则定义生命周期管理：发布与禁用。 */
public interface ConfigService {

    /**
     * 发布规则定义的最新草稿版本，使其进入激活状态。
     *
     * @param tenantId         规则所属租户 ID
     * @param ruleDefinitionId 待发布的规则定义 ID
     * @param actorId          触发发布的操作人 ID
     * @return 新激活的规则版本快照
     */
    RuleVersionSnapshot publish(String tenantId, Long ruleDefinitionId, String actorId);

    /**
     * 禁用规则定义及其当前激活版本。
     *
     * @param tenantId         规则所属租户 ID
     * @param ruleDefinitionId 待禁用的规则定义 ID
     * @param actorId          触发禁用的操作人 ID
     */
    void disable(String tenantId, Long ruleDefinitionId, String actorId);
}
