package com.sstlfsj.rule.eval.internal.action;

import com.sstlfsj.rule.eval.internal.domain.ActionExecutionEntity;
import com.sstlfsj.rule.eval.internal.domain.SceneActionBindingRow;
import com.sstlfsj.rule.eval.internal.repository.ActionExecutionMapper;
import com.sstlfsj.rule.eval.internal.repository.SceneActionBindingReadMapper;
import com.sstlfsj.rule.kernel.api.model.ActionContext;
import com.sstlfsj.rule.kernel.api.model.ActionResult;
import com.sstlfsj.rule.kernel.api.model.Decision;
import com.sstlfsj.rule.kernel.api.spi.action.ActionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** ActionDispatchService 单元测试：验证 handler 派发、空绑定、handler 缺失、异常隔离四个场景。 */
class ActionDispatchServiceTest {

    private SceneActionBindingReadMapper bindingMapper;
    private ActionExecutionMapper executionMapper;
    private ActionHandler stubHandler;
    private ActionIdempotencyGuard guard;
    private ActionDispatchService service;

    @BeforeEach
    void setUp() {
        bindingMapper = mock(SceneActionBindingReadMapper.class);
        executionMapper = mock(ActionExecutionMapper.class);
        stubHandler = mock(ActionHandler.class);
        when(stubHandler.execute(any())).thenReturn(ActionResult.success("aid", "BLOCK_TRANSACTION"));
        guard = mock(ActionIdempotencyGuard.class);
        when(guard.claim(any())).thenReturn(true);   // 默认放行；去重用例单独 stub false

        service = new ActionDispatchService(
                Map.of("BLOCK_TRANSACTION", stubHandler),
                bindingMapper,
                executionMapper,
                guard);
    }

    @Test
    void dispatch_withBinding_callsHandlerAndInsertsExecution() {
        when(bindingMapper.findBySceneCode(1L, "fraud_check"))
                .thenReturn(List.of(new SceneActionBindingRow("BLOCK_TRANSACTION", null)));

        service.dispatch(42L, 1L, "evt-001", "fraud_check",
                List.of(new Decision("REJECT", "", 10, 1L)));

        verify(stubHandler).execute(any(ActionContext.class));
        verify(executionMapper).insert(argThat((ActionExecutionEntity entity) ->
                "BLOCK_TRANSACTION".equals(entity.getActionType())
                && "SUCCESS".equals(entity.getStatus())
                && Long.valueOf(42L).equals(entity.getEvaluationSessionId())
                && "evt-001".equals(entity.getEventId())));
    }

    @Test
    void dispatch_emptyBindings_doesNothing() {
        when(bindingMapper.findBySceneCode(1L, "fraud_check")).thenReturn(List.of());

        service.dispatch(42L, 1L, "evt-001", "fraud_check",
                List.of(new Decision("REJECT", "", 10, 1L)));

        verifyNoInteractions(stubHandler);
        verifyNoInteractions(executionMapper);
    }

    @Test
    void dispatch_handlerNotRegistered_insertsSkippedRecord() {
        when(bindingMapper.findBySceneCode(1L, "fraud_check"))
                .thenReturn(List.of(new SceneActionBindingRow("UNKNOWN_ACTION", null)));

        service.dispatch(42L, 1L, "evt-001", "fraud_check",
                List.of(new Decision("REJECT", "", 10, 1L)));

        verifyNoInteractions(stubHandler);
        verify(executionMapper).insert(argThat((ActionExecutionEntity entity) ->
                "UNKNOWN_ACTION".equals(entity.getActionType())
                && "SKIPPED".equals(entity.getStatus())
                && "NO_HANDLER".equals(entity.getErrorCode())));
    }

    @Test
    void dispatch_insertException_doesNotPropagate() {
        when(bindingMapper.findBySceneCode(1L, "fraud_check"))
                .thenReturn(List.of(new SceneActionBindingRow("BLOCK_TRANSACTION", null)));
        doThrow(new RuntimeException("DB 写入失败")).when(executionMapper).insert(any(ActionExecutionEntity.class));

        // 插入异常不应向上传播，不影响 EvalResult
        service.dispatch(42L, 1L, "evt-001", "fraud_check",
                List.of(new Decision("REJECT", "", 10, 1L)));
    }

    @Test
    void dispatch_actionId_isDeterministicActionType() {
        when(bindingMapper.findBySceneCode(1L, "fraud_check"))
                .thenReturn(List.of(new SceneActionBindingRow("BLOCK_TRANSACTION", null)));

        service.dispatch(42L, 1L, "evt-001", "fraud_check",
                List.of(new Decision("REJECT", "", 10, 1L)));

        verify(executionMapper).insert(argThat((ActionExecutionEntity e) ->
                "BLOCK_TRANSACTION".equals(e.getActionId())));
    }

    @Test
    void dispatch_claimRejected_skipsHandlerAndInsert() {
        when(bindingMapper.findBySceneCode(1L, "fraud_check"))
                .thenReturn(List.of(new SceneActionBindingRow("BLOCK_TRANSACTION", null)));
        when(guard.claim(any())).thenReturn(false);   // 已被占坑（重复 eventId）

        service.dispatch(42L, 1L, "evt-001", "fraud_check",
                List.of(new Decision("REJECT", "", 10, 1L)));

        verifyNoInteractions(stubHandler);
        verify(executionMapper, never()).insert(any(ActionExecutionEntity.class));
    }

    @Test
    void dispatch_handlerFailed_releasesClaimForRetry() {
        when(bindingMapper.findBySceneCode(1L, "fraud_check"))
                .thenReturn(List.of(new SceneActionBindingRow("BLOCK_TRANSACTION", null)));
        when(stubHandler.execute(any()))
                .thenReturn(ActionResult.failed("BLOCK_TRANSACTION", "BLOCK_TRANSACTION", "ERR", true));

        service.dispatch(42L, 1L, "evt-001", "fraud_check",
                List.of(new Decision("REJECT", "", 10, 1L)));

        verify(guard).release(anyString());
        verify(executionMapper).insert(argThat((ActionExecutionEntity e) ->
                "FAILED".equals(e.getStatus())));
    }
}
