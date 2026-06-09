package com.sstlfsj.rule.observability.internal.trace;

import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import com.sstlfsj.rule.kernel.api.spi.trace.TraceWriter;
import com.sstlfsj.rule.observability.internal.domain.NodeTraceEntity;
import com.sstlfsj.rule.observability.internal.repository.NodeTraceMapper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class TraceWriterDbImplTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void implementsTraceWriter() {
        TraceWriterDbImpl writer = new TraceWriterDbImpl(100, 10, 50, mock(NodeTraceMapper.class), objectMapper);
        assertInstanceOf(TraceWriter.class, writer);
    }

    @Test
    void write_throwsNpe_beforeInit() {
        TraceWriterDbImpl writer = new TraceWriterDbImpl(100, 10, 50, mock(NodeTraceMapper.class), objectMapper);
        NodeTrace trace = new NodeTrace("LEAF", "AMOUNT_GT", "revenue", true, 100, "FETCHED", null, null, null, null, null);
        // queue 未初始化时调用 write 抛 NPE，调用方须在 afterPropertiesSet 后使用
        assertThrows(NullPointerException.class, () -> writer.write("t1", "s1", List.of(trace)));
    }

    @Test
    void afterPropertiesSet_startsConsumerThread() throws Exception {
        TraceWriterDbImpl writer = new TraceWriterDbImpl(100, 10, 50, mock(NodeTraceMapper.class), objectMapper);
        writer.afterPropertiesSet();
        try {
            // 消费者线程应已启动
            NodeTrace trace = new NodeTrace("LEAF", "AMOUNT_GT", "revenue", true, 100, "FETCHED", null, null, null, null, null);
            assertDoesNotThrow(() -> writer.write("t1", "s1", List.of(trace)));
        } finally {
            writer.destroy();
        }
    }

    @Test
    void write_doesNotThrow_withEmptyList() throws Exception {
        TraceWriterDbImpl writer = new TraceWriterDbImpl(100, 10, 50, mock(NodeTraceMapper.class), objectMapper);
        writer.afterPropertiesSet();
        try {
            assertDoesNotThrow(() -> writer.write("t1", "s1", List.of()));
        } finally {
            writer.destroy();
        }
    }

    @Test
    void write_dropsEntriesWhenQueueFull() throws Exception {
        // 容量为 1，连续写入两次，第二次应静默丢弃而非阻塞或抛异常
        TraceWriterDbImpl writer = new TraceWriterDbImpl(1, 10, 60_000, mock(NodeTraceMapper.class), objectMapper);
        writer.afterPropertiesSet();
        try {
            NodeTrace trace = new NodeTrace("LEAF", "AMOUNT_GT", "revenue", true, 100, "FETCHED", null, null, null, null, null);
            assertDoesNotThrow(() -> {
                writer.write("t1", "s1", List.of(trace));
                writer.write("t1", "s2", List.of(trace)); // 队列满，静默丢弃
            });
        } finally {
            writer.destroy();
        }
    }

    @Test
    void destroy_doesNotThrow_whenConsumerRunning() throws Exception {
        TraceWriterDbImpl writer = new TraceWriterDbImpl(100, 10, 50, mock(NodeTraceMapper.class), objectMapper);
        writer.afterPropertiesSet();
        assertDoesNotThrow(writer::destroy);
    }

    @Test
    void flushBatch_nodePath_rootUsesIndex_childAppendsDot() throws Exception {
        NodeTraceMapper mapper = mock(NodeTraceMapper.class);
        TraceWriterDbImpl w = new TraceWriterDbImpl(100, 10, 60_000, mapper, objectMapper);
        w.afterPropertiesSet();

        // root[0] → "0"；root[0].child[0] → "0.0"
        NodeTrace child = new NodeTrace("LEAF", "EQ", "score", false, 50, "FETCHED", null, null, null, null, null);
        NodeTrace root  = new NodeTrace("CONDITION", "GT", "revenue", true, 100, "FETCHED", null, List.of(child), 7L, null, null);
        w.write("1", "42", List.of(root));
        w.destroy();

        verify(mapper, atLeastOnce()).insertBatch(argThat(list -> {
            if (list.size() != 2) return false;
            return "0".equals(list.get(0).getNodePath())
                    && "0.0".equals(list.get(1).getNodePath());
        }));
    }

    @Test
    void flushBatch_setsRuleVersionId_onEntity() throws Exception {
        NodeTraceMapper mapper = mock(NodeTraceMapper.class);
        TraceWriterDbImpl w = new TraceWriterDbImpl(100, 10, 60_000, mapper, objectMapper);
        w.afterPropertiesSet();

        NodeTrace root = new NodeTrace("CONDITION", "GT", "revenue", true, 100, "FETCHED", null, null, 42L, null, null);
        w.write("1", "99", List.of(root));
        w.destroy();

        verify(mapper, atLeastOnce()).insertBatch(argThat(list ->
                list.size() == 1 && Long.valueOf(42L).equals(list.get(0).getRuleVersionId())));
    }

    @Test
    void flushBatch_callsInsertBatch_notInsert() throws Exception {
        NodeTraceMapper mapper = mock(NodeTraceMapper.class);
        // flushIntervalMs 超大，手动触发 destroy() 来触发最后一次 flush
        TraceWriterDbImpl w = new TraceWriterDbImpl(100, 10, 60_000, mapper, objectMapper);
        w.afterPropertiesSet();

        NodeTrace child = new NodeTrace("LEAF", "EQ", "score", false, 50, "FETCHED", null, null, null, null, null);
        NodeTrace root  = new NodeTrace("CONDITION", "GT", "revenue", true, 100, "FETCHED", null, List.of(child), 7L, null, null);
        w.write("1", "42", List.of(root));
        w.destroy(); // destroy() 内先 flushBatch()

        // 批量写库：insertBatch 被调用，insert 不再被调用
        verify(mapper, atLeastOnce()).insertBatch(argThat(list -> list.size() == 2));
        verify(mapper, never()).insert(any(NodeTraceEntity.class));
    }

    @Test
    void flushBatch_mapsValueSourceStringToEnum() throws Exception {
        NodeTraceMapper mapper = mock(NodeTraceMapper.class);
        TraceWriterDbImpl w = new TraceWriterDbImpl(100, 10, 60_000, mapper, objectMapper);
        w.afterPropertiesSet();

        // trace.valueSource() 是 String，落库实体字段是 kernel ValueSource 枚举
        NodeTrace leaf = new NodeTrace("LEAF", "GTE", "score", true, 100, "FETCHED", null, null, 7L, null, null);
        w.write("1", "42", List.of(leaf));
        w.destroy();

        verify(mapper, atLeastOnce()).insertBatch(argThat(list ->
                list.size() == 1
                && com.sstlfsj.rule.kernel.api.model.ValueSource.FETCHED.equals(list.get(0).getValueSource())));
    }

    @Test
    void flushBatch_mapsNullValueSourceToNull() throws Exception {
        NodeTraceMapper mapper = mock(NodeTraceMapper.class);
        TraceWriterDbImpl w = new TraceWriterDbImpl(100, 10, 60_000, mapper, objectMapper);
        w.afterPropertiesSet();

        // valueSource 为 null 时不应抛异常，落库为 null
        NodeTrace leaf = new NodeTrace("LEAF", "GTE", "score", true, 100, null, null, null, 7L, null, null);
        w.write("1", "42", List.of(leaf));
        w.destroy();

        verify(mapper, atLeastOnce()).insertBatch(argThat(list ->
                list.size() == 1 && list.get(0).getValueSource() == null));
    }

    @Test
    void flushBatch_setsDisplayLabelAndParamsJson_fromLeafTrace() throws Exception {
        NodeTraceMapper mapper = mock(NodeTraceMapper.class);
        TraceWriterDbImpl w = new TraceWriterDbImpl(100, 10, 60_000, mapper, objectMapper);
        w.afterPropertiesSet();

        // 叶子自携带 expectedValue（→params JSON）与 displayLabel（→display_label 列）
        NodeTrace leaf = new NodeTrace("ConditionNode", "GTE", "score", true, 100, "PROVIDED",
                null, null, 7L, Map.of("threshold", 0), "score>=0");
        w.write("1", "42", List.of(leaf));
        w.destroy();

        verify(mapper, atLeastOnce()).insertBatch(argThat(list ->
                list.size() == 1
                && "score>=0".equals(list.get(0).getDisplayLabel())
                && "{\"threshold\":0}".equals(list.get(0).getParams())));
    }
}
