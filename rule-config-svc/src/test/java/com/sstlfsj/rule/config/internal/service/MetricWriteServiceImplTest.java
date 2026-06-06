package com.sstlfsj.rule.config.internal.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.sstlfsj.rule.config.api.service.MetricWriteService;
import com.sstlfsj.rule.config.api.service.MetricWriteService.MetricWriteCommand;
import com.sstlfsj.rule.config.api.service.MetricWriteService.RuleRef;
import com.sstlfsj.rule.config.internal.domain.AuditLog;
import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleVersion;
import com.sstlfsj.rule.config.internal.repository.AuditLogMapper;
import com.sstlfsj.rule.config.internal.repository.MetricDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleVersionMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

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
    }

    @Mock MetricDefinitionMapper metricDefinitionMapper;
    @Mock AuditLogMapper auditLogMapper;
    @Mock RuleVersionMapper ruleVersionMapper;
    @Mock RuleDefinitionMapper ruleDefinitionMapper;
    @Spy  ObjectMapper objectMapper = JsonMapper.builder().build();
    @InjectMocks MetricWriteServiceImpl sut;

    private static final Long TENANT = 1L;
    private static final String CODE = "user.age";
    private static final String ACTOR = "dev";

    private MetricWriteCommand cmd() {
        return new MetricWriteCommand("用户年龄", "ATTRIBUTE", "LONG", "{}", 60, false);
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
        assertThat(inserted.getStatus()).isEqualTo("ACTIVE");
        assertThat(inserted.getTenantId()).isEqualTo(TENANT);
        assertThat(inserted.getMetricCode()).isEqualTo(CODE);
        assertThat(inserted.getName()).isEqualTo("用户年龄");
        assertThat(inserted.getSourceType()).isEqualTo("ATTRIBUTE");
        assertThat(inserted.getDataType()).isEqualTo("LONG");
        assertThat(inserted.getCreatedBy()).isEqualTo(ACTOR);

        // 断言 audit_log 写入一次
        verify(auditLogMapper, times(1)).insert(any(AuditLog.class));
    }

    // ── update breakingChange=false ───────────────────────────────────────────

    @Test
    void update_nonBreaking_updatesActiveRowInPlace() {
        MetricDefinition active = activeRow(2);
        when(metricDefinitionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(active);

        int version = sut.update(TENANT, CODE, cmd(), false, ACTOR);

        assertThat(version).isEqualTo(2);
        verify(metricDefinitionMapper, times(1)).updateById(active);
        verify(metricDefinitionMapper, never()).insert((MetricDefinition) any());
        assertThat(active.getStatus()).isEqualTo("ACTIVE");
        verify(auditLogMapper, times(1)).insert(any(AuditLog.class));
    }

    // ── update breakingChange=true ────────────────────────────────────────────

    @Test
    void update_breaking_supersedesOldRowAndInsertsNewVersion() {
        MetricDefinition active = activeRow(2);
        when(metricDefinitionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(active);

        doAnswer(inv -> {
            MetricDefinition m = inv.getArgument(0);
            m.setId(200L);
            return 1;
        }).when(metricDefinitionMapper).insert(any(MetricDefinition.class));

        int version = sut.update(TENANT, CODE, cmd(), true, ACTOR);

        assertThat(version).isEqualTo(3);

        // 旧行改为 SUPERSEDED
        verify(metricDefinitionMapper, times(1)).updateById(active);
        assertThat(active.getStatus()).isEqualTo("SUPERSEDED");

        // 新行插入 version=3 ACTIVE
        ArgumentCaptor<MetricDefinition> captor = ArgumentCaptor.forClass(MetricDefinition.class);
        verify(metricDefinitionMapper, times(1)).insert(captor.capture());
        MetricDefinition newRow = captor.getValue();
        assertThat(newRow.getVersion()).isEqualTo(3);
        assertThat(newRow.getStatus()).isEqualTo("ACTIVE");

        verify(auditLogMapper, times(1)).insert(any(AuditLog.class));
    }

    // ── update 无 ACTIVE 行 ───────────────────────────────────────────────────

    @Test
    void update_noActiveRow_throwsIllegalArgumentException() {
        when(metricDefinitionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> sut.update(TENANT, CODE, cmd(), false, ACTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(CODE);
    }

    // ── findReferencingRules ──────────────────────────────────────────────────

    @Test
    void findReferencingRules_returnsOnlyMatchingRules() {
        // rule_definition：两条属于 TENANT
        RuleDefinition rd1 = ruleDefinition(101L, "risk.transfer", "转账风控");
        RuleDefinition rd2 = ruleDefinition(102L, "risk.login", "登录风控");

        // rule_version：
        //   rv1 属于 rd1，ACTIVE，metric_dependencies 含 {account.age,1} → 应被选中
        //   rv2 属于 rd1，ACTIVE，metric_dependencies 含 {account.age,2} → 版本不匹配，排除
        //   rv3 属于 rd2，ACTIVE，metric_dependencies 含 {account.age,1} → 应被选中
        //   rv4 属于 rd2，ACTIVE，metric_dependencies 为空 → 排除
        RuleVersion rv1 = ruleVersion(1001L, 101L, "[{\"metricCode\":\"account.age\",\"metricVersion\":1}]");
        RuleVersion rv2 = ruleVersion(1002L, 101L, "[{\"metricCode\":\"account.age\",\"metricVersion\":2}]");
        RuleVersion rv3 = ruleVersion(1003L, 102L, "[{\"metricCode\":\"account.age\",\"metricVersion\":1},{\"metricCode\":\"user.level\",\"metricVersion\":1}]");
        RuleVersion rv4 = ruleVersion(1004L, 102L, "[]");

        // mock：先查该 tenant 下所有 ruleDefinition id
        when(ruleDefinitionMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(rd1, rd2));
        // mock：再查 ACTIVE rule_version
        when(ruleVersionMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(rv1, rv2, rv3, rv4));

        List<RuleRef> result = sut.findReferencingRules(TENANT, "account.age", 1);

        // 只有 rv1、rv3 匹配
        assertThat(result).hasSize(2);
        assertThat(result).extracting(RuleRef::ruleVersionId)
                .containsExactlyInAnyOrder(1001L, 1003L);
        assertThat(result).extracting(RuleRef::ruleDefinitionId)
                .containsExactlyInAnyOrder(101L, 102L);
        // 验证 code/name 正确从 ruleDefinition 携带
        assertThat(result).anySatisfy(ref ->
                assertThat(ref.ruleCode()).isEqualTo("risk.transfer"));
        assertThat(result).anySatisfy(ref ->
                assertThat(ref.ruleName()).isEqualTo("登录风控"));
    }

    @Test
    void findReferencingRules_noActiveRules_returnsEmpty() {
        when(ruleDefinitionMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of());

        List<RuleRef> result = sut.findReferencingRules(TENANT, "account.age", 1);

        assertThat(result).isEmpty();
        // 无 ruleDefinition 时不查 ruleVersion
        verifyNoInteractions(ruleVersionMapper);
    }

    @Test
    void findReferencingRules_malformedDependenciesJson_treatedAsNoMatch() {
        RuleDefinition rd = ruleDefinition(101L, "risk.transfer", "转账风控");
        // metric_dependencies 为非法 JSON，应静默忽略，不抛异常
        RuleVersion rv = ruleVersion(1001L, 101L, "not-valid-json");

        when(ruleDefinitionMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(rd));
        when(ruleVersionMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(rv));

        List<RuleRef> result = sut.findReferencingRules(TENANT, "account.age", 1);

        assertThat(result).isEmpty();
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
        m.setStatus("ACTIVE");
        return m;
    }

    private RuleDefinition ruleDefinition(Long id, String code, String name) {
        RuleDefinition rd = new RuleDefinition();
        rd.setId(id);
        rd.setTenantId(TENANT);
        rd.setCode(code);
        rd.setName(name);
        return rd;
    }

    private RuleVersion ruleVersion(Long id, Long ruleDefinitionId, String metricDependencies) {
        RuleVersion rv = new RuleVersion();
        rv.setId(id);
        rv.setRuleDefinitionId(ruleDefinitionId);
        rv.setStatus("ACTIVE");
        rv.setMetricDependencies(metricDependencies);
        return rv;
    }
}
