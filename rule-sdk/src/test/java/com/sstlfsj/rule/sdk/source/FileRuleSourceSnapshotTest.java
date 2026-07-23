package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.kernel.api.model.FlowBody;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证导出 API ?format=snapshot 产出的 JSON 可被 FileRuleSource 正确加载。
 */
class FileRuleSourceSnapshotTest {

    @Test
    void exportedDecisionFlowSnapshot_loadsIntoIndex() {
        SceneRuleIndex index = new SceneRuleIndex();
        FileRuleSource.classpath("rules/demo-flow-exported.json").loadInto(index);

        // 场景+事件类型匹配
        List<RuleVersionSnapshot> snaps = index.match("9100", "stub.test", "test");
        assertThat(snaps).hasSize(1);
        assertThat(snaps.get(0).code()).isEqualTo("demo_flow_001");
        assertThat(snaps.get(0).kind()).isEqualTo("DECISION_FLOW");
        assertThat(snaps.get(0).sceneCode()).isEqualTo("stub.test");
        assertThat(snaps.get(0).tenantId()).isEqualTo("9100");
        assertThat(snaps.get(0).version()).isGreaterThan(0);

        // FlowBody 完整
        assertThat(snaps.get(0).body()).isInstanceOf(FlowBody.class);
        FlowBody fb = (FlowBody) snaps.get(0).body();
        assertThat(fb.flowGraph().nodes()).isNotEmpty();
        assertThat(fb.flowGraph().inputNodeId()).isEqualTo("switch_1");
    }

    @Test
    void wireAllEvents_matches() {
        // 快照 triggerEventTypes 为空 → 匹配任意 eventType
        SceneRuleIndex index = new SceneRuleIndex();
        FileRuleSource.classpath("rules/demo-flow-exported.json").loadInto(index);

        assertThat(index.match("9100", "stub.test", "any_event")).hasSize(1);
        assertThat(index.match("9100", "stub.test", "txn.submit")).hasSize(1);
    }

    @Test
    void sceneMismatch_doesNotMatch() {
        SceneRuleIndex index = new SceneRuleIndex();
        FileRuleSource.classpath("rules/demo-flow-exported.json").loadInto(index);

        assertThat(index.match("9100", "other_scene", "test")).isEmpty();
    }
}
