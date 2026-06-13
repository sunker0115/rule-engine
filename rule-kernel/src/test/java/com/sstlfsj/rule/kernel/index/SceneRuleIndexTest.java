package com.sstlfsj.rule.kernel.index;

import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SceneRuleIndexTest {

    @Test
    void defaultParams_setAndGet() {
        SceneRuleIndex index = new SceneRuleIndex();
        assertThat(index.getDefaultParams("1", "scene")).isEmpty();
        index.setDefaultParams("1", "scene", java.util.Map.of("timezone", "Asia/Shanghai"));
        assertThat(index.getDefaultParams("1", "scene")).containsEntry("timezone", "Asia/Shanghai");
    }
}
