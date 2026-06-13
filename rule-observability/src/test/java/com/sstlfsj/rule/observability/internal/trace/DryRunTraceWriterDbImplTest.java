package com.sstlfsj.rule.observability.internal.trace;

import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import com.sstlfsj.rule.kernel.api.spi.trace.DryRunTraceWriter;
import com.sstlfsj.rule.observability.internal.domain.DryRunNodeTraceEntity;
import com.sstlfsj.rule.observability.internal.repository.DryRunNodeTraceMapper;
import com.sstlfsj.rule.observability.internal.repository.NodeTraceMapper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DryRunTraceWriterDbImplTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void implementsDryRunTraceWriter() {
        DryRunTraceWriterDbImpl writer = new DryRunTraceWriterDbImpl(100, 10, 50,
                mock(DryRunNodeTraceMapper.class), objectMapper);
        assertInstanceOf(DryRunTraceWriter.class, writer);
    }

    @Test
    void write_throwsNpe_beforeInit() {
        DryRunTraceWriterDbImpl writer = new DryRunTraceWriterDbImpl(100, 10, 50,
                mock(DryRunNodeTraceMapper.class), objectMapper);
        NodeTrace trace = new NodeTrace("LEAF", "AMOUNT_GT", "revenue", true, 100, "FETCHED", null, null, null, null, 0L, null, null);
        assertThrows(NullPointerException.class, () -> writer.write("t1", "s1", List.of(trace)));
    }

    @Test
    void afterPropertiesSet_startsConsumerThread() throws Exception {
        DryRunTraceWriterDbImpl writer = new DryRunTraceWriterDbImpl(100, 10, 50,
                mock(DryRunNodeTraceMapper.class), objectMapper);
        writer.afterPropertiesSet();
        try {
            NodeTrace trace = new NodeTrace("LEAF", "AMOUNT_GT", "revenue", true, 100, "FETCHED", null, null, null, null, 0L, null, null);
            assertDoesNotThrow(() -> writer.write("t1", "s1", List.of(trace)));
        } finally {
            writer.destroy();
        }
    }

    @Test
    void write_dropsEntriesWhenQueueFull() throws Exception {
        DryRunTraceWriterDbImpl writer = new DryRunTraceWriterDbImpl(1, 10, 60_000,
                mock(DryRunNodeTraceMapper.class), objectMapper);
        writer.afterPropertiesSet();
        try {
            NodeTrace trace = new NodeTrace("LEAF", "AMOUNT_GT", "revenue", true, 100, "FETCHED", null, null, null, null, 0L, null, null);
            assertDoesNotThrow(() -> {
                writer.write("t1", "s1", List.of(trace));
                writer.write("t1", "s2", List.of(trace));
            });
        } finally {
            writer.destroy();
        }
    }

    @Test
    void flushBatch_callsInsertBatch_notInsert() throws Exception {
        DryRunNodeTraceMapper mapper = mock(DryRunNodeTraceMapper.class);
        DryRunTraceWriterDbImpl w = new DryRunTraceWriterDbImpl(100, 10, 60_000, mapper, objectMapper);
        w.afterPropertiesSet();

        NodeTrace child = new NodeTrace("LEAF", "EQ", "score", false, 50, "FETCHED", null, null, null, null, 0L, null, null);
        NodeTrace root  = new NodeTrace("CONDITION", "GT", "revenue", true, 100, "FETCHED", null, List.of(child), 7L, null, 0L, null, null);
        w.write("1", "42", List.of(root));
        w.destroy();

        verify(mapper, atLeastOnce()).insertBatch(argThat(list -> list.size() == 2));
        verify(mapper, never()).insert(any(DryRunNodeTraceEntity.class));
    }

    @Test
    void flushBatch_setsDryRunSessionId_notEvaluationSessionId() throws Exception {
        DryRunNodeTraceMapper mapper = mock(DryRunNodeTraceMapper.class);
        DryRunTraceWriterDbImpl w = new DryRunTraceWriterDbImpl(100, 10, 60_000, mapper, objectMapper);
        w.afterPropertiesSet();

        NodeTrace root = new NodeTrace("CONDITION", "GT", "revenue", true, 100, "FETCHED", null, null, 42L, "RC-1", 3L, null, null);
        w.write("1", "99", List.of(root));
        w.destroy();

        verify(mapper, atLeastOnce()).insertBatch(argThat(list ->
                list.size() == 1
                && Long.valueOf(99L).equals(list.get(0).getDryRunSessionId())
                && Long.valueOf(42L).equals(list.get(0).getRuleVersionId())
                // 规则身份冗余键也须映射到 dry_run_node_trace 实体
                && "RC-1".equals(list.get(0).getRuleCode())
                && Long.valueOf(3L).equals(list.get(0).getRuleVersion())));
    }

    @Test
    void flushBatch_nodePath_rootUsesIndex_childAppendsDot() throws Exception {
        DryRunNodeTraceMapper mapper = mock(DryRunNodeTraceMapper.class);
        DryRunTraceWriterDbImpl w = new DryRunTraceWriterDbImpl(100, 10, 60_000, mapper, objectMapper);
        w.afterPropertiesSet();

        NodeTrace child = new NodeTrace("LEAF", "EQ", "score", false, 50, "FETCHED", null, null, null, null, 0L, null, null);
        NodeTrace root  = new NodeTrace("CONDITION", "GT", "revenue", true, 100, "FETCHED", null, List.of(child), 7L, null, 0L, null, null);
        w.write("1", "42", List.of(root));
        w.destroy();

        verify(mapper, atLeastOnce()).insertBatch(argThat(list -> {
            if (list.size() != 2) return false;
            return "0".equals(list.get(0).getNodePath())
                    && "0.0".equals(list.get(1).getNodePath());
        }));
    }

    @Test
    void write_doesNotCallNodeTraceMapper() throws Exception {
        // DryRunTraceWriterDbImpl 不得写 node_trace 表，只写 dry_run_node_trace
        NodeTraceMapper nodeTraceMapper = mock(NodeTraceMapper.class);
        DryRunNodeTraceMapper dryMapper = mock(DryRunNodeTraceMapper.class);
        DryRunTraceWriterDbImpl w = new DryRunTraceWriterDbImpl(100, 10, 60_000, dryMapper, objectMapper);
        w.afterPropertiesSet();

        NodeTrace trace = new NodeTrace("LEAF", "EQ", "score", false, 50, "FETCHED", null, null, null, null, 0L, null, null);
        w.write("1", "1", List.of(trace));
        w.destroy();

        verifyNoInteractions(nodeTraceMapper);
    }

    @Test
    void destroy_doesNotThrow_whenConsumerRunning() throws Exception {
        DryRunTraceWriterDbImpl writer = new DryRunTraceWriterDbImpl(100, 10, 50,
                mock(DryRunNodeTraceMapper.class), objectMapper);
        writer.afterPropertiesSet();
        assertDoesNotThrow(writer::destroy);
    }

    @Test
    void flushBatch_mapsValueSourceStringToEnum() throws Exception {
        DryRunNodeTraceMapper mapper = mock(DryRunNodeTraceMapper.class);
        DryRunTraceWriterDbImpl w = new DryRunTraceWriterDbImpl(100, 10, 60_000, mapper, objectMapper);
        w.afterPropertiesSet();

        // trace.valueSource() 是 String，落库实体字段是 kernel ValueSource 枚举
        NodeTrace leaf = new NodeTrace("LEAF", "GTE", "score", true, 100, "FETCHED", null, null, 7L, null, 0L, null, null);
        w.write("1", "42", List.of(leaf));
        w.destroy();

        verify(mapper, atLeastOnce()).insertBatch(argThat(list ->
                list.size() == 1
                && com.sstlfsj.rule.kernel.api.model.ValueSource.FETCHED.equals(list.get(0).getValueSource())));
    }

    @Test
    void flushBatch_mapsNullValueSourceToNull() throws Exception {
        DryRunNodeTraceMapper mapper = mock(DryRunNodeTraceMapper.class);
        DryRunTraceWriterDbImpl w = new DryRunTraceWriterDbImpl(100, 10, 60_000, mapper, objectMapper);
        w.afterPropertiesSet();

        // valueSource 为 null 时不应抛异常，落库为 null
        NodeTrace leaf = new NodeTrace("LEAF", "GTE", "score", true, 100, null, null, null, 7L, null, 0L, null, null);
        w.write("1", "42", List.of(leaf));
        w.destroy();

        verify(mapper, atLeastOnce()).insertBatch(argThat(list ->
                list.size() == 1 && list.get(0).getValueSource() == null));
    }

    @Test
    void flushBatch_setsDisplayLabelAndParamsJson_fromLeafTrace() throws Exception {
        DryRunNodeTraceMapper mapper = mock(DryRunNodeTraceMapper.class);
        DryRunTraceWriterDbImpl w = new DryRunTraceWriterDbImpl(100, 10, 60_000, mapper, objectMapper);
        w.afterPropertiesSet();

        // 叶子自携带 expectedValue（→params JSON）与 displayLabel（→display_label 列）
        NodeTrace leaf = new NodeTrace("ConditionNode", "GTE", "score", true, 100, "PROVIDED",
                null, null, 7L, null, 0L, Map.of("threshold", 0), "score>=0");
        w.write("1", "42", List.of(leaf));
        w.destroy();

        verify(mapper, atLeastOnce()).insertBatch(argThat(list ->
                list.size() == 1
                && "score>=0".equals(list.get(0).getDisplayLabel())
                && "{\"threshold\":0}".equals(list.get(0).getParams())));
    }

    @Test
    void flushBatch_jsonEncodesStringActualValue() throws Exception {
        DryRunNodeTraceMapper mapper = mock(DryRunNodeTraceMapper.class);
        DryRunTraceWriterDbImpl w = new DryRunTraceWriterDbImpl(100, 10, 60_000, mapper, objectMapper);
        w.afterPropertiesSet();

        // actual_value 是 JSON 列:字符串值须 JSON 编码("US" → "\"US\""),
        // 裸 toString 产生非法 JSON("US")会让整批 insert 被 MySQL 拒(回归)
        NodeTrace leaf = new NodeTrace("ConditionNode", "IN", "country", true, "US", "PAYLOAD",
                null, null, 7L, null, 0L, List.of("US", "RU"), "高风险国家");
        w.write("1", "42", List.of(leaf));
        w.destroy();

        verify(mapper, atLeastOnce()).insertBatch(argThat(list ->
                list.size() == 1 && "\"US\"".equals(list.get(0).getActualValue())));
    }

    @Test
    void flushBatch_numericActualValue_staysValidJson() throws Exception {
        DryRunNodeTraceMapper mapper = mock(DryRunNodeTraceMapper.class);
        DryRunTraceWriterDbImpl w = new DryRunTraceWriterDbImpl(100, 10, 60_000, mapper, objectMapper);
        w.afterPropertiesSet();

        // 数值不带引号,仍是合法 JSON 数字(5000 → "5000")
        NodeTrace leaf = new NodeTrace("ConditionNode", "GT", "amount", true, 5000, "PAYLOAD",
                null, null, 7L, null, 0L, Map.of("threshold", 1000), "金额>1000");
        w.write("1", "42", List.of(leaf));
        w.destroy();

        verify(mapper, atLeastOnce()).insertBatch(argThat(list ->
                list.size() == 1 && "5000".equals(list.get(0).getActualValue())));
    }
}
