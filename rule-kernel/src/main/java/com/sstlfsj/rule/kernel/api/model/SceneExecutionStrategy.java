package com.sstlfsj.rule.kernel.api.model;

/**
 * 场景级规则执行策略。
 *
 * <ul>
 *   <li>{@link #HIGHEST_PRIORITY}：全量评估，取最高优先级的命中决策（默认）</li>
 *   <li>{@link #ALL_HITS}：全量评估，收集所有命中决策，finalDecision 仍取最高优先级</li>
 *   <li>{@link #FIRST_HIT}：按优先级倒序，第一条命中即短路返回，节省后续评估开销</li>
 * </ul>
 */
public enum SceneExecutionStrategy {
    HIGHEST_PRIORITY,
    ALL_HITS,
    FIRST_HIT
}
