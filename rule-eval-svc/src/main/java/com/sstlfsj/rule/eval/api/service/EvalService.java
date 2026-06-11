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
     * @return 包含决策的完整评估结果
     */
    EvalResult evaluate(RuleEvent event);

    /**
     * 执行 dry-run 评估，返回含节点 trace 的结果，不落 evaluation_session。
     * ruleId / ruleVersionId 二选一必传：都不传抛 IllegalArgumentException（MISSING_DRYRUN_TARGET → 400）。
     *
     * @param event         待评估的规则事件
     * @param ruleId        规则 id（取其最新版本，含 DRAFT）；与 ruleVersionId 二选一
     * @param ruleVersionId 精确版本 id；与 ruleId 二选一，优先生效
     * @return 包含详细节点 trace 的评估结果
     */
    EvalResult dryRun(RuleEvent event, Long ruleId, Long ruleVersionId);
}
