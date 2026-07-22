package com.sstlfsj.rule.kernel.api.model;

import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.flow.FlowGraph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 规则版本的不可变运行时快照，由 Matcher 倒排索引持有，评估期只读。 */
public record RuleVersionSnapshot(
        Long ruleVersionId,
        String sceneCode,
        String tenantId,
        /** 判定主体多态载体（三承载收敛）：AstBody / ScriptBody / FlowBody 之一，与 kind 家族一致。 */
        RuleBody body,
        List<PreGateConfig> preGates,
        List<DecisionBinding> decisionBindings,
        /** 该版本监听的事件类型列表；空列表表示通配（匹配任意 eventType）。 */
        List<String> triggerEventTypes,
        /** 规则类型，默认 AST_BOOLEAN；决定由哪个 executor 求值。 */
        String kind,
        /** 逻辑规则编码(= rule_definition.code,(tenant,scene) 内唯一);本地/旧构造默认 null。 */
        String code,
        /** 版本号(= rule_version.version,per code 单调);本地/旧构造默认 0。 */
        long version,
        /** 引用的 (metricCode, metricVersion) 依赖，发布期冻结（全 kind）。 */
        List<MetricDependency> metricDependencies,
        /** 引用的 payload 字段依赖，发布期从 scene.payloadSchema 冻结（全 kind）。 */
        List<PayloadDependency> payloadDependencies
) {
    public RuleVersionSnapshot {
        preGates = preGates == null ? List.of() : List.copyOf(preGates);
        decisionBindings = decisionBindings == null ? List.of() : List.copyOf(decisionBindings);
        triggerEventTypes = triggerEventTypes == null ? List.of() : List.copyOf(triggerEventTypes);
        kind = kind == null ? RuleKind.AST_BOOLEAN.tag() : kind;
        metricDependencies = metricDependencies == null ? List.of() : List.copyOf(metricDependencies);
        payloadDependencies = payloadDependencies == null ? List.of() : List.copyOf(payloadDependencies);
    }

    /**
     * flat 载体便捷构造（收敛过渡：内部派生 body，record 状态仍为 body-only）：8 参 AST。
     * 保留以免既有测试/benchmark 直构大改；新代码优先用 builder 或规范 body 构造。
     */
    public RuleVersionSnapshot(Long ruleVersionId, String sceneCode, String tenantId, AstNode conditionAst,
                               List<PreGateConfig> preGates, List<DecisionBinding> decisionBindings,
                               List<String> triggerEventTypes, String kind) {
        this(ruleVersionId, sceneCode, tenantId, new AstBody(conditionAst), preGates, decisionBindings,
                triggerEventTypes, kind, null, 0L, List.of(), List.of());
    }

    /** flat 载体便捷构造：12 参 AST（含 metric/payload 依赖，无 script）。 */
    public RuleVersionSnapshot(Long ruleVersionId, String sceneCode, String tenantId, AstNode conditionAst,
                               List<PreGateConfig> preGates, List<DecisionBinding> decisionBindings,
                               List<String> triggerEventTypes, String kind, String code, long version,
                               List<MetricDependency> metricDependencies, List<PayloadDependency> payloadDependencies) {
        this(ruleVersionId, sceneCode, tenantId, new AstBody(conditionAst), preGates, decisionBindings,
                triggerEventTypes, kind, code, version, metricDependencies, payloadDependencies);
    }

    /** flat 载体便捷构造：13 参（+script，脚本优先否则 AST）。 */
    public RuleVersionSnapshot(Long ruleVersionId, String sceneCode, String tenantId, AstNode conditionAst,
                               List<PreGateConfig> preGates, List<DecisionBinding> decisionBindings,
                               List<String> triggerEventTypes, String kind, String code, long version,
                               List<MetricDependency> metricDependencies, List<PayloadDependency> payloadDependencies,
                               ScriptSource script) {
        this(ruleVersionId, sceneCode, tenantId,
                script != null ? new ScriptBody(script) : new AstBody(conditionAst),
                preGates, decisionBindings, triggerEventTypes, kind, code, version,
                metricDependencies, payloadDependencies);
    }

    /** flat 载体便捷构造：15 参（+flowGraph+referencedSnapshots，三承载择一：flow>script>ast）。 */
    public RuleVersionSnapshot(Long ruleVersionId, String sceneCode, String tenantId, AstNode conditionAst,
                               List<PreGateConfig> preGates, List<DecisionBinding> decisionBindings,
                               List<String> triggerEventTypes, String kind, String code, long version,
                               List<MetricDependency> metricDependencies, List<PayloadDependency> payloadDependencies,
                               ScriptSource script, FlowGraph flowGraph,
                               Map<String, RuleVersionSnapshot> referencedSnapshots) {
        this(ruleVersionId, sceneCode, tenantId,
                flowGraph != null ? new FlowBody(flowGraph, referencedSnapshots)
                        : script != null ? new ScriptBody(script) : new AstBody(conditionAst),
                preGates, decisionBindings, triggerEventTypes, kind, code, version,
                metricDependencies, payloadDependencies);
    }

    /** Pre-Gate 配置快照。 */
    public record PreGateConfig(String gateType, Map<String, Object> params) {
        public PreGateConfig {
            params = params == null ? Map.of() : Map.copyOf(params);
        }
    }

    /**
     * Decision 绑定配置快照。发布期从 decision_definition 冻结 name 进来（方案甲，守 D6 不可变 + 评估零额外查询）。
     *
     * @param decisionCode decision 编码
     * @param name         decision 名称（发布期冻结；旧兼容构造为 null）
     * @param priority     绑定优先级，越大越优先
     */
    public record DecisionBinding(String decisionCode, String name, int priority) {
        /** 兼容旧调用点：仅 (decisionCode, priority)，name=null。 */
        public DecisionBinding(String decisionCode, int priority) {
            this(decisionCode, null, priority);
        }
    }

    /** @return 链式构建器，用于本地模式代码定义规则快照 */
    public static Builder builder() { return new Builder(); }

    /**
     * 链式构建器，简化本地模式手工组装 RuleVersionSnapshot。
     * 保留 {@code conditionAst}/{@code script}/{@code flowGraph}/{@code addReferencedSnapshot} 便捷 setter
     * 作为输入语法糖，{@link #build()} 据此组装单一 {@link RuleBody}（也可用 {@link #body} 直接指定）。
     */
    public static final class Builder {
        private Long ruleVersionId;
        private String sceneCode;
        private String tenantId;
        private RuleBody body;
        private AstNode conditionAst;
        private ScriptSource script;
        private FlowGraph flowGraph;
        private String kind = RuleKind.AST_BOOLEAN.tag();
        private String code;
        private long version;
        private final List<PreGateConfig> preGates = new ArrayList<>();
        private final List<DecisionBinding> decisionBindings = new ArrayList<>();
        private final List<String> triggerEventTypes = new ArrayList<>();
        private final List<MetricDependency> metricDependencies = new ArrayList<>();
        private final List<PayloadDependency> payloadDependencies = new ArrayList<>();
        private final Map<String, RuleVersionSnapshot> referencedSnapshots = new HashMap<>();

        /** 规则版本 ID（本地模式可传任意 Long）。 */
        public Builder ruleVersionId(Long v)  { this.ruleVersionId = v; return this; }
        /** 场景编码。 */
        public Builder sceneCode(String v)    { this.sceneCode = v; return this; }
        /** 租户 ID。 */
        public Builder tenantId(String v)     { this.tenantId = v; return this; }
        /** 直接指定多态载体（优先于 conditionAst/script/flowGraph 便捷 setter）。 */
        public Builder body(RuleBody v)       { this.body = v; return this; }
        /** 便捷：AST 系载体的条件树（build() 包成 AstBody）。 */
        public Builder conditionAst(AstNode v){ this.conditionAst = v; return this; }
        /** 便捷：EXPRESSION_SCRIPT 脚本载体（build() 包成 ScriptBody）。 */
        public Builder script(ScriptSource v) { this.script = v; return this; }
        /** 便捷：DECISION_FLOW 决策图（build() 包成 FlowBody）。 */
        public Builder flowGraph(FlowGraph v) { this.flowGraph = v; return this; }
        /** 规则类型，默认 AST_BOOLEAN。 */
        public Builder kind(String v)         { this.kind = v; return this; }
        /** 逻辑规则编码。 */
        public Builder code(String v)    { this.code = v; return this; }
        /** 版本号。 */
        public Builder version(long v)   { this.version = v; return this; }
        /** 追加一个监听的事件类型。 */
        public Builder addTriggerEventType(String v) { triggerEventTypes.add(v); return this; }
        /** 追加一个 Decision 绑定。 */
        public Builder addDecisionBinding(String decisionCode, int priority) {
            decisionBindings.add(new DecisionBinding(decisionCode, priority)); return this;
        }
        /** 追加一个带 name 的 Decision 绑定。 */
        public Builder addDecisionBinding(String decisionCode, String name, int priority) {
            decisionBindings.add(new DecisionBinding(decisionCode, name, priority)); return this;
        }
        /** 追加一个 Pre-Gate 配置。 */
        public Builder addPreGate(String gateType, Map<String, Object> params) {
            preGates.add(new PreGateConfig(gateType, params)); return this;
        }
        /** 追加一个 metric 版本化依赖。 */
        public Builder addMetricDependency(String metricCode, int metricVersion) {
            metricDependencies.add(new MetricDependency(metricCode, metricVersion)); return this;
        }
        /** 追加一条 payload 字段依赖(发布期从 payloadSchema 冻结)。 */
        public Builder addPayloadDependency(String name, String dataType, boolean required) {
            this.payloadDependencies.add(new PayloadDependency(name, dataType, required));
            return this;
        }
        /** 追加一条 DECISION_FLOW 冻结的被引规则快照。 */
        public Builder addReferencedSnapshot(String ruleCode, RuleVersionSnapshot snapshot) {
            this.referencedSnapshots.put(ruleCode, snapshot);
            return this;
        }

        /** 构建 RuleVersionSnapshot：body 显式优先，否则按 flowGraph→script→conditionAst 组装对应变体。 */
        public RuleVersionSnapshot build() {
            RuleBody b = this.body != null ? this.body
                    : this.flowGraph != null ? new FlowBody(this.flowGraph, this.referencedSnapshots)
                    : this.script != null ? new ScriptBody(this.script)
                    : new AstBody(this.conditionAst);
            return new RuleVersionSnapshot(ruleVersionId, sceneCode, tenantId, b,
                    preGates, decisionBindings, triggerEventTypes, kind, code, version,
                    metricDependencies, payloadDependencies);
        }
    }
}
