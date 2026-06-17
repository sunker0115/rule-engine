package com.sstlfsj.rule.config.api.dto;

/**
 * Bundle import 冲突处理策略。
 * <ul>
 *   <li>{@link #SKIP}（默认）：目标已存在同 code 规则（无论 hash 是否相同）→ 跳过，保留现有版本。</li>
 *   <li>{@link #OVERWRITE}：目标有待发布 DRAFT → 先清除再建新 DRAFT；无 DRAFT 则直接建。
 *       注意：已发布版本不会被直接覆盖，新 DRAFT 仍需显式发布。</li>
 *   <li>{@link #ABORT}：collect-all 模式，跑完所有规则收集全部冲突，有冲突则整体终止不落任何数据。</li>
 * </ul>
 */
public enum ImportPolicy {
    SKIP,
    OVERWRITE,
    ABORT
}
