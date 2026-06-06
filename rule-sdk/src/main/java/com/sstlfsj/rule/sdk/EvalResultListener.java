package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;

/** 规则命中后回调，业务方自行决定如何处理 Decision。 */
public interface EvalResultListener {
    void onResult(RuleEvent event, EvalResult result);
}
