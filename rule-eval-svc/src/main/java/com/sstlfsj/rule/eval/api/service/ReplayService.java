package com.sstlfsj.rule.eval.api.service;

import com.sstlfsj.rule.kernel.api.model.EvalResult;

/** 历史评估会话的忠实重放：锁当时版本 + 灌当时数据 + 跳过取数，只读重跑。 */
public interface ReplayService {

    /**
     * 重放一个历史评估会话。
     *
     * @param tenantId  租户 id
     * @param sessionId 评估会话 id
     * @return 与当时一致的评估结果（含 nodeTrace）
     * @throws IllegalArgumentException REPLAY_SESSION_NOT_FOUND（不存在/跨租户）/
     *                                  REPLAY_NOT_REPRODUCIBLE（缺 payload/候选id/snapshot）/
     *                                  REPLAY_VERSION_MISSING（候选版本不存在）
     */
    EvalResult replay(Long tenantId, Long sessionId);
}
