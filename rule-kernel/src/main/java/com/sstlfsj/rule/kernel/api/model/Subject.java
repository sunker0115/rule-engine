package com.sstlfsj.rule.kernel.api.model;

import java.util.Map;

/** 规则评估主体，持有主体 id、类型及属性快照。 */
public record Subject(
        String subjectId,
        SubjectType subjectType,
        Map<String, Object> attributes
) {
    public Subject {
        attributes = Map.copyOf(attributes);
    }

    public Object getAttribute(String key) {
        return attributes.get(key);
    }
}
