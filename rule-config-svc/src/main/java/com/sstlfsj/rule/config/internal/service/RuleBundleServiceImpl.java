package com.sstlfsj.rule.config.internal.service;

import com.sstlfsj.rule.config.api.dto.RuleBundle;
import com.sstlfsj.rule.config.api.dto.RuleImportResult;
import com.sstlfsj.rule.config.api.service.RuleBundleService;
import com.sstlfsj.rule.config.internal.bundle.RuleExportService;
import com.sstlfsj.rule.config.internal.bundle.RuleImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** RuleBundleService 实现，委托 RuleExportService / RuleImportService。 */
@Service
@RequiredArgsConstructor
class RuleBundleServiceImpl implements RuleBundleService {

    private final RuleExportService ruleExportService;
    private final RuleImportService ruleImportService;

    @Override
    public RuleBundle export(String tenantId, List<Long> ruleIds, Long sceneId) {
        return ruleExportService.export(tenantId, ruleIds, sceneId);
    }

    @Override
    public RuleImportResult importBundle(String tenantId, RuleBundle bundle, String actorId) {
        return ruleImportService.importBundle(tenantId, bundle, actorId);
    }
}
