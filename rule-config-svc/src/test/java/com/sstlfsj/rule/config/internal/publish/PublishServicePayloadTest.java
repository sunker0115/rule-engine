package com.sstlfsj.rule.config.internal.publish;

import com.sstlfsj.rule.config.api.dto.PayloadFieldSpec;
import com.sstlfsj.rule.config.internal.domain.*;
import com.sstlfsj.rule.config.internal.repository.*;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.ValueRef;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** 发布期 payload 引用校验：valueRef=PAYLOAD 字段必须在 scene.payloadSchema 声明。 */
@ExtendWith(MockitoExtension.class)
class PublishServicePayloadTest {

    @Mock RuleDefinitionMapper ruleDefinitionMapper;
    @Mock SceneMapper sceneMapper;
    @Mock RuleVersionMapper ruleVersionMapper;
    @Mock DecisionDefinitionMapper decisionDefinitionMapper;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock MetricDefinitionMapper metricDefinitionMapper;

    @InjectMocks PublishService publishService;

    private RuleDefinition draftRule;
    private SceneDef scene;
    private RuleVersion draftVersion;

    @BeforeEach
    void setUp() {
        draftRule = new RuleDefinition();
        draftRule.setId(10L);
        draftRule.setTenantId(1L);
        draftRule.setSceneId(5L);
        draftRule.setCode("rule.demo");
        draftRule.setName("测试规则");
        draftRule.setStatus(RuleDefinitionStatus.DRAFT);
        draftRule.setKind(RuleKind.AST_BOOLEAN);

        scene = new SceneDef();
        scene.setId(5L);
        scene.setCode("PAYMENT");
        scene.setEventTypes(java.util.List.of("payment.initiated"));
        scene.setStatus(SceneStatus.ACTIVE);

        draftVersion = new RuleVersion();
        draftVersion.setId(100L);
        draftVersion.setRuleDefinitionId(10L);
        draftVersion.setVersion(0L);
        draftVersion.setDecisionBindings(List.of());
        draftVersion.setPreGates(List.of());
        draftVersion.setStatus(RuleVersionStatus.DRAFT);
    }

    @Test
    void publish_payloadFieldNotDeclaredInSchema_throws() {
        // 规则引用 payload 字段 amount，但 scene.payloadSchema 只声明了 channel → 发布拒绝
        draftVersion.setConditionAst(new ConditionNode("GT", "amount", null,
                Map.of("threshold", 1000), 0.0, null, ValueRef.PAYLOAD));
        scene.setPayloadSchema(List.of(
                new PayloadFieldSpec("channel", "STRING", false, null, null, null, null, null)));

        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(any())).thenReturn(draftVersion);

        assertThatThrownBy(() -> publishService.publish(1L, 10L, "actor"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount");
    }
}
