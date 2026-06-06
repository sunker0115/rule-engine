package com.sstlfsj.rule.audit.internal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sstlfsj.rule.audit.internal.domain.AuditLogRow;
import org.apache.ibatis.annotations.Mapper;

/** audit_log 只读 Mapper。 */
@Mapper
public interface AuditLogReadMapper extends BaseMapper<AuditLogRow> {

    /** 审计日志分页：按租户过滤，targetType / targetId 非空时附加条件，按操作时间倒序。 */
    default Page<AuditLogRow> selectAuditLogPage(Page<AuditLogRow> page, Long tenantId,
                                                 String targetType, Long targetId) {
        return selectPage(page, new LambdaQueryWrapper<AuditLogRow>()
                .eq(AuditLogRow::getTenantId, tenantId)
                .eq(targetType != null, AuditLogRow::getTargetType, targetType)
                .eq(targetId != null, AuditLogRow::getTargetId,
                        targetId == null ? null : String.valueOf(targetId))
                .orderByDesc(AuditLogRow::getOperatedAt));
    }
}
