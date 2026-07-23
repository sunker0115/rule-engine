package com.sstlfsj.rule.config.internal.lineage;

import com.sstlfsj.rule.config.api.service.RuleLineageService;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleVersion;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleVersionMapper;
import com.sstlfsj.rule.kernel.api.model.FlowBody;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 反向血缘实现：遍历 tenant 下全部 ACTIVE DECISION_FLOW 规则的冻结快照，
 * 查其 {@link FlowBody#referencedSnapshots()} 是否含目标 ruleCode。
 */
@Service
@RequiredArgsConstructor
public class RuleLineageServiceImpl implements RuleLineageService {

    private final RuleDefinitionMapper ruleDefinitionMapper;
    private final RuleVersionMapper ruleVersionMapper;

    @Override
    @Transactional(readOnly = true)
    public List<ReferencingFlowItem> findFlowsReferencingRule(Long tenantId, String ruleCode) {
        List<RuleDefinition> flowDefs = ruleDefinitionMapper.findByTenantAndKind(
                tenantId, RuleKind.DECISION_FLOW);
        List<ReferencingFlowItem> result = new ArrayList<>();
        for (RuleDefinition rd : flowDefs) {
            RuleVersion active = ruleVersionMapper.findActiveVersion(rd.getId());
            if (active == null) continue;
            // 引用关系在发布期已冻入 FlowBody.referencedSnapshots（ruleCode → snapshot），直读判断
            if (active.getBody() instanceof FlowBody fb && fb.referencedSnapshots().containsKey(ruleCode)) {
                result.add(new ReferencingFlowItem(rd.getId(), rd.getCode(), rd.getSceneCode()));
            }
        }
        return result;
    }
}
