# Trace PII 读时脱敏 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 rule-api 展示出口（trace 扁平 / trace 树 / dry-run / replay）把敏感字段的 `actualValue` 抹成 `"***"`,raw 值仍按原样落库。

**Architecture:** 声明各在定义点(payload 字段→scene payloadSchema 的 `sensitive` 标志,存 JSON 列无 DDL;metric→metric 定义新增 `sensitive` 列)。读时由 config-svc 新查询 `SceneService.getSensitiveRefs(tenant, scene)` 返回 live 敏感集,rule-api 的纯函数 `TraceMasker` 按 `valueSource`+`metricCode` 匹配后遮蔽。不碰落库路径 / node_trace 表 / kernel `NodeTrace` 结构 / 评估热路径 / 重放逻辑。查询失败 fail-closed 全抹。

**Tech Stack:** Java 25 / Spring Boot 4 / MyBatis-Plus / Flyway / JUnit5 + Mockito + AssertJ。

**关键事实(已核对代码,执行时以此为准):**
- kernel `NodeTrace`(`rule-kernel/.../api/model/NodeTrace.java`)的 `valueSource` 是 **String**、`actualValue` 是 **Object**。容器节点 `actualValue==null`。
- `ValueSource` 枚举只有 `PROVIDED / FETCHED / PAYLOAD`(无 "METRIC")。规约里的 "METRIC 集" = **非 PAYLOAD 的来源(FETCHED ∪ PROVIDED)**。匹配规则:`valueSource==PAYLOAD` → 查敏感 payload 字段集;否则 → 查敏感 metric 码集。
- 三种 trace 形状字段:
  - 扁平 `AuditService.TraceNodeEntry`(`String valueSource, String actualValue, String metricCode`)。
  - 树 `AuditService.TraceTreeNode`(同上 + `List<TraceTreeNode> children`)。
  - kernel `NodeTrace`(`String valueSource, Object actualValue, String metricCode, List<NodeTrace> children`)。
- Java 泛型擦除:`List<TraceNodeEntry>` 与 `List<TraceTreeNode>` 不能重载同名方法 → masker 用 **三个不同方法名** `maskFlat / maskTree / maskKernel`。
- `MASK = "***"`。fail-closed 用 **`refs == null` 哨兵**表示"全抹"(任意 `actualValue != null` 的叶子都抹)。
- DB boolean 列约定:`TINYINT(1) NOT NULL DEFAULT 0`(对标 `metric_definition.allow_provided`、`tenant.is_default`)。MyBatis-Plus 映射到实体 `Boolean` 字段。
- 现有最新迁移 `V1_31`;本计划新增迁移用 **`V1_33`**(`V1_32` 已被「迁移列序前向修复」占用,见会话外处理)。执行前用 `ls rule-config-svc/src/main/resources/db/migration/ | sort -V | tail` 复核,若已有 V1_33 则顺延。
- rule-api 已在 classpath 依赖 rule-config-svc / rule-audit-svc / rule-eval-svc / rule-kernel(`EvalController` 注入 `TenantQueryService`、`SceneController` 注入 `SceneService`、`AuditController` 注入 `AuditService` 均为现状)。

**环境:** 每个含 `$MVN` 的命令前,先用 `mvn-env` skill 设好 `$MVN`(本会话执行一次即可)。

---

## File Structure

**config-svc 声明侧(rule-config-svc):**
- Modify `rule-config-svc/.../config/api/dto/PayloadFieldSpec.java` — 加 `boolean sensitive` 组件 + 8 参兼容构造器。
- Modify `rule-config-svc/.../config/api/service/MetricWriteService.java` — `MetricWriteCommand` 加 `boolean sensitive` 组件 + 6 参兼容构造器。
- Modify `rule-config-svc/.../config/internal/domain/MetricDefinition.java` — 加 `Boolean sensitive` 字段。
- Modify `rule-config-svc/.../config/internal/service/MetricWriteServiceImpl.java` — `applyCommandFields` set sensitive。
- Create `rule-config-svc/src/main/resources/db/migration/V1_33__metric_definition_sensitive.sql`。
- Modify `rule-config-svc/.../config/api/service/SceneService.java` — 加嵌套 `record SensitiveRefs` + `getSensitiveRefs` 方法。
- Modify `rule-config-svc/.../config/internal/service/SceneServiceImpl.java` — 注入 `MetricDefinitionMapper`,实现 `getSensitiveRefs`。

**审计读侧(rule-audit-svc):**
- Modify `rule-audit-svc/.../audit/internal/repository/EvalSessionReadMapper.java` — 加 `findSceneCode` default 方法。
- Modify `rule-audit-svc/.../audit/api/service/AuditService.java` — 加 `getSessionSceneCode` 方法。
- Modify `rule-audit-svc/.../audit/internal/service/AuditServiceImpl.java` — 实现 `getSessionSceneCode`。

**脱敏与接入(rule-api):**
- Create `rule-api/src/main/java/com/sstlfsj/rule/web/mask/TraceMasker.java`。
- Modify `rule-api/.../web/admin/AuditController.java` — 注入 `SceneService`,trace / trace/tree 接入脱敏。
- Modify `rule-api/.../web/api/EvalController.java` — 注入 `SceneService`,dry-run 接入脱敏。
- Modify `rule-api/.../web/admin/ReplayController.java` — 注入 `AuditService` + `SceneService`,replay 接入脱敏。

**测试(随实现同 commit):**
- `rule-config-svc/.../config/api/dto/PayloadFieldSpecTest.java`(已存在,加用例)。
- `rule-config-svc/.../config/internal/service/MetricWriteServiceImplTest.java`(已存在,改构造 + 加用例)。
- `rule-config-svc/.../config/internal/service/SceneServiceImplTest.java`(已存在,改构造 + 加用例)。
- Create `rule-api/src/test/java/com/sstlfsj/rule/web/mask/TraceMaskerTest.java`。
- `rule-audit-svc/.../audit/internal/service/AuditServiceImplTest.java`(已存在,加用例)。
- Create / 扩展 `rule-app` 集成 e2e。

---

## Task 1: PayloadFieldSpec 加 sensitive 标志

**Files:**
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/PayloadFieldSpec.java`
- Test: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/api/dto/PayloadFieldSpecTest.java`

- [ ] **Step 1: 写失败测试**

在 `PayloadFieldSpecTest.java` 末尾(类内)追加:

```java
    @Test
    void sensitiveField_serializesAndDeserializes() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        PayloadFieldSpec field = new PayloadFieldSpec(
                "phone", "STRING", true, null, null, null, null, null, true);
        String json = mapper.writeValueAsString(field);
        assertThat(json).contains("\"sensitive\":true");

        PayloadFieldSpec back = mapper.readValue(json, PayloadFieldSpec.class);
        assertThat(back.sensitive()).isTrue();
    }

    @Test
    void legacyConstructor_defaultsSensitiveToFalse() {
        PayloadFieldSpec field = new PayloadFieldSpec(
                "amount", "NUMBER", true, null, null, null, null, null);
        assertThat(field.sensitive()).isFalse();
    }

    @Test
    void missingSensitiveInJson_defaultsToFalse() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        PayloadFieldSpec back = mapper.readValue(
                "{\"name\":\"amount\",\"type\":\"NUMBER\",\"required\":true}", PayloadFieldSpec.class);
        assertThat(back.sensitive()).isFalse();
    }
```

- [ ] **Step 2: 运行测试,确认编译失败**

Run: `$MVN -pl rule-config-svc -am test -Dtest=PayloadFieldSpecTest`
Expected: 编译失败 —— `sensitive()` 方法不存在、9 参构造器不存在。

- [ ] **Step 3: 加 sensitive 组件 + 兼容构造器**

把 `PayloadFieldSpec.java` 的 record 头与体改为(在 `description` 后新增 `sensitive`,并加 8 参兼容构造器):

```java
public record PayloadFieldSpec(
        /** 字段名，对应 RuleEvent.payload 的 key。 */
        String name,
        /** 字段类型：STRING / INTEGER / NUMBER / BOOLEAN / ARRAY / OBJECT。 */
        String type,
        /** 是否必填，默认 false。 */
        boolean required,
        /** 枚举值约束；非 null 时 payload 该字段值必须在列表内。 */
        @JsonProperty("enum") List<Object> enumValues,
        /** 数值下界（NUMBER / INTEGER 有效）；null 表示不约束。 */
        Double minimum,
        /** 数值上界（NUMBER / INTEGER 有效）；null 表示不约束。 */
        Double maximum,
        /** 正则约束（STRING 有效）；null 表示不约束。 */
        String pattern,
        /** 字段描述，供运营可视化展示用。 */
        String description,
        /** 是否敏感字段：true 时该字段值在 trace 展示出口被读时脱敏（D71）；默认 false。 */
        boolean sensitive
) {
    /** 兼容既有 8 参调用点；sensitive 默认 false。新代码请用全参构造器。 */
    public PayloadFieldSpec(String name, String type, boolean required, List<Object> enumValues,
                            Double minimum, Double maximum, String pattern, String description) {
        this(name, type, required, enumValues, minimum, maximum, pattern, description, false);
    }
}
```

(`@JsonIgnoreProperties` / `@JsonInclude` / `@JsonProperty` 的 import 与注解保持原样不动。)

- [ ] **Step 4: 运行测试,确认通过**

Run: `$MVN -pl rule-config-svc -am test -Dtest=PayloadFieldSpecTest`
Expected: PASS。

- [ ] **Step 5: 跑全模块测试 + 提交**

Run: `$MVN -pl rule-config-svc -am test`
Expected: PASS(既有 8 参 caller 因兼容构造器全部编译通过)。

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/PayloadFieldSpec.java \
        rule-config-svc/src/test/java/com/sstlfsj/rule/config/api/dto/PayloadFieldSpecTest.java
git commit -m "feat(config): PayloadFieldSpec 加 sensitive 标志(读时脱敏声明位,D71)"
```

---

## Task 2: metric 定义加 sensitive 列(实体 + 写命令 + 迁移)

**Files:**
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/MetricWriteService.java`
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/MetricDefinition.java`
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/MetricWriteServiceImpl.java`
- Create: `rule-config-svc/src/main/resources/db/migration/V1_33__metric_definition_sensitive.sql`
- Test: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/service/MetricWriteServiceImplTest.java`

- [ ] **Step 1: 写失败测试**

在 `MetricWriteServiceImplTest.java` 类内追加(沿用文件已有的 mock 风格;`metricDefinitionMapper` 是该测试已有的 `@Mock`,`captor` 按需 new):

```java
    @Test
    void create_persistsSensitiveFlag() {
        MetricWriteCommand sensitiveCmd =
                new MetricWriteCommand("身份证号", "ATTRIBUTE", "STRING", java.util.Map.of(), 60, false, true);
        org.mockito.ArgumentCaptor<MetricDefinition> captor =
                org.mockito.ArgumentCaptor.forClass(MetricDefinition.class);
        when(metricDefinitionMapper.insert(captor.capture())).thenReturn(1);

        service.create(100L, "user.idno", sensitiveCmd, "actor-1");

        assertThat(captor.getValue().getSensitive()).isTrue();
    }

    @Test
    void create_defaultsSensitiveFalse() {
        org.mockito.ArgumentCaptor<MetricDefinition> captor =
                org.mockito.ArgumentCaptor.forClass(MetricDefinition.class);
        when(metricDefinitionMapper.insert(captor.capture())).thenReturn(1);

        // 6 参兼容构造器 sensitive 默认 false
        service.create(100L, "user.age",
                new MetricWriteCommand("用户年龄", "ATTRIBUTE", "LONG", java.util.Map.of(), 60, false), "actor-1");

        assertThat(captor.getValue().getSensitive()).isFalse();
    }
```

(若 `MetricDefinition` / `MetricWriteCommand` 未 import,补 `import com.sstlfsj.rule.config.internal.domain.MetricDefinition;`、`import com.sstlfsj.rule.config.api.service.MetricWriteService.MetricWriteCommand;`;`when/assertThat` 已有静态 import。)

- [ ] **Step 2: 运行测试,确认编译失败**

Run: `$MVN -pl rule-config-svc -am test -Dtest=MetricWriteServiceImplTest`
Expected: 编译失败 —— 7 参 `MetricWriteCommand` 构造器、`getSensitive()` 不存在。

- [ ] **Step 3: 给 MetricWriteCommand 加 sensitive 组件 + 兼容构造器**

把 `MetricWriteService.java` 中的 `MetricWriteCommand` record 改为:

```java
    /** metric 写入参数。params 为结构依 sourceType 而异的 JSON 对象（前端直接传对象，服务端序列化存库）。 */
    record MetricWriteCommand(
            String name,
            String sourceType,
            String dataType,
            Map<String, Object> params,
            Integer cacheTtlSeconds,
            boolean allowProvided,
            /** 是否敏感 metric：true 时其值在 trace 展示出口被读时脱敏（D71，租户级 D54）；默认 false。 */
            boolean sensitive) {

        /** 兼容既有 6 参调用点；sensitive 默认 false。 */
        public MetricWriteCommand(String name, String sourceType, String dataType,
                                  Map<String, Object> params, Integer cacheTtlSeconds, boolean allowProvided) {
            this(name, sourceType, dataType, params, cacheTtlSeconds, allowProvided, false);
        }
    }
```

- [ ] **Step 4: 给 MetricDefinition 实体加 sensitive 字段**

在 `MetricDefinition.java` 的 `allowProvided` 字段后新增:

```java
    private Boolean allowProvided;
    /** 是否敏感 metric：true 时其值在 trace 展示出口被读时脱敏（D71）。 */
    private Boolean sensitive;
    private MetricStatus status;
```

- [ ] **Step 5: applyCommandFields 写入 sensitive**

在 `MetricWriteServiceImpl.java` 的 `applyCommandFields` 末尾(`setAllowProvided` 后)加一行:

```java
        m.setAllowProvided(cmd.allowProvided());
        m.setSensitive(cmd.sensitive());
    }
```

- [ ] **Step 6: 写迁移**

先复核号段:`ls rule-config-svc/src/main/resources/db/migration/ | sort -V | tail`。若 `V1_33` 未占用,创建 `V1_33__metric_definition_sensitive.sql`:

```sql
-- D71 读时脱敏：metric 定义级敏感标志（租户级共享 D54）。trace 展示出口据此遮蔽该 metric 值。
ALTER TABLE metric_definition
    ADD COLUMN sensitive TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '是否敏感 metric：1=trace 展示出口读时脱敏（D71）' AFTER allow_provided;
```

- [ ] **Step 7: 运行测试,确认通过**

Run: `$MVN -pl rule-config-svc -am test -Dtest=MetricWriteServiceImplTest`
Expected: PASS。

- [ ] **Step 8: 跑全模块测试 + 提交**

Run: `$MVN -pl rule-config-svc -am test`
Expected: PASS(既有 6 参 caller 走兼容构造器)。

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/MetricWriteService.java \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/MetricDefinition.java \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/MetricWriteServiceImpl.java \
        rule-config-svc/src/main/resources/db/migration/V1_33__metric_definition_sensitive.sql \
        rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/service/MetricWriteServiceImplTest.java
git commit -m "feat(config): metric 定义加 sensitive 列 + 写命令字段(读时脱敏声明位,D71)"
```

---

## Task 3: SceneService.getSensitiveRefs 查询敏感集

**Files:**
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/SceneService.java`
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/SceneServiceImpl.java`
- Test: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/service/SceneServiceImplTest.java`

- [ ] **Step 1: 写失败测试**

先把 `SceneServiceImplTest.java` 的 setUp 与 mock 改为含 `MetricDefinitionMapper`:

```java
    @Mock SceneMapper sceneMapper;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock com.sstlfsj.rule.config.internal.repository.MetricDefinitionMapper metricDefinitionMapper;

    SceneServiceImpl sceneService;

    @BeforeEach
    void setUp() {
        sceneService = new SceneServiceImpl(sceneMapper, eventPublisher, metricDefinitionMapper);
    }
```

再在类内追加用例(`field` helper 已有,sensitive 默认 false;用全参构造器造敏感字段):

```java
    @Test
    void getSensitiveRefs_collectsSensitivePayloadFieldsAndMetricCodes() {
        SceneDef scene = new SceneDef();
        scene.setId(1L);
        scene.setTenantId(100L);
        scene.setCode("risk.transfer");
        scene.setPayloadSchema(List.of(
                new PayloadFieldSpec("phone", "STRING", true, null, null, null, null, null, true),
                new PayloadFieldSpec("amount", "NUMBER", true, null, null, null, null, null, false)));
        when(sceneMapper.findByCode(100L, "risk.transfer")).thenReturn(scene);

        com.sstlfsj.rule.config.internal.domain.MetricDefinition sensitiveMetric =
                new com.sstlfsj.rule.config.internal.domain.MetricDefinition();
        sensitiveMetric.setMetricCode("user.idno");
        sensitiveMetric.setSensitive(true);
        com.sstlfsj.rule.config.internal.domain.MetricDefinition plainMetric =
                new com.sstlfsj.rule.config.internal.domain.MetricDefinition();
        plainMetric.setMetricCode("user.age");
        plainMetric.setSensitive(false);
        when(metricDefinitionMapper.findActiveByTenant(100L))
                .thenReturn(List.of(sensitiveMetric, plainMetric));

        SceneService.SensitiveRefs refs = sceneService.getSensitiveRefs("100", "risk.transfer");

        assertThat(refs.payloadFields()).containsExactly("phone");
        assertThat(refs.metricCodes()).containsExactly("user.idno");
    }

    @Test
    void getSensitiveRefs_noneSensitive_returnsEmptySets() {
        SceneDef scene = new SceneDef();
        scene.setTenantId(100L);
        scene.setCode("s1");
        scene.setPayloadSchema(List.of(field("amount")));
        when(sceneMapper.findByCode(100L, "s1")).thenReturn(scene);
        when(metricDefinitionMapper.findActiveByTenant(100L)).thenReturn(List.of());

        SceneService.SensitiveRefs refs = sceneService.getSensitiveRefs("100", "s1");

        assertThat(refs.payloadFields()).isEmpty();
        assertThat(refs.metricCodes()).isEmpty();
    }

    @Test
    void getSensitiveRefs_sceneNotFound_throws() {
        when(sceneMapper.findByCode(100L, "missing")).thenReturn(null);
        assertThatThrownBy(() -> sceneService.getSensitiveRefs("100", "missing"))
                .isInstanceOf(IllegalArgumentException.class);
    }
```

(`field(...)` 返回 sensitive=false 的字段;注意若 `findActiveByTenant` 在 `getSensitiveRefs_sceneNotFound` 不会被调用,Mockito lenient 不需要——该用例 scene 先抛,不 stub metric mapper。)

- [ ] **Step 2: 运行测试,确认失败**

Run: `$MVN -pl rule-config-svc -am test -Dtest=SceneServiceImplTest`
Expected: 编译失败 —— `SceneService.SensitiveRefs` / `getSensitiveRefs` / 3 参构造器不存在。

- [ ] **Step 3: SceneService 加 SensitiveRefs record + getSensitiveRefs**

在 `SceneService.java` 接口内(末尾 `disableScene` 后)新增 import 与成员:

文件头 import 区补:
```java
import java.util.Set;
```

接口内追加:
```java
    /**
     * 读时脱敏所需的 live 敏感集（D71）。
     *
     * @param payloadFields 该 scene payloadSchema 中 sensitive=true 的字段名集合
     * @param metricCodes   该租户 metric 定义中 sensitive=true 的 metric 码集合
     */
    record SensitiveRefs(Set<String> payloadFields, Set<String> metricCodes) {}

    /**
     * 查询指定 (租户, 场景) 的 live 敏感集，供 trace 展示出口读时脱敏。
     *
     * @param tenantId  租户 ID
     * @param sceneCode 场景编码
     * @return 敏感 payload 字段集 + 敏感 metric 码集；场景不存在抛 IllegalArgumentException
     */
    SensitiveRefs getSensitiveRefs(String tenantId, String sceneCode);
```

- [ ] **Step 4: SceneServiceImpl 注入 mapper + 实现**

在 `SceneServiceImpl.java`:

构造字段加入 `MetricDefinitionMapper`(`@RequiredArgsConstructor` 自动生成 3 参构造器):
```java
    private final SceneMapper sceneMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final com.sstlfsj.rule.config.internal.repository.MetricDefinitionMapper metricDefinitionMapper;
```

import 区补:
```java
import java.util.Set;
import java.util.stream.Collectors;
```

类内(`disableScene` 后)实现:
```java
    @Override
    public SensitiveRefs getSensitiveRefs(String tenantId, String sceneCode) {
        Long tid = Long.valueOf(tenantId);
        // scene 不存在直接抛——调用方(rule-api)捕获后 fail-closed 全抹
        SceneDef scene = findScene(tid, sceneCode);
        List<PayloadFieldSpec> schema = scene.getPayloadSchema() != null
                ? scene.getPayloadSchema() : List.of();
        Set<String> payloadFields = schema.stream()
                .filter(PayloadFieldSpec::sensitive)
                .map(PayloadFieldSpec::name)
                .collect(Collectors.toSet());
        // metric 敏感性租户级共享(D54)：取该租户全部 ACTIVE metric 中 sensitive=true 的码
        Set<String> metricCodes = metricDefinitionMapper.findActiveByTenant(tid).stream()
                .filter(m -> Boolean.TRUE.equals(m.getSensitive()))
                .map(com.sstlfsj.rule.config.internal.domain.MetricDefinition::getMetricCode)
                .collect(Collectors.toSet());
        return new SensitiveRefs(payloadFields, metricCodes);
    }
```

- [ ] **Step 5: 运行测试,确认通过**

Run: `$MVN -pl rule-config-svc -am test -Dtest=SceneServiceImplTest`
Expected: PASS。

- [ ] **Step 6: 跑全模块测试 + 提交**

Run: `$MVN -pl rule-config-svc -am test`
Expected: PASS。

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/SceneService.java \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/SceneServiceImpl.java \
        rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/service/SceneServiceImplTest.java
git commit -m "feat(config): SceneService.getSensitiveRefs 查 live 敏感集(读时脱敏取数,D71)"
```

---

## Task 4: AuditService.getSessionSceneCode 解析会话场景

**Files:**
- Modify: `rule-audit-svc/src/main/java/com/sstlfsj/rule/audit/internal/repository/EvalSessionReadMapper.java`
- Modify: `rule-audit-svc/src/main/java/com/sstlfsj/rule/audit/api/service/AuditService.java`
- Modify: `rule-audit-svc/src/main/java/com/sstlfsj/rule/audit/internal/service/AuditServiceImpl.java`
- Test: `rule-audit-svc/src/test/java/com/sstlfsj/rule/audit/internal/service/AuditServiceImplTest.java`

- [ ] **Step 1: 写失败测试**

在 `AuditServiceImplTest.java` 类内追加:

```java
    @Test
    void getSessionSceneCode_returnsSceneCode() {
        EvalSessionRow row = new EvalSessionRow();
        row.setId(1L);
        row.setTenantId(100L);
        row.setSceneCode("risk.transfer");
        when(evalSessionMapper.findSceneCode(1L, 100L)).thenReturn("risk.transfer");

        assertThat(service.getSessionSceneCode("100", 1L)).isEqualTo("risk.transfer");
    }

    @Test
    void getSessionSceneCode_sessionNotFound_returnsNull() {
        when(evalSessionMapper.findSceneCode(999L, 100L)).thenReturn(null);
        assertThat(service.getSessionSceneCode("100", 999L)).isNull();
    }
```

- [ ] **Step 2: 运行测试,确认失败**

Run: `$MVN -pl rule-audit-svc -am test -Dtest=AuditServiceImplTest`
Expected: 编译失败 —— `findSceneCode` / `getSessionSceneCode` 不存在。

- [ ] **Step 3: EvalSessionReadMapper 加 findSceneCode**

在 `EvalSessionReadMapper.java` 接口内追加 default 方法(只 select scene_code 列,避免整行):

```java
    /** 查指定 (会话, 租户) 的 scene_code，不存在返回 null。 */
    default String findSceneCode(Long sessionId, Long tenantId) {
        EvalSessionRow row = selectOne(new LambdaQueryWrapper<EvalSessionRow>()
                .select(EvalSessionRow::getSceneCode)
                .eq(EvalSessionRow::getId, sessionId)
                .eq(EvalSessionRow::getTenantId, tenantId));
        return row != null ? row.getSceneCode() : null;
    }
```

- [ ] **Step 4: AuditService 加方法声明**

在 `AuditService.java` 接口内(末尾)追加:

```java
    /**
     * 查询评估会话所属场景编码（供 trace / replay 展示出口解析敏感集，D71）。
     *
     * @param tenantId  租户标识（数字字符串）
     * @param sessionId 评估会话 ID
     * @return 场景编码；会话不存在返回 null
     */
    String getSessionSceneCode(String tenantId, Long sessionId);
```

- [ ] **Step 5: AuditServiceImpl 实现**

在 `AuditServiceImpl.java` 类内(`querySessionsByRuleDefinition` 后)追加:

```java
    @Override
    public String getSessionSceneCode(String tenantId, Long sessionId) {
        return evalSessionMapper.findSceneCode(sessionId, Long.valueOf(tenantId));
    }
```

- [ ] **Step 6: 运行测试 + 提交**

Run: `$MVN -pl rule-audit-svc -am test`
Expected: PASS。

```bash
git add rule-audit-svc/src/main/java/com/sstlfsj/rule/audit/internal/repository/EvalSessionReadMapper.java \
        rule-audit-svc/src/main/java/com/sstlfsj/rule/audit/api/service/AuditService.java \
        rule-audit-svc/src/main/java/com/sstlfsj/rule/audit/internal/service/AuditServiceImpl.java \
        rule-audit-svc/src/test/java/com/sstlfsj/rule/audit/internal/service/AuditServiceImplTest.java
git commit -m "feat(audit): AuditService.getSessionSceneCode 解析会话场景(读时脱敏前置,D71)"
```

---

## Task 5: TraceMasker 脱敏纯函数(rule-api)

**Files:**
- Create: `rule-api/src/main/java/com/sstlfsj/rule/web/mask/TraceMasker.java`
- Test: `rule-api/src/test/java/com/sstlfsj/rule/web/mask/TraceMaskerTest.java`

- [ ] **Step 1: 写失败测试**

创建 `TraceMaskerTest.java`:

```java
package com.sstlfsj.rule.web.mask;

import com.sstlfsj.rule.audit.api.service.AuditService.TraceNodeEntry;
import com.sstlfsj.rule.audit.api.service.AuditService.TraceTreeNode;
import com.sstlfsj.rule.config.api.service.SceneService.SensitiveRefs;
import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import com.sstlfsj.rule.kernel.api.model.NodeType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TraceMaskerTest {

    private static final String MASK = "***";
    private static final SensitiveRefs REFS =
            new SensitiveRefs(Set.of("phone"), Set.of("user.idno"));

    private static TraceNodeEntry leaf(String metricCode, String actualValue, String valueSource) {
        return new TraceNodeEntry("0.0", "ConditionNode", "EQ", metricCode,
                actualValue, true, null, valueSource, "ruleA", 1L);
    }

    @Test
    void maskFlat_payloadSensitiveField_masked() {
        List<TraceNodeEntry> out = TraceMasker.maskFlat(REFS, List.of(leaf("phone", "13800001111", "PAYLOAD")));
        assertThat(out.get(0).actualValue()).isEqualTo(MASK);
    }

    @Test
    void maskFlat_payloadNonSensitiveField_kept() {
        List<TraceNodeEntry> out = TraceMasker.maskFlat(REFS, List.of(leaf("amount", "100", "PAYLOAD")));
        assertThat(out.get(0).actualValue()).isEqualTo("100");
    }

    @Test
    void maskFlat_metricSensitiveCode_masked() {
        List<TraceNodeEntry> out = TraceMasker.maskFlat(REFS, List.of(leaf("user.idno", "511...", "FETCHED")));
        assertThat(out.get(0).actualValue()).isEqualTo(MASK);
    }

    @Test
    void maskFlat_providedSource_judgedByMetricSet() {
        List<TraceNodeEntry> out = TraceMasker.maskFlat(REFS, List.of(leaf("user.idno", "x", "PROVIDED")));
        assertThat(out.get(0).actualValue()).isEqualTo(MASK);
    }

    @Test
    void maskFlat_nonSensitiveMetric_kept() {
        List<TraceNodeEntry> out = TraceMasker.maskFlat(REFS, List.of(leaf("user.age", "25", "FETCHED")));
        assertThat(out.get(0).actualValue()).isEqualTo("25");
    }

    @Test
    void maskFlat_nullMetricCode_notMasked() {
        List<TraceNodeEntry> out = TraceMasker.maskFlat(REFS, List.of(leaf(null, "x", "PAYLOAD")));
        assertThat(out.get(0).actualValue()).isEqualTo("x");
    }

    @Test
    void maskFlat_emptyRefs_noOp() {
        SensitiveRefs empty = new SensitiveRefs(Set.of(), Set.of());
        List<TraceNodeEntry> out = TraceMasker.maskFlat(empty, List.of(leaf("phone", "13800001111", "PAYLOAD")));
        assertThat(out.get(0).actualValue()).isEqualTo("13800001111");
    }

    @Test
    void maskFlat_nullRefs_failClosedMasksAllLeaves() {
        List<TraceNodeEntry> out = TraceMasker.maskFlat(null, List.of(
                leaf("phone", "13800001111", "PAYLOAD"),
                leaf("user.age", "25", "FETCHED")));
        assertThat(out.get(0).actualValue()).isEqualTo(MASK);
        assertThat(out.get(1).actualValue()).isEqualTo(MASK);
    }

    @Test
    void maskTree_recursesAndMasksDeepLeaf_containerUntouched() {
        TraceTreeNode deepLeaf = new TraceTreeNode("ConditionNode", "EQ", "phone",
                "13800001111", true, null, "PAYLOAD", "ruleA", 1L, List.of());
        TraceTreeNode container = new TraceTreeNode("AndNode", null, null,
                null, true, null, null, "ruleA", 1L, List.of(deepLeaf));

        List<TraceTreeNode> out = TraceMasker.maskTree(REFS, List.of(container));

        assertThat(out.get(0).actualValue()).isNull();
        assertThat(out.get(0).children().get(0).actualValue()).isEqualTo(MASK);
    }

    @Test
    void maskKernel_recursesAndMasksLeaf_containerUntouched() {
        NodeTrace leaf = new NodeTrace(NodeType.CONDITION.tag(), "EQ", "user.idno",
                true, "511...", "FETCHED", null, List.of(), 1L, "ruleA", 1L, null, null);
        NodeTrace container = NodeTrace.container(NodeType.AND, true, List.of(leaf), 1L);

        List<NodeTrace> out = TraceMasker.maskKernel(REFS, List.of(container));

        assertThat(out.get(0).actualValue()).isNull();
        assertThat(out.get(0).children().get(0).actualValue()).isEqualTo(MASK);
    }

    @Test
    void maskKernel_nullRefs_failClosedMasksLeavesWithValue() {
        NodeTrace leaf = new NodeTrace(NodeType.CONDITION.tag(), "EQ", "user.age",
                true, "25", "FETCHED", null, List.of(), 1L, "ruleA", 1L, null, null);
        List<NodeTrace> out = TraceMasker.maskKernel(null, List.of(leaf));
        assertThat(out.get(0).actualValue()).isEqualTo(MASK);
    }
}
```

- [ ] **Step 2: 运行测试,确认失败**

Run: `$MVN -pl rule-api -am test -Dtest=TraceMaskerTest`
Expected: 编译失败 —— `TraceMasker` 不存在。

- [ ] **Step 3: 实现 TraceMasker**

创建 `TraceMasker.java`:

```java
package com.sstlfsj.rule.web.mask;

import com.sstlfsj.rule.audit.api.service.AuditService.TraceNodeEntry;
import com.sstlfsj.rule.audit.api.service.AuditService.TraceTreeNode;
import com.sstlfsj.rule.config.api.service.SceneService.SensitiveRefs;
import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import com.sstlfsj.rule.kernel.api.model.ValueSource;

import java.util.List;

/**
 * trace 读时 PII 脱敏纯函数（D71）。把命中敏感集的叶子 actualValue 抹成 {@value #MASK}，返回新结构（不改原对象）。
 *
 * <p>三种 trace 形状各一个方法（泛型擦除不允许同名重载）：扁平 {@link TraceNodeEntry}、
 * 嵌套 {@link TraceTreeNode}、kernel {@link NodeTrace}。</p>
 *
 * <p><b>fail-closed：</b>{@code refs == null} 表示敏感集查询失败，全抹——任意 actualValue 非 null 的叶子都遮蔽，
 * 宁可过度遮蔽不漏 PII。{@code refs} 非 null（即便空集）时仅按敏感集精确遮蔽。</p>
 *
 * <p>匹配规则：叶子 valueSource==PAYLOAD 时查敏感 payload 字段集；否则（FETCHED/PROVIDED）查敏感 metric 码集。
 * 容器节点 actualValue 本就 null，不受影响。</p>
 */
public final class TraceMasker {

    /** 遮蔽后的固定占位串。 */
    public static final String MASK = "***";
    private static final String PAYLOAD = ValueSource.PAYLOAD.tag();

    private TraceMasker() {}

    /** 遮蔽扁平 trace 列表。{@code refs==null} 时 fail-closed 全抹。 */
    public static List<TraceNodeEntry> maskFlat(SensitiveRefs refs, List<TraceNodeEntry> nodes) {
        if (nodes == null || nodes.isEmpty()) return List.of();
        return nodes.stream().map(n -> shouldMask(refs, n.valueSource(), n.metricCode(), n.actualValue())
                ? new TraceNodeEntry(n.nodePath(), n.nodeType(), n.conditionType(), n.metricCode(),
                        MASK, n.result(), n.errorCode(), n.valueSource(), n.ruleCode(), n.ruleVersion())
                : n).toList();
    }

    /** 递归遮蔽嵌套 trace 树。{@code refs==null} 时 fail-closed 全抹。 */
    public static List<TraceTreeNode> maskTree(SensitiveRefs refs, List<TraceTreeNode> nodes) {
        if (nodes == null || nodes.isEmpty()) return List.of();
        return nodes.stream().map(n -> {
            List<TraceTreeNode> children = maskTree(refs, n.children());
            Object masked = shouldMask(refs, n.valueSource(), n.metricCode(), n.actualValue())
                    ? MASK : n.actualValue();
            return new TraceTreeNode(n.nodeType(), n.conditionType(), n.metricCode(),
                    (String) masked, n.result(), n.errorCode(), n.valueSource(),
                    n.ruleCode(), n.ruleVersion(), children);
        }).toList();
    }

    /** 递归遮蔽 kernel NodeTrace 列表（dry-run / replay 内存 trace）。{@code refs==null} 时 fail-closed 全抹。 */
    public static List<NodeTrace> maskKernel(SensitiveRefs refs, List<NodeTrace> nodes) {
        if (nodes == null || nodes.isEmpty()) return List.of();
        return nodes.stream().map(n -> {
            List<NodeTrace> children = maskKernel(refs, n.children());
            Object masked = shouldMask(refs, n.valueSource(), n.metricCode(), n.actualValue())
                    ? MASK : n.actualValue();
            return new NodeTrace(n.nodeType(), n.conditionType(), n.metricCode(), n.result(),
                    masked, n.valueSource(), n.errorCode(), children, n.ruleVersionId(),
                    n.ruleCode(), n.ruleVersion(), n.expectedValue(), n.displayLabel());
        }).toList();
    }

    /**
     * 判断某叶子是否该遮蔽。actualValue 为 null 的容器节点恒不遮蔽。
     * refs==null（fail-closed）时只要 actualValue 非 null 即遮蔽。
     */
    private static boolean shouldMask(SensitiveRefs refs, String valueSource,
                                      String metricCode, Object actualValue) {
        if (actualValue == null) return false;
        if (refs == null) return true; // fail-closed：全抹
        if (metricCode == null) return false;
        if (PAYLOAD.equals(valueSource)) return refs.payloadFields().contains(metricCode);
        // 非 PAYLOAD（FETCHED / PROVIDED）归入 metric 集判定
        return refs.metricCodes().contains(metricCode);
    }
}
```

- [ ] **Step 4: 运行测试,确认通过**

Run: `$MVN -pl rule-api -am test -Dtest=TraceMaskerTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add rule-api/src/main/java/com/sstlfsj/rule/web/mask/TraceMasker.java \
        rule-api/src/test/java/com/sstlfsj/rule/web/mask/TraceMaskerTest.java
git commit -m "feat(api): TraceMasker 读时脱敏纯函数(三形状 + fail-closed,D71)"
```

---

## Task 6: AuditController 接入 trace / trace-tree 脱敏

**Files:**
- Modify: `rule-api/src/main/java/com/sstlfsj/rule/web/admin/AuditController.java`
- Test: `rule-api/src/test/java/com/sstlfsj/rule/web/admin/AuditControllerTest.java`

- [ ] **Step 1: 写失败测试**

先看 `AuditControllerTest.java` 现有 mock 风格(MockMvc 还是直调 controller)。按其风格追加两个用例;若该测试直接 new controller + mock 服务,用如下骨架(按现有 import 与构造调整):

```java
    @Test
    void queryTrace_masksSensitiveLeafValues() {
        when(auditService.getSessionSceneCode("100", 1L)).thenReturn("risk.transfer");
        when(sceneService.getSensitiveRefs("100", "risk.transfer"))
                .thenReturn(new com.sstlfsj.rule.config.api.service.SceneService.SensitiveRefs(
                        java.util.Set.of("phone"), java.util.Set.of()));
        when(auditService.queryTrace("100", 1L)).thenReturn(List.of(
                new com.sstlfsj.rule.audit.api.service.AuditService.TraceNodeEntry(
                        "0.0", "ConditionNode", "EQ", "phone", "13800001111",
                        true, null, "PAYLOAD", "ruleA", 1L)));

        var resp = auditController.queryTrace(1L, "100");

        assertThat(resp.data().get(0).actualValue()).isEqualTo("***");
    }

    @Test
    void queryTrace_configUnavailable_failClosedMasksAll() {
        when(auditService.getSessionSceneCode("100", 1L)).thenReturn("risk.transfer");
        when(sceneService.getSensitiveRefs("100", "risk.transfer"))
                .thenThrow(new RuntimeException("config down"));
        when(auditService.queryTrace("100", 1L)).thenReturn(List.of(
                new com.sstlfsj.rule.audit.api.service.AuditService.TraceNodeEntry(
                        "0.0", "ConditionNode", "EQ", "amount", "100",
                        true, null, "PAYLOAD", "ruleA", 1L)));

        var resp = auditController.queryTrace(1L, "100");

        assertThat(resp.data().get(0).actualValue()).isEqualTo("***");
    }
```

(若 `AuditControllerTest` 用 `@WebMvcTest` + MockMvc,则改为 `@MockBean SceneService sceneService;` 并断言 JSON 中 `actualValue` == `"***"`;`auditService` 的 mock 已有。`resp.data()` 对应 `ApiResponse` 的取值方法,按现有测试既有写法用。)

- [ ] **Step 2: 运行测试,确认失败**

Run: `$MVN -pl rule-api -am test -Dtest=AuditControllerTest`
Expected: 失败/编译失败 —— controller 未注入 sceneService、未脱敏。

- [ ] **Step 3: 改 AuditController**

import 区补:
```java
import com.sstlfsj.rule.config.api.service.SceneService;
import com.sstlfsj.rule.web.mask.TraceMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
```

字段加注入 + logger:
```java
    private static final Logger log = LoggerFactory.getLogger(AuditController.class);

    private final AuditService auditService;
    private final SceneService sceneService;
```

`queryTrace` 改为:
```java
    @GetMapping("/evaluation-sessions/{sessionId}/trace")
    public ApiResponse<List<AuditService.TraceNodeEntry>> queryTrace(
            @PathVariable Long sessionId,
            @RequestParam String tenantId) {
        List<AuditService.TraceNodeEntry> trace = auditService.queryTrace(tenantId, sessionId);
        return ApiResponse.ok(TraceMasker.maskFlat(resolveRefs(tenantId, sessionId), trace));
    }
```

`getTraceTree` 改为:
```java
    @GetMapping("/evaluation-sessions/{sessionId}/trace/tree")
    public ApiResponse<List<AuditService.TraceTreeNode>> getTraceTree(
            @PathVariable Long sessionId,
            @RequestParam String tenantId) {
        List<AuditService.TraceTreeNode> tree = auditService.queryTraceTree(tenantId, sessionId);
        return ApiResponse.ok(TraceMasker.maskTree(resolveRefs(tenantId, sessionId), tree));
    }
```

类内加私有方法(fail-closed:任何异常→返回 null→masker 全抹):
```java
    /**
     * 解析会话所属场景的 live 敏感集；查询失败返回 null（masker 据此 fail-closed 全抹，D71）。
     */
    private SceneService.SensitiveRefs resolveRefs(String tenantId, Long sessionId) {
        try {
            String sceneCode = auditService.getSessionSceneCode(tenantId, sessionId);
            if (sceneCode == null) return null; // 会话/场景缺失 → fail-closed
            return sceneService.getSensitiveRefs(tenantId, sceneCode);
        } catch (RuntimeException e) {
            log.warn("getSensitiveRefs 失败，trace 读时脱敏 fail-closed 全抹: tenantId={}, sessionId={}",
                    tenantId, sessionId, e);
            return null;
        }
    }
```

- [ ] **Step 4: 运行测试,确认通过**

Run: `$MVN -pl rule-api -am test -Dtest=AuditControllerTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add rule-api/src/main/java/com/sstlfsj/rule/web/admin/AuditController.java \
        rule-api/src/test/java/com/sstlfsj/rule/web/admin/AuditControllerTest.java
git commit -m "feat(api): AuditController trace/tree 接入读时脱敏 + fail-closed(D71)"
```

---

## Task 7: EvalController dry-run 接入脱敏

**Files:**
- Modify: `rule-api/src/main/java/com/sstlfsj/rule/web/api/EvalController.java`
- Test: `rule-api/src/test/java/com/sstlfsj/rule/web/api/EvalControllerTest.java`

- [ ] **Step 1: 写失败测试**

按 `EvalControllerTest.java` 现有风格追加(dry-run 返回的 `EvalResult.nodeTrace` 中敏感叶子被抹)。骨架(按现有 mock 与 `EvalEventRequest` 构造调整):

```java
    @Test
    void dryRun_masksSensitivePayloadLeaf() {
        when(tenantQueryService.resolveIdByCode("acme")).thenReturn(100L);
        when(sceneService.getSensitiveRefs("100", "risk.transfer"))
                .thenReturn(new com.sstlfsj.rule.config.api.service.SceneService.SensitiveRefs(
                        java.util.Set.of("phone"), java.util.Set.of()));
        com.sstlfsj.rule.kernel.api.model.NodeTrace leaf =
                new com.sstlfsj.rule.kernel.api.model.NodeTrace(
                        "ConditionNode", "EQ", "phone", true, "13800001111", "PAYLOAD",
                        null, java.util.List.of(), 1L, "ruleA", 1L, null, null);
        when(evalService.dryRun(any(), any(), any()))
                .thenReturn(new com.sstlfsj.rule.kernel.api.model.EvalResult(
                        true, null, java.util.List.of(), java.util.List.of(leaf),
                        null, null, null, null));

        // req 的 sceneCode 须为 "risk.transfer"、tenantCode 须为 "acme"
        var resp = evalController.dryRun(sampleRequest(), 1L, null);

        assertThat(resp.data().nodeTrace().get(0).actualValue()).isEqualTo("***");
    }
```

(`sampleRequest()` 按现有 `EvalControllerTest` 里构造 `EvalEventRequest` 的方式,sceneCode=`risk.transfer`、tenantCode=`acme`。`resp.data()` 取 `ApiResponse` 内容沿用现有写法。)

- [ ] **Step 2: 运行测试,确认失败**

Run: `$MVN -pl rule-api -am test -Dtest=EvalControllerTest`
Expected: 失败/编译失败 —— 未注入 sceneService、未脱敏。

- [ ] **Step 3: 改 EvalController**

import 区补:
```java
import com.sstlfsj.rule.config.api.service.SceneService;
import com.sstlfsj.rule.web.mask.TraceMasker;
import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
```

字段(已有 `evalService` / `tenantQueryService`)加:
```java
    private static final Logger log = LoggerFactory.getLogger(EvalController.class);

    private final EvalService evalService;
    private final TenantQueryService tenantQueryService;
    private final SceneService sceneService;
```

`dryRun` 改为(在拿到 EvalResult 后按 req 的 tenant/scene 解析敏感集并抹 nodeTrace):
```java
    @PostMapping("/dry-run")
    public ApiResponse<EvalResult> dryRun(
            @RequestBody EvalEventRequest req,
            @RequestParam(required = false) Long ruleId,
            @RequestParam(required = false) Long ruleVersionId) {
        RuleEvent event = toEvent(req);
        EvalResult result = evalService.dryRun(event, ruleId, ruleVersionId);
        List<NodeTrace> masked = TraceMasker.maskKernel(
                resolveRefs(event.tenantId(), req.sceneCode()), result.nodeTrace());
        return ApiResponse.ok(new EvalResult(result.ruleHit(), result.finalDecision(),
                result.hitDecisions(), masked, result.errorCode(), result.score(),
                result.category(), result.decision()));
    }
```

类内加私有 fail-closed 解析方法:
```java
    /** 解析 (租户, 场景) 的 live 敏感集；失败返回 null（masker fail-closed 全抹，D71）。 */
    private SceneService.SensitiveRefs resolveRefs(String tenantId, String sceneCode) {
        try {
            return sceneService.getSensitiveRefs(tenantId, sceneCode);
        } catch (RuntimeException e) {
            log.warn("getSensitiveRefs 失败，dry-run trace 读时脱敏 fail-closed 全抹: tenantId={}, sceneCode={}",
                    tenantId, sceneCode, e);
            return null;
        }
    }
```

(注:`toEvent` 已把 tenantCode 解析成内部 id 并放进 `event.tenantId()`(String),故 `resolveRefs(event.tenantId(), req.sceneCode())` 与 getSensitiveRefs 的 String tenantId 契约一致。)

- [ ] **Step 4: 运行测试,确认通过**

Run: `$MVN -pl rule-api -am test -Dtest=EvalControllerTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add rule-api/src/main/java/com/sstlfsj/rule/web/api/EvalController.java \
        rule-api/src/test/java/com/sstlfsj/rule/web/api/EvalControllerTest.java
git commit -m "feat(api): EvalController dry-run nodeTrace 接入读时脱敏(D71)"
```

---

## Task 8: ReplayController 接入脱敏

**Files:**
- Modify: `rule-api/src/main/java/com/sstlfsj/rule/web/admin/ReplayController.java`
- Test: `rule-api/src/test/java/com/sstlfsj/rule/web/admin/ReplayControllerTest.java`

- [ ] **Step 1: 写失败测试**

按 `ReplayControllerTest.java` 现有风格追加:

```java
    @Test
    void replay_masksSensitiveMetricLeaf() {
        when(auditService.getSessionSceneCode("100", 1L)).thenReturn("risk.transfer");
        when(sceneService.getSensitiveRefs("100", "risk.transfer"))
                .thenReturn(new com.sstlfsj.rule.config.api.service.SceneService.SensitiveRefs(
                        java.util.Set.of(), java.util.Set.of("user.idno")));
        com.sstlfsj.rule.kernel.api.model.NodeTrace leaf =
                new com.sstlfsj.rule.kernel.api.model.NodeTrace(
                        "ConditionNode", "EQ", "user.idno", true, "511...", "FETCHED",
                        null, java.util.List.of(), 1L, "ruleA", 1L, null, null);
        when(replayService.replay("100", 1L))
                .thenReturn(new com.sstlfsj.rule.kernel.api.model.EvalResult(
                        true, null, java.util.List.of(), java.util.List.of(leaf),
                        null, null, null, null));

        var resp = replayController.replay(1L, "100");

        assertThat(resp.data().nodeTrace().get(0).actualValue()).isEqualTo("***");
    }
```

- [ ] **Step 2: 运行测试,确认失败**

Run: `$MVN -pl rule-api -am test -Dtest=ReplayControllerTest`
Expected: 失败/编译失败 —— 未注入 auditService/sceneService、未脱敏。

- [ ] **Step 3: 改 ReplayController**

import 区补:
```java
import com.sstlfsj.rule.audit.api.service.AuditService;
import com.sstlfsj.rule.config.api.service.SceneService;
import com.sstlfsj.rule.web.mask.TraceMasker;
import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
```

字段:
```java
    private static final Logger log = LoggerFactory.getLogger(ReplayController.class);

    private final ReplayService replayService;
    private final AuditService auditService;
    private final SceneService sceneService;
```

`replay` 改为:
```java
    @PostMapping("/evaluation-sessions/{sessionId}/replay")
    public ApiResponse<EvalResult> replay(
            @PathVariable Long sessionId,
            @RequestParam String tenantId) {
        EvalResult result = replayService.replay(tenantId, sessionId);
        List<NodeTrace> masked = TraceMasker.maskKernel(resolveRefs(tenantId, sessionId), result.nodeTrace());
        return ApiResponse.ok(new EvalResult(result.ruleHit(), result.finalDecision(),
                result.hitDecisions(), masked, result.errorCode(), result.score(),
                result.category(), result.decision()));
    }

    /** 解析会话场景的 live 敏感集；失败返回 null（masker fail-closed 全抹，D71）。 */
    private SceneService.SensitiveRefs resolveRefs(String tenantId, Long sessionId) {
        try {
            String sceneCode = auditService.getSessionSceneCode(tenantId, sessionId);
            if (sceneCode == null) return null;
            return sceneService.getSensitiveRefs(tenantId, sceneCode);
        } catch (RuntimeException e) {
            log.warn("getSensitiveRefs 失败，replay trace 读时脱敏 fail-closed 全抹: tenantId={}, sessionId={}",
                    tenantId, sessionId, e);
            return null;
        }
    }
```

- [ ] **Step 4: 运行测试,确认通过**

Run: `$MVN -pl rule-api -am test -Dtest=ReplayControllerTest`
Expected: PASS。

- [ ] **Step 5: rule-api 全模块测试 + 提交**

Run: `$MVN -pl rule-api -am test`
Expected: PASS。

```bash
git add rule-api/src/main/java/com/sstlfsj/rule/web/admin/ReplayController.java \
        rule-api/src/test/java/com/sstlfsj/rule/web/admin/ReplayControllerTest.java
git commit -m "feat(api): ReplayController nodeTrace 接入读时脱敏(D71)"
```

---

## Task 9: 全量回归 + 端到端功能验证

**Files:**
- (按需) Create/扩展 `rule-app` 集成测试,或走真实服务 e2e。

- [ ] **Step 1: 全量 clean test 兜底(跨模块改了实体/接口,必须 clean)**

Run: `$MVN clean test`
Expected: 全绿。若 `NoSuchMethodError` / 编译假象 → 确认未用 `-pl` 漏编。

- [ ] **Step 2: 起真实服务做端到端(按 CLAUDE「功能测试纪律」)**

打可执行包运行 rule-app(勿用 reactor run 目标),确认 Flyway 迁移到 `V1_33`、服务就绪。

- [ ] **Step 3: 按依赖顺序配置 + 核对真落库**

经 API 建:
1. metric(其一 `sensitive=true`,如 `user.idno`):`POST /admin/v1/metrics?tenantId=..&metricCode=user.idno` body 含 `"sensitive":true`。查 `metric_definition.sensitive` 真为 1。
2. scene(payloadSchema 含 `{"name":"phone","type":"STRING","sensitive":true}` 与一个非敏感字段):`POST /admin/v1/scenes`。`GET /admin/v1/scenes/{code}` 回 payloadSchema 中 `phone.sensitive==true`。
3. 规则引用 phone(PAYLOAD)与 user.idno(metric),发布。

- [ ] **Step 4: 核对四出口脱敏 + 非敏感保留**

1. `POST /api/v1/rule/dry-run` 命中事件 → 响应 `nodeTrace` 中 phone / user.idno 叶子 `actualValue=="***"`,非敏感叶子(如 amount)保留原值。
2. 真实评估(`/evaluate` 或 `/event`)后 `GET /admin/v1/evaluation-sessions/{id}/trace` 与 `/trace/tree` → 同上断言。
3. `POST /admin/v1/evaluation-sessions/{id}/replay` 返回 nodeTrace 同样被抹。
4. **核对 raw 仍忠实落库**:直接查 `node_trace.actual_value`,phone/user.idno 行**仍是原值**(脱敏只在读出口,不碰落库)——这是「存原值、读时脱敏」的关键验证。

- [ ] **Step 5: fail-closed 验证**

模拟 config 不可用(如停 config 依赖 / 用不存在的 session 触发 getSessionSceneCode 返回 null)→ 对应出口 `actualValue` 全抹为 `"***"`,日志有 `getSensitiveRefs 失败...fail-closed` warn。

- [ ] **Step 6: DB 字段落库审计 + 清理**

逐表确认本轮新增列 `metric_definition.sensitive` 真落库(敏感 metric 行为 1、其余 0)。验证完删除本次为测试新建的 scene / metric / 规则 / 评估会话数据,恢复干净基线。

---

## Self-Review 备注(已核对)

- **规约 §2/§3 出口**:实际为 `AuditController`(trace/tree)+ `EvalController`(dry-run)+ `ReplayController`(replay),非规约假设的单一 `EvalController`。四出口均接入。
- **规约 "valueSource=METRIC"**:代码无 METRIC 枚举值;映射为「非 PAYLOAD(FETCHED/PROVIDED)→ metric 集」,§4 的 PROVIDED 归 metric 集亦由此覆盖。
- **三形状方法名**:`maskFlat/maskTree/maskKernel`(擦除不允许同名重载);规约 "重载" 措辞按此落地。
- **fail-closed**:`refs==null` 哨兵 + 控制器 try/catch;`getSessionSceneCode` 返回 null(会话缺失)亦走 fail-closed。
- **payload 字段脱敏无 DDL**(存 scene.payload_schema JSON);仅 metric 侧新增列 `V1_33`。
- **§5 测试矩阵**全覆盖(Task5 单测 + Task6-8 控制器 + Task9 e2e)。
