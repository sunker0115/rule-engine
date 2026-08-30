package com.sstlfsj.rule.config.internal.publish;

import com.sstlfsj.rule.config.api.dto.PayloadFieldSpec;
import com.sstlfsj.rule.config.api.dto.RuleContent;
import com.sstlfsj.rule.config.internal.domain.*;
import com.sstlfsj.rule.config.internal.repository.*;
import com.sstlfsj.rule.kernel.api.model.AstBody;
import com.sstlfsj.rule.kernel.api.model.PayloadDependency;
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

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/** 草稿期 payload 引用校验（premise A）：valueRef=PAYLOAD 字段必须在 scene.payloadSchema 声明。 */
@ExtendWith(MockitoExtension.class)
class PublishServicePayloadTest {

    @Mock RuleDefinitionMapper ruleDefinitionMapper;
    @Mock SceneMapper sceneMapper;
    @Mock RuleVersionMapper ruleVersionMapper;
    @Mock DecisionDefinitionMapper decisionDefinitionMapper;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock MetricDefinitionMapper metricDefinitionMapper;

    @InjectMocks PublishService publishService;

    private SceneDef scene;

    @BeforeEach
    void setUp() {
        scene = new SceneDef();
        scene.setId(5L);
        scene.setTenantId(1L);
        scene.setCode("PAYMENT");
        scene.setEventTypes(java.util.List.of("payment.initiated"));
        scene.setStatus(SceneStatus.ACTIVE);
    }

    @Test
    void createDraft_payloadFieldNotDeclaredInSchema_throws() {
        // 规则引用 payload 字段 amount，但 scene.payloadSchema 只声明了 channel → 建草稿即拒绝（premise A）
        scene.setPayloadSchema(List.of(
                new PayloadFieldSpec("channel", "STRING", false, null, null, null, null, null)));
        when(sceneMapper.findByCode(any(), any())).thenReturn(scene);
        when(ruleDefinitionMapper.findByTenantAndCode(any(), any())).thenReturn(null);
        doAnswer(inv -> { inv.getArgument(0, RuleDefinition.class).setId(10L); return 1; })
                .when(ruleDefinitionMapper).insert(any(RuleDefinition.class));

        assertThatThrownBy(() -> publishService.createDraft(1L, "PAYMENT", "rule.demo",
                new RuleContent("测试规则", "AST_BOOLEAN",
                        new AstBody(new ConditionNode("GT", "amount", null, Map.of("threshold", 1000), 0.0, null, ValueRef.PAYLOAD)),
                        List.of(), List.of(), List.of()),
                "actor"))
                .isInstanceOf(IllegalArgumentException.class)
                // 消息须带语义前缀 UNRESOLVED_VARIABLE（项目"消息前缀=语义错误码"约定，docs 10-api-contract §七）
                .hasMessageStartingWith("UNRESOLVED_VARIABLE")
                .hasMessageContaining("amount");
    }

    @Test
    void freezePayloadDeps_carriesFullConstraintsFromSchema() throws Exception {
        // 发布期冻结:enum/min/max/pattern 须从 PayloadFieldSpec 完整流入 PayloadDependency
        scene.setPayloadSchema(List.of(
                new PayloadFieldSpec("amount", "INTEGER", true, null, 1.0, 1000.0, null, null),
                new PayloadFieldSpec("channel", "STRING", false, List.of("APP", "WEB"), null, null, "[A-Z]+", null)));

        Method m = PublishService.class.getDeclaredMethod(
                "freezePayloadDeps", SceneDef.class, List.class, Map.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<PayloadDependency> deps = (List<PayloadDependency>) m.invoke(
                publishService, scene, List.of("amount", "channel"), new HashMap<String, String>());

        PayloadDependency amount = deps.stream().filter(d -> d.name().equals("amount")).findFirst().orElseThrow();
        assertThat(amount.required()).isTrue();
        assertThat(amount.minimum()).isEqualTo(1.0);
        assertThat(amount.maximum()).isEqualTo(1000.0);
        assertThat(amount.enumValues()).isNull();
        assertThat(amount.pattern()).isNull();

        PayloadDependency channel = deps.stream().filter(d -> d.name().equals("channel")).findFirst().orElseThrow();
        assertThat(channel.required()).isFalse();
        assertThat(channel.enumValues()).containsExactly("APP", "WEB");
        assertThat(channel.pattern()).isEqualTo("[A-Z]+");
        assertThat(channel.minimum()).isNull();
        assertThat(channel.maximum()).isNull();
    }
}
