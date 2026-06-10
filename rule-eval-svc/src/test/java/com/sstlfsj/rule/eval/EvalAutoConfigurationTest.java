package com.sstlfsj.rule.eval;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.sstlfsj.rule.eval.internal.action.ActionDispatchService;
import com.sstlfsj.rule.eval.internal.event.DomainEventPublisher;
import com.sstlfsj.rule.eval.internal.TraceProperties;
import com.sstlfsj.rule.eval.internal.metric.sql.FetchResourceProperties;
import com.sstlfsj.rule.kernel.api.annotation.ActionType;
import com.sstlfsj.rule.kernel.api.model.ActionContext;
import com.sstlfsj.rule.kernel.api.model.ActionResult;
import com.sstlfsj.rule.kernel.api.spi.action.ActionHandler;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import com.sstlfsj.rule.kernel.internal.codec.SnapshotAssembler;
import com.sstlfsj.rule.kernel.internal.context.EvalContextAssembler;
import com.sstlfsj.rule.kernel.internal.engine.EvalEngine;
import com.sstlfsj.rule.kernel.internal.evaluator.DecisionTableExecutor;
import com.sstlfsj.rule.kernel.internal.evaluator.DecisionTreeExecutor;
import com.sstlfsj.rule.kernel.internal.evaluator.ScorecardExecutor;
import com.sstlfsj.rule.kernel.internal.evaluator.InterpretedExecutor;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 验证 EvalAutoConfiguration 带有必要的 Spring 注解，以及 Bean 工厂方法行为。 */
class EvalAutoConfigurationTest {

    private final EvalAutoConfiguration config = new EvalAutoConfiguration();

    private static final ExecutorService EXEC = Executors.newVirtualThreadPerTaskExecutor();

    @AfterAll
    static void closeExec() { EXEC.shutdown(); }

    /** 取数资源配置桩（默认超时 800ms），供 evalContextAssembler 装配。 */
    private static FetchResourceProperties fetchProps() {
        FetchResourceProperties p = new FetchResourceProperties();
        p.setTimeoutMs(800);
        return p;
    }

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
    void ruleVersionExecutor_returnsInterpretedExecutor() {
        RuleVersionExecutor executor = config.ruleVersionExecutor();
        assertNotNull(executor);
        assertInstanceOf(InterpretedExecutor.class, executor);
    }

    @Test
    void ruleVersionExecutor_hasPrimaryAnnotation() throws Exception {
        var method = EvalAutoConfiguration.class.getMethod("ruleVersionExecutor");
        assertNotNull(method.getAnnotation(Primary.class),
                "ruleVersionExecutor 必须标注 @Primary，否则与 ScorecardExecutor 并存时 Spring 无法消歧义");
    }

    @Test
    void scorecardExecutor_returnsScorecardExecutor() {
        ScorecardExecutor executor = config.scorecardExecutor();
        assertNotNull(executor);
        assertInstanceOf(ScorecardExecutor.class, executor);
    }

    @Test
    void decisionTreeExecutor_returnsInstance() {
        DecisionTreeExecutor executor = config.decisionTreeExecutor();
        assertNotNull(executor);
        assertInstanceOf(DecisionTreeExecutor.class, executor);
    }

    @Test
    void decisionTableExecutor_returnsInstance() {
        DecisionTableExecutor executor = config.decisionTableExecutor();
        assertNotNull(executor);
        assertInstanceOf(DecisionTableExecutor.class, executor);
    }

    @Test
    void sceneRuleIndex_returnsNewInstance() {
        SceneRuleIndex index = config.sceneRuleIndex();
        assertNotNull(index);
    }

    @Test
    void evalContextAssembler_nullLists_returnsInstance() {
        EvalContextAssembler assembler = config.evalContextAssembler(null, null, null, null, EXEC, fetchProps());
        assertNotNull(assembler);
    }

    @Test
    void evalContextAssembler_emptyLists_returnsInstance() {
        EvalContextAssembler assembler = config.evalContextAssembler(
                List.of(), List.of(), null, null, EXEC, fetchProps());
        assertNotNull(assembler);
    }

    @Test
    void snapshotAssembler_returnsInstance() {
        SnapshotAssembler assembler = config.snapshotAssembler(JsonMapper.builder().build());
        assertNotNull(assembler);
    }

    @Test
    void evalEngine_nullPreGates_returnsInstance() {
        EvalEngine engine = config.evalEngine(
                config.sceneRuleIndex(),
                config.evalContextAssembler(null, null, null, null, EXEC, fetchProps()),
                null,
                config.ruleVersionExecutor(),
                config.scorecardExecutor(),
                config.decisionTreeExecutor(),
                config.decisionTableExecutor(),
                new TraceProperties());
        assertNotNull(engine);
    }

    @Test
    void evalEngine_emptyPreGates_returnsInstance() {
        EvalEngine engine = config.evalEngine(
                config.sceneRuleIndex(),
                config.evalContextAssembler(null, null, null, null, EXEC, fetchProps()),
                List.of(),
                config.ruleVersionExecutor(),
                config.scorecardExecutor(),
                config.decisionTreeExecutor(),
                config.decisionTableExecutor(),
                new TraceProperties());
        assertNotNull(engine);
    }

    @Test
    void actionDispatchService_nullHandlers_returnsInstance() {
        ActionDispatchService svc = config.actionDispatchService(
                null,
                mock(DomainEventPublisher.class));
        assertNotNull(svc);
    }

    @Test
    void actionDispatchService_withAnnotatedHandler_buildsHandlerMap() {
        ActionHandler handler = new BlockTxStub();
        ActionDispatchService svc = config.actionDispatchService(
                List.of(handler),
                mock(DomainEventPublisher.class));
        assertNotNull(svc);
    }

    @Test
    void actionDispatchService_handlerWithoutAnnotation_isIgnored() {
        ActionHandler noAnnotation = ctx -> ActionResult.skipped(ctx.actionId(), ctx.actionType(), "STUB");
        ActionDispatchService svc = config.actionDispatchService(
                List.of(noAnnotation),
                mock(DomainEventPublisher.class));
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
