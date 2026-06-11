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

    /** 列出 tenant 下所有 decision。 */
    List<DecisionDefinition> list(Long tenantId);
}
