package com.sstlfsj.rule.job.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sstlfsj.rule.kernel.api.spi.task.TaskExecutionStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** scheduled_task_execution 表实体。 */
@Getter
@Setter
@TableName("scheduled_task_execution")
public class ScheduledTaskExecution {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long scheduledTaskId;
    private Long tenantId;
    private LocalDateTime triggerAt;
    private TaskExecutionStatus status;
    private Integer processedCount;
    private Integer successCount;
    private Integer errorCount;
    private String errorSummary;
    private LocalDateTime finishedAt;
}
