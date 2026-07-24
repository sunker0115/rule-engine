package com.sstlfsj.rule.config.internal.template.ref;

import com.sstlfsj.rule.config.api.dto.SlotKind;
import com.sstlfsj.rule.config.api.dto.SlotResolutionContext;
import com.sstlfsj.rule.config.api.dto.TemplateSlot;
import com.sstlfsj.rule.config.internal.domain.DecisionDefinition;
import com.sstlfsj.rule.config.internal.domain.DecisionStatus;
import com.sstlfsj.rule.config.internal.repository.DecisionDefinitionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DecisionRefResolverTest {

    @Mock
    DecisionDefinitionMapper decisionDefinitionMapper;

    @InjectMocks
    DecisionRefResolver resolver;

    private final SlotResolutionContext ctx = new SlotResolutionContext(1L, "PAYMENT");

    @Test
    void supports_decisionRef_true() {
        assertThat(resolver.supports(SlotKind.DECISION_REF)).isTrue();
    }

    @Test
    void supports_valueKind_false() {
        assertThat(resolver.supports(SlotKind.VALUE)).isFalse();
    }

    @Test
    void supports_metricRef_false() {
        assertThat(resolver.supports(SlotKind.METRIC_REF)).isFalse();
    }

    @Test
    void supports_ruleRef_false() {
        assertThat(resolver.supports(SlotKind.RULE_REF)).isFalse();
    }

    @Test
    void validate_activeDecision_exists_passes() {
        DecisionDefinition dd = new DecisionDefinition();
        dd.setCode("REJECT");
        dd.setStatus(DecisionStatus.ACTIVE);
        when(decisionDefinitionMapper.findByCode(any(), anyString()))
                .thenReturn(dd);

        TemplateSlot slot = new TemplateSlot("d", "决策", SlotKind.DECISION_REF, null, false, null);
        resolver.validate("REJECT", slot, ctx);
    }

    @Test
    void validate_decisionNotExists_throws() {
        when(decisionDefinitionMapper.findByCode(any(), anyString()))
                .thenReturn(null);

        TemplateSlot slot = new TemplateSlot("d", "决策", SlotKind.DECISION_REF, null, false, null);
        assertThatThrownBy(() -> resolver.validate("NONEXIST", slot, ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DECISION_REF slot 'd'")
                .hasMessageContaining("NONEXIST");
    }

    @Test
    void validate_decisionDisabled_throws() {
        DecisionDefinition dd = new DecisionDefinition();
        dd.setCode("OLD");
        dd.setStatus(DecisionStatus.DISABLED);
        when(decisionDefinitionMapper.findByCode(any(), anyString()))
                .thenReturn(dd);

        TemplateSlot slot = new TemplateSlot("d", "决策", SlotKind.DECISION_REF, null, false, null);
        assertThatThrownBy(() -> resolver.validate("OLD", slot, ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DECISION_REF slot 'd'")
                .hasMessageContaining("非 ACTIVE");
    }
}
