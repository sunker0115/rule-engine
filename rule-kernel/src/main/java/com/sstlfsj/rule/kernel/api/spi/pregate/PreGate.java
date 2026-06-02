package com.sstlfsj.rule.kernel.api.spi.pregate;

import com.sstlfsj.rule.kernel.api.model.PreGateContext;
import com.sstlfsj.rule.kernel.api.model.PreGateResult;

/** Pre-gate check run before rule evaluation (e.g., ROLLOUT, RATE_LIMIT, WHITELIST). */
public interface PreGate {
    String gateType();
    PreGateResult evaluate(PreGateContext ctx);
}
