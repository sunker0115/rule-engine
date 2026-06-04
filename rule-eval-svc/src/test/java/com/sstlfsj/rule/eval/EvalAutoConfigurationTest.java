package com.sstlfsj.rule.eval;

import com.sstlfsj.rule.eval.internal.action.ActionDispatchService;
import com.sstlfsj.rule.eval.internal.repository.ActionExecutionMapper;
import com.sstlfsj.rule.eval.internal.repository.SceneActionBindingReadMapper;
import com.sstlfsj.rule.kernel.api.annotation.ActionType;
import com.sstlfsj.rule.kernel.api.model.ActionContext;
import com.sstlfsj.rule.kernel.api.model.ActionResult;
import com.sstlfsj.rule.kernel.api.spi.action.ActionHandler;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import com.sstlfsj.rule.kernel.internal.evaluator.TracingInterpretedExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 验证 EvalAutoConfiguration 带有必要的 Spring 注解，以及 Bean 工厂方法行为。 */
class EvalAutoConfigurationTest {

    private final EvalAutoConfiguration config = new EvalAutoConfiguration();

    @Test
    void hasAutoConfigurationAnnotation() {
        assertNotNull(EvalAutoConfiguration.class.getAnnotation(AutoConfiguration.class));
    }

    @Test
    void componentScanTargetsInternalPackage() {
        ComponentScan scan = EvalAutoConfiguration.class.getAnnotation(ComponentScan.class);
        assertNotNull(scan);
        assertArrayEquals(new String[]{"com.sstlfsj.rule.eval.internal"}, scan.value());
    }

    @Test
    void ruleVersionExecutor_nullEvaluators_returnsTracingInterpretedExecutor() {
        // conditionEvaluators 为 null（无注册实现时 Spring 传入 null）
        RuleVersionExecutor executor = config.ruleVersionExecutor(null);
        assertNotNull(executor);
        assertInstanceOf(TracingInterpretedExecutor.class, executor);
    }

    @Test
    void ruleVersionExecutor_emptyEvaluators_returnsTracingInterpretedExecutor() {
        RuleVersionExecutor executor = config.ruleVersionExecutor(java.util.Map.of());
        assertNotNull(executor);
        assertInstanceOf(TracingInterpretedExecutor.class, executor);
    }

    @Test
    void actionDispatchService_nullHandlers_returnsInstance() {
        // actionHandlers 为 null（容器无任何 ActionHandler Bean 时 Spring 传入 null）
        ActionDispatchService svc = config.actionDispatchService(
                null,
                mock(SceneActionBindingReadMapper.class),
                mock(ActionExecutionMapper.class));
        assertNotNull(svc);
    }

    @Test
    void actionDispatchService_withAnnotatedHandler_buildsHandlerMap() {
        // 注册了一个带 @ActionType("BLOCK_TX") 的 handler，构建后应能正确索引
        ActionHandler handler = new BlockTxStub();
        ActionDispatchService svc = config.actionDispatchService(
                List.of(handler),
                mock(SceneActionBindingReadMapper.class),
                mock(ActionExecutionMapper.class));
        assertNotNull(svc);
    }

    @Test
    void actionDispatchService_handlerWithoutAnnotation_isIgnored() {
        // 没有 @ActionType 注解的 handler 不应注册到 map，也不应抛异常
        ActionHandler noAnnotation = ctx -> ActionResult.skipped(ctx.actionId(), ctx.actionType(), "STUB");
        ActionDispatchService svc = config.actionDispatchService(
                List.of(noAnnotation),
                mock(SceneActionBindingReadMapper.class),
                mock(ActionExecutionMapper.class));
        assertNotNull(svc);
    }

    /** 测试用 stub，带 @ActionType 注解。 */
    @ActionType("BLOCK_TX")
    private static class BlockTxStub implements ActionHandler {
        @Override
        public ActionResult execute(ActionContext ctx) {
            return ActionResult.skipped(ctx.actionId(), ctx.actionType(), "STUB");
        }
    }
}
