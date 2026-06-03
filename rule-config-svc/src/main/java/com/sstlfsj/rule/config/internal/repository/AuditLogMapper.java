package com.sstlfsj.rule.config.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.config.internal.domain.AuditLog;
import org.apache.ibatis.annotations.Mapper;

/** audit_log 表 MyBatis-Plus Mapper。 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {}
