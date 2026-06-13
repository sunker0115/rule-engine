# Schema 线收口 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把"建场景 / 写规则 / 发事件"三处的松散输入收成 typed 契约 + 分层校验:payload type 受控 enum、算子 param 键发布期校验、payloadSchema 约束(enum/min/max/pattern)运行期强制、scene 变更历史统一进 audit_log。

**Architecture:** 算子半(ConditionTypeCatalog 单一源 + ConditionParamValidator 发布校验 + AstDataTypeResolver 收敛 + 填 metadata)与 payload 半(PayloadFieldType enum + PayloadDependency 扩约束 + 运行期值校验 + 审计统一)两条线,统一于"typed 契约 + 建模期/发布期/运行期分层校验"。

**Tech Stack:** Java 25、Spring Boot 4、MyBatis-Plus、RE2J(正则,已引入)、JMH、JUnit5 + AssertJ。

**前置:** 跑 Maven 前用 `mvn-env` skill 设 `$MVN`(JDK25)。设计依据 `docs/superpowers/specs/2026-06-13-schema-line-consolidation-design.md`。

**编码约定(本计划强制):** 新增的**多参值类型**(`PayloadDependency` 7 参、`SceneSnapshot` 5 参、`ConditionTypeCatalog.Spec` 5 参)用 Lombok `@Builder`(record 上加 `@lombok.Builder`,Lombok 1.18.44 支持 record);构造点与测试**一律走 builder**,不用长位置参数。`PayloadDependency` 保留 3 参兼容构造器仅供既有 3 参老调用点(不改它们)。Jackson 反序列化仍走 record 规范构造器(不加 `@JsonDeserialize(builder=...)`),`@Builder` 仅供程序构造。

---

## 文件结构

**算子半**(config-svc `internal/publish`):
- `ConditionTypeCatalog.java`(已起草,Task 1 补测试定稿)
- `ConditionParamValidator.java`(新建)
- 改 `AstDataTypeResolver.java`(读 catalog)、`PublishService.java`(接 validator)、`MetadataServiceImpl.java`(填 conditionTypes)

**payload 半 - 静态**(config-svc `api/dto`):
- `PayloadFieldType.java`(新建 enum)
- 改 `PayloadFieldSpec.java`(@JsonInclude NON_NULL)、`SceneServiceImpl.java`(type 校验)、`PayloadDataTypeMapper.java`(用 enum)

**payload 半 - 冻结+运行期**:
- 改 `PayloadDependency.java`(kernel,扩约束 + 兼容构造器)、`PublishService.java`(冻全量约束)、`PayloadInputValidator.java`(eval-svc,enum/min/max/pattern + RE2J 缓存)

**审计收口**(config-svc):
- `SceneSnapshot.java`(新建,implements AuditSnapshot)、改 `AuditSnapshot.java`(permits)、`SceneServiceImpl.java`(前后快照 + 删快照/版本逻辑)、`SceneDetailDto.java`(删 version)
- 删 `ScenePayloadSchemaHistory.java` + `ScenePayloadSchemaHistoryMapper.java`
- `V1_30__drop_scene_payload_schema_history.sql`(新建迁移)

**基准**(rule-benchmark):`PayloadInputValidatorBenchmark.java`

---

# 阶段一:算子半

## Task 1: ConditionTypeCatalog 定稿 + 测试

**Files:**
- 已存在: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/ConditionTypeCatalog.java`(brainstorm 阶段起草)
- Test: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/ConditionTypeCatalogTest.java`

- [ ] **Step 1: 确认已起草文件内容正确**

Read `ConditionTypeCatalog.java`,确认含 17 算子的 `Spec(code, displayName, requiredParamKeys, allowedDataTypes, requiresMetric)` + `spec(String)` + `all()`。若缺失按 spec §5 B1 补齐(必填键用 `ConditionParams.*`,dataType 用 `DataType.*.tag()`,值与 `AstDataTypeResolver.ALLOWED` 一致)。

> 按编码约定(Spec 5 参 ≥4):给 `Spec` record 加 `@lombok.Builder`,内部 `put(...)` 构造改走 `Spec.builder().code(..).displayName(..).requiredParamKeys(..).allowedDataTypes(..).requiresMetric(true).build()`。

- [ ] **Step 2: 写测试**

```java
package com.sstlfsj.rule.config.internal.publish;

import com.sstlfsj.rule.kernel.api.model.ConditionParams;
import com.sstlfsj.rule.kernel.api.model.ConditionTypes;
import com.sstlfsj.rule.kernel.api.model.DataType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConditionTypeCatalogTest {

    @Test
    void gt_requiresThreshold_allowsNumeric() {
        ConditionTypeCatalog.Spec s = ConditionTypeCatalog.spec(ConditionTypes.GT);
        assertThat(s).isNotNull();
        assertThat(s.requiredParamKeys()).containsExactly(ConditionParams.THRESHOLD);
        assertThat(s.allowedDataTypes()).contains(DataType.LONG.tag(), DataType.DOUBLE.tag(), DataType.DECIMAL.tag());
    }

    @Test
    void between_requiresMinMax() {
        assertThat(ConditionTypeCatalog.spec(ConditionTypes.BETWEEN).requiredParamKeys())
                .containsExactlyInAnyOrder(ConditionParams.MIN, ConditionParams.MAX);
    }

    @Test
    void matches_requiresRegex_allowsString() {
        ConditionTypeCatalog.Spec s = ConditionTypeCatalog.spec(ConditionTypes.MATCHES);
        assertThat(s.requiredParamKeys()).containsExactly(ConditionParams.REGEX);
        assertThat(s.allowedDataTypes()).containsExactly(DataType.STRING.tag());
    }

    @Test
    void contains_requiresElement_allowsList() {
        ConditionTypeCatalog.Spec s = ConditionTypeCatalog.spec(ConditionTypes.CONTAINS);
        assertThat(s.requiredParamKeys()).containsExactly(ConditionParams.ELEMENT);
        assertThat(s.allowedDataTypes()).containsExactly(DataType.LIST.tag());
    }

    @Test
    void unknownType_returnsNull() {
        assertThat(ConditionTypeCatalog.spec("CUSTOM_OP")).isNull();
    }

    @Test
    void all_covers17Operators() {
        assertThat(ConditionTypeCatalog.all()).hasSize(17);
    }
}
```

- [ ] **Step 3: 跑测试**

Run: `$MVN -pl rule-config-svc -am test -Dtest='ConditionTypeCatalogTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（不过则按报错调 catalog 内容）。

- [ ] **Step 4: Commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/ConditionTypeCatalog.java \
        rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/ConditionTypeCatalogTest.java
git commit -m "feat(config): ConditionTypeCatalog 算子目录(必填键+允许 dataType,单一源)"
```

## Task 2: ConditionParamValidator(发布期 param 键校验)

**Files:**
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/ConditionParamValidator.java`
- Test: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/ConditionParamValidatorTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.config.internal.publish;

import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConditionParamValidatorTest {

    @Test
    void missingRequiredKey_throws() {
        // GT 必填 threshold，给了 wrongkey → 拒绝
        AstNode ast = new ConditionNode("GT", "amount", null, Map.of("wrongkey", 100), 0.0);
        assertThatThrownBy(() -> ConditionParamValidator.validate(ast))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GT")
                .hasMessageContaining("threshold");
    }

    @Test
    void presentRequiredKey_passes() {
        AstNode ast = new ConditionNode("GT", "amount", null, Map.of("threshold", 100), 0.0);
        assertThatCode(() -> ConditionParamValidator.validate(ast)).doesNotThrowAnyException();
    }

    @Test
    void unknownConditionType_passes() {
        // 目录缺席的自定义算子放行
        AstNode ast = new ConditionNode("CUSTOM_OP", "x", null, Map.of(), 0.0);
        assertThatCode(() -> ConditionParamValidator.validate(ast)).doesNotThrowAnyException();
    }

    @Test
    void nestedAnd_validatesAllLeaves() {
        AstNode ast = new AndNode(List.of(
                new ConditionNode("GT", "amount", null, Map.of("threshold", 1), 0.0),
                new ConditionNode("MATCHES", "name", null, Map.of("wrongkey", "x"), 0.0)
        ), null, null);
        assertThatThrownBy(() -> ConditionParamValidator.validate(ast))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MATCHES")
                .hasMessageContaining("regex");
    }

    @Test
    void betweenMissingMax_throws() {
        AstNode ast = new ConditionNode("BETWEEN", "amount", null, Map.of("min", 1), 0.0);
        assertThatThrownBy(() -> ConditionParamValidator.validate(ast))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-config-svc -am test -Dtest='ConditionParamValidatorTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败(类不存在)。

- [ ] **Step 3: 写实现**

```java
package com.sstlfsj.rule.config.internal.publish;

import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.ast.DecisionLeafNode;
import com.sstlfsj.rule.kernel.api.model.ast.DecisionTableNode;
import com.sstlfsj.rule.kernel.api.model.ast.IfNode;
import com.sstlfsj.rule.kernel.api.model.ast.NotNode;
import com.sstlfsj.rule.kernel.api.model.ast.OrNode;
import com.sstlfsj.rule.kernel.api.model.ast.ScorecardRootNode;
import com.sstlfsj.rule.kernel.api.model.ast.XorNode;

/**
 * 发布期 param 键校验:遍历 AST，按 {@link ConditionTypeCatalog} 校每个 ConditionNode 的必填 param 键齐全。
 * 目录缺席的 conditionType(SPI 自定义 / time.* 内置路径)放行。缺键抛 IllegalArgumentException 拒绝发布。
 */
final class ConditionParamValidator {

    private ConditionParamValidator() {}

    static void validate(AstNode node) {
        switch (node) {
            case ConditionNode c -> validateLeaf(c);
            case AndNode a -> a.children().forEach(ConditionParamValidator::validate);
            case OrNode o -> o.children().forEach(ConditionParamValidator::validate);
            case NotNode n -> validate(n.child());
            case XorNode x -> x.children().forEach(ConditionParamValidator::validate);
            case ScorecardRootNode sc -> sc.conditions().forEach(ConditionParamValidator::validateLeaf);
            case IfNode ifn -> {
                validate(ifn.condition());
                validate(ifn.thenBranch());
                if (ifn.elseBranch() != null) validate(ifn.elseBranch());
            }
            case DecisionLeafNode ignored -> { }
            case DecisionTableNode ignored -> { }
        }
    }

    private static void validateLeaf(ConditionNode c) {
        ConditionTypeCatalog.Spec spec = ConditionTypeCatalog.spec(c.conditionType());
        if (spec == null) return; // SPI 开放:目录缺席放行
        for (String key : spec.requiredParamKeys()) {
            if (!c.params().containsKey(key)) {
                throw new IllegalArgumentException(
                        "算子 " + c.conditionType() + " 缺少必填参数键 \"" + key + "\""
                        + "（metric=" + c.metricCode() + "，必填键=" + spec.requiredParamKeys() + "）");
            }
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-config-svc -am test -Dtest='ConditionParamValidatorTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/ConditionParamValidator.java \
        rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/ConditionParamValidatorTest.java
git commit -m "feat(config): ConditionParamValidator 发布期校验算子必填 param 键"
```

## Task 3: 接 ConditionParamValidator 进 PublishService

**Files:**
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PublishService.java`

- [ ] **Step 1: 定位 AST 解析/校验点**

在 PublishService 里找到 draft 保存期调用 `AstDataTypeResolver.resolve(...)` 的位置(草稿冻结链路,非 publish 激活)。在该 AST 校验附近(resolve 之前或之后)插入:

```java
ConditionParamValidator.validate(conditionAst);
```

> 注:`publish()` 方法是 DRAFT→ACTIVE 原地激活、不重解析(premise A);AST 校验在**草稿保存**期。定位到草稿保存的 service 方法(调用 AstDataTypeResolver 处),在那里加 validator 调用。若该逻辑在 ConfigServiceImpl 而非 PublishService,则改 ConfigServiceImpl 对应处。

- [ ] **Step 2: 跑 config-svc 全量(确认装配/既有草稿测试不回归)**

Run: `$MVN -pl rule-config-svc -am test`
Expected: 全绿。新校验默认对合法规则无影响。

- [ ] **Step 3: Commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PublishService.java
git commit -m "feat(config): 草稿保存期接入 ConditionParamValidator"
```

## Task 4: AstDataTypeResolver 读 catalog(收敛 ALLOWED)

**Files:**
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/AstDataTypeResolver.java`

- [ ] **Step 1: 替换私有 ALLOWED 为 catalog 查询**

删除 `private static final Map<String, Set<String>> ALLOWED` 及其 static 初始化块。`resolveCondition` 与 `resolveColumn` 里 `ALLOWED.get(type)` 改为:

```java
ConditionTypeCatalog.Spec spec = ConditionTypeCatalog.spec(cond.conditionType());
Set<String> allowed = spec != null ? spec.allowedDataTypes() : null;
if (allowed != null && !allowed.contains(dataType)) {
    throw new IllegalArgumentException(
            "算子 " + cond.conditionType() + " 不支持 dataType=" + dataType
            + "（metric=" + cond.metricCode() + "）");
}
```

决策表列(`resolveColumn`)同理:`ConditionTypeCatalog.spec(col.operator())`。导入 `java.util.Set` 保留。

- [ ] **Step 2: 跑既有 AstDataTypeResolverTest(必须全绿 = 行为不变)**

Run: `$MVN -pl rule-config-svc -am test -Dtest='AstDataTypeResolverTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 全绿。catalog 的 allowedDataTypes 值与原 ALLOWED 一致,行为不变。

- [ ] **Step 3: Commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/AstDataTypeResolver.java
git commit -m "refactor(config): AstDataTypeResolver 允许 dataType 改读 ConditionTypeCatalog(收敛重复声明)"
```

## Task 5: 填 metadata conditionTypes

**Files:**
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/MetadataServiceImpl.java`
- Test: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/service/MetadataServiceImplTest.java`(补用例)

- [ ] **Step 1: 写失败测试(补到 MetadataServiceImplTest)**

```java
@Test
void getSceneMetadata_returnsConditionTypesFromCatalog() {
    // 复用该测试类既有 scene 装配方式;断言 conditionTypes 非空且含 GT 及其 paramsSchema
    var resp = metadataService.getSceneMetadata(TENANT, SCENE_CODE);
    assertThat(resp.conditionTypes()).isNotEmpty();
    var gt = resp.conditionTypes().stream()
            .filter(c -> c.code().equals("GT")).findFirst().orElseThrow();
    assertThat(gt.displayName()).isEqualTo("大于");
    assertThat(gt.requiresMetric()).isTrue();
    assertThat((java.util.List<?>) gt.paramsSchema().get("required")).contains("threshold");
}
```

> 注:`TENANT`/`SCENE_CODE`/`metadataService` 按该测试类既有 setup 复用;若该类用 mock mapper,需让 `sceneMapper.findByCode` 返回一个非 null SceneDef(参考类内既有 scene 用例)。

- [ ] **Step 2: 跑确认失败**

Run: `$MVN -pl rule-config-svc -am test -Dtest='MetadataServiceImplTest#getSceneMetadata_returnsConditionTypesFromCatalog' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL(conditionTypes 为空)。

- [ ] **Step 3: 实现 — getSceneMetadata 填 conditionTypes**

把 `return new MetadataResponse(List.of(), metricMetas);` 改为:

```java
        List<MetadataService.ConditionTypeMeta> conditionTypes = ConditionTypeCatalog.all().stream()
                .map(s -> new MetadataService.ConditionTypeMeta(
                        s.code(), s.displayName(),
                        Map.of("required", List.copyOf(s.requiredParamKeys())),
                        s.requiresMetric()))
                .toList();
        return new MetadataResponse(conditionTypes, metricMetas);
```

加 import:`com.sstlfsj.rule.config.internal.publish.ConditionTypeCatalog`、`java.util.Map`。

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-config-svc -am test -Dtest='MetadataServiceImplTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/MetadataServiceImpl.java \
        rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/service/MetadataServiceImplTest.java
git commit -m "feat(config): getSceneMetadata 从 catalog 填 conditionTypes + paramsSchema(填 v1 空占位)"
```

---

# 阶段二:payload 半 - 静态(type enum)

## Task 6: PayloadFieldType enum + scene 创建期校验

**Files:**
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/PayloadFieldType.java`
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/PayloadFieldSpec.java`(@JsonInclude)
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PayloadDataTypeMapper.java`(用 enum)
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/SceneServiceImpl.java`(校验)
- Test: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/api/dto/PayloadFieldTypeTest.java`

- [ ] **Step 1: 写 PayloadFieldType + 测试**

`PayloadFieldType.java`:
```java
package com.sstlfsj.rule.config.api.dto;

/** payloadSchema 字段类型封闭集(JSON-shape authoring 词汇);经 PayloadDataTypeMapper 桥接到 kernel DataType。 */
public enum PayloadFieldType {
    STRING, INTEGER, NUMBER, BOOLEAN, ARRAY, OBJECT;

    /**
     * 解析 type 串为枚举,非法抛 IllegalArgumentException(含合法集)。
     *
     * @param tag payloadSchema 字段声明的 type
     * @return 对应枚举
     */
    public static PayloadFieldType fromTag(String tag) {
        if (tag == null) {
            throw new IllegalArgumentException("payloadSchema 字段 type 不能为空，合法值: STRING/INTEGER/NUMBER/BOOLEAN/ARRAY/OBJECT");
        }
        try {
            return valueOf(tag.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("非法 payloadSchema 字段 type=" + tag
                    + "，合法值: STRING/INTEGER/NUMBER/BOOLEAN/ARRAY/OBJECT");
        }
    }
}
```

`PayloadFieldTypeTest.java`:
```java
package com.sstlfsj.rule.config.api.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayloadFieldTypeTest {
    @Test
    void fromTag_valid() {
        assertThat(PayloadFieldType.fromTag("NUMBER")).isEqualTo(PayloadFieldType.NUMBER);
        assertThat(PayloadFieldType.fromTag("string")).isEqualTo(PayloadFieldType.STRING);
    }

    @Test
    void fromTag_invalid_throws() {
        assertThatThrownBy(() -> PayloadFieldType.fromTag("STRIGN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("STRIGN");
    }

    @Test
    void fromTag_null_throws() {
        assertThatThrownBy(() -> PayloadFieldType.fromTag(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: PayloadFieldSpec 加 @JsonInclude(NON_NULL)**

在 `PayloadFieldSpec` 的 `@JsonIgnoreProperties(ignoreUnknown = true)` 下加 `@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)`(或加 import 后用短名)。

- [ ] **Step 3: PayloadDataTypeMapper 改用 enum**

把 `toDataTypeTag(String schemaType)` 内的字符串 switch 改为先 `PayloadFieldType.fromTag(schemaType)` 再 switch 枚举(或保留宽松:无法解析仍回 UNKNOWN——但既然 scene 创建期已校验,这里可直接 `fromTag`):
```java
static String toDataTypeTag(String schemaType) {
    return switch (PayloadFieldType.fromTag(schemaType)) {
        case NUMBER  -> DataType.DECIMAL.tag();
        case INTEGER -> DataType.LONG.tag();
        case STRING  -> DataType.STRING.tag();
        case BOOLEAN -> DataType.BOOLEAN.tag();
        case ARRAY   -> DataType.LIST.tag();
        case OBJECT  -> DataType.UNKNOWN.tag();
    };
}
```

- [ ] **Step 4: SceneServiceImpl 创建/更新校验 type**

在 `createScene` 与 `updateScene` 里,落库前对 `payloadSchema`(非 null 时)逐字段校验:
```java
private void validatePayloadSchemaTypes(List<PayloadFieldSpec> payloadSchema) {
    if (payloadSchema == null) return;
    for (PayloadFieldSpec f : payloadSchema) {
        PayloadFieldType.fromTag(f.type()); // 非法 type 抛 IllegalArgumentException
    }
}
```
在 createScene 设置 payloadSchema 前、updateScene 处理 payloadSchema 前调用。导入 `PayloadFieldType`、`PayloadFieldSpec`(后者已导入)。

- [ ] **Step 5: 跑测试**

Run: `$MVN -pl rule-config-svc -am test -Dtest='PayloadFieldTypeTest,PayloadDataTypeMapperTest,SceneServiceTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS(既有 PayloadDataTypeMapperTest 若存在须仍绿;SceneServiceTest 既有用例不回归)。

- [ ] **Step 6: Commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/PayloadFieldType.java \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/PayloadFieldSpec.java \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PayloadDataTypeMapper.java \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/SceneServiceImpl.java \
        rule-config-svc/src/test/java/com/sstlfsj/rule/config/api/dto/PayloadFieldTypeTest.java
git commit -m "feat(config): PayloadFieldType enum + scene 创建期 type 校验 + @JsonInclude(NON_NULL)"
```

---

# 阶段三:payload 半 - 冻结 + 运行期

## Task 7: PayloadDependency 扩约束(兼容构造器)

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/PayloadDependency.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/PayloadDependencyTest.java`(补)

- [ ] **Step 1: 写失败测试(补)**

```java
@Test
void builder_carriesConstraints() {
    PayloadDependency d = PayloadDependency.builder()
            .name("amount").dataType("DECIMAL").required(true)
            .enumValues(java.util.List.of(1, 2)).minimum(0.0).maximum(100.0).pattern("\\d+")
            .build();
    assertThat(d.enumValues()).containsExactly(1, 2);
    assertThat(d.minimum()).isEqualTo(0.0);
    assertThat(d.maximum()).isEqualTo(100.0);
    assertThat(d.pattern()).isEqualTo("\\d+");
}

@Test
void compatConstructor_defaultsConstraintsNull() {
    // 3 参兼容构造器仅供既有老调用点;约束字段全 null
    PayloadDependency d = new PayloadDependency("amount", "DECIMAL", true);
    assertThat(d.enumValues()).isNull();
    assertThat(d.minimum()).isNull();
    assertThat(d.pattern()).isNull();
}
```

- [ ] **Step 2: 跑确认失败**

Run: `$MVN -pl rule-kernel -am test -Dtest='PayloadDependencyTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败(7 参构造器/访问器不存在)。

- [ ] **Step 3: 扩 record + 兼容构造器**

```java
package com.sstlfsj.rule.kernel.api.model;

import lombok.Builder;

import java.util.List;

/**
 * 规则引用的 payload 字段依赖(发布期从 scene.payloadSchema 冻结,随 RuleVersionSnapshot 下发)。
 * 携带完整输入约束,供评估期 PayloadInputValidator 强制校验(模型 2:约束随规则发布冻结)。
 *
 * @param name       payload 字段名(== ConditionNode.metricCode,valueRef=PAYLOAD)
 * @param dataType   字段类型标签(DataType.tag())
 * @param required   是否必填
 * @param enumValues 枚举约束(null=不约束)
 * @param minimum    数值下界(null=不约束)
 * @param maximum    数值上界(null=不约束)
 * @param pattern    正则约束(null=不约束)
 */
@Builder
public record PayloadDependency(String name, String dataType, boolean required,
                                List<Object> enumValues, Double minimum, Double maximum, String pattern) {

    /** 兼容构造器:无约束(既有 3 参老调用点专用),约束字段全 null。 */
    public PayloadDependency(String name, String dataType, boolean required) {
        this(name, dataType, required, null, null, null, null);
    }
}
```

- [ ] **Step 4: 跑 kernel 全量(确认 15+ 老调用点经兼容构造器仍编译)**

Run: `$MVN -pl rule-kernel -am test`
Expected: 全绿(3 参兼容构造器保住所有老调用点)。

- [ ] **Step 5: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/PayloadDependency.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/PayloadDependencyTest.java
git commit -m "feat(kernel): PayloadDependency 扩 enum/min/max/pattern 约束 + 兼容构造器"
```

## Task 8: PublishService 冻结全量约束

**Files:**
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PublishService.java`(~L567)

- [ ] **Step 1: 改冻结逻辑带约束**

把 `payloadDeps.add(new PayloadDependency(field, dataTypeTag, spec.required()));` 改为(走 builder):
```java
            payloadDeps.add(PayloadDependency.builder()
                    .name(field).dataType(dataTypeTag).required(spec.required())
                    .enumValues(spec.enumValues()).minimum(spec.minimum())
                    .maximum(spec.maximum()).pattern(spec.pattern())
                    .build());
```

- [ ] **Step 2: 跑 config-svc 全量**

Run: `$MVN -pl rule-config-svc -am test`
Expected: 全绿(既有发布/冻结测试不回归;约束随冻结流入)。

- [ ] **Step 3: Commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PublishService.java
git commit -m "feat(config): 发布期把 payloadSchema 完整约束冻进 payload_dependencies"
```

## Task 9: PayloadInputValidator 运行期强制约束(RE2J 缓存)

**Files:**
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/validate/PayloadInputValidator.java`
- Test: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/validate/PayloadInputValidatorTest.java`(补)

- [ ] **Step 1: 写失败测试(补到既有 PayloadInputValidatorTest)**

```java
@Test
void enumViolation_throws() {
    var deps = List.of(PayloadDependency.builder()
            .name("channel").dataType("STRING").required(true).enumValues(List.of("APP", "WEB")).build());
    assertThatThrownBy(() -> PayloadInputValidator.validate(deps, Map.of("channel", "SMS")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("INPUT_ENUM_VIOLATION");
}

@Test
void enumOk_passes() {
    var deps = List.of(PayloadDependency.builder()
            .name("channel").dataType("STRING").required(true).enumValues(List.of("APP", "WEB")).build());
    assertThatCode(() -> PayloadInputValidator.validate(deps, Map.of("channel", "APP")))
            .doesNotThrowAnyException();
}

@Test
void rangeViolation_throws() {
    var deps = List.of(PayloadDependency.builder()
            .name("amount").dataType("DECIMAL").required(true).minimum(0.0).maximum(100.0).build());
    assertThatThrownBy(() -> PayloadInputValidator.validate(deps, Map.of("amount", 200)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("INPUT_RANGE_VIOLATION");
}

@Test
void patternViolation_throws() {
    var deps = List.of(PayloadDependency.builder()
            .name("phone").dataType("STRING").required(true).pattern("\\d{11}").build());
    assertThatThrownBy(() -> PayloadInputValidator.validate(deps, Map.of("phone", "abc")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("INPUT_PATTERN_VIOLATION");
}

@Test
void patternOk_passes() {
    var deps = List.of(PayloadDependency.builder()
            .name("phone").dataType("STRING").required(true).pattern("\\d{11}").build());
    assertThatCode(() -> PayloadInputValidator.validate(deps, Map.of("phone", "13800138000")))
            .doesNotThrowAnyException();
}
```
(import `assertThatCode`、`java.util.List`)

- [ ] **Step 2: 跑确认失败**

Run: `$MVN -pl rule-eval-svc -am test -Dtest='PayloadInputValidatorTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL(约束未校,enum 违例未抛)。

- [ ] **Step 3: 实现 — 扩 validate + RE2J pattern 缓存**

在 `PayloadInputValidator` 的 `typeMatches` 通过后追加约束校验,并加 RE2J 编译缓存:
```java
import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// 类内字段(改为非纯静态工具或用静态缓存):pattern 串 → 编译产物(负缓存 empty)。
private static final ConcurrentHashMap<String, Optional<Pattern>> PATTERN_CACHE = new ConcurrentHashMap<>();
```
在 `validate` 的每个 dep 循环里,`typeMatches` 检查后追加:
```java
            // enum 成员
            if (d.enumValues() != null && !d.enumValues().isEmpty() && !d.enumValues().contains(value)) {
                throw new IllegalArgumentException(
                        "INPUT_ENUM_VIOLATION: 字段 " + d.name() + " 值不在枚举 " + d.enumValues() + " 内");
            }
            // min/max(值为数值时)
            if (value instanceof Number num) {
                double dv = num.doubleValue();
                if (d.minimum() != null && dv < d.minimum()) {
                    throw new IllegalArgumentException(
                            "INPUT_RANGE_VIOLATION: 字段 " + d.name() + " 小于下界 " + d.minimum());
                }
                if (d.maximum() != null && dv > d.maximum()) {
                    throw new IllegalArgumentException(
                            "INPUT_RANGE_VIOLATION: 字段 " + d.name() + " 超过上界 " + d.maximum());
                }
            }
            // pattern(值为字符串时,RE2J 缓存编译)
            if (d.pattern() != null && value instanceof CharSequence cs) {
                Optional<Pattern> p = PATTERN_CACHE.computeIfAbsent(d.pattern(), PayloadInputValidator::compileQuietly);
                if (p.isPresent() && !p.get().matcher(cs).matches()) {
                    throw new IllegalArgumentException(
                            "INPUT_PATTERN_VIOLATION: 字段 " + d.name() + " 不匹配 " + d.pattern());
                }
            }
```
加私有方法:
```java
    private static Optional<Pattern> compileQuietly(String regex) {
        try { return Optional.of(Pattern.compile(regex)); }
        catch (PatternSyntaxException e) { return Optional.empty(); }
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-eval-svc -am test -Dtest='PayloadInputValidatorTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/validate/PayloadInputValidator.java \
        rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/validate/PayloadInputValidatorTest.java
git commit -m "feat(eval): PayloadInputValidator 强制 enum/min/max/pattern(RE2J 缓存防 ReDoS)"
```

## Task 10: PayloadInputValidator 性能基准

**Files:**
- Create: `rule-benchmark/src/main/java/com/sstlfsj/rule/benchmark/PayloadInputValidatorBenchmark.java`
- Test: `rule-benchmark/src/test/java/com/sstlfsj/rule/benchmark/PayloadInputValidatorBenchmarkTest.java`(校准)

- [ ] **Step 1: 写基准 + 校准测试**

基准:N=10 字段,三档 @Param `mode` = `none`(无约束)/`enumrange`/`pattern`,`@Benchmark validate()` 跑 `PayloadInputValidator.validate(deps, payload)`,合法输入。校准测试同既有 benchmark 惯例(`setup()` + 一次 validate 不抛)。

```java
// 基准结构仿 InterpretedExecBenchmark:@BenchmarkMode(AverageTime)+@Fork(1)+@Warmup(3)+@Measurement(5)
// @Param({"none","enumrange","pattern"}) String mode; setup() 按 mode 建 N=10 deps + 合法 payload。
// @Benchmark public void validate(){ PayloadInputValidator.validate(deps, payload); }
```

- [ ] **Step 2: 打包跑基准(-prof gc)**

```bash
$MVN -q -pl rule-benchmark -am package -DskipTests
java -jar rule-benchmark/target/benchmarks.jar PayloadInputValidatorBenchmark -prof gc
```
Expected: pattern 档 ns/op 与无约束档同量级(证缓存生效,无重编译);分配≈低。记录数值。

- [ ] **Step 3: Commit**

```bash
git add rule-benchmark/src/main/java/com/sstlfsj/rule/benchmark/PayloadInputValidatorBenchmark.java \
        rule-benchmark/src/test/java/com/sstlfsj/rule/benchmark/PayloadInputValidatorBenchmarkTest.java
git commit -m "perf(benchmark): PayloadInputValidator 三档基准(验证 pattern 缓存、整体可忽略)"
```

---

# 阶段四:审计收口(删历史表)

## Task 11: SceneSnapshot + AuditSnapshot permits

**Files:**
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/event/SceneSnapshot.java`
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/event/AuditSnapshot.java`(permits 加 SceneSnapshot)

- [ ] **Step 1: 写 SceneSnapshot**

```java
package com.sstlfsj.rule.config.internal.event;

import com.sstlfsj.rule.config.api.dto.PayloadFieldSpec;
import lombok.Builder;

import java.util.List;
import java.util.Map;

/**
 * Scene 变更前/后快照,落 audit_log 的 before/after_snapshot(取代专用 payloadSchema 历史表)。
 *
 * @param name          场景名
 * @param eventTypes    允许 eventType 白名单
 * @param payloadSchema payloadSchema 字段声明
 * @param defaultParams 默认参数
 * @param status        场景状态(ACTIVE/DISABLED)
 */
@Builder
public record SceneSnapshot(String name, List<String> eventTypes,
                            List<PayloadFieldSpec> payloadSchema,
                            Map<String, Object> defaultParams, String status)
        implements AuditSnapshot {
}
```

- [ ] **Step 2: AuditSnapshot permits 加 SceneSnapshot**

```java
public sealed interface AuditSnapshot
        permits DraftCreatedSnapshot, RulePublishedSnapshot, RuleStatusSnapshot,
                RuleImportedSnapshot, MetricChangedSnapshot, SceneSnapshot {
}
```

- [ ] **Step 3: 编译确认**

Run: `$MVN -pl rule-config-svc -am -DskipTests compile`
Expected: BUILD SUCCESS。

- [ ] **Step 4: Commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/event/SceneSnapshot.java \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/event/AuditSnapshot.java
git commit -m "feat(config): SceneSnapshot 审计快照(纳入 AuditSnapshot sealed)"
```

## Task 12: SceneServiceImpl 改走前后快照 + 删历史/版本逻辑

**Files:**
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/SceneServiceImpl.java`
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/SceneDetailDto.java`(删 version)
- Delete: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/ScenePayloadSchemaHistory.java`
- Delete: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/repository/ScenePayloadSchemaHistoryMapper.java`
- Test: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/api/service/SceneServiceTest.java`(更新)

- [ ] **Step 1: 改 publishAudit 签名带快照 + updateScene 前后快照**

把 `publishAudit(tenantId, actor, action, targetType, targetId)` 扩为带 before/after:
```java
private void publishAudit(Long tenantId, String actor, String action,
                          String targetType, String targetId,
                          AuditSnapshot before, AuditSnapshot after) {
    eventPublisher.publishEvent(new OperationAuditedEvent(
            tenantId, actor, "USER", action, targetType, targetId, before, after, LocalDateTime.now()));
}

private static SceneSnapshot snapshotOf(SceneDef s) {
    return SceneSnapshot.builder()
            .name(s.getName()).eventTypes(s.getEventTypes()).payloadSchema(s.getPayloadSchema())
            .defaultParams(s.getDefaultParams())
            .status(s.getStatus() != null ? s.getStatus().name() : null)
            .build();
}
```
- `createScene`:落库后 `publishAudit(..., "CREATE", "scene", id, null, snapshotOf(scene))`。删除 `snapshotSchema(...)` 调用 + `setPayloadSchemaVersion(1)`。
- `updateScene`:进方法先 `SceneSnapshot before = snapshotOf(scene)`;删除 payloadSchema 变更时的 `snapshotSchema` + 版本自增整段(L79-89 收为直接 `if (payloadSchema != null) scene.setPayloadSchema(payloadSchema);`);落库后 `publishAudit(..., "UPDATE", "scene", id, before, snapshotOf(scene))`。
- `disableScene`:`SceneSnapshot before = snapshotOf(scene)` → 改 status → `publishAudit(..., "DISABLE", "scene", id, before, snapshotOf(scene))`。
- 删除 `snapshotSchema` 私有方法、`schemaHistoryMapper` 字段 + 构造器注入、`ScenePayloadSchemaHistory` import。

- [ ] **Step 2: SceneDetailDto 删 version + toDto 调整**

`SceneDetailDto` 删 `int payloadSchemaVersion` 字段;`SceneServiceImpl.toDto` 删 `version` 计算与传参。

- [ ] **Step 3: 删两个文件**

```bash
git rm rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/ScenePayloadSchemaHistory.java \
       rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/repository/ScenePayloadSchemaHistoryMapper.java
```

- [ ] **Step 4: 更新 SceneServiceTest + SceneDetailDtoTest**

既有断言 `version` 的用例去掉 version 断言;新增/调整断言 update 发出带 before/after 的 OperationAuditedEvent(mock eventPublisher 捕获,断言 afterSnapshot instanceof SceneSnapshot)。SceneDetailDtoTest 去掉 version 字段。

- [ ] **Step 5: 跑 config-svc 全量**

Run: `$MVN -pl rule-config-svc -am test`
Expected: 全绿。

- [ ] **Step 6: Commit**

```bash
git add -A rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/SceneServiceImpl.java \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/SceneDetailDto.java \
        rule-config-svc/src/test/
git commit -m "refactor(config): scene 变更走 audit_log 前后快照,删 payloadSchema 历史表/版本逻辑"
```

## Task 13: 迁移 V1_30 删历史表 + 版本列

**Files:**
- Create: `rule-config-svc/src/main/resources/db/migration/V1_30__drop_scene_payload_schema_history.sql`

- [ ] **Step 1: 写迁移**

```sql
-- Schema 线收口:scene 变更历史改走 audit_log（before/after 快照），删除专用 payloadSchema 历史表与版本列。
DROP TABLE IF EXISTS scene_payload_schema_history;
ALTER TABLE scene DROP COLUMN payload_schema_version;
```

- [ ] **Step 2: 确认 SceneDef 实体不再引用 payloadSchemaVersion**

确认 `SceneDef` 的 `payloadSchemaVersion` 字段已随 Task 12 删除(若 Task 12 未删,在此删 `SceneDef.payloadSchemaVersion` 字段 + getter/setter)。

- [ ] **Step 3: 全量 config-svc 测试(含迁移跑通)**

Run: `$MVN -pl rule-config-svc -am test`
Expected: 全绿(集成测试起 Flyway 跑到 V1_30,无残留引用)。

- [ ] **Step 4: Commit**

```bash
git add rule-config-svc/src/main/resources/db/migration/V1_30__drop_scene_payload_schema_history.sql \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/SceneDef.java
git commit -m "feat(config): V1_30 删 scene_payload_schema_history 表 + payload_schema_version 列"
```

---

# 阶段五:全量回归 + DB 端到端

## Task 14: 全量 clean test

- [ ] **Step 1: 全量 clean test**

Run: `$MVN clean test`
Expected: 全 27 模块全绿。失败按 CLAUDE.md 纪律修,不 skip。

## Task 15: DB 端到端功能测试(起真实服务)

- [ ] **Step 1: 打包 + 起服务**

按 CLAUDE.md 功能测试纪律,用打包产物起 rule-app(非 reactor run),确认 Flyway 迁移到 V1_30、`scene_payload_schema_history` 表已删。

- [ ] **Step 2: 走端到端剧本**

1. 建场景:`type="STRIGN"` → 期望 400;正确 schema(含 enum/min/max/pattern 字段)→ 成功。
2. 建规则:缺算子必填键(raw JSON,如 GT 不带 threshold)→ 保存/发布 400;正确规则 → 发布成功;查 `rule_version.payload_dependencies` 真带 enum/min/max/pattern。
3. 发事件:违反 enum/min/max/pattern → 400(对应错误码);合法事件 → 命中。
4. 查 `audit_log`:scene create/update 行的 `before/after_snapshot` 含 payloadSchema。
5. 查 metadata 接口:`GET /admin/v1/scenes/{code}/metadata` 返回非空 conditionTypes + paramsSchema。
6. 清理本次测试数据,恢复基线。

- [ ] **Step 2: 记录结果**

把端到端结果(各步通过/数据落库核对)回报。无新增提交(纯验证),发现 bug 则回到对应 Task 修。

---

## Self-Review

**Spec 覆盖:** §4 A1→Task6、A2→Task7/8、A3→Task9、A4→Task11/12/13、A5→Task6;§5 B1→Task1、B2→Task2/3、B3→Task4、B4→Task5;§7 性能基准→Task10;DB 端到端→Task15。全覆盖。

**类型一致性:** `PayloadDependency` 7 参构造器(name,dataType,required,enumValues,minimum,maximum,pattern)在 Task7 定义、Task8/9 一致引用;兼容 3 参构造器保住老调用点。`ConditionTypeCatalog.Spec`(code,displayName,requiredParamKeys,allowedDataTypes,requiresMetric)在 Task1 定义、Task2/4/5 一致。`SceneSnapshot`(name,eventTypes,payloadSchema,defaultParams,status)Task11 定义、Task12 用。`PayloadFieldType.fromTag` Task6 定义、PayloadDataTypeMapper/SceneServiceImpl 用。`MetadataService.ConditionTypeMeta(code,displayName,paramsSchema,requiresMetric)` 既有契约,Task5 按序填。

**占位扫描:** Task3 定位点("草稿保存调 AstDataTypeResolver 处")是已知的运行期定位指引(publish 是原地激活不重解析,AST 校验在草稿保存期),非占位;Task10 基准结构按既有 InterpretedExecBenchmark 惯例,给了 @Param/@Benchmark 形态。其余均含完整代码。
