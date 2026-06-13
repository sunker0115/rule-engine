package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.kernel.api.annotation.DecisionBinding;
import com.sstlfsj.rule.kernel.api.annotation.RuleDef;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import com.sstlfsj.rule.sdk.InlineRuleSpec;

import java.util.List;

/**
 * 注解模式规则来源：扫描 {@link InlineRuleSpec} 列表，读取 {@link RuleDef} 元数据，
 * 调用 {@link InlineRuleSpec#condition()} 构建 RuleVersionSnapshot 并装载到索引。
 * 未标注 @RuleDef 的 spec 静默跳过。
 */
public class AnnotationRuleSource implements RuleSource {

    private final List<InlineRuleSpec> specs;
    private final String defaultTenantId;

    public AnnotationRuleSource(List<InlineRuleSpec> specs) {
        this(specs, "");
    }

    public AnnotationRuleSource(List<InlineRuleSpec> specs, String defaultTenantId) {
        this.specs = List.copyOf(specs);
        this.defaultTenantId = defaultTenantId == null ? "" : defaultTenantId;
    }

    @Override
    public void loadInto(SceneRuleIndex index) {
        for (InlineRuleSpec spec : specs) {
            RuleDef ann = spec.getClass().getAnnotation(RuleDef.class);
            if (ann == null) continue;

            String tenant = ann.tenantId().isBlank() ? defaultTenantId : ann.tenantId();
            long ruleVersionId = stableId(tenant, ann.sceneCode(), ann.code());
            RuleVersionSnapshot.Builder builder = RuleVersionSnapshot.builder()
                    .ruleVersionId(ruleVersionId)
                    .tenantId(tenant)
                    .sceneCode(ann.sceneCode())
                    .code(ann.code())
                    .version(ann.version())
                    .conditionAst(spec.condition().toAst());

            if (ann.eventTypes().length == 0) {
                builder.addTriggerEventType("*");
            } else {
                for (String t : ann.eventTypes()) {
                    builder.addTriggerEventType(t);
                }
            }
            for (DecisionBinding d : ann.decisions()) {
                builder.addDecisionBinding(d.code(), d.priority());
            }

            new DslRuleSource(List.of(builder.build())).loadInto(index);
        }
    }

    /** 由 (tenant,scene,code) 派生稳定 64-bit 版本 id；同输入跨进程稳定，满足幂等 loadInto。 */
    private static long stableId(String tenant, String scene, String code) {
        return (tenant + ":" + scene + ":" + code).hashCode() & 0xffffffffL;
    }
}
