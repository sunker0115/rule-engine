package com.sstlfsj.rule.kernel.api.model;

import com.sstlfsj.rule.kernel.api.model.ast.AstNode;

import java.util.ArrayList;
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
        /** AST 引用的 (metricCode, metricVersion) 依赖，发布期冻结。 */
        List<MetricDependency> metricDependencies
) {
    public RuleVersionSnapshot {
        preGates = preGates == null ? List.of() : List.copyOf(preGates);
        decisionBindings = decisionBindings == null ? List.of() : List.copyOf(decisionBindings);
        triggerEventTypes = triggerEventTypes == null ? List.of() : List.copyOf(triggerEventTypes);
        kind = kind == null ? RuleKind.AST_BOOLEAN.tag() : kind;
        metricDependencies = metricDependencies == null ? List.of() : List.copyOf(metricDependencies);
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
                triggerEventTypes, kind, List.of());
    }

    /** Pre-Gate 配置快照。 */
    public record PreGateConfig(String gateType, Map<String, Object> params) {
        public PreGateConfig {
            params = params == null ? Map.of() : Map.copyOf(params);
        }
    }

    /** Decision 绑定配置快照。 */
    public record DecisionBinding(String decisionCode, int priority) {}

    /**
     * Decision 内单个 action 项，对应 decision_definition.actions JSON 数组元素。
     *
     * @param actionId   action 实例标识
     * @param actionType actionType 路由键
     * @param sortOrder  执行顺序，升序
     * @param params     依 actionType 异构的开放参数（结构无定义，故为 Map）
     */
    public record DecisionAction(String actionId, String actionType, int sortOrder, Map<String, Object> params) {
        public DecisionAction {
            params = params == null ? Map.of() : Map.copyOf(params);
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
        private String kind = RuleKind.AST_BOOLEAN.tag();
        private final List<PreGateConfig> preGates = new ArrayList<>();
        private final List<DecisionBinding> decisionBindings = new ArrayList<>();
        private final List<String> triggerEventTypes = new ArrayList<>();
        private final List<MetricDependency> metricDependencies = new ArrayList<>();

        /** 规则版本 ID（本地模式可传任意 Long）。 */
        public Builder ruleVersionId(Long v)  { this.ruleVersionId = v; return this; }
        /** 场景编码。 */
        public Builder sceneCode(String v)    { this.sceneCode = v; return this; }
        /** 租户 ID。 */
        public Builder tenantId(String v)     { this.tenantId = v; return this; }
        /** 条件 AST 根节点。 */
        public Builder conditionAst(AstNode v){ this.conditionAst = v; return this; }
        /** 规则类型，默认 AST_BOOLEAN。 */
        public Builder kind(String v)         { this.kind = v; return this; }
        /** 追加一个监听的事件类型。 */
        public Builder addTriggerEventType(String v) { triggerEventTypes.add(v); return this; }
        /** 追加一个 Decision 绑定。 */
        public Builder addDecisionBinding(String decisionCode, int priority) {
            decisionBindings.add(new DecisionBinding(decisionCode, priority)); return this;
        }
        /** 追加一个 Pre-Gate 配置。 */
        public Builder addPreGate(String gateType, Map<String, Object> params) {
            preGates.add(new PreGateConfig(gateType, params)); return this;
        }
        /** 追加一个 metric 版本化依赖。 */
        public Builder addMetricDependency(String metricCode, int metricVersion) {
            metricDependencies.add(new MetricDependency(metricCode, metricVersion)); return this;
        }

        /** 构建 RuleVersionSnapshot。 */
        public RuleVersionSnapshot build() {
            return new RuleVersionSnapshot(ruleVersionId, sceneCode, tenantId, conditionAst,
                    preGates, decisionBindings, triggerEventTypes, kind, metricDependencies);
        }
    }
}
