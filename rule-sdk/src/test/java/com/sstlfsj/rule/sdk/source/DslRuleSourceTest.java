package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DslRuleSourceTest {

    private static RuleVersionSnapshot snap(long id, String event) {
        return RuleVersionSnapshot.builder()
                .ruleVersionId(id).tenantId("t1").sceneCode("fraud")
                .conditionAst(new AndNode(List.of(), null, null))
                .addTriggerEventType(event)
                .build();
    }

    @Test
    void loadInto_singleSnap_writesCorrectKey() {
        SceneRuleIndex index = new SceneRuleIndex();
        new DslRuleSource(List.of(snap(1L, "TX"))).loadInto(index);
        assertThat(index.match("t1", "fraud", "TX")).hasSize(1);
    }

    @Test
    void loadInto_multipleSnaps_allWritten() {
        SceneRuleIndex index = new SceneRuleIndex();
        new DslRuleSource(List.of(snap(1L, "TX"), snap(2L, "TX"))).loadInto(index);
        assertThat(index.match("t1", "fraud", "TX")).hasSize(2);
    }

    @Test
    void loadInto_idempotent_noDuplicate() {
        SceneRuleIndex index = new SceneRuleIndex();
        DslRuleSource source = new DslRuleSource(List.of(snap(1L, "TX")));
        source.loadInto(index);
        source.loadInto(index);  // 重复加载
        assertThat(index.match("t1", "fraud", "TX")).hasSize(1);
    }

    @Test
    void loadInto_emptyTriggerTypes_usesWildcard() {
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(3L).tenantId("t1").sceneCode("fraud")
                .conditionAst(new AndNode(List.of(), null, null))
                .build();  // 不调用 addTriggerEventType → 空列表
        SceneRuleIndex index = new SceneRuleIndex();
        new DslRuleSource(List.of(snap)).loadInto(index);
        assertThat(index.match("t1", "fraud", "ANY_EVENT")).hasSize(1);
    }
}
