package com.sstlfsj.rule.observability.internal.trace;

import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import com.sstlfsj.rule.kernel.api.spi.trace.TraceWriter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TraceWriterDbImplTest {

    @Test
    void implementsTraceWriter() {
        TraceWriterDbImpl writer = new TraceWriterDbImpl(100, 10, 50);
        assertInstanceOf(TraceWriter.class, writer);
    }

    @Test
    void write_throwsNpe_beforeInit() {
        TraceWriterDbImpl writer = new TraceWriterDbImpl(100, 10, 50);
        NodeTrace trace = new NodeTrace("LEAF", "AMOUNT_GT", "revenue", true, 100, "DB", null, null);
        // queue 未初始化时调用 write 抛 NPE，调用方须在 afterPropertiesSet 后使用
        assertThrows(NullPointerException.class, () -> writer.write("t1", "s1", List.of(trace)));
    }

    @Test
    void afterPropertiesSet_startsConsumerThread() throws Exception {
        TraceWriterDbImpl writer = new TraceWriterDbImpl(100, 10, 50);
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
        TraceWriterDbImpl writer = new TraceWriterDbImpl(100, 10, 50);
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
        TraceWriterDbImpl writer = new TraceWriterDbImpl(1, 10, 60_000);
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
        TraceWriterDbImpl writer = new TraceWriterDbImpl(100, 10, 50);
        writer.afterPropertiesSet();
        assertDoesNotThrow(writer::destroy);
    }
}
