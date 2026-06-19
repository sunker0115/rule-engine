package com.sstlfsj.rule.job.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;
import com.sstlfsj.rule.job.api.TaskConfig;
import com.sstlfsj.rule.job.api.TaskStatus;
import com.sstlfsj.rule.job.api.TaskType;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** scheduled_task 表实体(typed config 列,JSON↔对象由 TypeHandler 持久层完成)。 */
@Getter
@Setter
@TableName(value = "scheduled_task", autoResultMap = true)
public class ScheduledTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String code;
    private String name;
    private TaskType taskType;
    private String cron;
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private TaskConfig config;
    private TaskStatus status;
    private String createdBy;
    private LocalDateTime createdAt;
    private String updatedBy;
    private LocalDateTime updatedAt;
}
