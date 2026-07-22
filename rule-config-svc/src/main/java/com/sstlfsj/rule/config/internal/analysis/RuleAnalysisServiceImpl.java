package com.sstlfsj.rule.config.internal.analysis;

import com.sstlfsj.rule.config.api.service.RuleAnalysisService;
import com.sstlfsj.rule.config.internal.domain.DecisionStrategy;
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
 * 规则集静态分析编排实现:解析场景 → 取其全部规则的待分析版本快照 →
 * 拆成 kernel 轻量输入 {@link AnalyzableRule} → 调 {@link RuleSetAnalyzer} 返回报告。
 *
 * <p>版本选取「草稿优先,否则 ACTIVE」:每个规则定义优先取其 DRAFT 版本(发布生命周期保证至多一个),
 * 无 DRAFT 时回落 ACTIVE,两者皆无则跳过该定义。语义是反映场景的待发布编辑态
 * ——「当前所有草稿一旦发布后场景将是什么样」,供发布前自检。</p>
 *
 * <p>decision_strategy 同名映射到 kernel 的 {@link SceneExecutionStrategy},为空时回落 HIGHEST_PRIORITY。</p>
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
            // 草稿优先,否则 ACTIVE:反映待发布编辑态(发布生命周期保证至多一个 DRAFT)
            RuleVersion draft = ruleVersionMapper.findLatestDraft(rd.getId());
            RuleVersion version = (draft != null) ? draft : ruleVersionMapper.findActiveVersion(rd.getId());
            if (version == null) continue;
            String kind = (version.getKind() != null ? version.getKind() : RuleKind.AST_BOOLEAN).name();
            // flowGraph 仅 DECISION_FLOW 非空,供 kernel 环/可达性分析;其余 kind 为 null
            analyzableRules.add(new AnalyzableRule(
                    rd.getCode(), version.getVersion(), version.getConditionAst(),
                    version.getDecisionBindings(), kind, version.getFlowGraph()));
        }

        return RuleSetAnalyzer.analyze(sceneCode, analyzableRules, strategy);
    }

    /** scene.decision_strategy 映射到 kernel 执行策略;为空回落 HIGHEST_PRIORITY。穷尽 switch 让加值时编译期报缺 case。 */
    private SceneExecutionStrategy mapStrategy(SceneDef scene) {
        DecisionStrategy s = scene.getDecisionStrategy();
        if (s == null) {
            return SceneExecutionStrategy.HIGHEST_PRIORITY;
        }
        return switch (s) {
            case HIGHEST_PRIORITY -> SceneExecutionStrategy.HIGHEST_PRIORITY;
            case ALL_HITS -> SceneExecutionStrategy.ALL_HITS;
            case FIRST_HIT -> SceneExecutionStrategy.FIRST_HIT;
        };
    }
}
