package com.sstlfsj.rule.config.internal.event;

import com.sstlfsj.rule.config.internal.domain.ActorType;
import com.sstlfsj.rule.config.internal.domain.AuditAction;
import com.sstlfsj.rule.config.internal.domain.AuditTargetType;

import java.time.LocalDateTime;

/**
 * 配置操作审计事件（B 类，同模块强一致副作用）：一次配置变更（人的行为）的事实，
 * 由各 service 在业务写同事务内发布，集中监听器 BEFORE_COMMIT 落 audit_log（D14 同事务红线）。
 *
 * @param tenantId       租户 id
 * @param actor          操作人（来自请求头 X-Actor-Id）
 * @param actorType      操作方类型（USER / SYSTEM / JOB，当前恒为 USER）
 * @param action         操作类型（CREATE / UPDATE / PUBLISH / ENABLE / DISABLE / DELETE / IMPORT）
 * @param targetType     变更对象类型（rule_definition / scene / metric_definition / decision_definition 等）
 * @param targetId       变更对象 id
 * @param beforeSnapshot 变更前快照（typed，由监听器序列化为 JSON 落库），可空
 * @param afterSnapshot  变更后快照（typed，由监听器序列化为 JSON 落库），可空
 * @param operatedAt     操作发生时间
 */
public record OperationAuditedEvent(
        Long tenantId,
        String actor,
        ActorType actorType,
        AuditAction action,
        AuditTargetType targetType,
        String targetId,
        AuditSnapshot beforeSnapshot,
        AuditSnapshot afterSnapshot,
        LocalDateTime operatedAt
) {}
