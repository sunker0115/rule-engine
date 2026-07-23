package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.kernel.api.model.FlowBody;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证导出 API ?format=snapshot 产出的 JSON 可被 FileRuleSource 正确加载并索引匹配。
 * 端到端评估需 CEL 引擎（不在 SDK 模块依赖范围），在集成测试中覆盖。
 */
class FileRuleSourceSnapshotTest {

    @Test
    void loadsAndMatchesBySceneAndEvent() {
        SceneRuleIndex index = new SceneRuleIndex();
        FileRuleSource.classpath("rules/demo-flow-exported.json").loadInto(index);

        List<RuleVersionSnapshot> snaps = index.match("9100", "stub.test", "test");
        assertThat(snaps).hasSize(1);
        assertThat(snaps.get(0).code()).isEqualTo("demo_flow_001");
        assertThat(snaps.get(0).kind()).isEqualTo("DECISION_FLOW");
        assertThat(snaps.get(0).sceneCode()).isEqualTo("stub.test");
        assertThat(snaps.get(0).tenantId()).isEqualTo("9100");
        assertThat(snaps.get(0).version()).isGreaterThan(0);
    }

    @Test
    void bodyContainsCompleteFlowGraph() {
        SceneRuleIndex index = new SceneRuleIndex();
        FileRuleSource.classpath("rules/demo-flow-exported.json").loadInto(index);

        RuleVersionSnapshot snap = index.match("9100", "stub.test", "test").get(0);
        assertThat(snap.body()).isInstanceOf(FlowBody.class);
        FlowBody fb = (FlowBody) snap.body();
        assertThat(fb.flowGraph().nodes()).hasSize(9);
        assertThat(fb.flowGraph().edges()).hasSize(8);
        assertThat(fb.flowGraph().inputNodeId()).isEqualTo("switch_1");
        assertThat(fb.referencedSnapshots()).containsKey("bool.test");
    }

    @Test
    void wireAllEventsMatches() {
        SceneRuleIndex index = new SceneRuleIndex();
        FileRuleSource.classpath("rules/demo-flow-exported.json").loadInto(index);

        // triggerEventTypes 为空 → 通配所有 eventType
        assertThat(index.match("9100", "stub.test", "any")).hasSize(1);
        assertThat(index.match("9100", "stub.test", "txn.submit")).hasSize(1);
    }

    @Test
    void sceneMismatchDoesNotMatch() {
        SceneRuleIndex index = new SceneRuleIndex();
        FileRuleSource.classpath("rules/demo-flow-exported.json").loadInto(index);

        assertThat(index.match("9100", "other_scene", "test")).isEmpty();
    }
}
