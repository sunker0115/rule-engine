package com.sstlfsj.rule.eval.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** evaluation_session 表对应的 MyBatis-Plus 实体（D11/D21 同步写）。 */
@TableName("evaluation_session")
public class EvaluationSession {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String eventId;
    private String sceneCode;
    private String eventType;
    private String subjectId;
    /** 评估触发来源：PUSH / PULL / REPLAY。 */
    private String source;
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

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getSceneCode() { return sceneCode; }
    public void setSceneCode(String sceneCode) { this.sceneCode = sceneCode; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getSubjectId() { return subjectId; }
    public void setSubjectId(String subjectId) { this.subjectId = subjectId; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getFinalDecision() { return finalDecision; }
    public void setFinalDecision(String finalDecision) { this.finalDecision = finalDecision; }
    public String getHitDecisions() { return hitDecisions; }
    public void setHitDecisions(String hitDecisions) { this.hitDecisions = hitDecisions; }
    public String getBlockedBy() { return blockedBy; }
    public void setBlockedBy(String blockedBy) { this.blockedBy = blockedBy; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public Integer getCandidateRuleCount() { return candidateRuleCount; }
    public void setCandidateRuleCount(Integer candidateRuleCount) { this.candidateRuleCount = candidateRuleCount; }
    public Integer getHitRuleCount() { return hitRuleCount; }
    public void setHitRuleCount(Integer hitRuleCount) { this.hitRuleCount = hitRuleCount; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime occurredAt) { this.occurredAt = occurredAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public Integer getEvalDurationMs() { return evalDurationMs; }
    public void setEvalDurationMs(Integer evalDurationMs) { this.evalDurationMs = evalDurationMs; }
}
