# D13 payloadSchema 完整子集 + 演进 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实装 D13 Scene.payloadSchema 完整 JSON Schema 子集（字段名/类型/required/enum/min/max/pattern）+ schema 演进基础设施（版本号 + 历史快照表），并补全 Scene 创建/更新 API 使其能够持久化这些字段，最终在发布时对 triggerEventTypes 做白名单校验。

**Architecture:**
- `PayloadFieldSpec` record 定义 JSON Schema 子集模型，以 JSON 数组形式存储在 `scene.payload_schema` 列；
- `scene_payload_schema_history` 表保存每次 payloadSchema 变更前的快照，`scene.payload_schema_version` 记录当前版本号；
- 发布时 `PublishService` 追加 triggerEventTypes ⊆ Scene.eventTypes 白名单校验，拒绝引用未声明 eventType 的规则发布。

**Tech Stack:** Java 25 / Spring Boot 4 / MyBatis-Plus / Flyway / Jackson record 支持

---

## 文件清单

| 文件 | 动作 |
|------|------|
| `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/PayloadFieldSpec.java` | 新建 |
| `rule-config-svc/src/main/resources/db/migration/V1_1__scene_payload_schema_version.sql` | 新建 |
| `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/SceneDef.java` | 加 `payloadSchemaVersion` 字段 |
| `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/ScenePayloadSchemaHistory.java` | 新建 |
| `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/repository/ScenePayloadSchemaHistoryMapper.java` | 新建 |
| `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/SceneDetailDto.java` | 新建 |
| `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/SceneService.java` | 更新三个方法签名 + 加 `getScene` |
| `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/SceneServiceImpl.java` | 完整重写 |
| `rule-api/src/main/java/com/sstlfsj/rule/web/config/dto/CreateSceneRequest.java` | 加 D13 字段 |
| `rule-api/src/main/java/com/sstlfsj/rule/web/config/dto/UpdateSceneRequest.java` | 新建 |
| `rule-api/src/main/java/com/sstlfsj/rule/web/config/SceneController.java` | 加 PATCH + GET 端点 |
| `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PublishService.java` | 加 triggerEventTypes 校验 |
| `rule-config-svc/src/test/java/com/sstlfsj/rule/config/api/dto/PayloadFieldSpecTest.java` | 新建 |
| `rule-config-svc/src/test/java/com/sstlfsj/rule/config/api/service/SceneServiceTest.java` | 更新 stub 签名 |
| `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/service/SceneServiceImplTest.java` | 加新测试 |
| `rule-api/src/test/java/com/sstlfsj/rule/web/config/SceneControllerTest.java` | 加新测试 |
| `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/PublishServiceTest.java` | 加校验测试 |
| `docs/08-evolution.md` | §2.12 状态更新 |

---

## Task 1：PayloadFieldSpec + Flyway V1_1（history 表 + version 列）

**Files:**
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/PayloadFieldSpec.java`
- Create: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/api/dto/PayloadFieldSpecTest.java`
- Create: `rule-config-svc/src/main/resources/db/migration/V1_1__scene_payload_schema_version.sql`
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/SceneDef.java`
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/ScenePayloadSchemaHistory.java`
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/repository/ScenePayloadSchemaHistoryMapper.java`

- [ ] **Step 1: 写失败测试**

新建 `rule-config-svc/src/test/java/com/sstlfsj/rule/config/api/dto/PayloadFieldSpecTest.java`：

```java
package com.sstlfsj.rule.config.api.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PayloadFieldSpecTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void payloadFieldSpec_serializes_名称类型required() throws Exception {
        PayloadFieldSpec field = new PayloadFieldSpec(
                "amount", "NUMBER", true, null, 0.0, 999999.0, null, "交易金额");
        String json = mapper.writeValueAsString(field);
        assertThat(json).contains("\"name\":\"amount\"");
        assertThat(json).contains("\"type\":\"NUMBER\"");
        assertThat(json).contains("\"required\":true");
        assertThat(json).contains("\"minimum\":0.0");
        assertThat(json).contains("\"maximum\":999999.0");
    }

    @Test
    void payloadFieldSpec_withEnum_序列化enum键() throws Exception {
        PayloadFieldSpec field = new PayloadFieldSpec(
                "currency", "STRING", true, List.of("CNY", "USD"), null, null, null, null);
        String json = mapper.writeValueAsString(field);
        assertThat(json).contains("\"enum\":[\"CNY\",\"USD\"]");
    }

    @Test
    void payloadFieldSpec_roundTrip反序列化() throws Exception {
        PayloadFieldSpec original = new PayloadFieldSpec(
                "userId", "STRING", true, null, null, null, "[A-Za-z0-9]{8}", "用户ID");
        String json = mapper.writeValueAsString(original);
        PayloadFieldSpec deserialized = mapper.readValue(json, PayloadFieldSpec.class);
        assertThat(deserialized.name()).isEqualTo("userId");
        assertThat(deserialized.type()).isEqualTo("STRING");
        assertThat(deserialized.required()).isTrue();
        assertThat(deserialized.pattern()).isEqualTo("[A-Za-z0-9]{8}");
    }

    @Test
    void payloadFieldSpec_list_roundTrip() throws Exception {
        List<PayloadFieldSpec> specs = List.of(
                new PayloadFieldSpec("amount", "NUMBER", true, null, 0.0, null, null, null),
                new PayloadFieldSpec("currency", "STRING", true, List.of("CNY", "USD"), null, null, null, null)
        );
        String json = mapper.writeValueAsString(specs);
        List<PayloadFieldSpec> result = mapper.readValue(json, new TypeReference<>() {});
        assertThat(result).hasSize(2);
        assertThat(result.get(0).name()).isEqualTo("amount");
        assertThat(result.get(1).enumValues()).containsExactly("CNY", "USD");
    }
}
```

- [ ] **Step 2: 运行失败测试（确认 PayloadFieldSpec 未存在）**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
$MVN -pl rule-config-svc -am test -Dtest=PayloadFieldSpecTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL（PayloadFieldSpec 类不存在）

- [ ] **Step 3: 新建 `PayloadFieldSpec`**

新建 `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/PayloadFieldSpec.java`：

```java
package com.sstlfsj.rule.config.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * payloadSchema 单字段声明（JSON Schema 完整子集）。
 * 以 JSON 数组形式存入 scene.payload_schema 列，每个元素对应此 record。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
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
        String description
) {}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
$MVN -pl rule-config-svc -am test -Dtest=PayloadFieldSpecTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: 全部 4 个测试通过

- [ ] **Step 5: 写 Flyway V1_1 迁移脚本**

新建 `rule-config-svc/src/main/resources/db/migration/V1_1__scene_payload_schema_version.sql`：

```sql
-- D13 演进基础设施：为 scene 表添加 payload_schema_version，新增 schema 历史快照表

ALTER TABLE scene
  ADD COLUMN payload_schema_version INT NOT NULL DEFAULT 1
    COMMENT 'payloadSchema 当前版本号，初始为 1，每次更新自增';

CREATE TABLE IF NOT EXISTS scene_payload_schema_history (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  scene_id    BIGINT       NOT NULL COMMENT '所属 scene.id',
  version     INT          NOT NULL COMMENT '该快照对应的版本号（变更前的版本）',
  schema_json JSON         NOT NULL COMMENT '历史 payloadSchema JSON 数组快照',
  created_by  VARCHAR(64)  COMMENT '触发变更的操作人',
  created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_scene_ver (scene_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='Scene payloadSchema 历史版本快照（D13 演进基础设施）';
```

- [ ] **Step 6: 新建 `ScenePayloadSchemaHistory` 实体 + Mapper**

新建 `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/ScenePayloadSchemaHistory.java`：

```java
package com.sstlfsj.rule.config.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** scene_payload_schema_history 表实体，记录 payloadSchema 每次变更前的快照（D13）。 */
@Getter
@Setter
@TableName("scene_payload_schema_history")
public class ScenePayloadSchemaHistory {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sceneId;
    /** 快照对应的 payloadSchema 版本号（变更前的版本，用于溯源）。 */
    private Integer version;
    /** payloadSchema JSON 数组字符串快照。 */
    private String schemaJson;
    private String createdBy;
    private LocalDateTime createdAt;
}
```

新建 `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/repository/ScenePayloadSchemaHistoryMapper.java`：

```java
package com.sstlfsj.rule.config.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.config.internal.domain.ScenePayloadSchemaHistory;
import org.apache.ibatis.annotations.Mapper;

/** scene_payload_schema_history 表 Mapper（D13 演进快照）。 */
@Mapper
public interface ScenePayloadSchemaHistoryMapper extends BaseMapper<ScenePayloadSchemaHistory> {}
```

- [ ] **Step 7: `SceneDef` 加 `payloadSchemaVersion` 字段**

在 `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/SceneDef.java` 的 `defaultParams` 字段后加：

```java
/** payloadSchema 当前版本号，初始为 1，每次更新自增（D13 演进）。 */
private Integer payloadSchemaVersion;
```

完整字段列表（修改后）：
```java
@TableId(type = IdType.AUTO)
private Long id;
private Long tenantId;
private String code;
private String name;
private String description;
private String dominantMode;
private String decisionStrategy;
private String subjectType;
/** JSON 数组字符串，存储允许的 eventType 白名单。 */
private String eventTypes;
/** JSON 对象字符串，存储 payloadSchema 字段类型声明。 */
private String payloadSchema;
/** JSON 对象字符串，存储 Scene 默认参数。 */
private String defaultParams;
/** payloadSchema 当前版本号，初始为 1，每次更新自增（D13 演进）。 */
private Integer payloadSchemaVersion;
private String status;
private String createdBy;
private java.time.LocalDateTime createdAt;
private String updatedBy;
private java.time.LocalDateTime updatedAt;
```

- [ ] **Step 8: 运行 rule-config-svc 全量测试（确认现有测试仍通过）**

```bash
$MVN -pl rule-config-svc -am test
```

Expected: BUILD SUCCESS

- [ ] **Step 9: commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/PayloadFieldSpec.java
git add rule-config-svc/src/test/java/com/sstlfsj/rule/config/api/dto/PayloadFieldSpecTest.java
git add rule-config-svc/src/main/resources/db/migration/V1_1__scene_payload_schema_version.sql
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/ScenePayloadSchemaHistory.java
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/repository/ScenePayloadSchemaHistoryMapper.java
git add -u
git commit -m "feat(config): D13 PayloadFieldSpec + Flyway V1_1 历史表 + SceneDef.payloadSchemaVersion"
```

---

## Task 2：Scene 创建 API 扩展 — 接受 D13 字段

**Files:**
- Modify: `rule-api/src/main/java/com/sstlfsj/rule/web/config/dto/CreateSceneRequest.java`
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/SceneService.java`
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/SceneServiceImpl.java`
- Modify: `rule-api/src/main/java/com/sstlfsj/rule/web/config/SceneController.java`
- Modify: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/api/service/SceneServiceTest.java`
- Modify: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/service/SceneServiceImplTest.java`
- Modify: `rule-api/src/test/java/com/sstlfsj/rule/web/config/SceneControllerTest.java`

- [ ] **Step 1: 写失败测试（SceneServiceImplTest 新增 payloadSchema 持久化测试）**

在 `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/service/SceneServiceImplTest.java` 做以下修改：

1. 在 `@Mock` 声明区加 `@Mock ScenePayloadSchemaHistoryMapper schemaHistoryMapper;`
2. 将原有 `createScene_insertsSceneAndWritesAuditLog` 测试改为新签名调用：
   ```java
   sceneService.createScene("1", "PAYMENT", "支付场景",
           null, null, null, null, null, null, "actor1");
   ```
3. 新增以下两个测试方法：

```java
@Test
void createScene_withPayloadSchema_持久化所有D13字段() {
    when(sceneMapper.insert((SceneDef) any())).thenReturn(1);
    when(auditLogMapper.insert((AuditLog) any())).thenReturn(1);
    when(schemaHistoryMapper.insert((ScenePayloadSchemaHistory) any())).thenReturn(1);

    sceneService.createScene("1", "PAYMENT", "支付场景",
            "支付业务场景", "PUSH", "USER",
            "[\"payment.initiated\"]",
            "[{\"name\":\"amount\",\"type\":\"NUMBER\",\"required\":true}]",
            "{\"timezone\":\"Asia/Shanghai\"}", "actor1");

    ArgumentCaptor<SceneDef> sceneCaptor = ArgumentCaptor.forClass(SceneDef.class);
    verify(sceneMapper).insert(sceneCaptor.capture());
    SceneDef saved = sceneCaptor.getValue();
    assertThat(saved.getEventTypes()).isEqualTo("[\"payment.initiated\"]");
    assertThat(saved.getPayloadSchema()).contains("amount");
    assertThat(saved.getDefaultParams()).contains("Asia/Shanghai");
    assertThat(saved.getPayloadSchemaVersion()).isEqualTo(1);

    // 有 payloadSchema 时应写入初始历史快照
    ArgumentCaptor<ScenePayloadSchemaHistory> histCaptor =
            ArgumentCaptor.forClass(ScenePayloadSchemaHistory.class);
    verify(schemaHistoryMapper).insert(histCaptor.capture());
    assertThat(histCaptor.getValue().getVersion()).isEqualTo(1);
}

@Test
void createScene_withoutPayloadSchema_不写历史快照() {
    when(sceneMapper.insert((SceneDef) any())).thenReturn(1);
    when(auditLogMapper.insert((AuditLog) any())).thenReturn(1);

    sceneService.createScene("1", "PAYMENT", "支付场景",
            null, null, null, null, null, null, "actor1");

    verify(sceneMapper).insert((SceneDef) any());
    verify(schemaHistoryMapper, never()).insert((ScenePayloadSchemaHistory) any());
}
```

- [ ] **Step 2: 运行确认测试失败（方法签名不匹配）**

```bash
$MVN -pl rule-config-svc -am test -Dtest=SceneServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL（编译错误，createScene 参数不匹配）

- [ ] **Step 3: 更新 `SceneService` 接口签名**

将 `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/SceneService.java` 全文替换为：

```java
package com.sstlfsj.rule.config.api.service;

import com.sstlfsj.rule.config.api.dto.SceneDetailDto;

/** 场景生命周期管理：创建、更新、查询、禁用。 */
public interface SceneService {

    /**
     * 为指定租户创建新场景（含 D13 元数据）。
     *
     * @param tenantId          租户 ID
     * @param sceneCode         租户内唯一的场景编码
     * @param name              场景展示名称
     * @param description       场景业务说明（可为 null）
     * @param dominantMode      PUSH / PULL / HYBRID（null 时默认 PUSH）
     * @param subjectType       USER / ACCOUNT / DEVICE / ORDER / CUSTOM（null 时默认 USER）
     * @param eventTypesJson    允许的 eventType 白名单 JSON 数组字符串（null 时默认 "[]"）
     * @param payloadSchemaJson payloadSchema JSON 数组字符串（null 表示暂不设置）
     * @param defaultParamsJson 默认参数 JSON 对象字符串（null 表示暂不设置）
     * @param actorId           创建操作人 ID
     * @return 新创建场景的 ID
     */
    Long createScene(String tenantId, String sceneCode, String name,
                     String description, String dominantMode, String subjectType,
                     String eventTypesJson, String payloadSchemaJson, String defaultParamsJson,
                     String actorId);

    /**
     * 更新已有场景元数据。payloadSchema 发生变化时自动快照历史版本并自增版本号。
     *
     * @param tenantId          租户 ID
     * @param sceneCode         待更新的场景编码
     * @param name              新名称（null 表示不更新）
     * @param eventTypesJson    新 eventType 白名单（null 表示不更新）
     * @param payloadSchemaJson 新 payloadSchema（null 表示不更新）
     * @param defaultParamsJson 新 defaultParams（null 表示不更新）
     * @param actorId           更新操作人 ID
     */
    void updateScene(String tenantId, String sceneCode,
                     String name, String eventTypesJson,
                     String payloadSchemaJson, String defaultParamsJson,
                     String actorId);

    /**
     * 查询场景详情（含 payloadSchema / eventTypes 等 D13 字段）。
     *
     * @param tenantId  租户 ID
     * @param sceneCode 场景编码
     * @return 场景详情 DTO
     */
    SceneDetailDto getScene(String tenantId, String sceneCode);

    /**
     * 禁用场景，禁用后不再参与规则评估匹配。
     *
     * @param tenantId  租户 ID
     * @param sceneCode 待禁用的场景编码
     * @param actorId   禁用操作人 ID
     */
    void disableScene(String tenantId, String sceneCode, String actorId);
}
```

- [ ] **Step 4: 重写 `SceneServiceImpl`**

将 `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/SceneServiceImpl.java` 全文替换为：

```java
package com.sstlfsj.rule.config.internal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sstlfsj.rule.config.api.dto.PayloadFieldSpec;
import com.sstlfsj.rule.config.api.dto.SceneDetailDto;
import com.sstlfsj.rule.config.api.event.SceneChangedEvent;
import com.sstlfsj.rule.config.api.service.SceneService;
import com.sstlfsj.rule.config.internal.domain.AuditLog;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.domain.ScenePayloadSchemaHistory;
import com.sstlfsj.rule.config.internal.repository.AuditLogMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import com.sstlfsj.rule.config.internal.repository.ScenePayloadSchemaHistoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** SceneService 实现：Scene CRUD + payloadSchema 演进快照 + SceneChangedEvent（D13）。 */
@Service
@RequiredArgsConstructor
class SceneServiceImpl implements SceneService {

    private final SceneMapper sceneMapper;
    private final AuditLogMapper auditLogMapper;
    private final ScenePayloadSchemaHistoryMapper schemaHistoryMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    @Transactional
    public Long createScene(String tenantId, String sceneCode, String name,
                            String description, String dominantMode, String subjectType,
                            String eventTypesJson, String payloadSchemaJson, String defaultParamsJson,
                            String actorId) {
        SceneDef scene = new SceneDef();
        scene.setTenantId(Long.valueOf(tenantId));
        scene.setCode(sceneCode);
        scene.setName(name);
        scene.setDescription(description);
        scene.setDominantMode(dominantMode != null ? dominantMode : "PUSH");
        scene.setDecisionStrategy("HIGHEST_PRIORITY");
        scene.setSubjectType(subjectType != null ? subjectType : "USER");
        scene.setEventTypes(eventTypesJson != null ? eventTypesJson : "[]");
        scene.setPayloadSchema(payloadSchemaJson);
        scene.setDefaultParams(defaultParamsJson);
        scene.setPayloadSchemaVersion(1);
        scene.setStatus("ACTIVE");
        scene.setCreatedBy(actorId);
        sceneMapper.insert(scene);

        // 有 payloadSchema 时写入初始历史快照（version=1）
        if (payloadSchemaJson != null) {
            snapshotSchema(scene.getId(), 1, payloadSchemaJson, actorId);
        }

        writeAudit(Long.valueOf(tenantId), actorId, "CREATE", "scene",
                scene.getId() != null ? scene.getId().toString() : sceneCode);
        return scene.getId();
    }

    @Override
    @Transactional
    public void updateScene(String tenantId, String sceneCode,
                            String name, String eventTypesJson,
                            String payloadSchemaJson, String defaultParamsJson,
                            String actorId) {
        SceneDef scene = findScene(Long.valueOf(tenantId), sceneCode);

        if (name != null) scene.setName(name);
        if (eventTypesJson != null) scene.setEventTypes(eventTypesJson);
        if (defaultParamsJson != null) scene.setDefaultParams(defaultParamsJson);

        // payloadSchema 变更时快照旧版本并自增版本号
        if (payloadSchemaJson != null && !payloadSchemaJson.equals(scene.getPayloadSchema())) {
            int oldVersion = scene.getPayloadSchemaVersion() != null
                    ? scene.getPayloadSchemaVersion() : 1;
            // 旧版本不为 null 时才写历史（创建时已写 version=1 快照）
            if (scene.getPayloadSchema() != null) {
                snapshotSchema(scene.getId(), oldVersion, scene.getPayloadSchema(), actorId);
            }
            scene.setPayloadSchema(payloadSchemaJson);
            scene.setPayloadSchemaVersion(oldVersion + 1);
        }

        scene.setUpdatedBy(actorId);
        scene.setUpdatedAt(LocalDateTime.now());
        sceneMapper.updateById(scene);
        writeAudit(Long.valueOf(tenantId), actorId, "UPDATE", "scene", scene.getId().toString());
    }

    @Override
    public SceneDetailDto getScene(String tenantId, String sceneCode) {
        SceneDef scene = findScene(Long.valueOf(tenantId), sceneCode);
        return toDto(scene);
    }

    @Override
    @Transactional
    public void disableScene(String tenantId, String sceneCode, String actorId) {
        SceneDef scene = findScene(Long.valueOf(tenantId), sceneCode);
        scene.setStatus("DISABLED");
        scene.setUpdatedBy(actorId);
        scene.setUpdatedAt(LocalDateTime.now());
        sceneMapper.updateById(scene);
        writeAudit(Long.valueOf(tenantId), actorId, "DISABLE", "scene", scene.getId().toString());
        eventPublisher.publishEvent(new SceneChangedEvent(tenantId, sceneCode, false));
    }

    private SceneDef findScene(Long tenantId, String sceneCode) {
        SceneDef scene = sceneMapper.selectOne(
                new LambdaQueryWrapper<SceneDef>()
                        .eq(SceneDef::getTenantId, tenantId)
                        .eq(SceneDef::getCode, sceneCode));
        if (scene == null) {
            throw new IllegalArgumentException("Scene 不存在: " + sceneCode);
        }
        return scene;
    }

    private void snapshotSchema(Long sceneId, int version, String schemaJson, String actorId) {
        ScenePayloadSchemaHistory hist = new ScenePayloadSchemaHistory();
        hist.setSceneId(sceneId);
        hist.setVersion(version);
        hist.setSchemaJson(schemaJson);
        hist.setCreatedBy(actorId);
        hist.setCreatedAt(LocalDateTime.now());
        schemaHistoryMapper.insert(hist);
    }

    private SceneDetailDto toDto(SceneDef scene) {
        List<String> eventTypes = parseStringList(scene.getEventTypes());
        List<PayloadFieldSpec> payloadSchema = scene.getPayloadSchema() != null
                ? parseSchemaList(scene.getPayloadSchema())
                : List.of();
        Map<String, Object> defaultParams = scene.getDefaultParams() != null
                ? parseMap(scene.getDefaultParams())
                : Map.of();
        int version = scene.getPayloadSchemaVersion() != null ? scene.getPayloadSchemaVersion() : 1;
        return new SceneDetailDto(
                scene.getId(),
                String.valueOf(scene.getTenantId()),
                scene.getCode(),
                scene.getName(),
                scene.getDescription(),
                scene.getDominantMode(),
                scene.getSubjectType(),
                eventTypes,
                payloadSchema,
                defaultParams,
                version,
                scene.getStatus()
        );
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<PayloadFieldSpec> parseSchemaList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<PayloadFieldSpec>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private void writeAudit(Long tenantId, String actor, String action,
                             String targetType, String targetId) {
        AuditLog log = new AuditLog();
        log.setTenantId(tenantId);
        log.setActor(actor);
        log.setActorType("USER");
        log.setAction(action);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setOperatedAt(LocalDateTime.now());
        auditLogMapper.insert(log);
    }
}
```

- [ ] **Step 5: 新建 `SceneDetailDto`**

新建 `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/SceneDetailDto.java`：

```java
package com.sstlfsj.rule.config.api.dto;

import java.util.List;
import java.util.Map;

/**
 * Scene 详情响应 DTO（D13），包含 payloadSchema / eventTypes / defaultParams 等元数据。
 */
public record SceneDetailDto(
        Long id,
        String tenantId,
        String sceneCode,
        String name,
        String description,
        String dominantMode,
        String subjectType,
        List<String> eventTypes,
        List<PayloadFieldSpec> payloadSchema,
        Map<String, Object> defaultParams,
        int payloadSchemaVersion,
        String status
) {}
```

- [ ] **Step 6: 更新 `CreateSceneRequest`**

将 `rule-api/src/main/java/com/sstlfsj/rule/web/config/dto/CreateSceneRequest.java` 全文替换为：

```java
package com.sstlfsj.rule.web.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** 创建场景请求体（D13 扩展：含 payloadSchema / eventTypes / dominantMode 等）。 */
public record CreateSceneRequest(
        @NotBlank String tenantId,
        @NotBlank String sceneCode,
        @NotBlank String name,
        String description,
        String dominantMode,
        String subjectType,
        List<String> eventTypes,
        JsonNode payloadSchema,
        JsonNode defaultParams
) {}
```

- [ ] **Step 7: 更新 `SceneController`**

将 `rule-api/src/main/java/com/sstlfsj/rule/web/config/SceneController.java` 全文替换为（暂时只加 createScene 更新，PATCH + GET 在后续 Task 中补）：

```java
package com.sstlfsj.rule.web.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sstlfsj.rule.config.api.service.SceneService;
import com.sstlfsj.rule.web.common.ApiResponse;
import com.sstlfsj.rule.web.config.dto.CreateSceneRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 场景管理入口：创建、更新、查询场景（D13）。 */
@RestController
@RequestMapping("/api/v1/scenes")
public class SceneController {

    private final SceneService sceneService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SceneController(SceneService sceneService) {
        this.sceneService = sceneService;
    }

    /**
     * POST /api/v1/scenes — 创建场景（含 payloadSchema / eventTypes 等 D13 字段）。
     */
    @PostMapping
    public ApiResponse<Map<String, Object>> createScene(
            @Valid @RequestBody CreateSceneRequest req,
            @RequestHeader("X-Actor-Id") String actorId) throws JsonProcessingException {
        String eventTypesJson = req.eventTypes() != null
                ? objectMapper.writeValueAsString(req.eventTypes()) : null;
        Long id = sceneService.createScene(
                req.tenantId(), req.sceneCode(), req.name(),
                req.description(), req.dominantMode(), req.subjectType(),
                eventTypesJson,
                jsonToString(req.payloadSchema()),
                jsonToString(req.defaultParams()),
                actorId);
        return ApiResponse.ok(Map.of("id", id));
    }

    private String jsonToString(JsonNode node) throws JsonProcessingException {
        return node == null ? null : objectMapper.writeValueAsString(node);
    }
}
```

- [ ] **Step 8: 更新 `SceneServiceTest`（契约 stub）**

将 `rule-config-svc/src/test/java/com/sstlfsj/rule/config/api/service/SceneServiceTest.java` 全文替换为：

```java
package com.sstlfsj.rule.config.api.service;

import com.sstlfsj.rule.config.api.dto.SceneDetailDto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证 SceneService 契约：stub 实现编译通过、方法签名匹配。 */
class SceneServiceTest {

    private final SceneService stub = new SceneService() {
        @Override
        public Long createScene(String tenantId, String sceneCode, String name,
                                String description, String dominantMode, String subjectType,
                                String eventTypesJson, String payloadSchemaJson,
                                String defaultParamsJson, String actorId) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void updateScene(String tenantId, String sceneCode,
                                String name, String eventTypesJson,
                                String payloadSchemaJson, String defaultParamsJson,
                                String actorId) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public SceneDetailDto getScene(String tenantId, String sceneCode) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void disableScene(String tenantId, String sceneCode, String actorId) {
            throw new UnsupportedOperationException("stub");
        }
    };

    @Test
    void createScene_stubThrowsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> stub.createScene("t1", "SCENE_A", "场景A",
                        null, null, null, null, null, null, "actor"));
    }

    @Test
    void updateScene_stubThrowsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> stub.updateScene("t1", "SCENE_A", null, null, null, null, "actor"));
    }

    @Test
    void getScene_stubThrowsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> stub.getScene("t1", "SCENE_A"));
    }

    @Test
    void disableScene_stubThrowsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> stub.disableScene("t1", "SCENE_A", "actor"));
    }
}
```

- [ ] **Step 9: 更新 `SceneControllerTest`**

将 `rule-api/src/test/java/com/sstlfsj/rule/web/config/SceneControllerTest.java` 全文替换为：

```java
package com.sstlfsj.rule.web.config;

import com.sstlfsj.rule.config.api.service.SceneService;
import com.sstlfsj.rule.web.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** SceneController 单元测试。 */
class SceneControllerTest {

    private MockMvc mockMvc;
    private SceneService sceneService;

    @BeforeEach
    void setUp() throws Exception {
        sceneService = mock(SceneService.class);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new SceneController(sceneService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void createScene_returns200_withId() throws Exception {
        when(sceneService.createScene(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any())).thenReturn(42L);

        mockMvc.perform(post("/api/v1/scenes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor-Id", "user1")
                        .content("""
                            {"tenantId":"t1","sceneCode":"fraud","name":"欺诈检测"}
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(42));

        verify(sceneService).createScene(
                eq("t1"), eq("fraud"), eq("欺诈检测"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq("user1"));
    }

    @Test
    void createScene_withPayloadSchema_传入序列化后的字符串() throws Exception {
        when(sceneService.createScene(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any())).thenReturn(99L);

        mockMvc.perform(post("/api/v1/scenes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor-Id", "user1")
                        .content("""
                            {
                              "tenantId":"t1",
                              "sceneCode":"payment",
                              "name":"支付场景",
                              "eventTypes":["payment.initiated"],
                              "payloadSchema":[{"name":"amount","type":"NUMBER","required":true}]
                            }
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(99));

        verify(sceneService).createScene(
                eq("t1"), eq("payment"), eq("支付场景"),
                isNull(), isNull(), isNull(),
                eq("[\"payment.initiated\"]"),
                argThat(s -> s != null && s.contains("amount")),
                isNull(), eq("user1"));
    }

    @Test
    void createScene_missingTenantId_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/scenes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor-Id", "user1")
                        .content("""
                            {"sceneCode":"fraud","name":"欺诈检测"}
                            """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("INVALID_ARGUMENT"));
    }
}
```

- [ ] **Step 10: 运行 rule-config-svc + rule-api 全量测试**

```bash
$MVN -pl rule-config-svc,rule-api -am test
```

Expected: BUILD SUCCESS

- [ ] **Step 11: commit**

```bash
git add -u
git commit -m "feat(config): Scene 创建 API 扩展——payloadSchema/eventTypes/defaultParams（D13）"
```

---

## Task 3：updateScene 实现 + payloadSchema 演进快照 + PATCH 端点

**Files:**
- Create: `rule-api/src/main/java/com/sstlfsj/rule/web/config/dto/UpdateSceneRequest.java`
- Modify: `rule-api/src/main/java/com/sstlfsj/rule/web/config/SceneController.java`
- Modify: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/service/SceneServiceImplTest.java`
- Modify: `rule-api/src/test/java/com/sstlfsj/rule/web/config/SceneControllerTest.java`

（SceneService + SceneServiceImpl 在 Task 2 中已实现 updateScene，此任务补 HTTP 层 + 测试）

- [ ] **Step 1: 写失败测试（SceneServiceImplTest 演进快照测试）**

在 `SceneServiceImplTest.java` 中追加：

```java
@Test
void updateScene_payloadSchema变更_快照旧版本并自增版本号() {
    SceneDef existing = new SceneDef();
    existing.setId(10L);
    existing.setTenantId(1L);
    existing.setCode("PAYMENT");
    existing.setPayloadSchema("[{\"name\":\"amount\",\"type\":\"NUMBER\",\"required\":true}]");
    existing.setPayloadSchemaVersion(1);
    existing.setEventTypes("[]");
    when(sceneMapper.selectOne(any())).thenReturn(existing);
    when(sceneMapper.updateById((SceneDef) any())).thenReturn(1);
    when(auditLogMapper.insert((AuditLog) any())).thenReturn(1);
    when(schemaHistoryMapper.insert((ScenePayloadSchemaHistory) any())).thenReturn(1);

    String newSchema = "[{\"name\":\"amount\",\"type\":\"NUMBER\",\"required\":true},"
            + "{\"name\":\"currency\",\"type\":\"STRING\",\"required\":true}]";
    sceneService.updateScene("1", "PAYMENT", null, null, newSchema, null, "actor1");

    // 旧版本应写入历史
    ArgumentCaptor<ScenePayloadSchemaHistory> histCaptor =
            ArgumentCaptor.forClass(ScenePayloadSchemaHistory.class);
    verify(schemaHistoryMapper).insert(histCaptor.capture());
    assertThat(histCaptor.getValue().getVersion()).isEqualTo(1);
    assertThat(histCaptor.getValue().getSchemaJson()).contains("amount");

    // scene 版本号自增为 2
    ArgumentCaptor<SceneDef> sceneCaptor = ArgumentCaptor.forClass(SceneDef.class);
    verify(sceneMapper).updateById(sceneCaptor.capture());
    assertThat(sceneCaptor.getValue().getPayloadSchemaVersion()).isEqualTo(2);
    assertThat(sceneCaptor.getValue().getPayloadSchema()).contains("currency");
}

@Test
void updateScene_payloadSchema未变更_不写历史不变版本号() {
    SceneDef existing = new SceneDef();
    existing.setId(10L);
    existing.setTenantId(1L);
    existing.setCode("PAYMENT");
    existing.setPayloadSchema("[{\"name\":\"amount\",\"type\":\"NUMBER\"}]");
    existing.setPayloadSchemaVersion(2);
    existing.setEventTypes("[\"payment.initiated\"]");
    when(sceneMapper.selectOne(any())).thenReturn(existing);
    when(sceneMapper.updateById((SceneDef) any())).thenReturn(1);
    when(auditLogMapper.insert((AuditLog) any())).thenReturn(1);

    // 传入与现有相同的 payloadSchema
    sceneService.updateScene("1", "PAYMENT", "新名称", null,
            "[{\"name\":\"amount\",\"type\":\"NUMBER\"}]", null, "actor1");

    verify(schemaHistoryMapper, never()).insert((ScenePayloadSchemaHistory) any());
    ArgumentCaptor<SceneDef> sceneCaptor = ArgumentCaptor.forClass(SceneDef.class);
    verify(sceneMapper).updateById(sceneCaptor.capture());
    assertThat(sceneCaptor.getValue().getPayloadSchemaVersion()).isEqualTo(2);
    assertThat(sceneCaptor.getValue().getName()).isEqualTo("新名称");
}
```

- [ ] **Step 2: 运行确认测试通过（updateScene 已在 Task 2 实现）**

```bash
$MVN -pl rule-config-svc -am test -Dtest=SceneServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: 全部测试通过

- [ ] **Step 3: 新建 `UpdateSceneRequest`**

新建 `rule-api/src/main/java/com/sstlfsj/rule/web/config/dto/UpdateSceneRequest.java`：

```java
package com.sstlfsj.rule.web.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** 更新场景请求体；所有字段均可选，null 表示不更新该字段。 */
public record UpdateSceneRequest(
        @NotBlank String tenantId,
        String name,
        List<String> eventTypes,
        JsonNode payloadSchema,
        JsonNode defaultParams
) {}
```

- [ ] **Step 4: 在 `SceneController` 加 PATCH 端点**

在 `SceneController.java` 的 `createScene` 方法后追加：

```java
/**
 * PATCH /api/v1/scenes/{sceneCode} — 更新场景元数据（payloadSchema / eventTypes 等）。
 * payloadSchema 发生变化时自动快照历史版本并自增版本号。
 */
@PatchMapping("/{sceneCode}")
public ApiResponse<Void> updateScene(
        @PathVariable String sceneCode,
        @Valid @RequestBody UpdateSceneRequest req,
        @RequestHeader("X-Actor-Id") String actorId) throws JsonProcessingException {
    String eventTypesJson = req.eventTypes() != null
            ? objectMapper.writeValueAsString(req.eventTypes()) : null;
    sceneService.updateScene(
            req.tenantId(), sceneCode,
            req.name(), eventTypesJson,
            jsonToString(req.payloadSchema()),
            jsonToString(req.defaultParams()),
            actorId);
    return ApiResponse.ok(null);
}
```

- [ ] **Step 5: 在 `SceneControllerTest` 加 PATCH 测试**

在 `SceneControllerTest.java` 追加：

```java
@Test
void patchScene_returns200() throws Exception {
    doNothing().when(sceneService).updateScene(any(), any(), any(), any(), any(), any(), any());

    mockMvc.perform(patch("/api/v1/scenes/payment")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Actor-Id", "user1")
                    .content("""
                        {
                          "tenantId":"t1",
                          "payloadSchema":[{"name":"amount","type":"NUMBER","required":true}]
                        }
                        """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

    verify(sceneService).updateScene(
            eq("t1"), eq("payment"), isNull(), isNull(),
            argThat(s -> s != null && s.contains("amount")),
            isNull(), eq("user1"));
}

@Test
void patchScene_missingTenantId_returns400() throws Exception {
    mockMvc.perform(patch("/api/v1/scenes/payment")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Actor-Id", "user1")
                    .content("""{"name":"新名称"}"""))
            .andExpect(status().isBadRequest());
}
```

- [ ] **Step 6: 运行 rule-config-svc + rule-api 全量测试**

```bash
$MVN -pl rule-config-svc,rule-api -am test
```

Expected: BUILD SUCCESS

- [ ] **Step 7: commit**

```bash
git add rule-api/src/main/java/com/sstlfsj/rule/web/config/dto/UpdateSceneRequest.java
git add -u
git commit -m "feat(api): PATCH /api/v1/scenes/{sceneCode} + payloadSchema 演进快照（D13）"
```

---

## Task 4：GET /api/v1/scenes/{sceneCode} 读取端点

**Files:**
- Modify: `rule-api/src/main/java/com/sstlfsj/rule/web/config/SceneController.java`
- Modify: `rule-api/src/test/java/com/sstlfsj/rule/web/config/SceneControllerTest.java`
- Modify: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/service/SceneServiceImplTest.java`

（SceneService.getScene + SceneDetailDto 在 Task 2 中已实现，此任务补 HTTP 层 + 测试）

- [ ] **Step 1: 写失败测试（SceneControllerTest GET 端点）**

在 `SceneControllerTest.java` 追加：

```java
@Test
void getScene_returns200_withDetail() throws Exception {
    com.sstlfsj.rule.config.api.dto.SceneDetailDto dto =
            new com.sstlfsj.rule.config.api.dto.SceneDetailDto(
                    5L, "t1", "payment", "支付场景",
                    null, "PUSH", "USER",
                    java.util.List.of("payment.initiated"),
                    java.util.List.of(),
                    java.util.Map.of("timezone", "Asia/Shanghai"),
                    1, "ACTIVE");
    when(sceneService.getScene("t1", "payment")).thenReturn(dto);

    mockMvc.perform(get("/api/v1/scenes/payment")
                    .param("tenantId", "t1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.sceneCode").value("payment"))
            .andExpect(jsonPath("$.data.payloadSchemaVersion").value(1))
            .andExpect(jsonPath("$.data.eventTypes[0]").value("payment.initiated"));
}

@Test
void getScene_notFound_returns400() throws Exception {
    when(sceneService.getScene("t1", "notexist"))
            .thenThrow(new IllegalArgumentException("Scene 不存在: notexist"));

    mockMvc.perform(get("/api/v1/scenes/notexist")
                    .param("tenantId", "t1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false));
}
```

- [ ] **Step 2: 运行确认测试失败（GET 端点未实现）**

```bash
$MVN -pl rule-api -am test -Dtest=SceneControllerTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL（404 Not Found，端点未注册）

- [ ] **Step 3: 在 `SceneController` 加 GET 端点**

在 `SceneController.java` 追加：

```java
/**
 * GET /api/v1/scenes/{sceneCode}?tenantId=xxx — 查询 Scene 详情（含 payloadSchema）。
 *
 * @param sceneCode 场景编码（路径参数）
 * @param tenantId  租户 ID（查询参数）
 * @return Scene 详情（含 payloadSchema / eventTypes / defaultParams）
 */
@GetMapping("/{sceneCode}")
public ApiResponse<com.sstlfsj.rule.config.api.dto.SceneDetailDto> getScene(
        @PathVariable String sceneCode,
        @RequestParam String tenantId) {
    return ApiResponse.ok(sceneService.getScene(tenantId, sceneCode));
}
```

- [ ] **Step 4: 写 SceneServiceImplTest — getScene 测试**

在 `SceneServiceImplTest.java` 追加：

```java
@Test
void getScene_返回完整SceneDetailDto() {
    SceneDef scene = new SceneDef();
    scene.setId(5L);
    scene.setTenantId(1L);
    scene.setCode("PAYMENT");
    scene.setName("支付场景");
    scene.setDominantMode("PUSH");
    scene.setSubjectType("USER");
    scene.setEventTypes("[\"payment.initiated\"]");
    scene.setPayloadSchema("[{\"name\":\"amount\",\"type\":\"NUMBER\",\"required\":true}]");
    scene.setDefaultParams("{\"timezone\":\"Asia/Shanghai\"}");
    scene.setPayloadSchemaVersion(2);
    scene.setStatus("ACTIVE");
    when(sceneMapper.selectOne(any())).thenReturn(scene);

    com.sstlfsj.rule.config.api.dto.SceneDetailDto dto =
            sceneService.getScene("1", "PAYMENT");

    assertThat(dto.sceneCode()).isEqualTo("PAYMENT");
    assertThat(dto.payloadSchemaVersion()).isEqualTo(2);
    assertThat(dto.eventTypes()).containsExactly("payment.initiated");
    assertThat(dto.payloadSchema()).hasSize(1);
    assertThat(dto.payloadSchema().get(0).name()).isEqualTo("amount");
    assertThat(dto.defaultParams()).containsEntry("timezone", "Asia/Shanghai");
}

@Test
void getScene_sceneNotFound_抛IllegalArgument() {
    when(sceneMapper.selectOne(any())).thenReturn(null);
    assertThatThrownBy(() -> sceneService.getScene("1", "NOT_EXIST"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Scene 不存在");
}
```

- [ ] **Step 5: 运行 rule-config-svc + rule-api 全量测试**

```bash
$MVN -pl rule-config-svc,rule-api -am test
```

Expected: BUILD SUCCESS

- [ ] **Step 6: commit**

```bash
git add -u
git commit -m "feat(api): GET /api/v1/scenes/{sceneCode} 读取 payloadSchema 详情（D13）"
```

---

## Task 5：发布时 triggerEventTypes ⊆ Scene.eventTypes 校验

**Files:**
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PublishService.java`
- Modify: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/PublishServiceTest.java`
- Modify: `docs/08-evolution.md`

- [ ] **Step 1: 写失败测试**

在 `PublishServiceTest.java` 末尾追加（在最后一个 `}` 之前）：

```java
@Test
void publish_triggerEventType不在Scene白名单_抛IllegalArgument() {
    draftVersion.setTriggerEventTypes("[\"order.placed\"]");
    scene.setEventTypes("[\"payment.initiated\"]");   // 只允许 payment 类型

    when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
    when(sceneMapper.selectById(5L)).thenReturn(scene);
    when(ruleVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(draftVersion);

    assertThatThrownBy(() -> publishService.publish(1L, 10L, "actor"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("order.placed");
}

@Test
void publish_triggerEventType在Scene白名单内_正常发布() {
    draftVersion.setTriggerEventTypes("[\"payment.initiated\"]");
    scene.setEventTypes("[\"payment.initiated\",\"payment.refunded\"]");

    when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
    when(sceneMapper.selectById(5L)).thenReturn(scene);
    when(ruleVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(draftVersion);
    when(ruleVersionMapper.maxVersion(10L)).thenReturn(0L);
    when(astSerializer.fromJson(any()))
            .thenReturn(new ConditionNode("EQ", "metric1", null, Map.of(), 0.0));
    when(ruleVersionMapper.insert(any())).thenReturn(1);
    when(ruleDefinitionMapper.updateById(any())).thenReturn(1);
    when(auditLogMapper.insert(any())).thenReturn(1);

    // 不应抛异常，发布成功
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(
            () -> publishService.publish(1L, 10L, "actor"));
}

@Test
void publish_triggerEventTypes为空_跳过校验() {
    draftVersion.setTriggerEventTypes("[]");
    scene.setEventTypes("[\"payment.initiated\"]");

    when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
    when(sceneMapper.selectById(5L)).thenReturn(scene);
    when(ruleVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(draftVersion);
    when(ruleVersionMapper.maxVersion(10L)).thenReturn(0L);
    when(astSerializer.fromJson(any()))
            .thenReturn(new ConditionNode("EQ", "m1", null, Map.of(), 0.0));
    when(ruleVersionMapper.insert(any())).thenReturn(1);
    when(ruleDefinitionMapper.updateById(any())).thenReturn(1);
    when(auditLogMapper.insert(any())).thenReturn(1);

    // 空 triggerEventTypes 应跳过校验，正常发布
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(
            () -> publishService.publish(1L, 10L, "actor"));
}

@Test
void publish_sceneEventTypes为空_跳过校验() {
    // scene.eventTypes 为空（Scene 尚未配置白名单），发布不应被阻断
    draftVersion.setTriggerEventTypes("[\"payment.initiated\"]");
    scene.setEventTypes("[]");

    when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
    when(sceneMapper.selectById(5L)).thenReturn(scene);
    when(ruleVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(draftVersion);
    when(ruleVersionMapper.maxVersion(10L)).thenReturn(0L);
    when(astSerializer.fromJson(any()))
            .thenReturn(new ConditionNode("EQ", "m1", null, Map.of(), 0.0));
    when(ruleVersionMapper.insert(any())).thenReturn(1);
    when(ruleDefinitionMapper.updateById(any())).thenReturn(1);
    when(auditLogMapper.insert(any())).thenReturn(1);

    org.junit.jupiter.api.Assertions.assertDoesNotThrow(
            () -> publishService.publish(1L, 10L, "actor"));
}
```

> **注意：** `PublishServiceTest` 中已有 `draftRule.setKind("AST_BOOLEAN")`，所有使用 `ConditionNode` 的测试需要五参数构造（D12 中已加 `weight` 字段）。若 D12 尚未合并，暂时用四参数构造。

- [ ] **Step 2: 运行确认失败测试**

```bash
$MVN -pl rule-config-svc -am test -Dtest=PublishServiceTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `publish_triggerEventType不在Scene白名单_抛IllegalArgument` FAIL（当前无校验逻辑）

- [ ] **Step 3: 在 `PublishService.publish()` 加 triggerEventTypes 校验**

在 Step 3（查 draftVersion）之后、Step 4（反序列化 AST）之前，在 `publish()` 方法内插入：

```java
        // 3.5. 校验 triggerEventTypes ⊆ Scene.eventTypes（D13）
        validateTriggerEventTypes(draftVersion.getTriggerEventTypes(), scene.getEventTypes());
```

在类末尾加私有方法（紧接 `toJson` 之后）：

```java
    /**
     * 校验规则的 triggerEventTypes 是否均在 Scene 允许的 eventTypes 白名单内。
     * scene.eventTypes 为空时跳过（Scene 尚未配置白名单，容错）；
     * triggerEventTypes 为空时也跳过（规则通配所有事件）。
     */
    private void validateTriggerEventTypes(String triggerEventTypesJson, String sceneEventTypesJson) {
        try {
            if (triggerEventTypesJson == null || triggerEventTypesJson.isBlank()) return;
            java.util.List<String> ruleTypes = objectMapper.readValue(triggerEventTypesJson,
                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
            if (ruleTypes.isEmpty()) return;

            java.util.List<String> sceneTypes = objectMapper.readValue(sceneEventTypesJson,
                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
            if (sceneTypes.isEmpty()) return;   // Scene 未设置白名单，容错通过

            java.util.Set<String> allowed = new java.util.HashSet<>(sceneTypes);
            java.util.List<String> invalid = ruleTypes.stream()
                    .filter(et -> !allowed.contains(et))
                    .toList();
            if (!invalid.isEmpty()) {
                throw new IllegalArgumentException(
                        "triggerEventType 不在 Scene 允许列表，非法值: " + invalid);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            // JSON 解析失败时容错（不应阻断发布）
        }
    }
```

- [ ] **Step 4: 运行 rule-config-svc 全量测试**

```bash
$MVN -pl rule-config-svc -am test
```

Expected: BUILD SUCCESS

- [ ] **Step 5: 更新 `docs/08-evolution.md` §2.12**

找到 `docs/08-evolution.md` 中 `### 2.12 Scene schema 演进` 所在段落，将状态更新：

将标题行
```
### 2.12 Scene schema 演进（来源 D13 v1 不做的"payloadSchema 演进"）
```
改为
```
### 2.12 Scene schema 演进（来源 D13，v2 阶段已实装基础设施）
```

并在段落末尾追加：

```
- **v2 实装（2026-06-03）**：`PayloadFieldSpec` JSON Schema 完整子集（enum/min/max/pattern）、`scene_payload_schema_history` 历史表、`scene.payload_schema_version` 版本号字段已落地。Scene 创建/更新 API 现可持久化 payloadSchema，发布时 triggerEventTypes ⊆ Scene.eventTypes 校验已启用。AST payload 字段引用校验留到 v3（需约定 ConditionNode.params 的字段引用编码规范）。
```

- [ ] **Step 6: 运行全量测试**

```bash
$MVN -pl rule-kernel,rule-eval-svc,rule-config-svc,rule-api -am test
```

Expected: BUILD SUCCESS

- [ ] **Step 7: commit**

```bash
git add -u
git commit -m "feat(config): 发布校验 triggerEventTypes ⊆ Scene.eventTypes + §2.12 文档更新（D13）"
```

---

## 验证命令汇总

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn

# Task 1：config-svc 基础 DTO + DB
$MVN -pl rule-config-svc -am test

# Task 2-4：config-svc + api
$MVN -pl rule-config-svc,rule-api -am test

# 全量
$MVN -pl rule-kernel,rule-eval-svc,rule-config-svc,rule-api -am test
```
