package com.sstlfsj.rule.kernel.api.trace;

import com.sstlfsj.rule.kernel.api.model.NodeTrace;

import java.util.List;

/**
 * 把一次评估的 NodeTrace 树拍平成单行紧凑字符串，供线上日志 grep 排障。
 * 非持久化契约（结构化 trace 落 node_trace 表）；本视图面向人读/检索，格式可随需调整。
 *
 * <p>格式：每棵规则树前缀 {@code <ruleCode>#v<version>=}，多棵以空格分隔；
 * 叶子条件渲染 {@code <conditionType>[:<metricCode>]:<R>}，容器渲染 {@code <Type>:<R>[<child>,...]}；
 * 结果标记 {@code R} 为 {@code T}（true）/{@code F}（false）/{@code -}（result 为 null）/{@code E:<errorCode>}（出错优先）。
 * 示例：{@code PROMO_A#v3=And:T[GT:order_amount:T,IN:user_level:T]}。
 */
public final class NodeTraceFormatter {

    private NodeTraceFormatter() {
    }

    /**
     * 将多棵规则 trace 树渲染为单行字符串。
     *
     * @param traces 评估产出的顶层 NodeTrace 列表（每条求值规则一棵）；null/空返回 {@code "[]"}
     * @return 紧凑单行表示
     */
    public static String compact(List<NodeTrace> traces) {
        if (traces == null || traces.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < traces.size(); i++) {
            if (i > 0) sb.append(' ');
            NodeTrace t = traces.get(i);
            appendPrefix(sb, t);
            appendNode(sb, t);
        }
        return sb.toString();
    }

    /** 顶层前缀 {@code <ruleCode>#v<version>=}；ruleCode 缺省回退 {@code rv<ruleVersionId>}，皆无则省略。 */
    private static void appendPrefix(StringBuilder sb, NodeTrace t) {
        String code = t.ruleCode();
        if (code == null && t.ruleVersionId() != null) {
            code = "rv" + t.ruleVersionId();
        }
        if (code == null) return;
        sb.append(code);
        if (t.ruleVersion() > 0) sb.append("#v").append(t.ruleVersion());
        sb.append('=');
    }

    /** 递归渲染单节点：叶子带 conditionType[:metric]，容器用类型短名；再附结果标记与子节点。 */
    private static void appendNode(StringBuilder sb, NodeTrace node) {
        if (node.conditionType() != null) {
            sb.append(node.conditionType());
            if (node.metricCode() != null) sb.append(':').append(node.metricCode());
        } else {
            sb.append(shortType(node.nodeType()));
        }
        sb.append(':').append(mark(node));
        List<NodeTrace> children = node.children();
        if (!children.isEmpty()) {
            sb.append('[');
            for (int i = 0; i < children.size(); i++) {
                if (i > 0) sb.append(',');
                appendNode(sb, children.get(i));
            }
            sb.append(']');
        }
    }

    /** 结果标记：错误优先 {@code E:<code>}，否则 {@code T}/{@code F}/{@code -}（result 为 null 的容器）。 */
    private static String mark(NodeTrace node) {
        if (node.errorCode() != null) return "E:" + node.errorCode();
        Boolean r = node.result();
        return r == null ? "-" : (r ? "T" : "F");
    }

    /** nodeType tag 去掉 "Node" 后缀（AndNode→And）；无后缀原样；null 用 "?"。 */
    private static String shortType(String nodeType) {
        if (nodeType == null) return "?";
        return nodeType.endsWith("Node") ? nodeType.substring(0, nodeType.length() - 4) : nodeType;
    }
}
