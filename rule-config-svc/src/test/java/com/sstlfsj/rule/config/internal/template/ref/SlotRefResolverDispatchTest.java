package com.sstlfsj.rule.config.internal.template.ref;

import com.sstlfsj.rule.config.api.dto.SlotKind;
import com.sstlfsj.rule.config.api.dto.SlotResolutionContext;
import com.sstlfsj.rule.config.api.dto.TemplateSlot;
import com.sstlfsj.rule.config.api.service.SlotRefResolver;
import com.sstlfsj.rule.config.internal.domain.DecisionDefinition;
import com.sstlfsj.rule.config.internal.domain.DecisionStatus;
import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import com.sstlfsj.rule.config.internal.domain.MetricStatus;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleDefinitionStatus;
import com.sstlfsj.rule.config.internal.domain.RuleVersion;
import com.sstlfsj.rule.config.internal.domain.RuleVersionStatus;
import com.sstlfsj.rule.config.internal.repository.DecisionDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.MetricDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleVersionMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** SPI 零 switch 分派集成测试：List&lt;SlotRefResolver&gt; 注入后按 kind 找到唯一实现。 */
@SpringJUnitConfig
class SlotRefResolverDispatchTest {

    @Configuration
    static class TestConfig {
        @Bean
        MetricDefinitionMapper metricDefinitionMapper() {
            MetricDefinitionMapper m = mock(MetricDefinitionMapper.class);
            MetricDefinition md = new MetricDefinition();
            md.setMetricCode("score");
            md.setStatus(MetricStatus.ACTIVE);
            when(m.findActiveByCode(any(), anyString())).thenReturn(md);
            return m;
        }

        @Bean
        DecisionDefinitionMapper decisionDefinitionMapper() {
            DecisionDefinitionMapper m = mock(DecisionDefinitionMapper.class);
            DecisionDefinition dd = new DecisionDefinition();
            dd.setCode("REJECT");
            dd.setStatus(DecisionStatus.ACTIVE);
            when(m.findByCode(any(), anyString())).thenReturn(dd);
            return m;
        }

        @Bean
        RuleDefinitionMapper ruleDefinitionMapper() {
            RuleDefinitionMapper m = mock(RuleDefinitionMapper.class);
            RuleDefinition rd = new RuleDefinition();
            rd.setId(10L);
            rd.setCode("rule.score");
            rd.setStatus(RuleDefinitionStatus.PUBLISHED);
            when(m.findByTenantAndCode(any(), anyString())).thenReturn(rd);
            return m;
        }

        @Bean
        RuleVersionMapper ruleVersionMapper() {
            RuleVersionMapper m = mock(RuleVersionMapper.class);
            RuleVersion rv = new RuleVersion();
            rv.setId(20L);
            rv.setStatus(RuleVersionStatus.ACTIVE);
            when(m.findActiveVersion(anyLong())).thenReturn(rv);
            return m;
        }

        @Bean
        MetricRefResolver metricRefResolver() {
            return new MetricRefResolver(metricDefinitionMapper());
        }

        @Bean
        DecisionRefResolver decisionRefResolver() {
            return new DecisionRefResolver(decisionDefinitionMapper());
        }

        @Bean
        RuleRefResolver ruleRefResolver() {
            return new RuleRefResolver(ruleDefinitionMapper(), ruleVersionMapper());
        }
    }

    @Autowired
    List<SlotRefResolver> resolvers;

    private final SlotResolutionContext ctx = new SlotResolutionContext(1L, "PAYMENT");

    @Test
    void allThreeResolvers_registered() {
        assertThat(resolvers).hasSize(3);
    }

    @Test
    void dispatch_byKind_metricRef_findsResolver() {
        SlotRefResolver r = resolvers.stream()
                .filter(rr -> rr.supports(SlotKind.METRIC_REF))
                .findFirst().orElseThrow();
        assertThat(r).isInstanceOf(MetricRefResolver.class);
        TemplateSlot slot = new TemplateSlot("m", "指标", SlotKind.METRIC_REF, null, false, null);
        r.validate("score", slot, ctx); // 不抛异常
    }

    @Test
    void dispatch_byKind_decisionRef_findsResolver() {
        SlotRefResolver r = resolvers.stream()
                .filter(rr -> rr.supports(SlotKind.DECISION_REF))
                .findFirst().orElseThrow();
        assertThat(r).isInstanceOf(DecisionRefResolver.class);
        TemplateSlot slot = new TemplateSlot("d", "决策", SlotKind.DECISION_REF, null, false, null);
        r.validate("REJECT", slot, ctx); // 不抛异常
    }

    @Test
    void dispatch_byKind_ruleRef_findsResolver() {
        SlotRefResolver r = resolvers.stream()
                .filter(rr -> rr.supports(SlotKind.RULE_REF))
                .findFirst().orElseThrow();
        assertThat(r).isInstanceOf(RuleRefResolver.class);
        TemplateSlot slot = new TemplateSlot("r", "规则", SlotKind.RULE_REF, null, false, null);
        r.validate("rule.score", slot, ctx); // 不抛异常
    }

    @Test
    void dispatch_byKind_valueKind_findsNone() {
        List<SlotRefResolver> valueResolvers = resolvers.stream()
                .filter(rr -> rr.supports(SlotKind.VALUE))
                .toList();
        assertThat(valueResolvers).isEmpty();
    }

    @Test
    void eachKind_hasExactlyOneResolver() {
        for (SlotKind kind : SlotKind.values()) {
            if (kind == SlotKind.VALUE) continue; // VALUE 无 resolver
            List<SlotRefResolver> matches = resolvers.stream()
                    .filter(rr -> rr.supports(kind))
                    .toList();
            assertThat(matches).as("kind=%s 应有且仅有一个 resolver", kind).hasSize(1);
        }
    }
}
