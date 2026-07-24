package com.sstlfsj.rule.config.internal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.config.internal.domain.RuleTemplate;
import com.sstlfsj.rule.config.internal.domain.TemplateStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

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
                .eq(RuleTemplate::getStatus, TemplateStatus.PUBLISHED));
    }

    /** 按租户查全部模板。 */
    default List<RuleTemplate> findByTenantId(Long tenantId) {
        return selectList(new LambdaQueryWrapper<RuleTemplate>()
                .eq(RuleTemplate::getTenantId, tenantId));
    }

    /** 按租户 + 状态查模板列表。 */
    default List<RuleTemplate> findByTenantId(Long tenantId, TemplateStatus status) {
        return selectList(new LambdaQueryWrapper<RuleTemplate>()
                .eq(RuleTemplate::getTenantId, tenantId)
                .eq(status != null, RuleTemplate::getStatus, status));
    }

    /**
     * 按租户查可见模板：STANDARD 租户可见 SYSTEM 模板 + 自身模板。
     * 通过 JOIN tenant.type 判断可见性。
     */
    @Select("""
            SELECT rt.* FROM rule_template rt
            INNER JOIN tenant t ON rt.tenant_id = t.id
            WHERE (t.type = 'SYSTEM' OR rt.tenant_id = #{tenantId})
            ORDER BY rt.id
            """)
    List<RuleTemplate> findVisibleByTenant(Long tenantId);

    /**
     * 按租户 + code 查可见模板：STANDARD 租户可见 SYSTEM 模板 + 自身模板。
     */
    @Select("""
            SELECT rt.* FROM rule_template rt
            INNER JOIN tenant t ON rt.tenant_id = t.id
            WHERE rt.code = #{code}
              AND (t.type = 'SYSTEM' OR rt.tenant_id = #{tenantId})
            """)
    RuleTemplate findVisibleByCode(Long tenantId, String code);
}
