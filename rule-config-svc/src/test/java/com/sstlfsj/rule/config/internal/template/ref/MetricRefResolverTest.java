package com.sstlfsj.rule.config.internal.template.ref;

import com.sstlfsj.rule.config.api.dto.SlotKind;
import com.sstlfsj.rule.config.api.dto.SlotResolutionContext;
import com.sstlfsj.rule.config.api.dto.TemplateSlot;
import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import com.sstlfsj.rule.config.internal.domain.MetricStatus;
import com.sstlfsj.rule.config.internal.repository.MetricDefinitionMapper;
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
class MetricRefResolverTest {

    @Mock
    MetricDefinitionMapper metricDefinitionMapper;

    @InjectMocks
    MetricRefResolver resolver;

    private final SlotResolutionContext ctx = new SlotResolutionContext(1L, "PAYMENT");

    @Test
    void supports_metricRef_true() {
        assertThat(resolver.supports(SlotKind.METRIC_REF)).isTrue();
    }

    @Test
    void supports_valueKind_false() {
        assertThat(resolver.supports(SlotKind.VALUE)).isFalse();
    }

    @Test
    void supports_decisionRef_false() {
        assertThat(resolver.supports(SlotKind.DECISION_REF)).isFalse();
    }

    @Test
    void supports_ruleRef_false() {
        assertThat(resolver.supports(SlotKind.RULE_REF)).isFalse();
    }

    @Test
    void validate_activeMetric_exists_passes() {
        MetricDefinition md = new MetricDefinition();
        md.setMetricCode("score");
        md.setStatus(MetricStatus.ACTIVE);
        when(metricDefinitionMapper.findAnyByCode(any(), anyString()))
                .thenReturn(md);

        TemplateSlot slot = new TemplateSlot("m", "指标", SlotKind.METRIC_REF, null, false, null);
        resolver.validate("score", slot, ctx);
    }

    @Test
    void validate_metricNotExists_throws() {
        when(metricDefinitionMapper.findAnyByCode(any(), anyString()))
                .thenReturn(null);

        TemplateSlot slot = new TemplateSlot("m", "指标", SlotKind.METRIC_REF, null, false, null);
        assertThatThrownBy(() -> resolver.validate("nonexistent", slot, ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在")
                .hasMessageNotContaining("非 ACTIVE");
    }

    @Test
    void validate_metricExistsButDisabled_throws() {
        MetricDefinition md = new MetricDefinition();
        md.setMetricCode("score");
        md.setStatus(MetricStatus.DISABLED);
        when(metricDefinitionMapper.findAnyByCode(any(), anyString()))
                .thenReturn(md);

        TemplateSlot slot = new TemplateSlot("m", "指标", SlotKind.METRIC_REF, null, false, null);
        assertThatThrownBy(() -> resolver.validate("score", slot, ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("存在但非 ACTIVE")
                .hasMessageContaining("DISABLED");
    }
}
