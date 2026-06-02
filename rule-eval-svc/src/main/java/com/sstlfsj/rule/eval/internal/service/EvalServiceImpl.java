package com.sstlfsj.rule.eval.internal.service;

import com.sstlfsj.rule.eval.api.service.EvalService;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import org.springframework.stereotype.Service;

@Service
class EvalServiceImpl implements EvalService {

    @Override
    public boolean acceptEvent(RuleEvent event) {
        throw new UnsupportedOperationException("acceptEvent not yet implemented");
    }

    @Override
    public EvalResult evaluate(RuleEvent event) {
        throw new UnsupportedOperationException("evaluate not yet implemented");
    }

    @Override
    public EvalResult dryRun(RuleEvent event, Long ruleVersionId) {
        throw new UnsupportedOperationException("dryRun not yet implemented");
    }
}
