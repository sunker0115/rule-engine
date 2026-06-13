package com.sstlfsj.rule.web.mask;

import com.sstlfsj.rule.audit.api.service.AuditService.TraceNodeEntry;
import com.sstlfsj.rule.audit.api.service.AuditService.TraceTreeNode;
import com.sstlfsj.rule.config.api.service.SceneService.SensitiveRefs;
import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import com.sstlfsj.rule.kernel.api.model.NodeType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TraceMaskerTest {

    private static final String MASK = "***";
    private static final SensitiveRefs REFS =
            new SensitiveRefs(Set.of("phone"), Set.of("user.idno"));

    private static TraceNodeEntry leaf(String metricCode, String actualValue, String valueSource) {
        return new TraceNodeEntry("0.0", "ConditionNode", "EQ", metricCode,
                actualValue, true, null, valueSource, "ruleA", 1L);
    }

    @Test
    void maskFlat_payloadSensitiveField_masked() {
        List<TraceNodeEntry> out = TraceMasker.maskFlat(REFS, List.of(leaf("phone", "13800001111", "PAYLOAD")));
        assertThat(out.get(0).actualValue()).isEqualTo(MASK);
    }

    @Test
    void maskFlat_payloadNonSensitiveField_kept() {
        List<TraceNodeEntry> out = TraceMasker.maskFlat(REFS, List.of(leaf("amount", "100", "PAYLOAD")));
        assertThat(out.get(0).actualValue()).isEqualTo("100");
    }

    @Test
    void maskFlat_metricSensitiveCode_masked() {
        List<TraceNodeEntry> out = TraceMasker.maskFlat(REFS, List.of(leaf("user.idno", "511...", "FETCHED")));
        assertThat(out.get(0).actualValue()).isEqualTo(MASK);
    }

    @Test
    void maskFlat_providedSource_judgedByMetricSet() {
        List<TraceNodeEntry> out = TraceMasker.maskFlat(REFS, List.of(leaf("user.idno", "x", "PROVIDED")));
        assertThat(out.get(0).actualValue()).isEqualTo(MASK);
    }

    @Test
    void maskFlat_nonSensitiveMetric_kept() {
        List<TraceNodeEntry> out = TraceMasker.maskFlat(REFS, List.of(leaf("user.age", "25", "FETCHED")));
        assertThat(out.get(0).actualValue()).isEqualTo("25");
    }

    @Test
    void maskFlat_nullMetricCode_notMasked() {
        List<TraceNodeEntry> out = TraceMasker.maskFlat(REFS, List.of(leaf(null, "x", "PAYLOAD")));
        assertThat(out.get(0).actualValue()).isEqualTo("x");
    }

    @Test
    void maskFlat_emptyRefs_noOp() {
        SensitiveRefs empty = new SensitiveRefs(Set.of(), Set.of());
        List<TraceNodeEntry> out = TraceMasker.maskFlat(empty, List.of(leaf("phone", "13800001111", "PAYLOAD")));
        assertThat(out.get(0).actualValue()).isEqualTo("13800001111");
    }

    @Test
    void maskFlat_nullRefs_failClosedMasksAllLeaves() {
        List<TraceNodeEntry> out = TraceMasker.maskFlat(null, List.of(
                leaf("phone", "13800001111", "PAYLOAD"),
                leaf("user.age", "25", "FETCHED")));
        assertThat(out.get(0).actualValue()).isEqualTo(MASK);
        assertThat(out.get(1).actualValue()).isEqualTo(MASK);
    }

    @Test
    void maskTree_recursesAndMasksDeepLeaf_containerUntouched() {
        TraceTreeNode deepLeaf = new TraceTreeNode("ConditionNode", "EQ", "phone",
                "13800001111", true, null, "PAYLOAD", "ruleA", 1L, List.of());
        TraceTreeNode container = new TraceTreeNode("AndNode", null, null,
                null, true, null, null, "ruleA", 1L, List.of(deepLeaf));

        List<TraceTreeNode> out = TraceMasker.maskTree(REFS, List.of(container));

        assertThat(out.get(0).actualValue()).isNull();
        assertThat(out.get(0).children().get(0).actualValue()).isEqualTo(MASK);
    }

    @Test
    void maskKernel_recursesAndMasksLeaf_containerUntouched() {
        NodeTrace leaf = new NodeTrace(NodeType.CONDITION.tag(), "EQ", "user.idno",
                true, "511...", "FETCHED", null, List.of(), 1L, "ruleA", 1L, null, null);
        NodeTrace container = NodeTrace.container(NodeType.AND, true, List.of(leaf), 1L);

        List<NodeTrace> out = TraceMasker.maskKernel(REFS, List.of(container));

        assertThat(out.get(0).actualValue()).isNull();
        assertThat(out.get(0).children().get(0).actualValue()).isEqualTo(MASK);
    }

    @Test
    void maskKernel_nullRefs_failClosedMasksLeavesWithValue() {
        NodeTrace leaf = new NodeTrace(NodeType.CONDITION.tag(), "EQ", "user.age",
                true, "25", "FETCHED", null, List.of(), 1L, "ruleA", 1L, null, null);
        List<NodeTrace> out = TraceMasker.maskKernel(null, List.of(leaf));
        assertThat(out.get(0).actualValue()).isEqualTo(MASK);
    }
}
