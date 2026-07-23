package com.sstlfsj.rule.config.internal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** rule_definition 表 MyBatis-Plus Mapper。 */
@Mapper
public interface RuleDefinitionMapper extends BaseMapper<RuleDefinition> {

    /** 导出选取：ruleIds → sceneCode → 整租户（条件重载，单条查询）。 */
    default List<RuleDefinition> selectForExport(Long tenantId, List<Long> ruleIds, String sceneCode) {
        boolean byIds = ruleIds != null && !ruleIds.isEmpty();
        return selectList(new LambdaQueryWrapper<RuleDefinition>()
                .eq(RuleDefinition::getTenantId, tenantId)
                .in(byIds, RuleDefinition::getId, ruleIds)
                .eq(!byIds && sceneCode != null, RuleDefinition::getSceneCode, sceneCode));
    }

    /** 按 (tenantId, code) 查规则定义，tenant 内唯一。不存在返回 null。 */
    default RuleDefinition findByTenantAndCode(Long tenantId, String code) {
        return selectOne(new LambdaQueryWrapper<RuleDefinition>()
                .eq(RuleDefinition::getTenantId, tenantId)
                .eq(RuleDefinition::getCode, code));
    }

    /** 按 (tenantId, sceneCode) 查全部规则定义。 */
    default List<RuleDefinition> findByTenantAndSceneCode(Long tenantId, String sceneCode) {
        return selectList(new LambdaQueryWrapper<RuleDefinition>()
                .eq(RuleDefinition::getTenantId, tenantId)
                .eq(RuleDefinition::getSceneCode, sceneCode));
    }

    /** 按 (tenantId, kind) 查规则定义列表，供反向血缘遍历 DECISION_FLOW 用。 */
    default List<RuleDefinition> findByTenantAndKind(Long tenantId, RuleKind kind) {
        return selectList(new LambdaQueryWrapper<RuleDefinition>()
                .eq(RuleDefinition::getTenantId, tenantId)
                .eq(RuleDefinition::getKind, kind));
    }

    /** 按租户查全部规则定义。 */
    default List<RuleDefinition> findByTenant(Long tenantId) {
        return selectList(new LambdaQueryWrapper<RuleDefinition>()
                .eq(RuleDefinition::getTenantId, tenantId));
    }

    /** 规则列表分页：按租户过滤，sceneCode / status / from/to 非空时附加条件，按 id 倒序。 */
    default Page<RuleDefinition> selectRulePage(Page<RuleDefinition> page, Long tenantId,
                                                String sceneCode, String status,
                                                java.time.LocalDate from, java.time.LocalDate to) {
        return selectPage(page, new LambdaQueryWrapper<RuleDefinition>()
                .eq(RuleDefinition::getTenantId, tenantId)
                .eq(sceneCode != null && !sceneCode.isBlank(), RuleDefinition::getSceneCode, sceneCode)
                .eq(status != null && !status.isBlank(), RuleDefinition::getStatus, status)
                .ge(from != null, RuleDefinition::getPublishedAt, from != null ? from.atStartOfDay() : null)
                .le(to != null, RuleDefinition::getPublishedAt, to != null ? to.plusDays(1).atStartOfDay() : null)
                .orderByDesc(RuleDefinition::getId));
    }
}
