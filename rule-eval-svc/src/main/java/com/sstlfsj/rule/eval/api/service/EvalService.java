package com.sstlfsj.rule.eval.api.service;

import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;

/** 使用场景规则索引和已注册 SPI 组件对规则事件进行评估。 */
public interface EvalService {

    /**
     * 接收规则事件，异步 PUSH 模式评估。
     *
     * @param event 待评估的规则事件
     * @return 事件被接受返回 true，被拒绝返回 false
     */
    boolean acceptEvent(RuleEvent event);

    /**
     * 同步 PULL 模式评估规则事件，返回完整结果。
     *
     * @param event 待评估的规则事件
     * @return 包含决策和 Action 结果的完整评估结果
     */
    EvalResult evaluate(RuleEvent event);

    /**
     * 执行 dry-run 评估，返回含节点 trace 的结果，不派发 Action。
     *
     * @param event         待评估的规则事件
     * @param ruleVersionId 指定测试的规则版本 ID，null 表示使用当前活跃版本
     * @return 包含详细节点 trace 的评估结果，不执行任何 Action
     */
    EvalResult dryRun(RuleEvent event, Long ruleVersionId);
}
