package com.sstlfsj.rule.job.api;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 调度任务的主体查询配置判别联合（typed，存于 scheduled_task.config 的 TriggerConfig.subjectQuery）。
 * 多态注解打在接口上，与 AstNode 同风格，全局 ObjectMapper 与 codec 均可解析。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(@JsonSubTypes.Type(value = BeanMethodQuery.class, name = "BEAN_METHOD"))
public sealed interface SubjectQuery permits BeanMethodQuery {}
