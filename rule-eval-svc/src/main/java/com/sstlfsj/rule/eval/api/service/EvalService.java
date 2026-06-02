package com.sstlfsj.rule.eval.api.service;

import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;

/** Evaluates rule events using the scene rule index and registered SPI components. */
public interface EvalService {

    /**
     * Accepts a rule event for asynchronous PUSH-mode evaluation.
     *
     * @param event the rule event to evaluate
     * @return true if the event was accepted for processing, false if rejected
     */
    boolean acceptEvent(RuleEvent event);

    /**
     * Synchronously evaluates a rule event in PULL mode and returns the full result.
     *
     * @param event the rule event to evaluate
     * @return the complete evaluation result including decisions and action results
     */
    EvalResult evaluate(RuleEvent event);

    /**
     * Performs a dry-run evaluation without dispatching actions.
     *
     * @param event         the rule event to evaluate
     * @param ruleVersionId specific rule version to test, or null to use the active version
     * @return the evaluation result including detailed node trace, with no actions dispatched
     */
    EvalResult dryRun(RuleEvent event, Long ruleVersionId);
}
