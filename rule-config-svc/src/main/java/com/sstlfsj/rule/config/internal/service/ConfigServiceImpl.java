package com.sstlfsj.rule.config.internal.service;

import com.sstlfsj.rule.config.api.service.ConfigService;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import org.springframework.stereotype.Service;

@Service
class ConfigServiceImpl implements ConfigService {

    @Override
    public RuleVersionSnapshot publish(String tenantId, Long ruleDefinitionId, String actorId) {
        throw new UnsupportedOperationException("publish not yet implemented");
    }

    @Override
    public void disable(String tenantId, Long ruleDefinitionId, String actorId) {
        throw new UnsupportedOperationException("disable not yet implemented");
    }
}
