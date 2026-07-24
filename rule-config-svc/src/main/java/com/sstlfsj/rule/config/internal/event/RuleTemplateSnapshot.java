package com.sstlfsj.rule.config.internal.event;

import com.sstlfsj.rule.config.api.dto.SlotBinding;
import com.sstlfsj.rule.config.api.dto.TemplateSlot;
import com.sstlfsj.rule.kernel.api.model.RuleBody;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import lombok.Builder;

import java.util.List;

/** 规则模板变更前/后快照（结合 RuleTemplate 身份 + RuleTemplateVersion 版本快照），落 audit_log。 */
@Builder
public record RuleTemplateSnapshot(
        Long id, String code, Long tenantId, String name, String description,
        RuleKind kind, String status,
        Long versionId, int version,
        RuleBody bodySkeleton, List<TemplateSlot> slots, List<SlotBinding> bindings)
        implements AuditSnapshot {
}
