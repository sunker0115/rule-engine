package com.sstlfsj.rule.eval.internal.action;

import com.sstlfsj.rule.eval.internal.async.ActionExecuted;
import com.sstlfsj.rule.eval.internal.domain.ActionExecutionEntity;
import com.sstlfsj.rule.eval.internal.repository.ActionExecutionMapper;
import com.sstlfsj.rule.kernel.api.model.ActionResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 验证 ActionExecuted 事件被异步批量消费后写一行 action_execution；落库异常隔离，不影响消费线程。 */
class ActionExecutionPersisterTest {

    @Test
    void onActionExecuted_insertsActionExecutionRow() throws Exception {
        ActionExecutionMapper mapper = mock(ActionExecutionMapper.class);
        ActionExecutionPersister persister = new ActionExecutionPersister(2000, 200, 50, mapper);
        persister.afterPropertiesSet();

        ActionResult result = ActionResult.success("BLOCK_TRANSACTION", "BLOCK_TRANSACTION");
        persister.onActionExecuted(new ActionExecuted(42L, 1L, "evt-1",
                "BLOCK_TRANSACTION", "BLOCK_TRANSACTION", "REJECT", result));

        Thread.sleep(300);   // 等异步消费
        persister.destroy();

        ArgumentCaptor<ActionExecutionEntity> captor = ArgumentCaptor.forClass(ActionExecutionEntity.class);
        verify(mapper, times(1)).insert(captor.capture());
        ActionExecutionEntity e = captor.getValue();
        assertThat(e.getActionId()).isEqualTo("BLOCK_TRANSACTION");
        assertThat(e.getStatus()).isEqualTo("SUCCESS");
        assertThat(e.getEventId()).isEqualTo("evt-1");
        assertThat(e.getEvaluationSessionId()).isEqualTo(42L);
        assertThat(e.getTenantId()).isEqualTo(1L);
    }

    @Test
    void onActionExecuted_duplicateKey_swallowed() throws Exception {
        ActionExecutionMapper mapper = mock(ActionExecutionMapper.class);
        doThrow(new DuplicateKeyException("uk_idempotency"))
                .when(mapper).insert(any(ActionExecutionEntity.class));
        ActionExecutionPersister persister = new ActionExecutionPersister(2000, 200, 50, mapper);
        persister.afterPropertiesSet();

        // 行级 backstop：claim 漏掉的重复撞 uk_idempotency，应被吞掉、不影响消费线程
        persister.onActionExecuted(new ActionExecuted(42L, 1L, "evt-1",
                "BLOCK_TRANSACTION", "BLOCK_TRANSACTION", "REJECT",
                ActionResult.success("BLOCK_TRANSACTION", "BLOCK_TRANSACTION")));

        Thread.sleep(300);
        persister.destroy();

        verify(mapper, times(1)).insert(any(ActionExecutionEntity.class));
    }

    @Test
    void onActionExecuted_insertFailure_doesNotKillConsumer() throws Exception {
        ActionExecutionMapper mapper = mock(ActionExecutionMapper.class);
        doThrow(new RuntimeException("DB 写入失败"))
                .when(mapper).insert(any(ActionExecutionEntity.class));
        ActionExecutionPersister persister = new ActionExecutionPersister(2000, 200, 50, mapper);
        persister.afterPropertiesSet();

        // 普通写库异常被吞，消费线程存活，后续事件仍能处理
        persister.onActionExecuted(new ActionExecuted(42L, 1L, "evt-1",
                "BLOCK_TRANSACTION", "BLOCK_TRANSACTION", "REJECT",
                ActionResult.success("BLOCK_TRANSACTION", "BLOCK_TRANSACTION")));
        Thread.sleep(150);
        persister.onActionExecuted(new ActionExecuted(43L, 1L, "evt-2",
                "BLOCK_TRANSACTION", "BLOCK_TRANSACTION", "REJECT",
                ActionResult.success("BLOCK_TRANSACTION", "BLOCK_TRANSACTION")));

        Thread.sleep(300);
        persister.destroy();

        verify(mapper, times(2)).insert(any(ActionExecutionEntity.class));
    }
}
