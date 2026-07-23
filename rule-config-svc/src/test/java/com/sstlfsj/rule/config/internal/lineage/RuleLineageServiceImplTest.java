package com.sstlfsj.rule.config.internal.lineage;

import com.sstlfsj.rule.config.api.service.RuleLineageService.ReferencingFlowItem;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleVersion;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleVersionMapper;
import com.sstlfsj.rule.kernel.api.model.FlowBody;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.flow.FlowGraph;
import com.sstlfsj.rule.kernel.api.model.flow.OutputNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuleLineageServiceImplTest {

    @Mock RuleDefinitionMapper ruleDefinitionMapper;
    @Mock RuleVersionMapper ruleVersionMapper;
    @InjectMocks RuleLineageServiceImpl sut;

    private RuleVersion flowVersionReferencing(String... referencedCodes) {
        RuleVersion rv = new RuleVersion();
        rv.setId(100L);
        var refs = new java.util.HashMap<String, RuleVersionSnapshot>();
        for (String code : referencedCodes) {
            refs.put(code, RuleVersionSnapshot.builder()
                    .ruleVersionId(200L).sceneCode("risk.base").tenantId("1")
                    .conditionAst(new AndNode(List.of(), null, null))
                    .build());
        }
        rv.setBody(new FlowBody(
                new FlowGraph(List.of(new OutputNode("out", "PASS")), List.of(), "out"), refs));
        return rv;
    }

    @Test
    void findFlowsReferencingRule_referencingFlowsReturned() {
        RuleDefinition flow = new RuleDefinition();
        flow.setId(1L);
        flow.setCode("flow.transfer");
        flow.setSceneCode("risk.transfer");
        flow.setKind(RuleKind.DECISION_FLOW);
        when(ruleDefinitionMapper.findByTenantAndKind(1L, RuleKind.DECISION_FLOW))
                .thenReturn(List.of(flow));
        when(ruleVersionMapper.findActiveVersion(1L))
                .thenReturn(flowVersionReferencing("base-check"));

        List<ReferencingFlowItem> result = sut.findFlowsReferencingRule(1L, "base-check");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).ruleDefinitionId()).isEqualTo(1L);
        assertThat(result.get(0).ruleCode()).isEqualTo("flow.transfer");
        // 反向血缘返回 flow 自身所属 Scene（可能与被引规则跨 Scene）
        assertThat(result.get(0).sceneCode()).isEqualTo("risk.transfer");
    }

    @Test
    void findFlowsReferencingRule_flowReferencesOtherRule_notMatched() {
        RuleDefinition flow = new RuleDefinition();
        flow.setId(1L);
        flow.setCode("flow.transfer");
        flow.setSceneCode("risk.transfer");
        flow.setKind(RuleKind.DECISION_FLOW);
        when(ruleDefinitionMapper.findByTenantAndKind(1L, RuleKind.DECISION_FLOW))
                .thenReturn(List.of(flow));
        when(ruleVersionMapper.findActiveVersion(1L))
                .thenReturn(flowVersionReferencing("some-other-rule"));

        assertThat(sut.findFlowsReferencingRule(1L, "base-check")).isEmpty();
    }

    @Test
    void findFlowsReferencingRule_noFlows_returnsEmpty() {
        when(ruleDefinitionMapper.findByTenantAndKind(1L, RuleKind.DECISION_FLOW))
                .thenReturn(List.of());

        assertThat(sut.findFlowsReferencingRule(1L, "base-check")).isEmpty();
    }
}
