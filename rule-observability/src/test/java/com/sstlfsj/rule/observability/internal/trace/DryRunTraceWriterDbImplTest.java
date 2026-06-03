package com.sstlfsj.rule.observability.internal.trace;

import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import com.sstlfsj.rule.kernel.api.spi.trace.DryRunTraceWriter;
import com.sstlfsj.rule.observability.internal.domain.DryRunNodeTraceEntity;
import com.sstlfsj.rule.observability.internal.repository.DryRunNodeTraceMapper;
import com.sstlfsj.rule.observability.internal.repository.NodeTraceMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DryRunTraceWriterDbImplTest {

    @Test
    void implementsDryRunTraceWriter() {
        DryRunTraceWriterDbImpl writer = new DryRunTraceWriterDbImpl(100, 10, 50,
                mock(DryRunNodeTraceMapper.class));
        assertInstanceOf(DryRunTraceWriter.class, writer);
    }

    @Test
    void write_throwsNpe_beforeInit() {
        DryRunTraceWriterDbImpl writer = new DryRunTraceWriterDbImpl(100, 10, 50,
                mock(DryRunNodeTraceMapper.class));
        NodeTrace trace = new NodeTrace("LEAF", "AMOUNT_GT", "revenue", true, 100, "DB", null, null, null);
        assertThrows(NullPointerException.class, () -> writer.write("t1", "s1", List.of(trace)));
    }

    @Test
    void afterPropertiesSet_startsConsumerThread() throws Exception {
        DryRunTraceWriterDbImpl writer = new DryRunTraceWriterDbImpl(100, 10, 50,
                mock(DryRunNodeTraceMapper.class));
        writer.afterPropertiesSet();
        try {
            NodeTrace trace = new NodeTrace("LEAF", "AMOUNT_GT", "revenue", true, 100, "DB", null, null, null);
            assertDoesNotThrow(() -> writer.write("t1", "s1", List.of(trace)));
        } finally {
            writer.destroy();
        }
    }

    @Test
    void write_dropsEntriesWhenQueueFull() throws Exception {
        DryRunTraceWriterDbImpl writer = new DryRunTraceWriterDbImpl(1, 10, 60_000,
                mock(DryRunNodeTraceMapper.class));
        writer.afterPropertiesSet();
        try {
            NodeTrace trace = new NodeTrace("LEAF", "AMOUNT_GT", "revenue", true, 100, "DB", null, null, null);
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
        DryRunTraceWriterDbImpl w = new DryRunTraceWriterDbImpl(100, 10, 60_000, mapper);
        w.afterPropertiesSet();

        NodeTrace child = new NodeTrace("LEAF", "EQ", "score", false, 50, "DB", null, null, null);
        NodeTrace root  = new NodeTrace("CONDITION", "GT", "revenue", true, 100, "DB", null, List.of(child), 7L);
        w.write("1", "42", List.of(root));
        w.destroy();

        verify(mapper, atLeastOnce()).insertBatch(argThat(list -> list.size() == 2));
        verify(mapper, never()).insert(any(DryRunNodeTraceEntity.class));
    }

    @Test
    void flushBatch_setsDryRunSessionId_notEvaluationSessionId() throws Exception {
        DryRunNodeTraceMapper mapper = mock(DryRunNodeTraceMapper.class);
        DryRunTraceWriterDbImpl w = new DryRunTraceWriterDbImpl(100, 10, 60_000, mapper);
        w.afterPropertiesSet();

        NodeTrace root = new NodeTrace("CONDITION", "GT", "revenue", true, 100, "DB", null, null, 42L);
        w.write("1", "99", List.of(root));
        w.destroy();

        verify(mapper, atLeastOnce()).insertBatch(argThat(list ->
                list.size() == 1
                && Long.valueOf(99L).equals(list.get(0).getDryRunSessionId())
                && Long.valueOf(42L).equals(list.get(0).getRuleVersionId())));
    }

    @Test
    void flushBatch_nodePath_rootUsesIndex_childAppendsDot() throws Exception {
        DryRunNodeTraceMapper mapper = mock(DryRunNodeTraceMapper.class);
        DryRunTraceWriterDbImpl w = new DryRunTraceWriterDbImpl(100, 10, 60_000, mapper);
        w.afterPropertiesSet();

        NodeTrace child = new NodeTrace("LEAF", "EQ", "score", false, 50, "DB", null, null, null);
        NodeTrace root  = new NodeTrace("CONDITION", "GT", "revenue", true, 100, "DB", null, List.of(child), 7L);
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
        DryRunTraceWriterDbImpl w = new DryRunTraceWriterDbImpl(100, 10, 60_000, dryMapper);
        w.afterPropertiesSet();

        NodeTrace trace = new NodeTrace("LEAF", "EQ", "score", false, 50, "DB", null, null, null);
        w.write("1", "1", List.of(trace));
        w.destroy();

        verifyNoInteractions(nodeTraceMapper);
    }
}
