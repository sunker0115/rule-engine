package com.sstlfsj.rule.config.internal.domain;

import com.sstlfsj.rule.kernel.api.model.MetricDependency;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.PreGateConfig;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 RuleVersion Lombok getter/setter 及 typed JSON 字段覆盖。 */
class RuleVersionTest {

    @Test
    void getterSetter_roundTrip() {
        RuleVersion ver = new RuleVersion();
        ver.setId(5L);
        ver.setRuleDefinitionId(10L);
        ver.setVersion(1L);
        AstNode ast = new AndNode(List.of(), null, null);
        ver.setConditionAst(ast);
        ver.setDecisionBindings(List.of(new DecisionBinding("BLOCK", 100)));
        ver.setPreGates(List.of(new PreGateConfig("ROLLOUT", Map.of("percentage", 50))));
        ver.setKind("AST_BOOLEAN");
        ver.setTriggerEventTypes(List.of("payment.initiated"));
        ver.setMetricDependencies(List.of(new MetricDependency("user.age", 1)));
        ver.setStatus("ACTIVE");
        ver.setPublishedBy("operator1");

        assertEquals(5L, ver.getId());
        assertEquals(10L, ver.getRuleDefinitionId());
        assertEquals(1L, ver.getVersion());
        assertSame(ast, ver.getConditionAst());
        assertEquals("BLOCK", ver.getDecisionBindings().getFirst().decisionCode());
        assertEquals("ROLLOUT", ver.getPreGates().getFirst().gateType());
        assertEquals("AST_BOOLEAN", ver.getKind());
        assertEquals(List.of("payment.initiated"), ver.getTriggerEventTypes());
        assertEquals("user.age", ver.getMetricDependencies().getFirst().metricCode());
        assertEquals("ACTIVE", ver.getStatus());
        assertEquals("operator1", ver.getPublishedBy());
    }

    @Test
    void defaultValues_areNull() {
        RuleVersion ver = new RuleVersion();
        assertNull(ver.getId());
        assertNull(ver.getConditionAst());
        assertNull(ver.getPublishedAt());
        assertNull(ver.getCreatedAt());
    }
}
