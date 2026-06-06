package com.sstlfsj.rule.config.internal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.config.internal.domain.RuleVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** rule_version 表 MyBatis-Plus Mapper。 */
@Mapper
public interface RuleVersionMapper extends BaseMapper<RuleVersion> {

    /**
     * 查询指定规则下的最大版本号，用于发布时单调递增。
     *
     * @param ruleDefinitionId 规则定义 id
     * @return 当前最大版本号，无记录时返回 0
     */
    @Select("SELECT COALESCE(MAX(version), 0) FROM rule_version WHERE rule_definition_id = #{ruleDefinitionId}")
    Long maxVersion(Long ruleDefinitionId);

    /** 查规则当前 ACTIVE 版本（最高版本号的 ACTIVE 行），不存在返回 null。 */
    default RuleVersion findActiveVersion(Long ruleDefinitionId) {
        return selectOne(new LambdaQueryWrapper<RuleVersion>()
                .eq(RuleVersion::getRuleDefinitionId, ruleDefinitionId)
                .eq(RuleVersion::getStatus, "ACTIVE")
                .orderByDesc(RuleVersion::getVersion)
                .last("LIMIT 1"));
    }
}
