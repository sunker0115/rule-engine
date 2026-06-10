package com.sstlfsj.rule.config.internal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.config.internal.domain.RuleVersion;
import com.sstlfsj.rule.config.internal.domain.RuleVersionStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

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
                .eq(RuleVersion::getStatus, RuleVersionStatus.ACTIVE)
                .orderByDesc(RuleVersion::getVersion)
                .last("LIMIT 1"));
    }

    /** 查指定规则最新草稿版本（status=DRAFT 的最高版本号），不存在返回 null。 */
    default RuleVersion findLatestDraft(Long ruleDefinitionId) {
        return selectOne(new LambdaQueryWrapper<RuleVersion>()
                .eq(RuleVersion::getRuleDefinitionId, ruleDefinitionId)
                .eq(RuleVersion::getStatus, RuleVersionStatus.DRAFT)
                .orderByDesc(RuleVersion::getVersion)
                .last("LIMIT 1"));
    }

    /**
     * 按 rule_definition id 集合查 ACTIVE 版本，仅取影响面判断所需列
     * （id / ruleDefinitionId / metricDependencies），避免拉取 condition_ast 等大字段。
     * 空集合返回空列表。
     */
    default List<RuleVersion> findActiveByRuleDefIds(Collection<Long> ruleDefinitionIds) {
        if (ruleDefinitionIds == null || ruleDefinitionIds.isEmpty()) return List.of();
        return selectList(new LambdaQueryWrapper<RuleVersion>()
                .in(RuleVersion::getRuleDefinitionId, ruleDefinitionIds)
                .eq(RuleVersion::getStatus, RuleVersionStatus.ACTIVE)
                .select(RuleVersion::getId, RuleVersion::getRuleDefinitionId,
                        RuleVersion::getMetricDependencies));
    }

    /**
     * 按 rule_definition id 集合查 ACTIVE 版本，取输入清单所需列
     * （ruleDefinitionId / payloadDependencies / triggerEventTypes），用于 input-manifest 发现。
     * 空集合返回空列表。
     */
    default List<RuleVersion> findActiveWithPayloadByRuleDefIds(Collection<Long> ruleDefinitionIds) {
        if (ruleDefinitionIds == null || ruleDefinitionIds.isEmpty()) return List.of();
        return selectList(new LambdaQueryWrapper<RuleVersion>()
                .in(RuleVersion::getRuleDefinitionId, ruleDefinitionIds)
                .eq(RuleVersion::getStatus, RuleVersionStatus.ACTIVE)
                .select(RuleVersion::getRuleDefinitionId,
                        RuleVersion::getPayloadDependencies, RuleVersion::getTriggerEventTypes));
    }

    /** 将指定 ACTIVE 版本置为 SUPERSEDED；返回受影响行数。 */
    default int markSuperseded(Long ruleVersionId) {
        return update(null, new LambdaUpdateWrapper<RuleVersion>()
                .eq(RuleVersion::getId, ruleVersionId)
                .eq(RuleVersion::getStatus, RuleVersionStatus.ACTIVE)
                .set(RuleVersion::getStatus, RuleVersionStatus.SUPERSEDED));
    }
}
