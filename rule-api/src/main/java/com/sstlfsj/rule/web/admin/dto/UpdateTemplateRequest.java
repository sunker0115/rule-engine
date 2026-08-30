package com.sstlfsj.rule.web.admin.dto;

import com.sstlfsj.rule.config.api.dto.SlotBinding;
import com.sstlfsj.rule.config.api.dto.TemplateSlot;
import com.sstlfsj.rule.kernel.api.model.RuleBody;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** 更新模板请求体。 */
public record UpdateTemplateRequest(
        @NotNull Long tenantId,
        String name,
        String kind,
        String description,
        RuleBody bodySkeleton,
        List<TemplateSlot> slots,
        List<SlotBinding> bindings
) {}
