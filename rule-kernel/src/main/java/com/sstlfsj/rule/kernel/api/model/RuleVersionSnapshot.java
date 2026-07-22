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
        AstNode conditionAst,
        List<PreGateConfig> preGates,
        List<DecisionBinding> decisionBindings,
        /** 该版本监听的事件类型列表；空列表表示通配（匹配任意 eventType）。 */
        List<String> triggerEventTypes,
        /** 规则类型，默认 AST_BOOLEAN；SCORECARD 时由 ScorecardExecutor 求值。 */
        String kind,
        /** 逻辑规则编码(= rule_definition.code,(tenant,scene) 内唯一);本地/旧构造默认 null。 */
        String code,
        /** 版本号(= rule_version.version,per code 单调);本地/旧构造默认 0。 */
        long version,
        /** AST 引用的 (metricCode, metricVersion) 依赖，发布期冻结。 */
        List<MetricDependency> metricDependencies,
        /** AST 引用的 payload 字段依赖，发布期从 scene.payloadSchema 冻结。 */
        List<PayloadDependency> payloadDependencies,
        /** EXPRESSION_SCRIPT 规则的脚本载体;其它 kind 为 null。 */
        ScriptSource script,
        /** DECISION_FLOW 规则的决策图;其它 kind 为 null。与 conditionAst/script 三选一。 */
        FlowGraph flowGraph,
        /** DECISION_FLOW 发布期冻结的被引规则快照(ruleCode → 冻结 snapshot);其它 kind 为空 map。 */
        Map<String, RuleVersionSnapshot> referencedSnapshots
) {
    public RuleVersionSnapshot {
        preGates = preGates == null ? List.of() : List.copyOf(preGates);
        decisionBindings = decisionBindings == null ? List.of() : List.copyOf(decisionBindings);
        triggerEventTypes = triggerEventTypes == null ? List.of() : List.copyOf(triggerEventTypes);
        kind = kind == null ? RuleKind.AST_BOOLEAN.tag() : kind;
        metricDependencies = metricDependencies == null ? List.of() : List.copyOf(metricDependencies);
        payloadDependencies = payloadDependencies == null ? List.of() : List.copyOf(payloadDependencies);
        referencedSnapshots = referencedSnapshots == null ? Map.of() : Map.copyOf(referencedSnapshots);
    }

    /**
     * 兼容 13 参调用点(无 flowGraph/referencedSnapshots,默认 null/空 map)。
     *
     * @param ruleVersionId       规则版本 id
     * @param sceneCode           场景编码
     * @param tenantId            租户 id
     * @param conditionAst        条件 AST 根节点
     * @param preGates            Pre-Gate 配置列表
     * @param decisionBindings    Decision 绑定列表
     * @param triggerEventTypes   监听事件类型列表
     * @param kind                规则类型
     * @param code                逻辑规则编码
     * @param version             版本号
     * @param metricDependencies  metric 依赖
     * @param payloadDependencies payload 依赖
     * @param script              脚本载体
     */
    public RuleVersionSnapshot(Long ruleVersionId, String sceneCode, String tenantId, AstNode conditionAst,
                               List<PreGateConfig> preGates, List<DecisionBinding> decisionBindings,
                               List<String> triggerEventTypes, String kind, String code, long version,
                               List<MetricDependency> metricDependencies, List<PayloadDependency> payloadDependencies,
                               ScriptSource script) {
        this(ruleVersionId, sceneCode, tenantId, conditionAst, preGates, decisionBindings,
                triggerEventTypes, kind, code, version, metricDependencies, payloadDependencies, script,
                null, Map.of());
    }

    /**
     * 兼容旧 12 参调用点(无 script,默认 null)。
     *
     * @param ruleVersionId       规则版本 id
     * @param sceneCode           场景编码
     * @param tenantId            租户 id
     * @param conditionAst        条件 AST 根节点
     * @param preGates            Pre-Gate 配置列表
     * @param decisionBindings    Decision 绑定列表
     * @param triggerEventTypes   监听事件类型列表
     * @param kind                规则类型
     * @param code                逻辑规则编码
     * @param version             版本号
     * @param metricDependencies  metric 依赖
     * @param payloadDependencies payload 依赖
     */
    public RuleVersionSnapshot(Long ruleVersionId, String sceneCode, String tenantId, AstNode conditionAst,
                               List<PreGateConfig> preGates, List<DecisionBinding> decisionBindings,
                               List<String> triggerEventTypes, String kind, String code, long version,
                               List<MetricDependency> metricDependencies, List<PayloadDependency> payloadDependencies) {
        this(ruleVersionId, sceneCode, tenantId, conditionAst, preGates, decisionBindings,
                triggerEventTypes, kind, code, version, metricDependencies, payloadDependencies, null);
    }

    /**
     * 兼容旧调用点的便利构造（无 metricDependencies，默认空列表）。
     *
     * @param ruleVersionId     规则版本 id
     * @param sceneCode         场景编码
     * @param tenantId          租户 id
     * @param conditionAst      条件 AST 根节点
     * @param preGates          Pre-Gate 配置列表
     * @param decisionBindings  Decision 绑定列表
     * @param triggerEventTypes 监听事件类型列表
     * @param kind              规则类型
     */
    public RuleVersionSnapshot(Long ruleVersionId, String sceneCode, String tenantId, AstNode conditionAst,
                               List<PreGateConfig> preGates, List<DecisionBinding> decisionBindings,
                               List<String> triggerEventTypes, String kind) {
        this(ruleVersionId, sceneCode, tenantId, conditionAst, preGates, decisionBindings,
                triggerEventTypes, kind, null, 0L, List.of(), List.of());
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

    /** 链式构建器，简化本地模式手工组装 RuleVersionSnapshot。 */
    public static final class Builder {
        private Long ruleVersionId;
        private String sceneCode;
        private String tenantId;
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
        /** 条件 AST 根节点。 */
        public Builder conditionAst(AstNode v){ this.conditionAst = v; return this; }
        /** EXPRESSION_SCRIPT 脚本载体。 */
        public Builder script(ScriptSource v) { this.script = v; return this; }
        /** DECISION_FLOW 决策图。 */
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

        /** 构建 RuleVersionSnapshot。 */
        public RuleVersionSnapshot build() {
            return new RuleVersionSnapshot(ruleVersionId, sceneCode, tenantId, conditionAst,
                    preGates, decisionBindings, triggerEventTypes, kind, code, version,
                    metricDependencies, payloadDependencies, script, flowGraph, referencedSnapshots);
        }
    }
}
