package com.sstlfsj.rule.config.internal.publish;

import com.sstlfsj.rule.kernel.api.model.ast.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 发布期 AST 遍历器：给每个 ConditionNode 冻结 dataType，并校验算子×dataType 兼容性。
 * 遍历方式仿 MetricDependencyCollector，但需重建不可变 record 树（ConditionNode 新增 dataType）。
 * DecisionTableNode 原样返回——B19 不冻结决策表列的 dataType（已知边界，留后续）。
 */
class AstDataTypeResolver {

    // 算子允许的 dataType 集合（权威来源：spec §5 / 03-rule-expression §3.1-3.4）。
    // DATE_BEFORE/DATE_AFTER 已纳入矩阵（B20 §7），仅允许 DATE/DATETIME；
    // 剩余 time.* 内置路径与自定义算子仍 ALLOWED 缺席即放行。
    private static final Map<String, Set<String>> ALLOWED;

    static {
        Map<String, Set<String>> m = new HashMap<>();
        m.put("EQ",           Set.of("LONG", "DOUBLE", "STRING", "BOOLEAN", "DATE", "DATETIME"));
        m.put("NEQ",          Set.of("LONG", "DOUBLE", "STRING", "BOOLEAN", "DATE", "DATETIME"));
        m.put("GT",           Set.of("LONG", "DOUBLE"));
        m.put("GTE",          Set.of("LONG", "DOUBLE"));
        m.put("LT",           Set.of("LONG", "DOUBLE"));
        m.put("LTE",          Set.of("LONG", "DOUBLE"));
        m.put("BETWEEN",      Set.of("LONG", "DOUBLE", "DATE", "DATETIME"));
        m.put("NOT_BETWEEN",  Set.of("LONG", "DOUBLE", "DATE", "DATETIME"));
        m.put("IN",           Set.of("LONG", "STRING"));
        m.put("NOT_IN",       Set.of("LONG", "STRING"));
        m.put("CONTAINS",     Set.of("LIST"));
        m.put("NOT_CONTAINS", Set.of("LIST"));
        m.put("STARTS_WITH",  Set.of("STRING"));
        m.put("ENDS_WITH",    Set.of("STRING"));
        m.put("MATCHES",      Set.of("STRING"));
        m.put("DATE_BEFORE",  Set.of("DATE", "DATETIME"));
        m.put("DATE_AFTER",   Set.of("DATE", "DATETIME"));
        ALLOWED = Map.copyOf(m);
    }

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
            // 决策树终端叶子：无比较、无 metric，永久原样返回（非待办）
            case DecisionLeafNode leaf -> leaf;
            // 决策表：列级 dataType 冻结 + 发布校验留后续（backlog B22），求值期合成节点走 Default 策略
            case DecisionTableNode dt  -> dt;
        };
    }

    private static ConditionNode resolveCondition(ConditionNode cond,
                                                   Map<String, String> dataTypeMap) {
        String dataType = dataTypeMap.get(cond.metricCode());
        // 校验仅在 dataType 已知（非 null）时执行；ALLOWED 缺席的算子（time.*、自定义）直接放行
        // DATE_BEFORE/DATE_AFTER 已纳入 ALLOWED（B20），不再绕过校验
        if (dataType != null) {
            Set<String> allowed = ALLOWED.get(cond.conditionType());
            if (allowed != null && !allowed.contains(dataType)) {
                throw new IllegalArgumentException(
                        "算子 " + cond.conditionType() + " 不支持 dataType=" + dataType
                        + "（metric=" + cond.metricCode() + "）");
            }
        }
        // 重建 ConditionNode，冻结 dataType（查不到的 metric -> dataType=null，原样不变）。
        // 不变量：草稿 AST 的 ConditionNode.dataType 一律为 null（DSL 构造路径），
        // 本次赋值是唯一的写入点，不存在覆盖既有值的情况。
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
