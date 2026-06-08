package com.sstlfsj.rule.eval;

import com.sstlfsj.rule.eval.internal.action.ActionDispatchService;
import com.sstlfsj.rule.eval.internal.action.ActionIdempotencyGuard;
import com.sstlfsj.rule.eval.internal.action.SceneActionBindingIndex;
import com.sstlfsj.rule.eval.internal.event.DomainEventPublisher;
import com.sstlfsj.rule.kernel.api.annotation.ActionType;
import com.sstlfsj.rule.kernel.api.spi.action.ActionHandler;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import com.sstlfsj.rule.kernel.api.annotation.MetricSourceType;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricCache;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricDefinitionResolver;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricSourceHandler;
import com.sstlfsj.rule.kernel.api.spi.pregate.PreGate;
import com.sstlfsj.rule.kernel.api.spi.subject.SubjectLoader;
import tools.jackson.databind.ObjectMapper;
import com.sstlfsj.rule.kernel.internal.codec.AstJsonCodec;
import com.sstlfsj.rule.kernel.internal.codec.SnapshotAssembler;
import com.sstlfsj.rule.kernel.internal.condition.KernelEvaluators;
import com.sstlfsj.rule.kernel.internal.context.EvalContextAssembler;
import com.sstlfsj.rule.kernel.internal.engine.EvalEngine;
import com.sstlfsj.rule.kernel.internal.evaluator.DecisionTableExecutor;
import com.sstlfsj.rule.kernel.internal.evaluator.DecisionTreeExecutor;
import com.sstlfsj.rule.kernel.internal.evaluator.ScorecardExecutor;
import com.sstlfsj.rule.kernel.internal.evaluator.InterpretedExecutor;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import com.sstlfsj.rule.eval.internal.metric.sql.FetchResourceProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Primary;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/** 自动装配规则评估模块。 */
@AutoConfiguration
@ComponentScan("com.sstlfsj.rule.eval.internal")
@org.springframework.boot.context.properties.EnableConfigurationProperties({
        com.sstlfsj.rule.eval.internal.metric.sql.FetchResourceProperties.class,
        com.sstlfsj.rule.eval.internal.action.ActionIdempotencyProperties.class})
public class EvalAutoConfiguration {

    /**
     * 默认使用 InterpretedExecutor（AST 树形解释执行，按 TraceScope.COLLECT 守卫收集 NodeTrace）。
     * 外部可注册自定义 RuleVersionExecutor Bean 覆盖此默认值。
     *
     * @return InterpretedExecutor 实例
     */
    @Bean
    @Primary
    public RuleVersionExecutor ruleVersionExecutor() {
        return new InterpretedExecutor(KernelEvaluators.defaults());
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
    public Executor metricFetchExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(8);
        ex.setMaxPoolSize(32);
        ex.setQueueCapacity(256);
        ex.setThreadNamePrefix("metric-fetch-");
        ex.initialize();
        return ex;
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
            @Qualifier("metricFetchExecutor") Executor fetchExecutor,
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
     * @param traceEnabled          全局 NodeTrace 收集开关（engine.rule.trace.enabled，默认 true）
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
            @org.springframework.beans.factory.annotation.Value("${engine.rule.trace.enabled:true}") boolean traceEnabled) {
        Map<String, PreGate> gateMap = new HashMap<>();
        if (preGates != null) {
            preGates.forEach(g -> gateMap.put(g.gateType(), g));
        }
        return new EvalEngine(sceneRuleIndex, evalContextAssembler, gateMap,
                Map.of("AST_BOOLEAN",     ruleVersionExecutor,
                       "SCORECARD",       scorecardExecutor,
                       "DECISION_TREE",   decisionTreeExecutor,
                       "DECISION_TABLE",  decisionTableExecutor),
                traceEnabled);
    }

    /**
     * 注册 ActionDispatchService，按 @ActionType.value() 构建 handler 映射。
     *
     * @param actionHandlers  Spring 容器中所有 ActionHandler bean（可为空）
     * @param bindingIndex    scene_action_binding 内存索引（派发零 DB 读）
     * @param eventPublisher  领域事件发布缝（ActionExecuted 由 persister 异步落库）
     * @return ActionDispatchService 实例
     */
    @Bean
    public ActionDispatchService actionDispatchService(
            @Autowired(required = false) List<ActionHandler> actionHandlers,
            SceneActionBindingIndex bindingIndex,
            DomainEventPublisher eventPublisher,
            ActionIdempotencyGuard idempotencyGuard) {
        Map<String, ActionHandler> handlerMap = new HashMap<>();
        if (actionHandlers != null) {
            for (ActionHandler handler : actionHandlers) {
                ActionType ann = handler.getClass().getAnnotation(ActionType.class);
                if (ann != null) {
                    handlerMap.put(ann.value(), handler);
                }
            }
        }
        return new ActionDispatchService(handlerMap, bindingIndex, eventPublisher, idempotencyGuard);
    }
}
