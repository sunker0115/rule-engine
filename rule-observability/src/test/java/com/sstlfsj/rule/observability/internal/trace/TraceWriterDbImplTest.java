package com.sstlfsj.rule.observability.internal.trace;

import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import com.sstlfsj.rule.kernel.api.spi.trace.TraceWriter;
import com.sstlfsj.rule.observability.internal.domain.NodeTraceEntity;
import com.sstlfsj.rule.observability.internal.repository.NodeTraceMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class TraceWriterDbImplTest {

    @Test
    void implementsTraceWriter() {
        TraceWriterDbImpl writer = new TraceWriterDbImpl(100, 10, 50, mock(NodeTraceMapper.class));
        assertInstanceOf(TraceWriter.class, writer);
    }

    @Test
    void write_throwsNpe_beforeInit() {
        TraceWriterDbImpl writer = new TraceWriterDbImpl(100, 10, 50, mock(NodeTraceMapper.class));
        NodeTrace trace = new NodeTrace("LEAF", "AMOUNT_GT", "revenue", true, 100, "DB", null, null);
        // queue 未初始化时调用 write 抛 NPE，调用方须在 afterPropertiesSet 后使用
        assertThrows(NullPointerException.class, () -> writer.write("t1", "s1", List.of(trace)));
    }

    @Test
    void afterPropertiesSet_startsConsumerThread() throws Exception {
        TraceWriterDbImpl writer = new TraceWriterDbImpl(100, 10, 50, mock(NodeTraceMapper.class));
        writer.afterPropertiesSet();
        try {
            // 消费者线程应已启动
            NodeTrace trace = new NodeTrace("LEAF", "AMOUNT_GT", "revenue", true, 100, "DB", null, null);
            assertDoesNotThrow(() -> writer.write("t1", "s1", List.of(trace)));
        } finally {
            writer.destroy();
        }
    }

    @Test
    void write_doesNotThrow_withEmptyList() throws Exception {
        TraceWriterDbImpl writer = new TraceWriterDbImpl(100, 10, 50, mock(NodeTraceMapper.class));
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
        TraceWriterDbImpl writer = new TraceWriterDbImpl(1, 10, 60_000, mock(NodeTraceMapper.class));
        writer.afterPropertiesSet();
        try {
            NodeTrace trace = new NodeTrace("LEAF", "AMOUNT_GT", "revenue", true, 100, "DB", null, null);
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
        TraceWriterDbImpl writer = new TraceWriterDbImpl(100, 10, 50, mock(NodeTraceMapper.class));
        writer.afterPropertiesSet();
        assertDoesNotThrow(writer::destroy);
    }

    @Test
    void flushBatch_nodePath_rootUsesIndex_childAppendsDot() throws Exception {
        NodeTraceMapper mapper = mock(NodeTraceMapper.class);
        TraceWriterDbImpl w = new TraceWriterDbImpl(100, 10, 60_000, mapper);
        w.afterPropertiesSet();

        // root[0] → "0"；root[0].child[0] → "0.0"
        NodeTrace child = new NodeTrace("LEAF", "EQ", "score", false, 50, "DB", null, null);
        NodeTrace root  = new NodeTrace("CONDITION", "GT", "revenue", true, 100, "DB", null, List.of(child));
        w.write("1", "42", List.of(root));
        w.destroy();

        verify(mapper, atLeastOnce()).insertBatch(argThat(list -> {
            if (list.size() != 2) return false;
            return "0".equals(list.get(0).getNodePath())
                    && "0.0".equals(list.get(1).getNodePath());
        }));
    }

    @Test
    void flushBatch_callsInsertBatch_notInsert() throws Exception {
        NodeTraceMapper mapper = mock(NodeTraceMapper.class);
        // flushIntervalMs 超大，手动触发 destroy() 来触发最后一次 flush
        TraceWriterDbImpl w = new TraceWriterDbImpl(100, 10, 60_000, mapper);
        w.afterPropertiesSet();

        NodeTrace child = new NodeTrace("LEAF", "EQ", "score", false, 50, "DB", null, null);
        NodeTrace root  = new NodeTrace("CONDITION", "GT", "revenue", true, 100, "DB", null, List.of(child));
        w.write("1", "42", List.of(root));
        w.destroy(); // destroy() 内先 flushBatch()

        // 批量写库：insertBatch 被调用，insert 不再被调用
        verify(mapper, atLeastOnce()).insertBatch(argThat(list -> list.size() == 2));
        verify(mapper, never()).insert(any(NodeTraceEntity.class));
    }
}
