package com.sstlfsj.rule.audit.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** audit_log 表只读映射。 */
@Getter
@Setter
@TableName("audit_log")
public class AuditLogRow {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String actor;
    private String actorType;
    private String action;
    private String targetType;
    private String targetId;
    private String beforeSnapshot;
    private String afterSnapshot;
    private LocalDateTime operatedAt;
}
