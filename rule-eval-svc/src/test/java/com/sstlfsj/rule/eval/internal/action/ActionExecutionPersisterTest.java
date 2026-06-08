package com.sstlfsj.rule.eval.internal.action;

import com.sstlfsj.rule.eval.internal.async.ActionExecuted;
import com.sstlfsj.rule.eval.internal.domain.ActionExecutionEntity;
import com.sstlfsj.rule.eval.internal.repository.ActionExecutionMapper;
import com.sstlfsj.rule.kernel.api.model.ActionResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ActionExecutionPersisterTest {

    @Test
    void accept_insertsActionExecutionRow() {
        ActionExecutionMapper mapper = mock(ActionExecutionMapper.class);
        ActionExecutionPersister persister = new ActionExecutionPersister(mapper);
        ActionResult result = ActionResult.success("BLOCK_TRANSACTION", "BLOCK_TRANSACTION");

        persister.accept(new ActionExecuted(42L, 1L, "evt-1",
                "BLOCK_TRANSACTION", "BLOCK_TRANSACTION", "REJECT", result));

        ArgumentCaptor<ActionExecutionEntity> captor = ArgumentCaptor.forClass(ActionExecutionEntity.class);
        verify(mapper).insert(captor.capture());
        ActionExecutionEntity e = captor.getValue();
        assertThat(e.getActionId()).isEqualTo("BLOCK_TRANSACTION");
        assertThat(e.getStatus()).isEqualTo("SUCCESS");
        assertThat(e.getEventId()).isEqualTo("evt-1");
    }
}
