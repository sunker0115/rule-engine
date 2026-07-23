package com.sstlfsj.rule.config.api.service;

import com.sstlfsj.rule.config.api.dto.ImportDiffReport;
import com.sstlfsj.rule.config.api.dto.ImportPolicy;
import com.sstlfsj.rule.config.api.dto.RuleBundle;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;

import java.util.List;

/**
 * 规则批量导出 / 导入（Bundle v2）。
 *
 * <p>import 支持三种冲突策略（{@link ImportPolicy}）和 dry-run 模式：
 * dry-run=true 时返回 diff 报告但不落库；dry-run=false 时真实 apply。</p>
 */
public interface RuleBundleService {

    /**
     * 按条件批量导出规则的当前 ACTIVE 版本为自包含 Bundle v2（含 script / contentHash / revision）。
     *
     * @param tenantId 租户 id
     * @param ruleIds  规则定义 id 列表（可为 null / 空）
     * @param sceneId  场景 id（可为 null）
     * @return 多规则自包含 Bundle v2
     * @throws IllegalArgumentException 无可导出的 ACTIVE 规则
     */
    RuleBundle export(Long tenantId, List<Long> ruleIds, Long sceneId);

    /** 按条件导出规则当前 ACTIVE 版本为快照列表（SDK 本地调用用）。 */
    List<RuleVersionSnapshot> exportSnapshots(Long tenantId, List<Long> ruleIds, Long sceneId);

    /**
     * 导入 Bundle 到目标租户。
     *
     * <p>dry-run=true 时事务内完整执行后强制回滚，返回 diff 报告但不落库。
     * dry-run=false 时真实 apply，所有资源走完整 service 链（审计/事件/校验全部继承）。</p>
     *
     * @param tenantId 目标租户 id
     * @param bundle   Bundle v2
     * @param policy   冲突处理策略（null 默认 SKIP）
     * @param dryRun   true = dry-run，false = apply
     * @param actorId  操作人（来自 X-Actor-Id）
     * @return diff 报告（dry-run 和 apply 均返回）
     * @throws com.sstlfsj.rule.config.internal.bundle.RuleImportService.ImportConflictException ABORT 策略有冲突时
     */
    ImportDiffReport importBundle(Long tenantId, RuleBundle bundle,
                                  ImportPolicy policy, boolean dryRun, String actorId);
}
