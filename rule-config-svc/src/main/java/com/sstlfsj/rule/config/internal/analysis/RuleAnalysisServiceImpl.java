package com.sstlfsj.rule.config.internal.analysis;

import com.sstlfsj.rule.config.api.service.RuleAnalysisService;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleVersion;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleVersionMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import com.sstlfsj.rule.kernel.api.analysis.RuleSetAnalysisReport;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.SceneExecutionStrategy;
import com.sstlfsj.rule.kernel.internal.analysis.AnalyzableRule;
import com.sstlfsj.rule.kernel.internal.analysis.RuleSetAnalyzer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 规则集静态分析编排实现:解析场景 → 取其全部 ACTIVE 规则版本快照 →
 * 拆成 kernel 轻量输入 {@link AnalyzableRule} → 调 {@link RuleSetAnalyzer} 返回报告。
 *
 * <p>读路径与 {@code RuleExportService} 一致:findByCode → findByTenantAndSceneIds → findActiveVersion;
 * 无 ACTIVE 版本的规则定义跳过。decision_strategy 同名映射到 kernel 的 {@link SceneExecutionStrategy},
 * 为空时回落 HIGHEST_PRIORITY。</p>
 */
@Service
@RequiredArgsConstructor
public class RuleAnalysisServiceImpl implements RuleAnalysisService {

    private final SceneMapper sceneMapper;
    private final RuleDefinitionMapper ruleDefinitionMapper;
    private final RuleVersionMapper ruleVersionMapper;

    @Override
    @Transactional(readOnly = true)
    public RuleSetAnalysisReport analyze(Long tenantId, String sceneCode) {
        SceneDef scene = sceneMapper.findByCode(tenantId, sceneCode);
        if (scene == null) {
            throw new IllegalArgumentException("Scene 不存在: code=" + sceneCode);
        }

        SceneExecutionStrategy strategy = mapStrategy(scene);

        List<RuleDefinition> ruleDefs = ruleDefinitionMapper.findByTenantAndSceneIds(tenantId, List.of(scene.getId()));
        List<AnalyzableRule> analyzableRules = new ArrayList<>();
        for (RuleDefinition rd : ruleDefs) {
            RuleVersion active = ruleVersionMapper.findActiveVersion(rd.getId());
            if (active == null) continue;
            String kind = (active.getKind() != null ? active.getKind() : RuleKind.AST_BOOLEAN).name();
            analyzableRules.add(new AnalyzableRule(
                    rd.getCode(), active.getVersion(), active.getConditionAst(),
                    active.getDecisionBindings(), kind));
        }

        return RuleSetAnalyzer.analyze(sceneCode, analyzableRules, strategy);
    }

    /** scene.decision_strategy 同名映射到 kernel 执行策略;为空回落 HIGHEST_PRIORITY。 */
    private SceneExecutionStrategy mapStrategy(SceneDef scene) {
        if (scene.getDecisionStrategy() == null) {
            return SceneExecutionStrategy.HIGHEST_PRIORITY;
        }
        return SceneExecutionStrategy.valueOf(scene.getDecisionStrategy().name());
    }
}
