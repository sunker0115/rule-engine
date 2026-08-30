package com.sstlfsj.rule.config.internal.template.ref;

import com.sstlfsj.rule.config.api.dto.SlotKind;
import com.sstlfsj.rule.config.api.dto.SlotResolutionContext;
import com.sstlfsj.rule.config.api.dto.TemplateSlot;
import com.sstlfsj.rule.config.api.service.SlotRefResolver;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleVersionMapper;
import org.springframework.stereotype.Component;

/** RULE_REF 引用解析器：验证 rule 存在且有 PUBLISHED 版本。 */
@Component
public class RuleRefResolver implements SlotRefResolver {

    private final RuleDefinitionMapper ruleDefinitionMapper;
    private final RuleVersionMapper ruleVersionMapper;

    public RuleRefResolver(RuleDefinitionMapper ruleDefinitionMapper,
                           RuleVersionMapper ruleVersionMapper) {
        this.ruleDefinitionMapper = ruleDefinitionMapper;
        this.ruleVersionMapper = ruleVersionMapper;
    }

    @Override
    public boolean supports(SlotKind kind) {
        return kind == SlotKind.RULE_REF;
    }

    @Override
    public void validate(String value, TemplateSlot slot, SlotResolutionContext ctx) {
        var ruleDef = ruleDefinitionMapper.findByTenantAndCode(ctx.tenantId(), value);
        if (ruleDef == null) {
            throw new IllegalArgumentException(
                    "RULE_REF slot '%s': rule '%s' 不存在（tenant=%d）"
                            .formatted(slot.key(), value, ctx.tenantId()));
        }
        var activeVersion = ruleVersionMapper.findActiveVersion(ruleDef.getId());
        if (activeVersion == null) {
            throw new IllegalArgumentException(
                    "RULE_REF slot '%s': rule '%s' 无已发布版本（tenant=%d）"
                            .formatted(slot.key(), value, ctx.tenantId()));
        }
    }
}
