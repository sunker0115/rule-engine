package com.sstlfsj.rule.config.internal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.config.internal.domain.RuleTemplate;
import com.sstlfsj.rule.config.internal.domain.RuleTemplateStatus;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** rule_template 表 MyBatis-Plus Mapper。 */
@Mapper
public interface RuleTemplateMapper extends BaseMapper<RuleTemplate> {

    /** 按 (tenantId, code) 查唯一模板，不存在返回 null。 */
    default RuleTemplate findByTenantAndCode(Long tenantId, String code) {
        return selectOne(new LambdaQueryWrapper<RuleTemplate>()
                .eq(RuleTemplate::getTenantId, tenantId)
                .eq(RuleTemplate::getCode, code));
    }

    /** 按 (tenantId, code) 查 PUBLISHED 状态的模板，供实例化入口。不存在返回 null。 */
    default RuleTemplate findPublishedByCode(Long tenantId, String code) {
        return selectOne(new LambdaQueryWrapper<RuleTemplate>()
                .eq(RuleTemplate::getTenantId, tenantId)
                .eq(RuleTemplate::getCode, code)
                .eq(RuleTemplate::getStatus, RuleTemplateStatus.PUBLISHED));
    }

    /** 按租户查全部模板。 */
    default List<RuleTemplate> findByTenantId(Long tenantId) {
        return selectList(new LambdaQueryWrapper<RuleTemplate>()
                .eq(RuleTemplate::getTenantId, tenantId));
    }

    /** 按租户 + 状态查模板列表。 */
    default List<RuleTemplate> findByTenantId(Long tenantId, RuleTemplateStatus status) {
        return selectList(new LambdaQueryWrapper<RuleTemplate>()
                .eq(RuleTemplate::getTenantId, tenantId)
                .eq(status != null, RuleTemplate::getStatus, status));
    }
}
