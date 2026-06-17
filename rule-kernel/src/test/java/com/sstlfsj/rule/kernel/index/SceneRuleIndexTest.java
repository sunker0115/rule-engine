package com.sstlfsj.rule.kernel.index;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SceneRuleIndexTest {

    private static RuleVersionSnapshot snap(long id) {
        return new RuleVersionSnapshot(id, "s", "1", null, List.of(), List.of(), null, null);
    }

    @Test
    void defaultParams_setAndGet() {
        SceneRuleIndex index = new SceneRuleIndex();
        assertThat(index.getDefaultParams("1", "scene")).isEmpty();
        index.setDefaultParams("1", "scene", Map.of("timezone", "Asia/Shanghai"));
        assertThat(index.getDefaultParams("1", "scene")).containsEntry("timezone", "Asia/Shanghai");
    }

    @Test
    void replaceScene_emptyMap_removesResidualBuckets() {
        // 该 scene 规则全部禁用 → 空 Map → 残留旧桶必须被摘除（否则已禁用规则仍命中）
        SceneRuleIndex index = new SceneRuleIndex();
        index.update("1", "s", "login", List.of(snap(1L)));
        assertThat(index.match("1", "s", "login")).hasSize(1);

        index.replaceScene("1", "s", Map.of());

        assertThat(index.match("1", "s", "login")).isEmpty();
    }

    @Test
    void replaceScene_putsNewBuckets_andDropsStaleOnes() {
        SceneRuleIndex index = new SceneRuleIndex();
        index.update("1", "s", "login", List.of(snap(1L)));
        index.update("1", "s", "logout", List.of(snap(1L)));

        // 新集合只剩 login 桶并替换内容 → logout 旧桶被摘除、login 替换为新快照
        index.replaceScene("1", "s", Map.of("login", List.of(snap(2L))));

        assertThat(index.match("1", "s", "login"))
                .extracting(RuleVersionSnapshot::ruleVersionId).containsExactly(2L);
        assertThat(index.match("1", "s", "logout")).isEmpty();
    }

    @Test
    void replaceScene_doesNotAffectOtherScenes() {
        SceneRuleIndex index = new SceneRuleIndex();
        index.update("1", "other", "login", List.of(snap(1L)));

        index.replaceScene("1", "s", Map.of());

        assertThat(index.match("1", "other", "login")).hasSize(1);
    }
}
