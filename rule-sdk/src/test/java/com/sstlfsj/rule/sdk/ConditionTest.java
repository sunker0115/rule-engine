package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.ConditionParams;
import com.sstlfsj.rule.kernel.api.model.ast.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConditionTest {

    @Test
    void gt_producesConditionNode() {
        AstNode ast = Condition.gt("amount", 1000).toAst();
        assertThat(ast).isInstanceOf(ConditionNode.class);
        ConditionNode node = (ConditionNode) ast;
        assertThat(node.conditionType()).isEqualTo("GT");
        assertThat(node.metricCode()).isEqualTo("amount");
        assertThat(node.params().get(ConditionParams.THRESHOLD)).isEqualTo(1000);
        assertThat(node.weight()).isEqualTo(0.0);
        assertThat(node.displayLabel()).isNull();
    }

    @Test
    void matches_usesRegexParamKey() {
        // 回归：param 键必须是 "regex"(MatchesEvaluator 读 "regex"，旧 "pattern" 键会永不命中)
        AstNode ast = Condition.matches("phone", "\\d{11}").toAst();
        ConditionNode node = (ConditionNode) ast;
        assertThat(node.conditionType()).isEqualTo("MATCHES");
        assertThat(node.params().get(ConditionParams.REGEX)).isEqualTo("\\d{11}");
        assertThat(node.params()).doesNotContainKey("pattern");
    }

    @Test
    void in_producesConditionNodeWithValuesList() {
        AstNode ast = Condition.in("country", "CN", "HK").toAst();
        ConditionNode node = (ConditionNode) ast;
        assertThat(node.conditionType()).isEqualTo("IN");
        assertThat(node.params().get(ConditionParams.VALUES)).isEqualTo(List.of("CN", "HK"));
    }

    @Test
    void between_producesConditionNodeWithMinMax() {
        AstNode ast = Condition.between("age", 18, 65).toAst();
        ConditionNode node = (ConditionNode) ast;
        assertThat(node.conditionType()).isEqualTo("BETWEEN");
        assertThat(node.params().get(ConditionParams.MIN)).isEqualTo(18);
        assertThat(node.params().get(ConditionParams.MAX)).isEqualTo(65);
    }

    @Test
    void and_producesAndNode() {
        AstNode ast = Condition.gt("amount", 1000)
                .and(Condition.in("country", "CN", "HK"))
                .toAst();
        assertThat(ast).isInstanceOf(AndNode.class);
        assertThat(((AndNode) ast).children()).hasSize(2);
    }

    @Test
    void or_producesOrNode() {
        AstNode ast = Condition.gt("amount", 1000)
                .or(Condition.eq("vip", true))
                .toAst();
        assertThat(ast).isInstanceOf(OrNode.class);
        assertThat(((OrNode) ast).children()).hasSize(2);
    }

    @Test
    void not_producesNotNode() {
        AstNode ast = Condition.eq("blocked", true).not().toAst();
        assertThat(ast).isInstanceOf(NotNode.class);
        assertThat(((NotNode) ast).child()).isInstanceOf(ConditionNode.class);
    }

    @Test
    void always_producesEmptyAndNode() {
        AstNode ast = Condition.always().toAst();
        assertThat(ast).isInstanceOf(AndNode.class);
        assertThat(((AndNode) ast).children()).isEmpty();
    }

    @Test
    void never_producesEmptyOrNode() {
        AstNode ast = Condition.never().toAst();
        assertThat(ast).isInstanceOf(OrNode.class);
        assertThat(((OrNode) ast).children()).isEmpty();
    }

    @Test
    void of_customOperator_producesConditionNode() {
        AstNode ast = Condition.of("BLACKLIST_HIT", "device_id",
                Map.of("list", List.of("dev-001"))).toAst();
        ConditionNode node = (ConditionNode) ast;
        assertThat(node.conditionType()).isEqualTo("BLACKLIST_HIT");
        assertThat(node.params().get("list")).isEqualTo(List.of("dev-001"));
    }

    @Test
    void of_twoArg_customOperator_hasNullMetric() {
        AstNode ast = Condition.of("BUSINESS_HOURS", Map.of("tz", "Asia/Shanghai")).toAst();
        ConditionNode node = (ConditionNode) ast;
        assertThat(node.conditionType()).isEqualTo("BUSINESS_HOURS");
        assertThat(node.metricCode()).isNull();
        assertThat(node.params().get("tz")).isEqualTo("Asia/Shanghai");
    }

    @Test
    void chained_andConditions_flattenedIntoOneAndNode() {
        AstNode ast = Condition.gt("a", 1)
                .and(Condition.gt("b", 2))
                .and(Condition.gt("c", 3))
                .toAst();
        assertThat(ast).isInstanceOf(AndNode.class);
        assertThat(((AndNode) ast).children()).hasSize(3);
    }

    @Test
    void chained_orConditions_flattenedIntoOneOrNode() {
        AstNode ast = Condition.gt("a", 1)
                .or(Condition.gt("b", 2))
                .or(Condition.gt("c", 3))
                .toAst();
        assertThat(ast).isInstanceOf(OrNode.class);
        assertThat(((OrNode) ast).children()).hasSize(3);
    }
}
