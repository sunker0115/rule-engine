package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileRuleSourceTest {

    @Test
    void classpath_loadsAndWritesIntoIndex() {
        SceneRuleIndex index = new SceneRuleIndex();
        FileRuleSource.classpath("rules/test-rule.json").loadInto(index);

        List<RuleVersionSnapshot> snaps = index.match("t1", "test", "TEST_EVENT");
        assertThat(snaps).hasSize(1);
        assertThat(snaps.get(0).ruleVersionId()).isEqualTo(100L);
        assertThat(snaps.get(0).sceneCode()).isEqualTo("test");
        assertThat(snaps.get(0).kind()).isEqualTo("AST_BOOLEAN");
    }

    @Test
    void classpath_nonExistent_throwsIllegalArgument() {
        assertThatThrownBy(() -> FileRuleSource.classpath("rules/no-such-file.json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("classpath 资源不存在");
    }
}
