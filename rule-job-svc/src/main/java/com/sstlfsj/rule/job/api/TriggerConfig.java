package com.sstlfsj.rule.job.api;

/**
 * TRIGGER 任务配置:取主体 → 合成 RuleEvent → 评估。
 *
 * @param sceneCode    绑定场景(仅 PUSH/HYBRID)
 * @param eventType    合成 RuleEvent 的 eventType
 * @param subjectQuery 主体查询(如 BeanMethodQuery)
 */
public record TriggerConfig(String sceneCode, String eventType, SubjectQuery subjectQuery) {}
