package com.sstlfsj.rule.config.internal.publish;

import com.sstlfsj.rule.kernel.api.model.ConditionType;
import com.sstlfsj.rule.kernel.api.model.DataType;
import com.sstlfsj.rule.kernel.api.model.ast.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 发布期 AST 遍历器：给每个 ConditionNode 与决策表列冻结 dataType，并校验算子×dataType 兼容性。
 * 遍历方式仿 MetricDependencyCollector，但需重建不可变 record 树（ConditionNode / Column 新增 dataType）。
 * 决策表列 dataType 冻结 + 发布校验已落地（B22）。
 */
class AstDataTypeResolver {

    // 算子允许的 dataType 集合（权威来源：spec §5 / 03-rule-expression §3.1-3.4）。
    // DATE_BEFORE/DATE_AFTER 已纳入矩阵（B20 §7），仅允许 DATE/DATETIME；
    // 剩余 time.* 内置路径与自定义算子仍 ALLOWED 缺席即放行。
    private static final Map<String, Set<String>> ALLOWED;

    static {
        Map<String, Set<String>> m = new HashMap<>();
        m.put(ConditionType.EQ,           Set.of(DataType.LONG.tag(), DataType.DOUBLE.tag(), DataType.DECIMAL.tag(), DataType.STRING.tag(), DataType.BOOLEAN.tag(), DataType.DATE.tag(), DataType.DATETIME.tag()));
        m.put(ConditionType.NEQ,          Set.of(DataType.LONG.tag(), DataType.DOUBLE.tag(), DataType.DECIMAL.tag(), DataType.STRING.tag(), DataType.BOOLEAN.tag(), DataType.DATE.tag(), DataType.DATETIME.tag()));
        m.put(ConditionType.GT,           Set.of(DataType.LONG.tag(), DataType.DOUBLE.tag(), DataType.DECIMAL.tag()));
        m.put(ConditionType.GTE,          Set.of(DataType.LONG.tag(), DataType.DOUBLE.tag(), DataType.DECIMAL.tag()));
        m.put(ConditionType.LT,           Set.of(DataType.LONG.tag(), DataType.DOUBLE.tag(), DataType.DECIMAL.tag()));
        m.put(ConditionType.LTE,          Set.of(DataType.LONG.tag(), DataType.DOUBLE.tag(), DataType.DECIMAL.tag()));
        m.put(ConditionType.BETWEEN,      Set.of(DataType.LONG.tag(), DataType.DOUBLE.tag(), DataType.DECIMAL.tag(), DataType.DATE.tag(), DataType.DATETIME.tag()));
        m.put(ConditionType.NOT_BETWEEN,  Set.of(DataType.LONG.tag(), DataType.DOUBLE.tag(), DataType.DECIMAL.tag(), DataType.DATE.tag(), DataType.DATETIME.tag()));
        m.put(ConditionType.IN,           Set.of(DataType.LONG.tag(), DataType.STRING.tag()));
        m.put(ConditionType.NOT_IN,       Set.of(DataType.LONG.tag(), DataType.STRING.tag()));
        m.put(ConditionType.CONTAINS,     Set.of(DataType.LIST.tag()));
        m.put(ConditionType.NOT_CONTAINS, Set.of(DataType.LIST.tag()));
        m.put(ConditionType.STARTS_WITH,  Set.of(DataType.STRING.tag()));
        m.put(ConditionType.ENDS_WITH,    Set.of(DataType.STRING.tag()));
        m.put(ConditionType.MATCHES,      Set.of(DataType.STRING.tag()));
        m.put(ConditionType.DATE_BEFORE,  Set.of(DataType.DATE.tag(), DataType.DATETIME.tag()));
        m.put(ConditionType.DATE_AFTER,   Set.of(DataType.DATE.tag(), DataType.DATETIME.tag()));
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
            // 决策表：逐列从 metricCode 冻结 dataType + 校验算子兼容性（B22）
            case DecisionTableNode dt  -> resolveDecisionTable(dt, dataTypeMap);
        };
    }

    private static DecisionTableNode resolveDecisionTable(DecisionTableNode dt,
                                                          Map<String, String> dataTypeMap) {
        List<DecisionTableNode.Column> columns = dt.columns().stream()
                .map(c -> resolveColumn(c, dataTypeMap)).toList();
        // rows 仅为条件值 + decisionCode，无 metric/dataType，原样保留
        return new DecisionTableNode(columns, dt.rows());
    }

    private static DecisionTableNode.Column resolveColumn(DecisionTableNode.Column col,
                                                          Map<String, String> dataTypeMap) {
        String dataType = dataTypeMap.get(col.metricCode());
        // 校验同 resolveCondition：dataType 已知且算子在 ALLOWED 内才校验；缺席算子放行
        if (dataType != null) {
            Set<String> allowed = ALLOWED.get(col.operator());
            if (allowed != null && !allowed.contains(dataType)) {
                throw new IllegalArgumentException(
                        "算子 " + col.operator() + " 不支持 dataType=" + dataType
                        + "（决策表列 metric=" + col.metricCode() + "）");
            }
        }
        return new DecisionTableNode.Column(col.metricCode(), col.operator(), dataType);
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
