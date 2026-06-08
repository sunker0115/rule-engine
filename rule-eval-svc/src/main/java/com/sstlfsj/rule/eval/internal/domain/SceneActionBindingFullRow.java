package com.sstlfsj.rule.eval.internal.domain;

/** scene_action_binding 全量查询结果 DTO，携带分组键（tenant + sceneCode）供内存索引按场景归桶。 */
public record SceneActionBindingFullRow(Long tenantId, String sceneCode,
                                        String actionType, String defaultParamsJson) {}
