package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleSourceTest {

    private static RuleVersionSnapshot snap(long id, String tenant, String scene, String event) {
        return RuleVersionSnapshot.builder()
                .ruleVersionId(id).tenantId(tenant).sceneCode(scene)
                .conditionAst(new AndNode(List.of(), null, null))
                .addTriggerEventType(event)
                .build();
    }

    @Test
    void loadInto_lambdaImpl_writesIntoIndex() {
        SceneRuleIndex index = new SceneRuleIndex();
        RuleVersionSnapshot s = snap(1L, "t1", "fraud", "TX");

        // RuleSource 是 FI，可用 lambda 实现
        RuleSource source = idx -> idx.update("t1", "fraud", "TX", List.of(s));
        source.loadInto(index);

        assertThat(index.match("t1", "fraud", "TX")).containsExactly(s);
    }
}
