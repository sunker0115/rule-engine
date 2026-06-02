package com.sstlfsj.rule.kernel.api.spi.subject;

import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.Subject;
import com.sstlfsj.rule.kernel.api.model.SubjectType;

import java.util.List;

/** Loads subject entity data for rule evaluation. */
public interface SubjectLoader {
    Subject load(String subjectId, SubjectType subjectType, RuleEvent event);
    List<SubjectType> supportedTypes();
}
