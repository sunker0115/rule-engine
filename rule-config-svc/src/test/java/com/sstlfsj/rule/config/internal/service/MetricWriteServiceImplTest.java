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
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.repository.AuditLogMapper;
import com.sstlfsj.rule.config.internal.repository.MetricDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleVersionMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
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
        TableInfoHelper.initTableInfo(assistant, SceneDef.class);
    }

    @Mock MetricDefinitionMapper metricDefinitionMapper;
    @Mock AuditLogMapper auditLogMapper;
    @Mock RuleVersionMapper ruleVersionMapper;
    @Mock RuleDefinitionMapper ruleDefinitionMapper;
    @Mock SceneMapper sceneMapper;
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

    // ── update breakingChange=false 但 sourceType/dataType 变更 → 强制升版 ──────

    @Test
    void update_nonBreaking_butSourceTypeChanged_forcesNewVersion() {
        // 当前 ACTIVE：sourceType=ATTRIBUTE
        MetricDefinition active = activeRow(1);
        active.setSourceType("ATTRIBUTE");
        active.setDataType("LONG");
        when(metricDefinitionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(active);
        doAnswer(inv -> {
            MetricDefinition m = inv.getArgument(0);
            m.setId(300L);
            return 1;
        }).when(metricDefinitionMapper).insert(any(MetricDefinition.class));

        // cmd 将 sourceType 改为 EVENT_PAYLOAD，即使 breakingChange=false
        MetricWriteCommand changedCmd = new MetricWriteCommand("用户年龄", "EVENT_PAYLOAD", "LONG", "{}", 60, false);
        int version = sut.update(TENANT, CODE, changedCmd, false, ACTOR);

        // 应走升版路径：version=2，旧行 SUPERSEDED
        assertThat(version).isEqualTo(2);
        assertThat(active.getStatus()).isEqualTo("SUPERSEDED");
        ArgumentCaptor<MetricDefinition> captor = ArgumentCaptor.forClass(MetricDefinition.class);
        verify(metricDefinitionMapper, times(1)).insert(captor.capture());
        assertThat(captor.getValue().getVersion()).isEqualTo(2);
        assertThat(captor.getValue().getStatus()).isEqualTo("ACTIVE");
        assertThat(captor.getValue().getSourceType()).isEqualTo("EVENT_PAYLOAD");
    }

    @Test
    void update_nonBreaking_butDataTypeChanged_forcesNewVersion() {
        // 当前 ACTIVE：dataType=LONG
        MetricDefinition active = activeRow(1);
        active.setSourceType("ATTRIBUTE");
        active.setDataType("LONG");
        when(metricDefinitionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(active);
        doAnswer(inv -> {
            MetricDefinition m = inv.getArgument(0);
            m.setId(301L);
            return 1;
        }).when(metricDefinitionMapper).insert(any(MetricDefinition.class));

        // cmd 将 dataType 改为 DOUBLE
        MetricWriteCommand changedCmd = new MetricWriteCommand("用户年龄", "ATTRIBUTE", "DOUBLE", "{}", 60, false);
        int version = sut.update(TENANT, CODE, changedCmd, false, ACTOR);

        assertThat(version).isEqualTo(2);
        assertThat(active.getStatus()).isEqualTo("SUPERSEDED");
        // 补：用 ArgumentCaptor 断言新行 dataType == "DOUBLE"（与 sourceType 用例对称）
        ArgumentCaptor<MetricDefinition> captor = ArgumentCaptor.forClass(MetricDefinition.class);
        verify(metricDefinitionMapper, times(1)).insert(captor.capture());
        assertThat(captor.getValue().getVersion()).isEqualTo(2);
        assertThat(captor.getValue().getStatus()).isEqualTo("ACTIVE");
        assertThat(captor.getValue().getDataType()).isEqualTo("DOUBLE");
    }

    @Test
    void update_nonBreaking_sameSourceTypeAndDataType_updatesInPlace() {
        // sourceType/dataType 未变，breakingChange=false → 仍原地更新
        MetricDefinition active = activeRow(1);
        active.setSourceType("ATTRIBUTE");
        active.setDataType("LONG");
        when(metricDefinitionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(active);

        // cmd 只改 name，sourceType/dataType 不变
        MetricWriteCommand sameTypeCmd = new MetricWriteCommand("新名称", "ATTRIBUTE", "LONG", "{}", 120, false);
        int version = sut.update(TENANT, CODE, sameTypeCmd, false, ACTOR);

        assertThat(version).isEqualTo(1);
        verify(metricDefinitionMapper, times(1)).updateById(active);
        verify(metricDefinitionMapper, never()).insert((MetricDefinition) any());
        // 补：与既有原地更新用例对称，确认 audit_log 写入一次
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
        // rule_definition：两条属于 TENANT，分属不同 scene
        RuleDefinition rd1 = ruleDefinition(101L, "risk.transfer", "转账风控", 10L, "ACTIVE");
        RuleDefinition rd2 = ruleDefinition(102L, "risk.login", "登录风控", 11L, "DISABLED");

        // scene：10 → transfer，11 → login
        SceneDef sc1 = scene(10L, "risk.transfer");
        SceneDef sc2 = scene(11L, "risk.login");

        // rule_version（mock 只返回裁列后仍包含的三个字段：id/ruleDefinitionId/metricDependencies）：
        //   rv1 属于 rd1，含 {account.age,1} → 应被选中
        //   rv2 属于 rd1，含 {account.age,2} → 版本不匹配，排除
        //   rv3 属于 rd2（rd.status=DISABLED），含 {account.age,1} → 仍应被选中（口径按 rv.status=ACTIVE）
        //   rv4 属于 rd2，dependencies 为空 → 排除
        RuleVersion rv1 = ruleVersion(1001L, 101L, "[{\"metricCode\":\"account.age\",\"metricVersion\":1}]");
        RuleVersion rv2 = ruleVersion(1002L, 101L, "[{\"metricCode\":\"account.age\",\"metricVersion\":2}]");
        RuleVersion rv3 = ruleVersion(1003L, 102L, "[{\"metricCode\":\"account.age\",\"metricVersion\":1},{\"metricCode\":\"user.level\",\"metricVersion\":1}]");
        RuleVersion rv4 = ruleVersion(1004L, 102L, "[]");

        when(ruleDefinitionMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(rd1, rd2));
        when(ruleVersionMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(rv1, rv2, rv3, rv4));
        // scene 批量查询
        when(sceneMapper.selectList(any(LambdaQueryWrapper.class)))
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
            assertThat(ref.status()).isEqualTo("ACTIVE");
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
        RuleDefinition rd = ruleDefinition(101L, "risk.transfer", "转账风控", 10L, "ACTIVE");
        RuleVersion rv = ruleVersion(1001L, 101L, "[{\"metricCode\":\"user.level\",\"metricVersion\":1}]");

        when(ruleDefinitionMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(rd));
        when(ruleVersionMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(rv));
        when(sceneMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(scene(10L, "risk.transfer")));

        List<RuleRef> result = sut.findReferencingRules(TENANT, "account.age", 1);

        assertThat(result).isEmpty();
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
        RuleDefinition rd = ruleDefinition(101L, "risk.transfer", "转账风控", 10L, "ACTIVE");
        // metric_dependencies 为非法 JSON，应静默忽略，不抛异常
        RuleVersion rv = ruleVersion(1001L, 101L, "not-valid-json");

        when(ruleDefinitionMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(rd));
        when(ruleVersionMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(rv));
        when(sceneMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(scene(10L, "risk.transfer")));

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

    private RuleDefinition ruleDefinition(Long id, String code, String name, Long sceneId, String status) {
        RuleDefinition rd = new RuleDefinition();
        rd.setId(id);
        rd.setTenantId(TENANT);
        rd.setCode(code);
        rd.setName(name);
        rd.setSceneId(sceneId);
        rd.setStatus(status);
        return rd;
    }

    private SceneDef scene(Long id, String code) {
        SceneDef sc = new SceneDef();
        sc.setId(id);
        sc.setCode(code);
        return sc;
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
