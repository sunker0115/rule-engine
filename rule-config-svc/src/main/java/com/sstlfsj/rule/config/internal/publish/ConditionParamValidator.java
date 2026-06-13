package com.sstlfsj.rule.config.internal.publish;

import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.ast.DecisionLeafNode;
import com.sstlfsj.rule.kernel.api.model.ast.DecisionTableNode;
import com.sstlfsj.rule.kernel.api.model.ast.IfNode;
import com.sstlfsj.rule.kernel.api.model.ast.NotNode;
import com.sstlfsj.rule.kernel.api.model.ast.OrNode;
import com.sstlfsj.rule.kernel.api.model.ast.ScorecardRootNode;
import com.sstlfsj.rule.kernel.api.model.ast.XorNode;
import com.sstlfsj.rule.kernel.api.operator.OperatorSpec;
import com.sstlfsj.rule.kernel.internal.condition.ConditionTypeCatalog;

/**
 * 发布期 param 键校验：遍历 AST，按 {@link ConditionTypeCatalog} 校每个 ConditionNode 的必填 param 键齐全。
 * 目录缺席的 conditionType（SPI 自定义 / time.* 内置路径）放行。缺键抛 IllegalArgumentException 拒绝发布。
 */
final class ConditionParamValidator {

    private ConditionParamValidator() {}

    static void validate(AstNode node) {
        switch (node) {
            case ConditionNode c -> validateLeaf(c);
            case AndNode a -> a.children().forEach(ConditionParamValidator::validate);
            case OrNode o -> o.children().forEach(ConditionParamValidator::validate);
            case NotNode n -> validate(n.child());
            case XorNode x -> x.children().forEach(ConditionParamValidator::validate);
            case ScorecardRootNode sc -> sc.conditions().forEach(ConditionParamValidator::validateLeaf);
            case IfNode ifn -> {
                validate(ifn.condition());
                validate(ifn.thenBranch());
                if (ifn.elseBranch() != null) validate(ifn.elseBranch());
            }
            case DecisionLeafNode ignored -> { }
            case DecisionTableNode ignored -> { }
        }
    }

    private static void validateLeaf(ConditionNode c) {
        OperatorSpec spec = ConditionTypeCatalog.spec(c.conditionType());
        if (spec == null) return;
        for (String key : spec.requiredParamKeys()) {
            if (!c.params().containsKey(key)) {
                throw new IllegalArgumentException(
                        "算子 " + c.conditionType() + " 缺少必填参数键 \"" + key + "\""
                        + "（metric=" + c.metricCode() + "，必填键=" + spec.requiredParamKeys() + "）");
            }
        }
    }
}
