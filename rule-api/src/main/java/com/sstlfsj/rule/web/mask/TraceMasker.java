package com.sstlfsj.rule.web.mask;

import com.sstlfsj.rule.audit.api.service.AuditService.TraceNodeEntry;
import com.sstlfsj.rule.audit.api.service.AuditService.TraceTreeNode;
import com.sstlfsj.rule.config.api.service.SceneService.SensitiveRefs;
import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import com.sstlfsj.rule.kernel.api.model.ValueSource;

import java.util.List;

/**
 * trace 读时 PII 脱敏纯函数（D71）。把命中敏感集的叶子 actualValue 抹成 {@value #MASK}，返回新结构（不改原对象）。
 *
 * <p>三种 trace 形状各一个方法（泛型擦除不允许同名重载）：扁平 {@link TraceNodeEntry}、
 * 嵌套 {@link TraceTreeNode}、kernel {@link NodeTrace}。</p>
 *
 * <p><b>fail-closed：</b>{@code refs == null} 表示敏感集查询失败，全抹——任意 actualValue 非 null 的叶子都遮蔽，
 * 宁可过度遮蔽不漏 PII。{@code refs} 非 null（即便空集）时仅按敏感集精确遮蔽。</p>
 *
 * <p>匹配规则：叶子 valueSource==PAYLOAD 时查敏感 payload 字段集;否则（FETCHED/PROVIDED）查敏感 metric 码集。
 * 容器节点 actualValue 本就 null，不受影响。</p>
 */
public final class TraceMasker {

    /** 遮蔽后的固定占位串。 */
    public static final String MASK = "***";
    private static final String PAYLOAD = ValueSource.PAYLOAD.tag();

    private TraceMasker() {}

    /** 遮蔽扁平 trace 列表。{@code refs==null} 时 fail-closed 全抹。 */
    public static List<TraceNodeEntry> maskFlat(SensitiveRefs refs, List<TraceNodeEntry> nodes) {
        if (nodes == null || nodes.isEmpty()) return List.of();
        return nodes.stream().map(n -> shouldMask(refs, n.valueSource(), n.metricCode(), n.actualValue())
                ? new TraceNodeEntry(n.nodePath(), n.nodeType(), n.conditionType(), n.metricCode(),
                        MASK, n.result(), n.errorCode(), n.valueSource(), n.ruleCode(), n.ruleVersion())
                : n).toList();
    }

    /** 递归遮蔽嵌套 trace 树。{@code refs==null} 时 fail-closed 全抹。 */
    public static List<TraceTreeNode> maskTree(SensitiveRefs refs, List<TraceTreeNode> nodes) {
        if (nodes == null || nodes.isEmpty()) return List.of();
        return nodes.stream().map(n -> {
            List<TraceTreeNode> children = maskTree(refs, n.children());
            Object masked = shouldMask(refs, n.valueSource(), n.metricCode(), n.actualValue())
                    ? MASK : n.actualValue();
            return new TraceTreeNode(n.nodeType(), n.conditionType(), n.metricCode(),
                    (String) masked, n.result(), n.errorCode(), n.valueSource(),
                    n.ruleCode(), n.ruleVersion(), children);
        }).toList();
    }

    /** 递归遮蔽 kernel NodeTrace 列表（dry-run / replay 内存 trace）。{@code refs==null} 时 fail-closed 全抹。 */
    public static List<NodeTrace> maskKernel(SensitiveRefs refs, List<NodeTrace> nodes) {
        if (nodes == null || nodes.isEmpty()) return List.of();
        return nodes.stream().map(n -> {
            List<NodeTrace> children = maskKernel(refs, n.children());
            Object masked = shouldMask(refs, n.valueSource(), n.metricCode(), n.actualValue())
                    ? MASK : n.actualValue();
            return new NodeTrace(n.nodeType(), n.conditionType(), n.metricCode(), n.result(),
                    masked, n.valueSource(), n.errorCode(), children, n.ruleVersionId(),
                    n.ruleCode(), n.ruleVersion(), n.expectedValue(), n.displayLabel());
        }).toList();
    }

    /**
     * 判断某叶子是否该遮蔽。actualValue 为 null 的容器节点恒不遮蔽。
     * refs==null（fail-closed）时只要 actualValue 非 null 即遮蔽。
     */
    private static boolean shouldMask(SensitiveRefs refs, String valueSource,
                                      String metricCode, Object actualValue) {
        if (actualValue == null) return false;
        if (refs == null) return true; // fail-closed：全抹
        if (metricCode == null) return false;
        if (PAYLOAD.equals(valueSource)) return refs.payloadFields().contains(metricCode);
        // 非 PAYLOAD（FETCHED / PROVIDED）归入 metric 集判定
        return refs.metricCodes().contains(metricCode);
    }
}
