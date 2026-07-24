package com.sstlfsj.rule.config.internal.template.ref;

import com.sstlfsj.rule.config.api.dto.SlotKind;
import com.sstlfsj.rule.config.api.dto.SlotResolutionContext;
import com.sstlfsj.rule.config.api.dto.TemplateSlot;
import com.sstlfsj.rule.config.api.service.SlotRefResolver;
import com.sstlfsj.rule.config.internal.domain.DecisionStatus;
import com.sstlfsj.rule.config.internal.repository.DecisionDefinitionMapper;
import org.springframework.stereotype.Component;

/** DECISION_REF 引用解析器：验证 decision 存在且 ACTIVE。 */
@Component
public class DecisionRefResolver implements SlotRefResolver {

    private final DecisionDefinitionMapper decisionDefinitionMapper;

    public DecisionRefResolver(DecisionDefinitionMapper decisionDefinitionMapper) {
        this.decisionDefinitionMapper = decisionDefinitionMapper;
    }

    @Override
    public boolean supports(SlotKind kind) {
        return kind == SlotKind.DECISION_REF;
    }

    @Override
    public void validate(String value, TemplateSlot slot, SlotResolutionContext ctx) {
        var decision = decisionDefinitionMapper.findByCode(ctx.tenantId(), value);
        if (decision == null) {
            throw new IllegalArgumentException(
                    "DECISION_REF slot '%s': decision '%s' 不存在（tenant=%d）"
                            .formatted(slot.key(), value, ctx.tenantId()));
        }
        if (decision.getStatus() != DecisionStatus.ACTIVE) {
            throw new IllegalArgumentException(
                    "DECISION_REF slot '%s': decision '%s' 状态非 ACTIVE（tenant=%d, status=%s）"
                            .formatted(slot.key(), value, ctx.tenantId(), decision.getStatus()));
        }
    }
}
