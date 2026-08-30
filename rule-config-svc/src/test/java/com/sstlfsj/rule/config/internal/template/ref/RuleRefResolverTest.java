package com.sstlfsj.rule.config.internal.template.ref;

import com.sstlfsj.rule.config.api.dto.SlotKind;
import com.sstlfsj.rule.config.api.dto.SlotResolutionContext;
import com.sstlfsj.rule.config.api.dto.TemplateSlot;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleDefinitionStatus;
import com.sstlfsj.rule.config.internal.domain.RuleVersion;
import com.sstlfsj.rule.config.internal.domain.RuleVersionStatus;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleVersionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuleRefResolverTest {

    @Mock
    RuleDefinitionMapper ruleDefinitionMapper;

    @Mock
    RuleVersionMapper ruleVersionMapper;

    @InjectMocks
    RuleRefResolver resolver;

    private final SlotResolutionContext ctx = new SlotResolutionContext(1L, "PAYMENT");

    @Test
    void supports_ruleRef_true() {
        assertThat(resolver.supports(SlotKind.RULE_REF)).isTrue();
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
    void supports_decisionRef_false() {
        assertThat(resolver.supports(SlotKind.DECISION_REF)).isFalse();
    }

    @Test
    void validate_publishedRule_withActiveVersion_passes() {
        RuleDefinition rd = new RuleDefinition();
        rd.setId(10L);
        rd.setCode("rule.score");
        rd.setStatus(RuleDefinitionStatus.PUBLISHED);
        when(ruleDefinitionMapper.findByTenantAndCode(any(), anyString()))
                .thenReturn(rd);

        RuleVersion rv = new RuleVersion();
        rv.setId(20L);
        rv.setStatus(RuleVersionStatus.ACTIVE);
        when(ruleVersionMapper.findActiveVersion(anyLong()))
                .thenReturn(rv);

        TemplateSlot slot = new TemplateSlot("r", "规则", SlotKind.RULE_REF, null, false, null);
        resolver.validate("rule.score", slot, ctx);
    }

    @Test
    void validate_ruleNotExists_throws() {
        when(ruleDefinitionMapper.findByTenantAndCode(any(), anyString()))
                .thenReturn(null);

        TemplateSlot slot = new TemplateSlot("r", "规则", SlotKind.RULE_REF, null, false, null);
        assertThatThrownBy(() -> resolver.validate("nonexistent", slot, ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RULE_REF slot 'r'")
                .hasMessageContaining("nonexistent");
    }

    @Test
    void validate_ruleExists_noActiveVersion_throws() {
        RuleDefinition rd = new RuleDefinition();
        rd.setId(10L);
        rd.setCode("rule.draft");
        rd.setStatus(RuleDefinitionStatus.DRAFT);
        when(ruleDefinitionMapper.findByTenantAndCode(any(), anyString()))
                .thenReturn(rd);
        when(ruleVersionMapper.findActiveVersion(anyLong()))
                .thenReturn(null);

        TemplateSlot slot = new TemplateSlot("r", "规则", SlotKind.RULE_REF, null, false, null);
        assertThatThrownBy(() -> resolver.validate("rule.draft", slot, ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RULE_REF slot 'r'")
                .hasMessageContaining("无已发布版本");
    }
}
