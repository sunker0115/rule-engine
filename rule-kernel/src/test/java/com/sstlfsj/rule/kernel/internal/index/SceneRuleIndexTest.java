package com.sstlfsj.rule.kernel.internal.index;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.SceneExecutionStrategy;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SceneRuleIndexTest {

    private static RuleVersionSnapshot snap(Long id, String tenant, String scene) {
        return new RuleVersionSnapshot(id, scene, tenant,
                new AndNode(List.of(), null, null), List.of(), List.of(), List.of(), "AST_BOOLEAN");
    }

    @Test
    void match_exactEventType_returnsSnap() {
        SceneRuleIndex idx = new SceneRuleIndex();
        RuleVersionSnapshot s = snap(1L, "t1", "payment");
        idx.update("t1", "payment", "ORDER", List.of(s));
        assertThat(idx.match("t1", "payment", "ORDER")).containsExactly(s);
    }

    @Test
    void match_wildcardFallback_returnsSnap() {
        SceneRuleIndex idx = new SceneRuleIndex();
        RuleVersionSnapshot s = snap(2L, "t1", "payment");
        idx.update("t1", "payment", "*", List.of(s));
        assertThat(idx.match("t1", "payment", "ANYTHING")).containsExactly(s);
    }

    @Test
    void match_mergesExactAndWildcard_noDuplicates() {
        SceneRuleIndex idx = new SceneRuleIndex();
        RuleVersionSnapshot exact = snap(1L, "t1", "fraud");
        RuleVersionSnapshot wild  = snap(2L, "t1", "fraud");
        idx.update("t1", "fraud", "LOGIN", List.of(exact));
        idx.update("t1", "fraud", "*",     List.of(wild));
        assertThat(idx.match("t1", "fraud", "LOGIN")).hasSize(2).contains(exact, wild);
    }

    @Test
    void match_sameIdInBothBuckets_dedupedKeepsExact() {
        SceneRuleIndex idx = new SceneRuleIndex();
        RuleVersionSnapshot exact = snap(1L, "t1", "fraud");
        RuleVersionSnapshot wildSameId = snap(1L, "t1", "fraud");
        RuleVersionSnapshot wildOther = snap(2L, "t1", "fraud");
        idx.update("t1", "fraud", "LOGIN", List.of(exact));
        idx.update("t1", "fraud", "*", List.of(wildSameId, wildOther));

        // id=1 仅保留 exact 实例，id=2 从 wildcard 补入；exact 优先在前
        assertThat(idx.match("t1", "fraud", "LOGIN"))
                .containsExactly(exact, wildOther);
    }

    @Test
    void match_noEntry_returnsEmpty() {
        assertThat(new SceneRuleIndex().match("t1", "scene", "ORDER")).isEmpty();
    }

    @Test
    void remove_deletesAllEntriesForScene() {
        SceneRuleIndex idx = new SceneRuleIndex();
        idx.update("t1", "payment", "ORDER", List.of(snap(1L, "t1", "payment")));
        idx.remove("t1", "payment");
        assertThat(idx.match("t1", "payment", "ORDER")).isEmpty();
    }

    @Test
    void getStrategy_defaultIsHighestPriority() {
        SceneRuleIndex idx = new SceneRuleIndex();
        assertThat(idx.getStrategy("t1", "payment")).isEqualTo(SceneExecutionStrategy.HIGHEST_PRIORITY);
    }

    @Test
    void setStrategy_returnsConfiguredStrategy() {
        SceneRuleIndex idx = new SceneRuleIndex();
        idx.setStrategy("t1", "fraud", SceneExecutionStrategy.FIRST_HIT);
        assertThat(idx.getStrategy("t1", "fraud")).isEqualTo(SceneExecutionStrategy.FIRST_HIT);
    }

    @Test
    void remove_clearsStrategy() {
        SceneRuleIndex idx = new SceneRuleIndex();
        idx.setStrategy("t1", "fraud", SceneExecutionStrategy.ALL_HITS);
        idx.remove("t1", "fraud");
        assertThat(idx.getStrategy("t1", "fraud")).isEqualTo(SceneExecutionStrategy.HIGHEST_PRIORITY);
    }
}
