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

        AstNode result = AstDataTypeResolver.resolve(cond, typeMap, Map.of());

        assertThat(result).isInstanceOf(ConditionNode.class);
        assertThat(((ConditionNode) result).dataType()).isEqualTo("LONG");
    }

    @Test
    void resolve_conditionNode_existingDataTypeOverwrittenByCurrentWorld() {
        // 回退克隆场景：输入 AST 已带 dataType(STRING)，重解析应按当前世界 typeMap 覆盖为 LONG，
        // 不读取既有值（保证"按当前世界重解析"）。
        ConditionNode preResolved = new ConditionNode("GT", "amount", null,
                Map.of("threshold", 100), 0.0, "STRING", com.sstlfsj.rule.kernel.api.model.ValueRef.METRIC);
        Map<String, String> typeMap = Map.of("amount", "LONG");

        AstNode result = AstDataTypeResolver.resolve(preResolved, typeMap, Map.of());

        assertThat(((ConditionNode) result).dataType()).isEqualTo("LONG");
    }

    @Test
    void resolve_conditionNode_metricNotInMap_dataTypeRemainsNull() {
        ConditionNode cond = new ConditionNode("GT", "unknown_metric", null,
                Map.of("threshold", 100), 0.0);
        Map<String, String> typeMap = Map.of("amount", "LONG");

        AstNode result = AstDataTypeResolver.resolve(cond, typeMap, Map.of());

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

        AstNode result = AstDataTypeResolver.resolve(and, typeMap, Map.of());

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

        AstNode result = AstDataTypeResolver.resolve(not, typeMap, Map.of());

        assertThat(result).isInstanceOf(NotNode.class);
        assertThat(((ConditionNode)((NotNode) result).child()).dataType()).isEqualTo("BOOLEAN");
    }

    @Test
    void resolve_orNode_recursivelyFreezesChildren() {
        OrNode or = new OrNode(List.of(
                new ConditionNode("EQ", "type", null, Map.of("threshold", "A"), 0.0)
        ), null, null);
        Map<String, String> typeMap = Map.of("type", "STRING");

        AstNode result = AstDataTypeResolver.resolve(or, typeMap, Map.of());

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

        AstNode result = AstDataTypeResolver.resolve(xor, typeMap, Map.of());

        assertThat(result).isInstanceOf(XorNode.class);
        assertThat(((ConditionNode)((XorNode) result).children().get(0)).dataType())
                .isEqualTo("STRING");
    }

    @Test
    void resolve_scorecardRootNode_freezesLeafDataTypes() {
        ScorecardRootNode sc = new ScorecardRootNode(List.of(
                new ConditionNode("GT", "score", null, Map.of("threshold", 60), 0.4)
        ), 0.6, java.util.List.of());
        Map<String, String> typeMap = Map.of("score", "DOUBLE");

        AstNode result = AstDataTypeResolver.resolve(sc, typeMap, Map.of());

        assertThat(result).isInstanceOf(ScorecardRootNode.class);
        assertThat(((ScorecardRootNode) result).conditions().get(0).dataType())
                .isEqualTo("DOUBLE");
    }

    @Test
    void resolve_scorecardRootNode_preservesBands() {
        // resolve 重建 ScorecardRootNode 时必须保留 bands（发布期 band decisionCode 回填依赖此）
        com.sstlfsj.rule.kernel.api.model.ast.ScoreBand band =
                new com.sstlfsj.rule.kernel.api.model.ast.ScoreBand(0, 60, "REJECT", "HIGH");
        ScorecardRootNode sc = new ScorecardRootNode(List.of(
                new ConditionNode("GT", "score", null, Map.of("threshold", 60), 0.4)
        ), 0.6, List.of(band));

        AstNode result = AstDataTypeResolver.resolve(sc, Map.of("score", "DOUBLE"), Map.of());

        assertThat(((ScorecardRootNode) result).bands()).containsExactly(band);
    }

    @Test
    void resolve_ifNode_recursivelyFreezesConditionAndBranches() {
        IfNode ifn = new IfNode(
                new ConditionNode("GT", "amount", null, Map.of("threshold", 1000), 0.0),
                new DecisionLeafNode("BLOCK", "HIGH"),
                new DecisionLeafNode("PASS", "LOW")
        );
        Map<String, String> typeMap = Map.of("amount", "LONG");

        AstNode result = AstDataTypeResolver.resolve(ifn, typeMap, Map.of());

        assertThat(result).isInstanceOf(IfNode.class);
        IfNode resolved = (IfNode) result;
        assertThat(((ConditionNode) resolved.condition()).dataType()).isEqualTo("LONG");
        // DecisionLeafNode 原样返回（无 dataType 概念）
        assertThat(resolved.thenBranch()).isInstanceOf(DecisionLeafNode.class);
    }

    @Test
    void resolve_decisionTableNode_freezesColumnDataType() {
        // B22：决策表列从 metricCode 冻结 dataType，返回重建的新树
        DecisionTableNode dt = new DecisionTableNode(
                List.of(new DecisionTableNode.Column("amount", "GT")),
                List.of(new DecisionTableNode.Row(List.of(1000), "BLOCK"))
        );
        Map<String, String> typeMap = Map.of("amount", "LONG");

        AstNode result = AstDataTypeResolver.resolve(dt, typeMap, Map.of());

        assertThat(result).isInstanceOf(DecisionTableNode.class);
        DecisionTableNode resolved = (DecisionTableNode) result;
        assertThat(resolved.columns().get(0).dataType()).isEqualTo("LONG");
        // rows 原样保留
        assertThat(resolved.rows().get(0).decisionCode()).isEqualTo("BLOCK");
    }

    @Test
    void resolve_decisionTableColumn_invalidOperatorDataType_throws() {
        // B22：GT 不允许 STRING dataType → 发布期报错（与 ConditionNode 校验同矩阵）
        DecisionTableNode dt = new DecisionTableNode(
                List.of(new DecisionTableNode.Column("country", "GT")),
                List.of(new DecisionTableNode.Row(List.of("CN"), "BLOCK"))
        );
        Map<String, String> typeMap = Map.of("country", "STRING");

        assertThatThrownBy(() -> AstDataTypeResolver.resolve(dt, typeMap, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GT");
    }

    @Test
    void resolve_decisionLeafNode_returnedAsIs() {
        // 决策树终端叶子：无比较、无 metric，原样返回（永久边界，非待办）
        DecisionLeafNode leaf = new DecisionLeafNode("BLOCK", "HIGH");

        AstNode result = AstDataTypeResolver.resolve(leaf, Map.of(), Map.of());

        assertThat(result).isSameAs(leaf);
    }

    // ── 兼容性校验 ────────────────────────────────────────────────────────────

    @Test
    void resolve_gtWithBoolean_throwsIllegalArgument() {
        // GT 不允许 BOOLEAN dataType -> 发布期报错
        ConditionNode cond = new ConditionNode("GT", "flag", null,
                Map.of("threshold", "true"), 0.0);
        Map<String, String> typeMap = Map.of("flag", "BOOLEAN");

        assertThatThrownBy(() -> AstDataTypeResolver.resolve(cond, typeMap, Map.of()))
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

        assertThatThrownBy(() -> AstDataTypeResolver.resolve(cond, typeMap, Map.of()))
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

        AstNode result = AstDataTypeResolver.resolve(cond, typeMap, Map.of());
        assertThat(((ConditionNode) result).dataType()).isEqualTo("BOOLEAN");
    }

    @Test
    void resolve_dataTypeNull_skipsCompatibilityCheck() {
        // metric 查不到（dataType=null）-> 跳过校验，不报错
        ConditionNode cond = new ConditionNode("GT", "unknown", null,
                Map.of("threshold", 100), 0.0);
        // 不在 typeMap 里
        AstNode result = AstDataTypeResolver.resolve(cond, Map.of(), Map.of());
        assertThat(((ConditionNode) result).dataType()).isNull();
    }

    @Test
    void resolve_containsWithList_passesViaAllowed() {
        // CONTAINS + LIST -> ALLOWED.get("CONTAINS")={LIST}，LIST∈ -> 经矩阵校验通过，dataType 冻结为 LIST
        ConditionNode cond = new ConditionNode("CONTAINS", "tags", null,
                Map.of("value", "vip"), 0.0);
        Map<String, String> typeMap = Map.of("tags", "LIST");

        AstNode result = AstDataTypeResolver.resolve(cond, typeMap, Map.of());
        assertThat(((ConditionNode) result).dataType()).isEqualTo("LIST");
    }

    // ── CONTAINS / 字符串算子兼容矩阵（补全 spec §5）────────────────────────────

    @Test
    void resolve_containsWithString_throwsIllegalArgument() {
        // CONTAINS 仅允许 LIST；STRING metric 挂 CONTAINS 算子 -> 发布期拒绝
        ConditionNode cond = new ConditionNode("CONTAINS", "name", null,
                Map.of("value", "foo"), 0.0);
        Map<String, String> typeMap = Map.of("name", "STRING");

        assertThatThrownBy(() -> AstDataTypeResolver.resolve(cond, typeMap, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CONTAINS")
                .hasMessageContaining("STRING");
    }

    @Test
    void resolve_gtWithList_throwsIllegalArgument() {
        // GT 允许 LONG/DOUBLE；LIST metric 挂 GT -> 发布期拒绝（修复：LIST 不再绕过矩阵校验）
        ConditionNode cond = new ConditionNode("GT", "tags", null,
                Map.of("threshold", 1), 0.0);
        Map<String, String> typeMap = Map.of("tags", "LIST");

        assertThatThrownBy(() -> AstDataTypeResolver.resolve(cond, typeMap, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GT")
                .hasMessageContaining("LIST");
    }

    @Test
    void resolve_startsWithWithLong_throwsIllegalArgument() {
        // STARTS_WITH 仅允许 STRING；LONG metric 挂 STARTS_WITH -> 发布期拒绝
        ConditionNode cond = new ConditionNode("STARTS_WITH", "amount", null,
                Map.of("value", "10"), 0.0);
        Map<String, String> typeMap = Map.of("amount", "LONG");

        assertThatThrownBy(() -> AstDataTypeResolver.resolve(cond, typeMap, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("STARTS_WITH")
                .hasMessageContaining("LONG");
    }

    @Test
    void resolve_startsWithWithString_ok() {
        // STARTS_WITH + STRING -> 通过，dataType 冻结为 STRING
        ConditionNode cond = new ConditionNode("STARTS_WITH", "code", null,
                Map.of("value", "CN"), 0.0);
        Map<String, String> typeMap = Map.of("code", "STRING");

        AstNode result = AstDataTypeResolver.resolve(cond, typeMap, Map.of());
        assertThat(((ConditionNode) result).dataType()).isEqualTo("STRING");
    }

    // ── DECIMAL 数值算子兼容矩阵 ──────────────────────────────────────────────

    @Test
    void resolve_gteWithDecimal_ok() {
        // GTE 允许 DECIMAL（精确小数/金额）-> 不报错，dataType 冻结为 DECIMAL
        ConditionNode cond = new ConditionNode("GTE", "balance", null,
                Map.of("threshold", "9999.99"), 0.0);
        Map<String, String> typeMap = Map.of("balance", "DECIMAL");

        AstNode result = AstDataTypeResolver.resolve(cond, typeMap, Map.of());
        assertThat(((ConditionNode) result).dataType()).isEqualTo("DECIMAL");
    }

    @Test
    void resolve_inWithDecimal_throwsIllegalArgument() {
        // IN 仅允许 LONG/STRING；DECIMAL 不在允许集 -> 报错（DECIMAL 不渗入非数值算子）
        ConditionNode cond = new ConditionNode("IN", "balance", null,
                Map.of("values", List.of("9999.99")), 0.0);
        Map<String, String> typeMap = Map.of("balance", "DECIMAL");

        assertThatThrownBy(() -> AstDataTypeResolver.resolve(cond, typeMap, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IN")
                .hasMessageContaining("DECIMAL");
    }

    // ── B20 时间行 ────────────────────────────────────────────────────────────

    @Test
    void resolve_eqWithDate_ok() {
        ConditionNode cond = new ConditionNode("EQ", "joinDate", null,
                Map.of("threshold", "2026-06-01"), 0.0);
        AstNode result = AstDataTypeResolver.resolve(cond, Map.of("joinDate", "DATE"), Map.of());
        assertThat(((ConditionNode) result).dataType()).isEqualTo("DATE");
    }

    @Test
    void resolve_betweenWithDatetime_ok() {
        ConditionNode cond = new ConditionNode("BETWEEN", "ts", null,
                Map.of("min", "2026-01-01T00:00:00Z", "max", "2026-06-01T00:00:00Z"), 0.0);
        AstNode result = AstDataTypeResolver.resolve(cond, Map.of("ts", "DATETIME"), Map.of());
        assertThat(((ConditionNode) result).dataType()).isEqualTo("DATETIME");
    }

    @Test
    void resolve_dateBeforeWithDate_ok() {
        ConditionNode cond = new ConditionNode("DATE_BEFORE", "joinDate", null,
                Map.of("threshold", "2026-06-01"), 0.0);
        AstNode result = AstDataTypeResolver.resolve(cond, Map.of("joinDate", "DATE"), Map.of());
        assertThat(((ConditionNode) result).dataType()).isEqualTo("DATE");
    }

    @Test
    void resolve_dateBeforeWithLong_throwsIllegalArgument() {
        // DATE_BEFORE 现在只允许 DATE/DATETIME，LONG 被拒
        ConditionNode cond = new ConditionNode("DATE_BEFORE", "amount", null,
                Map.of("threshold", 100), 0.0);
        assertThatThrownBy(() -> AstDataTypeResolver.resolve(cond, Map.of("amount", "LONG"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DATE_BEFORE")
                .hasMessageContaining("LONG");
    }

    @Test
    void resolve_dateAfterWithString_throwsIllegalArgument() {
        ConditionNode cond = new ConditionNode("DATE_AFTER", "name", null,
                Map.of("threshold", "x"), 0.0);
        assertThatThrownBy(() -> AstDataTypeResolver.resolve(cond, Map.of("name", "STRING"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DATE_AFTER")
                .hasMessageContaining("STRING");
    }
}
