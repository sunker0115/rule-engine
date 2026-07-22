package com.sstlfsj.rule.config.publish;

import com.sstlfsj.rule.config.internal.domain.DecisionDefinition;
import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import com.sstlfsj.rule.config.internal.domain.MetricStatus;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleVersion;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.publish.PublishService;
import com.sstlfsj.rule.config.internal.repository.DecisionDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.MetricDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleVersionMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import com.sstlfsj.rule.expression.cel.CelExpressionEngine;
import com.sstlfsj.rule.kernel.api.model.ExpressionLang;
import com.sstlfsj.rule.kernel.api.model.MetricDependency;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import com.sstlfsj.rule.kernel.api.model.flow.FlowEdge;
import com.sstlfsj.rule.kernel.api.model.flow.FlowGraph;
import com.sstlfsj.rule.kernel.api.model.flow.OutputNode;
import com.sstlfsj.rule.kernel.api.model.flow.RuleRefNode;
import com.sstlfsj.rule.kernel.api.model.flow.SwitchNode;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 验证 resolveAndValidate 的 DECISION_FLOW 分支（图编排层）：
 * ① RuleRefNode 冻被引规则 ACTIVE 快照、Switch 表达式 metric + 被引 metric 并集、OutputNode 决策冻结；
 * ② 无 ACTIVE 被引规则 / 跨 Scene RuleRef 拒绝发布；③ 结构非法（缺 input / 孤儿节点 / Output 决策不存在）拒绝。
 * 用真实 {@link CelExpressionEngine} 注入 PublishService，mapper 用 mock。
 */
@ExtendWith(MockitoExtension.class)
class FlowResolveValidateTest {

    @Mock RuleDefinitionMapper ruleDefinitionMapper;
    @Mock SceneMapper sceneMapper;
    @Mock RuleVersionMapper ruleVersionMapper;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock MetricDefinitionMapper metricDefinitionMapper;
    @Mock DecisionDefinitionMapper decisionDefinitionMapper;

    private PublishService publishService;
    private SceneDef scene;

    @BeforeEach
    void setUp() {
        List<ExpressionEngine> engines = List.of(new CelExpressionEngine());
        publishService = new PublishService(ruleDefinitionMapper, sceneMapper, ruleVersionMapper,
                eventPublisher, metricDefinitionMapper, decisionDefinitionMapper, engines);

        scene = new SceneDef();
        scene.setId(5L);
        scene.setCode("PAYMENT");
        scene.setEventTypes(List.of("payment.initiated"));
    }

    /** RuleRefNode 引用同 Scene 有 ACTIVE 版本的规则：冻其完整快照，Switch 表达式 metric 与被引 metric 取并集，Output 冻决策。 */
    @Test
    void flowBranch_freezesReferencedSnapshot_unionsMetricDeps_freezesOutputDecision() {
        // 被引规则 sub_rule 存在于同 Scene(id=5)，有 ACTIVE 版本(version=3)，其 metricDeps=[txn_cnt_1d v2]
        RuleDefinition ref = new RuleDefinition();
        ref.setId(100L);
        ref.setCode("sub_rule");
        when(ruleDefinitionMapper.findBySceneAndCode(any(), any(), any())).thenReturn(ref);
        RuleVersion active = new RuleVersion();
        active.setId(200L);
        active.setVersion(3L);
        active.setKind(RuleKind.AST_BOOLEAN);
        active.setMetricDependencies(List.of(new MetricDependency("txn_cnt_1d", 2)));
        when(ruleVersionMapper.findActiveVersion(any())).thenReturn(active);

        // Switch 表达式引用 metrics.amount_sum_1d → 冻当前 ACTIVE(version=1)
        MetricDefinition md = new MetricDefinition();
        md.setMetricCode("amount_sum_1d");
        md.setDataType("DOUBLE");
        md.setVersion(1);
        md.setStatus(MetricStatus.ACTIVE);
        when(metricDefinitionMapper.findActiveByCodes(any(), any())).thenReturn(List.of(md));

        // OutputNode 决策码 REVIEW 须在 decision_definition 存在，冻 name/priority
        DecisionDefinition dd = new DecisionDefinition();
        dd.setCode("REVIEW");
        dd.setName("人工审核");
        dd.setPriority(10);
        when(decisionDefinitionMapper.findByCodes(any(), any())).thenReturn(List.of(dd));

        FlowGraph flow = new FlowGraph(
                List.of(
                        new RuleRefNode("n1", "sub_rule"),
                        new SwitchNode("n2", ExpressionLang.CEL,
                                "metrics.amount_sum_1d > 100 ? 'HIGH' : 'LOW'", List.of("HIGH", "LOW")),
                        new OutputNode("n3", "REVIEW")),
                List.of(new FlowEdge("n1", "n2", null), new FlowEdge("n2", "n3", "HIGH")),
                "n1");

        PublishService.ResolvedDraft resolved = publishService.resolveAndValidate(
                1L, scene, RuleKind.DECISION_FLOW,
                null, List.of(), List.of(), List.of(), null, flow);

        // 被引规则完整快照冻入 referencedSnapshots
        assertThat(resolved.referencedSnapshots()).containsKey("sub_rule");
        RuleVersionSnapshot refSnap = resolved.referencedSnapshots().get("sub_rule");
        assertThat(refSnap.ruleVersionId()).isEqualTo(200L);
        assertThat(refSnap.version()).isEqualTo(3L);
        assertThat(refSnap.code()).isEqualTo("sub_rule");
        assertThat(refSnap.sceneCode()).isEqualTo("PAYMENT");
        // metricDeps 并集：被引 txn_cnt_1d(v2) + Switch 表达式 amount_sum_1d(v1)
        assertThat(resolved.metricDeps()).containsExactlyInAnyOrder(
                new MetricDependency("txn_cnt_1d", 2), new MetricDependency("amount_sum_1d", 1));
        // Output 决策冻 name/priority
        assertThat(resolved.decisionBindings())
                .containsExactly(new DecisionBinding("REVIEW", "人工审核", 10));
        // 图规则不进 AST，脚本为 null，图原样冻入
        assertThat(resolved.resolvedAst()).isNull();
        assertThat(resolved.scriptSource()).isNull();
        assertThat(resolved.flowGraph()).isEqualTo(flow);
        assertThat(resolved.kind()).isEqualTo(RuleKind.DECISION_FLOW);
    }

    /** 被引规则存在但无 ACTIVE 版本 → 拒绝发布（仿 metric 无 ACTIVE 的拒绝）。 */
    @Test
    void flowBranch_referencedRuleNoActiveVersion_throws() {
        RuleDefinition ref = new RuleDefinition();
        ref.setId(100L);
        ref.setCode("sub_rule");
        when(ruleDefinitionMapper.findBySceneAndCode(any(), any(), any())).thenReturn(ref);
        when(ruleVersionMapper.findActiveVersion(any())).thenReturn(null);

        FlowGraph flow = new FlowGraph(
                List.of(new RuleRefNode("n1", "sub_rule"), new OutputNode("n2", "REVIEW")),
                List.of(new FlowEdge("n1", "n2", null)),
                "n1");

        assertThatThrownBy(() -> publishService.resolveAndValidate(
                1L, scene, RuleKind.DECISION_FLOW,
                null, List.of(), List.of(), List.of(), null, flow))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无 ACTIVE 版本");
    }

    /** RuleRef 引用的规则不在本 Scene（findBySceneAndCode 查不到）→ 跨 Scene 拒绝发布（v1 限同 Scene）。 */
    @Test
    void flowBranch_crossSceneRuleRef_throws() {
        // 同 Scene 下查不到该 code（规则属于别的 Scene）
        when(ruleDefinitionMapper.findBySceneAndCode(any(), any(), any())).thenReturn(null);

        FlowGraph flow = new FlowGraph(
                List.of(new RuleRefNode("n1", "other_scene_rule"), new OutputNode("n2", "REVIEW")),
                List.of(new FlowEdge("n1", "n2", null)),
                "n1");

        assertThatThrownBy(() -> publishService.resolveAndValidate(
                1L, scene, RuleKind.DECISION_FLOW,
                null, List.of(), List.of(), List.of(), null, flow))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("同一 Scene");
    }

    /** 缺 inputNodeId → 结构校验拒绝。 */
    @Test
    void flowBranch_missingInputNode_throws() {
        FlowGraph flow = new FlowGraph(
                List.of(new OutputNode("n1", "REVIEW")),
                List.of(),
                null);

        assertThatThrownBy(() -> publishService.resolveAndValidate(
                1L, scene, RuleKind.DECISION_FLOW,
                null, List.of(), List.of(), List.of(), null, flow))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inputNodeId");
    }

    /** 孤儿节点（非入口且无边连接）→ 结构校验拒绝。 */
    @Test
    void flowBranch_orphanNode_throws() {
        FlowGraph flow = new FlowGraph(
                List.of(new OutputNode("n1", "REVIEW"), new OutputNode("n2", "PASS")),
                List.of(),
                "n1");

        assertThatThrownBy(() -> publishService.resolveAndValidate(
                1L, scene, RuleKind.DECISION_FLOW,
                null, List.of(), List.of(), List.of(), null, flow))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("孤儿节点");
    }

    /** OutputNode.decisionCode 在 decision_definition 不存在 → 冻结期拒绝（DECISION_CODE_NOT_FOUND）。 */
    @Test
    void flowBranch_outputDecisionNotFound_throws() {
        // 单节点图：入口即 OutputNode，无边/无孤儿；决策码 MISSING 查不到
        when(decisionDefinitionMapper.findByCodes(any(), any())).thenReturn(List.of());

        FlowGraph flow = new FlowGraph(
                List.of(new OutputNode("n1", "MISSING")),
                List.of(),
                "n1");

        assertThatThrownBy(() -> publishService.resolveAndValidate(
                1L, scene, RuleKind.DECISION_FLOW,
                null, List.of(), List.of(), List.of(), null, flow))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DECISION_CODE_NOT_FOUND");
    }

    /** Switch 出边 caseKey 不在该 Switch.caseKeys 内 → 结构校验拒绝。 */
    @Test
    void flowBranch_switchEdgeCaseKeyNotInCaseKeys_throws() {
        FlowGraph flow = new FlowGraph(
                List.of(
                        new SwitchNode("n1", ExpressionLang.CEL, "1 > 0 ? 'A' : 'B'", List.of("A", "B")),
                        new OutputNode("n2", "REVIEW")),
                // 出边 caseKey=C 不在 caseKeys[A,B]
                List.of(new FlowEdge("n1", "n2", "C")),
                "n1");

        assertThatThrownBy(() -> publishService.resolveAndValidate(
                1L, scene, RuleKind.DECISION_FLOW,
                null, List.of(), List.of(), List.of(), null, flow))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("caseKey");
    }
}
