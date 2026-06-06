package com.sstlfsj.rule.config.internal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.config.internal.domain.DecisionDefinition;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

/** decision_definition 表 MyBatis-Plus Mapper。 */
@Mapper
public interface DecisionDefinitionMapper extends BaseMapper<DecisionDefinition> {

    /** 按 (tenantId, code) 查 decision，不存在返回 null。 */
    default DecisionDefinition findByCode(Long tenantId, String code) {
        return selectOne(new LambdaQueryWrapper<DecisionDefinition>()
                .eq(DecisionDefinition::getTenantId, tenantId)
                .eq(DecisionDefinition::getCode, code));
    }

    /** 按 code 集合批量查 decision；空集合返回空列表。 */
    default List<DecisionDefinition> findByCodes(Long tenantId, Collection<String> codes) {
        if (codes == null || codes.isEmpty()) return List.of();
        return selectList(new LambdaQueryWrapper<DecisionDefinition>()
                .eq(DecisionDefinition::getTenantId, tenantId)
                .in(DecisionDefinition::getCode, codes));
    }
}
