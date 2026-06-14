package com.sstlfsj.rule.config.internal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

/** rule_definition 表 MyBatis-Plus Mapper。 */
@Mapper
public interface RuleDefinitionMapper extends BaseMapper<RuleDefinition> {

    /** 导出选取：ruleIds → sceneId → 整租户（条件重载，单条查询）。 */
    default List<RuleDefinition> selectForExport(Long tenantId, List<Long> ruleIds, Long sceneId) {
        boolean byIds = ruleIds != null && !ruleIds.isEmpty();
        return selectList(new LambdaQueryWrapper<RuleDefinition>()
                .eq(RuleDefinition::getTenantId, tenantId)
                .in(byIds, RuleDefinition::getId, ruleIds)
                .eq(!byIds && sceneId != null, RuleDefinition::getSceneId, sceneId));
    }

    /** 按 (tenantId, sceneId, code) 查规则定义，不存在返回 null。 */
    default RuleDefinition findBySceneAndCode(Long tenantId, Long sceneId, String code) {
        return selectOne(new LambdaQueryWrapper<RuleDefinition>()
                .eq(RuleDefinition::getTenantId, tenantId)
                .eq(RuleDefinition::getSceneId, sceneId)
                .eq(RuleDefinition::getCode, code));
    }

    /** 按租户查全部规则定义。 */
    default List<RuleDefinition> findByTenant(Long tenantId) {
        return selectList(new LambdaQueryWrapper<RuleDefinition>()
                .eq(RuleDefinition::getTenantId, tenantId));
    }

    /** 按 (tenantId) + 场景 id 集合查规则定义；空集合返回空列表。 */
    default List<RuleDefinition> findByTenantAndSceneIds(Long tenantId, Collection<Long> sceneIds) {
        if (sceneIds == null || sceneIds.isEmpty()) return List.of();
        return selectList(new LambdaQueryWrapper<RuleDefinition>()
                .eq(RuleDefinition::getTenantId, tenantId)
                .in(RuleDefinition::getSceneId, sceneIds));
    }

    /** 规则列表分页：按租户过滤，sceneId / status / from/to 非空时附加条件，按 id 倒序。 */
    default Page<RuleDefinition> selectRulePage(Page<RuleDefinition> page, Long tenantId,
                                                Long sceneId, String status,
                                                java.time.LocalDate from, java.time.LocalDate to) {
        return selectPage(page, new LambdaQueryWrapper<RuleDefinition>()
                .eq(RuleDefinition::getTenantId, tenantId)
                .eq(sceneId != null, RuleDefinition::getSceneId, sceneId)
                .eq(status != null && !status.isBlank(), RuleDefinition::getStatus, status)
                .ge(from != null, RuleDefinition::getPublishedAt, from != null ? from.atStartOfDay() : null)
                .le(to != null, RuleDefinition::getPublishedAt, to != null ? to.plusDays(1).atStartOfDay() : null)
                .orderByDesc(RuleDefinition::getId));
    }
}
