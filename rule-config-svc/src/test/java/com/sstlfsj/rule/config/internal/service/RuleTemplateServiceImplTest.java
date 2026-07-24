package com.sstlfsj.rule.config.internal.service;

import com.sstlfsj.rule.config.api.dto.DraftCreatedResult;
import com.sstlfsj.rule.config.api.dto.RuleContent;
import com.sstlfsj.rule.config.api.dto.SlotBinding;
import com.sstlfsj.rule.config.api.dto.SlotConstraint;
import com.sstlfsj.rule.config.api.dto.SlotKind;
import com.sstlfsj.rule.config.api.dto.SlotResolutionContext;
import com.sstlfsj.rule.config.api.dto.TemplateSlot;
import com.sstlfsj.rule.config.api.dto.ValueDataType;
import com.sstlfsj.rule.config.api.service.SlotRefResolver;
import com.sstlfsj.rule.config.internal.domain.RuleTemplate;
import com.sstlfsj.rule.config.internal.domain.RuleTemplateInstantiation;
import com.sstlfsj.rule.config.internal.domain.RuleTemplateVersion;
import com.sstlfsj.rule.config.internal.domain.TemplateStatus;
import com.sstlfsj.rule.config.internal.event.OperationAuditedEvent;
import com.sstlfsj.rule.config.internal.publish.PublishService;
import com.sstlfsj.rule.config.internal.repository.RuleTemplateInstantiationMapper;
import com.sstlfsj.rule.config.internal.repository.RuleTemplateMapper;
import com.sstlfsj.rule.config.internal.repository.RuleTemplateVersionMapper;
import com.sstlfsj.rule.config.internal.template.TemplateBinder;
import com.sstlfsj.rule.kernel.api.model.AstBody;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RuleTemplateServiceImplTest {

    private static final Long TENANT_ID = 9001L; // STANDARD 租户

    @Mock
    RuleTemplateMapper templateMapper;
    @Mock
    RuleTemplateVersionMapper versionMapper;
    @Mock
    RuleTemplateInstantiationMapper instantiationMapper;
    @Mock
    PublishService publishService;
    @Mock
    ApplicationEventPublisher eventPublisher;
    @Mock
    TemplateBinder binder;
    @Mock
    SlotRefResolver metricRefResolver;
    @Mock
    SlotRefResolver decisionRefResolver;
    @Mock
    SlotRefResolver ruleRefResolver;

    RuleTemplateServiceImpl service;

    @BeforeEach
    void setUp() {
        when(binder.supports(any())).thenReturn(true);
        // 三个 REF resolver 分别支持各自的 kind
        when(metricRefResolver.supports(SlotKind.METRIC_REF)).thenReturn(true);
        when(decisionRefResolver.supports(SlotKind.DECISION_REF)).thenReturn(true);
        when(ruleRefResolver.supports(SlotKind.RULE_REF)).thenReturn(true);
        service = new RuleTemplateServiceImpl(templateMapper, versionMapper, instantiationMapper,
                publishService, eventPublisher, List.of(binder),
                List.of(metricRefResolver, decisionRefResolver, ruleRefResolver));
    }

    // ---------- helper ----------

    private AstBody skeleton() {
        return new AstBody(new AndNode(
                List.of(new ConditionNode("GT", "amount", "金额大于", Map.of("threshold", 100), 0.0)),
                null, null));
    }

    private List<SlotBinding> sampleBindings() {
        // 使用简化 SlotBinding（仅 key，无 target），因为 mock 了 binder
        return List.of(new SlotBinding("threshold", null));
    }

    private List<TemplateSlot> valueSlot(SlotConstraint constraint) {
        return List.of(new TemplateSlot("threshold", "阈值", SlotKind.VALUE, ValueDataType.LONG, true, constraint));
    }

    private RuleTemplate template(Long id, TemplateStatus status) {
        RuleTemplate tmpl = new RuleTemplate();
        tmpl.setId(id);
        tmpl.setCode("tmpl-a");
        tmpl.setTenantId(TENANT_ID);
        tmpl.setName("模板A");
        tmpl.setDescription("测试模板");
        tmpl.setKind(RuleKind.AST_BOOLEAN);
        tmpl.setStatus(status);
        return tmpl;
    }

    private RuleTemplateVersion draftVersion(Long templateId, int version) {
        RuleTemplateVersion ver = new RuleTemplateVersion();
        ver.setId(10L + templateId);
        ver.setTemplateId(templateId);
        ver.setVersion(version);
        ver.setBodySkeleton(skeleton());
        ver.setSlots(valueSlot(null));
        ver.setBindings(sampleBindings());
        ver.setStatus(TemplateStatus.DRAFT);
        return ver;
    }

    private RuleTemplateVersion publishedVersion(Long templateId, int version) {
        RuleTemplateVersion ver = draftVersion(templateId, version);
        ver.setStatus(TemplateStatus.PUBLISHED);
        return ver;
    }

    // ---------- create ----------

    @Test
    void create_insertsIdentityAndV1Draft() {
        when(templateMapper.findByTenantAndCode(TENANT_ID, "tmpl-a")).thenReturn(null);
        when(templateMapper.insert(any(RuleTemplate.class))).thenAnswer(inv -> {
            RuleTemplate t = inv.getArgument(0);
            t.setId(1L);
            return 1;
        });
        when(versionMapper.insert(any(RuleTemplateVersion.class))).thenReturn(1);

        Long id = service.create(TENANT_ID, "tmpl-a", "模板A", RuleKind.AST_BOOLEAN.name(), "desc",
                skeleton(), valueSlot(null), sampleBindings(), "u1");

        assertThat(id).isEqualTo(1L);
        // 验证身份表写入
        ArgumentCaptor<RuleTemplate> tmplCaptor = ArgumentCaptor.forClass(RuleTemplate.class);
        verify(templateMapper).insert(tmplCaptor.capture());
        assertThat(tmplCaptor.getValue().getStatus()).isEqualTo(TemplateStatus.DRAFT);
        assertThat(tmplCaptor.getValue().getCode()).isEqualTo("tmpl-a");
        // 验证版本表写入 v1 DRAFT
        ArgumentCaptor<RuleTemplateVersion> verCaptor = ArgumentCaptor.forClass(RuleTemplateVersion.class);
        verify(versionMapper).insert(verCaptor.capture());
        assertThat(verCaptor.getValue().getVersion()).isEqualTo(1);
        assertThat(verCaptor.getValue().getStatus()).isEqualTo(TemplateStatus.DRAFT);
        // 审计事件
        ArgumentCaptor<OperationAuditedEvent> evtCaptor = ArgumentCaptor.forClass(OperationAuditedEvent.class);
        verify(eventPublisher).publishEvent(evtCaptor.capture());
        assertThat(evtCaptor.getValue().action().name()).isEqualTo("CREATE");
        assertThat(evtCaptor.getValue().afterSnapshot()).isNotNull();
    }

    @Test
    void create_duplicateCode_throws() {
        when(templateMapper.findByTenantAndCode(TENANT_ID, "tmpl-a")).thenReturn(template(1L, TemplateStatus.DRAFT));
        assertThatThrownBy(() -> service.create(TENANT_ID, "tmpl-a", "模板A", RuleKind.AST_BOOLEAN.name(),
                "desc", skeleton(), valueSlot(null), sampleBindings(), "u1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("模板编码已存在");
    }

    // ---------- update ----------

    @Test
    void update_existingDraft_updatesInPlaceSameVersion() {
        RuleTemplate tmpl = template(1L, TemplateStatus.DRAFT);
        RuleTemplateVersion draft = draftVersion(1L, 1);
        when(templateMapper.findByTenantAndCode(TENANT_ID, "tmpl-a")).thenReturn(tmpl);
        when(versionMapper.findDraft(1L)).thenReturn(draft);

        service.update(TENANT_ID, "tmpl-a", "模板A改", RuleKind.AST_BOOLEAN.name(), "新描述",
                skeleton(), valueSlot(null), sampleBindings(), "u2");

        verify(templateMapper).updateById(tmpl);
        assertThat(tmpl.getName()).isEqualTo("模板A改");
        // DRAFT 版本原地更新，不 insert 新版本
        verify(versionMapper).updateById(draft);
        verify(versionMapper, never()).insert(any(RuleTemplateVersion.class));
    }

    @Test
    void update_publishedNoDraft_createsNewDraftVersion() {
        RuleTemplate tmpl = template(1L, TemplateStatus.PUBLISHED);
        when(templateMapper.findByTenantAndCode(TENANT_ID, "tmpl-a")).thenReturn(tmpl);
        when(versionMapper.findDraft(1L)).thenReturn(null);
        when(versionMapper.findMaxVersion(1L)).thenReturn(3);

        service.update(TENANT_ID, "tmpl-a", "模板A改", RuleKind.AST_BOOLEAN.name(), "新描述",
                skeleton(), valueSlot(null), sampleBindings(), "u2");

        // 新增 v4 DRAFT
        ArgumentCaptor<RuleTemplateVersion> verCaptor = ArgumentCaptor.forClass(RuleTemplateVersion.class);
        verify(versionMapper).insert(verCaptor.capture());
        RuleTemplateVersion newDraft = verCaptor.getValue();
        assertThat(newDraft.getVersion()).isEqualTo(4);
        assertThat(newDraft.getStatus()).isEqualTo(TemplateStatus.DRAFT);
        // 模板身份更新
        verify(templateMapper).updateById(tmpl);
    }

    @Test
    void update_disabled_throws() {
        RuleTemplate tmpl = template(1L, TemplateStatus.DISABLED);
        when(templateMapper.findByTenantAndCode(TENANT_ID, "tmpl-a")).thenReturn(tmpl);
        assertThatThrownBy(() -> service.update(TENANT_ID, "tmpl-a", "模板A改", RuleKind.AST_BOOLEAN.name(),
                "新描述", skeleton(), valueSlot(null), sampleBindings(), "u2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DISABLED");
    }

    // ---------- publish ----------

    @Test
    void publish_draftToPublished_inPlaceStatusTransition() {
        RuleTemplate tmpl = template(1L, TemplateStatus.DRAFT);
        RuleTemplateVersion draft = draftVersion(1L, 1);
        when(templateMapper.findByTenantAndCode(TENANT_ID, "tmpl-a")).thenReturn(tmpl);
        when(versionMapper.findDraft(1L)).thenReturn(draft);

        service.publish(TENANT_ID, "tmpl-a", "u1");

        // DRAFT 版本原地翻 PUBLISHED（不 insert 新行）
        assertThat(draft.getStatus()).isEqualTo(TemplateStatus.PUBLISHED);
        verify(versionMapper).updateById(draft);
        verify(versionMapper, never()).insert(any(RuleTemplateVersion.class));
        // 模板身份翻 PUBLISHED
        assertThat(tmpl.getStatus()).isEqualTo(TemplateStatus.PUBLISHED);
        verify(templateMapper).updateById(tmpl);
    }

    @Test
    void publish_noDraft_throws() {
        RuleTemplate tmpl = template(1L, TemplateStatus.DRAFT);
        when(templateMapper.findByTenantAndCode(TENANT_ID, "tmpl-a")).thenReturn(tmpl);
        when(versionMapper.findDraft(1L)).thenReturn(null);
        assertThatThrownBy(() -> service.publish(TENANT_ID, "tmpl-a", "u1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无 DRAFT 版本可发布");
    }

    @Test
    void publish_disabled_throws() {
        RuleTemplate tmpl = template(1L, TemplateStatus.DISABLED);
        when(templateMapper.findByTenantAndCode(TENANT_ID, "tmpl-a")).thenReturn(tmpl);
        assertThatThrownBy(() -> service.publish(TENANT_ID, "tmpl-a", "u1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DISABLED");
    }

    // ---------- disable ----------

    @Test
    void disable_publishedToDisabled() {
        RuleTemplate tmpl = template(1L, TemplateStatus.PUBLISHED);
        when(templateMapper.findByTenantAndCode(TENANT_ID, "tmpl-a")).thenReturn(tmpl);
        when(versionMapper.findLatestPublished(1L)).thenReturn(publishedVersion(1L, 3));

        service.disable(TENANT_ID, "tmpl-a", "u1");

        assertThat(tmpl.getStatus()).isEqualTo(TemplateStatus.DISABLED);
        verify(templateMapper).updateById(tmpl);
    }

    @Test
    void disable_notPublished_throws() {
        RuleTemplate tmpl = template(1L, TemplateStatus.DRAFT);
        when(templateMapper.findByTenantAndCode(TENANT_ID, "tmpl-a")).thenReturn(tmpl);
        assertThatThrownBy(() -> service.disable(TENANT_ID, "tmpl-a", "u1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("仅 PUBLISHED");
    }

    // ---------- enable ----------

    @Test
    void enable_disabledToPublished_updatesStatus() {
        RuleTemplate tmpl = template(1L, TemplateStatus.DISABLED);
        RuleTemplateVersion pub = publishedVersion(1L, 2);
        when(templateMapper.findByTenantAndCode(TENANT_ID, "tmpl-a")).thenReturn(tmpl);
        when(versionMapper.findLatestPublished(1L)).thenReturn(pub);

        service.enable(TENANT_ID, "tmpl-a", "u1");

        assertThat(tmpl.getStatus()).isEqualTo(TemplateStatus.PUBLISHED);
        verify(templateMapper).updateById(tmpl);
        // 审计事件
        ArgumentCaptor<OperationAuditedEvent> evtCaptor = ArgumentCaptor.forClass(OperationAuditedEvent.class);
        verify(eventPublisher).publishEvent(evtCaptor.capture());
        assertThat(evtCaptor.getValue().action().name()).isEqualTo("ENABLE");
    }

    @Test
    void enable_notDisabled_throws() {
        RuleTemplate tmpl = template(1L, TemplateStatus.PUBLISHED);
        when(templateMapper.findByTenantAndCode(TENANT_ID, "tmpl-a")).thenReturn(tmpl);
        assertThatThrownBy(() -> service.enable(TENANT_ID, "tmpl-a", "u1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("仅 DISABLED");
    }

    // ---------- get / getVersion ----------

    @Test
    void get_visibleByCode_returnsTemplate() {
        RuleTemplate tmpl = template(1L, TemplateStatus.PUBLISHED);
        when(templateMapper.findVisibleByCode(TENANT_ID, "tmpl-a")).thenReturn(tmpl);

        RuleTemplate result = service.get(TENANT_ID, "tmpl-a");
        assertThat(result.getCode()).isEqualTo("tmpl-a");
    }

    @Test
    void getVersion_latest_returnsTemplateDetail() {
        RuleTemplate tmpl = template(1L, TemplateStatus.PUBLISHED);
        RuleTemplateVersion ver = publishedVersion(1L, 3);
        when(templateMapper.findByTenantAndCode(TENANT_ID, "tmpl-a")).thenReturn(tmpl);
        when(versionMapper.findLatestPublished(1L)).thenReturn(ver);

        var detail = service.getVersion(TENANT_ID, "tmpl-a");
        assertThat(detail.template().getCode()).isEqualTo("tmpl-a");
        assertThat(detail.version().getVersion()).isEqualTo(3);
    }

    @Test
    void getVersion_draftStatus_returnsDraftVersion() {
        RuleTemplate tmpl = template(1L, TemplateStatus.DRAFT);
        RuleTemplateVersion draft = draftVersion(1L, 1);
        when(templateMapper.findByTenantAndCode(TENANT_ID, "tmpl-a")).thenReturn(tmpl);
        when(versionMapper.findDraft(1L)).thenReturn(draft);

        var detail = service.getVersion(TENANT_ID, "tmpl-a");
        assertThat(detail.template().getStatus()).isEqualTo(TemplateStatus.DRAFT);
        assertThat(detail.version().getStatus()).isEqualTo(TemplateStatus.DRAFT);
        assertThat(detail.version().getVersion()).isEqualTo(1);
    }

    @Test
    void getVersion_specific_returnsSpecifiedVersion() {
        RuleTemplate tmpl = template(1L, TemplateStatus.PUBLISHED);
        RuleTemplateVersion ver = publishedVersion(1L, 2);
        when(templateMapper.findByTenantAndCode(TENANT_ID, "tmpl-a")).thenReturn(tmpl);
        when(versionMapper.findByVersion(1L, 2)).thenReturn(ver);

        var detail = service.getVersion(TENANT_ID, "tmpl-a", 2);
        assertThat(detail.version().getVersion()).isEqualTo(2);
    }

    // ---------- instantiate ----------

    @Test
    void instantiate_valueCoercion_appliesCoercedValue() {
        RuleTemplate tmpl = template(7L, TemplateStatus.PUBLISHED);
        RuleTemplateVersion pub = publishedVersion(7L, 3);
        when(templateMapper.findVisibleByCode(TENANT_ID, "tmpl-a")).thenReturn(tmpl);
        when(versionMapper.findLatestPublished(7L)).thenReturn(pub);
        when(binder.bind(any(), any(), any())).thenReturn(skeleton());
        when(publishService.createDraft(eq(TENANT_ID), eq("scene-x"), eq("rule-1"), any(RuleContent.class), eq("u1")))
                .thenReturn(new DraftCreatedResult(11L, 22L, 1L, "DRAFT"));
        when(instantiationMapper.insert(any(RuleTemplateInstantiation.class))).thenReturn(1);

        DraftCreatedResult result = service.instantiate(TENANT_ID, "tmpl-a", "rule-1", "规则1",
                "scene-x", List.of("evt"), Map.of("threshold", 200), "u1");

        assertThat(result.ruleVersionId()).isEqualTo(22L);
        // 校验 createDraft 被调用
        verify(publishService).createDraft(eq(TENANT_ID), eq("scene-x"), eq("rule-1"), any(RuleContent.class), eq("u1"));
        // 溯源记录写入
        ArgumentCaptor<RuleTemplateInstantiation> riCaptor = ArgumentCaptor.forClass(RuleTemplateInstantiation.class);
        verify(instantiationMapper).insert(riCaptor.capture());
        RuleTemplateInstantiation ri = riCaptor.getValue();
        assertThat(ri.getTemplateId()).isEqualTo(7L);
        assertThat(ri.getTemplateVersionId()).isEqualTo(pub.getId());
        assertThat(ri.getRuleVersionId()).isEqualTo(22L);
        // coercValue 把 Integer 200 强转为 Long
        assertThat(ri.getSlotValues().get("threshold")).isEqualTo(200L);
    }

    @Test
    void instantiate_refSlot_validatedByResolver_andPassThrough() {
        RuleTemplate tmpl = template(7L, TemplateStatus.PUBLISHED);
        List<TemplateSlot> slots = List.of(
                new TemplateSlot("metricId", "指标", SlotKind.METRIC_REF, null, true, null));
        RuleTemplateVersion pub = new RuleTemplateVersion();
        pub.setId(70L);
        pub.setTemplateId(7L);
        pub.setVersion(3);
        pub.setBodySkeleton(skeleton());
        pub.setSlots(slots);
        pub.setBindings(List.of(new SlotBinding("metricId", null)));
        pub.setStatus(TemplateStatus.PUBLISHED);

        when(templateMapper.findVisibleByCode(TENANT_ID, "tmpl-a")).thenReturn(tmpl);
        when(versionMapper.findLatestPublished(7L)).thenReturn(pub);
        when(binder.bind(any(), any(), any())).thenReturn(skeleton());
        when(publishService.createDraft(eq(TENANT_ID), eq("scene-x"), eq("rule-1"), any(RuleContent.class), eq("u1")))
                .thenReturn(new DraftCreatedResult(11L, 22L, 1L, "DRAFT"));
        when(instantiationMapper.insert(any(RuleTemplateInstantiation.class))).thenReturn(1);

        service.instantiate(TENANT_ID, "tmpl-a", "rule-1", "规则1",
                "scene-x", List.of(), Map.of("metricId", "metric_001"), "u1");

        // REF resolver 被调用校验
        verify(metricRefResolver).validate(eq("metric_001"), any(TemplateSlot.class),
                argThat(ctx -> ctx.tenantId().equals(TENANT_ID) && ctx.sceneCode().equals("scene-x")));
        // 溯源中 REF 值 pass-through（原样 String "metric_001"）
        ArgumentCaptor<RuleTemplateInstantiation> riCaptor = ArgumentCaptor.forClass(RuleTemplateInstantiation.class);
        verify(instantiationMapper).insert(riCaptor.capture());
        assertThat(riCaptor.getValue().getSlotValues().get("metricId")).isEqualTo("metric_001");
    }

    @Test
    void instantiate_disabledTemplate_throws() {
        RuleTemplate tmpl = template(7L, TemplateStatus.DISABLED);
        when(templateMapper.findVisibleByCode(TENANT_ID, "tmpl-a")).thenReturn(tmpl);
        assertThatThrownBy(() -> service.instantiate(TENANT_ID, "tmpl-a", "rule-1", "规则1",
                "scene-x", List.of(), Map.of("threshold", 200), "u1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DISABLED");
    }

    @Test
    void instantiate_missingRequiredSlot_throws() {
        RuleTemplate tmpl = template(7L, TemplateStatus.PUBLISHED);
        RuleTemplateVersion pub = publishedVersion(7L, 3);
        when(templateMapper.findVisibleByCode(TENANT_ID, "tmpl-a")).thenReturn(tmpl);
        when(versionMapper.findLatestPublished(7L)).thenReturn(pub);

        assertThatThrownBy(() -> service.instantiate(TENANT_ID, "tmpl-a", "rule-1", "规则1",
                "scene-x", List.of(), Map.of(), "u1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少必填 slot");
    }

    @Test
    void instantiate_optionalSlotNotFilled_noError_notInCoercedValues() {
        RuleTemplate tmpl = template(7L, TemplateStatus.PUBLISHED);
        List<TemplateSlot> slots = List.of(
                new TemplateSlot("threshold", "阈值", SlotKind.VALUE, ValueDataType.LONG, true, null),
                new TemplateSlot("optionalTag", "可选标签", SlotKind.VALUE, ValueDataType.STRING, false, null));
        RuleTemplateVersion pub = new RuleTemplateVersion();
        pub.setId(70L);
        pub.setTemplateId(7L);
        pub.setVersion(3);
        pub.setBodySkeleton(skeleton());
        pub.setSlots(slots);
        pub.setBindings(List.of(new SlotBinding("threshold", null), new SlotBinding("optionalTag", null)));
        pub.setStatus(TemplateStatus.PUBLISHED);

        when(templateMapper.findVisibleByCode(TENANT_ID, "tmpl-a")).thenReturn(tmpl);
        when(versionMapper.findLatestPublished(7L)).thenReturn(pub);
        when(binder.bind(any(), any(), any())).thenReturn(skeleton());
        when(publishService.createDraft(eq(TENANT_ID), eq("scene-x"), eq("rule-1"), any(RuleContent.class), eq("u1")))
                .thenReturn(new DraftCreatedResult(11L, 22L, 1L, "DRAFT"));
        when(instantiationMapper.insert(any(RuleTemplateInstantiation.class))).thenReturn(1);

        // 只填必填 slot
        service.instantiate(TENANT_ID, "tmpl-a", "rule-1", "规则1",
                "scene-x", List.of(), Map.of("threshold", 200), "u1");

        // 可选 slot 未填，不报错
        ArgumentCaptor<RuleTemplateInstantiation> riCaptor = ArgumentCaptor.forClass(RuleTemplateInstantiation.class);
        verify(instantiationMapper).insert(riCaptor.capture());
        Map<String, Object> sv = riCaptor.getValue().getSlotValues();
        assertThat(sv).containsKey("threshold");
        assertThat(sv).doesNotContainKey("optionalTag");
    }

    @Test
    void instantiate_instantiationInsertFails_doesNotThrow() {
        RuleTemplate tmpl = template(7L, TemplateStatus.PUBLISHED);
        RuleTemplateVersion pub = publishedVersion(7L, 3);
        when(templateMapper.findVisibleByCode(TENANT_ID, "tmpl-a")).thenReturn(tmpl);
        when(versionMapper.findLatestPublished(7L)).thenReturn(pub);
        when(binder.bind(any(), any(), any())).thenReturn(skeleton());
        when(publishService.createDraft(eq(TENANT_ID), eq("scene-x"), eq("rule-1"), any(RuleContent.class), eq("u1")))
                .thenReturn(new DraftCreatedResult(11L, 22L, 1L, "DRAFT"));
        when(instantiationMapper.insert(any(RuleTemplateInstantiation.class)))
                .thenThrow(new RuntimeException("DB down"));

        // 溯源失败不影响主流程
        DraftCreatedResult result = service.instantiate(TENANT_ID, "tmpl-a", "rule-1", "规则1",
                "scene-x", List.of(), Map.of("threshold", 200), "u1");

        assertThat(result.ruleVersionId()).isEqualTo(22L);
    }

    @Test
    void instantiate_refSlotNoResolver_throws() {
        RuleTemplate tmpl = template(7L, TemplateStatus.PUBLISHED);
        List<TemplateSlot> slots = List.of(
                new TemplateSlot("ruleRef", "规则引用", SlotKind.RULE_REF, null, true, null));
        RuleTemplateVersion pub = new RuleTemplateVersion();
        pub.setId(70L);
        pub.setTemplateId(7L);
        pub.setVersion(3);
        pub.setBodySkeleton(skeleton());
        pub.setSlots(slots);
        pub.setBindings(List.of(new SlotBinding("ruleRef", null)));
        pub.setStatus(TemplateStatus.PUBLISHED);

        // ruleRefResolver.supports(RULE_REF) = false（模拟无匹配 resolver）
        when(ruleRefResolver.supports(SlotKind.RULE_REF)).thenReturn(false);
        when(templateMapper.findVisibleByCode(TENANT_ID, "tmpl-a")).thenReturn(tmpl);
        when(versionMapper.findLatestPublished(7L)).thenReturn(pub);

        assertThatThrownBy(() -> service.instantiate(TENANT_ID, "tmpl-a", "rule-1", "规则1",
                "scene-x", List.of(), Map.of("ruleRef", "r001"), "u1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SLOT_REF_UNSUPPORTED");
    }

    @Test
    void instantiate_templateNotFound_throws() {
        when(templateMapper.findVisibleByCode(TENANT_ID, "tmpl-x")).thenReturn(null);
        assertThatThrownBy(() -> service.instantiate(TENANT_ID, "tmpl-x", "rule-1", "规则1",
                "scene-x", List.of(), Map.of(), "u1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("模板不存在");
    }

    @Test
    void instantiate_noPublishedVersion_throws() {
        RuleTemplate tmpl = template(7L, TemplateStatus.PUBLISHED);
        when(templateMapper.findVisibleByCode(TENANT_ID, "tmpl-a")).thenReturn(tmpl);
        when(versionMapper.findLatestPublished(7L)).thenReturn(null);
        assertThatThrownBy(() -> service.instantiate(TENANT_ID, "tmpl-a", "rule-1", "规则1",
                "scene-x", List.of(), Map.of(), "u1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无 PUBLISHED 版本");
    }
}
