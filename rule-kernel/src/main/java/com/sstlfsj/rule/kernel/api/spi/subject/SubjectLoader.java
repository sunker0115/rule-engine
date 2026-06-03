package com.sstlfsj.rule.kernel.api.spi.subject;

import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.Subject;
import com.sstlfsj.rule.kernel.api.model.SubjectType;

import java.util.List;

/** 为规则评估加载主体实体数据的 SPI 接口。 */
public interface SubjectLoader {
    Subject load(String subjectId, SubjectType subjectType, RuleEvent event);
    List<SubjectType> supportedTypes();
}
