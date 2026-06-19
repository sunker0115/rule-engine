package com.sstlfsj.rule.job.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sstlfsj.rule.job.api.TaskStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * scheduled_task 表实体(去中心化:框架核视 config 为不透明原始 JSON,task_type 为开放 string;
 * 派发时由 registry 按 executor.configType() 反序列化成各 handler 的 typed config)。
 */
@Getter
@Setter
@TableName("scheduled_task")
public class ScheduledTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String code;
    private String name;
    /** 开放类型名(与 TaskExecutor.type() 对应,如 "TRIGGER" / "OUTCOME_INGESTION")。 */
    private String taskType;
    private String cron;
    /** 原始 JSON 配置(不透明 payload,各 handler 在派发时反序列化成自带 typed record)。 */
    private String config;
    /** 增量任务运行游标(与 config 分离的运行态);非增量任务为 null。run_cursor ↔ runCursor 由 mapUnderscoreToCamelCase 自动映射。 */
    private String runCursor;
    private TaskStatus status;
    private String createdBy;
    private LocalDateTime createdAt;
    private String updatedBy;
    private LocalDateTime updatedAt;
}
