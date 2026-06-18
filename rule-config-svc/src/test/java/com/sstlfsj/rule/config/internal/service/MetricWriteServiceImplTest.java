package com.sstlfsj.rule.config.internal.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.sstlfsj.rule.config.api.service.MetricWriteService;
import com.sstlfsj.rule.config.api.service.MetricWriteService.MetricWriteCommand;
import com.sstlfsj.rule.config.api.service.MetricWriteService.RuleRef;
import com.sstlfsj.rule.config.api.service.UsageCount;
import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import com.sstlfsj.rule.config.internal.domain.MetricStatus;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleDefinitionStatus;
import com.sstlfsj.rule.config.internal.domain.RuleVersion;
import com.sstlfsj.rule.config.internal.domain.RuleVersionStatus;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.event.MetricChangedSnapshot;
import com.sstlfsj.rule.config.internal.event.OperationAuditedEvent;
import com.sstlfsj.rule.config.internal.repository.MetricDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleVersionMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import com.sstlfsj.rule.kernel.api.model.MetricDependency;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import tools.jackson.core.JacksonException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** MetricWriteServiceImpl 单元测试（mock mapper，不依赖 Spring 容器）。 */
@ExtendWith(MockitoExtension.class)
class MetricWriteServiceImplTest {

    /**
     * LambdaQueryWrapper 在执行 .eq(Entity::getField, val) 时依赖 MP TableInfo 缓存。
     * 纯 Mockito 环境无 Spring 容器初始化，这里手动注册所有用到 LambdaQueryWrapper 的实体。
     */
    @BeforeAll
    static void initMybatisPlusTableInfo() {
        MybatisConfiguration cfg = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(cfg, "");
        TableInfoHelper.initTableInfo(assistant, MetricDefinition.class);
        TableInfoHelper.initTableInfo(assistant, RuleDefinition.class);
        TableInfoHelper.initTableInfo(assistant, RuleVersion.class);
        TableInfoHelper.initTableInfo(assistant, SceneDef.class);
    }

    @Mock MetricDefinitionMapper metricDefinitionMapper;
    @Mock RuleVersionMapper ruleVersionMapper;
    @Mock RuleDefinitionMapper ruleDefinitionMapper;
    @Mock SceneMapper sceneMapper;
    @Mock ApplicationEventPublisher eventPublisher;
    @Spy  ObjectMapper objectMapper = JsonMapper.builder().build();
    @Spy  com.sstlfsj.rule.config.internal.MetricProperties metricProperties =
            new com.sstlfsj.rule.config.internal.MetricProperties();
    @InjectMocks MetricWriteServiceImpl sut;

    private static final Long TENANT = 1L;
    private static final String CODE = "user.age";
    private static final String ACTOR = "dev";

    private MetricWriteCommand cmd() {
        return new MetricWriteCommand("用户年龄", "ATTRIBUTE", "LONG", Map.of(), 60, false);
    }

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    void create_insertsVersion1ActiveRow() {
        // mapper.insert 回填 id（模拟 MyBatis-Plus AUTO 策略通过 keyProperty 回填）
        doAnswer(inv -> {
            MetricDefinition m = inv.getArgument(0);
            m.setId(100L);
            return 1;
        }).when(metricDefinitionMapper).insert(any(MetricDefinition.class));

        Long id = sut.create(TENANT, CODE, cmd(), ACTOR);

        assertThat(id).isEqualTo(100L);

        // 断言 insert 被调一次，且插入行字段正确
        ArgumentCaptor<MetricDefinition> captor = ArgumentCaptor.forClass(MetricDefinition.class);
        verify(metricDefinitionMapper, times(1)).insert(captor.capture());
        MetricDefinition inserted = captor.getValue();
        assertThat(inserted.getVersion()).isEqualTo(1);
        assertThat(inserted.getStatus()).isEqualTo(MetricStatus.ACTIVE);
        assertThat(inserted.getTenantId()).isEqualTo(TENANT);
        assertThat(inserted.getMetricCode()).isEqualTo(CODE);
        assertThat(inserted.getName()).isEqualTo("用户年龄");
        assertThat(inserted.getSourceType()).isEqualTo("ATTRIBUTE");
        assertThat(inserted.getDataType()).isEqualTo("LONG");
        assertThat(inserted.getCreatedBy()).isEqualTo(ACTOR);

        // 断言 audit_log 写入一次，CREATE 类 before/after 为同一 typed 快照（breaking=null）
        ArgumentCaptor<OperationAuditedEvent> auditCaptor = ArgumentCaptor.forClass(OperationAuditedEvent.class);
        verify(eventPublisher, times(1)).publishEvent(auditCaptor.capture());
        OperationAuditedEvent audit = auditCaptor.getValue();
        assertThat(audit.action()).isEqualTo(com.sstlfsj.rule.config.internal.domain.AuditAction.CREATE);
        assertThat(audit.beforeSnapshot()).isSameAs(audit.afterSnapshot());
        assertThat(audit.afterSnapshot()).isEqualTo(new MetricChangedSnapshot(CODE, 1, null));
    }

    @Test
    void create_nullCacheTtl_usesMetricPropertiesDefault() {
        // cacheTtlSeconds 为 null 时回退到 MetricProperties.defaultCacheTtlSeconds（默认 60）
        doAnswer(inv -> {
            MetricDefinition m = inv.getArgument(0);
            m.setId(200L);
            return 1;
        }).when(metricDefinitionMapper).insert(any(MetricDefinition.class));

        MetricWriteCommand cmdNullTtl = new MetricWriteCommand(
                "用户年龄", "ATTRIBUTE", "LONG", Map.of(), null, false);
        sut.create(TENANT, CODE, cmdNullTtl, ACTOR);

        ArgumentCaptor<MetricDefinition> captor = ArgumentCaptor.forClass(MetricDefinition.class);
        verify(metricDefinitionMapper, times(1)).insert(captor.capture());
        assertThat(captor.getValue().getCacheTtlSeconds()).isEqualTo(60);
    }

    // ── 枚举列 app 校验（DB ENUM 去除后由 app 兜底）─────────────────────────────

    @Test
    void create_dataTypeDecimal_succeeds() {
        // DECIMAL 为新增合法 data_type，应通过校验并正常插入
        doAnswer(inv -> {
            MetricDefinition m = inv.getArgument(0);
            m.setId(400L);
            return 1;
        }).when(metricDefinitionMapper).insert(any(MetricDefinition.class));

        MetricWriteCommand decimalCmd =
                new MetricWriteCommand("金额", "ATTRIBUTE", "DECIMAL", Map.of(), 60, false);
        Long id = sut.create(TENANT, CODE, decimalCmd, ACTOR);

        assertThat(id).isEqualTo(400L);
        ArgumentCaptor<MetricDefinition> captor = ArgumentCaptor.forClass(MetricDefinition.class);
        verify(metricDefinitionMapper, times(1)).insert(captor.capture());
        assertThat(captor.getValue().getDataType()).isEqualTo("DECIMAL");
    }

    @Test
    void create_invalidDataType_throwsIllegalArgumentException() {
        MetricWriteCommand badCmd =
                new MetricWriteCommand("x", "ATTRIBUTE", "FOO", Map.of(), 60, false);

        assertThatThrownBy(() -> sut.create(TENANT, CODE, badCmd, ACTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("data_type");
        verifyNoInteractions(metricDefinitionMapper);
    }

    @Test
    void create_invalidSourceType_throwsIllegalArgumentException() {
        MetricWriteCommand badCmd =
                new MetricWriteCommand("x", "BOGUS", "LONG", Map.of(), 60, false);

        assertThatThrownBy(() -> sut.create(TENANT, CODE, badCmd, ACTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source_type");
        verifyNoInteractions(metricDefinitionMapper);
    }

    @Test
    void update_invalidDataType_throwsBeforeLookup() {
        // update 也校验：非法值在查 ACTIVE 行之前就抛出
        MetricWriteCommand badCmd =
                new MetricWriteCommand("x", "ATTRIBUTE", "FOO", Map.of(), 60, false);

        assertThatThrownBy(() -> sut.update(TENANT, CODE, badCmd, false, ACTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("data_type");
        verifyNoInteractions(metricDefinitionMapper);
    }

    // ── update breakingChange=false ───────────────────────────────────────────

    @Test
    void update_nonBreaking_updatesActiveRowInPlace() {
        MetricDefinition active = activeRow(2);
        when(metricDefinitionMapper.findActiveByCode(any(), any())).thenReturn(active);

        int version = sut.update(TENANT, CODE, cmd(), false, ACTOR);

        assertThat(version).isEqualTo(2);
        verify(metricDefinitionMapper, times(1)).updateById(active);
        verify(metricDefinitionMapper, never()).insert((MetricDefinition) any());
        assertThat(active.getStatus()).isEqualTo(MetricStatus.ACTIVE);
        // UPDATE 非创建：before 仍为 null，after 为 typed 快照（breaking=false）
        ArgumentCaptor<OperationAuditedEvent> auditCaptor = ArgumentCaptor.forClass(OperationAuditedEvent.class);
        verify(eventPublisher, times(1)).publishEvent(auditCaptor.capture());
        OperationAuditedEvent audit = auditCaptor.getValue();
        assertThat(audit.action()).isEqualTo(com.sstlfsj.rule.config.internal.domain.AuditAction.UPDATE);
        assertThat(audit.beforeSnapshot()).isNull();
        assertThat(audit.afterSnapshot()).isEqualTo(new MetricChangedSnapshot(CODE, 2, false));
    }

    // ── update breakingChange=true ────────────────────────────────────────────

    @Test
    void update_breaking_supersedesOldRowAndInsertsNewVersion() {
        MetricDefinition active = activeRow(2);
        when(metricDefinitionMapper.findActiveByCode(any(), any())).thenReturn(active);

        doAnswer(inv -> {
            MetricDefinition m = inv.getArgument(0);
            m.setId(200L);
            return 1;
        }).when(metricDefinitionMapper).insert(any(MetricDefinition.class));

        int version = sut.update(TENANT, CODE, cmd(), true, ACTOR);

        assertThat(version).isEqualTo(3);

        // 旧行改为 SUPERSEDED
        verify(metricDefinitionMapper, times(1)).updateById(active);
        assertThat(active.getStatus()).isEqualTo(MetricStatus.SUPERSEDED);

        // 新行插入 version=3 ACTIVE
        ArgumentCaptor<MetricDefinition> captor = ArgumentCaptor.forClass(MetricDefinition.class);
        verify(metricDefinitionMapper, times(1)).insert(captor.capture());
        MetricDefinition newRow = captor.getValue();
        assertThat(newRow.getVersion()).isEqualTo(3);
        assertThat(newRow.getStatus()).isEqualTo(MetricStatus.ACTIVE);

        // 升版 UPDATE：before 为 null，after 为 typed 快照（breaking=true，version=3）
        ArgumentCaptor<OperationAuditedEvent> auditCaptor = ArgumentCaptor.forClass(OperationAuditedEvent.class);
        verify(eventPublisher, times(1)).publishEvent(auditCaptor.capture());
        OperationAuditedEvent audit = auditCaptor.getValue();
        assertThat(audit.beforeSnapshot()).isNull();
        assertThat(audit.afterSnapshot()).isEqualTo(new MetricChangedSnapshot(CODE, 3, true));
    }

    // ── update breakingChange=false 但 sourceType/dataType 变更 → 强制升版 ──────

    @Test
    void update_nonBreaking_butSourceTypeChanged_forcesNewVersion() {
        // 当前 ACTIVE：sourceType=ATTRIBUTE
        MetricDefinition active = activeRow(1);
        active.setSourceType("ATTRIBUTE");
        active.setDataType("LONG");
        when(metricDefinitionMapper.findActiveByCode(any(), any())).thenReturn(active);
        doAnswer(inv -> {
            MetricDefinition m = inv.getArgument(0);
            m.setId(300L);
            return 1;
        }).when(metricDefinitionMapper).insert(any(MetricDefinition.class));

        // cmd 将 sourceType 改为 SQL_AGGREGATE（合法值），即使 breakingChange=false
        MetricWriteCommand changedCmd = new MetricWriteCommand("用户年龄", "SQL_AGGREGATE", "LONG", Map.of(), 60, false);
        int version = sut.update(TENANT, CODE, changedCmd, false, ACTOR);

        // 应走升版路径：version=2，旧行 SUPERSEDED
        assertThat(version).isEqualTo(2);
        assertThat(active.getStatus()).isEqualTo(MetricStatus.SUPERSEDED);
        ArgumentCaptor<MetricDefinition> captor = ArgumentCaptor.forClass(MetricDefinition.class);
        verify(metricDefinitionMapper, times(1)).insert(captor.capture());
        assertThat(captor.getValue().getVersion()).isEqualTo(2);
        assertThat(captor.getValue().getStatus()).isEqualTo(MetricStatus.ACTIVE);
        assertThat(captor.getValue().getSourceType()).isEqualTo("SQL_AGGREGATE");
    }

    @Test
    void update_nonBreaking_butDataTypeChanged_forcesNewVersion() {
        // 当前 ACTIVE：dataType=LONG
        MetricDefinition active = activeRow(1);
        active.setSourceType("ATTRIBUTE");
        active.setDataType("LONG");
        when(metricDefinitionMapper.findActiveByCode(any(), any())).thenReturn(active);
        doAnswer(inv -> {
            MetricDefinition m = inv.getArgument(0);
            m.setId(301L);
            return 1;
        }).when(metricDefinitionMapper).insert(any(MetricDefinition.class));

        // cmd 将 dataType 改为 DOUBLE
        MetricWriteCommand changedCmd = new MetricWriteCommand("用户年龄", "ATTRIBUTE", "DOUBLE", Map.of(), 60, false);
        int version = sut.update(TENANT, CODE, changedCmd, false, ACTOR);

        assertThat(version).isEqualTo(2);
        assertThat(active.getStatus()).isEqualTo(MetricStatus.SUPERSEDED);
        // 补：用 ArgumentCaptor 断言新行 dataType == "DOUBLE"（与 sourceType 用例对称）
        ArgumentCaptor<MetricDefinition> captor = ArgumentCaptor.forClass(MetricDefinition.class);
        verify(metricDefinitionMapper, times(1)).insert(captor.capture());
        assertThat(captor.getValue().getVersion()).isEqualTo(2);
        assertThat(captor.getValue().getStatus()).isEqualTo(MetricStatus.ACTIVE);
        assertThat(captor.getValue().getDataType()).isEqualTo("DOUBLE");
    }

    @Test
    void update_nonBreaking_sameSourceTypeAndDataType_updatesInPlace() {
        // sourceType/dataType 未变，breakingChange=false → 仍原地更新
        MetricDefinition active = activeRow(1);
        active.setSourceType("ATTRIBUTE");
        active.setDataType("LONG");
        when(metricDefinitionMapper.findActiveByCode(any(), any())).thenReturn(active);

        // cmd 只改 name，sourceType/dataType 不变
        MetricWriteCommand sameTypeCmd = new MetricWriteCommand("新名称", "ATTRIBUTE", "LONG", Map.of(), 120, false);
        int version = sut.update(TENANT, CODE, sameTypeCmd, false, ACTOR);

        assertThat(version).isEqualTo(1);
        verify(metricDefinitionMapper, times(1)).updateById(active);
        verify(metricDefinitionMapper, never()).insert((MetricDefinition) any());
        // 补：与既有原地更新用例对称，确认 audit_log 写入一次
        verify(eventPublisher, times(1)).publishEvent(any(OperationAuditedEvent.class));
    }

    // ── update 无 ACTIVE 行 ───────────────────────────────────────────────────

    @Test
    void update_noActiveRow_throwsIllegalArgumentException() {
        when(metricDefinitionMapper.findActiveByCode(any(), any())).thenReturn(null);

        assertThatThrownBy(() -> sut.update(TENANT, CODE, cmd(), false, ACTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(CODE);
    }

    // ── findReferencingRules ──────────────────────────────────────────────────

    @Test
    void findReferencingRules_returnsOnlyMatchingRules() {
        // rule_definition：两条属于 TENANT，分属不同 scene
        RuleDefinition rd1 = ruleDefinition(101L, "risk.transfer", "转账风控", 10L, "PUBLISHED");
        RuleDefinition rd2 = ruleDefinition(102L, "risk.login", "登录风控", 11L, "DISABLED");

        // scene：10 → transfer，11 → login
        SceneDef sc1 = scene(10L, "risk.transfer");
        SceneDef sc2 = scene(11L, "risk.login");

        // rule_version（mock 只返回裁列后仍包含的三个字段：id/ruleDefinitionId/metricDependencies）：
        //   rv1 属于 rd1，含 {account.age,1} → 应被选中
        //   rv2 属于 rd1，含 {account.age,2} → 版本不匹配，排除
        //   rv3 属于 rd2（rd.status=DISABLED），含 {account.age,1} → 仍应被选中（口径按 rv.status=ACTIVE）
        //   rv4 属于 rd2，dependencies 为空 → 排除
        RuleVersion rv1 = ruleVersion(1001L, 101L, List.of(new MetricDependency("account.age", 1)));
        RuleVersion rv2 = ruleVersion(1002L, 101L, List.of(new MetricDependency("account.age", 2)));
        RuleVersion rv3 = ruleVersion(1003L, 102L,
                List.of(new MetricDependency("account.age", 1), new MetricDependency("user.level", 1)));
        RuleVersion rv4 = ruleVersion(1004L, 102L, List.of());

        when(ruleDefinitionMapper.findByTenant(any()))
                .thenReturn(List.of(rd1, rd2));
        when(ruleVersionMapper.findActiveByRuleDefIds(any()))
                .thenReturn(List.of(rv1, rv2, rv3, rv4));
        // scene 批量查询
        when(sceneMapper.findByIds(any()))
                .thenReturn(List.of(sc1, sc2));

        List<RuleRef> result = sut.findReferencingRules(TENANT, "account.age", 1);

        // 只有 rv1（rd1）、rv3（rd2）匹配
        assertThat(result).hasSize(2);
        assertThat(result).extracting(RuleRef::ruleDefinitionId)
                .containsExactlyInAnyOrder(101L, 102L);
        // 验证 code/name/sceneCode/status 正确组装
        assertThat(result).anySatisfy(ref -> {
            assertThat(ref.ruleCode()).isEqualTo("risk.transfer");
            assertThat(ref.sceneCode()).isEqualTo("risk.transfer");
            assertThat(ref.status()).isEqualTo("PUBLISHED");
        });
        assertThat(result).anySatisfy(ref -> {
            assertThat(ref.ruleName()).isEqualTo("登录风控");
            assertThat(ref.sceneCode()).isEqualTo("risk.login");
            // rd.status=DISABLED 的规则仍出现（口径对齐 eval，按 rv.status 收集）
            assertThat(ref.status()).isEqualTo("DISABLED");
        });
    }

    @Test
    void findReferencingRules_differentMetricCode_notIncluded() {
        // 纯反例：规则只引用 {user.level,1}，查 account.age/1 时不应出现
        RuleDefinition rd = ruleDefinition(101L, "risk.transfer", "转账风控", 10L, "PUBLISHED");
        RuleVersion rv = ruleVersion(1001L, 101L, List.of(new MetricDependency("user.level", 1)));

        when(ruleDefinitionMapper.findByTenant(any()))
                .thenReturn(List.of(rd));
        when(ruleVersionMapper.findActiveByRuleDefIds(any()))
                .thenReturn(List.of(rv));
        when(sceneMapper.findByIds(any()))
                .thenReturn(List.of(scene(10L, "risk.transfer")));

        List<RuleRef> result = sut.findReferencingRules(TENANT, "account.age", 1);

        assertThat(result).isEmpty();
    }

    @Test
    void findReferencingRules_noActiveRules_returnsEmpty() {
        when(ruleDefinitionMapper.findByTenant(any()))
                .thenReturn(List.of());

        List<RuleRef> result = sut.findReferencingRules(TENANT, "account.age", 1);

        assertThat(result).isEmpty();
        // 无 ruleDefinition 时不查 ruleVersion
        verifyNoInteractions(ruleVersionMapper);
    }

    @Test
    void findReferencingRules_nullDependencies_treatedAsNoMatch() {
        RuleDefinition rd = ruleDefinition(101L, "risk.transfer", "转账风控", 10L, "PUBLISHED");
        // metric_dependencies 为 null（列空/未设置），containsDependency 视为不匹配，不抛异常
        RuleVersion rv = ruleVersion(1001L, 101L, null);

        when(ruleDefinitionMapper.findByTenant(any()))
                .thenReturn(List.of(rd));
        when(ruleVersionMapper.findActiveByRuleDefIds(any()))
                .thenReturn(List.of(rv));
        when(sceneMapper.findByIds(any()))
                .thenReturn(List.of(scene(10L, "risk.transfer")));

        List<RuleRef> result = sut.findReferencingRules(TENANT, "account.age", 1);

        assertThat(result).isEmpty();
    }

    // ── findRulesReferencingMetric（版本无关，按规则去重）──────────────────────

    @Test
    void findRulesReferencingMetric_dedupsByRuleAcrossVersions() {
        // rd1：两条 ACTIVE rule_version 都引用 account.age（不同版本），应只出一条 RuleRef
        // rd2：一条引用 account.age v3，单独出一条
        RuleDefinition rd1 = ruleDefinition(101L, "risk.transfer", "转账风控", 10L, "PUBLISHED");
        RuleDefinition rd2 = ruleDefinition(102L, "risk.login", "登录风控", 11L, "DISABLED");

        RuleVersion rv1 = ruleVersion(1001L, 101L, List.of(new MetricDependency("account.age", 1)));
        RuleVersion rv2 = ruleVersion(1002L, 101L, List.of(new MetricDependency("account.age", 2)));
        RuleVersion rv3 = ruleVersion(1003L, 102L, List.of(new MetricDependency("account.age", 3)));

        when(ruleDefinitionMapper.findByTenant(any())).thenReturn(List.of(rd1, rd2));
        when(ruleVersionMapper.findActiveByRuleDefIds(any())).thenReturn(List.of(rv1, rv2, rv3));
        when(sceneMapper.findByIds(any()))
                .thenReturn(List.of(scene(10L, "risk.transfer"), scene(11L, "risk.login")));

        List<RuleRef> result = sut.findRulesReferencingMetric(TENANT, "account.age");

        // 跨版本去重：rd1 只一条；共两条规则
        assertThat(result).hasSize(2);
        assertThat(result).extracting(RuleRef::ruleDefinitionId)
                .containsExactlyInAnyOrder(101L, 102L);
        assertThat(result).anySatisfy(ref -> {
            assertThat(ref.ruleCode()).isEqualTo("risk.transfer");
            assertThat(ref.sceneCode()).isEqualTo("risk.transfer");
            assertThat(ref.status()).isEqualTo("PUBLISHED");
        });
        assertThat(result).anySatisfy(ref -> {
            assertThat(ref.ruleName()).isEqualTo("登录风控");
            assertThat(ref.sceneCode()).isEqualTo("risk.login");
            assertThat(ref.status()).isEqualTo("DISABLED");
        });
    }

    @Test
    void findRulesReferencingMetric_differentMetricCode_notIncluded() {
        RuleDefinition rd = ruleDefinition(101L, "risk.transfer", "转账风控", 10L, "PUBLISHED");
        RuleVersion rv = ruleVersion(1001L, 101L, List.of(new MetricDependency("user.level", 1)));

        when(ruleDefinitionMapper.findByTenant(any())).thenReturn(List.of(rd));
        when(ruleVersionMapper.findActiveByRuleDefIds(any())).thenReturn(List.of(rv));
        when(sceneMapper.findByIds(any())).thenReturn(List.of(scene(10L, "risk.transfer")));

        List<RuleRef> result = sut.findRulesReferencingMetric(TENANT, "account.age");

        assertThat(result).isEmpty();
    }

    @Test
    void findRulesReferencingMetric_noActiveRules_returnsEmpty() {
        when(ruleDefinitionMapper.findByTenant(any())).thenReturn(List.of());

        List<RuleRef> result = sut.findRulesReferencingMetric(TENANT, "account.age");

        assertThat(result).isEmpty();
        verifyNoInteractions(ruleVersionMapper);
    }

    // ── countRuleUsages（版本无关批量计数）────────────────────────────────────

    @Test
    void countRuleUsages_aggregatesPerMetricCode_dedupPerRule() {
        RuleDefinition rd = ruleDefinition(101L, "risk.transfer", "转账", 10L, "PUBLISHED");
        // rv1 引用 account.age v1；rv2 引用 account.age v2 + user.level v1；
        // 同一规则跨版本引用同 metric → 按 metricCode 去重（account.age 计 2 条规则版本，user.level 计 1）
        RuleVersion rv1 = ruleVersion(1001L, 101L, List.of(new MetricDependency("account.age", 1)));
        RuleVersion rv2 = ruleVersion(1002L, 101L,
                List.of(new MetricDependency("account.age", 2), new MetricDependency("user.level", 1)));
        when(ruleDefinitionMapper.findByTenant(any())).thenReturn(List.of(rd));
        when(ruleVersionMapper.findActiveByRuleDefIds(any())).thenReturn(List.of(rv1, rv2));

        List<UsageCount> counts = sut.countRuleUsages(1L);

        assertThat(counts).anySatisfy(c -> { assertThat(c.code()).isEqualTo("account.age"); assertThat(c.count()).isEqualTo(2); });
        assertThat(counts).anySatisfy(c -> { assertThat(c.code()).isEqualTo("user.level"); assertThat(c.count()).isEqualTo(1); });
    }

    // ── sensitive 列（D71 读时脱敏声明位）─────────────────────────────────────

    @Test
    void create_persistsSensitiveFlag() {
        MetricWriteCommand sensitiveCmd =
                new MetricWriteCommand("身份证号", "ATTRIBUTE", "STRING", Map.of(), 60, false, true);
        ArgumentCaptor<MetricDefinition> captor = ArgumentCaptor.forClass(MetricDefinition.class);
        doAnswer(inv -> {
            inv.<MetricDefinition>getArgument(0).setId(500L);
            return 1;
        }).when(metricDefinitionMapper).insert(captor.capture());

        sut.create(TENANT, "user.idno", sensitiveCmd, ACTOR);

        assertThat(captor.getValue().getSensitive()).isTrue();
    }

    @Test
    void create_defaultsSensitiveFalse() {
        ArgumentCaptor<MetricDefinition> captor = ArgumentCaptor.forClass(MetricDefinition.class);
        doAnswer(inv -> {
            inv.<MetricDefinition>getArgument(0).setId(501L);
            return 1;
        }).when(metricDefinitionMapper).insert(captor.capture());

        // 6 参兼容构造器 sensitive 默认 false
        sut.create(TENANT, "user.age",
                new MetricWriteCommand("用户年龄", "ATTRIBUTE", "LONG", Map.of(), 60, false), ACTOR);

        assertThat(captor.getValue().getSensitive()).isFalse();
    }

    // ── 辅助 ──────────────────────────────────────────────────────────────────

    private MetricDefinition activeRow(int version) {
        MetricDefinition m = new MetricDefinition();
        m.setId(10L);
        m.setTenantId(TENANT);
        m.setMetricCode(CODE);
        m.setVersion(version);
        m.setName("旧名称");
        m.setSourceType("ATTRIBUTE");
        m.setDataType("LONG");
        m.setStatus(MetricStatus.ACTIVE);
        return m;
    }

    private RuleDefinition ruleDefinition(Long id, String code, String name, Long sceneId, String status) {
        RuleDefinition rd = new RuleDefinition();
        rd.setId(id);
        rd.setTenantId(TENANT);
        rd.setCode(code);
        rd.setName(name);
        rd.setSceneId(sceneId);
        rd.setStatus(RuleDefinitionStatus.valueOf(status));
        return rd;
    }

    private SceneDef scene(Long id, String code) {
        SceneDef sc = new SceneDef();
        sc.setId(id);
        sc.setCode(code);
        return sc;
    }

    private RuleVersion ruleVersion(Long id, Long ruleDefinitionId, List<MetricDependency> metricDependencies) {
        RuleVersion rv = new RuleVersion();
        rv.setId(id);
        rv.setRuleDefinitionId(ruleDefinitionId);
        rv.setStatus(RuleVersionStatus.ACTIVE);
        rv.setMetricDependencies(metricDependencies);
        return rv;
    }
}
