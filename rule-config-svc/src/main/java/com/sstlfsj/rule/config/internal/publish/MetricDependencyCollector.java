package com.sstlfsj.rule.config.internal.publish;

import com.sstlfsj.rule.kernel.api.model.ValueRef;
import com.sstlfsj.rule.kernel.api.model.ast.*;
import com.sstlfsj.rule.kernel.api.model.flow.FlowGraph;
import com.sstlfsj.rule.kernel.api.model.flow.FlowNode;
import com.sstlfsj.rule.kernel.api.model.flow.SwitchNode;
import com.sstlfsj.rule.kernel.api.model.flow.TransformNode;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionCompileException;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionEngine;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 静态扫描 AST 树，收集所有叶子 ConditionNode 引用的 metricCode（去重，保序）。 */
class MetricDependencyCollector {

    /** metrics 命名空间前缀：表达式引用形如 metrics.txn_cnt_1d，冻依赖时去前缀取 code。 */
    private static final String METRIC_PREFIX = "metrics.";
    /** payload 命名空间前缀：表达式引用形如 payload.amount，冻依赖时去前缀取字段名。 */
    private static final String PAYLOAD_PREFIX = "payload.";

    static List<String> collect(AstNode node) {
        Set<String> result = new LinkedHashSet<>();
        walk(node, result);
        return new ArrayList<>(result);
    }

    /**
     * DECISION_FLOW 图内 Switch/Transform 表达式引用的变量，按命名空间前缀分流。
     * subject.* 等其它前缀开放，不冻不校验（同脚本 kind）。
     *
     * @param metricCodes   metrics.* 引用的 metricCode（去前缀、去重、保序）
     * @param payloadFields payload.* 引用的字段名（去前缀、去重、保序）
     */
    record FlowExpressionRefs(List<String> metricCodes, List<String> payloadFields) {}

    /**
     * 扫描 DECISION_FLOW 图内 Switch/Transform 节点的表达式，编译取 referencedVariables，同一遍分流
     * metrics.* 与 payload.* 前缀。编译同时充当表达式语法校验：无对应引擎/语法错抛 IllegalArgumentException。
     *
     * @param flow    决策图
     * @param engines lang → 表达式引擎路由
     * @return 图内表达式引用的 metric code 与 payload 字段（各自去前缀、去重、保序）
     */
    static FlowExpressionRefs collectFlowExpressionRefs(FlowGraph flow, Map<String, ExpressionEngine> engines) {
        Set<String> metricCodes = new LinkedHashSet<>();
        Set<String> payloadFields = new LinkedHashSet<>();
        for (FlowNode node : flow.nodes()) {
            String lang;
            String expression;
            switch (node) {
                case SwitchNode sw -> {
                    lang = sw.lang().tag();
                    expression = sw.expression();
                }
                case TransformNode tf -> {
                    lang = tf.lang().tag();
                    expression = tf.expression();
                }
                default -> {
                    continue;
                }
            }
            ExpressionEngine engine = engines.get(lang);
            if (engine == null) {
                throw new IllegalArgumentException("DECISION_FLOW 表达式无对应引擎,lang=" + lang + " (node=" + node.id() + ")");
            }
            Set<String> refVars;
            try {
                refVars = engine.compile(expression).referencedVariables();
            } catch (ExpressionCompileException e) {
                throw new IllegalArgumentException(
                        "DECISION_FLOW 表达式编译失败(node=" + node.id() + "): " + e.getMessage(), e);
            }
            for (String v : refVars) {
                if (v.startsWith(METRIC_PREFIX)) metricCodes.add(v.substring(METRIC_PREFIX.length()));
                else if (v.startsWith(PAYLOAD_PREFIX)) payloadFields.add(v.substring(PAYLOAD_PREFIX.length()));
            }
        }
        return new FlowExpressionRefs(new ArrayList<>(metricCodes), new ArrayList<>(payloadFields));
    }

    private static void walk(AstNode node, Set<String> acc) {
        switch (node) {
            case AndNode and -> and.children().forEach(c -> walk(c, acc));
            case OrNode or   -> or.children().forEach(c -> walk(c, acc));
            case NotNode not -> walk(not.child(), acc);
            case ConditionNode cond -> {
                // payload 字段不是受治理 metric，不计入依赖
                if (cond.valueRef() != ValueRef.PAYLOAD && cond.metricCode() != null) acc.add(cond.metricCode());
            }
            // ScorecardRootNode：直接遍历叶子条件，收集其 metricCode
            case ScorecardRootNode sc -> sc.conditions().forEach(c -> {
                // payload 字段不是受治理 metric，不计入依赖
                if (c.valueRef() != ValueRef.PAYLOAD && c.metricCode() != null) acc.add(c.metricCode());
            });
            // XorNode：遍历全部子节点（全量，不短路）
            case XorNode xor -> xor.children().forEach(c -> walk(c, acc));
            // IfNode：遍历条件 + 两个分支
            case IfNode ifn -> {
                walk(ifn.condition(), acc);
                walk(ifn.thenBranch(), acc);
                if (ifn.elseBranch() != null) walk(ifn.elseBranch(), acc);
            }
            // DecisionLeafNode：终止节点，无 metric 依赖
            case DecisionLeafNode ignored -> {}
            // DecisionTableNode：遍历列头中的 metricCode；PAYLOAD 列不计入 metric 依赖
            case DecisionTableNode dt ->
                    dt.columns().forEach(col -> {
                        if (col.valueRef() == ValueRef.PAYLOAD) return;
                        if (col.metricCode() != null && !col.metricCode().isBlank()) acc.add(col.metricCode());
                    });
        }
    }
}
