package com.sstlfsj.rule.audit.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.audit.internal.domain.AuditLogRow;
import org.apache.ibatis.annotations.Mapper;

/** audit_log 只读 Mapper。 */
@Mapper
public interface AuditLogReadMapper extends BaseMapper<AuditLogRow> {}
