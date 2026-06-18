package com.sstlfsj.rule.audit.internal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sstlfsj.rule.audit.internal.domain.AuditLogRow;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** audit_log 只读 Mapper。 */
@Mapper
public interface AuditLogReadMapper extends BaseMapper<AuditLogRow> {

    /** 审计日志分页：支持 resourceType / resourceId / action / actorId / 时间范围筛选。 */
    default Page<AuditLogRow> selectAuditLogPage(Page<AuditLogRow> page, Long tenantId,
                                                 String targetType, Long targetId,
                                                 String action, String actorId,
                                                 String from, String to) {
        LambdaQueryWrapper<AuditLogRow> qw = new LambdaQueryWrapper<AuditLogRow>()
                .eq(AuditLogRow::getTenantId, tenantId)
                .eq(targetType != null, AuditLogRow::getTargetType, targetType)
                .eq(targetId != null, AuditLogRow::getTargetId,
                        targetId == null ? null : String.valueOf(targetId))
                .eq(action != null, AuditLogRow::getAction, action)
                .eq(actorId != null, AuditLogRow::getActor, actorId);

        if (from != null && !from.isBlank()) {
            qw.ge(AuditLogRow::getOperatedAt, LocalDateTime.of(LocalDate.parse(from), LocalTime.MIN));
        }
        if (to != null && !to.isBlank()) {
            qw.le(AuditLogRow::getOperatedAt, LocalDateTime.of(LocalDate.parse(to), LocalTime.MAX));
        }

        return selectPage(page, qw.orderByDesc(AuditLogRow::getOperatedAt));
    }
}
