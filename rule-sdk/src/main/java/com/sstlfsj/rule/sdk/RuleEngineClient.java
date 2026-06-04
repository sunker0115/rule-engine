package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import com.sstlfsj.rule.kernel.api.spi.pregate.PreGate;
import com.sstlfsj.rule.kernel.internal.condition.KernelEvaluators;
import com.sstlfsj.rule.kernel.internal.context.EvalContextAssembler;
import com.sstlfsj.rule.kernel.internal.engine.EvalEngine;
import com.sstlfsj.rule.kernel.internal.evaluator.InterpretedExecutor;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import com.sstlfsj.rule.sdk.source.DslRuleSource;
import com.sstlfsj.rule.sdk.source.FileRuleSource;
import com.sstlfsj.rule.sdk.source.PollingRuleSource;
import com.sstlfsj.rule.sdk.source.RuleSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 嵌入式规则评估门面。
 * 持有本地 SceneRuleIndex 和 EvalEngine，evaluate() 路径零网络跳转。
 * 支持四种规则来源：HTTP 轮询、JSON 文件、代码 DSL、多源混用。
 */
public class RuleEngineClient implements AutoCloseable {

    private final EvalEngine evalEngine;
    private final List<PollingRuleSource> pollingSources;
    private final EvalResultListener evalResultListener;
    private final EvalSessionListener evalSessionListener;

    private RuleEngineClient(Builder b) {
        SceneRuleIndex index = new SceneRuleIndex();
        EvalContextAssembler assembler = new EvalContextAssembler(List.of(), List.of());
        RuleVersionExecutor executor = b.executor != null
                ? b.executor
                : new InterpretedExecutor(KernelEvaluators.defaults());
        this.evalEngine = new EvalEngine(index, assembler,
                b.preGates != null ? b.preGates : Map.of(),
                Map.of("AST_BOOLEAN", executor));

        // 汇总所有 RuleSource：显式 ruleSource() + localSnapshot() 转 DslRuleSource + serverUrl 转 PollingRuleSource
        List<RuleSource> allSources = new ArrayList<>(b.ruleSources);
        if (!b.localSnapshots.isEmpty()) {
            allSources.add(new DslRuleSource(b.localSnapshots));
        }
        if (b.serverUrl != null && !b.serverUrl.isBlank()) {
            allSources.add(new PollingRuleSource(b.serverUrl, b.tenantId,
                    b.fetchMode, b.scenes, b.pollInterval));
        }

        // 统一 loadInto，收集需要 stop() 的 PollingRuleSource
        List<PollingRuleSource> polling = new ArrayList<>();
        for (RuleSource source : allSources) {
            source.loadInto(index);
            if (source instanceof PollingRuleSource p) polling.add(p);
        }
        this.pollingSources = List.copyOf(polling);

        this.evalResultListener = b.evalResultListener;
        this.evalSessionListener = b.evalSessionListener;
    }

    /** 对单个事件本地求值，零网络跳转。 */
    public EvalResult evaluate(RuleEvent event) {
        EvalResult result = evalEngine.evaluate(event);
        if (evalResultListener != null) evalResultListener.onResult(event, result);
        if (evalSessionListener != null) evalSessionListener.onSession(event, result);
        return result;
    }

    @Override
    public void close() {
        pollingSources.forEach(PollingRuleSource::stop);
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
        private RuleVersionExecutor executor;
        private Map<String, PreGate> preGates;
        private final List<RuleVersionSnapshot> localSnapshots = new ArrayList<>();
        private final List<RuleSource> ruleSources = new ArrayList<>();

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
         * 从 classpath 加载 JSON 规则文件（文件模式快捷入口）。
         *
         * @param classpathPath classpath 相对路径，如 "rules/fraud.json"
         */
        public Builder ruleFile(String classpathPath) {
            ruleSources.add(FileRuleSource.classpath(classpathPath)); return this;
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
            return new RuleEngineClient(this);
        }
    }
}
