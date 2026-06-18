package com.sstlfsj.rule.config.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** audit_log 表实体，D14：配置变更审计，同步事务写，永久保留。 */
@Getter
@Setter
@TableName("audit_log")
public class AuditLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String actor;
    private ActorType actorType;
    private AuditAction action;
    private AuditTargetType targetType;
    private String targetId;
    private String beforeSnapshot;
    private String afterSnapshot;
    private String traceId;
    private java.time.LocalDateTime operatedAt;
}
