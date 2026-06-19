package com.sstlfsj.rule.job.api;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 调度任务的类型化配置载体。多态由 kind 判别自描述(JSON↔子类型)。
 * track #2 接 OUTCOME_INGESTION 时在 permits + @JsonSubTypes 加 OutcomeIngestionConfig。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({ @JsonSubTypes.Type(value = TriggerConfig.class, name = "TRIGGER") })
public sealed interface TaskConfig permits TriggerConfig {
    /** 对应的任务类型,供 dispatcher 校验 config 与 task_type 一致。 */
    TaskType type();
}
