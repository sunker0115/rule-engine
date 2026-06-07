package com.sstlfsj.rule.eval.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** evaluation_session 表对应的 MyBatis-Plus 实体（D11/D21 同步写）。 */
@Getter
@Setter
@TableName("evaluation_session")
public class EvaluationSession {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String eventId;
    private String sceneCode;
    private String eventType;
    private String subjectId;
    /** 事件渠道：HTTP / MQ / JOB / SDK / REPLAY（取自 RuleEvent.source）。 */
    private String source;
    /** 评估模式：PUSH（异步）/ PULL（同步），由 EvalService 入口判定。 */
    private String mode;
    /** 状态：PENDING / HIT / MISS / BLOCKED / ERROR / FAILED。 */
    private String status;
    private String finalDecision;
    private String hitDecisions;
    private String blockedBy;
    private String errorCode;
    private Integer candidateRuleCount;
    private Integer hitRuleCount;
    private LocalDateTime occurredAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Integer evalDurationMs;
    /** EvalContext metrics 取数快照（JSON 文本），构建失败时为 null。 */
    private String contextSnapshot;
}
