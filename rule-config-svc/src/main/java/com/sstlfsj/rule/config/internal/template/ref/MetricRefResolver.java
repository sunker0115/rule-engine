package com.sstlfsj.rule.config.internal.template.ref;

import com.sstlfsj.rule.config.api.dto.SlotKind;
import com.sstlfsj.rule.config.api.dto.SlotResolutionContext;
import com.sstlfsj.rule.config.api.dto.TemplateSlot;
import com.sstlfsj.rule.config.api.service.SlotRefResolver;
import com.sstlfsj.rule.config.internal.repository.MetricDefinitionMapper;
import org.springframework.stereotype.Component;

/** METRIC_REF 引用解析器：验证 metric 存在且 ACTIVE。 */
@Component
public class MetricRefResolver implements SlotRefResolver {

    private final MetricDefinitionMapper metricDefinitionMapper;

    public MetricRefResolver(MetricDefinitionMapper metricDefinitionMapper) {
        this.metricDefinitionMapper = metricDefinitionMapper;
    }

    @Override
    public boolean supports(SlotKind kind) {
        return kind == SlotKind.METRIC_REF;
    }

    @Override
    public void validate(String value, TemplateSlot slot, SlotResolutionContext ctx) {
        var metric = metricDefinitionMapper.findActiveByCode(ctx.tenantId(), value);
        if (metric == null) {
            throw new IllegalArgumentException(
                    "METRIC_REF slot '%s': metric '%s' 不存在或非 ACTIVE（tenant=%d）"
                            .formatted(slot.key(), value, ctx.tenantId()));
        }
    }
}
