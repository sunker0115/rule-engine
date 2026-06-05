package com.sstlfsj.rule.config.internal.publish;

import com.sstlfsj.rule.kernel.api.model.ast.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 发布期 AST 遍历器：给每个 ConditionNode 冻结 dataType，并校验算子×dataType 兼容性。
 * 遍历方式仿 MetricDependencyCollector，但需重建不可变 record 树（ConditionNode 新增 dataType）。
 * DecisionTableNode 原样返回——B19 不冻结决策表列的 dataType（已知边界，留后续）。
 */
class AstDataTypeResolver {

    // 算子允许的 dataType 集合（权威来源：spec §5 / 03-rule-expression §3.1-3.4）
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "EQ",          Set.of("LONG", "DOUBLE", "STRING", "BOOLEAN"),
            "NEQ",         Set.of("LONG", "DOUBLE", "STRING", "BOOLEAN"),
            "GT",          Set.of("LONG", "DOUBLE"),
            "GTE",         Set.of("LONG", "DOUBLE"),
            "LT",          Set.of("LONG", "DOUBLE"),
            "LTE",         Set.of("LONG", "DOUBLE"),
            "BETWEEN",     Set.of("LONG", "DOUBLE"),
            "NOT_BETWEEN", Set.of("LONG", "DOUBLE"),
            "IN",          Set.of("LONG", "STRING"),
            "NOT_IN",      Set.of("LONG", "STRING")
    );

    /**
     * 递归遍历 AST，给 ConditionNode 冻结 dataType 并校验算子兼容性，返回重建的新树。
     *
     * @param root        原始 AST 根节点
     * @param dataTypeMap metricCode -> dataType 映射（来自 metric_definition 查询结果）
     * @return 含冻结 dataType 的新 AST 树（不可变 record 重建）
     */
    static AstNode resolve(AstNode root, Map<String, String> dataTypeMap) {
        return switch (root) {
            case ConditionNode cond -> resolveCondition(cond, dataTypeMap);
            case AndNode and -> new AndNode(
                    resolveList(and.children(), dataTypeMap),
                    and.displayLabel(), and.weight());
            case OrNode or -> new OrNode(
                    resolveList(or.children(), dataTypeMap),
                    or.displayLabel(), or.weight());
            case NotNode not -> new NotNode(resolve(not.child(), dataTypeMap));
            case XorNode xor -> new XorNode(
                    resolveList(xor.children(), dataTypeMap),
                    xor.displayLabel());
            case ScorecardRootNode sc -> new ScorecardRootNode(
                    resolveConditionList(sc.conditions(), dataTypeMap),
                    sc.threshold());
            case IfNode ifn -> new IfNode(
                    resolve(ifn.condition(), dataTypeMap),
                    resolve(ifn.thenBranch(), dataTypeMap),
                    ifn.elseBranch() != null ? resolve(ifn.elseBranch(), dataTypeMap) : null);
            // DecisionLeafNode/DecisionTableNode 原样返回（B19 不处理）
            case DecisionLeafNode leaf -> leaf;
            case DecisionTableNode dt  -> dt;
        };
    }

    private static ConditionNode resolveCondition(ConditionNode cond,
                                                   Map<String, String> dataTypeMap) {
        String dataType = dataTypeMap.get(cond.metricCode());
        // 校验仅在 dataType 已知且不是 LIST/null 时执行
        if (dataType != null && !"LIST".equals(dataType)) {
            Set<String> allowed = ALLOWED.get(cond.conditionType());
            if (allowed != null && !allowed.contains(dataType)) {
                throw new IllegalArgumentException(
                        "算子 " + cond.conditionType() + " 不支持 dataType=" + dataType
                        + "（metric=" + cond.metricCode() + "）");
            }
        }
        // 重建 ConditionNode，冻结 dataType（查不到的 metric -> dataType=null，原样不变）
        return new ConditionNode(cond.conditionType(), cond.metricCode(),
                cond.displayLabel(), cond.params(), cond.weight(), dataType);
    }

    private static List<AstNode> resolveList(List<AstNode> nodes,
                                              Map<String, String> dataTypeMap) {
        return nodes.stream().map(n -> resolve(n, dataTypeMap)).toList();
    }

    private static List<ConditionNode> resolveConditionList(List<ConditionNode> nodes,
                                                             Map<String, String> dataTypeMap) {
        return nodes.stream().map(n -> resolveCondition(n, dataTypeMap)).toList();
    }
}
