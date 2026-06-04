package com.sstlfsj.rule.eval.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** action_execution 表实体：记录每次 ActionHandler 执行结果。 */
@TableName("action_execution")
public class ActionExecutionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long evaluationSessionId;
    private Long tenantId;
    private String eventId;
    private String actionId;
    private String actionType;
    private String decisionCode;
    private String status;
    private String errorCode;
    private Boolean retryable;
    private Integer retryCount;
    private LocalDateTime executedAt;
    private Boolean compensated;
    private LocalDateTime compensatedAt;
    private String compensatedBy;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getEvaluationSessionId() { return evaluationSessionId; }
    public void setEvaluationSessionId(Long evaluationSessionId) { this.evaluationSessionId = evaluationSessionId; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public String getActionId() { return actionId; }
    public void setActionId(String actionId) { this.actionId = actionId; }

    public String getActionType() { return actionType; }
    public void setActionType(String actionType) { this.actionType = actionType; }

    public String getDecisionCode() { return decisionCode; }
    public void setDecisionCode(String decisionCode) { this.decisionCode = decisionCode; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }

    public Boolean getRetryable() { return retryable; }
    public void setRetryable(Boolean retryable) { this.retryable = retryable; }

    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }

    public LocalDateTime getExecutedAt() { return executedAt; }
    public void setExecutedAt(LocalDateTime executedAt) { this.executedAt = executedAt; }

    public Boolean getCompensated() { return compensated; }
    public void setCompensated(Boolean compensated) { this.compensated = compensated; }

    public LocalDateTime getCompensatedAt() { return compensatedAt; }
    public void setCompensatedAt(LocalDateTime compensatedAt) { this.compensatedAt = compensatedAt; }

    public String getCompensatedBy() { return compensatedBy; }
    public void setCompensatedBy(String compensatedBy) { this.compensatedBy = compensatedBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
