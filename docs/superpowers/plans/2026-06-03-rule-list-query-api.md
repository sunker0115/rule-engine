# 规则列表查询 API 实现计划

> **Status: 已完成**

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 `GET /api/v1/rules` 规则列表分页查询端点，对应 10-api-contract.md §4.4 / 08-evolution.md §2.18。

**Architecture:** `RuleListItemVO` 定义响应 VO，放在 `rule-config-svc` 的 api 包；`ConfigService.listRules` 接口 + `ConfigServiceImpl` 实现（先按 sceneCode 解析 sceneId，再分页查 rule_definition）；`RuleController` 挂载 GET 端点。新增 `MybatisPlusConfig` 注册 `PaginationInnerInterceptor`（`@ConditionalOnMissingBean` 避免 app 层冲突）。

**Tech Stack:** Java 25 / Spring Boot 4 / MyBatis-Plus 3（Page + LambdaQueryWrapper）/ JUnit 5 + Mockito + AssertJ / MockMvc

---

## 文件清单

**新建：**
- `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/RuleListItemVO.java`
- `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/MybatisPlusConfig.java`
- `rule-config-svc/src/test/java/com/sstlfsj/rule/config/api/dto/RuleListItemVOTest.java`
- `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/MybatisPlusConfigTest.java`

**修改：**
- `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/ConfigService.java`
- `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/ConfigServiceImpl.java`
- `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/service/ConfigServiceImplTest.java`
- `rule-api/src/main/java/com/sstlfsj/rule/web/config/RuleController.java`
- `rule-api/src/test/java/com/sstlfsj/rule/web/config/RuleControllerTest.java`

---

## Maven 环境（每次执行测试前设置）

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
```

---

## Task 1: RuleListItemVO + MybatisPlusConfig

**Files:**
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/RuleListItemVO.java`
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/MybatisPlusConfig.java`
- Create: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/api/dto/RuleListItemVOTest.java`
- Create: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/MybatisPlusConfigTest.java`

- [x] **Step 1: 写失败测试 — RuleListItemVO 字段校验**

```java
// rule-config-svc/src/test/java/com/sstlfsj/rule/config/api/dto/RuleListItemVOTest.java
package com.sstlfsj.rule.config.api.dto;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;

class RuleListItemVOTest {

    @Test
    void fields_roundTrip() {
        LocalDateTime publishedAt = LocalDateTime.of(2026, 6, 1, 10, 0);
        RuleListItemVO vo = new RuleListItemVO(10L, "rule.a", "规则A", "PUBLISHED", 42L, publishedAt);

        assertThat(vo.ruleDefinitionId()).isEqualTo(10L);
        assertThat(vo.code()).isEqualTo("rule.a");
        assertThat(vo.name()).isEqualTo("规则A");
        assertThat(vo.status()).isEqualTo("PUBLISHED");
        assertThat(vo.currentVersion()).isEqualTo(42L);
        assertThat(vo.publishedAt()).isEqualTo(publishedAt);
    }

    @Test
    void nullableFields_allowNull() {
        RuleListItemVO vo = new RuleListItemVO(1L, "rule.b", "规则B", "DRAFT", null, null);

        assertThat(vo.currentVersion()).isNull();
        assertThat(vo.publishedAt()).isNull();
    }
}
```

- [x] **Step 2: 写失败测试 — MybatisPlusConfig bean 注册**

```java
// rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/MybatisPlusConfigTest.java
package com.sstlfsj.rule.config.internal;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MybatisPlusConfigTest {

    @Test
    void mybatisPlusInterceptor_isNotNull() {
        MybatisPlusConfig config = new MybatisPlusConfig();
        MybatisPlusInterceptor interceptor = config.mybatisPlusInterceptor();
        assertThat(interceptor).isNotNull();
    }

    @Test
    void mybatisPlusInterceptor_hasPaginationPlugin() {
        MybatisPlusConfig config = new MybatisPlusConfig();
        MybatisPlusInterceptor interceptor = config.mybatisPlusInterceptor();
        assertThat(interceptor.getInterceptors()).hasSize(1);
    }
}
```

- [x] **Step 3: 运行测试确认失败**

```bash
$MVN -pl rule-config-svc -am test \
  -Dtest='RuleListItemVOTest,MybatisPlusConfigTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

期望：FAIL，`RuleListItemVO` / `MybatisPlusConfig` 类不存在。

- [x] **Step 4: 创建 RuleListItemVO**

```java
// rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/RuleListItemVO.java
package com.sstlfsj.rule.config.api.dto;

import java.time.LocalDateTime;

/**
 * 规则列表查询响应项，对应 10-api-contract.md §4.4。
 *
 * @param ruleDefinitionId 规则定义 ID
 * @param code             规则编码
 * @param name             规则名称
 * @param status           规则状态（DRAFT / PUBLISHED / DISABLED）
 * @param currentVersion   当前版本 ID（未发布时为 null）
 * @param publishedAt      最后发布时间（未发布时为 null）
 */
public record RuleListItemVO(
        Long ruleDefinitionId,
        String code,
        String name,
        String status,
        Long currentVersion,
        LocalDateTime publishedAt
) {}
```

- [x] **Step 5: 创建 MybatisPlusConfig**

```java
// rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/MybatisPlusConfig.java
package com.sstlfsj.rule.config.internal;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** MyBatis-Plus 分页插件，供 rule-config-svc 使用；app 层已注册时自动跳过。 */
@Configuration
class MybatisPlusConfig {

    @Bean
    @ConditionalOnMissingBean(MybatisPlusInterceptor.class)
    MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
        return interceptor;
    }
}
```

- [x] **Step 6: 运行测试确认通过**

```bash
$MVN -pl rule-config-svc -am test \
  -Dtest='RuleListItemVOTest,MybatisPlusConfigTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

期望：PASS，2 test classes，4 tests。

- [x] **Step 7: 提交**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/RuleListItemVO.java \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/MybatisPlusConfig.java \
        rule-config-svc/src/test/java/com/sstlfsj/rule/config/api/dto/RuleListItemVOTest.java \
        rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/MybatisPlusConfigTest.java
git commit -m "feat(config): add RuleListItemVO + MybatisPlusConfig pagination interceptor"
```

---

## Task 2: ConfigService.listRules + ConfigServiceImpl 实现

**Files:**
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/ConfigService.java`
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/ConfigServiceImpl.java`
- Modify: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/service/ConfigServiceImplTest.java`

- [x] **Step 1: 写失败测试 — 在 ConfigServiceImplTest 追加 listRules 测试**

在 `ConfigServiceImplTest.java` 末尾追加（类体内，已有 `@ExtendWith(MockitoExtension.class)` + `@InjectMocks ConfigServiceImpl configService`）：

```java
// 新增 mock 字段（在类顶部的 @Mock 字段区域追加）
@Mock SceneMapper sceneMapper;

// 新增测试方法
@Test
void listRules_withSceneCodeAndStatus_filtersAndReturnsPage() {
    SceneDef scene = new SceneDef();
    scene.setId(5L);
    scene.setTenantId(1L);
    scene.setCode("risk.transfer");
    when(sceneMapper.selectOne(any())).thenReturn(scene);

    RuleDefinition rd = new RuleDefinition();
    rd.setId(10L);
    rd.setCode("rule.a");
    rd.setName("规则A");
    rd.setStatus("PUBLISHED");
    rd.setCurrentVersion(42L);
    rd.setPublishedAt(java.time.LocalDateTime.of(2026, 6, 1, 0, 0));

    com.baomidou.mybatisplus.extension.plugins.pagination.Page<RuleDefinition> mockPage =
            new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20, 1);
    mockPage.setRecords(java.util.List.of(rd));
    when(ruleDefinitionMapper.selectPage(any(), any())).thenReturn(mockPage);

    var result = configService.listRules("1", "risk.transfer", "PUBLISHED", 1, 20);

    assertThat(result.getTotal()).isEqualTo(1);
    assertThat(result.getRecords()).hasSize(1);
    var item = result.getRecords().get(0);
    assertThat(item.ruleDefinitionId()).isEqualTo(10L);
    assertThat(item.code()).isEqualTo("rule.a");
    assertThat(item.status()).isEqualTo("PUBLISHED");
    assertThat(item.currentVersion()).isEqualTo(42L);
    verify(sceneMapper).selectOne(any());
    verify(ruleDefinitionMapper).selectPage(any(), any());
}

@Test
void listRules_sceneNotFound_returnsEmptyPage() {
    when(sceneMapper.selectOne(any())).thenReturn(null);

    var result = configService.listRules("1", "nonexistent.scene", null, 1, 20);

    assertThat(result.getRecords()).isEmpty();
    assertThat(result.getTotal()).isEqualTo(0);
    verifyNoInteractions(ruleDefinitionMapper);
}

@Test
void listRules_noSceneCodeFilter_queriesAllRulesForTenant() {
    com.baomidou.mybatisplus.extension.plugins.pagination.Page<RuleDefinition> emptyPage =
            new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20, 0);
    emptyPage.setRecords(java.util.List.of());
    when(ruleDefinitionMapper.selectPage(any(), any())).thenReturn(emptyPage);

    var result = configService.listRules("1", null, null, 1, 20);

    assertThat(result.getRecords()).isEmpty();
    verify(ruleDefinitionMapper).selectPage(any(), any());
    verifyNoInteractions(sceneMapper);
}
```

- [x] **Step 2: 运行测试确认失败**

```bash
$MVN -pl rule-config-svc -am test \
  -Dtest='ConfigServiceImplTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

期望：FAIL，`listRules` 方法不存在。

- [x] **Step 3: 在 ConfigService 接口添加 listRules 方法**

将 `ConfigService.java` 替换为：

```java
// rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/ConfigService.java
package com.sstlfsj.rule.config.api.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sstlfsj.rule.config.api.dto.RuleListItemVO;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;

/** 规则定义生命周期管理：发布、禁用、查询。 */
public interface ConfigService {

    /**
     * 发布规则定义的最新草稿版本，使其进入激活状态。
     *
     * @param tenantId         规则所属租户 ID
     * @param ruleDefinitionId 待发布的规则定义 ID
     * @param actorId          触发发布的操作人 ID
     * @return 新激活的规则版本快照
     */
    RuleVersionSnapshot publish(String tenantId, Long ruleDefinitionId, String actorId);

    /**
     * 禁用规则定义及其当前激活版本。
     *
     * @param tenantId         规则所属租户 ID
     * @param ruleDefinitionId 待禁用的规则定义 ID
     * @param actorId          触发禁用的操作人 ID
     */
    void disable(String tenantId, Long ruleDefinitionId, String actorId);

    /**
     * 查询规则列表，支持按 sceneCode / status 过滤，结果分页返回。
     *
     * @param tenantId  租户 ID
     * @param sceneCode Scene 编码（null 或空字符串时不过滤）
     * @param status    规则状态过滤（null 时不过滤；DRAFT / PUBLISHED / DISABLED）
     * @param page      页码（从 1 开始）
     * @param size      每页条数
     * @return 分页规则列表
     */
    Page<RuleListItemVO> listRules(String tenantId, String sceneCode, String status, int page, int size);
}
```

- [x] **Step 4: 在 ConfigServiceImpl 添加 SceneMapper 注入 + listRules 实现**

将 `ConfigServiceImpl.java` 替换为：

```java
// rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/ConfigServiceImpl.java
package com.sstlfsj.rule.config.internal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sstlfsj.rule.config.api.dto.RuleListItemVO;
import com.sstlfsj.rule.config.api.service.ConfigService;
import com.sstlfsj.rule.config.internal.domain.AuditLog;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.publish.PublishService;
import com.sstlfsj.rule.config.internal.repository.AuditLogMapper;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** ConfigService 实现，委托 PublishService 执行发布流程。 */
@Service
@RequiredArgsConstructor
class ConfigServiceImpl implements ConfigService {

    private final PublishService publishService;
    private final RuleDefinitionMapper ruleDefinitionMapper;
    private final AuditLogMapper auditLogMapper;
    private final SceneMapper sceneMapper;

    @Override
    public RuleVersionSnapshot publish(String tenantId, Long ruleDefinitionId, String actorId) {
        return publishService.publish(Long.valueOf(tenantId), ruleDefinitionId, actorId);
    }

    @Override
    @Transactional
    public void disable(String tenantId, Long ruleDefinitionId, String actorId) {
        RuleDefinition rule = ruleDefinitionMapper.selectById(ruleDefinitionId);
        if (rule == null || !tenantId.equals(String.valueOf(rule.getTenantId()))) {
            throw new IllegalArgumentException("规则不存在: id=" + ruleDefinitionId);
        }
        rule.setStatus("DISABLED");
        ruleDefinitionMapper.updateById(rule);

        AuditLog log = new AuditLog();
        log.setTenantId(Long.valueOf(tenantId));
        log.setActor(actorId);
        log.setActorType("USER");
        log.setAction("DISABLE");
        log.setTargetType("rule_definition");
        log.setTargetId(ruleDefinitionId.toString());
        log.setOperatedAt(LocalDateTime.now());
        auditLogMapper.insert(log);
    }

    @Override
    public Page<RuleListItemVO> listRules(String tenantId, String sceneCode, String status, int page, int size) {
        // 按 sceneCode 解析 sceneId（未传时不过滤）
        Long sceneId = null;
        if (sceneCode != null && !sceneCode.isBlank()) {
            SceneDef scene = sceneMapper.selectOne(
                    new LambdaQueryWrapper<SceneDef>()
                            .eq(SceneDef::getTenantId, Long.valueOf(tenantId))
                            .eq(SceneDef::getCode, sceneCode)
            );
            if (scene == null) {
                return new Page<>(page, size);
            }
            sceneId = scene.getId();
        }

        LambdaQueryWrapper<RuleDefinition> wrapper = new LambdaQueryWrapper<RuleDefinition>()
                .eq(RuleDefinition::getTenantId, Long.valueOf(tenantId));
        if (sceneId != null) {
            wrapper.eq(RuleDefinition::getSceneId, sceneId);
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(RuleDefinition::getStatus, status);
        }
        wrapper.orderByDesc(RuleDefinition::getId);

        Page<RuleDefinition> rdPage = ruleDefinitionMapper.selectPage(new Page<>(page, size), wrapper);

        Page<RuleListItemVO> voPage = new Page<>(rdPage.getCurrent(), rdPage.getSize(), rdPage.getTotal());
        voPage.setRecords(rdPage.getRecords().stream()
                .map(rd -> new RuleListItemVO(
                        rd.getId(), rd.getCode(), rd.getName(),
                        rd.getStatus(), rd.getCurrentVersion(), rd.getPublishedAt()
                ))
                .toList());
        return voPage;
    }
}
```

- [x] **Step 5: 运行 ConfigServiceImplTest 确认通过**

```bash
$MVN -pl rule-config-svc -am test \
  -Dtest='ConfigServiceImplTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

期望：PASS，所有已有测试 + 3 个新测试全部通过。

- [x] **Step 6: 提交**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/ConfigService.java \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/ConfigServiceImpl.java \
        rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/service/ConfigServiceImplTest.java
git commit -m "feat(config): ConfigService.listRules + ConfigServiceImpl 分页查询实现"
```

---

## Task 3: RuleController GET /api/v1/rules 端点

**Files:**
- Modify: `rule-api/src/main/java/com/sstlfsj/rule/web/config/RuleController.java`
- Modify: `rule-api/src/test/java/com/sstlfsj/rule/web/config/RuleControllerTest.java`

- [x] **Step 1: 写失败测试 — 在 RuleControllerTest 追加 listRules 测试**

在 `RuleControllerTest.java` 末尾追加（类体内）：

```java
// 在 import 区域追加（如尚未有 get 静态导入）
// import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@Test
void listRules_returns200_withPageResult() throws Exception {
    com.baomidou.mybatisplus.extension.plugins.pagination.Page<
            com.sstlfsj.rule.config.api.dto.RuleListItemVO> page =
            new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20, 1);
    page.setRecords(java.util.List.of(
            new com.sstlfsj.rule.config.api.dto.RuleListItemVO(
                    10L, "rule.a", "规则A", "PUBLISHED", 42L,
                    java.time.LocalDateTime.of(2026, 6, 1, 0, 0))
    ));
    when(configService.listRules("t1", "risk.transfer", "PUBLISHED", 1, 20)).thenReturn(page);

    mockMvc.perform(get("/api/v1/rules")
                    .param("tenantId", "t1")
                    .param("sceneCode", "risk.transfer")
                    .param("status", "PUBLISHED"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.records[0].ruleDefinitionId").value(10))
            .andExpect(jsonPath("$.data.records[0].code").value("rule.a"))
            .andExpect(jsonPath("$.data.records[0].status").value("PUBLISHED"));

    verify(configService).listRules("t1", "risk.transfer", "PUBLISHED", 1, 20);
}

@Test
void listRules_withoutOptionalParams_usesDefaults() throws Exception {
    com.baomidou.mybatisplus.extension.plugins.pagination.Page<
            com.sstlfsj.rule.config.api.dto.RuleListItemVO> emptyPage =
            new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20, 0);
    emptyPage.setRecords(java.util.List.of());
    when(configService.listRules("t1", null, null, 1, 20)).thenReturn(emptyPage);

    mockMvc.perform(get("/api/v1/rules")
                    .param("tenantId", "t1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.total").value(0));

    verify(configService).listRules("t1", null, null, 1, 20);
}
```

- [x] **Step 2: 运行测试确认失败**

```bash
$MVN -pl rule-api -am test \
  -Dtest='RuleControllerTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

期望：FAIL，`listRules` 端点不存在，GET 请求返回 404 / NoHandlerFound。

- [x] **Step 3: 在 RuleController 添加 listRules 端点**

将 `RuleController.java` 替换为：

```java
// rule-api/src/main/java/com/sstlfsj/rule/web/config/RuleController.java
package com.sstlfsj.rule.web.config;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sstlfsj.rule.config.api.dto.RuleListItemVO;
import com.sstlfsj.rule.config.api.service.ConfigService;
import com.sstlfsj.rule.web.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 规则版本生命周期管理入口：发布、禁用、查询。 */
@RestController
@RequestMapping("/api/v1/rules")
public class RuleController {

    private final ConfigService configService;

    public RuleController(ConfigService configService) {
        this.configService = configService;
    }

    /**
     * POST /api/v1/rules — 创建规则草稿（v1 占位，v1.5 实现）。
     *
     * @param actorId 操作人
     * @return 501 Not Implemented
     */
    @PostMapping
    @ResponseStatus(HttpStatus.NOT_IMPLEMENTED)
    public ApiResponse<Void> createDraft(
            @RequestHeader("X-Actor-Id") String actorId) {
        return ApiResponse.error("NOT_IMPLEMENTED", "规则草稿创建接口将在 v1.5 实现");
    }

    /**
     * POST /api/v1/rules/{ruleId}/publish — 发布规则版本。
     *
     * @param ruleId   规则 ID
     * @param tenantId 租户
     * @param actorId  操作人
     * @return 发布后的 RuleVersionSnapshot
     */
    @PostMapping("/{ruleId}/publish")
    public ApiResponse<Object> publish(
            @PathVariable Long ruleId,
            @RequestParam String tenantId,
            @RequestHeader("X-Actor-Id") String actorId) {
        return ApiResponse.ok(configService.publish(tenantId, ruleId, actorId));
    }

    /**
     * POST /api/v1/rules/{ruleId}/disable — 禁用规则版本。
     *
     * @param ruleId   规则 ID
     * @param tenantId 租户
     * @param actorId  操作人
     * @return 空数据
     */
    @PostMapping("/{ruleId}/disable")
    public ApiResponse<Void> disable(
            @PathVariable Long ruleId,
            @RequestParam String tenantId,
            @RequestHeader("X-Actor-Id") String actorId) {
        configService.disable(tenantId, ruleId, actorId);
        return ApiResponse.ok(null);
    }

    /**
     * GET /api/v1/rules — 查询规则列表，支持 sceneCode / status 过滤与分页。
     *
     * @param tenantId  租户 ID
     * @param sceneCode Scene 编码（可选）
     * @param status    规则状态（可选；DRAFT / PUBLISHED / DISABLED）
     * @param page      页码，默认 1
     * @param size      每页条数，默认 20
     * @return 分页规则列表
     */
    @GetMapping
    public ApiResponse<Page<RuleListItemVO>> listRules(
            @RequestParam String tenantId,
            @RequestParam(required = false) String sceneCode,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(configService.listRules(tenantId, sceneCode, status, page, size));
    }
}
```

- [x] **Step 4: 运行 RuleControllerTest 确认通过**

```bash
$MVN -pl rule-api -am test \
  -Dtest='RuleControllerTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

期望：PASS，所有已有测试 + 2 个新测试全部通过（共 6 tests）。

- [x] **Step 5: 提交**

```bash
git add rule-api/src/main/java/com/sstlfsj/rule/web/config/RuleController.java \
        rule-api/src/test/java/com/sstlfsj/rule/web/config/RuleControllerTest.java
git commit -m "feat(api): GET /api/v1/rules 规则列表分页查询端点"
```

---

## Task 4: 全量测试验证

**Files:** 无修改，仅验证。

- [x] **Step 1: 运行全量测试**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
$MVN test
```

期望：BUILD SUCCESS，所有模块全部通过，无 FAIL / ERROR。

- [x] **Step 2: 确认新增测试数量**

新增测试：
- `RuleListItemVOTest` — 2 tests
- `MybatisPlusConfigTest` — 2 tests
- `ConfigServiceImplTest`（新增 3 条）— 合计 5 tests
- `RuleControllerTest`（新增 2 条）— 合计 6 tests

- [x] **Step 3: 更新 08-evolution.md §2.18 状态**

在 `docs/08-evolution.md` 的 §2.18 末尾追加（原"迁移成本"行之后）：

```
- **实现状态（v2）**：已实现，commit 见 `feat(api): GET /api/v1/rules 规则列表分页查询端点`。
```

并更新 §一 文档状态表，将"§二 演进锚点"状态保持 ✅。

- [x] **Step 4: 提交文档更新**

```bash
git add docs/08-evolution.md
git commit -m "docs: 标记 §2.18 规则列表查询 API 已实现"
```
