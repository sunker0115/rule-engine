package com.sstlfsj.rule.eval.internal.action;

import com.sstlfsj.rule.eval.internal.async.ActionExecutedEvent;
import com.sstlfsj.rule.eval.internal.domain.ActionExecutionEntity;
import com.sstlfsj.rule.eval.internal.repository.ActionExecutionMapper;
import com.sstlfsj.rule.kernel.api.model.ActionResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/** 验证 ActionExecutedEvent 事件被异步多行批量落库；整批写库异常隔离，不影响消费线程。 */
class ActionExecutionPersisterTest {

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<ActionExecutionEntity>> batchCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    @Test
    void onActionExecuted_batchInsertsActionExecutionRow() throws Exception {
        ActionExecutionMapper mapper = mock(ActionExecutionMapper.class);
        ActionExecutionPersister persister = new ActionExecutionPersister(2000, 200, 50, mapper);
        persister.afterPropertiesSet();

        ActionResult result = ActionResult.success("BLOCK_TRANSACTION", "BLOCK_TRANSACTION");
        persister.onActionExecuted(new ActionExecutedEvent(42L, 1L, "evt-1",
                "BLOCK_TRANSACTION", "BLOCK_TRANSACTION", "REJECT", result));

        Thread.sleep(300);   // 等异步消费
        persister.destroy();

        ArgumentCaptor<List<ActionExecutionEntity>> captor = batchCaptor();
        verify(mapper, times(1)).insertBatch(captor.capture());
        ActionExecutionEntity e = captor.getValue().get(0);
        assertThat(e.getActionId()).isEqualTo("BLOCK_TRANSACTION");
        assertThat(e.getStatus()).isEqualTo("SUCCESS");
        assertThat(e.getEventId()).isEqualTo("evt-1");
        assertThat(e.getEvaluationSessionId()).isEqualTo(42L);
        assertThat(e.getTenantId()).isEqualTo(1L);
    }

    @Test
    void onActionExecuted_batchInsertFailure_doesNotKillConsumer() throws Exception {
        ActionExecutionMapper mapper = mock(ActionExecutionMapper.class);
        doThrow(new RuntimeException("DB 写入失败")).when(mapper).insertBatch(anyList());
        ActionExecutionPersister persister = new ActionExecutionPersister(2000, 200, 50, mapper);
        persister.afterPropertiesSet();

        // 整批写库异常被吞，消费线程存活，下个 flush 周期仍能处理后续事件
        persister.onActionExecuted(new ActionExecutedEvent(42L, 1L, "evt-1",
                "BLOCK_TRANSACTION", "BLOCK_TRANSACTION", "REJECT",
                ActionResult.success("BLOCK_TRANSACTION", "BLOCK_TRANSACTION")));
        Thread.sleep(150);
        persister.onActionExecuted(new ActionExecutedEvent(43L, 1L, "evt-2",
                "BLOCK_TRANSACTION", "BLOCK_TRANSACTION", "REJECT",
                ActionResult.success("BLOCK_TRANSACTION", "BLOCK_TRANSACTION")));

        Thread.sleep(300);
        persister.destroy();

        verify(mapper, atLeast(2)).insertBatch(anyList());
    }
}
