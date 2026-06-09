package com.sstlfsj.rule.eval.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sstlfsj.rule.kernel.api.model.ActionResult;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** action_execution 表实体：记录每次 ActionHandler 执行结果。 */
@Getter
@Setter
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
    private ActionResult.ActionStatus status;
    private String errorCode;
    private Boolean retryable;
    private Integer retryCount;
    private LocalDateTime executedAt;
    private Boolean compensated;
    private LocalDateTime compensatedAt;
    private String compensatedBy;
    private LocalDateTime createdAt;
}
