package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.annotation.MetricSourceType;
import com.sstlfsj.rule.kernel.api.model.EvalOutcome;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.MetricDescriptor;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionEngine;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricCache;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricDefinitionResolver;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricSourceHandler;
import com.sstlfsj.rule.kernel.api.spi.pregate.PreGate;
import com.sstlfsj.rule.kernel.api.trace.NodeTraceFormatter;
import com.sstlfsj.rule.kernel.internal.condition.KernelEvaluators;
import com.sstlfsj.rule.kernel.internal.context.EvalContextAssembler;
import com.sstlfsj.rule.kernel.internal.engine.EvalEngine;
import com.sstlfsj.rule.kernel.internal.evaluator.InterpretedExecutor;
import com.sstlfsj.rule.kernel.internal.evaluator.ScriptExecutor;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import com.sstlfsj.rule.sdk.metric.MetricDefinitionRegistry;
import com.sstlfsj.rule.sdk.metric.SnapshotMetricDefinitionResolver;
import com.sstlfsj.rule.sdk.source.DslMetricDefinitionSource;
import com.sstlfsj.rule.sdk.source.DslRuleSource;
import com.sstlfsj.rule.sdk.source.FileRuleSource;
import com.sstlfsj.rule.sdk.source.MetricDefinitionSource;
import com.sstlfsj.rule.sdk.source.PollingMetricDefinitionSource;
import com.sstlfsj.rule.sdk.source.PollingRuleSource;
import com.sstlfsj.rule.sdk.source.RuleSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * 嵌入式规则评估门面。
 * 持有本地 SceneRuleIndex 和 EvalEngine，evaluate() 路径零网络跳转。
 * 支持四种规则来源：HTTP 轮询、JSON 文件、代码 DSL、多源混用。
 */
public class RuleEngineClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RuleEngineClient.class);

    private final EvalEngine evalEngine;
    private final List<PollingRuleSource> pollingSources;
    private final List<PollingMetricDefinitionSource> metricPollingSources;
    private final EvalResultListener evalResultListener;
    private final EvalSessionListener evalSessionListener;
    private final DecisionContextListener decisionContextListener;

    private RuleEngineClient(Builder b) {
        SceneRuleIndex index = new SceneRuleIndex();

        // metric 取数装配：注入 handler 才启用 fetch（默认仅 providedMetrics，行为不变）
        MetricDefinitionRegistry metricRegistry = new MetricDefinitionRegistry();
        Map<String, MetricSourceHandler> sourceMap = new HashMap<>(toSourceTypeMap(b.metricHandlers));
        sourceMap.putAll(b.explicitSourceHandlers);
        boolean fetchEnabled = !sourceMap.isEmpty();
        EvalContextAssembler assembler;
        if (fetchEnabled) {
            MetricDefinitionResolver resolver = b.metricDefinitionResolver != null
                    ? b.metricDefinitionResolver
                    : new SnapshotMetricDefinitionResolver(metricRegistry);
            assembler = new EvalContextAssembler(List.of(),
                    sourceMap,
                    resolver, b.metricCache, b.fetchExecutor, 0L);
        } else {
            assembler = new EvalContextAssembler(List.of(), List.of());
        }

        // 以内置算子为底，用户自定义叠加（同名自定义覆盖内置）
        Map<String, ConditionEvaluator> evaluators = new HashMap<>(KernelEvaluators.defaults());
        evaluators.putAll(b.extraEvaluators);
        RuleVersionExecutor executor = b.executor != null
                ? b.executor
                : new InterpretedExecutor(evaluators);
        // executors 按 kind tag 分派:AST_BOOLEAN 走解释器,@Decide/@Score 走对应合成执行器(有注册才装)
        Map<String, RuleVersionExecutor> executors = new HashMap<>();
        executors.put(RuleKind.AST_BOOLEAN.tag(), executor);
        if (!b.decideInvocations.isEmpty()) {
            executors.put(com.sstlfsj.rule.sdk.source.AnnotatedRuleScanner.KIND_DECIDE,
                    new com.sstlfsj.rule.sdk.source.AnnotatedDecideExecutor(b.decideInvocations));
        }
        if (!b.scoreInvocations.isEmpty()) {
            executors.put(com.sstlfsj.rule.sdk.source.AnnotatedRuleScanner.KIND_SCORE,
                    new com.sstlfsj.rule.sdk.source.AnnotatedScoreExecutor(b.scoreInvocations));
        }
        // ScriptExecutor 始终注册:避开 EvalEngine 对未知 kind 回退 AST_BOOLEAN 的陷阱(脚本规则 conditionAst=null)。
        // 引擎才是 opt-in——未注入任何 ExpressionEngine 时 engines 为空,碰脚本规则优雅返回 SCRIPT_NO_ENGINE,不连累其它规则。
        executors.put(RuleKind.EXPRESSION_SCRIPT.tag(), new ScriptExecutor(byLang(b.expressionEngines)));
        this.evalEngine = new EvalEngine(index, assembler,
                b.preGates != null ? b.preGates : Map.of(),
                executors,
                false);

        // 规则来源：显式 ruleSource() + localSnapshot() 转 DslRuleSource + serverUrl 转 PollingRuleSource
        List<RuleSource> allSources = new ArrayList<>(b.ruleSources);
        if (!b.localSnapshots.isEmpty()) {
            allSources.add(new DslRuleSource(b.localSnapshots));
        }
        if (b.serverUrl != null && !b.serverUrl.isBlank()) {
            allSources.add(new PollingRuleSource(b.serverUrl, b.tenantId,
                    b.fetchMode, b.scenes, b.pollInterval));
        }
        List<PollingRuleSource> polling = new ArrayList<>();
        for (RuleSource source : allSources) {
            source.loadInto(index);
            if (source instanceof PollingRuleSource p) polling.add(p);
        }
        this.pollingSources = List.copyOf(polling);

        // metric 定义来源：localMetric → DslMetricDefinitionSource；HTTP+fetch → PollingMetricDefinitionSource
        List<MetricDefinitionSource> metricSources = new ArrayList<>(b.metricDefinitionSources);
        for (Map.Entry<String, List<MetricDescriptor>> e : b.localMetrics.entrySet()) {
            metricSources.add(new DslMetricDefinitionSource(e.getKey(), e.getValue()));
        }
        if (fetchEnabled && b.serverUrl != null && !b.serverUrl.isBlank()) {
            metricSources.add(new PollingMetricDefinitionSource(b.serverUrl, b.tenantId,
                    b.fetchMode, b.scenes, b.pollInterval));
        }
        List<PollingMetricDefinitionSource> metricPolling = new ArrayList<>();
        for (MetricDefinitionSource s : metricSources) {
            s.loadInto(metricRegistry);
            if (s instanceof PollingMetricDefinitionSource p) metricPolling.add(p);
        }
        this.metricPollingSources = List.copyOf(metricPolling);

        this.evalResultListener = b.evalResultListener;
        this.evalSessionListener = b.evalSessionListener;
        this.decisionContextListener = b.decisionContextListener;
    }

    /** 把 handler 列表按 @MetricSourceType 归类为 sourceType → handler 映射。 */
    private static Map<String, MetricSourceHandler> toSourceTypeMap(List<MetricSourceHandler> handlers) {
        Map<String, MetricSourceHandler> m = new HashMap<>();
        for (MetricSourceHandler h : handlers) {
            MetricSourceType ann = h.getClass().getAnnotation(MetricSourceType.class);
            if (ann != null) m.put(ann.value(), h);
        }
        return m;
    }

    /** 表达式引擎按 lang 建路由表,同 lang 重复声明 fail-fast(对齐 eval-svc / config-svc)。 */
    private static Map<String, ExpressionEngine> byLang(List<ExpressionEngine> engines) {
        Map<String, ExpressionEngine> byLang = new HashMap<>();
        for (ExpressionEngine engine : engines) {
            ExpressionEngine prev = byLang.putIfAbsent(engine.lang(), engine);
            if (prev != null) {
                throw new IllegalStateException("多个 ExpressionEngine 声明同一 lang=" + engine.lang());
            }
        }
        return byLang;
    }

    /** 对单个事件本地求值，零网络跳转；渠道由 SDK 入口权威设为 SDK，不信任调用方传入。 */
    public EvalResult evaluate(RuleEvent event) {
        RuleEvent sdkEvent = event.source() == EventSource.SDK
                ? event : event.toBuilder().source(EventSource.SDK).build();
        EvalOutcome outcome = evalEngine.evaluateWithContext(
                sdkEvent, evalEngine.match(sdkEvent), java.time.Instant.now());
        EvalResult result = outcome.result();
        if (log.isDebugEnabled()) {
            // SDK 默认不收集 trace（EvalEngine collectTrace=false）→ nodeTrace 为空输出 "[]"；启用 trace 后才有内容
            log.debug("eval trace {}", NodeTraceFormatter.compact(result.nodeTrace()));
        }
        if (evalResultListener != null) evalResultListener.onResult(sdkEvent, result);
        if (evalSessionListener != null) evalSessionListener.onSession(sdkEvent, result);
        if (decisionContextListener != null) {
            decisionContextListener.onEvaluated(sdkEvent, result, outcome.context());
        }
        return result;
    }

    @Override
    public void close() {
        pollingSources.forEach(PollingRuleSource::stop);
        metricPollingSources.forEach(PollingMetricDefinitionSource::stop);
    }

    /** @return 新建 Builder */
    public static Builder builder() {
        return new Builder();
    }

    /** RuleEngineClient 构建器。 */
    public static final class Builder {
        private String serverUrl;
        private String tenantId;
        private FetchMode fetchMode = FetchMode.DECLARED;
        private final List<String> scenes = new ArrayList<>();
        private Duration pollInterval = Duration.ofSeconds(30);
        private EvalResultListener evalResultListener;
        private EvalSessionListener evalSessionListener;
        private DecisionContextListener decisionContextListener;
        private RuleVersionExecutor executor;
        private Map<String, PreGate> preGates;
        private final List<RuleVersionSnapshot> localSnapshots = new ArrayList<>();
        private final List<RuleSource> ruleSources = new ArrayList<>();
        private final Map<String, ConditionEvaluator> extraEvaluators = new HashMap<>();
        private final Map<String, com.sstlfsj.rule.sdk.source.AnnotatedDecideExecutor.Invocation> decideInvocations = new HashMap<>();
        private final Map<String, com.sstlfsj.rule.sdk.source.AnnotatedScoreExecutor.Invocation> scoreInvocations = new HashMap<>();
        private final List<MetricSourceHandler> metricHandlers = new ArrayList<>();
        private final Map<String, MetricSourceHandler> explicitSourceHandlers = new HashMap<>();
        private final List<ExpressionEngine> expressionEngines = new ArrayList<>();
        private MetricDefinitionResolver metricDefinitionResolver;
        private MetricCache metricCache;
        private ExecutorService fetchExecutor;
        private final List<MetricDefinitionSource> metricDefinitionSources = new ArrayList<>();
        private final Map<String, List<MetricDescriptor>> localMetrics = new LinkedHashMap<>();

        /** @param v rule-api 服务地址（HTTP 模式必填，本地模式不填） */
        public Builder serverUrl(String v)      { this.serverUrl = v; return this; }
        /** @param v 租户 ID（HTTP 模式必填） */
        public Builder tenantId(String v)       { this.tenantId = v; return this; }
        /** @param v 快照订阅模式，默认 DECLARED */
        public Builder fetchMode(FetchMode v)   { this.fetchMode = v; return this; }
        /** @param v 订阅的场景编码列表（DECLARED 模式下有效） */
        public Builder scenes(String... v)      { scenes.addAll(Arrays.asList(v)); return this; }
        /** @param v 轮询间隔，默认 30 秒 */
        public Builder pollInterval(Duration v) { this.pollInterval = v; return this; }
        /** @param v 评估结果回调（可选） */
        public Builder evalResultListener(EvalResultListener v)  { this.evalResultListener = v; return this; }
        /** @param v 审计回调（可选） */
        public Builder evalSessionListener(EvalSessionListener v) { this.evalSessionListener = v; return this; }
        /** @param v 带 context 的评估回调(可选),用于注解动作派发 */
        public Builder decisionContextListener(DecisionContextListener v) {
            this.decisionContextListener = v; return this;
        }
        /** @param v 自定义 executor，不传则使用 InterpretedExecutor（内置全量算子） */
        public Builder executor(RuleVersionExecutor v)  { this.executor = v; return this; }
        /** @param v 自定义 Pre-Gate 映射（可选） */
        public Builder preGates(Map<String, PreGate> v) { this.preGates = v; return this; }
        /**
         * 添加本地规则快照（代码 DSL 模式），不可与 serverUrl 混用。
         *
         * @param v 规则版本快照
         */
        public Builder localSnapshot(RuleVersionSnapshot v) { localSnapshots.add(v); return this; }
        /**
         * 添加规则来源（支持 FileRuleSource / DslRuleSource 等），可与其他来源混用。
         *
         * @param v RuleSource 实现
         */
        public Builder ruleSource(RuleSource v) { ruleSources.add(v); return this; }
        /**
         * 注册自定义条件算子，叠加在内置算子之上（同名自定义覆盖内置）。
         *
         * @param conditionType 算子类型标识，与 ConditionNode.conditionType 对应
         * @param evaluator     算子实现
         */
        public Builder addEvaluator(String conditionType, ConditionEvaluator evaluator) {
            extraEvaluators.put(conditionType, evaluator); return this;
        }
        /**
         * 注册 @Decide 规则调用(key=注解规则坐标键)。
         *
         * @param m 坐标键 → @Decide 调用三元组
         */
        public Builder addDecideInvocations(Map<String, com.sstlfsj.rule.sdk.source.AnnotatedDecideExecutor.Invocation> m) {
            decideInvocations.putAll(m); return this;
        }
        /**
         * 注册 @Score 规则调用(key=注解规则坐标键)。
         *
         * @param m 坐标键 → @Score 调用信息(方法 + 分档表)
         */
        public Builder addScoreInvocations(Map<String, com.sstlfsj.rule.sdk.source.AnnotatedScoreExecutor.Invocation> m) {
            scoreInvocations.putAll(m); return this;
        }
        /**
         * 从 classpath 加载 JSON 规则文件（文件模式快捷入口）。
         *
         * @param classpathPath classpath 相对路径，如 "rules/fraud.json"
         */
        public Builder ruleFile(String classpathPath) {
            ruleSources.add(FileRuleSource.classpath(classpathPath)); return this;
        }

        /**
         * 注入宿主自带的 metric 取数 handler（按 @MetricSourceType 归类）。注入任一 handler 即启用 fetch。
         *
         * @param v MetricSourceHandler 实现，类上须标注 @MetricSourceType
         */
        public Builder metricSourceHandler(MetricSourceHandler... v) {
            metricHandlers.addAll(Arrays.asList(v)); return this;
        }

        /** 按显式 sourceType 注册 handler(供 @MetricSource 合成 handler 用;无 @MetricSourceType 注解)。 */
        public Builder addMetricSourceHandler(String sourceType, MetricSourceHandler handler) {
            explicitSourceHandlers.put(sourceType, handler); return this;
        }

        /**
         * 启用 EXPRESSION_SCRIPT 脚本规则执行:注入表达式引擎(如 CelExpressionEngine)。
         * 不注入则脚本规则评估返回 SCRIPT_NO_ENGINE(graceful,不连累其它规则)。同 lang 重复注入装配期 fail-fast。
         *
         * @param v 表达式引擎实现
         */
        public Builder expressionEngine(ExpressionEngine v) {
            expressionEngines.add(v); return this;
        }

        /**
         * 覆盖默认的 metric 定义解析器（默认 SnapshotMetricDefinitionResolver 读本地下发缓存）。
         *
         * @param v 自定义 MetricDefinitionResolver
         */
        public Builder metricDefinitionResolver(MetricDefinitionResolver v) {
            this.metricDefinitionResolver = v; return this;
        }

        /**
         * 注入取数结果缓存（可选）。
         *
         * @param v MetricCache 实现
         */
        public Builder metricCache(MetricCache v) { this.metricCache = v; return this; }

        /**
         * 注入并发取数线程池（可选，默认 ForkJoinPool.commonPool）。
         *
         * @param v ExecutorService
         */
        public Builder fetchExecutor(ExecutorService v) { this.fetchExecutor = v; return this; }

        /**
         * 添加 metric 定义来源（HTTP/文件/DSL），写入本地定义注册表。
         *
         * @param v MetricDefinitionSource 实现
         */
        public Builder metricDefinitionSource(MetricDefinitionSource v) {
            metricDefinitionSources.add(v); return this;
        }

        /**
         * 本地声明单个 metric 定义（DSL 便捷入口），按租户归类后转 DslMetricDefinitionSource。
         *
         * @param tenantId   定义所属租户 id
         * @param descriptor metric 定义快照
         */
        public Builder localMetric(String tenantId, MetricDescriptor descriptor) {
            localMetrics.computeIfAbsent(tenantId, k -> new ArrayList<>()).add(descriptor);
            return this;
        }

        /**
         * 构建 RuleEngineClient。
         * HTTP 模式需配置 serverUrl + tenantId；本地/文件模式需至少一个规则来源；两者互斥。
         *
         * @return RuleEngineClient 实例
         * @throws IllegalArgumentException 配置不合法时
         */
        public RuleEngineClient build() {
            boolean hasServer = serverUrl != null && !serverUrl.isBlank();
            boolean hasLocal  = !localSnapshots.isEmpty() || !ruleSources.isEmpty();
            if (hasServer && hasLocal)
                throw new IllegalArgumentException("serverUrl 和本地规则来源（localSnapshot/ruleSource/ruleFile）不可同时配置");
            if (!hasServer && !hasLocal)
                throw new IllegalArgumentException("必须配置 serverUrl（HTTP 模式）或至少一个本地规则来源");
            if (hasServer && (tenantId == null || tenantId.isBlank()))
                throw new IllegalArgumentException("HTTP 模式下 tenantId 必填");
            // 有取数配置但无 handler → 定义会被静默丢弃，提前失败优于运行时无声 no-op
            boolean hasFetchConfig = !metricDefinitionSources.isEmpty()
                    || !localMetrics.isEmpty()
                    || metricDefinitionResolver != null
                    || metricCache != null
                    || fetchExecutor != null;
            if (hasFetchConfig && metricHandlers.isEmpty() && explicitSourceHandlers.isEmpty())
                throw new IllegalArgumentException(
                        "配置了取数相关项（metric 定义来源 / resolver / cache / executor）但未注入 metricSourceHandler，无法启用 fetch");
            return new RuleEngineClient(this);
        }
    }
}
