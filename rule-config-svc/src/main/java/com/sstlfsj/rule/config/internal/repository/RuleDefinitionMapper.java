package com.sstlfsj.rule.config.internal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import org.apache.ibatis.annotations.Mapper;

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
}
