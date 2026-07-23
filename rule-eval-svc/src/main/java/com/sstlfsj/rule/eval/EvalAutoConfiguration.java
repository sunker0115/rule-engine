package com.sstlfsj.rule.eval;

import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionEngine;
import com.sstlfsj.rule.kernel.api.annotation.MetricSourceType;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricCache;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricDefinitionResolver;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricSourceHandler;
import com.sstlfsj.rule.kernel.api.spi.pregate.PreGate;
import com.sstlfsj.rule.kernel.api.spi.subject.SubjectLoader;
import tools.jackson.databind.ObjectMapper;
import com.sstlfsj.rule.kernel.internal.codec.AstJsonCodec;
import com.sstlfsj.rule.kernel.internal.codec.SnapshotAssembler;
import com.sstlfsj.rule.kernel.api.annotation.ConditionType;
import com.sstlfsj.rule.kernel.internal.condition.KernelEvaluators;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.sstlfsj.rule.kernel.internal.context.EvalContextAssembler;
import com.sstlfsj.rule.kernel.internal.engine.EvalEngine;
import com.sstlfsj.rule.kernel.internal.evaluator.DecisionTableExecutor;
import com.sstlfsj.rule.kernel.internal.evaluator.DecisionTreeExecutor;
import com.sstlfsj.rule.kernel.internal.evaluator.ScorecardExecutor;
import com.sstlfsj.rule.kernel.internal.evaluator.ScriptExecutor;
import com.sstlfsj.rule.kernel.internal.evaluator.FlowExecutor;
import com.sstlfsj.rule.kernel.internal.evaluator.InterpretedExecutor;
import com.sstlfsj.rule.kernel.internal.evaluator.AstCompiler;
import com.sstlfsj.rule.kernel.internal.evaluator.CompiledExecutor;
import com.sstlfsj.rule.kernel.internal.evaluator.RuleVersionCache;
import com.sstlfsj.rule.eval.internal.CompiledExecutorProperties;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import com.sstlfsj.rule.eval.internal.metric.sql.FetchResourceProperties;
import com.sstlfsj.rule.eval.internal.repository.EvaluationSessionMapper;
import com.sstlfsj.rule.eval.internal.retention.RetentionProperties;
import com.sstlfsj.rule.eval.internal.EvalInstrumentation;
import com.sstlfsj.rule.eval.internal.retention.SessionRetentionCleaner;
import com.sstlfsj.rule.observability.api.metrics.RuleMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Primary;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 自动装配规则评估模块。 */
@AutoConfiguration
@ComponentScan("com.sstlfsj.rule.eval.internal")
@org.springframework.boot.context.properties.EnableConfigurationProperties({
        com.sstlfsj.rule.eval.internal.metric.sql.FetchResourceProperties.class,
        com.sstlfsj.rule.eval.internal.TraceProperties.class,
        com.sstlfsj.rule.eval.internal.async.AuditProperties.class,
        com.sstlfsj.rule.eval.internal.snapshot.ScriptPrecompileProperties.class,
        com.sstlfsj.rule.eval.internal.CompiledExecutorProperties.class,
        RetentionProperties.class})
public class EvalAutoConfiguration {

    /**
     * 注册 session 表数据保留清理调度 bean（evaluation_session）。
     * 可通过 engine.rule.retention.enabled=false 关闭。
     *
     * @param evaluationSessionMapper evaluation_session Mapper
     * @param retentionProperties     保留清理配置
     * @return SessionRetentionCleaner 实例
     */
    @Bean
    @ConditionalOnProperty(name = "engine.rule.retention.enabled", matchIfMissing = true)
    public SessionRetentionCleaner sessionRetentionCleaner(EvaluationSessionMapper evaluationSessionMapper,
                                                           RetentionProperties retentionProperties) {
        return new SessionRetentionCleaner(evaluationSessionMapper, retentionProperties);
    }

    /**
     * 编译产物缓存 bean，供 CompiledExecutor 与 CompiledPredicateEvictor 共享。
     *
     * @return RuleVersionCache 实例
     */
    @Bean
    public RuleVersionCache ruleVersionCache() {
        return new RuleVersionCache();
    }

    /**
     * AST_BOOLEAN executor：默认 CompiledExecutor 包裹 InterpretedExecutor。
     * compiled-executor.enabled=false(默认)时逐字节等同解释器(永远委托)；
     * 开启后非 trace 快路径走编译闭包，开 trace / 灰度未命中时回落解释器。
     * 外部可注册自定义 RuleVersionExecutor Bean 覆盖此默认值。
     *
     * @param ruleVersionCache 编译产物缓存
     * @param props            编译执行器灰度配置
     * @return CompiledExecutor 实例(对外仍是 RuleVersionExecutor)
     */
    @Bean
    @Primary
    public RuleVersionExecutor ruleVersionExecutor(RuleVersionCache ruleVersionCache,
                                                   CompiledExecutorProperties props,
                                                   List<ConditionEvaluator> customEvaluators) {
        Map<String, ConditionEvaluator> evaluators = mergeEvaluators(customEvaluators);
        InterpretedExecutor interpreter = new InterpretedExecutor(evaluators);
        AstCompiler compiler = new AstCompiler(evaluators);
        return new CompiledExecutor(interpreter, compiler, ruleVersionCache,
                props.isEnabled(), Set.copyOf(props.getRuleCodeWhitelist()), props.getOnCompileError());
    }

    /**
     * 将 Spring 托管的自定义 {@link ConditionEvaluator} bean 合并进内置算子表。
     * 内置算子优先级低于自定义（可被覆盖），覆盖时输出 WARN 日志。
     * 未标注 {@link ConditionType} 的 bean 跳过（WARN），避免无意中污染算子路由。
     *
     * @param custom Spring 自动收集的自定义 evaluator（无 bean 时为空列表）
     * @return 合并后的不可变算子 Map
     */
    private static Map<String, ConditionEvaluator> mergeEvaluators(List<ConditionEvaluator> custom) {
        if (custom == null || custom.isEmpty()) return KernelEvaluators.defaults();
        Logger log = LoggerFactory.getLogger(EvalAutoConfiguration.class);
        Map<String, ConditionEvaluator> map = new HashMap<>(KernelEvaluators.defaults());
        for (ConditionEvaluator ev : custom) {
            ConditionType ann = ev.getClass().getAnnotation(ConditionType.class);
            if (ann == null) {
                log.warn("自定义 ConditionEvaluator {} 未标注 @ConditionType，跳过注册", ev.getClass().getName());
                continue;
            }
            if (map.containsKey(ann.value())) {
                log.warn("自定义算子 code={} 覆盖内置实现", ann.value());
            }
            map.put(ann.value(), ev);
        }
        return Map.copyOf(map);
    }

    /**
     * 注册 ScorecardExecutor，供 kind=SCORECARD 的规则版本评估使用。
     *
     * @return ScorecardExecutor 实例
     */
    @Bean
    public ScorecardExecutor scorecardExecutor() {
        return new ScorecardExecutor(KernelEvaluators.defaults());
    }

    /**
     * 注册 DecisionTreeExecutor，供 kind=DECISION_TREE 的规则版本评估使用。
     *
     * @return DecisionTreeExecutor 实例
     */
    @Bean
    public DecisionTreeExecutor decisionTreeExecutor() {
        return new DecisionTreeExecutor(KernelEvaluators.defaults());
    }

    /**
     * 注册 DecisionTableExecutor，供 kind=DECISION_TABLE 的规则版本评估使用。
     *
     * @return DecisionTableExecutor 实例
     */
    @Bean
    public DecisionTableExecutor decisionTableExecutor() {
        return new DecisionTableExecutor(KernelEvaluators.defaults());
    }

    /**
     * 注册 ScriptExecutor，供 kind=EXPRESSION_SCRIPT 的规则版本评估使用。
     * 按 lang 路由引擎：自动收集所有 {@link ExpressionEngine} bean（盒内默认仅 CEL，
     * opt-in 引擎模块注册各自的 bean 即被纳入，无需改本配置），按 lang() 建路由表。
     *
     * @param expressionEngines 所有已注册的表达式引擎（Spring 自动收集）
     * @return ScriptExecutor 实例
     */
    @Bean
    public ScriptExecutor scriptExecutor(List<ExpressionEngine> expressionEngines) {
        return new ScriptExecutor(byLang(expressionEngines));
    }

    /**
     * 按 lang 建表达式引擎路由表，同一 lang 重复声明时装配期 fail-fast。
     * 供 scriptExecutor / flowExecutor 共享（EXPRESSION_SCRIPT 与 DECISION_FLOW 内 Switch/Transform 均按 lang 路由）。
     *
     * @param expressionEngines Spring 自动收集的表达式引擎
     * @return lang → ExpressionEngine 映射
     */
    private static Map<String, ExpressionEngine> byLang(List<ExpressionEngine> expressionEngines) {
        Map<String, ExpressionEngine> byLang = new HashMap<>();
        for (ExpressionEngine engine : expressionEngines) {
            ExpressionEngine prev = byLang.putIfAbsent(engine.lang(), engine);
            if (prev != null) {
                throw new IllegalStateException("多个 ExpressionEngine 声明同一 lang=" + engine.lang());
            }
        }
        return byLang;
    }

    /**
     * 注册 FlowExecutor，供 kind=DECISION_FLOW 的规则版本评估使用。
     * leafExecutors 用可变 map 组装：先放五个叶子 executor，构造 FlowExecutor（持 map 引用不 copyOf），
     * 再把 flow 自身回填进 map，以支持嵌套 flow（环由发布期静态分析拒绝）。
     * engines 按 lang 路由 Switch/Transform 表达式，与 scriptExecutor 同源。
     *
     * @param ruleVersionExecutor   AST_BOOLEAN executor
     * @param scorecardExecutor     SCORECARD executor
     * @param decisionTreeExecutor  DECISION_TREE executor
     * @param decisionTableExecutor DECISION_TABLE executor
     * @param scriptExecutor        EXPRESSION_SCRIPT executor
     * @param expressionEngines     所有已注册的表达式引擎（Spring 自动收集）
     * @return FlowExecutor 实例
     */
    @Bean
    public FlowExecutor flowExecutor(RuleVersionExecutor ruleVersionExecutor,
                                     ScorecardExecutor scorecardExecutor,
                                     DecisionTreeExecutor decisionTreeExecutor,
                                     DecisionTableExecutor decisionTableExecutor,
                                     ScriptExecutor scriptExecutor,
                                     List<ExpressionEngine> expressionEngines) {
        Map<String, RuleVersionExecutor> leafExecutors = new HashMap<>();
        leafExecutors.put(RuleKind.AST_BOOLEAN.tag(),       ruleVersionExecutor);
        leafExecutors.put(RuleKind.SCORECARD.tag(),         scorecardExecutor);
        leafExecutors.put(RuleKind.DECISION_TREE.tag(),     decisionTreeExecutor);
        leafExecutors.put(RuleKind.DECISION_TABLE.tag(),    decisionTableExecutor);
        leafExecutors.put(RuleKind.EXPRESSION_SCRIPT.tag(), scriptExecutor);
        FlowExecutor flow = new FlowExecutor(leafExecutors, byLang(expressionEngines));
        leafExecutors.put(RuleKind.DECISION_FLOW.tag(), flow);
        return flow;
    }

    /**
     * SnapshotAssembler：注入 Spring 全局 ObjectMapper，与 HTTP 层序列化行为一致。
     *
     * @param objectMapper Spring Boot 自动配置的全局 ObjectMapper
     * @return SnapshotAssembler 实例
     */
    @Bean
    public SnapshotAssembler snapshotAssembler(ObjectMapper objectMapper) {
        return new SnapshotAssembler(new AstJsonCodec(objectMapper));
    }

    /**
     * 内存倒排索引，供 IndexStartupLoader / EventListener 更新，EvalServiceImpl 查询。
     *
     * @return SceneRuleIndex 实例
     */
    @Bean
    public SceneRuleIndex sceneRuleIndex() {
        return new SceneRuleIndex();
    }

    /**
     * 取数专用线程池：并发 fetch 多个 metric，延迟 = max 而非 sum。
     *
     * @return 命名为 metricFetchExecutor 的线程池
     */
    @Bean(name = "metricFetchExecutor")
    public ExecutorService metricFetchExecutor() {
        // 虚拟线程-per-task：无固定上限，取数并发由下游连接池兜底；ExecutorService 自带 AutoCloseable，Spring 关闭时 close
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 装配 EvalContext；按 @MetricSourceType 归类 handler，注入 resolver/cache/fetchExecutor。
     * resolver 为 null（无定义解析器）时 assembler 退化为仅 providedMetrics 生效。
     *
     * @param subjectLoaders     可选 SubjectLoader SPI 列表
     * @param metricHandlers     可选 MetricSourceHandler SPI 列表
     * @param definitionResolver metric 定义解析器（可选）
     * @param metricCache        取数缓存（可选）
     * @param fetchExecutor      取数线程池
     * @param fetchProps         取数资源配置（全局取数超时单一来源 engine.rule.fetch.*）
     * @return EvalContextAssembler 实例
     */
    @Bean
    public EvalContextAssembler evalContextAssembler(
            @Autowired(required = false) List<SubjectLoader> subjectLoaders,
            @Autowired(required = false) List<MetricSourceHandler> metricHandlers,
            @Autowired(required = false) MetricDefinitionResolver definitionResolver,
            @Autowired(required = false) MetricCache metricCache,
            @Qualifier("metricFetchExecutor") ExecutorService fetchExecutor,
            FetchResourceProperties fetchProps) {
        Map<String, MetricSourceHandler> bySource = new HashMap<>();
        if (metricHandlers != null) {
            for (MetricSourceHandler h : metricHandlers) {
                MetricSourceType ann = h.getClass().getAnnotation(MetricSourceType.class);
                if (ann != null) bySource.put(ann.value(), h);
            }
        }
        return new EvalContextAssembler(
                subjectLoaders == null ? List.of() : subjectLoaders,
                bySource, definitionResolver, metricCache, fetchExecutor, fetchProps.getTimeoutMs());
    }

    /**
     * 纯 Java 评估编排器：Matcher → Pre-Gate → EvalContext → Executor。
     * 无 DB 写入、无 Action 派发；key 映射 kind 字段到对应 executor。
     *
     * @param sceneRuleIndex        倒排索引
     * @param evalContextAssembler  上下文装配
     * @param preGates              可选 PreGate SPI 实现列表
     * @param ruleVersionExecutor   AST_BOOLEAN executor
     * @param scorecardExecutor     SCORECARD executor
     * @param decisionTreeExecutor  DECISION_TREE executor
     * @param decisionTableExecutor DECISION_TABLE executor
     * @param scriptExecutor        EXPRESSION_SCRIPT executor
     * @param flowExecutor          DECISION_FLOW executor
     * @param traceProperties       全局 NodeTrace 收集开关配置（engine.rule.trace.enabled，默认 true）
     * @return EvalEngine 实例
     */
    @Bean
    public EvalEngine evalEngine(
            SceneRuleIndex sceneRuleIndex,
            EvalContextAssembler evalContextAssembler,
            @Autowired(required = false) List<PreGate> preGates,
            RuleVersionExecutor ruleVersionExecutor,
            ScorecardExecutor scorecardExecutor,
            DecisionTreeExecutor decisionTreeExecutor,
            DecisionTableExecutor decisionTableExecutor,
            ScriptExecutor scriptExecutor,
            FlowExecutor flowExecutor,
            com.sstlfsj.rule.eval.internal.TraceProperties traceProperties) {
        Map<String, PreGate> gateMap = new HashMap<>();
        if (preGates != null) {
            preGates.forEach(g -> gateMap.put(g.gateType(), g));
        }
        return new EvalEngine(sceneRuleIndex, evalContextAssembler, gateMap,
                Map.of(RuleKind.AST_BOOLEAN.tag(),       ruleVersionExecutor,
                       RuleKind.SCORECARD.tag(),         scorecardExecutor,
                       RuleKind.DECISION_TREE.tag(),     decisionTreeExecutor,
                       RuleKind.DECISION_TABLE.tag(),    decisionTableExecutor,
                       RuleKind.EXPRESSION_SCRIPT.tag(), scriptExecutor,
                       RuleKind.DECISION_FLOW.tag(),     flowExecutor),
                traceProperties.isEnabled());
    }

    /**
     * eval-svc 侧可观测性埋点，封装 Counter/Gauge 注册，使 EvalServiceImpl 专注评估协调。
     *
     * @param meterRegistry Spring Boot 自动装配的 Micrometer 注册表
     * @return EvalInstrumentation 实例
     */
    @Bean
    public EvalInstrumentation evalInstrumentation(MeterRegistry meterRegistry) {
        Counter evalTotal = Counter.builder(RuleMetrics.EVAL_TOTAL)
                .description("评估总次数").register(meterRegistry);
        Counter evalError = Counter.builder(RuleMetrics.EVAL_ERROR_TOTAL)
                .description("评估错误总数（errorCode 非空）").register(meterRegistry);
        return new EvalInstrumentation(evalTotal, evalError, meterRegistry);
    }
}
