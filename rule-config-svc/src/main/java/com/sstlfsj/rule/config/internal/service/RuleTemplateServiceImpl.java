package com.sstlfsj.rule.config.internal.service;

import com.sstlfsj.rule.config.api.dto.DraftCreatedResult;
import com.sstlfsj.rule.config.api.dto.RuleContent;
import com.sstlfsj.rule.config.api.dto.SlotBinding;
import com.sstlfsj.rule.config.api.dto.SlotConstraint;
import com.sstlfsj.rule.config.api.dto.TemplateSlot;
import com.sstlfsj.rule.config.api.service.RuleTemplateService;
import com.sstlfsj.rule.config.internal.domain.ActorType;
import com.sstlfsj.rule.config.internal.domain.AuditAction;
import com.sstlfsj.rule.config.internal.domain.AuditTargetType;
import com.sstlfsj.rule.config.internal.domain.RuleTemplate;
import com.sstlfsj.rule.config.internal.domain.RuleTemplateStatus;
import com.sstlfsj.rule.config.internal.event.OperationAuditedEvent;
import com.sstlfsj.rule.config.internal.event.RuleTemplateSnapshot;
import com.sstlfsj.rule.config.internal.publish.PublishService;
import com.sstlfsj.rule.config.internal.repository.RuleTemplateMapper;
import com.sstlfsj.rule.config.internal.template.TemplateBinder;
import com.sstlfsj.rule.kernel.api.model.AstBody;
import com.sstlfsj.rule.kernel.api.model.DataType;
import com.sstlfsj.rule.kernel.api.model.FlowBody;
import com.sstlfsj.rule.kernel.api.model.RuleBody;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.ScriptBody;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则模板管理实现（v2）：body skeleton + SlotBinding sidecar，binder SPI 按 body 变体分派，
 * 覆盖全 6 kind；无 token 逻辑、无 exportFromRule。
 */
@Service
@ConditionalOnProperty(name = "rule.template.enabled", havingValue = "true")
public class RuleTemplateServiceImpl implements RuleTemplateService {

    private final RuleTemplateMapper templateMapper;
    private final PublishService publishService;
    private final ApplicationEventPublisher eventPublisher;
    private final List<TemplateBinder> binders;

    public RuleTemplateServiceImpl(RuleTemplateMapper templateMapper,
                                   PublishService publishService,
                                   ApplicationEventPublisher eventPublisher,
                                   List<TemplateBinder> binders) {
        this.templateMapper = templateMapper;
        this.publishService = publishService;
        this.eventPublisher = eventPublisher;
        this.binders = binders;
    }

    @Override
    @Transactional
    public Long create(Long tenantId, String code, String name, String kind,
                       String description, RuleBody bodySkeleton,
                       List<TemplateSlot> slots, List<SlotBinding> bindings, String actorId) {
        RuleKind rk = validateKind(kind);
        validateKindBody(rk, bodySkeleton);
        TemplateBinder binder = pickBinder(bodySkeleton);
        binder.validate(bodySkeleton, safe(bindings), safe(slots));

        RuleTemplate tmpl = new RuleTemplate();
        tmpl.setCode(code);
        tmpl.setTenantId(tenantId);
        tmpl.setName(name);
        tmpl.setDescription(description);
        tmpl.setKind(rk);
        tmpl.setBodySkeleton(bodySkeleton);
        tmpl.setSlots(slots);
        tmpl.setBindings(bindings);
        tmpl.setVersion(1);
        tmpl.setStatus(RuleTemplateStatus.DRAFT);
        tmpl.setCreatedBy(actorId);
        tmpl.setCreatedAt(LocalDateTime.now());
        tmpl.setUpdatedBy(actorId);
        tmpl.setUpdatedAt(LocalDateTime.now());
        templateMapper.insert(tmpl);

        var snapshot = toSnapshot(tmpl);
        eventPublisher.publishEvent(new OperationAuditedEvent(
                tenantId, actorId, ActorType.USER, AuditAction.CREATE,
                AuditTargetType.RULE_TEMPLATE, tmpl.getId().toString(),
                null, snapshot, LocalDateTime.now()));
        return tmpl.getId();
    }

    @Override
    @Transactional
    public void update(Long tenantId, String code, String name, String kind,
                       String description, RuleBody bodySkeleton,
                       List<TemplateSlot> slots, List<SlotBinding> bindings, String actorId) {
        RuleTemplate tmpl = requireDraft(tenantId, code);
        RuleKind rk = validateKind(kind);
        validateKindBody(rk, bodySkeleton);
        TemplateBinder binder = pickBinder(bodySkeleton);
        binder.validate(bodySkeleton, safe(bindings), safe(slots));

        var before = toSnapshot(tmpl);
        tmpl.setName(name);
        tmpl.setDescription(description);
        tmpl.setKind(rk);
        tmpl.setBodySkeleton(bodySkeleton);
        tmpl.setSlots(slots);
        tmpl.setBindings(bindings);
        tmpl.setVersion(tmpl.getVersion() + 1);
        tmpl.setUpdatedBy(actorId);
        tmpl.setUpdatedAt(LocalDateTime.now());
        templateMapper.updateById(tmpl);

        var after = toSnapshot(tmpl);
        eventPublisher.publishEvent(new OperationAuditedEvent(
                tenantId, actorId, ActorType.USER, AuditAction.UPDATE,
                AuditTargetType.RULE_TEMPLATE, tmpl.getId().toString(),
                before, after, LocalDateTime.now()));
    }

    @Override
    @Transactional
    public void publish(Long tenantId, String code, String actorId) {
        RuleTemplate tmpl = requireDraft(tenantId, code);
        TemplateBinder binder = pickBinder(tmpl.getBodySkeleton());
        binder.validate(tmpl.getBodySkeleton(), safe(tmpl.getBindings()), safe(tmpl.getSlots()));

        var before = toSnapshot(tmpl);
        tmpl.setStatus(RuleTemplateStatus.PUBLISHED);
        tmpl.setVersion(tmpl.getVersion() + 1);
        tmpl.setUpdatedBy(actorId);
        tmpl.setUpdatedAt(LocalDateTime.now());
        templateMapper.updateById(tmpl);

        var after = toSnapshot(tmpl);
        eventPublisher.publishEvent(new OperationAuditedEvent(
                tenantId, actorId, ActorType.USER, AuditAction.PUBLISH,
                AuditTargetType.RULE_TEMPLATE, tmpl.getId().toString(),
                before, after, LocalDateTime.now()));
    }

    @Override
    @Transactional
    public void disable(Long tenantId, String code, String actorId) {
        RuleTemplate tmpl = requireTemplate(tenantId, code);
        if (tmpl.getStatus() != RuleTemplateStatus.PUBLISHED) {
            throw new IllegalArgumentException("仅 PUBLISHED 状态的模板可禁用");
        }
        var before = toSnapshot(tmpl);
        tmpl.setStatus(RuleTemplateStatus.DISABLED);
        tmpl.setUpdatedBy(actorId);
        tmpl.setUpdatedAt(LocalDateTime.now());
        templateMapper.updateById(tmpl);

        var after = toSnapshot(tmpl);
        eventPublisher.publishEvent(new OperationAuditedEvent(
                tenantId, actorId, ActorType.USER, AuditAction.DISABLE,
                AuditTargetType.RULE_TEMPLATE, tmpl.getId().toString(),
                before, after, LocalDateTime.now()));
    }

    @Override
    public List<RuleTemplate> list(Long tenantId, String status) {
        if (status != null && !status.isBlank()) {
            return templateMapper.findByTenantId(tenantId, RuleTemplateStatus.valueOf(status));
        }
        return templateMapper.findByTenantId(tenantId);
    }

    @Override
    public RuleTemplate get(Long tenantId, String code) {
        return requireTemplate(tenantId, code);
    }

    @Override
    @Transactional
    public DraftCreatedResult instantiate(Long tenantId, String templateCode,
                                          String ruleCode, String ruleName,
                                          String sceneCode, List<String> triggerEventTypes,
                                          Map<String, Object> slotValues, String actorId) {
        RuleTemplate tmpl = templateMapper.findPublishedByCode(tenantId, templateCode);
        if (tmpl == null) {
            throw new IllegalArgumentException("模板不存在或未发布: " + templateCode);
        }
        TemplateBinder binder = pickBinder(tmpl.getBodySkeleton());
        Map<String, Object> values = slotValues != null ? slotValues : Map.of();
        // required 齐全 + 无多余键 + 按 DataType 强转 + SlotConstraint 校验
        Map<String, Object> coerced = validateAndCoerce(safe(tmpl.getSlots()), values);
        RuleBody bound = binder.bind(tmpl.getBodySkeleton(), safe(tmpl.getBindings()), coerced);

        RuleContent content = new RuleContent(ruleName, tmpl.getKind().tag(), bound,
                List.of(), List.of(),
                triggerEventTypes != null ? triggerEventTypes : List.of());
        return publishService.createDraft(tenantId, sceneCode, ruleCode, content, actorId,
                tmpl.getId(), tmpl.getVersion());
    }

    // ---------- 内部 ----------

    /** 挑选支持该 body 变体的 binder；无匹配抛 TEMPLATE_KIND_UNSUPPORTED。 */
    private TemplateBinder pickBinder(RuleBody body) {
        for (TemplateBinder b : binders) {
            if (b.supports(body)) return b;
        }
        throw new IllegalArgumentException("TEMPLATE_KIND_UNSUPPORTED: 无 binder 支持该 body 类型: "
                + (body == null ? "null" : body.getClass().getSimpleName()));
    }

    private RuleKind validateKind(String kind) {
        if (kind == null || kind.isBlank()) throw new IllegalArgumentException("模板 kind 不可为空");
        return RuleKind.valueOf(kind);
    }

    /**
     * 校验 kind 家族与 body skeleton 变体一致，避免 kind 与 body 错配的模板落库后每次实例化才失败（永不可实例化）。
     * 与 PublishService.validateKindBodyConsistent 同款语义（后者私有静态不可复用，此处最小镜像）。
     * body 为 null 时跳过（binder 分派会另行拒收）。
     */
    private void validateKindBody(RuleKind kind, RuleBody body) {
        if (body == null) return;
        boolean ok = switch (kind) {
            case AST_BOOLEAN, SCORECARD, DECISION_TREE, DECISION_TABLE -> body instanceof AstBody;
            case EXPRESSION_SCRIPT -> body instanceof ScriptBody;
            case DECISION_FLOW -> body instanceof FlowBody;
        };
        if (!ok) {
            throw new IllegalArgumentException("KIND_BODY_MISMATCH: 模板 kind=" + kind
                    + " 与 body 类型 " + body.getClass().getSimpleName() + " 不一致");
        }
    }

    private RuleTemplate requireTemplate(Long tenantId, String code) {
        RuleTemplate tmpl = templateMapper.findByTenantAndCode(tenantId, code);
        if (tmpl == null) throw new IllegalArgumentException("模板不存在: code=" + code);
        return tmpl;
    }

    private RuleTemplate requireDraft(Long tenantId, String code) {
        RuleTemplate tmpl = requireTemplate(tenantId, code);
        if (tmpl.getStatus() != RuleTemplateStatus.DRAFT) {
            throw new IllegalArgumentException("仅 DRAFT 状态的模板可编辑/发布");
        }
        return tmpl;
    }

    private RuleTemplateSnapshot toSnapshot(RuleTemplate tmpl) {
        return RuleTemplateSnapshot.builder()
                .id(tmpl.getId()).code(tmpl.getCode())
                .name(tmpl.getName()).status(tmpl.getStatus().name())
                .version(tmpl.getVersion())
                .bodySkeleton(tmpl.getBodySkeleton())
                .slots(tmpl.getSlots())
                .bindings(tmpl.getBindings())
                .build();
    }

    /**
     * 校验填值并按 DataType 强转：required 齐全 + 无多余键 + 逐值强转 + SlotConstraint 校验。
     *
     * @return slotKey→强转后值（供 binder.bind 填入 skeleton）
     */
    private Map<String, Object> validateAndCoerce(List<TemplateSlot> slots, Map<String, Object> slotValues) {
        Map<String, TemplateSlot> byKey = new HashMap<>();
        slots.forEach(s -> byKey.put(s.key(), s));
        for (TemplateSlot def : slots) {
            if (def.required() && !slotValues.containsKey(def.key())) {
                throw new IllegalArgumentException("缺少必填 slot: " + def.key());
            }
        }
        Map<String, Object> coerced = new HashMap<>();
        for (var entry : slotValues.entrySet()) {
            TemplateSlot def = byKey.get(entry.getKey());
            if (def == null) {
                throw new IllegalArgumentException("slotValues 包含未声明的 slot: " + entry.getKey());
            }
            Object value = coerceValue(entry.getValue(), def.dataType());
            validateConstraint(def, value);
            coerced.put(entry.getKey(), value);
        }
        return coerced;
    }

    /** 校验 SlotConstraint：数值 min/max、标量/LIST 元素 enumValues 成员。 */
    private void validateConstraint(TemplateSlot def, Object coerced) {
        SlotConstraint c = def.constraint();
        if (c == null || coerced == null) return;
        if (coerced instanceof Number n) {
            double d = n.doubleValue();
            if (c.min() != null && d < c.min().doubleValue()) {
                throw new IllegalArgumentException("TEMPLATE_SLOT_VALUE_INVALID: " + def.key() + " 小于 min " + c.min());
            }
            if (c.max() != null && d > c.max().doubleValue()) {
                throw new IllegalArgumentException("TEMPLATE_SLOT_VALUE_INVALID: " + def.key() + " 大于 max " + c.max());
            }
        }
        if (c.enumValues() != null && !c.enumValues().isEmpty()) {
            if (coerced instanceof List<?> list) {
                for (Object el : list) {
                    if (!c.enumValues().contains(String.valueOf(el))) {
                        throw new IllegalArgumentException("TEMPLATE_SLOT_VALUE_INVALID: " + def.key() + " 元素不在 enumValues: " + el);
                    }
                }
            } else if (!c.enumValues().contains(String.valueOf(coerced))) {
                throw new IllegalArgumentException("TEMPLATE_SLOT_VALUE_INVALID: " + def.key() + " 不在 enumValues: " + coerced);
            }
        }
    }

    /** 按 slot DataType 把原始填值强转为目标类型，转不动抛 TEMPLATE_SLOT_VALUE_INVALID。 */
    private Object coerceValue(Object value, DataType dt) {
        if (value == null) return null;
        return switch (dt) {
            case LONG -> {
                if (value instanceof Number n) yield n.longValue();
                if (value instanceof String s) {
                    try { yield Long.parseLong(s.trim()); }
                    catch (NumberFormatException e) { throw invalid("无法转为 LONG", value); }
                }
                throw invalid("LONG 类型需要数值或字符串", value);
            }
            case DOUBLE -> {
                if (value instanceof Number n) yield n.doubleValue();
                if (value instanceof String s) {
                    try { yield Double.parseDouble(s.trim()); }
                    catch (NumberFormatException e) { throw invalid("无法转为 DOUBLE", value); }
                }
                throw invalid("DOUBLE 类型需要数值或字符串", value);
            }
            case DECIMAL -> {
                if (value instanceof BigDecimal bd) yield bd;
                if (value instanceof String s) {
                    try { yield new BigDecimal(s.trim()); }
                    catch (NumberFormatException e) { throw invalid("无法转为 DECIMAL", value); }
                }
                if (value instanceof Number n) yield BigDecimal.valueOf(n.doubleValue());
                throw invalid("DECIMAL 类型需要数值或字符串", value);
            }
            case STRING -> String.valueOf(value);
            case BOOLEAN -> {
                if (value instanceof Boolean b) yield b;
                if (value instanceof String s) yield Boolean.parseBoolean(s.trim());
                throw invalid("BOOLEAN 类型需要布尔值", value);
            }
            case DATE, DATETIME -> {
                if (value instanceof String s) {
                    try { java.time.LocalDate.parse(s); }
                    catch (Exception e) {
                        try { java.time.Instant.parse(s); }
                        catch (Exception e2) { throw invalid("无法解析日期", s); }
                    }
                    yield s;
                }
                throw invalid("DATE/DATETIME 需要 ISO 字符串", value);
            }
            case LIST -> {
                if (value instanceof List<?> l) yield l;
                throw invalid("LIST 类型需要数组", value);
            }
            default -> value;
        };
    }

    private IllegalArgumentException invalid(String reason, Object value) {
        return new IllegalArgumentException("TEMPLATE_SLOT_VALUE_INVALID: " + reason + ": " + value);
    }

    private <T> List<T> safe(List<T> list) {
        return list != null ? list : List.of();
    }
}
