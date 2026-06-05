package com.sstlfsj.rule.config.internal.publish;

import com.sstlfsj.rule.kernel.api.model.ast.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AstDataTypeResolverTest {

    // ── 基础冻结 ──────────────────────────────────────────────────────────────

    @Test
    void resolve_conditionNode_freezesDataType() {
        ConditionNode cond = new ConditionNode("GT", "amount", null,
                Map.of("threshold", 100), 0.0);
        Map<String, String> typeMap = Map.of("amount", "LONG");

        AstNode result = AstDataTypeResolver.resolve(cond, typeMap);

        assertThat(result).isInstanceOf(ConditionNode.class);
        assertThat(((ConditionNode) result).dataType()).isEqualTo("LONG");
    }

    @Test
    void resolve_conditionNode_metricNotInMap_dataTypeRemainsNull() {
        ConditionNode cond = new ConditionNode("GT", "unknown_metric", null,
                Map.of("threshold", 100), 0.0);
        Map<String, String> typeMap = Map.of("amount", "LONG");

        AstNode result = AstDataTypeResolver.resolve(cond, typeMap);

        // 查不到的 metric -> 跳过冻结，dataType=null（不报错）
        assertThat(((ConditionNode) result).dataType()).isNull();
    }

    @Test
    void resolve_andNode_recursivelyFreezesChildren() {
        AndNode and = new AndNode(List.of(
                new ConditionNode("GT", "amount", null, Map.of("threshold", 100), 0.0),
                new ConditionNode("EQ", "status", null, Map.of("threshold", "ACTIVE"), 0.0)
        ), null, null);
        Map<String, String> typeMap = Map.of("amount", "LONG", "status", "STRING");

        AstNode result = AstDataTypeResolver.resolve(and, typeMap);

        assertThat(result).isInstanceOf(AndNode.class);
        List<AstNode> children = ((AndNode) result).children();
        assertThat(((ConditionNode) children.get(0)).dataType()).isEqualTo("LONG");
        assertThat(((ConditionNode) children.get(1)).dataType()).isEqualTo("STRING");
    }

    @Test
    void resolve_notNode_recursivelyFreezesChild() {
        NotNode not = new NotNode(new ConditionNode("EQ", "flag", null,
                Map.of("threshold", "true"), 0.0));
        Map<String, String> typeMap = Map.of("flag", "BOOLEAN");

        AstNode result = AstDataTypeResolver.resolve(not, typeMap);

        assertThat(result).isInstanceOf(NotNode.class);
        assertThat(((ConditionNode)((NotNode) result).child()).dataType()).isEqualTo("BOOLEAN");
    }

    @Test
    void resolve_orNode_recursivelyFreezesChildren() {
        OrNode or = new OrNode(List.of(
                new ConditionNode("EQ", "type", null, Map.of("threshold", "A"), 0.0)
        ), null, null);
        Map<String, String> typeMap = Map.of("type", "STRING");

        AstNode result = AstDataTypeResolver.resolve(or, typeMap);

        assertThat(result).isInstanceOf(OrNode.class);
        assertThat(((ConditionNode)((OrNode) result).children().get(0)).dataType())
                .isEqualTo("STRING");
    }

    @Test
    void resolve_xorNode_recursivelyFreezesChildren() {
        XorNode xor = new XorNode(List.of(
                new ConditionNode("EQ", "code", null, Map.of("threshold", "A"), 0.0)
        ), null);
        Map<String, String> typeMap = Map.of("code", "STRING");

        AstNode result = AstDataTypeResolver.resolve(xor, typeMap);

        assertThat(result).isInstanceOf(XorNode.class);
        assertThat(((ConditionNode)((XorNode) result).children().get(0)).dataType())
                .isEqualTo("STRING");
    }

    @Test
    void resolve_scorecardRootNode_freezesLeafDataTypes() {
        ScorecardRootNode sc = new ScorecardRootNode(List.of(
                new ConditionNode("GT", "score", null, Map.of("threshold", 60), 0.4)
        ), 0.6);
        Map<String, String> typeMap = Map.of("score", "DOUBLE");

        AstNode result = AstDataTypeResolver.resolve(sc, typeMap);

        assertThat(result).isInstanceOf(ScorecardRootNode.class);
        assertThat(((ScorecardRootNode) result).conditions().get(0).dataType())
                .isEqualTo("DOUBLE");
    }

    @Test
    void resolve_ifNode_recursivelyFreezesConditionAndBranches() {
        IfNode ifn = new IfNode(
                new ConditionNode("GT", "amount", null, Map.of("threshold", 1000), 0.0),
                new DecisionLeafNode("BLOCK", "HIGH"),
                new DecisionLeafNode("PASS", "LOW")
        );
        Map<String, String> typeMap = Map.of("amount", "LONG");

        AstNode result = AstDataTypeResolver.resolve(ifn, typeMap);

        assertThat(result).isInstanceOf(IfNode.class);
        IfNode resolved = (IfNode) result;
        assertThat(((ConditionNode) resolved.condition()).dataType()).isEqualTo("LONG");
        // DecisionLeafNode 原样返回（无 dataType 概念）
        assertThat(resolved.thenBranch()).isInstanceOf(DecisionLeafNode.class);
    }

    @Test
    void resolve_decisionTableNode_returnedAsIs() {
        // B19 不冻结决策表列的 dataType，DecisionTableNode 原样返回
        DecisionTableNode dt = new DecisionTableNode(
                List.of(new DecisionTableNode.Column("amount", "GT")),
                List.of(new DecisionTableNode.Row(List.of(1000), "BLOCK"))
        );
        Map<String, String> typeMap = Map.of("amount", "LONG");

        AstNode result = AstDataTypeResolver.resolve(dt, typeMap);

        assertThat(result).isSameAs(dt);
    }

    // ── 兼容性校验 ────────────────────────────────────────────────────────────

    @Test
    void resolve_gtWithBoolean_throwsIllegalArgument() {
        // GT 不允许 BOOLEAN dataType -> 发布期报错
        ConditionNode cond = new ConditionNode("GT", "flag", null,
                Map.of("threshold", "true"), 0.0);
        Map<String, String> typeMap = Map.of("flag", "BOOLEAN");

        assertThatThrownBy(() -> AstDataTypeResolver.resolve(cond, typeMap))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GT")
                .hasMessageContaining("BOOLEAN");
    }

    @Test
    void resolve_inWithBoolean_throwsIllegalArgument() {
        // IN 允许 LONG/STRING，BOOLEAN 不在允许集 -> 报错
        ConditionNode cond = new ConditionNode("IN", "flag", null,
                Map.of("values", List.of("true")), 0.0);
        Map<String, String> typeMap = Map.of("flag", "BOOLEAN");

        assertThatThrownBy(() -> AstDataTypeResolver.resolve(cond, typeMap))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IN")
                .hasMessageContaining("BOOLEAN");
    }

    @Test
    void resolve_eqWithBoolean_ok() {
        // EQ 允许 BOOLEAN -> 不报错
        ConditionNode cond = new ConditionNode("EQ", "flag", null,
                Map.of("threshold", "true"), 0.0);
        Map<String, String> typeMap = Map.of("flag", "BOOLEAN");

        AstNode result = AstDataTypeResolver.resolve(cond, typeMap);
        assertThat(((ConditionNode) result).dataType()).isEqualTo("BOOLEAN");
    }

    @Test
    void resolve_dataTypeNull_skipsCompatibilityCheck() {
        // metric 查不到（dataType=null）-> 跳过校验，不报错
        ConditionNode cond = new ConditionNode("GT", "unknown", null,
                Map.of("threshold", 100), 0.0);
        // 不在 typeMap 里
        AstNode result = AstDataTypeResolver.resolve(cond, Map.of());
        assertThat(((ConditionNode) result).dataType()).isNull();
    }

    @Test
    void resolve_dataTypeList_skipsCompatibilityCheck() {
        // LIST dataType 跳过校验（CONTAINS/NOT_CONTAINS 自洽，B19 不做矩阵校验）
        ConditionNode cond = new ConditionNode("CONTAINS", "tags", null,
                Map.of("value", "vip"), 0.0);
        Map<String, String> typeMap = Map.of("tags", "LIST");

        AstNode result = AstDataTypeResolver.resolve(cond, typeMap);
        assertThat(((ConditionNode) result).dataType()).isEqualTo("LIST");
    }
}
