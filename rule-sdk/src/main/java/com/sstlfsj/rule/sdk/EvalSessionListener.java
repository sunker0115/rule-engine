package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;

/** 可选审计回调，业务方自行决定是否写评估日志。 */
public interface EvalSessionListener {
    void onSession(RuleEvent event, EvalResult result);
}
