package com.sstlfsj.rule.config.internal.bundle;

import com.sstlfsj.rule.config.api.dto.RuleBundle;
import com.sstlfsj.rule.kernel.api.model.AstBody;
import com.sstlfsj.rule.kernel.api.model.FlowBody;
import com.sstlfsj.rule.kernel.api.model.ScriptBody;
import com.sstlfsj.rule.kernel.api.model.ScriptSource;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.flow.FlowGraph;
import com.sstlfsj.rule.kernel.api.model.flow.OutputNode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleContentHasherTest {

    private final ObjectMapper om = JsonMapper.builder().build();

    @Test
    void sameContent_sameHash() {
        var ast = new AstBody(new AndNode(List.of(new ConditionNode("GT", "amount", null, Map.of("threshold", 100), 0.0)), null, null));
        var bindings = List.of(new DecisionBinding("BLOCK", 100));

        String h1 = RuleContentHasher.ruleHash(ast, bindings, List.of(), "AST_BOOLEAN", List.of("login"), om);
        String h2 = RuleContentHasher.ruleHash(ast, bindings, List.of(), "AST_BOOLEAN", List.of("login"), om);

        assertThat(h1).isEqualTo(h2);
        assertThat(h1).hasSize(64);  // SHA-256 hex = 64 chars
    }

    @Test
    void differentContent_differentHash() {
        var ast1 = new AstBody(new AndNode(List.of(), null, null));
        var ast2 = new AstBody(new AndNode(List.of(new ConditionNode("GT", "amount", null, Map.of("threshold", 100), 0.0)), null, null));

        String h1 = RuleContentHasher.ruleHash(ast1, List.of(), List.of(), "AST_BOOLEAN", List.of(), om);
        String h2 = RuleContentHasher.ruleHash(ast2, List.of(), List.of(), "AST_BOOLEAN", List.of(), om);

        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void nullParams_treatedAsEmpty_stableHash() {
        // null 与空列表语义等价，hash 应相同（规范化处理）
        String h1 = RuleContentHasher.ruleHash(null, null, null, null, null, om);
        String h2 = RuleContentHasher.ruleHash(null, List.of(), List.of(), "AST_BOOLEAN", List.of(), om);

        assertThat(h1).isEqualTo(h2);
    }

    @Test
    void scriptDiffers_differentHash() {
        // script body 不同 → hash 不同
        var s1 = new ScriptBody(new ScriptSource("metrics.amount > 1000", "CEL"));
        var s2 = new ScriptBody(new ScriptSource("metrics.amount > 2000", "CEL"));

        String h1 = RuleContentHasher.ruleHash(s1, List.of(), List.of(), "EXPRESSION_SCRIPT", List.of(), om);
        String h2 = RuleContentHasher.ruleHash(s2, List.of(), List.of(), "EXPRESSION_SCRIPT", List.of(), om);

        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void flowGraphDiffers_differentHash() {
        // flow body 的 nodes 不同 → hash 不同（body 多态判别保证不同载体不撞）
        var f1 = new FlowBody(new FlowGraph(List.of(new OutputNode("out", "PASS")), List.of(), "out"), Map.of());
        var f2 = new FlowBody(new FlowGraph(List.of(new OutputNode("out", "REVIEW")), List.of(), "out"), Map.of());

        String h1 = RuleContentHasher.ruleHash(f1, List.of(), List.of(), "DECISION_FLOW", List.of(), om);
        String h2 = RuleContentHasher.ruleHash(f2, List.of(), List.of(), "DECISION_FLOW", List.of(), om);

        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void bundleRevision_dependsOnContentHashes() {
        var entry1 = new RuleBundle.RuleEntry("r1", "n", "AST_BOOLEAN", "s", null, List.of(), List.of(), List.of(), List.of(), List.of(), "hash-a");
        var entry2 = new RuleBundle.RuleEntry("r2", "n", "AST_BOOLEAN", "s", null, List.of(), List.of(), List.of(), List.of(), List.of(), "hash-b");

        RuleBundle b1 = new RuleBundle(2, null, "t", "t1", List.of(entry1, entry2), List.of(), List.of(), List.of());
        RuleBundle b2 = new RuleBundle(2, null, "t", "t1", List.of(entry1), List.of(), List.of(), List.of());

        assertThat(RuleContentHasher.bundleRevision(b1)).isNotEqualTo(RuleContentHasher.bundleRevision(b2));
        // 相同规则列表 → 相同 revision
        assertThat(RuleContentHasher.bundleRevision(b1)).isEqualTo(RuleContentHasher.bundleRevision(b1));
    }
}
