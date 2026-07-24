package com.sstlfsj.rule.config.internal.template;

import com.sstlfsj.rule.config.api.dto.*;
import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.*;
import com.sstlfsj.rule.kernel.api.model.flow.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonPointerBinderTest {

    static JsonPointerBinder binder;

    @BeforeAll
    static void setUp() {
        binder = new JsonPointerBinder(new ObjectMapper());
    }

    @Test
    void supports_astBody() {
        assertThat(binder.supports(new AstBody(null))).isTrue();
    }

    @Test
    void supports_scriptBody() {
        assertThat(binder.supports(new ScriptBody(new ScriptSource("e", "CEL")))).isTrue();
    }

    @Test
    void supports_flowBody() {
        assertThat(binder.supports(new FlowBody(
                new FlowGraph(List.of(), List.of(), "in"), Map.of()))).isTrue();
    }

    // ---- AST: ConditionNode params 深层绑定 ----
    @Test
    void bind_conditionNode_replacesThreshold() {
        AstBody skeleton = new AstBody(
                new AndNode(List.of(new ConditionNode("GT", "amount", "金额大于",
                        Map.of("threshold", 100), 0.0)), null, null));
        SlotBinding binding = new SlotBinding("t",
                new JsonPointerTarget("/conditionAst/children/0/params/threshold"));
        RuleBody result = binder.bind(skeleton, List.of(binding), Map.of("t", 200));
        AstBody ab = (AstBody) result;
        ConditionNode cn = (ConditionNode) ((AndNode) ab.conditionAst()).children().get(0);
        assertThat(cn.params().get("threshold")).isEqualTo(200);
    }

    // ---- ScorecardRootNode：叶子 condition weight（3 参构造：conditions + threshold + bands） ----
    @Test
    void bind_scorecardCondition_weight() {
        ScorecardRootNode root = new ScorecardRootNode(
                List.of(new ConditionNode("GT", "score", ">60", Map.of("threshold", 60), 0.3)),
                0.0, List.of());
        AstBody skeleton = new AstBody(root);
        SlotBinding binding = new SlotBinding("w",
                new JsonPointerTarget("/conditionAst/conditions/0/weight"));
        RuleBody result = binder.bind(skeleton, List.of(binding), Map.of("w", 0.5));
        AstBody ab = (AstBody) result;
        ScorecardRootNode sc = (ScorecardRootNode) ab.conditionAst();
        assertThat(sc.conditions().get(0).weight()).isEqualTo(0.5);
    }

    // ---- DecisionTableNode：比较值在 Row.conditions（数组元素位替换） ----
    @Test
    void bind_decisionTableRowCell() {
        DecisionTableNode dt = new DecisionTableNode(
                List.of(new DecisionTableNode.Column("amount", "GT", "LONG", ValueRef.METRIC)),
                List.of(new DecisionTableNode.Row(List.of(100), "PASS")));
        AstBody skeleton = new AstBody(dt);
        SlotBinding binding = new SlotBinding("t",
                new JsonPointerTarget("/conditionAst/rows/0/conditions/0"));
        RuleBody result = binder.bind(skeleton, List.of(binding), Map.of("t", 200));
        AstBody ab = (AstBody) result;
        DecisionTableNode resultDt = (DecisionTableNode) ab.conditionAst();
        assertThat(resultDt.rows().get(0).conditions().get(0)).isEqualTo(200);
    }

    // ---- IfNode 深层（DECISION_TREE） ----
    @Test
    void bind_ifNodeCondition_threshold() {
        IfNode ifn = new IfNode(
                new AndNode(List.of(new ConditionNode("GT", "amt", "",
                        Map.of("threshold", 50), 0.0)), null, null),
                new DecisionLeafNode("PASS", null), null);
        AstBody skeleton = new AstBody(ifn);
        SlotBinding binding = new SlotBinding("t",
                new JsonPointerTarget("/conditionAst/condition/children/0/params/threshold"));
        RuleBody result = binder.bind(skeleton, List.of(binding), Map.of("t", 75));
        AstBody ab = (AstBody) result;
        IfNode resultIf = (IfNode) ab.conditionAst();
        AndNode andNode = (AndNode) resultIf.condition();
        ConditionNode cn = (ConditionNode) andNode.children().get(0);
        assertThat(cn.params().get("threshold")).isEqualTo(75);
    }

    // ---- Script params ----
    @Test
    void bind_scriptParams() {
        ScriptSource src = new ScriptSource("params.t > 0", "CEL", Map.of("t", 1));
        ScriptBody skeleton = new ScriptBody(src);
        SlotBinding binding = new SlotBinding("t",
                new JsonPointerTarget("/script/params/t"));
        RuleBody result = binder.bind(skeleton, List.of(binding), Map.of("t", 99));
        ScriptBody sb = (ScriptBody) result;
        assertThat(sb.script().params().get("t")).isEqualTo(99);
    }

    // ---- 校验：pointer 可解析 ----
    @Test
    void validate_unresolvablePointer_throws() {
        AstBody skeleton = new AstBody(
                new AndNode(List.of(new ConditionNode("GT", "a", "", Map.of("t", 1), 0.0)), null, null));
        SlotBinding binding = new SlotBinding("t",
                new JsonPointerTarget("/conditionAst/children/999/params/x"));
        assertThatThrownBy(() -> binder.validate(skeleton, List.of(binding), List.of(
                new TemplateSlot("t", "", DataType.LONG, true, null))))
                .hasMessageContaining("TEMPLATE_BINDING_UNRESOLVABLE");
    }

    // ---- 校验：slot↔binding 不一一对应 ----
    @Test
    void validate_slotBindingMismatch_extraSlot_throws() {
        AstBody skeleton = new AstBody(
                new AndNode(List.of(new ConditionNode("GT", "a", "", Map.of("t", 1), 0.0)), null, null));
        SlotBinding binding = new SlotBinding("t",
                new JsonPointerTarget("/conditionAst/children/0/params/t"));
        assertThatThrownBy(() -> binder.validate(skeleton, List.of(binding), List.of(
                new TemplateSlot("t", "", DataType.LONG, true, null),
                new TemplateSlot("extra", "", DataType.STRING, false, null))))
                .hasMessageContaining("TEMPLATE_SLOT_BINDING_MISMATCH");
    }

    // ---- 校验：ScriptBody 拒 /script/source ----
    @Test
    void validate_scriptSourcePointer_rejected() {
        ScriptBody skeleton = new ScriptBody(new ScriptSource("expr", "CEL"));
        SlotBinding binding = new SlotBinding("s",
                new JsonPointerTarget("/script/source"));
        assertThatThrownBy(() -> binder.validate(skeleton, List.of(binding), List.of(
                new TemplateSlot("s", "", DataType.STRING, true, null))))
                .hasMessageContaining("TEMPLATE_TARGET_FORBIDDEN");
    }

    // ---- 校验：FlowBody 拒 /referencedSnapshots ----
    @Test
    void validate_flowRefSnapshots_rejected() {
        FlowBody skeleton = new FlowBody(
                new FlowGraph(List.of(new OutputNode("o", "PASS")), List.of(), "o"), Map.of());
        SlotBinding binding = new SlotBinding("r",
                new JsonPointerTarget("/referencedSnapshots/someRule"));
        assertThatThrownBy(() -> binder.validate(skeleton, List.of(binding), List.of(
                new TemplateSlot("r", "", DataType.STRING, true, null))))
                .hasMessageContaining("TEMPLATE_TARGET_FORBIDDEN");
    }

    // ---- 省略的可选 slot：不用 null 覆盖 skeleton 默认值 ----
    @Test
    void bind_omittedOptionalSlot_preservesSkeletonDefault() {
        AstBody skeleton = new AstBody(new AndNode(List.of(
                new ConditionNode("GT", "amount", "金额",
                        Map.of("threshold", 100, "cap", 500), 0.0)), null, null));
        SlotBinding thresholdBinding = new SlotBinding("t",
                new JsonPointerTarget("/conditionAst/children/0/params/threshold"));
        SlotBinding capBinding = new SlotBinding("c",
                new JsonPointerTarget("/conditionAst/children/0/params/cap"));
        // 仅传 t，省略可选的 c → c 处应保留 skeleton 默认值 500，而非被 null 抹掉
        RuleBody result = binder.bind(skeleton, List.of(thresholdBinding, capBinding), Map.of("t", 200));
        AstBody ab = (AstBody) result;
        ConditionNode cn = (ConditionNode) ((AndNode) ab.conditionAst()).children().get(0);
        assertThat(cn.params().get("threshold")).isEqualTo(200);
        assertThat(cn.params().get("cap")).isEqualTo(500);
    }

    @Test
    void bind_preservesSkeleton() {
        AstBody skeleton = new AstBody(
                new AndNode(List.of(new ConditionNode("GT", "a", "", Map.of("t", 100), 0.0)), null, null));
        SlotBinding binding = new SlotBinding("t",
                new JsonPointerTarget("/conditionAst/children/0/params/t"));
        RuleBody result = binder.bind(skeleton, List.of(binding), Map.of("t", 200));
        ConditionNode originalCn = (ConditionNode) ((AndNode) skeleton.conditionAst()).children().get(0);
        assertThat(originalCn.params().get("t")).isEqualTo(100);
        assertThat(result).isNotSameAs(skeleton);
    }
}
