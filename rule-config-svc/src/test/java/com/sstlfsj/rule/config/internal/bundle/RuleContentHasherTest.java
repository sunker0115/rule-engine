package com.sstlfsj.rule.config.internal.bundle;

import com.sstlfsj.rule.config.api.dto.RuleBundle;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import com.sstlfsj.rule.kernel.api.model.ScriptSource;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
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
        var ast = new AndNode(List.of(new ConditionNode("GT", "amount", null, Map.of("threshold", 100), 0.0)), null, null);
        var bindings = List.of(new DecisionBinding("BLOCK", 100));

        String h1 = RuleContentHasher.ruleHash(ast, bindings, List.of(), "AST_BOOLEAN", List.of("login"), null, om);
        String h2 = RuleContentHasher.ruleHash(ast, bindings, List.of(), "AST_BOOLEAN", List.of("login"), null, om);

        assertThat(h1).isEqualTo(h2);
        assertThat(h1).hasSize(64);  // SHA-256 hex = 64 chars
    }

    @Test
    void differentContent_differentHash() {
        var ast1 = new AndNode(List.of(), null, null);
        var ast2 = new AndNode(List.of(new ConditionNode("GT", "amount", null, Map.of("threshold", 100), 0.0)), null, null);

        String h1 = RuleContentHasher.ruleHash(ast1, List.of(), List.of(), "AST_BOOLEAN", List.of(), null, om);
        String h2 = RuleContentHasher.ruleHash(ast2, List.of(), List.of(), "AST_BOOLEAN", List.of(), null, om);

        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void nullParams_treatedAsEmpty_stableHash() {
        // null 与空列表语义等价，hash 应相同（规范化处理）
        String h1 = RuleContentHasher.ruleHash(null, null, null, null, null, null, om);
        String h2 = RuleContentHasher.ruleHash(null, List.of(), List.of(), "AST_BOOLEAN", List.of(), null, om);

        assertThat(h1).isEqualTo(h2);
    }

    @Test
    void scriptDiffers_differentHash() {
        // script 不同 → hash 不同
        ScriptSource s1 = new ScriptSource("metrics.amount > 1000", "CEL");
        ScriptSource s2 = new ScriptSource("metrics.amount > 2000", "CEL");

        String h1 = RuleContentHasher.ruleHash(null, List.of(), List.of(), "EXPRESSION_SCRIPT", List.of(), s1, om);
        String h2 = RuleContentHasher.ruleHash(null, List.of(), List.of(), "EXPRESSION_SCRIPT", List.of(), s2, om);

        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void bundleRevision_dependsOnContentHashes() {
        var entry1 = new RuleBundle.RuleEntry("r1", "n", "AST_BOOLEAN", "s", null, List.of(), List.of(), List.of(), List.of(), List.of(), null, "hash-a");
        var entry2 = new RuleBundle.RuleEntry("r2", "n", "AST_BOOLEAN", "s", null, List.of(), List.of(), List.of(), List.of(), List.of(), null, "hash-b");

        RuleBundle b1 = new RuleBundle(2, null, "t", "t1", List.of(entry1, entry2), List.of(), List.of(), List.of());
        RuleBundle b2 = new RuleBundle(2, null, "t", "t1", List.of(entry1), List.of(), List.of(), List.of());

        assertThat(RuleContentHasher.bundleRevision(b1)).isNotEqualTo(RuleContentHasher.bundleRevision(b2));
        // 相同规则列表 → 相同 revision
        assertThat(RuleContentHasher.bundleRevision(b1)).isEqualTo(RuleContentHasher.bundleRevision(b1));
    }
}
