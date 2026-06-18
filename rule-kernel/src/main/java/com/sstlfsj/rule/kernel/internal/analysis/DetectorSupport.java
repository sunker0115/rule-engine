package com.sstlfsj.rule.kernel.internal.analysis;

import java.util.ArrayList;
import java.util.List;

/** 两两检测器共享：把规则集投影为 {@link ProjectedRule} 列表，剔除不可投影 / 无绑定者，保输入序。 */
final class DetectorSupport {

    private DetectorSupport() {}

    /**
     * 投影规则集为比较单元列表。
     *
     * <p>不可投影（非 AST_BOOLEAN flat-AND）或无决策绑定的规则被剔除；保留输入顺序，
     * 使两两遍历中靠前规则恒为 "A"，输出可复现。
     *
     * @param rules 原始规则列表（由编排层传入，非 null）
     * @return 投影后的比较单元，按输入序
     */
    static List<ProjectedRule> project(List<AnalyzableRule> rules) {
        List<ProjectedRule> projected = new ArrayList<>();
        for (AnalyzableRule rule : rules) {
            ProjectedRule.of(rule).ifPresent(projected::add);
        }
        return projected;
    }
}
