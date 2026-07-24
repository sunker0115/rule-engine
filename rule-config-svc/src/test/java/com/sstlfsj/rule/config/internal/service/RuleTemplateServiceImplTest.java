package com.sstlfsj.rule.config.internal.service;

import com.sstlfsj.rule.config.api.dto.DraftCreatedResult;
import com.sstlfsj.rule.config.api.dto.JsonPointerTarget;
import com.sstlfsj.rule.config.api.dto.RuleContent;
import com.sstlfsj.rule.config.api.dto.SlotBinding;
import com.sstlfsj.rule.config.api.dto.SlotConstraint;
import com.sstlfsj.rule.config.api.dto.TemplateSlot;
import com.sstlfsj.rule.config.internal.domain.RuleTemplate;
import com.sstlfsj.rule.config.internal.domain.RuleTemplateStatus;
import com.sstlfsj.rule.config.internal.event.OperationAuditedEvent;
import com.sstlfsj.rule.config.internal.publish.PublishService;
import com.sstlfsj.rule.config.internal.repository.RuleTemplateMapper;
import com.sstlfsj.rule.config.internal.template.JsonPointerBinder;
import com.sstlfsj.rule.config.internal.template.TemplateBinder;
import com.sstlfsj.rule.kernel.api.model.AstBody;
import com.sstlfsj.rule.config.api.dto.SlotKind;
import com.sstlfsj.rule.config.api.dto.ValueDataType;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuleTemplateServiceImplTest {

    @Mock
    RuleTemplateMapper templateMapper;
    @Mock
    PublishService publishService;
    @Mock
    ApplicationEventPublisher eventPublisher;

    RuleTemplateServiceImpl service;

    @BeforeEach
    void setUp() {
        List<TemplateBinder> binders = List.of(new JsonPointerBinder(new ObjectMapper()));
        service = new RuleTemplateServiceImpl(templateMapper, publishService, eventPublisher, binders);
    }

    // 一个合法 AstBody 骨架：ConditionNode.params.threshold 默认 100，绑定到 slot "threshold"
    private AstBody skeleton() {
        return new AstBody(new AndNode(
                List.of(new ConditionNode("GT", "amount", "金额大于", Map.of("threshold", 100), 0.0)),
                null, null));
    }

    private List<SlotBinding> bindings() {
        return List.of(new SlotBinding("threshold",
                new JsonPointerTarget("/conditionAst/children/0/params/threshold")));
    }

    private List<TemplateSlot> slots(SlotConstraint constraint) {
        return List.of(new TemplateSlot("threshold", "阈值", SlotKind.VALUE, ValueDataType.LONG, true, constraint));
    }

    @Test
    void create_insertsTemplateAndPublishesCreateAudit() {
        when(templateMapper.insert(any(RuleTemplate.class))).thenAnswer(inv -> {
            RuleTemplate t = inv.getArgument(0);
            t.setId(1L);
            return 1;
        });

        Long id = service.create(0L, "tmpl-a", "模板A", RuleKind.AST_BOOLEAN.name(), "desc",
                skeleton(), slots(null), bindings(), "u1");

        assertThat(id).isEqualTo(1L);
        ArgumentCaptor<OperationAuditedEvent> captor = ArgumentCaptor.forClass(OperationAuditedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        OperationAuditedEvent evt = captor.getValue();
        assertThat(evt.action().name()).isEqualTo("CREATE");
        assertThat(evt.targetType().name()).isEqualTo("RULE_TEMPLATE");
        assertThat(evt.beforeSnapshot()).isNull();
        assertThat(evt.afterSnapshot()).isNotNull();
    }

    @Test
    void create_noBinderSupportsBody_throwsKindUnsupported() {
        RuleTemplateServiceImpl noBinder =
                new RuleTemplateServiceImpl(templateMapper, publishService, eventPublisher, List.of());
        assertThatThrownBy(() -> noBinder.create(0L, "tmpl-a", "模板A", RuleKind.AST_BOOLEAN.name(),
                "desc", skeleton(), slots(null), bindings(), "u1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TEMPLATE_KIND_UNSUPPORTED");
    }

    @Test
    void create_kindBodyVariantMismatch_rejected() {
        // kind=DECISION_FLOW 但 body 是 AstBody skeleton → 应在落库前拒收（否则模板永不可实例化）
        assertThatThrownBy(() -> service.create(0L, "tmpl-a", "模板A", RuleKind.DECISION_FLOW.name(),
                "desc", skeleton(), slots(null), bindings(), "u1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("KIND_BODY");
    }

    @Test
    void instantiate_bindsValuesAndCallsCreateDraftWithTemplateProvenance() {
        RuleTemplate tmpl = publishedTemplate(null);
        when(templateMapper.findPublishedByCode(0L, "tmpl-a")).thenReturn(tmpl);
        when(publishService.createDraft(eq(0L), eq("scene-x"), eq("rule-1"), any(RuleContent.class),
                eq("u1"), eq(7L), eq(3)))
                .thenReturn(new DraftCreatedResult(11L, 22L, 1L, "DRAFT"));

        DraftCreatedResult result = service.instantiate(0L, "tmpl-a", "rule-1", "规则1",
                "scene-x", List.of("evt"), Map.of("threshold", 200), "u1");

        assertThat(result.ruleVersionId()).isEqualTo(22L);
        ArgumentCaptor<RuleContent> captor = ArgumentCaptor.forClass(RuleContent.class);
        verify(publishService).createDraft(eq(0L), eq("scene-x"), eq("rule-1"), captor.capture(),
                eq("u1"), eq(7L), eq(3));
        AstBody bound = (AstBody) captor.getValue().body();
        ConditionNode cn = (ConditionNode) ((AndNode) bound.conditionAst()).children().get(0);
        assertThat(((Number) cn.params().get("threshold")).longValue()).isEqualTo(200L);
    }

    @Test
    void instantiate_templateNotPublished_throws() {
        when(templateMapper.findPublishedByCode(0L, "tmpl-a")).thenReturn(null);
        assertThatThrownBy(() -> service.instantiate(0L, "tmpl-a", "rule-1", "规则1",
                "scene-x", List.of(), Map.of("threshold", 200), "u1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未发布");
    }

    @Test
    void instantiate_constraintViolation_throwsValueInvalid() {
        RuleTemplate tmpl = publishedTemplate(new SlotConstraint(null, BigDecimal.valueOf(100), null, null));
        when(templateMapper.findPublishedByCode(0L, "tmpl-a")).thenReturn(tmpl);
        assertThatThrownBy(() -> service.instantiate(0L, "tmpl-a", "rule-1", "规则1",
                "scene-x", List.of(), Map.of("threshold", 200), "u1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TEMPLATE_SLOT_VALUE_INVALID");
    }

    @Test
    void create_slotMissingBinding_rejected() {
        // slots 有 threshold 但 bindings 为空 → 缺少 binding
        List<TemplateSlot> slots = List.of(new TemplateSlot("threshold", "阈值", SlotKind.VALUE, ValueDataType.LONG, true, null));
        assertThatThrownBy(() -> service.create(0L, "tmpl-b", "模板B", RuleKind.AST_BOOLEAN.name(),
                "desc", skeleton(), slots, List.of(), "u1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TEMPLATE_SLOT_BINDING_MISMATCH");
    }

    @Test
    void instantiate_missingRequiredSlotValue_rejected() {
        RuleTemplate tmpl = publishedTemplate(null);
        when(templateMapper.findPublishedByCode(0L, "tmpl-a")).thenReturn(tmpl);
        // threshold 是 required=true 的 slot，但 slotValues 为空
        assertThatThrownBy(() -> service.instantiate(0L, "tmpl-a", "rule-2", "规则2",
                "scene-x", List.of(), Map.of(), "u1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少必填 slot");
    }

    @Test
    void instantiate_unknownSlotKey_rejected() {
        RuleTemplate tmpl = publishedTemplate(null);
        when(templateMapper.findPublishedByCode(0L, "tmpl-a")).thenReturn(tmpl);
        // 必填 slot 齐全 + 多余未知键 → 触发 unknown key 分支
        assertThatThrownBy(() -> service.instantiate(0L, "tmpl-a", "rule-3", "规则3",
                "scene-x", List.of(), Map.of("threshold", 200, "unknownKey", 123), "u1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未声明的 slot");
    }

    private RuleTemplate publishedTemplate(SlotConstraint constraint) {
        RuleTemplate tmpl = new RuleTemplate();
        tmpl.setId(7L);
        tmpl.setCode("tmpl-a");
        tmpl.setTenantId(0L);
        tmpl.setName("模板A");
        tmpl.setKind(RuleKind.AST_BOOLEAN);
        tmpl.setBodySkeleton(skeleton());
        tmpl.setSlots(slots(constraint));
        tmpl.setBindings(bindings());
        tmpl.setVersion(3);
        tmpl.setStatus(RuleTemplateStatus.PUBLISHED);
        return tmpl;
    }
}
