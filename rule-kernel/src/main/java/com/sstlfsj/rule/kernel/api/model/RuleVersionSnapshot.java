package com.sstlfsj.rule.kernel.api.model;

import com.sstlfsj.rule.kernel.api.model.ast.AstNode;

import java.util.List;
import java.util.Map;

/** 规则版本的不可变运行时快照，由 Matcher 倒排索引持有，评估期只读。 */
public record RuleVersionSnapshot(
        Long ruleVersionId,
        String sceneCode,
        String tenantId,
        AstNode conditionAst,
        List<PreGateConfig> preGates,
        List<DecisionBinding> decisionBindings
) {
    public RuleVersionSnapshot {
        preGates = preGates == null ? List.of() : List.copyOf(preGates);
        decisionBindings = decisionBindings == null ? List.of() : List.copyOf(decisionBindings);
    }

    /** Pre-Gate 配置快照。 */
    public record PreGateConfig(String gateType, Map<String, Object> params) {
        public PreGateConfig {
            params = params == null ? Map.of() : Map.copyOf(params);
        }
    }

    /** Decision 绑定配置快照。 */
    public record DecisionBinding(String decisionCode, int priority) {}
}
