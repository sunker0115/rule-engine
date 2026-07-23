package com.sstlfsj.rule.config.internal.service;

import com.sstlfsj.rule.config.api.dto.ImportDiffReport;
import com.sstlfsj.rule.config.api.dto.ImportPolicy;
import com.sstlfsj.rule.config.api.dto.RuleBundle;
import com.sstlfsj.rule.config.api.service.RuleBundleService;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
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
    public RuleBundle export(Long tenantId, List<Long> ruleIds, Long sceneId) {
        return ruleExportService.export(tenantId, ruleIds, sceneId);
    }

    @Override
    public List<RuleVersionSnapshot> exportSnapshots(Long tenantId, List<Long> ruleIds, Long sceneId) {
        return ruleExportService.exportSnapshots(tenantId, ruleIds, sceneId);
    }

    @Override
    public ImportDiffReport importBundle(Long tenantId, RuleBundle bundle,
                                         ImportPolicy policy, boolean dryRun, String actorId) {
        if (dryRun) {
            return ruleImportService.dryRun(tenantId, bundle, policy, actorId);
        }
        return ruleImportService.apply(tenantId, bundle, policy, actorId);
    }
}
