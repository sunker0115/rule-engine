package com.sstlfsj.rule.config.api.service;

import com.sstlfsj.rule.config.api.dto.DraftCreatedResult;
import com.sstlfsj.rule.config.api.dto.SlotBinding;
import com.sstlfsj.rule.config.api.dto.TemplateSlot;
import com.sstlfsj.rule.config.internal.domain.RuleTemplate;
import com.sstlfsj.rule.kernel.api.model.RuleBody;

import java.util.List;
import java.util.Map;

/** 规则模板管理（v2：binder SPI 重设计，JsonPointer 统一寻址覆盖全 6 kind）。 */
public interface RuleTemplateService {

    /** 创建 DRAFT 模板。bodySkeleton 为合法 body（默认值已就位，无 token）。返回模板 ID。 */
    Long create(Long tenantId, String code, String name, String kind,
                String description, RuleBody bodySkeleton,
                List<TemplateSlot> slots, List<SlotBinding> bindings, String actorId);

    /** 更新 DRAFT 模板（仅 DRAFT 可编辑）。版本 +1。 */
    void update(Long tenantId, String code, String name, String kind,
                String description, RuleBody bodySkeleton,
                List<TemplateSlot> slots, List<SlotBinding> bindings, String actorId);

    /** 发布模板 DRAFT→PUBLISHED（发布前经 binder.validate 校验）。 */
    void publish(Long tenantId, String code, String actorId);

    /** 禁用模板 PUBLISHED→DISABLED。 */
    void disable(Long tenantId, String code, String actorId);

    /** 按租户 + 状态列出模板。 */
    List<RuleTemplate> list(Long tenantId, String status);

    /** 查单个模板。 */
    RuleTemplate get(Long tenantId, String code);

    /** 实例化：从 PUBLISHED 模板生成 DRAFT RuleVersion。 */
    DraftCreatedResult instantiate(Long tenantId, String templateCode,
                                   String ruleCode, String ruleName,
                                   String sceneCode, List<String> triggerEventTypes,
                                   Map<String, Object> slotValues, String actorId);
}
