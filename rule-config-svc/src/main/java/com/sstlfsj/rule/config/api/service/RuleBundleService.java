package com.sstlfsj.rule.config.api.service;

import com.sstlfsj.rule.config.api.dto.RuleBundle;
import com.sstlfsj.rule.config.api.dto.RuleImportResult;

import java.util.List;

/** 规则批量导出 / 导入（B7 / 08-evolution §2.9）。 */
public interface RuleBundleService {

    /**
     * 按条件批量导出规则的当前 ACTIVE 版本为自包含 Bundle。
     * <p>选取优先级：ruleIds 非空 → 按 id 列表；否则 sceneId 非空 → 该场景全部；否则 → 该租户全部。
     * 对每条规则仅导当前 ACTIVE 版本，无 ACTIVE 版本者跳过；最终无可导出规则时报错。</p>
     *
     * @param tenantId 租户 id
     * @param ruleIds  规则定义 id 列表（可为 null / 空）
     * @param sceneId  场景 id（可为 null）
     * @return 多规则自包含 Bundle
     * @throws IllegalArgumentException 无可导出的 ACTIVE 规则
     */
    RuleBundle export(String tenantId, List<Long> ruleIds, Long sceneId);

    /**
     * 幂等导入 Bundle 到目标租户：整体 upsert 依赖（Scene / metric / decision 缺失则建），
     * 逐条把规则落为 DRAFT 版本（已存在则追加草稿版本，不覆盖已发布版本）。
     *
     * @param tenantId 目标租户 id
     * @param bundle   导入 Bundle
     * @param actorId  操作人（来自 X-Actor-Id）
     * @return 导入结果汇总
     * @throws IllegalArgumentException Bundle 结构非法
     */
    RuleImportResult importBundle(String tenantId, RuleBundle bundle, String actorId);
}
