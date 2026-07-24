package com.sstlfsj.rule.config.internal.event;

import com.sstlfsj.rule.config.api.dto.SlotBinding;
import com.sstlfsj.rule.config.api.dto.TemplateSlot;
import com.sstlfsj.rule.kernel.api.model.RuleBody;
import lombok.Builder;

import java.util.List;

/** 规则模板变更前/后快照，落 audit_log 的 before/after_snapshot（含 bodySkeleton/slots/bindings 完整审计）。 */
@Builder
public record RuleTemplateSnapshot(
        Long id, String code, String name, String status, int version,
        RuleBody bodySkeleton, List<TemplateSlot> slots, List<SlotBinding> bindings)
        implements AuditSnapshot {
}
