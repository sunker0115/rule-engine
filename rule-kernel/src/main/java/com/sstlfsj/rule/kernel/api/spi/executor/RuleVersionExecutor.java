package com.sstlfsj.rule.kernel.api.spi.executor;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;

/** Evaluates a rule version snapshot against the provided context. */
public interface RuleVersionExecutor {
    /** Evaluates the given rule version snapshot against the provided context and returns the result. */
    EvalResult execute(RuleVersionSnapshot snapshot, EvalContext ctx);
}
