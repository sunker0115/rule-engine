package com.sstlfsj.rule.web.admin.dto;

import com.sstlfsj.rule.kernel.api.model.RuleBody;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.PreGateConfig;
import java.util.List;

/** 规则内容字段视图：create/edit/newVersion 三请求体共有的内容部分。 */
public interface RuleContentSource {
    String name();
    String kind();
    RuleBody body();
    List<DecisionBindingInput> decisionBindings();
    List<PreGateConfig> preGates();
    List<String> triggerEventTypes();
}
