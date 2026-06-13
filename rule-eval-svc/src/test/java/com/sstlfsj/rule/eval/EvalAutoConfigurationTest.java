package com.sstlfsj.rule.eval;

import tools.jackson.databind.json.JsonMapper;
import com.sstlfsj.rule.eval.internal.TraceProperties;
import com.sstlfsj.rule.eval.internal.metric.sql.FetchResourceProperties;
import com.sstlfsj.rule.eval.internal.repository.DryRunSessionMapper;
import com.sstlfsj.rule.eval.internal.repository.EvaluationSessionMapper;
import com.sstlfsj.rule.eval.internal.retention.RetentionProperties;
import com.sstlfsj.rule.eval.internal.retention.SessionRetentionCleaner;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import com.sstlfsj.rule.kernel.internal.codec.SnapshotAssembler;
import com.sstlfsj.rule.kernel.internal.context.EvalContextAssembler;
import com.sstlfsj.rule.kernel.internal.engine.EvalEngine;
import com.sstlfsj.rule.kernel.internal.evaluator.DecisionTableExecutor;
import com.sstlfsj.rule.kernel.internal.evaluator.DecisionTreeExecutor;
import com.sstlfsj.rule.kernel.internal.evaluator.ScorecardExecutor;
import com.sstlfsj.rule.kernel.internal.evaluator.ScriptExecutor;
import com.sstlfsj.rule.kernel.internal.evaluator.CompiledExecutor;
import com.sstlfsj.rule.kernel.internal.evaluator.RuleVersionCache;
import com.sstlfsj.rule.eval.internal.CompiledExecutorProperties;
import com.sstlfsj.rule.expression.cel.CelExpressionEngine;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import com.sstlfsj.rule.observability.api.metrics.RuleMetrics;
import com.sstlfsj.rule.eval.internal.EvalInstrumentation;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

    /** 默认 props（enabled=false），供装配测试复用。 */
    private static CompiledExecutorProperties defaultProps() {
        return new CompiledExecutorProperties();
    }

    @Test
    void ruleVersionExecutor_returnsCompiledExecutor() {
        // AST_BOOLEAN executor 现为 CompiledExecutor(默认关，逐字节等同解释器)
        RuleVersionExecutor executor = config.ruleVersionExecutor(config.ruleVersionCache(), defaultProps());
        assertNotNull(executor);
        assertInstanceOf(CompiledExecutor.class, executor);
    }

    @Test
    void ruleVersionExecutor_hasPrimaryAnnotation() throws Exception {
        var method = EvalAutoConfiguration.class.getMethod("ruleVersionExecutor",
                RuleVersionCache.class, CompiledExecutorProperties.class);
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
    void scriptExecutor_returnsInstance() {
        // CelExpressionEngine 现由 CEL 模块的 @AutoConfiguration 注册;此处直接 new 模拟容器收集的引擎
        ScriptExecutor executor = config.scriptExecutor(List.of(new CelExpressionEngine()));
        assertNotNull(executor);
        assertInstanceOf(ScriptExecutor.class, executor);
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
                config.ruleVersionExecutor(config.ruleVersionCache(), defaultProps()),
                config.scorecardExecutor(),
                config.decisionTreeExecutor(),
                config.decisionTableExecutor(),
                config.scriptExecutor(List.of(new CelExpressionEngine())),
                new TraceProperties());
        assertNotNull(engine);
    }

    @Test
    void evalEngine_emptyPreGates_returnsInstance() {
        EvalEngine engine = config.evalEngine(
                config.sceneRuleIndex(),
                config.evalContextAssembler(null, null, null, null, EXEC, fetchProps()),
                List.of(),
                config.ruleVersionExecutor(config.ruleVersionCache(), defaultProps()),
                config.scorecardExecutor(),
                config.decisionTreeExecutor(),
                config.decisionTableExecutor(),
                config.scriptExecutor(List.of(new CelExpressionEngine())),
                new TraceProperties());
        assertNotNull(engine);
    }

    @Test
    void evalInstrumentationBean_registered_withCounters() {
        MeterRegistry registry = new SimpleMeterRegistry();
        EvalInstrumentation instrumentation = config.evalInstrumentation(registry);
        assertNotNull(instrumentation);
        assertNotNull(registry.find(RuleMetrics.EVAL_ERROR_TOTAL).counter());
        assertNotNull(registry.find(RuleMetrics.EVAL_TOTAL).counter());
    }

    @Test
    void sessionRetentionCleaner_returnsInstance() {
        SessionRetentionCleaner cleaner = config.sessionRetentionCleaner(
                mock(EvaluationSessionMapper.class),
                mock(DryRunSessionMapper.class),
                new RetentionProperties());
        assertNotNull(cleaner);
    }
}
