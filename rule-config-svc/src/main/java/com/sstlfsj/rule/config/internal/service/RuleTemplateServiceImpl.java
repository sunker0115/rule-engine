package com.sstlfsj.rule.config.internal.service;

import com.sstlfsj.rule.config.api.dto.DraftCreatedResult;
import com.sstlfsj.rule.config.api.dto.RuleContent;
import com.sstlfsj.rule.config.api.dto.SlotBinding;
import com.sstlfsj.rule.config.api.dto.SlotConstraint;
import com.sstlfsj.rule.config.api.dto.TemplateSlot;
import com.sstlfsj.rule.config.api.dto.ValueDataType;
import com.sstlfsj.rule.config.api.service.RuleTemplateService;
import com.sstlfsj.rule.config.internal.domain.ActorType;
import com.sstlfsj.rule.config.internal.domain.AuditAction;
import com.sstlfsj.rule.config.internal.domain.AuditTargetType;
import com.sstlfsj.rule.config.internal.domain.RuleTemplate;
import com.sstlfsj.rule.config.internal.domain.RuleTemplateInstantiation;
import com.sstlfsj.rule.config.internal.domain.RuleTemplateVersion;
import com.sstlfsj.rule.config.internal.domain.TemplateStatus;
import com.sstlfsj.rule.config.internal.event.OperationAuditedEvent;
import com.sstlfsj.rule.config.internal.event.RuleTemplateSnapshot;
import com.sstlfsj.rule.config.internal.publish.PublishService;
import com.sstlfsj.rule.config.internal.repository.RuleTemplateInstantiationMapper;
import com.sstlfsj.rule.config.internal.repository.RuleTemplateMapper;
import com.sstlfsj.rule.config.internal.repository.RuleTemplateVersionMapper;
import com.sstlfsj.rule.config.internal.template.TemplateBinder;
import com.sstlfsj.rule.kernel.api.model.AstBody;
import com.sstlfsj.rule.kernel.api.model.FlowBody;
import com.sstlfsj.rule.kernel.api.model.RuleBody;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.ScriptBody;
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
 * 模板身份存 rule_template，版本快照存 rule_template_version，溯源存 rule_template_instantiation。
 */
@Service
public class RuleTemplateServiceImpl implements RuleTemplateService {

    private final RuleTemplateMapper templateMapper;
    private final RuleTemplateVersionMapper versionMapper;
    private final RuleTemplateInstantiationMapper instantiationMapper;
    private final PublishService publishService;
    private final ApplicationEventPublisher eventPublisher;
    private final List<TemplateBinder> binders;

    public RuleTemplateServiceImpl(RuleTemplateMapper templateMapper,
                                   RuleTemplateVersionMapper versionMapper,
                                   RuleTemplateInstantiationMapper instantiationMapper,
                                   PublishService publishService,
                                   ApplicationEventPublisher eventPublisher,
                                   List<TemplateBinder> binders) {
        this.templateMapper = templateMapper;
        this.versionMapper = versionMapper;
        this.instantiationMapper = instantiationMapper;
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

        // 预检 (tenant,code) 唯一性
        if (templateMapper.findByTenantAndCode(tenantId, code) != null) {
            throw new IllegalArgumentException("模板编码已存在: " + code);
        }

        LocalDateTime now = LocalDateTime.now();
        // 写入身份
        RuleTemplate tmpl = new RuleTemplate();
        tmpl.setCode(code);
        tmpl.setTenantId(tenantId);
        tmpl.setName(name);
        tmpl.setDescription(description);
        tmpl.setKind(rk);
        tmpl.setStatus(TemplateStatus.DRAFT);
        tmpl.setCreatedBy(actorId);
        tmpl.setCreatedAt(now);
        tmpl.setUpdatedBy(actorId);
        tmpl.setUpdatedAt(now);
        templateMapper.insert(tmpl);

        // 写入 v1 版本快照
        RuleTemplateVersion v1 = new RuleTemplateVersion();
        v1.setTemplateId(tmpl.getId());
        v1.setVersion(1);
        v1.setBodySkeleton(bodySkeleton);
        v1.setSlots(safe(slots));
        v1.setBindings(safe(bindings));
        v1.setStatus(TemplateStatus.DRAFT);
        v1.setCreatedBy(actorId);
        v1.setCreatedAt(now);
        versionMapper.insert(v1);

        var snapshot = toSnapshot(tmpl, v1);
        eventPublisher.publishEvent(new OperationAuditedEvent(
                tenantId, actorId, ActorType.USER, AuditAction.CREATE,
                AuditTargetType.RULE_TEMPLATE, tmpl.getId().toString(),
                null, snapshot, now));
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

        RuleTemplateVersion draft = versionMapper.findDraft(tmpl.getId());
        var before = toSnapshot(tmpl, draft);

        LocalDateTime now = LocalDateTime.now();
        // 更新身份
        tmpl.setName(name);
        tmpl.setDescription(description);
        tmpl.setKind(rk);
        tmpl.setUpdatedBy(actorId);
        tmpl.setUpdatedAt(now);
        templateMapper.updateById(tmpl);

        // 写入新版本快照
        int nextVersion = draft != null ? draft.getVersion() + 1 : 1;
        RuleTemplateVersion next = new RuleTemplateVersion();
        next.setTemplateId(tmpl.getId());
        next.setVersion(nextVersion);
        next.setBodySkeleton(bodySkeleton);
        next.setSlots(safe(slots));
        next.setBindings(safe(bindings));
        next.setStatus(TemplateStatus.DRAFT);
        next.setCreatedBy(actorId);
        next.setCreatedAt(now);
        versionMapper.insert(next);

        var after = toSnapshot(tmpl, next);
        eventPublisher.publishEvent(new OperationAuditedEvent(
                tenantId, actorId, ActorType.USER, AuditAction.UPDATE,
                AuditTargetType.RULE_TEMPLATE, tmpl.getId().toString(),
                before, after, now));
    }

    @Override
    @Transactional
    public void publish(Long tenantId, String code, String actorId) {
        RuleTemplate tmpl = requireDraft(tenantId, code);
        RuleTemplateVersion draft = versionMapper.findDraft(tmpl.getId());
        if (draft == null) {
            throw new IllegalArgumentException("模板无 DRAFT 版本可发布: " + code);
        }
        TemplateBinder binder = pickBinder(draft.getBodySkeleton());
        binder.validate(draft.getBodySkeleton(), safe(draft.getBindings()), safe(draft.getSlots()));

        var before = toSnapshot(tmpl, draft);
        LocalDateTime now = LocalDateTime.now();

        // 发布：插入 PUBLISHED 版本快照 + 更新身份状态
        RuleTemplateVersion published = new RuleTemplateVersion();
        published.setTemplateId(tmpl.getId());
        published.setVersion(draft.getVersion() + 1);
        published.setBodySkeleton(draft.getBodySkeleton());
        published.setSlots(draft.getSlots());
        published.setBindings(draft.getBindings());
        published.setStatus(TemplateStatus.PUBLISHED);
        published.setCreatedBy(actorId);
        published.setCreatedAt(now);
        versionMapper.insert(published);

        tmpl.setStatus(TemplateStatus.PUBLISHED);
        tmpl.setUpdatedBy(actorId);
        tmpl.setUpdatedAt(now);
        templateMapper.updateById(tmpl);

        var after = toSnapshot(tmpl, published);
        eventPublisher.publishEvent(new OperationAuditedEvent(
                tenantId, actorId, ActorType.USER, AuditAction.PUBLISH,
                AuditTargetType.RULE_TEMPLATE, tmpl.getId().toString(),
                before, after, now));
    }

    @Override
    @Transactional
    public void disable(Long tenantId, String code, String actorId) {
        RuleTemplate tmpl = requireTemplate(tenantId, code);
        if (tmpl.getStatus() != TemplateStatus.PUBLISHED) {
            throw new IllegalArgumentException("仅 PUBLISHED 状态的模板可禁用");
        }
        var before = toSnapshot(tmpl, versionMapper.findLatestPublished(tmpl.getId()));
        tmpl.setStatus(TemplateStatus.DISABLED);
        tmpl.setUpdatedBy(actorId);
        tmpl.setUpdatedAt(LocalDateTime.now());
        templateMapper.updateById(tmpl);

        var after = toSnapshot(tmpl, versionMapper.findLatestPublished(tmpl.getId()));
        eventPublisher.publishEvent(new OperationAuditedEvent(
                tenantId, actorId, ActorType.USER, AuditAction.DISABLE,
                AuditTargetType.RULE_TEMPLATE, tmpl.getId().toString(),
                before, after, LocalDateTime.now()));
    }

    @Override
    public List<RuleTemplate> list(Long tenantId, String status) {
        if (status != null && !status.isBlank()) {
            return templateMapper.findByTenantId(tenantId, TemplateStatus.valueOf(status));
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
        RuleTemplateVersion published = versionMapper.findLatestPublished(tmpl.getId());
        if (published == null) {
            throw new IllegalArgumentException("模板无 PUBLISHED 版本: " + templateCode);
        }
        TemplateBinder binder = pickBinder(published.getBodySkeleton());
        Map<String, Object> values = slotValues != null ? slotValues : Map.of();
        Map<String, Object> coerced = validateAndCoerce(safe(published.getSlots()), values);
        RuleBody bound = binder.bind(published.getBodySkeleton(), safe(published.getBindings()), coerced);

        RuleContent content = new RuleContent(ruleName, published.getBodySkeleton() instanceof AstBody
                ? RuleKind.AST_BOOLEAN.tag() : tmpl.getKind().tag(), bound,
                List.of(), List.of(),
                triggerEventTypes != null ? triggerEventTypes : List.of());
        DraftCreatedResult result = publishService.createDraft(tenantId, sceneCode, ruleCode, content, actorId);

        // 写入溯源记录
        RuleTemplateInstantiation ri = new RuleTemplateInstantiation();
        ri.setTemplateId(tmpl.getId());
        ri.setTemplateVersionId(published.getId());
        ri.setTemplateVersion(published.getVersion());
        ri.setRuleDefinitionId(result.ruleDefinitionId());
        ri.setRuleVersionId(result.ruleVersionId());
        ri.setSlotValues(coerced);
        ri.setInstantiatedAt(LocalDateTime.now());
        ri.setInstantiatedBy(actorId);
        instantiationMapper.insert(ri);

        return result;
    }

    // ---------- 内部 ----------

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
        if (tmpl.getStatus() != TemplateStatus.DRAFT) {
            throw new IllegalArgumentException("仅 DRAFT 状态的模板可编辑/发布");
        }
        return tmpl;
    }

    private RuleTemplateSnapshot toSnapshot(RuleTemplate tmpl, RuleTemplateVersion ver) {
        if (ver == null) {
            return RuleTemplateSnapshot.builder()
                    .id(tmpl.getId()).code(tmpl.getCode())
                    .name(tmpl.getName()).status(tmpl.getStatus().name())
                    .version(0)
                    .bodySkeleton(null).slots(List.of()).bindings(List.of())
                    .build();
        }
        return RuleTemplateSnapshot.builder()
                .id(tmpl.getId()).code(tmpl.getCode())
                .name(tmpl.getName()).status(tmpl.getStatus().name())
                .version(ver.getVersion())
                .bodySkeleton(ver.getBodySkeleton())
                .slots(ver.getSlots())
                .bindings(ver.getBindings())
                .build();
    }

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

    private Object coerceValue(Object value, ValueDataType dt) {
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
            case DATE -> {
                if (value instanceof String s) {
                    try { java.time.LocalDate.parse(s); yield s; }
                    catch (Exception e) { throw invalid("无法解析 DATE，需 ISO 日期", s); }
                }
                throw invalid("DATE 需要 ISO 日期字符串", value);
            }
            case DATETIME -> {
                if (value instanceof String s) {
                    try { java.time.Instant.parse(s); yield s; }
                    catch (Exception e) { throw invalid("无法解析 DATETIME，需 ISO 时间戳", s); }
                }
                throw invalid("DATETIME 需要 ISO 时间戳字符串", value);
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
