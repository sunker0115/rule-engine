package com.sstlfsj.rule.eval.internal.async;

import com.sstlfsj.rule.eval.internal.event.DomainEvent;
import com.sstlfsj.rule.eval.internal.event.Durability;
import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;

/** dry-run 完成事件(best-effort):供异步落 dry_run_session + trace(单次终态)。 */
public record DryRunRecorded(long sessionId, RuleEvent event, Long ruleVersionId,
                             EvalResult result, EvalContext context) implements DomainEvent {
    @Override
    public Durability durability() { return Durability.BEST_EFFORT; }
}
